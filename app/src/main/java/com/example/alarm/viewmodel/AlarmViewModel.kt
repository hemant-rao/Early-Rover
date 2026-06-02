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
import com.example.alarm.location.CityInfo
import com.example.alarm.location.LocationHelper
import com.example.alarm.data.TravelAlarm
import com.example.alarm.location.TravelTrackingService
import com.example.alarm.scheduling.AlarmScheduler
import com.example.alarm.scheduling.AlarmService
import com.example.alarm.weather.WeatherInfo
import com.example.alarm.weather.WeatherRepository
import com.example.alarm.weather.DetailedWeatherInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.util.Calendar

data class RingingAlarmState(
    val id: Int,
    val title: String,
    val type: String
)

class AlarmViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = AlarmRepository(database.alarmDao(), database.travelAlarmDao())
    private val scheduler = AlarmScheduler(application)
    private val locationHelper = LocationHelper(application)
    private var searchJob: kotlinx.coroutines.Job? = null

    // UI state flows
    val allAlarms: StateFlow<List<Alarm>> = repository.allAlarms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _latitude = MutableStateFlow(locationHelper.getSavedLatitude())
    val latitude: StateFlow<Double> = _latitude.asStateFlow()

    private val _longitude = MutableStateFlow(locationHelper.getSavedLongitude())
    val longitude: StateFlow<Double> = _longitude.asStateFlow()

    private val _locationName = MutableStateFlow(locationHelper.getSavedLocationName())
    val locationName: StateFlow<String> = _locationName.asStateFlow()

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

    private val _isWeatherLoading = MutableStateFlow(false)
    val isWeatherLoading: StateFlow<Boolean> = _isWeatherLoading.asStateFlow()

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

    private val _defaultSnoozeMinutes = MutableStateFlow(settingsPrefs.getInt("default_snooze", 5))
    val defaultSnoozeMinutes: StateFlow<Int> = _defaultSnoozeMinutes.asStateFlow()

    // BCP-47 language tag (e.g. "en", "hi", "hi-IN"). LocaleHelper is the canonical store.
    val currentLanguage = MutableStateFlow(
        com.example.alarm.util.LocaleHelper.getPersistedTag(application)
    )

    /**
     * Sets the UI language. Persists via [LocaleHelper], then recreates the activity so
     * attachBaseContext re-reads the locale (LocalConfiguration updates). The ViewModel
     * survives recreate(); the key(lang) wrapper in MainActivity is a safety net for
     * the non-reactive translate() call sites.
     */
    fun setAppLanguage(tag: String, activity: android.app.Activity? = null) {
        if (tag == currentLanguage.value) return
        currentLanguage.value = tag
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
        "PM" to "अपराह्न (PM)"
    )

    init {
        recomputeSunTimes()
        observeAlarmsForUpcoming()

        // Initialize saved location cities database
        val savedStr = settingsPrefs.getString("saved_cities_list", "") ?: ""
        if (savedStr.isEmpty()) {
            val defaultCity = CityInfo(
                locationHelper.getSavedLocationName().ifEmpty { "New York" },
                "United States",
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
                        list.add(
                            CityInfo(
                                parts[0],
                                parts[1],
                                parts[2].toDoubleOrNull() ?: 40.7128,
                                parts[3].toDoubleOrNull() ?: -74.0060,
                                parts[4].toDoubleOrNull() ?: -5.0
                            )
                        )
                    }
                }
            }
            _savedCities.value = list
        }
    }

    fun refreshWeather() {
        val lat = _latitude.value
        val lng = _longitude.value
        _isWeatherLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val detailed = WeatherRepository.fetchDetailed(lat, lng)
                if (detailed != null) {
                    _detailedWeather.value = detailed
                    _weather.value = detailed.current
                } else {
                    val info = WeatherRepository.fetchCurrent(lat, lng)
                    if (info != null) {
                        _weather.value = info
                    }
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
        val today = LocalDate.now()
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
            allAlarms.collect { list ->
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

    fun recalculateAndScheduleActiveAlarms() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = repository.getActiveAlarms()
            for (alarm in list) {
                var updatedAlarm = alarm
                if (alarm.alarmType == "SUNRISE") {
                    val targetLocalTime = _sunriseTime.value.plusMinutes(alarm.offsetMinutes.toLong())
                    updatedAlarm = alarm.copy(hour = targetLocalTime.hour, minute = targetLocalTime.minute)
                    repository.updateAlarm(updatedAlarm)
                } else if (alarm.alarmType == "SUNSET") {
                    val targetLocalTime = _sunsetTime.value.plusMinutes(alarm.offsetMinutes.toLong())
                    updatedAlarm = alarm.copy(hour = targetLocalTime.hour, minute = targetLocalTime.minute)
                    repository.updateAlarm(updatedAlarm)
                }
                scheduler.schedule(updatedAlarm)
            }
        }
    }

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

    fun saveEditingAlarm() {
        val alarm = editingAlarm.value ?: return
        viewModelScope.launch {
            var finalAlarm = alarm
            
            // Re-apply sunrise/sunset shifts to base hours
            if (alarm.alarmType == "SUNRISE") {
                val targetLocalTime = _sunriseTime.value.plusMinutes(alarm.offsetMinutes.toLong())
                finalAlarm = alarm.copy(hour = targetLocalTime.hour, minute = targetLocalTime.minute)
            } else if (alarm.alarmType == "SUNSET") {
                val targetLocalTime = _sunsetTime.value.plusMinutes(alarm.offsetMinutes.toLong())
                finalAlarm = alarm.copy(hour = targetLocalTime.hour, minute = targetLocalTime.minute)
            }

            val savedId = repository.insertAlarm(finalAlarm)
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

    fun triggerAutoLocationDetect() {
        locationHelper.setAutoDetect(true)
        locationHelper.requestCurrentLocation(
            onSuccess = { lat, lng, offset, name ->
                _latitude.value = lat
                _longitude.value = lng
                _timezoneOffset.value = offset
                _locationName.value = name
                recomputeSunTimes() // recomputeSunTimes() already refreshes weather for the new coordinates
            },
            onFailure = { e ->
                Log.e("AlarmViewModel", "Failed to detect automatic GPS location", e)
            }
        )
    }

    // Alarm Trigger Ring interactions
    fun setRingingState(id: Int, title: String, type: String) {
        _ringingAlarm.value = RingingAlarmState(id, title, type)
    }

    fun stopRingingAlarmAndDismiss() {
        val ringingId = _ringingAlarm.value?.id ?: -1
        _ringingAlarm.value = null
        
        val context = getApplication<Application>()
        if (ringingId >= 500000) {
            TravelTrackingService.stopService(context)
        } else {
            // Command service to halt playing
            val dismissIntent = Intent(context, AlarmService::class.java).apply {
                action = "ACTION_DISMISS"
                putExtra("ALARM_ID", ringingId)
            }
            context.startService(dismissIntent)
        }
    }

    fun stopRingingAlarmAndSnooze() {
        val ringingId = _ringingAlarm.value?.id ?: -1
        _ringingAlarm.value = null

        val context = getApplication<Application>()
        if (ringingId >= 500000) {
            TravelTrackingService.stopService(context)
        } else {
            val snoozeIntent = Intent(context, AlarmService::class.java).apply {
                action = "ACTION_SNOOZE"
                putExtra("ALARM_ID", ringingId)
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
        val str = list.joinToString(";") { "${it.name}|${it.country}|${it.latitude}|${it.longitude}|${it.timezoneOffset}" }
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
        val current = _savedCities.value.toMutableList()
        current.removeAll { it.name.lowercase() == city.name.lowercase() }
        
        // Ensure at least one city remains
        if (current.isEmpty()) {
            current.add(city)
        }
        _savedCities.value = current
        saveCitiesToPrefs(current)
        
        // If we deleted the active city, select another one
        if (locationName.value.lowercase() == city.name.lowercase() && current.isNotEmpty()) {
            setManualCitySelection(current[0])
        }
    }

    // --- TRAVEL / DESTINATION ARRIVAL ALARM SYSTEMS ---
    val allTravelAlarms: StateFlow<List<TravelAlarm>> = repository.allTravelAlarms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        }
    }

    fun toggleTravelAlarmActive(alarm: TravelAlarm) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = alarm.copy(active = !alarm.active)
            repository.updateTravelAlarm(updated)
        }
    }
    
    // Companion tracking fields bound directly to services
    val isTravelTrackingActive = TravelTrackingService.isTracking
    val travelCurrentLocation = TravelTrackingService.currentLocation
    val travelNearestAlarm = TravelTrackingService.nearestAlarm
    val travelDistanceToNearest = TravelTrackingService.distanceToNearestKm
    val travelStatusMsg = TravelTrackingService.statusMessage
    val travelCurrentSpeed = TravelTrackingService.currentSpeedKmh

    fun startTravelTracking() {
        val context = getApplication<Application>()
        TravelTrackingService.startService(context)
    }

    fun stopTravelTracking() {
        val context = getApplication<Application>()
        TravelTrackingService.stopService(context)
    }
}
