package com.rwa.wienerlinien

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rwa.wienerlinien.ui.DepartureViewModel
import com.rwa.wienerlinien.ui.MainScreen
import com.rwa.wienerlinien.ui.theme.WienerLinienTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: DepartureViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark"  -> true
                else    -> isSystemInDarkTheme()
            }
            WienerLinienTheme(darkTheme = darkTheme) {
                MainScreen(vm = vm)
            }
        }
    }
}
