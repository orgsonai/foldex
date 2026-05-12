package com.zerotoship.foldex.ui.main

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.ui.connections.ConnectionsScreen
import com.zerotoship.foldex.ui.filebrowser.FileBrowserScreen
import com.zerotoship.foldex.ui.filebrowser.FileBrowserViewModel
import com.zerotoship.foldex.ui.servers.ServerEditScreen
import com.zerotoship.foldex.ui.servers.ServerLogScreen
import com.zerotoship.foldex.ui.servers.ServersScreen
import com.zerotoship.foldex.ui.settings.OpenWithSettingsScreen
import com.zerotoship.foldex.ui.settings.SettingsScreen
import com.zerotoship.foldex.ui.settings.TrashScreen
import com.zerotoship.foldex.ui.sync.SyncBackupScreen
import com.zerotoship.foldex.ui.sync.SyncJobEditScreen
import com.zerotoship.foldex.ui.sync.SyncJobsScreen

private enum class TopTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    FILES("files", "ファイル", Icons.Filled.Folder, Icons.Outlined.Folder),
    CONNECTIONS("connections", "接続", Icons.Filled.Lan, Icons.Outlined.Lan),
    SERVER("server", "サーバー", Icons.Filled.Storage, Icons.Outlined.Storage),
    SYNC("sync", "同期", Icons.Filled.Sync, Icons.Outlined.Sync),
    SETTINGS("settings", "設定", Icons.Filled.Settings, Icons.Outlined.Settings),
}

@Composable
fun MainScreen(browserViewModel: FileBrowserViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    fun selectTab(tab: TopTab) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        // 各画面が自前の Scaffold/TopAppBar で system bar を処理するため、外側は bottomBar 分のみ
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                TopTab.entries.forEach { tab ->
                    val selected = currentRoute == tab.route ||
                        currentRoute?.startsWith(tab.route + "/") == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectTab(tab) },
                        icon = {
                            Icon(
                                if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopTab.FILES.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopTab.FILES.route) {
                FileBrowserScreen(viewModel = browserViewModel)
            }
            composable(TopTab.CONNECTIONS.route) {
                ConnectionsScreen(
                    onBack = { selectTab(TopTab.FILES) },
                    onOpen = { connection ->
                        if (connection is Connection.Smb) {
                            browserViewModel.openSmbConnection(connection.id, connection.name)
                            selectTab(TopTab.FILES)
                        }
                    },
                )
            }
            composable(TopTab.SERVER.route) {
                ServersScreen(
                    onBack = { selectTab(TopTab.FILES) },
                    onAdd = { navController.navigate("server/new") },
                    onEdit = { config -> navController.navigate("server/edit/${config.id}") },
                    onOpenLogs = { config -> navController.navigate("server/log/${config.id}") },
                )
            }
            composable("server/new") {
                ServerEditScreen(onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
            }
            composable(
                route = "server/edit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) {
                ServerEditScreen(onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
            }
            composable(
                route = "server/log/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) {
                ServerLogScreen(onBack = { navController.popBackStack() })
            }
            composable(TopTab.SYNC.route) {
                SyncJobsScreen(
                    onBack = { selectTab(TopTab.FILES) },
                    onAdd = { navController.navigate("sync/new") },
                    onEdit = { job -> navController.navigate("sync/edit/${job.id}") },
                    onOpenBackups = { job -> navController.navigate("sync/backup/${job.id}") },
                )
            }
            composable(
                route = "sync/backup/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) {
                SyncBackupScreen(onBack = { navController.popBackStack() })
            }
            composable("sync/new") {
                SyncJobEditScreen(onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
            }
            composable(
                route = "sync/edit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) {
                SyncJobEditScreen(onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
            }
            composable(TopTab.SETTINGS.route) {
                SettingsScreen(
                    onOpenFileTypes = { navController.navigate("settings/open-with") },
                    onOpenTrash = { navController.navigate("settings/trash") },
                )
            }
            composable("settings/open-with") {
                OpenWithSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/trash") {
                TrashScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
