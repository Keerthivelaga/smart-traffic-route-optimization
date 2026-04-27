package com.smarttraffic.core_engine.data.local

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("smart_traffic_settings") },
    )

    val highContrast: Flow<Boolean> = dataStore.data.map { it[HIGH_CONTRAST] ?: false }
    val hapticsEnabled: Flow<Boolean> = dataStore.data.map { it[HAPTICS] ?: true }
    val textScalePct: Flow<Int> = dataStore.data.map { it[TEXT_SCALE] ?: 100 }

    suspend fun setHighContrast(enabled: Boolean) {
        dataStore.edit { it[HIGH_CONTRAST] = enabled }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        dataStore.edit { it[HAPTICS] = enabled }
    }

    suspend fun setTextScale(percent: Int) {
        dataStore.edit { it[TEXT_SCALE] = percent.coerceIn(85, 140) }
    }

    companion object {
        private val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        private val HAPTICS = booleanPreferencesKey("haptics_enabled")
        private val TEXT_SCALE = intPreferencesKey("text_scale")
    }
}

