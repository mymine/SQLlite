package com.dbstudio.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import com.dbstudio.app.data.AppPrefs
import com.dbstudio.app.ui.AppRoot
import com.dbstudio.app.ui.theme.DBStudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            val dark = when (AppPrefs.themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            DBStudioTheme(darkTheme = dark) {
                AppRoot()
            }
        }
    }
}
