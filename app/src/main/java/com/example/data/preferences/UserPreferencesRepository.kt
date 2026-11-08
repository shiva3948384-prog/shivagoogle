package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.models.AiModelCatalog
import com.example.domain.models.AppPreferences
import com.example.domain.models.ThemeMode
import com.example.domain.repositories.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nexus_ai_preferences")

class UserPreferencesRepository(
    private val context: Context
) : PreferencesRepository {

    companion object {
        private const val SYNC_PREFS_NAME = "smarter_ai_sync_prefs"
        private const val KEY_SYNC_THEME_MODE = "theme_mode"
        private const val KEY_SYNC_DEFAULT_MODEL = "default_model_id"
        private const val KEY_SYNC_FALLBACK_MODEL = "fallback_model_id"
        private const val KEY_SYNC_ALLOW_FALLBACK = "allow_model_fallback"
        private const val KEY_SYNC_ENTER_TO_SEND = "enter_to_send"
        private const val KEY_SYNC_REDUCED_ANIM = "reduced_animations"
        private const val KEY_SYNC_STREAM_RESPONSES = "stream_responses"
        private const val KEY_SYNC_RENDER_MARKDOWN = "render_markdown"
        private const val KEY_SYNC_PERSONAL_INTELLIGENCE = "personal_intelligence_enabled"
    }

    private val syncPrefs = context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEFAULT_MODEL_ID = stringPreferencesKey("default_model_id")
        val FALLBACK_MODEL_ID = stringPreferencesKey("fallback_model_id")
        val ALLOW_MODEL_FALLBACK = booleanPreferencesKey("allow_model_fallback")
        val ENTER_TO_SEND = booleanPreferencesKey("enter_to_send")
        val REDUCED_ANIMATIONS = booleanPreferencesKey("reduced_animations")
        val STREAM_RESPONSES = booleanPreferencesKey("stream_responses")
        val RENDER_MARKDOWN = booleanPreferencesKey("render_markdown")
        val PERSONAL_INTELLIGENCE_ENABLED = booleanPreferencesKey("personal_intelligence_enabled")
    }

    override fun getInitialPreferences(): AppPreferences {
        val rawTheme = syncPrefs.getString(KEY_SYNC_THEME_MODE, ThemeMode.DARK.name)
        val theme = runCatching { ThemeMode.valueOf(rawTheme ?: ThemeMode.DARK.name) }
            .getOrDefault(ThemeMode.DARK)
        return AppPreferences(
            themeMode = theme,
            defaultModelId = syncPrefs.getString(KEY_SYNC_DEFAULT_MODEL, AiModelCatalog.DEFAULT_MODEL_ID)
                ?: AiModelCatalog.DEFAULT_MODEL_ID,
            fallbackModelId = syncPrefs.getString(KEY_SYNC_FALLBACK_MODEL, null)?.takeIf { it.isNotBlank() },
            allowModelFallback = syncPrefs.getBoolean(KEY_SYNC_ALLOW_FALLBACK, false),
            enterToSend = syncPrefs.getBoolean(KEY_SYNC_ENTER_TO_SEND, false),
            reducedAnimations = syncPrefs.getBoolean(KEY_SYNC_REDUCED_ANIM, false),
            streamResponses = syncPrefs.getBoolean(KEY_SYNC_STREAM_RESPONSES, true),
            renderMarkdown = syncPrefs.getBoolean(KEY_SYNC_RENDER_MARKDOWN, true),
            personalIntelligenceEnabled = syncPrefs.getBoolean(KEY_SYNC_PERSONAL_INTELLIGENCE, true)
        )
    }

    override val preferencesFlow: Flow<AppPreferences> = context.dataStore.data.map { prefs ->
        val rawTheme = prefs[Keys.THEME_MODE] ?: syncPrefs.getString(KEY_SYNC_THEME_MODE, ThemeMode.DARK.name)
        val theme = runCatching { ThemeMode.valueOf(rawTheme ?: ThemeMode.DARK.name) }
            .getOrDefault(ThemeMode.DARK)
        val defaultModelId = prefs[Keys.DEFAULT_MODEL_ID]
            ?: syncPrefs.getString(KEY_SYNC_DEFAULT_MODEL, AiModelCatalog.DEFAULT_MODEL_ID)
            ?: AiModelCatalog.DEFAULT_MODEL_ID
        val fallbackModelId = (prefs[Keys.FALLBACK_MODEL_ID] ?: syncPrefs.getString(KEY_SYNC_FALLBACK_MODEL, null))
            ?.takeIf { it.isNotBlank() }
        val allowFallback = prefs[Keys.ALLOW_MODEL_FALLBACK] ?: syncPrefs.getBoolean(KEY_SYNC_ALLOW_FALLBACK, false)
        val enterToSend = prefs[Keys.ENTER_TO_SEND] ?: syncPrefs.getBoolean(KEY_SYNC_ENTER_TO_SEND, false)
        val reducedAnimations = prefs[Keys.REDUCED_ANIMATIONS] ?: syncPrefs.getBoolean(KEY_SYNC_REDUCED_ANIM, false)
        val streamResponses = prefs[Keys.STREAM_RESPONSES] ?: syncPrefs.getBoolean(KEY_SYNC_STREAM_RESPONSES, true)
        val renderMarkdown = prefs[Keys.RENDER_MARKDOWN] ?: syncPrefs.getBoolean(KEY_SYNC_RENDER_MARKDOWN, true)
        val personalIntelligenceEnabled = prefs[Keys.PERSONAL_INTELLIGENCE_ENABLED]
            ?: syncPrefs.getBoolean(KEY_SYNC_PERSONAL_INTELLIGENCE, true)

        syncPrefs.edit()
            .putString(KEY_SYNC_THEME_MODE, theme.name)
            .putString(KEY_SYNC_DEFAULT_MODEL, defaultModelId)
            .putString(KEY_SYNC_FALLBACK_MODEL, fallbackModelId ?: "")
            .putBoolean(KEY_SYNC_ALLOW_FALLBACK, allowFallback)
            .putBoolean(KEY_SYNC_ENTER_TO_SEND, enterToSend)
            .putBoolean(KEY_SYNC_REDUCED_ANIM, reducedAnimations)
            .putBoolean(KEY_SYNC_STREAM_RESPONSES, streamResponses)
            .putBoolean(KEY_SYNC_RENDER_MARKDOWN, renderMarkdown)
            .putBoolean(KEY_SYNC_PERSONAL_INTELLIGENCE, personalIntelligenceEnabled)
            .apply()

        AppPreferences(
            themeMode = theme,
            defaultModelId = defaultModelId,
            fallbackModelId = fallbackModelId,
            allowModelFallback = allowFallback,
            enterToSend = enterToSend,
            reducedAnimations = reducedAnimations,
            streamResponses = streamResponses,
            renderMarkdown = renderMarkdown,
            personalIntelligenceEnabled = personalIntelligenceEnabled
        )
    }

    override suspend fun setThemeMode(themeMode: ThemeMode) {
        syncPrefs.edit().putString(KEY_SYNC_THEME_MODE, themeMode.name).commit()
        context.dataStore.edit { it[Keys.THEME_MODE] = themeMode.name }
    }

    override suspend fun setDefaultModelId(modelId: String) {
        syncPrefs.edit().putString(KEY_SYNC_DEFAULT_MODEL, modelId).apply()
        context.dataStore.edit { it[Keys.DEFAULT_MODEL_ID] = modelId }
    }

    override suspend fun setFallbackModel(modelId: String?, allowFallback: Boolean) {
        syncPrefs.edit()
            .putString(KEY_SYNC_FALLBACK_MODEL, modelId ?: "")
            .putBoolean(KEY_SYNC_ALLOW_FALLBACK, allowFallback)
            .apply()
        context.dataStore.edit {
            it[Keys.FALLBACK_MODEL_ID] = modelId ?: ""
            it[Keys.ALLOW_MODEL_FALLBACK] = allowFallback
        }
    }

    override suspend fun setEnterToSend(enabled: Boolean) {
        syncPrefs.edit().putBoolean(KEY_SYNC_ENTER_TO_SEND, enabled).apply()
        context.dataStore.edit { it[Keys.ENTER_TO_SEND] = enabled }
    }

    override suspend fun setReducedAnimations(enabled: Boolean) {
        syncPrefs.edit().putBoolean(KEY_SYNC_REDUCED_ANIM, enabled).apply()
        context.dataStore.edit { it[Keys.REDUCED_ANIMATIONS] = enabled }
    }

    override suspend fun setStreamResponses(enabled: Boolean) {
        syncPrefs.edit().putBoolean(KEY_SYNC_STREAM_RESPONSES, enabled).apply()
        context.dataStore.edit { it[Keys.STREAM_RESPONSES] = enabled }
    }

    override suspend fun setRenderMarkdown(enabled: Boolean) {
        syncPrefs.edit().putBoolean(KEY_SYNC_RENDER_MARKDOWN, enabled).apply()
        context.dataStore.edit { it[Keys.RENDER_MARKDOWN] = enabled }
    }

    override suspend fun setPersonalIntelligenceEnabled(enabled: Boolean) {
        syncPrefs.edit().putBoolean(KEY_SYNC_PERSONAL_INTELLIGENCE, enabled).commit()
        context.dataStore.edit { it[Keys.PERSONAL_INTELLIGENCE_ENABLED] = enabled }
    }
}
