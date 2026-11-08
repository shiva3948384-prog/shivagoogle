package com.example

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import com.example.di.DefaultAppContainer
import com.example.domain.models.ThemeMode
import com.example.navigation.NexusAiAppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as? NexusAiApplication
        val container = app?.container ?: DefaultAppContainer(applicationContext)

        // Synchronously resolve the user's saved theme BEFORE super.onCreate / setContent
        // so the native window background and system bars never flash Dark when Light is selected.
        val initialPrefs = container.preferencesRepository.getInitialPreferences()
        val isSystemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val useDarkTheme = when (initialPrefs.themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> isSystemDark
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialBgColor = if (useDarkTheme) Color.parseColor("#090C12") else Color.parseColor("#F7F9FC")
        window.decorView.setBackgroundColor(initialBgColor)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !useDarkTheme
            isAppearanceLightNavigationBars = !useDarkTheme
        }

        setContent {
            NexusAiAppRoot(appContainer = container)
        }
    }
}
