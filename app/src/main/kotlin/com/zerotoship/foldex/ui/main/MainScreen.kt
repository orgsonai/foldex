package com.zerotoship.foldex.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
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
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.home.HomeFunction
import com.zerotoship.foldex.ui.connections.ConnectionsScreen
import com.zerotoship.foldex.ui.filebrowser.FileBrowserScreen
import com.zerotoship.foldex.ui.filebrowser.FileBrowserViewModel
import com.zerotoship.foldex.ui.home.HomeScreen
import com.zerotoship.foldex.ui.home.openPermissionsSettings
import com.zerotoship.foldex.ui.home.rememberSafTreeLauncher
import com.zerotoship.foldex.ui.media.MediaCollectionScreen
import com.zerotoship.foldex.ui.media.MediaCollectionViewModel
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
    HOME("home", "HOME", Icons.Filled.Home, Icons.Outlined.Home),
    FILES("files", "ファイル", Icons.Filled.Folder, Icons.Outlined.Folder),
    CONNECTIONS("connections", "接続", Icons.Filled.Lan, Icons.Outlined.Lan),
    SERVER("server", "サーバー", Icons.Filled.Storage, Icons.Outlined.Storage),
    SYNC("sync", "同期", Icons.Filled.Sync, Icons.Outlined.Sync),
    SETTINGS("settings", "設定", Icons.Filled.Settings, Icons.Outlined.Settings),
}

