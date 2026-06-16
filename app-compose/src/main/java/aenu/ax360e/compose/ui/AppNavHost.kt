package aenu.ax360e.compose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import aenu.ax360e.compose.AppContainer
import aenu.ax360e.compose.gamepad.GamepadController
import aenu.ax360e.compose.gamepad.GamepadEditorScreen
import aenu.ax360e.compose.settings.SettingsViewModel
import aenu.ax360e.compose.ui.library.GameLibraryScreen
import aenu.ax360e.compose.ui.library.GameLibraryViewModel
import aenu.ax360e.compose.ui.settings.SettingsScreen

object Routes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val KEYMAP = "keymap"
    const val ABOUT = "about"
    const val USERDATA = "userdata"   // action, not a destination (see GameLibraryScreen)
    const val GAMEPAD_EDITOR = "gamepad_editor"
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
            )
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = container.settingsViewModelFactory())
            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.KEYMAP) {
            val vm: aenu.ax360e.compose.ui.keymap.KeymapViewModel =
                viewModel(factory = container.keymapViewModelFactory())
            aenu.ax360e.compose.ui.keymap.KeymapScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.ABOUT) {
            aenu.ax360e.compose.ui.about.AboutScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.GAMEPAD_EDITOR) {
            val ctx = LocalContext.current
            val controller = remember { GamepadController(ctx.applicationContext) }
            GamepadEditorScreen(controller = controller, onDone = { nav.popBackStack() })
        }
    }
}
