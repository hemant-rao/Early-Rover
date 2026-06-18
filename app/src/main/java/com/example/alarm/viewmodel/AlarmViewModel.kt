package com.example.alarm.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.alarm.SunCalculator
import com.example.alarm.data.Alarm
import com.example.alarm.data.AlarmRepository
import com.example.alarm.data.AppDatabase
import com.example.alarm.data.SunAlarmResolver
import com.example.alarm.location.CityInfo
import com.example.alarm.location.LocationHelper
import com.example.alarm.data.TravelAlarm
import com.example.alarm.location.TravelTrackingService
import com.example.alarm.scheduling.AlarmScheduler
import com.example.alarm.scheduling.AlarmService
import com.example.alarm.weather.WeatherInfo
import com.example.alarm.weather.WeatherRepository
import com.example.alarm.weather.DetailedWeatherInfo
import com.example.alarm.weather.AirQualityInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.util.Calendar
import kotlin.math.abs

data class RingingAlarmState(
    val id: Int,
    val title: String,
    val type: String,
    val snoozeEnabled: Boolean = true,
    val isExactAlso: Boolean = false
)

data class AlarmProfile(
    val id: String,
    val name: String,
    val sunriseOffset: Int,
    val sunsetOffset: Int,
    val vibrationPattern: String
)

enum class ThemeMode { LIGHT, DARK, AUTO }

class AlarmViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsPrefs = application.getSharedPreferences("sun_alarm_settings_prefs", Context.MODE_PRIVATE)

    private val database = AppDatabase.getDatabase(application)
    private val repository = AlarmRepository(database.alarmDao(), database.travelAlarmDao())
    private val scheduler = AlarmScheduler(application)
    private val locationHelper = LocationHelper(application)
    private var searchJob: kotlinx.coroutines.Job? = null

    // Declared ABOVE the init block: init transitively writes these (via recomputeSunTimes ->
    // refreshWeather / recalculateAndScheduleActiveAlarms). Kotlin runs property initializers and
    // init blocks strictly top-to-bottom, so declaring them after init would reset the Job handles
    // assigned during the initial pass back to null, making that first fetch/recalc un-cancellable.
    // Weather refetch throttle: skip if we recently fetched for ~the same coordinates.
    private var lastWeatherFetchMs = 0L
    private var lastWeatherLat = Double.NaN
    private var lastWeatherLng = Double.NaN
    private var weatherJob: Job? = null
    private var recalcJob: Job? = null

    // Guards async location callbacks from mutating StateFlows after the ViewModel is destroyed.
    @Volatile private var cleared = false

    override fun onCleared() {
        cleared = true
        locationHelper.cancelLocationRequest()
        weatherJob?.cancel()
        recalcJob?.cancel()
        super.onCleared()
    }

    // UI state flows
    val allAlarms: StateFlow<List<Alarm>> = repository.allAlarms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    private val _latitude = MutableStateFlow(locationHelper.getSavedLatitude())
    val latitude: StateFlow<Double> = _latitude.asStateFlow()

    private val _longitude = MutableStateFlow(locationHelper.getSavedLongitude())
    val longitude: StateFlow<Double> = _longitude.asStateFlow()

    private val _locationName = MutableStateFlow(locationHelper.getSavedLocationName())
    val locationName: StateFlow<String> = _locationName.asStateFlow()

    val alarmsForCurrentLocation: StateFlow<List<Alarm>> = combine(
        repository.allAlarms,
        _locationName,
        _latitude,
        _longitude
    ) { alarms, name, lat, lng ->
        alarms.filter { a ->
            // Unbound legacy alarms are backfilled at init (see backfillUnboundAlarms), so we no
            // longer show empty-location alarms in EVERY city. Match by bound name or proximity using
            // the same epsilon as auto-detect dedup, so "same city" means one consistent thing.
            !a.hasLocation() ||
                a.locationName == name ||
                (abs(a.latitude - lat) < SAME_CITY_EPSILON && abs(a.longitude - lng) < SAME_CITY_EPSILON)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _timezoneOffset = MutableStateFlow(locationHelper.getSavedTimezoneOffset())
    val timezoneOffset: StateFlow<Double> = _timezoneOffset.asStateFlow()

    // Calculated Sunrise/Sunset
    private val _sunriseTime = MutableStateFlow(LocalTime.of(6, 0))
    val sunriseTime: StateFlow<LocalTime> = _sunriseTime.asStateFlow()

    private val _sunsetTime = MutableStateFlow(LocalTime.of(18, 0))
    val sunsetTime: StateFlow<LocalTime> = _sunsetTime.asStateFlow()

    private val _tomorrowSunriseTime = MutableStateFlow(LocalTime.of(6, 0))
    val tomorrowSunriseTime: StateFlow<LocalTime> = _tomorrowSunriseTime.asStateFlow()

    private val _tomorrowSunsetTime = MutableStateFlow(LocalTime.of(18, 0))
    val tomorrowSunsetTime: StateFlow<LocalTime> = _tomorrowSunsetTime.asStateFlow()

    // Current location weather (drives the animated dashboard background)
    private val _weather = MutableStateFlow<WeatherInfo?>(null)
    val weather: StateFlow<WeatherInfo?> = _weather.asStateFlow()

    private val _detailedWeather = MutableStateFlow<DetailedWeatherInfo?>(null)
    val detailedWeather: StateFlow<DetailedWeatherInfo?> = _detailedWeather.asStateFlow()

    private val _airQuality = MutableStateFlow<AirQualityInfo?>(null)
    val airQuality: StateFlow<AirQualityInfo?> = _airQuality.asStateFlow()

    private val _isWeatherLoading = MutableStateFlow(false)
    val isWeatherLoading: StateFlow<Boolean> = _isWeatherLoading.asStateFlow()

    private val _isDetectingLocation = MutableStateFlow(false)
    val isDetectingLocation: StateFlow<Boolean> = _isDetectingLocation.asStateFlow()

    // Upcoming Hero alarm info
    private val _nextUpcomingAlarm = MutableStateFlow<Alarm?>(null)
    val nextUpcomingAlarm: StateFlow<Alarm?> = _nextUpcomingAlarm.asStateFlow()

    private val _nextUpcomingAlarmTimeFormatted = MutableStateFlow("None")
    val nextUpcomingAlarmTimeFormatted: StateFlow<String> = _nextUpcomingAlarmTimeFormatted.asStateFlow()

    // Alarm edit/create scratchpad state
    var editingAlarm = MutableStateFlow<Alarm?>(null)

    // Ringing state overlay
    private val _ringingAlarm = MutableStateFlow<RingingAlarmState?>(null)
    val ringingAlarm: StateFlow<RingingAlarmState?> = _ringingAlarm.asStateFlow()

    // Location search result listing
    private val _searchResults = MutableStateFlow<List<CityInfo>>(emptyList())
    val searchResults: StateFlow<List<CityInfo>> = _searchResults.asStateFlow()

    // Saved multi-cities states
    private val _savedCities = MutableStateFlow<List<CityInfo>>(emptyList())
    val savedCities: StateFlow<List<CityInfo>> = _savedCities.asStateFlow()

    // Feature toggles
    private val FEATURE_WEATHER = "feature_weather"
    private val FEATURE_SOLAR_TRENDS = "feature_solar_trends"
    private val FEATURE_TRAVEL = "feature_travel"
    private val FEATURE_LOCATION = "feature_location"

    private val _isWeatherEnabled = MutableStateFlow(settingsPrefs.getBoolean(FEATURE_WEATHER, true))
    val isWeatherEnabled: StateFlow<Boolean> = _isWeatherEnabled.asStateFlow()

    private val _isSolarTrendsEnabled = MutableStateFlow(settingsPrefs.getBoolean(FEATURE_SOLAR_TRENDS, true))
    val isSolarTrendsEnabled: StateFlow<Boolean> = _isSolarTrendsEnabled.asStateFlow()

    private val _isTravelEnabled = MutableStateFlow(settingsPrefs.getBoolean(FEATURE_TRAVEL, true))
    val isTravelEnabled: StateFlow<Boolean> = _isTravelEnabled.asStateFlow()

    private val _isLocationEnabled = MutableStateFlow(settingsPrefs.getBoolean(FEATURE_LOCATION, true))
    val isLocationEnabled: StateFlow<Boolean> = _isLocationEnabled.asStateFlow()

    // Ola Maps API key — admin-configurable (Settings > Advanced), stored alongside other
    // settings so the map / Places / Directions stay fully dynamic (no hardcoded key).
    private val OLA_MAPS_API_KEY = "ola_maps_api_key"
    private val _olaMapsApiKey = MutableStateFlow(settingsPrefs.getString(OLA_MAPS_API_KEY, "") ?: "")
    val olaMapsApiKey: StateFlow<String> = _olaMapsApiKey.asStateFlow()

    fun setOlaMapsApiKey(key: String) {
        val trimmed = key.trim()
        _olaMapsApiKey.value = trimmed
        settingsPrefs.edit().putString(OLA_MAPS_API_KEY, trimmed).apply()
    }

    fun setFeatureEnabled(key: String, enabled: Boolean) {
        when(key) {
            FEATURE_WEATHER -> {
                _isWeatherEnabled.value = enabled
                settingsPrefs.edit().putBoolean(FEATURE_WEATHER, enabled).apply()
            }
            FEATURE_SOLAR_TRENDS -> {
                _isSolarTrendsEnabled.value = enabled
                settingsPrefs.edit().putBoolean(FEATURE_SOLAR_TRENDS, enabled).apply()
            }
            FEATURE_TRAVEL -> {
                _isTravelEnabled.value = enabled
                settingsPrefs.edit().putBoolean(FEATURE_TRAVEL, enabled).apply()
            }
            FEATURE_LOCATION -> {
                _isLocationEnabled.value = enabled
                settingsPrefs.edit().putBoolean(FEATURE_LOCATION, enabled).apply()
            }
        }
    }

    // Alarm Profiles State Management
    private val defaultAlarmProfiles = listOf(
        AlarmProfile("work", "Work Week", -15, 15, "Steady"),
        AlarmProfile("weekend", "Weekend", 30, 30, "Heartbeat"),
        AlarmProfile("mindful", "Yoga & Zen", -30, 0, "Quick Pulses")
    )

    private val _activeProfileId = MutableStateFlow(settingsPrefs.getString("active_profile_id", "work") ?: "work")
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    private val _alarmProfiles = MutableStateFlow<List<AlarmProfile>>(emptyList())
    val alarmProfiles: StateFlow<List<AlarmProfile>> = _alarmProfiles.asStateFlow()

    init {
        _alarmProfiles.value = loadAlarmProfilesFromPrefs()
        // Ensure defaults are saved if preferences are empty
        if (settingsPrefs.getString("saved_alarm_profiles", null) == null) {
            saveAlarmProfilesToPrefs(defaultAlarmProfiles)
        }
    }

    private fun loadAlarmProfilesFromPrefs(): List<AlarmProfile> {
        val serialized = settingsPrefs.getString("saved_alarm_profiles", null) ?: return defaultAlarmProfiles
        if (serialized.isEmpty()) return defaultAlarmProfiles
        
        return try {
            serialized.split("|").mapNotNull { block ->
                val parts = block.split(";")
                if (parts.size >= 5) {
                    AlarmProfile(
                        id = parts[0],
                        name = parts[1].replace("%3B", ";").replace("%7C", "|"),
                        sunriseOffset = parts[2].toIntOrNull() ?: 0,
                        sunsetOffset = parts[3].toIntOrNull() ?: 0,
                        vibrationPattern = parts[4]
                    )
                } else null
            }
        } catch (e: Exception) {
            defaultAlarmProfiles
        }
    }

    private fun saveAlarmProfilesToPrefs(list: List<AlarmProfile>) {
        val serialized = list.joinToString("|") { profile ->
            val safeName = profile.name.replace(";", "%3B").replace("|", "%7C")
            "${profile.id};${safeName};${profile.sunriseOffset};${profile.sunsetOffset};${profile.vibrationPattern}"
        }
        settingsPrefs.edit().putString("saved_alarm_profiles", serialized).apply()
        _alarmProfiles.value = list
    }

    fun selectProfile(id: String) {
        val profile = _alarmProfiles.value.firstOrNull { it.id == id } ?: return
        _activeProfileId.value = id
        settingsPrefs.edit()
            .putString("active_profile_id", id)
            .putString("active_vibration_pattern", profile.vibrationPattern)
            .apply()
        
        applyProfileToAlarms(profile)
    }

    fun saveProfile(profile: AlarmProfile) {
        val currentList = _alarmProfiles.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == profile.id }
        if (index != -1) {
            currentList[index] = profile
        } else {
            currentList.add(profile)
        }
        saveAlarmProfilesToPrefs(currentList)
        
        // If saved profile is active, re-select to apply offsets immediately
        if (_activeProfileId.value == profile.id) {
            selectProfile(profile.id)
        }
    }

    fun deleteProfile(id: String) {
        if (id == _activeProfileId.value) return
        val currentList = _alarmProfiles.value.filter { it.id != id }
        saveAlarmProfilesToPrefs(currentList)
    }

    fun applyProfileToAlarms(profile: AlarmProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = allAlarms.value
            all.forEach { alarm ->
                var updated: Alarm? = null
                if (alarm.alarmType == "SUNRISE" && alarm.offsetMinutes != profile.sunriseOffset) {
                    updated = alarm.copy(offsetMinutes = profile.sunriseOffset, updatedAt = System.currentTimeMillis())
                } else if (alarm.alarmType == "SUNSET" && alarm.offsetMinutes != profile.sunsetOffset) {
                    updated = alarm.copy(offsetMinutes = profile.sunsetOffset, updatedAt = System.currentTimeMillis())
                }
                
                if (updated != null) {
                    val loc = if (updated.hasLocation()) {
                        SunAlarmResolver.Location(
                            latitude = updated.latitude,
                            longitude = updated.longitude,
                            timezoneOffset = updated.timezoneOffset,
                            name = updated.locationName
                        )
                    } else {
                        currentActiveLocation()
                    }
                    val recalibrated = SunAlarmResolver.recalibrate(updated, loc)
                    repository.updateAlarm(recalibrated)
                    scheduler.schedule(recalibrated)
                }
            }
        }
    }

    // Adaptive theme mode. Defaults derive from the legacy "dark_theme" flag for back-compat.
    private val _themeMode = MutableStateFlow(
        run {
            val stored = settingsPrefs.getString("theme_mode", null)
            if (stored != null) {
                runCatching { ThemeMode.valueOf(stored) }.getOrDefault(
                    if (settingsPrefs.getBoolean("dark_theme", true)) ThemeMode.DARK else ThemeMode.LIGHT
                )
            } else {
                if (settingsPrefs.getBoolean("dark_theme", true)) ThemeMode.DARK else ThemeMode.LIGHT
            }
        }
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(m: ThemeMode) {
        _themeMode.value = m
        // Drop the now-orphaned legacy "dark_theme" key so its stale value can't linger and drift
        // from the new "theme_mode" source of truth.
        settingsPrefs.edit().putString("theme_mode", m.name).remove("dark_theme").apply()
    }

    /** Resolves the active theme: AUTO follows daylight at the location; DARK/LIGHT are explicit. */
    fun isEffectiveDark(mode: ThemeMode, isDayAtLocation: Boolean): Boolean = when (mode) {
        ThemeMode.AUTO -> !isDayAtLocation
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    private val _defaultSnoozeMinutes = MutableStateFlow(settingsPrefs.getInt("default_snooze", 5))
    val defaultSnoozeMinutes: StateFlow<Int> = _defaultSnoozeMinutes.asStateFlow()

    // BCP-47 language tag (e.g. "en", "hi", "hi-IN"). LocaleHelper is the canonical store.
    val currentLanguage = MutableStateFlow(
        com.example.alarm.util.LocaleHelper.getPersistedTag(application)
    )

    // True while the activity is being recreated for a language switch. Survives recreate()
    // (the ViewModelStore is retained), so MainActivity can keep a loader on top of the
    // black recreate gap and clear it once the new UI is ready.
    private val _isSwitchingLanguage = MutableStateFlow(false)
    val isSwitchingLanguage: StateFlow<Boolean> = _isSwitchingLanguage.asStateFlow()

    fun clearLanguageSwitching() {
        _isSwitchingLanguage.value = false
    }

    /**
     * Sets the UI language. Persists via [LocaleHelper], then recreates the activity so
     * attachBaseContext re-reads the locale (LocalConfiguration updates). The ViewModel
     * survives recreate(); translate() call sites are non-reactive but are refreshed because
     * the entire activity is recreated on a language switch (manual recreate() below API 33;
     * LocaleManager auto-recreate on 33+). currentLanguage is also a StateFlow read reactively
     * inside translate(), so composables that read it recompose without a full recreate.
     */
    fun setAppLanguage(tag: String, activity: android.app.Activity? = null) {
        if (tag == currentLanguage.value) return
        currentLanguage.value = tag
        // Raise the loader before the recreate so MainActivity covers the native black gap.
        _isSwitchingLanguage.value = true
        com.example.alarm.util.LocaleHelper.persist(getApplication(), tag)
        // On API 33+ persist() sets the framework LocaleManager, which itself recreates the
        // activity; calling recreate() again would rebuild twice. Only recreate manually below 33.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            activity?.recreate()
        }
    }

    /** Device languages for the Settings dropdown (en + hi always included). */
    fun availableLanguages(): List<com.example.alarm.util.LocaleHelper.LangOption> =
        com.example.alarm.util.LocaleHelper.availableLanguages(getApplication())

    fun translate(englishText: String): String {
        // startsWith so tags like "hi-IN" still resolve to the Hindi dictionary.
        if (currentLanguage.value.startsWith("hi")) {
            return translationsHindi[englishText] ?: englishText
        }
        return englishText
    }

    private val translationsHindi = mapOf(
        "CURRENT LOCATION" to "वर्तमान स्थान",
        "Active City:" to "सक्रिय शहर:",
        "Coordinates" to "स्थान निर्देशांक",
        "Coordinates Details" to "निर्देशांक विवरण",
        "Location Coordinates" to "स्थान संदर्भ",
        "Schedule Alarm" to "अलार्म शेड्यूल करें",
        "Edit Alarm" to "अलार्म बदलें",
        "Standard Clock Alarm" to "मानक अलार्म घड़ी",
        "Fires at an exact manually set clock time." to "दिए गए समय पर सटीक अलार्म बजाएं।",
        "Sunrise Alarm" to "सूर्योदय अलार्म",
        "Sunset Alarm" to "सूर्यास्त अलार्म",
        "Fires relative to today's local sunrise." to "आज के सूर्योदय के आधार पर बजेगा।",
        "Fires relative to today's local sunset." to "आज के सूर्यास्त के आधार पर बजेगा।",
        "Repeat Days" to "दोहराने के दिन",
        "Fires weekly during selected days" to "चयनित दिनों में साप्ताहिक अलार्म दोहराएं",
        "Alarm Identifier" to "अलार्म का नाम / शीर्षक",
        "e.g. Sunrise Tracker, Yoga Call..." to "जैसे: योग क्लास, सूर्योदय देखना...",
        "Vibration" to "वाइब्रेशन (कंपन)",
        "Vibrate during alarm trigger sequence" to "अलार्म बजने पर फ़ोन वाइब्रेट करें",
        "Snooze Awake" to "अलार्म फिर से बजने का समय (स्नूज़)",
        "Snooze pause duration" to "स्नूज़ का समय",
        "mins" to "मिनट",
        "Confirm Schedule Settings" to "अलार्म सेव करें",
        "SUNSET" to "सूर्यास्त",
        "SUNRISE" to "सूर्योदय",
        "CUSTOM" to "सामान्य घड़ी",
        "DASH" to "होम",
        "LOCATION" to "स्थान सेटिंग्स",
        "SETTINGS" to "सेटिंग्स",
        "Settings" to "सेटिंग्स",
        "Preferences & Appearance" to "सेटिंग्स और थीम",
        "Dark Mode" to "डार्क मोड",
        "Light" to "लाइट",
        "Dark" to "डार्क",
        "Auto" to "ऑटो",
        "Render dark celestial color profiles" to "गहरी रंग थीम चालू करें",
        "Applying language…" to "भाषा बदल रही है…",
        // Full-screen alarm ring screen
        "SOLARIS ALERT TRIGGERED" to "अलार्म बज रहा है",
        "SECURE ARRIVAL SENTRY DETECTED" to "मंज़िल आ गई है",
        "Dynamic Daylight Call" to "दिन की शुरुआत",
        "Solar Sunrise Synchronized Alarm" to "सूर्योदय का अलार्म",
        "Solar Sunset Synchronized Alarm" to "सूर्यास्त का अलार्म",
        "Wayfarer Transit Arrival Security" to "यात्रा आगमन अलार्म",
        "Standard Trigger Clock" to "सामान्य अलार्म घड़ी",
        "DISMISS ALARM" to "अलार्म बंद करें",
        "ARRIVED - DISMISS ALARM" to "पहुँच गए - अलार्म बंद करें",
        "SNOOZE WAKE" to "स्नूज़ करें",
        // Dashboard hero, travel cards, repeat presets, and skip/turn-off dialog
        "Wake with the Sun" to "सूरज के साथ जागें",
        "Alarms synced to sunrise & sunset at your exact location." to "आपके इलाके में सूरज निकलने और ढलने के हिसाब से बजेगा।",
        "Journey Active" to "यात्रा चालू है",
        "Tracking" to "लोकेशन ट्रैक हो रही है",
        "Add Travel Alarm" to "यात्रा का अलार्म लगाएँ",
        "Get woken when you near your destination." to "अपनी मंज़िल के पास पहुँचने पर जागें।",
        "Turn off this alarm?" to "यह अलार्म बंद करें?",
        "This alarm repeats. Skip just the next occurrence, or turn it off completely?" to "यह अलार्म हर रोज़ बजता है। क्या आप इसे केवल अगली बार के लिए छोड़ना चाहते हैं, या पूरी तरह बंद करना चाहते हैं?",
        "Skip today only" to "केवल आज छोड़ें",
        "Turn off completely" to "पूरी तरह बंद करें",
        "Cancel" to "रद्द करें",
        "Once" to "एक बार",
        "Mon-Fri" to "सोम-शुक्र",
        "Custom" to "कस्टम",
        "Sunrise" to "सूर्योदय",
        "3D Solar Arc progression" to "3D सौर आर्क प्रगति",
        "Progression with selected active alarm indicators" to "सक्रिय अलार्म संकेतकों के साथ दिन की प्रगति",
        "Alarms" to "अलार्म",
        "Sunset" to "सूर्यास्त",
        "Add Standard Alarm" to "सामान्य अलार्म जोड़ें",
        "Use the Sunrise/Sunset cards above or the Add Standard Alarm button to schedule alarms." to "अलार्म सेट करने के लिए सूर्योदय/सूर्यास्त या सामान्य अलार्म बटन का उपयोग करें।",
        "Type city name..." to "शहर का नाम लिखें...",
        "ACTIVE LOCATIONS HUB" to "लोकेशन सेटिंग्स",
        "Default Snooze Duration" to "डिफ़ॉल्ट स्नूज़ समय",
        "Select Language" to "भाषा चुनें (Language)",
        "Only English & Hindi are fully translated; other languages change date/number format only." to "केवल अंग्रेज़ी और हिंदी में पूरा अनुवाद है; बाकी सिर्फ़ तारीख/नंबर का तरीक़ा बदलेंगी।",
        "Where are you? Type city name here..." to "आप कहाँ हैं? अपने शहर का नाम लिखें...",
        "Search city (e.g. Reykjavik, London, Tokyo...)" to "शहर खोजें (जैसे: दिल्ली, मुम्बई...)",
        "Use My Current Phone Location (GPS)" to "मेरे फ़ोन का वर्तमान स्थान (जीपीएस) इस्तेमाल करें",
        "Location permission denied. Enable it in Settings or search a city below." to "लोकेशन की अनुमति नहीं मिली। इसे सेटिंग्स से चालू करें या नीचे शहर का नाम खोजें।",
        "Instantly adjust to where you are standing." to "ऑटोमैटिक आपकी मौजूद जगह ले लेगा।",
        "Why set location?" to "लोकेशन चुनना क्यों ज़रूरी है?",
        "We calculate the exact sunrise and sunset times automatically for your location, even without internet!" to "हम बिना इंटरनेट के भी आपके स्थान के हिसाब से सूर्योदय और सूर्यास्त का बिल्कुल सही समय निकालते हैं!",
        "Trigger exactly at Sunrise" to "ठीक सूर्योदय के समय अलार्म बजाएँ",
        "Trigger exactly at Sunset" to "ठीक सूर्यास्त के समय अलार्म बजाएँ",
        "Fires at the precise moment of astronomical rise/set" to "सटीक उदय या अस्त के समय बजेगा",
        "Configure Clock Time" to "अलार्म का समय सेट करें",
        "Based on location, triggers today at" to "लोकेशन के हिसाब से, आज बजेगा: ",
        "CURRENT COORDINATES" to "वर्तमान स्थान जानकारी",
        "Manual Search Lookup" to "मैन्युअल रूप से शहर खोजें",
        "Offline Astronomical Security" to "ऑफ़लाइन अलर्ट सिस्टम",
        "GPS Location Detected" to "जीपीएस द्वारा स्थान मिला",
        "Next Alarm" to "अगला अलार्म",
        "No Alarms Scheduled" to "कोई अलार्म नहीं लगा है",
        "SOLARIS LIVE" to "अर्ली रोवर लाइव",
        "Hour" to "घंटा",
        "Minute" to "मिनट",
        "Go back" to "वापस जाएं",
        "Save" to "सेव करें",
        "Delete" to "डिलीट करें",
        "Active" to "चालू",
        "Inactive" to "बंद",
        "None" to "कोई नहीं",
        "Trigger Offset" to "अलार्म आगे/पीछे करें (अगर चाहें)",
        "Before" to "पहले",
        "After" to "बाद में",
        "At Event" to "ठीक समय पर",
        "AM" to "सुबह (AM)",
        "PM" to "शाम (PM)",
        "Conch Sound" to "शंख ध्वनि",
        "Premium Sound" to "प्रीमियम ध्वनि",
        "Ringtone" to "रिंगटोन",
        "Select Alarm Tone" to "रिंगटोन चुनें",
        "Alarm Tone" to "रिंगटोन",
        "Custom Audio File" to "अन्य वॉइस / ऑडियो फ़ाइल",
        "Device storage / voice recording" to "फ़ोन स्टोरेज / वॉइस रिकॉर्डिंग",
        "Default Alarm" to "डिफ़ॉल्ट अलार्म टोन",
        "Default Alarm Sound" to "फ़ोन की डिफ़ॉल्ट अलार्म ध्वनि",
        // Settings reliability / about section (wrapped in translate() in SettingsScreen).
        "ALARM SYSTEM RELIABILITY GUIDES" to "अलार्म काम करने के लिए ज़रूरी सेटिंग्स",
        "Battery Optimizations Exclusions" to "बैटरी ऑप्टिमाइज़ेशन बंद करें",
        "To guarantee the alarm triggers precisely on-time when the physical screen is off, newer Android versions require excluding the app from system-level battery optimizations." to "फ़ोन की स्क्रीन बंद होने पर भी अलार्म ठीक समय पर बजे, इसके लिए एंड्रॉयड सेटिंग्स से इस ऐप को बैटरी ऑप्टिमाइज़ेशन से हटाना ज़रूरी है।",
        "Go to system App Info -> Battery -> and select 'Unrestricted' for completely uninterrupted wake alarm service." to "ऐप इन्फो (App Info) -> बैटरी पर जाएं और 'अनरेस्ट्रिक्टेड (Unrestricted)' चुनें।",
        "System Exact Alarm Permission" to "सटीक अलार्म की अनुमति",
        "Solari uses System Alarm Clock info APIs which list upcoming alerts on your lockscreen and bypass Silent / Do Not Disturb boundaries." to "Early Rover सिस्टम अलार्म की परमिशन लेता है ताकि अलार्म 'डू नॉट डिस्टर्ब' और 'साइलेंट' मोड पर भी ज़ोर से बज सके।",
        "If scheduled warnings seem deactivated, ensure 'Alarms & Reminders' permission is granted in the device settings panel." to "अगर अलार्म नहीं बज रहा, तो फ़ोन की सेटिंग्स में जाकर पक्का करें कि इस ऐप के पास 'अलार्म और रिमाइंडर (Alarms & Reminders)' की अनुमति चालू है।",
        "SOLARIS ALARM COMPASS" to "EARLY ROVER",
        "Version 1.0.0 (Concept Edition)" to "वर्ज़न 1.0.0",
        "Developed using modern Jetpack Compose, Room reactive databases, Alarm Clock APIs, and hardware-accelerated OpenGL ES 2.0 visualization." to "Early Rover (अर्ली रोवर) आपको समय पर जगाने और किसी भी सफर के दौरान सुरक्षित रखने में मदद करता है।"
    )

    // --- TRAVEL / DESTINATION ARRIVAL ALARM SYSTEMS (Declared early to avoid initialization NPE in init) ---
    val allTravelAlarms: StateFlow<List<TravelAlarm>> = repository.allTravelAlarms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Companion tracking fields bound directly to services
    val isTravelTrackingActive = TravelTrackingService.isTracking
    val travelCurrentLocation = TravelTrackingService.currentLocation
    val travelStartLocation = TravelTrackingService.startLocation
    val travelTotalTripDistance = TravelTrackingService.totalTripDistanceKm
    val travelNearestAlarm = TravelTrackingService.nearestAlarm
    val travelDistanceToNearest = TravelTrackingService.distanceToNearestKm
    val travelStatusMsg = TravelTrackingService.statusMessage
    val travelCurrentSpeed = TravelTrackingService.currentSpeedKmh

    init {
        // Correct any Indian coordinate offsets or other fractional zones automatically to prevent discrepancies.
        val savedLat = locationHelper.getSavedLatitude()
        val savedLng = locationHelper.getSavedLongitude()
        val originalTz = locationHelper.getSavedTimezoneOffset()
        val savedName = locationHelper.getSavedLocationName()
        val sanitizedTz = LocationHelper.sanitizeOffset(savedLat, savedLng, originalTz, savedName)
        if (originalTz != sanitizedTz) {
            _timezoneOffset.value = sanitizedTz
            locationHelper.saveLocation(savedLat, savedLng, sanitizedTz, savedName)
        }

        // Initialize saved location cities database FIRST so recomputeSunTimes can rely on exact city coordinates
        val savedStr = settingsPrefs.getString("saved_cities_list", "") ?: ""
        if (savedStr.isEmpty()) {
            val defaultCity = CityInfo(
                "Detecting Location...",
                "...",
                locationHelper.getSavedLatitude(),
                locationHelper.getSavedLongitude(),
                _timezoneOffset.value
            )
            val initialList = listOf(defaultCity)
            _savedCities.value = initialList
            saveCitiesToPrefs(initialList)
        } else {
            val list = mutableListOf<CityInfo>()
            savedStr.split(";").forEach { item ->
                if (item.isNotEmpty()) {
                    val parts = item.split("|")
                    if (parts.size >= 5) {
                        val lat = parts[2].toDoubleOrNull()
                        val lng = parts[3].toDoubleOrNull()
                        // Drop a record whose coordinates can't be parsed instead of silently pinning
                        // it to New York (would give wrong sun times/weather). Fields are sanitized of
                        // the '|'/';' delimiters at write time so coordinates land in the right token.
                        if (lat != null && lng != null) {
                            val rawOffset = parts[4].toDoubleOrNull() ?: -5.0
                            val offset = LocationHelper.sanitizeOffset(lat, lng, rawOffset, parts[1])
                            list.add(
                                CityInfo(
                                    parts[0],
                                    parts[1],
                                    lat,
                                    lng,
                                    offset
                                )
                            )
                        }
                    }
                }
            }
            _savedCities.value = list
            saveCitiesToPrefs(list) // Save back the corrected cities list
        }

        observeAlarmsForUpcoming()
        // Serialize the one-time backfill BEFORE the recalc pass so the same legacy/unbound rows are
        // never written by two concurrent IO coroutines. Backfill binds the active location onto every
        // unbound alarm; recompute's recalc then runs as the FINAL write, recalibrating hour/minute on
        // any now-bound active sun alarm (backfill only copies coordinates, not the recalibrated time).
        viewModelScope.launch(Dispatchers.IO) {
            backfillUnboundAlarms()
            recomputeSunTimes()
        }

        // Auto-stop travel tracking service if there are no travel alarms remaining
        viewModelScope.launch {
            allTravelAlarms.collect { list ->
                if (list.isEmpty() && isTravelTrackingActive.value) {
                    stopTravelTracking()
                }
            }
        }

        if (savedStr.isEmpty()) {
            triggerAutoLocationDetect()
        }
    }

    fun refreshWeather(force: Boolean = false) {
        // Confine the throttle read/decision, the display-state reset, and weatherJob (re)assignment
        // to a single thread (Main.immediate). recomputeSunTimes() reaches here both from the init
        // coroutine on Dispatchers.IO and from setManualCitySelection()/the auto-detect callback on
        // the main thread; serializing the read-decide-assign sequence means weatherJob?.cancel()
        // reliably targets the in-flight job and the throttle fields can't be torn between threads.
        // The actual network fetch still runs on Dispatchers.IO via the inner launch below.
        viewModelScope.launch(Dispatchers.Main.immediate) refresh@{
        val lat = _latitude.value
        val lng = _longitude.value
        val now = System.currentTimeMillis()
        // On a genuine location change clear the previous city's display state so a failed/throttled
        // refetch can't leave the OLD city's temperature/condition/AQI showing under the NEW city's
        // name. The UI then renders its loading/empty state instead of mislabeled stale data. Skip
        // this when the coords match the last fetch (a forced refresh of the same city keeps data).
        if (lastWeatherFetchMs != 0L &&
            (abs(lat - lastWeatherLat) >= WEATHER_THROTTLE_EPSILON ||
                abs(lng - lastWeatherLng) >= WEATHER_THROTTLE_EPSILON)
        ) {
            _weather.value = null
            _detailedWeather.value = null
            _airQuality.value = null
        }
        if (!force &&
            // Gate on the bookkeeping itself (set after ANY successful primary fetch, detailed OR the
            // lightweight fetchCurrent fallback) rather than _detailedWeather, so a current-only fetch
            // still engages the 10-minute throttle. lastWeatherFetchMs == 0L lets the first fetch run.
            lastWeatherFetchMs != 0L &&
            now - lastWeatherFetchMs < 10 * 60 * 1000 &&
            abs(lat - lastWeatherLat) < WEATHER_THROTTLE_EPSILON &&
            abs(lng - lastWeatherLng) < WEATHER_THROTTLE_EPSILON
        ) {
            return@refresh
        }
        _isWeatherLoading.value = true
        // Cancel any in-flight fetch so a superseded (older-city) request can't write stale results.
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var primaryOk = false
                val detailed = WeatherRepository.fetchDetailed(lat, lng)
                // Staleness guard: if the active city changed while the network call was in flight,
                // drop these results so we don't overwrite current-city data or poison the throttle.
                if (abs(_latitude.value - lat) > WEATHER_THROTTLE_EPSILON ||
                    abs(_longitude.value - lng) > WEATHER_THROTTLE_EPSILON
                ) {
                    return@launch
                }
                if (detailed != null) {
                    _detailedWeather.value = detailed
                    _weather.value = detailed.current
                    
                    val detectedTz = detailed.timezoneOffset ?: _timezoneOffset.value
                    val activeName = _locationName.value
                    val sanitizedTz = LocationHelper.sanitizeOffset(lat, lng, detectedTz, activeName)
                    
                    if (sanitizedTz != _timezoneOffset.value) {
                        _timezoneOffset.value = sanitizedTz
                        
                        // We must save the dynamically refreshed DST offset to Room
                        locationHelper.saveLocation(lat, lng, sanitizedTz, activeName)
                        
                        // Because tzOffset updated, we should recompute sun times inline to match the correct DST (preventing recursion)
                        val offset = sanitizedTz
                        val today = LocalDate.now(SunAlarmResolver.zoneOf(offset))
                        val tomorrow = today.plusDays(1)
                
                        val todayTimes = SunCalculator.calculateSunTimes(lat, lng, today, offset)
                        _sunriseTime.value = todayTimes.sunrise ?: LocalTime.of(6, 0)
                        _sunsetTime.value = todayTimes.sunset ?: LocalTime.of(18, 0)
                
                        val tomorrowTimes = SunCalculator.calculateSunTimes(lat, lng, tomorrow, offset)
                        _tomorrowSunriseTime.value = tomorrowTimes.sunrise ?: LocalTime.of(6, 0)
                        _tomorrowSunsetTime.value = tomorrowTimes.sunset ?: LocalTime.of(18, 0)
                
                        recalculateAndScheduleActiveAlarms()
                    }
                    primaryOk = true
                } else {
                    val info = WeatherRepository.fetchCurrent(lat, lng)
                    if (info != null) {
                        _weather.value = info
                        primaryOk = true
                    }
                }
                // Air quality for the same coordinates (null-safe; leaves prior value on failure).
                val aqi = WeatherRepository.fetchAirQuality(lat, lng)
                // Re-apply the staleness guard before writing: the AQI request can finish after the
                // user switches cities, and unlike the detailed fetch above there was no second check,
                // so the old city's AQI could overwrite the new active city's display.
                if (abs(_latitude.value - lat) > WEATHER_THROTTLE_EPSILON ||
                    abs(_longitude.value - lng) > WEATHER_THROTTLE_EPSILON
                ) {
                    return@launch
                }
                if (aqi != null) {
                    _airQuality.value = aqi
                }
                // Only advance the throttle bookkeeping when a primary fetch actually succeeded; on a
                // total failure leave it untouched so the next call can retry immediately.
                if (primaryOk) {
                    // Record COMPLETION time (not the start-time `now`) so the 10-minute throttle
                    // window measures from when the fetch finished, not when it was requested.
                    lastWeatherFetchMs = System.currentTimeMillis()
                    lastWeatherLat = lat
                    lastWeatherLng = lng
                }
            } catch (e: Exception) {
                Log.e("AlarmViewModel", "Weather refresh failed: ", e)
            } finally {
                _isWeatherLoading.value = false
            }
        }
        }
    }

    private fun recomputeSunTimes() {
        val lat = _latitude.value
        val lng = _longitude.value
        val offset = _timezoneOffset.value
        val today = LocalDate.now(SunAlarmResolver.zoneOf(offset))
        val tomorrow = today.plusDays(1)

        val todayTimes = SunCalculator.calculateSunTimes(lat, lng, today, offset)
        _sunriseTime.value = todayTimes.sunrise ?: LocalTime.of(6, 0)
        _sunsetTime.value = todayTimes.sunset ?: LocalTime.of(18, 0)

        val tomorrowTimes = SunCalculator.calculateSunTimes(lat, lng, tomorrow, offset)
        _tomorrowSunriseTime.value = tomorrowTimes.sunrise ?: LocalTime.of(6, 0)
        _tomorrowSunsetTime.value = tomorrowTimes.sunset ?: LocalTime.of(18, 0)

        // Whenever sunrise/sunset recalibrates, we dynamically rewrite daylight event hours in DB for active alarms
        recalculateAndScheduleActiveAlarms()

        // Location changed (or first launch) -> refresh the weather backdrop for the new coordinates.
        refreshWeather()
    }

    private fun observeAlarmsForUpcoming() {
        viewModelScope.launch {
            // Recompute on any DB change AND at least once a minute, so the hero "Next Alarm"
            // self-heals after a repeating alarm re-arms (via the receiver, not the ViewModel)
            // or after a midnight rollover even with the dashboard left open.
            val ticker = flow { while (true) { emit(Unit); delay(60_000) } }
            combine(allAlarms, ticker) { list, _ -> list }.collect { list ->
                val activeList = list.filter { it.active }
                if (activeList.isEmpty()) {
                    _nextUpcomingAlarm.value = null
                    _nextUpcomingAlarmTimeFormatted.value = "None"
                } else {
                    var earliestAlarm: Alarm? = null
                    var earliestTime = Long.MAX_VALUE
                    
                    for (alarm in activeList) {
                        val occ = AlarmScheduler.getNextOccurrence(alarm).timeInMillis
                        if (occ < earliestTime) {
                            earliestTime = occ
                            earliestAlarm = alarm
                        }
                    }
                    
                    _nextUpcomingAlarm.value = earliestAlarm
                    if (earliestAlarm != null) {
                        val cal = Calendar.getInstance().apply { timeInMillis = earliestTime }
                        val hour = cal.get(Calendar.HOUR_OF_DAY)
                        val minute = cal.get(Calendar.MINUTE)
                        val ampm = if (hour >= 12) "PM" else "AM"
                        val formattedHour = if (hour % 12 == 0) 12 else hour % 12
                        val formattedMinute = String.format("%02d", minute)
                        val dayWord = when (cal.get(Calendar.DAY_OF_WEEK)) {
                            Calendar.MONDAY -> "Mon"
                            Calendar.TUESDAY -> "Tue"
                            Calendar.WEDNESDAY -> "Wed"
                            Calendar.THURSDAY -> "Thu"
                            Calendar.FRIDAY -> "Fri"
                            Calendar.SATURDAY -> "Sat"
                            Calendar.SUNDAY -> "Sun"
                            else -> ""
                        }
                        _nextUpcomingAlarmTimeFormatted.value = "$dayWord, $formattedHour:$formattedMinute $ampm"
                    }
                }
            }
        }
    }

    /**
     * One-time backfill (run at init): assigns the currently active location to every alarm that has
     * no recorded location (pre-binding legacy rows, or rows whose binding failed). Without this such
     * alarms match the per-location filter in EVERY city and appear duplicated across all cities.
     */
    private suspend fun backfillUnboundAlarms() {
        val all = repository.allAlarms.first()
        val active = currentActiveLocation()
        val activeLoc = SunAlarmResolver.Location(active.latitude, active.longitude, active.timezoneOffset, active.name)
        
        for (alarm in all) {
            var updated = alarm
            var changed = false
            if (!alarm.hasLocation()) {
                updated = updated.copy(
                    locationName = active.name,
                    latitude = active.latitude,
                    longitude = active.longitude,
                    timezoneOffset = active.timezoneOffset
                )
                // Recalibrate immediately with the newly bound location so digits are correct
                updated = SunAlarmResolver.recalibrate(updated, activeLoc)
                changed = true
            }
            // Auto-correct any Indian coordinate offsets or others from previous bad detections
            val sanitized = LocationHelper.sanitizeOffset(updated.latitude, updated.longitude, updated.timezoneOffset, updated.locationName)
            if (updated.timezoneOffset != sanitized) {
                updated = updated.copy(timezoneOffset = sanitized)
                // Re-recalibrate digits for the corrected offset
                val boundLoc = SunAlarmResolver.Location(updated.latitude, updated.longitude, updated.timezoneOffset, updated.locationName)
                updated = SunAlarmResolver.recalibrate(updated, boundLoc)
                changed = true
            }
            if (changed) {
                repository.updateAlarm(updated)
            }
        }
    }

    /**
     * Recomputes the clock time of every active SUNRISE/SUNSET alarm and (re)schedules it.
     *
     * Each sun-alarm is resolved against ITS OWN stored coordinates — never the currently active
     * location. That is the whole point of the per-location binding: switching the active city only
     * changes the dashboard/weather, it can no longer rewrite another city's alarm. Two cities =>
     * two independent alarms, each firing at its own local sunrise/sunset.
     *
     * Legacy alarms (created before the binding existed, [Alarm.hasLocation] == false) are backfilled
     * once with the active location so they keep working after the upgrade.
     */
    fun recalculateAndScheduleActiveAlarms() {
        // Cancel any prior pass so rapid city switches / a detect during a manual selection can't
        // interleave concurrent DB + AlarmManager writes for the same alarm ids.
        recalcJob?.cancel()
        recalcJob = viewModelScope.launch(Dispatchers.IO) {
            val list = repository.getActiveAlarms()
            val activeLocation = currentActiveLocation()
            for (alarm in list) {
                // No date passed: each sun-alarm calibrates against "today" in its OWN city's timezone.
                var updatedAlarm = SunAlarmResolver.recalibrate(alarm, activeLocation)
                // Hygiene: drop a stale (already-past) skipDate so it can't linger in the DB. The
                // resolver already ignores past skip dates, so this is purely cleanup. Compared in the
                // alarm's own zone via the recalibrated coordinates, falling back to the device zone.
                var skipCleared = false
                if (alarm.skipDate.isNotEmpty()) {
                    val zone = if ((updatedAlarm.alarmType == "SUNRISE" || updatedAlarm.alarmType == "SUNSET") && updatedAlarm.hasLocation())
                        SunAlarmResolver.zoneOf(updatedAlarm.timezoneOffset)
                    else java.time.ZoneId.systemDefault()
                    val todayThere = LocalDate.now(zone)
                    val parsed = runCatching { LocalDate.parse(alarm.skipDate) }.getOrNull()
                    if (parsed == null || parsed.isBefore(todayThere)) {
                        updatedAlarm = updatedAlarm.copy(skipDate = "")
                        skipCleared = true
                    }
                }
                // Only persist + reschedule when the fields that actually drive the fire time changed;
                // recalibrate always returns a fresh copy (new updatedAt) so the !== check alone churns.
                val changed = skipCleared ||
                    updatedAlarm.hour != alarm.hour ||
                    updatedAlarm.minute != alarm.minute ||
                    updatedAlarm.latitude != alarm.latitude ||
                    updatedAlarm.longitude != alarm.longitude ||
                    updatedAlarm.timezoneOffset != alarm.timezoneOffset
                if (changed) {
                    repository.updateAlarm(updatedAlarm)
                    scheduler.schedule(updatedAlarm)
                } else {
                    scheduler.schedule(alarm)
                }
            }
        }
    }

    fun updateAlarmOffset(alarm: Alarm, offset: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            // Recalibrate against the alarm's OWN bound location (resolver uses currentActiveLocation
            // only as a fallback for legacy/unbound alarms). CUSTOM alarms just take the new offset.
            val updated = SunAlarmResolver.recalibrate(alarm.copy(offsetMinutes = offset), currentActiveLocation())
            repository.updateAlarm(updated)
            if (updated.active) {
                scheduler.schedule(updated)
            }
        }
    }

    fun calculateTimeUntilTrigger(alarm: Alarm): String? {
        val nextTrigger = AlarmScheduler.getNextOccurrence(alarm).timeInMillis
        val now = System.currentTimeMillis()
        if (nextTrigger < now) return null
        
        val diff = nextTrigger - now
        val hours = (diff / (1000 * 60 * 60)).toInt()
        val minutes = ((diff / (1000 * 60)) % 60).toInt()
        
        return if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m"
        }
    }

    /** The location the app is currently showing — fallback for alarms with no recorded location. */
    private fun currentActiveLocation() = SunAlarmResolver.Location(
        _latitude.value, _longitude.value, _timezoneOffset.value, _locationName.value
    )

    // Alarm management actions
    fun toggleAlarmActive(alarm: Alarm) {
        viewModelScope.launch {
            val updated = alarm.copy(active = !alarm.active)
            repository.updateAlarm(updated)
            if (updated.active) {
                scheduler.schedule(updated)
            } else {
                scheduler.cancel(updated)
            }
        }
    }

    /**
     * "Skip today only" for a (typically repeating) alarm: marks the alarm's NEXT occurrence date as
     * skipped, so the resolver advances to the one after it. The alarm stays ACTIVE — it just misses
     * a single occurrence and keeps repeating afterward. Persists the new skipDate and reschedules.
     *
     * Reuses [SunAlarmResolver.nextOccurrenceDate] (the same logic the scheduler uses) so the date we
     * skip is exactly the occurrence that would otherwise fire next. Calling this repeatedly walks
     * forward one occurrence at a time, because the resolver already honors the current skipDate when
     * computing the next occurrence.
     */
    fun skipNextOccurrence(alarm: Alarm) {
        viewModelScope.launch(Dispatchers.IO) {
            val nextDate = SunAlarmResolver.nextOccurrenceDate(
                alarm, java.time.Instant.now(), java.time.ZoneId.systemDefault()
            )
            val updated = alarm.copy(
                skipDate = nextDate.toString(), // ISO yyyy-MM-dd
                updatedAt = System.currentTimeMillis()
            )
            repository.updateAlarm(updated)
            if (updated.active) {
                scheduler.schedule(updated)
            }
        }
    }

    fun deleteAlarm(alarm: Alarm) {
        viewModelScope.launch {
            scheduler.cancel(alarm)
            repository.deleteAlarm(alarm)
        }
    }

    fun startNewAlarmScratchpad(type: String) {
        val initialHour: Int
        val initialMinute: Int
        val initialOffset: Int
        
        when (type) {
            "SUNRISE" -> {
                val base = _sunriseTime.value
                initialHour = base.hour
                initialMinute = base.minute
                initialOffset = 0
            }
            "SUNSET" -> {
                val base = _sunsetTime.value
                initialHour = base.hour
                initialMinute = base.minute
                initialOffset = 0
            }
            else -> {
                val now = LocalTime.now().plusHours(1)
                initialHour = now.hour
                initialMinute = 0
                initialOffset = 0
            }
        }

        editingAlarm.value = Alarm(
            title = "",
            alarmType = type,
            hour = initialHour,
            minute = initialMinute,
            offsetMinutes = initialOffset,
            snoozeMinutes = _defaultSnoozeMinutes.value,
            active = true
        )
    }

    fun createDefaultAlarm(type: String) {
        val base = if (type == "SUNRISE") _sunriseTime.value else _sunsetTime.value
        val draft = Alarm(
            title = if (type == "SUNRISE") "Sunrise Alarm" else "Sunset Alarm",
            alarmType = type,
            hour = base.hour,
            minute = base.minute,
            offsetMinutes = 0,
            snoozeMinutes = _defaultSnoozeMinutes.value,
            active = true
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Bind to the active city (sets coordinates + timezone, not just the name) so the alarm is
                // self-contained and hasLocation() is true with REAL coords, not (0,0).
                val alarm = SunAlarmResolver.recalibrate(draft, currentActiveLocation())
                val savedId = repository.insertAlarm(alarm)
                scheduler.schedule(alarm.copy(id = savedId.toInt()))
            } catch (e: Exception) {
                android.util.Log.e("AlarmViewModel", "Failed to schedule sun alarm", e)
            }
        }
    }

    fun saveEditingAlarm() {
        val alarm = editingAlarm.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // Bind & calibrate the alarm. A new alarm (or a legacy one with no location) adopts the
            // currently active city; an already-bound alarm keeps its own city even if a different
            // one is being viewed. This is what stops "set alarm for city A, switch to city B" from
            // overwriting A's alarm.
            val recalced = SunAlarmResolver.recalibrate(alarm, currentActiveLocation())
            // CUSTOM alarms pass through recalibrate untouched, but the alarm list is filtered per
            // active city — so tag a brand-new/unbound custom alarm with the active location to keep
            // it visible. An already-bound custom alarm keeps its own city on edit (mirrors the sun
            // path), so "set alarm for city A, switch to city B" no longer reassigns it to B.
            // Normalize the title: trim surrounding whitespace and fall back to a type-appropriate
            // default if it's blank (or whitespace-only). Without this a blank/all-spaces title is
            // saved verbatim and shown in the notification / full-screen ring.
            val normalizedTitle = recalced.title.trim().ifBlank {
                when (recalced.alarmType) {
                    "SUNRISE" -> "Sunrise Alarm"
                    "SUNSET" -> "Sunset Alarm"
                    else -> "Alarm"
                }
            }
            val titled = recalced.copy(title = normalizedTitle)
            val finalAlarm = if (titled.alarmType == "CUSTOM" && !titled.hasLocation()) {
                titled.copy(
                    locationName = _locationName.value,
                    latitude = _latitude.value,
                    longitude = _longitude.value,
                    timezoneOffset = _timezoneOffset.value
                )
            } else {
                titled
            }

            // Editing an existing row -> @Update (true in-place update); a brand-new row -> @Insert.
            // Using insert(REPLACE) for edits would delete+reinsert the row, silently wiping any future
            // FK ON DELETE CASCADE children and diverging from the updateAlarm path used everywhere else.
            val schedulerTarget = if (finalAlarm.id != 0) {
                repository.updateAlarm(finalAlarm)
                finalAlarm
            } else {
                val savedId = repository.insertAlarm(finalAlarm)
                finalAlarm.copy(id = savedId.toInt())
            }
            scheduler.schedule(schedulerTarget)

            editingAlarm.value = null
        }
    }

    // Location settings actions
    fun setManualCitySelection(city: CityInfo) {
        // Abort any pending auto-detect FIRST: this sets LocationHelper's `cancelled` flag before its
        // success/failure (and the IP fallbacks) can reach saveLocation(), so a late-arriving detected
        // location can't persist over the manual choice the user is making here.
        locationHelper.cancelLocationRequest()
        _isDetectingLocation.value = false
        locationHelper.setAutoDetect(false)
        locationHelper.saveLocation(city.latitude, city.longitude, city.timezoneOffset, city.name)
        
        _latitude.value = city.latitude
        _longitude.value = city.longitude
        _timezoneOffset.value = city.timezoneOffset
        _locationName.value = city.name

        recomputeSunTimes() // recomputeSunTimes() already refreshes weather for the new coordinates
    }

    fun searchLocationQuery(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(300)
            val results = locationHelper.searchCity(query)
            _searchResults.value = results
        }
    }

    /** True when the app should follow GPS (i.e. the user hasn't pinned a manual city). */
    fun isAutoLocationEnabled(): Boolean = locationHelper.isAutoDetectEnabled()

    fun triggerAutoLocationDetect() {
        _isDetectingLocation.value = true
        locationHelper.setAutoDetect(true)
        locationHelper.requestCurrentLocation(
            onSuccess = { lat, lng, offset, name ->
                if (cleared) return@requestCurrentLocation
                // Bail if the user switched to a manual city while this detect was in flight, so a late
                // callback can't clobber their selection (sun times / weather / active city).
                if (!locationHelper.isAutoDetectEnabled()) return@requestCurrentLocation
                
                val sanitizedOffset = LocationHelper.sanitizeOffset(lat, lng, offset, name)
                
                _isDetectingLocation.value = false
                _latitude.value = lat
                _longitude.value = lng
                _timezoneOffset.value = sanitizedOffset
                _locationName.value = name
                recomputeSunTimes() // recomputeSunTimes() already refreshes weather for the new coordinates
                
                // Add to saved cities
                val current = _savedCities.value.toMutableList()
                val newCity = CityInfo(name, "Detected", lat, lng, sanitizedOffset)
                
                // Only add if not already present (shared dedup rule with addSavedCity).
                val existingIndex = current.indexOfFirst { it.sameCityAs(newCity) }
                if (existingIndex != -1) {
                    current[existingIndex] = newCity
                } else {
                    val index = current.indexOfFirst { it.name == "Detecting Location..." }
                    if (index != -1) current[index] = newCity
                    else current.add(0, newCity)
                }
                _savedCities.value = current
                saveCitiesToPrefs(current)
            },
            onFailure = { e ->
                if (cleared) return@requestCurrentLocation
                if (!locationHelper.isAutoDetectEnabled()) return@requestCurrentLocation
                _isDetectingLocation.value = false
                Log.e("AlarmViewModel", "Failed to detect automatic GPS location", e)
            }
        )
    }

    /**
     * One-shot "use my location" fix for the TRAVEL start field ONLY.
     *
     * Unlike [triggerAutoLocationDetect] this MUST NOT hijack the dashboard's active location:
     * it does not mutate _latitude/_longitude/_timezoneOffset/_locationName/_savedCities and never
     * calls recomputeSunTimes(). The result is delivered exclusively through [onResult].
     *
     * LocationHelper.requestCurrentLocation's deeper paths (processLocation / IP fallback) gate on
     * isAutoDetectEnabled() and saveLocation() the persisted prefs, so for a user who has pinned a
     * manual city (auto-detect = false) we (a) snapshot the prior persisted location + auto-detect
     * flag, (b) temporarily enable auto-detect so the one-shot fix is allowed through, then
     * (c) restore BOTH the persisted location and the auto-detect flag once a result/failure lands.
     * The ViewModel's location StateFlows are never touched, so the dashboard's pinned city, sun
     * times, weather, and active pager page all stay exactly as the user left them.
     */
    fun triggerTravelStartLocation(onResult: (Double, Double, String) -> Unit) {
        val priorAutoDetect = locationHelper.isAutoDetectEnabled()
        val priorLat = locationHelper.getSavedLatitude()
        val priorLng = locationHelper.getSavedLongitude()
        val priorOffset = locationHelper.getSavedTimezoneOffset()
        val priorName = locationHelper.getSavedLocationName()

        fun restorePrior() {
            // Undo any saveLocation() the lookup paths performed and put the auto-detect flag back,
            // so this travel-only fix leaves zero global side effects.
            locationHelper.saveLocation(priorLat, priorLng, priorOffset, priorName)
            locationHelper.setAutoDetect(priorAutoDetect)
        }

        _isDetectingLocation.value = true
        // Temporarily allow the gated fallback paths to run for this one-shot fix only.
        if (!priorAutoDetect) locationHelper.setAutoDetect(true)

        locationHelper.requestCurrentLocation(
            onSuccess = { lat, lng, _, name ->
                restorePrior()
                if (cleared) return@requestCurrentLocation
                _isDetectingLocation.value = false
                // Deliver ONLY to the travel screen; do not touch active-location state.
                onResult(lat, lng, name)
            },
            onFailure = { e ->
                restorePrior()
                if (cleared) return@requestCurrentLocation
                _isDetectingLocation.value = false
                Log.e("AlarmViewModel", "Travel start one-shot location failed", e)
            }
        )
    }

    // Alarm Trigger Ring interactions
    fun setRingingState(id: Int, title: String, type: String, snoozeEnabled: Boolean = true, isExactAlso: Boolean = false) {
        _ringingAlarm.value = RingingAlarmState(id, title, type, snoozeEnabled, isExactAlso)
    }

    fun stopRingingAlarmAndDismiss() {
        val ringing = _ringingAlarm.value
        val ringingId = ringing?.id ?: -1
        val isExactAlso = ringing?.isExactAlso ?: false
        _ringingAlarm.value = null

        val context = getApplication<Application>()
        if (ringing?.type == "TRAVEL") {
            TravelTrackingService.stopService(context)
        } else {
            // Command service to halt playing
            val dismissIntent = Intent(context, AlarmService::class.java).apply {
                action = "ACTION_DISMISS"
                putExtra("ALARM_ID", ringingId)
                putExtra("ALARM_TITLE", ringing?.title ?: "Alarm")
                putExtra("ALARM_TYPE", ringing?.type ?: "CUSTOM")
                putExtra("IS_EXACT_ALSO", isExactAlso)
            }
            context.startService(dismissIntent)
        }
    }

    fun stopRingingAlarmAndSnooze() {
        val ringing = _ringingAlarm.value
        val ringingId = ringing?.id ?: -1
        val isExactAlso = ringing?.isExactAlso ?: false
        _ringingAlarm.value = null

        val context = getApplication<Application>()
        if (ringing?.type == "TRAVEL") {
            TravelTrackingService.stopService(context)
        } else {
            val snoozeIntent = Intent(context, AlarmService::class.java).apply {
                action = "ACTION_SNOOZE"
                putExtra("ALARM_ID", ringingId)
                // Carry the real name & SUNRISE/SUNSET type so the snoozed re-fire isn't
                // downgraded to the default "Alarm"/"CUSTOM" (matches the notification-button path).
                putExtra("ALARM_TITLE", ringing?.title ?: "Alarm")
                putExtra("ALARM_TYPE", ringing?.type ?: "CUSTOM")
                putExtra("IS_EXACT_ALSO", isExactAlso)
            }
            context.startService(snoozeIntent)
        }
    }

    // Setting management preferences
    fun setDefaultSnoozeMinutes(minutes: Int) {
        _defaultSnoozeMinutes.value = minutes
        settingsPrefs.edit().putInt("default_snooze", minutes).apply()
    }

    // Shared "same city" predicate so the add-path and the auto-detect path coalesce identically:
    // name match OR coordinate proximity within SAME_CITY_EPSILON. Without this the add-path dedups
    // by name only while detect dedups by name-or-coords, allowing duplicate pager pages for one place.
    private fun CityInfo.sameCityAs(other: CityInfo): Boolean =
        name.equals(other.name, ignoreCase = true) ||
            (abs(latitude - other.latitude) < SAME_CITY_EPSILON &&
                abs(longitude - other.longitude) < SAME_CITY_EPSILON)

    // SAVED LOCATIONS STORAGE & MANAGEMENT
    private fun saveCitiesToPrefs(list: List<CityInfo>) {
        // Sanitize the '|' (field) and ';' (record) delimiters out of free-form names/countries so a
        // geocoded value like "Washington, D.C.; County" can't split or shift the record on read.
        fun clean(s: String) = s.replace("|", " ").replace(";", " ")
        val str = list.joinToString(";") {
            "${clean(it.name)}|${clean(it.country)}|${it.latitude}|${it.longitude}|${it.timezoneOffset}"
        }
        settingsPrefs.edit().putString("saved_cities_list", str).apply()
    }

    fun addSavedCity(city: CityInfo) {
        val current = _savedCities.value.toMutableList()
        val index = current.indexOfFirst { it.sameCityAs(city) }
        if (index != -1) {
            current[index] = city
        } else {
            current.add(city)
        }
        _savedCities.value = current
        saveCitiesToPrefs(current)
        setManualCitySelection(city)
    }

    fun deleteSavedCity(city: CityInfo) {
        // Never delete the last remaining city: the app always needs an active location. The UI also
        // hides/disables Delete on the last row, so this is a defensive no-op rather than the old
        // "remove then silently re-add the same item" which left the city deleted-but-still-present.
        if (_savedCities.value.size <= 1) return

        // Identify the active city by coordinates (display names like "Delhi, IN" / "GPS: .." / "Auto
        // IP Location" rarely equal the stored CityInfo.name), falling back to name equality.
        val wasActive =
            (abs(city.latitude - _latitude.value) < SAME_CITY_EPSILON &&
                abs(city.longitude - _longitude.value) < SAME_CITY_EPSILON) ||
                locationName.value.lowercase() == city.name.lowercase()

        val current = _savedCities.value.toMutableList()
        // Remove only the first exact match
        current.remove(city)
        _savedCities.value = current
        saveCitiesToPrefs(current)

        // Reconcile the alarms table: SUNRISE/SUNSET (and tagged CUSTOM) alarms are bound to a city
        // via their own coordinates and are resolved/scheduled against THOSE coords, never the active
        // city. If we leave them behind, a deleted city's alarms keep firing at its sun times yet no
        // longer match alarmsForCurrentLocation for any visible city — the user would be woken by an
        // alarm for a city they deleted with no on-screen record. Cancel + delete those orphans so a
        // deleted city leaves no orphaned ringing alarms.
        viewModelScope.launch(Dispatchers.IO) {
            val all = repository.allAlarms.first()
            for (alarm in all) {
                if (alarm.hasLocation() &&
                    abs(alarm.latitude - city.latitude) < SAME_CITY_EPSILON &&
                    abs(alarm.longitude - city.longitude) < SAME_CITY_EPSILON
                ) {
                    scheduler.cancel(alarm)
                    repository.deleteAlarm(alarm)
                }
            }
        }

        // If we deleted the active city, reselect a remaining one so lat/lng/tz/loc_name stay
        // consistent with the list (otherwise the app keeps pointing at the deleted city).
        if (wasActive && current.isNotEmpty()) {
            setManualCitySelection(current[0])
        }
    }

    fun insertTravelAlarm(alarm: TravelAlarm) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertTravelAlarm(alarm)
        }
    }

    fun updateTravelAlarm(alarm: TravelAlarm) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateTravelAlarm(alarm)
        }
    }

    fun deleteTravelAlarm(alarm: TravelAlarm) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteTravelAlarm(alarm)
            if (TravelTrackingService.nearestAlarm.value?.id == alarm.id) {
                TravelTrackingService.clearNearestAlarm()
            }
        }
    }

    fun toggleTravelAlarmActive(alarm: TravelAlarm) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = alarm.copy(active = !alarm.active)
            repository.updateTravelAlarm(updated)
        }
    }
    
    fun startTravelTracking() {
        val context = getApplication<Application>()
        TravelTrackingService.startService(context)
    }

    fun stopTravelTracking() {
        val context = getApplication<Application>()
        TravelTrackingService.stopService(context)
    }

    fun resetAppSettings() {
        // Reset feature toggles to true
        setFeatureEnabled(FEATURE_WEATHER, true)
        setFeatureEnabled(FEATURE_SOLAR_TRENDS, true)
        setFeatureEnabled(FEATURE_TRAVEL, true)
        setFeatureEnabled(FEATURE_LOCATION, true)
        
        // Reset snooze
        setDefaultSnoozeMinutes(5)
        
        // Reset theme
        setThemeMode(ThemeMode.AUTO)
    }

    fun clearAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            // Clear repository data
            repository.deleteAllAlarms()
            repository.deleteAllTravelAlarms()
            
            // Clear location data
            val defaultCity = CityInfo(
                "Detecting Location...",
                "...",
                locationHelper.getSavedLatitude(),
                locationHelper.getSavedLongitude(),
                _timezoneOffset.value
            )
            val initialList = listOf(defaultCity)
            _savedCities.value = initialList
            saveCitiesToPrefs(initialList)
            
            // Re-trigger location detection
            triggerAutoLocationDetect()
        }
    }

    fun getAdviceForUpcomingAlarm(): String? {
        val alarm = _nextUpcomingAlarm.value ?: return null
        if (alarm.alarmType != "SUNRISE") return null
        
        val weatherData = _detailedWeather.value ?: return null
        
        // Find hourly detail closest to alarm time
        val hour = alarm.hour
        val minute = alarm.minute
        
        val targetTime = java.time.LocalTime.of(hour, minute)
        
        val closestHour = weatherData.hourlyList.minByOrNull { hourly ->
            val time = java.time.LocalDateTime.parse(hourly.timeIso).toLocalTime()
            kotlin.math.abs(java.time.Duration.between(targetTime, time).toMinutes())
        } ?: return null
        
        return com.example.alarm.weather.WeatherAdviser.getAdvice(closestHour.condition, closestHour.precipitationMm)
    }

    fun stopTravelTrackingAndDisableFeature() {
        viewModelScope.launch(Dispatchers.IO) {
            // Stop service
            TravelTrackingService.stopService(getApplication())
            // Clear travel alarms
            repository.deleteAllTravelAlarms()
            // Disable feature
            setFeatureEnabled(FEATURE_TRAVEL, false)
        }
    }
    
    companion object {
        // Single shared definition of "same city" used by both the per-location alarm filter and the
        // auto-detect dedup, so list visibility and detection agree (~1.1 km at the equator).
        const val SAME_CITY_EPSILON = 0.01

        // Weather refetch dedup/staleness radius (~550 m). Deliberately <= SAME_CITY_EPSILON so two
        // nearby saved cities (adjacent metros/airports) are not collapsed onto one city's cached
        // weather/AQI when the user switches between them.
        const val WEATHER_THROTTLE_EPSILON = 0.005
    }
}