@Composable
fun MainScreen(
    browserViewModel: FileBrowserViewModel,
    shortcutAction: String? = null,
    onShortcutConsumed: () -> Unit = {},
    sharedUris: List<android.net.Uri> = emptyList(),
    onSharedUrisConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val context = LocalContext.current

    fun selectTab(tab: TopTab) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // App Shortcuts (manifest 静的) から `foldex.shortcut` を受けて該当タブ/画面に遷移する。
    // 受け取りは MainActivity が `pendingShortcut` で保持し、一度処理したら null に戻す。
    androidx.compose.runtime.LaunchedEffect(shortcutAction) {
        when (shortcutAction) {
            "files" -> selectTab(TopTab.FILES)
            "connections" -> selectTab(TopTab.CONNECTIONS)
            "server" -> selectTab(TopTab.SERVER)
            "sync" -> selectTab(TopTab.SYNC)
            "settings" -> selectTab(TopTab.SETTINGS)
            "trash" -> {
                selectTab(TopTab.SETTINGS)
                navController.navigate("settings/trash")
            }
            null -> Unit
            else -> Unit
        }
        if (shortcutAction != null) onShortcutConsumed()
    }

    // ACTION_SEND/ACTION_SEND_MULTIPLE: 受け取った URI を FileBrowserViewModel に渡し、ファイルタブへ。
    androidx.compose.runtime.LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            browserViewModel.receiveSharedFiles(sharedUris)
            selectTab(TopTab.FILES)
            onSharedUrisConsumed()
        }
    }

    // HOME 以外の **タブルート** にいるときに端末の戻るボタンが押されたら、(子の BackHandler が
    // 処理しなければ) 終了ではなく HOME に戻す。サブルート (server/new, settings/trash 等) は
    // NavController の通常の popBackStack に任せる。
    val isTabRoot = currentRoute != null && TopTab.entries.any { it.route == currentRoute }
    BackHandler(enabled = isTabRoot && currentRoute != TopTab.HOME.route) {
        selectTab(TopTab.HOME)
    }

    // SAF ピッカー (HOME の SAF タイル or 権限タイルから呼ぶ用)。選択後はファイルブラウザで開く。
    val safLauncher = rememberSafTreeLauncher { treeUri ->
        browserViewModel.onSafRootPicked(treeUri)
        selectTab(TopTab.FILES)
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
            startDestination = TopTab.HOME.route,
            modifier = Modifier.padding(innerPadding),
            // タブ/サブ画面切替の既定フェードを無効化 (体感を瞬時切替に)。
            enterTransition = { androidx.compose.animation.EnterTransition.None },
            exitTransition = { androidx.compose.animation.ExitTransition.None },
            popEnterTransition = { androidx.compose.animation.EnterTransition.None },
            popExitTransition = { androidx.compose.animation.ExitTransition.None },
        ) {
            composable(TopTab.HOME.route) {
                HomeScreen(
                    browserViewModel = browserViewModel,
                    onOpenLocalFolder = { path ->
                        browserViewModel.open(FileUri.Local(path), displayName = path.substringAfterLast('/').ifEmpty { path })
                        selectTab(TopTab.FILES)
                    },
                    onOpenFunction = { fn ->
                        when (fn) {
                            HomeFunction.INTERNAL_STORAGE -> {
                                browserViewModel.openLocalRoot()
                                selectTab(TopTab.FILES)
                            }
                            HomeFunction.TRASH -> navController.navigate("settings/trash")
                            HomeFunction.SERVERS -> selectTab(TopTab.SERVER)
                            HomeFunction.SYNC -> selectTab(TopTab.SYNC)
                            HomeFunction.SETTINGS -> selectTab(TopTab.SETTINGS)
                            HomeFunction.PERMISSIONS -> openPermissionsSettings(context)
                            HomeFunction.SAF_PICK -> safLauncher.launch(null)
                            HomeFunction.ALL_IMAGES -> navController.navigate("media/image")
                            HomeFunction.ALL_VIDEOS -> navController.navigate("media/video")
                        }
                    },
                    onOpenConnection = { conn ->
                        // 4 種別の Remote を FileBrowser で開く。initialPath があればそこへ navigate。
                        val initial = conn.initialPath.ifBlank { "/" }
                        when (conn) {
                            is com.zerotoship.foldex.core.model.Connection.Smb ->
                                browserViewModel.openSmbConnection(conn.id, conn.name, initial)
                            else -> browserViewModel.open(
                                com.zerotoship.foldex.core.model.FileUri.Remote(conn.protocol, conn.id, initial),
                                displayName = conn.name,
                            )
                        }
                        selectTab(TopTab.FILES)
                    },
                    onOpenUri = { uri, name ->
                        browserViewModel.open(uri, displayName = name)
                        selectTab(TopTab.FILES)
                    },
                    onPickFolder = { safLauncher.launch(null) },
                )
            }
            composable(TopTab.FILES.route) {
                FileBrowserScreen(viewModel = browserViewModel)
            }
            composable(TopTab.CONNECTIONS.route) {
                ConnectionsScreen(
                    onBack = { selectTab(TopTab.FILES) },
                    onOpen = { connection ->
                        // 全プロトコルを開けるようにする。SMB は専用ヘルパ、
                        // それ以外は Remote URI で initialPath (なければ "/") を開く。
                        val initial = connection.initialPath.ifBlank { "/" }
                        when (connection) {
                            is Connection.Smb ->
                                browserViewModel.openSmbConnection(connection.id, connection.name, initial)
                            else -> browserViewModel.open(
                                com.zerotoship.foldex.core.model.FileUri.Remote(
                                    connection.protocol,
                                    connection.id,
                                    initial,
                                ),
                                displayName = connection.name,
                            )
                        }
                        selectTab(TopTab.FILES)
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
                    onOpenLogs = { navController.navigate("settings/logs") },
                    onOpenLicenses = { navController.navigate("settings/licenses") },
                )
            }
            composable("settings/open-with") {
                OpenWithSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/licenses") {
                com.zerotoship.foldex.ui.settings.LicensesScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/trash") {
                TrashScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/logs") {
                com.zerotoship.foldex.ui.settings.AppLogScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = "media/{kind}",
                arguments = listOf(navArgument(MediaCollectionViewModel.ARG_KIND) { type = NavType.StringType }),
            ) {
                MediaCollectionScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLocalFolder = { path ->
                        browserViewModel.open(
                            FileUri.Local(path),
                            displayName = path.substringAfterLast('/').ifEmpty { path },
                        )
                        selectTab(TopTab.FILES)
                    },
                )
            }
        }
    }
}
