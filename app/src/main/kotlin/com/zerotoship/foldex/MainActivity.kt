package com.zerotoship.foldex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zerotoship.foldex.ui.filebrowser.FileBrowserScreen
import com.zerotoship.foldex.ui.theme.FoldexTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FoldexTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "browser") {
                    composable("browser") {
                        FileBrowserScreen()
                    }
                }
            }
        }
    }
}
