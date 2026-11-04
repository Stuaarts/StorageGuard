package com.storageguard.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")
data class Preferences(val welcomed: Boolean = false, val sources: Set<String> = emptySet(), val permanentDelete: Boolean = false)
class PreferenceStore(private val context: Context) {
    private val welcome = booleanPreferencesKey("welcomed")
    private val sources = stringSetPreferencesKey("sources")
    private val permanent = booleanPreferencesKey("permanent_delete")
    val flow = context.dataStore.data.map { Preferences(it[welcome] ?: false, it[sources] ?: emptySet(), it[permanent] ?: false) }
    suspend fun welcome() { context.dataStore.edit { it[welcome] = true } }
    suspend fun addSource(value: String) { context.dataStore.edit { it[sources] = (it[sources] ?: emptySet()) + value } }
    suspend fun removeSource(value: String) { context.dataStore.edit { it[sources] = (it[sources] ?: emptySet()) - value } }
    suspend fun permanent(value: Boolean) { context.dataStore.edit { it[permanent] = value } }
    suspend fun clear() { context.dataStore.edit { it.clear() } }
}
