package com.dmrandevu.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.dmrandevu.gallery.ui.GalleryScreen
import com.dmrandevu.gallery.ui.LoginScreen

/**
 * Single activity, single state toggle: Login ↔ Gallery.
 * A navigation library would only add indirection for two destinations.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceLocator.init(applicationContext)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color(0xFF15151A))) {
                Surface(color = Color.Black) {
                    // The resolved Instagram id doubles as the "logged in" flag: we only ever
                    // reach the gallery by resolving an account, which requires a live session.
                    var igId by rememberSaveable { mutableStateOf<String?>(null) }

                    val currentIgId = igId
                    if (currentIgId == null) {
                        LoginScreen(onAuthenticated = { igId = it })
                    } else {
                        GalleryScreen(igId = currentIgId, onSessionLost = { igId = null })
                    }
                }
            }
        }
    }
}
