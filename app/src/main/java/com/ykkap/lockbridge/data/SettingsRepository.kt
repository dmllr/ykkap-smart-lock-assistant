package com.ykkap.lockbridge.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class MqttSettings(
  val isEnabled: Boolean = true,
  val broker: String = "192.168.1.100",
  val port: Int = 1883,
  val username: String = "",
  val password: String = ""
)

data class WebServerSettings(
  val isEnabled: Boolean = false,
  val port: Int = 8080
)

data class AppSettings(
  val mqttSettings: MqttSettings = MqttSettings(),
  val webServerSettings: WebServerSettings = WebServerSettings()
)

class SettingsRepository(private val context: Context) {

  private object PreferencesKeys {
    val MQTT_ENABLED = booleanPreferencesKey("mqtt_enabled")
    val MQTT_BROKER = stringPreferencesKey("mqtt_broker")
    val MQTT_PORT = intPreferencesKey("mqtt_port")
    val MQTT_USERNAME = stringPreferencesKey("mqtt_username")
    val MQTT_PASSWORD = stringPreferencesKey("mqtt_password")

    val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
    val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
  }

  val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { preferences ->
    val mqttSettings = MqttSettings(
      isEnabled = preferences[PreferencesKeys.MQTT_ENABLED] ?: true,
      broker = preferences[PreferencesKeys.MQTT_BROKER] ?: "192.168.1.100",
      port = preferences[PreferencesKeys.MQTT_PORT] ?: 1883,
      username = preferences[PreferencesKeys.MQTT_USERNAME] ?: "",
      password = preferences[PreferencesKeys.MQTT_PASSWORD] ?: ""
    )
    val webServerSettings = WebServerSettings(
      isEnabled = preferences[PreferencesKeys.WEB_SERVER_ENABLED] ?: false,
      port = preferences[PreferencesKeys.WEB_SERVER_PORT] ?: 8080
    )
    AppSettings(mqttSettings, webServerSettings)
  }

  suspend fun updateAppSettings(settings: AppSettings) {
    context.dataStore.edit { preferences ->
      preferences[PreferencesKeys.MQTT_ENABLED] = settings.mqttSettings.isEnabled
      preferences[PreferencesKeys.MQTT_BROKER] = settings.mqttSettings.broker
      preferences[PreferencesKeys.MQTT_PORT] = settings.mqttSettings.port
      preferences[PreferencesKeys.MQTT_USERNAME] = settings.mqttSettings.username
      preferences[PreferencesKeys.MQTT_PASSWORD] = settings.mqttSettings.password
      preferences[PreferencesKeys.WEB_SERVER_ENABLED] = settings.webServerSettings.isEnabled
      preferences[PreferencesKeys.WEB_SERVER_PORT] = settings.webServerSettings.port
    }
  }
}
