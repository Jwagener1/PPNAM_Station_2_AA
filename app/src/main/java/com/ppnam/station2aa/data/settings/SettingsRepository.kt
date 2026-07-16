package com.ppnam.station2aa.data.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.ppnam.station2aa.domain.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "app_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val STATION_NAME            = stringPreferencesKey("station_name")
        val DEVICE_ID               = stringPreferencesKey("device_id")
        val SCANNER_ID              = intPreferencesKey("scanner_id")
        val MQTT_HOST               = stringPreferencesKey("mqtt_host")
        val MQTT_PORT               = intPreferencesKey("mqtt_port")
        val MQTT_USE_WEBSOCKET      = booleanPreferencesKey("mqtt_use_websocket")
        val MQTT_USE_TLS            = booleanPreferencesKey("mqtt_use_tls")
        val MQTT_USERNAME           = stringPreferencesKey("mqtt_username")
        val MQTT_PASSWORD           = stringPreferencesKey("mqtt_password")
        val REQUEST_TIMEOUT_MS      = longPreferencesKey("request_timeout_ms")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            stationName          = prefs[Keys.STATION_NAME]         ?: "Station 2",
            deviceId              = prefs[Keys.DEVICE_ID]            ?: "handheld_1",
            scannerId            = prefs[Keys.SCANNER_ID]           ?: 1,
            mqttHost             = prefs[Keys.MQTT_HOST]            ?: "mqtt.sysone.co.za",
            mqttPort             = prefs[Keys.MQTT_PORT]            ?: 8884,
            mqttUseWebSocket     = prefs[Keys.MQTT_USE_WEBSOCKET]   ?: true,
            mqttUseTls           = prefs[Keys.MQTT_USE_TLS]         ?: true,
            mqttUsername         = prefs[Keys.MQTT_USERNAME]        ?: "admin",
            mqttPassword         = prefs[Keys.MQTT_PASSWORD]        ?: "admin",
            requestTimeoutMs     = prefs[Keys.REQUEST_TIMEOUT_MS]   ?: 10_000L
        )
    }

    suspend fun current(): AppSettings = settingsFlow.first()

    suspend fun save(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.STATION_NAME]        = settings.stationName
            prefs[Keys.DEVICE_ID]           = settings.deviceId
            prefs[Keys.SCANNER_ID]          = settings.scannerId
            prefs[Keys.MQTT_HOST]           = settings.mqttHost
            prefs[Keys.MQTT_PORT]           = settings.mqttPort
            prefs[Keys.MQTT_USE_WEBSOCKET]  = settings.mqttUseWebSocket
            prefs[Keys.MQTT_USE_TLS]        = settings.mqttUseTls
            prefs[Keys.MQTT_USERNAME]       = settings.mqttUsername
            prefs[Keys.MQTT_PASSWORD]       = settings.mqttPassword
            prefs[Keys.REQUEST_TIMEOUT_MS]  = settings.requestTimeoutMs
        }
    }
}
