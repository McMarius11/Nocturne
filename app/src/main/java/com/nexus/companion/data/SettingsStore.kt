package com.nexus.companion.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nexus_settings")

class SettingsStore(private val context: Context) {

    companion object {
        private val KEY_SELECTED_MODEL = stringPreferencesKey("selected_model_id")
        private val KEY_VOICE_PROFILE = stringPreferencesKey("voice_profile_id")
    }

    suspend fun getSelectedModelId(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_SELECTED_MODEL]
        }.first()
    }

    suspend fun setSelectedModelId(modelId: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SELECTED_MODEL] = modelId
        }
    }

    suspend fun getVoiceProfileId(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_VOICE_PROFILE]
        }.first()
    }

    suspend fun setVoiceProfileId(profileId: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VOICE_PROFILE] = profileId
        }
    }
}
