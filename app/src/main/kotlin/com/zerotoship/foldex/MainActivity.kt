package com.zerotoship.foldex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zerotoship.foldex.ui.connections.ConnectionsScreen
import com.zerotoship.foldex.ui.filebrowser.FileBrowserScreen
import com.zerotoship.foldex.ui.filebrowser.FileBrowserViewModel
import com.zerotoship.foldex.core.model.Connection
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import com.zerotoship.foldex.ui.theme.FoldexTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FoldexTheme {
                val navController = rememberNavController()
                // Activity スコープの ViewModel — browser/connections の両ルートで共有する
                val browserViewModel: FileBrowserViewModel = hiltViewModel(this@MainActivity)
                NavHost(navController = navController, startDestination = "browser") {
                    composable("browser") {
                        FileBrowserScreen(
                            onOpenConnections = { navController.navigate("connections") },
                            viewModel = browserViewModel,
                        )
                    }
                    composable("connections") {
                        ConnectionsScreen(
                            onBack = { navController.popBackStack() },
                            onOpen = { connection ->
                                if (connection is Connection.Smb) {
                                    browserViewModel.openSmbConnection(connection.id, connection.name)
                                    navController.popBackStack()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
