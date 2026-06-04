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
    val type: String
)

enum class ThemeMode { LIGHT, DARK, AUTO }

class AlarmViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = AlarmRepository(database.alarmDao(), database.travelAlarmDao())
    private val scheduler = AlarmScheduler(application)
    private val locationHelper = LocationHelper(application)
    private var searchJob: kotlinx.coroutines.Job? = null

    // Guards async location callbacks from mutating StateFlows after the ViewModel is destroyed.
    @Volatile private var cleared = false

    override fun onCleared() {
        cleared = true
        locationHelper.cancelLocationRequest()
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

    // System Settings preferences
    private val settingsPrefs = application.getSharedPreferences("sun_alarm_settings_prefs", Context.MODE_PRIVATE)
    
    private val _darkThemeEnabled = MutableStateFlow(settingsPrefs.getBoolean("dark_theme", true))
    val darkThemeEnabled: StateFlow<Boolean> = _darkThemeEnabled.asStateFlow()

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
        settingsPrefs.edit().putString("theme_mode", m.name).apply()
    }

    /** Resolves the active theme: AUTO follows daylight at the location; DARK/LIGHT are explicit. */
    fun isEffectiveDark(isDayAtLocation: Boolean): Boolean = when (_themeMode.value) {
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
        "Snooze pause duration" to "स्नूज़ विराम अवधि",
        "mins" to "मिनट",
        "Confirm Schedule Settings" to "अलार्म सुरक्षित (सेव) करें",
        "SUNSET" to "सूर्यास्त",
        "SUNRISE" to "सूर्योदय",
        "CUSTOM" to "मानक घड़ी",
        "DASH" to "मुख्य पृष्ठ",
        "LOCATION" to "स्थान चुनें",
        "SETTINGS" to "सेटिंग्स",
        "Settings" to "सेटिंग्स",
        "Preferences & Appearance" to "प्राथमिकताएं और थीम",
        "Dark Mode" to "डार्क मोड",
        "Light" to "लाइट",
        "Dark" to "डार्क",
        "Auto" to "ऑटो",
        "Render dark celestial color profiles" to "गहरे खगोलीय रंग प्रोफ़ाइल दिखाएं",
        "Applying language…" to "भाषा लागू की जा रही है…",
        "Default Snooze Duration" to "डिफ़ॉल्ट स्नूज़ समय",
        "Select Language" to "भाषा चुनें (Language)",
        "Only English & Hindi are fully translated; other languages change date/number format only." to "केवल अंग्रेज़ी और हिंदी का पूरा अनुवाद उपलब्ध है; अन्य भाषाएँ केवल तारीख/संख्या प्रारूप बदलती हैं।",
        "Where are you? Type city name here..." to "आप कहाँ हैं? शहर ढूंढें...",
        "Search city (e.g. Reykjavik, London, Tokyo...)" to "शहर का नाम (जैसे: दिल्ली, मुम्बई, टोक्यो...)",
        "Use My Current Phone Location (GPS)" to "अपने फ़ोन के GPS/स्थान का उपयोग करें",
        "Location permission denied. Enable it in Settings or search a city below." to "स्थान की अनुमति अस्वीकृत। इसे सेटिंग्स में चालू करें या नीचे शहर खोजें।",
        "Instantly adjust to where you are standing." to "सटीक रूप से अपने वर्तमान स्थान का उपयोग करें।",
        "Why set location?" to "स्थान चुनना क्यों ज़रूरी है?",
        "We calculate the exact sunrise and sunset times automatically for your location, even without internet!" to "हम बिना इंटरनेट के भी आपके स्थान के लिए सूर्योदय और सूर्यास्त का बिल्कुल सटीक समय ऑन-डिवाइस निकालते हैं!",
        "Trigger exactly during event (Sunrise/Sunset)" to "मौसम घटना के समय बिल्कुल सटीक बजाएं (सूर्योदय/सूर्यास्त होने पर)",
        "Fires at the precise moment of astronomical rise/set" to "सटीक उदय या अस्त के समय अलार्म बजाएं",
        "Configure Clock Time" to "घड़ी का सटीक समय चुनें",
        "Based on location, triggers today at" to "स्थान की गणना से, आज अलार्म बजेगा: ",
        "CURRENT COORDINATES" to "वर्तमान स्थान विवरण",
        "Manual Search Lookup" to "मैन्युअल शहर खोजें",
        "Offline Astronomical Security" to "ऑफ़लाइन भौगोलिक सुरक्षा",
        "GPS Location Detected" to "जीपीएस द्वारा स्थान मिला",
        "Next Alarm" to "अगला सूचना/अलार्म",
        "No Alarms Scheduled" to "कोई अलार्म निर्धारित नहीं है",
        "SOLARIS LIVE" to "लाइव संरेखण",
        "Hour" to "घंटा",
        "Minute" to "मिनट",
        "Go back" to "पीछे जाएं",
        "Save" to "सुरक्षित करें",
        "Delete" to "हटाएं",
        "Active" to "सक्रिय",
        "Inactive" to "निष्क्रिय",
        "None" to "कोई नहीं",
        "Trigger Offset" to "समय का अंतर (ओफ़्सेट)",
        "Before" to "पहले",
        "After" to "बाद में",
        "At Event" to "घटना के समय",
        "AM" to "पूर्वाह्न (AM)",
        "PM" to "अपराह्न (PM)",
        "Ringtone" to "अलार्म टोन",
        "Select Alarm Tone" to "अलार्म टोन चुनें",
        "Alarm Tone" to "अलार्म टोन",
        "Default Alarm" to "डिफ़ॉल्ट अलार्म",
        "Default Alarm Sound" to "डिफ़ॉल्ट अलार्म ध्वनि"
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
        // Initialize saved location cities database FIRST so recomputeSunTimes can rely on exact city coordinates
        val savedStr = settingsPrefs.getString("saved_cities_list", "") ?: ""
        if (savedStr.isEmpty()) {
            val defaultCity = CityInfo(
                "Detecting Location...",
                "...",
                locationHelper.getSavedLatitude(),
                locationHelper.getSavedLongitude(),
                locationHelper.getSavedTimezoneOffset()
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
                            list.add(
                                CityInfo(
                                    parts[0],
                                    parts[1],
                                    lat,
                                    lng,
                                    parts[4].toDoubleOrNull() ?: -5.0
                                )
                            )
                        }
                    }
                }
            }
            _savedCities.value = list
        }

        recomputeSunTimes()
        observeAlarmsForUpcoming()
        backfillUnboundAlarms()

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

    // Weather refetch throttle: skip if we recently fetched for ~the same coordinates.
    private var lastWeatherFetchMs = 0L
    private var lastWeatherLat = Double.NaN
    private var lastWeatherLng = Double.NaN
    private var weatherJob: Job? = null

    fun refreshWeather(force: Boolean = false) {
        val lat = _latitude.value
        val lng = _longitude.value
        val now = System.currentTimeMillis()
        if (!force &&
            _detailedWeather.value != null &&
            now - lastWeatherFetchMs < 10 * 60 * 1000 &&
            abs(lat - lastWeatherLat) < 0.05 &&
            abs(lng - lastWeatherLng) < 0.05
        ) {
            return
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
                if (abs(_latitude.value - lat) > 0.05 || abs(_longitude.value - lng) > 0.05) {
                    return@launch
                }
                if (detailed != null) {
                    _detailedWeather.value = detailed
                    _weather.value = detailed.current
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
                if (aqi != null) {
                    _airQuality.value = aqi
                }
                // Only advance the throttle bookkeeping when a primary fetch actually succeeded; on a
                // total failure leave it untouched so the next call can retry immediately.
                if (primaryOk) {
                    lastWeatherFetchMs = now
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
    private fun backfillUnboundAlarms() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = repository.allAlarms.first()
            val active = currentActiveLocation()
            for (alarm in all) {
                if (!alarm.hasLocation()) {
                    repository.updateAlarm(
                        alarm.copy(
                            locationName = active.name,
                            latitude = active.latitude,
                            longitude = active.longitude,
                            timezoneOffset = active.timezoneOffset
                        )
                    )
                }
            }
        }
    }

    private var recalcJob: Job? = null

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
                val updatedAlarm = SunAlarmResolver.recalibrate(alarm, activeLocation)
                // Only persist + reschedule when the fields that actually drive the fire time changed;
                // recalibrate always returns a fresh copy (new updatedAt) so the !== check alone churns.
                val changed = updatedAlarm.hour != alarm.hour ||
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
            // Bind to the active city (sets coordinates + timezone, not just the name) so the alarm is
            // self-contained and hasLocation() is true with REAL coords, not (0,0).
            val alarm = SunAlarmResolver.recalibrate(draft, currentActiveLocation())
            val savedId = repository.insertAlarm(alarm)
            scheduler.schedule(alarm.copy(id = savedId.toInt()))
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
            val finalAlarm = if (recalced.alarmType == "CUSTOM" && !recalced.hasLocation()) {
                recalced.copy(
                    locationName = _locationName.value,
                    latitude = _latitude.value,
                    longitude = _longitude.value,
                    timezoneOffset = _timezoneOffset.value
                )
            } else {
                recalced
            }

            val savedId = repository.insertAlarm(finalAlarm)
            // insertAlarm uses REPLACE: a brand-new row returns its new id; an edited row keeps its id.
            val schedulerTarget = finalAlarm.copy(id = savedId.toInt())
            scheduler.schedule(schedulerTarget)

            editingAlarm.value = null
        }
    }

    // Location settings actions
    fun setManualCitySelection(city: CityInfo) {
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
                _isDetectingLocation.value = false
                _latitude.value = lat
                _longitude.value = lng
                _timezoneOffset.value = offset
                _locationName.value = name
                recomputeSunTimes() // recomputeSunTimes() already refreshes weather for the new coordinates
                
                // Add to saved cities
                val current = _savedCities.value.toMutableList()
                val newCity = CityInfo(name, "Detected", lat, lng, offset)
                
                // Only add if not already present
                val existingIndex = current.indexOfFirst { it.name == name || (abs(it.latitude - lat) < SAME_CITY_EPSILON && abs(it.longitude - lng) < SAME_CITY_EPSILON) }
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
                _isDetectingLocation.value = false
                Log.e("AlarmViewModel", "Failed to detect automatic GPS location", e)
            }
        )
    }

    // Alarm Trigger Ring interactions
    fun setRingingState(id: Int, title: String, type: String) {
        _ringingAlarm.value = RingingAlarmState(id, title, type)
    }

    fun stopRingingAlarmAndDismiss() {
        val ringing = _ringingAlarm.value
        val ringingId = ringing?.id ?: -1
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
            }
            context.startService(dismissIntent)
        }
    }

    fun stopRingingAlarmAndSnooze() {
        val ringing = _ringingAlarm.value
        val ringingId = ringing?.id ?: -1
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
            }
            context.startService(snoozeIntent)
        }
    }

    // Setting management preferences
    fun toggleDarkThemeSetting() {
        val toggled = !_darkThemeEnabled.value
        _darkThemeEnabled.value = toggled
        settingsPrefs.edit().putBoolean("dark_theme", toggled).apply()
    }

    fun setDefaultSnoozeMinutes(minutes: Int) {
        _defaultSnoozeMinutes.value = minutes
        settingsPrefs.edit().putInt("default_snooze", minutes).apply()
    }

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
        if (!current.any { it.name.lowercase() == city.name.lowercase() }) {
            current.add(city)
            _savedCities.value = current
            saveCitiesToPrefs(current)
        }
        setManualCitySelection(city)
    }

    fun deleteSavedCity(city: CityInfo) {
        // Identify the active city by coordinates (display names like "Delhi, IN" / "GPS: .." / "Auto
        // IP Location" rarely equal the stored CityInfo.name), falling back to name equality.
        val wasActive =
            (abs(city.latitude - _latitude.value) < SAME_CITY_EPSILON &&
                abs(city.longitude - _longitude.value) < SAME_CITY_EPSILON) ||
                locationName.value.lowercase() == city.name.lowercase()

        val current = _savedCities.value.toMutableList()
        // Remove only the first exact match
        current.remove(city)

        // Ensure at least one city remains
        if (current.isEmpty()) {
            current.add(city)
        }
        _savedCities.value = current
        saveCitiesToPrefs(current)

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

    companion object {
        // Single shared definition of "same city" used by both the per-location alarm filter and the
        // auto-detect dedup, so list visibility and detection agree (~1.1 km at the equator).
        const val SAME_CITY_EPSILON = 0.01
    }
}
