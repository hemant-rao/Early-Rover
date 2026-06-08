package com.example.alarm.weather

object WeatherAdviser {
    fun getAdvice(condition: WeatherCondition, precipitationMm: Double): String? {
        return when {
            precipitationMm > 5.0 -> "Heavy rain expected. Not recommended for a walk."
            condition == WeatherCondition.THUNDER -> "Thunderstorm expected. Stay indoors."
            condition == WeatherCondition.FOG -> "Foggy conditions. Visibility is low."
            precipitationMm > 0.1 -> "Light rain expected."
            else -> "Weather looks good for your morning routine."
        }
    }
}
