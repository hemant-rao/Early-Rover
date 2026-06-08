# Early Rover

Early Rover is a highly advanced, location-aware astronomical sun-tracking alarm clock designed to synchronize your daily routines with the natural cycles of your environment. Whether you are aiming to wake up with the sunrise, catch the sunset, or travel cross-country, Early Rover ensures you stay perfectly timed and ready.

## Core Features

### 1. Astro-Sync Alarms
Intelligent alarms that automatically calibrate to real-time sunrise and sunset times based on your current or specified location.
*   **Automatic Recalibration**: No need to worry about changing seasonal daylight hours; Early Rover constantly calculates and updates your sunrise/sunset alarm times, ensuring accuracy throughout the year.
*   **Location-Based Awareness**: Supports alarms tailored to specific global locations, meaning your sunset alarm will ring at the *actual* sunset time for the location you've set, regardless of your current timezone.

### 2. Travel & Destination Alarms
Designed for nomads, commuters, and travelers. 
*   **Background Proximity Tracking**: Early Rover can monitor your location in the background while you travel towards a destination. 
*   **Dynamic Recalibration**: Upon arriving at or approaching a new destination, the app automatically detects the coordinate change. It then recalculates sun events for your *new* location and dynamically adjusts your alarms to stay synchronized with your destination's local sunrise and sunset cycle.
*   **Foreground Reliability**: Uses a foreground service to maintain location accuracy during transit, ensuring your alarm reliably triggers even after a long travel day in a new timezone.

### 3. Alarm Profiles
Manage your daily rhythm with highly customizable alarm profiles.
*   **Pre-defined & Custom Profiles**: Start with templates like "Work Week" or "Weekend" or create your own custom profiles for specific scenarios (e.g., "Camping Trip", "Yoga Zen").
*   **Configurable Offsets**: Fine-tune your alarms by setting specific offsets to ring exactly when you need—minutes before or after the sun event occurs.
*   **Customizable Alerts**: Assign unique vibration patterns to different profiles, such as "Steady", "Heartbeat", "Siren", or "Quick Pulses", ensuring your wake-up experience matches your needs.

### 4. Live Weather & Environmental Insights
Plan your day with built-in environmental data.
*   **Weather Forecasting**: Access up-to-date weather forecasts to prepare for your outdoor activities or travel.
*   **Air Quality Index (AQI)**: Monitor real-time air quality in your current location so you can make health-informed, safe decisions throughout the day.

### 5. Weekly Solar Trends
Visualize your upcoming week using interactive tools.
*   **Solar Timing Chart**: A clear, weekly D3.js interactive chart showing granular sunrise and sunset timing trends for your location. This helps you anticipate the gradual shift in daylight hours.

### 6. Adaptive User Experience
*   **Dynamic Theming**: Intelligent theme switching (Light/Dark/Auto) based on user preference and local environmental context for comfortable viewing at any hour.
*   **Optimized Performance**: Built with modern, power-efficient architecture to provide a smooth, responsive, and highly polished experience while preserving battery life.

## How It Works

*   **Precise Solar Calculation**: Early Rover utilizes high-precision astronomical algorithms alongside device location services to calculate sun events for any given latitude, longitude, and timezone.
*   **Reliable Alarm Scheduling**: The app leverages the native Android `AlarmManager` for precise, system-level alarm triggering. This ensures your alarms fire reliably even if the phone has been idle or the app is in the background.
*   **Robust Data Persistence**: We use a local Room Database to securely store your locations, custom alarm profiles, and system settings, ensuring fast and reliable operation, even without an active internet connection.
*   **Modern Architecture**: Built using industry-standard Clean Architecture principles (MVVM, Kotlin Coroutines, Jetpack Compose, KSP) to ensure a clean, reactive, maintainable, and stable codebase.
