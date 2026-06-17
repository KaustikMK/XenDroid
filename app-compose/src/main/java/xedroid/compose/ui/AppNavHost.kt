package xedroid.compose.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import xedroid.compose.AppContainer
import xedroid.compose.gamepad.GamepadController
import xedroid.compose.gamepad.GamepadEditorScreen
import xedroid.compose.settings.GameSettingsViewModel
import xedroid.compose.settings.SettingsViewModel
import xedroid.compose.ui.library.GameLibraryScreen
import xedroid.compose.ui.library.GameLibraryViewModel
import xedroid.compose.ui.settings.PerGameSettingsScreen
import xedroid.compose.ui.settings.SettingsScreen
import xedroid.compose.ui.about.AboutScreen
import xedroid.compose.ui.keymap.KeymapScreen
import xedroid.compose.ui.keymap.KeymapViewModel

object Routes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val KEYMAP = "keymap"
    const val ABOUT = "about"
    const val USERDATA = "userdata"   // action, not a destination (see GameLibraryScreen)
    const val GAMEPAD_EDITOR = "gamepad_editor"
    const val PER_GAME_SETTINGS = "per_game_settings"   // "$PER_GAME_SETTINGS/{titleId}?name={name}"
}

@Composable
fun AppNavHost(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            val vm: GameLibraryViewModel =
                viewModel(factory = container.libraryViewModelFactory())
            GameLibraryScreen(
                viewModel = vm,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenKeymap = { nav.navigate(Routes.KEYMAP) },
                onOpenAbout = { nav.navigate(Routes.ABOUT) },
                onOpenTouchControls = { nav.navigate(Routes.GAMEPAD_EDITOR) },
                onOpenPerGameSettings = { titleId, name ->
                    nav.navigate("${Routes.PER_GAME_SETTINGS}/$titleId?name=${Uri.encode(name)}")
                },
            )
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = container.settingsViewModelFactory())
            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.KEYMAP) {
            val vm: KeymapViewModel =
                viewModel(factory = container.keymapViewModelFactory())
            KeymapScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.GAMEPAD_EDITOR) {
            val ctx = LocalContext.current
            val controller = remember { GamepadController(ctx.applicationContext) }
            GamepadEditorScreen(controller = controller, onDone = { nav.popBackStack() })
        }
        composable(
            route = "${Routes.PER_GAME_SETTINGS}/{titleId}?name={name}",
            arguments = listOf(
                navArgument("titleId") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { backStackEntry ->
            val titleId = backStackEntry.arguments?.getString("titleId") ?: return@composable
            val gameName = backStackEntry.arguments?.getString("name") ?: ""
            val vm: GameSettingsViewModel =
                viewModel(factory = container.gameSettingsViewModelFactory(titleId))
            PerGameSettingsScreen(vm = vm, gameName = gameName, onBack = { nav.popBackStack() })
        }
    }
}
