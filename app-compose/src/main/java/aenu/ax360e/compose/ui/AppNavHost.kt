package aenu.ax360e.compose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import aenu.ax360e.compose.AppContainer
import aenu.ax360e.compose.ui.library.GameLibraryScreen
import aenu.ax360e.compose.ui.library.GameLibraryViewModel

object Routes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"   // SP1 later
    const val KEYMAP = "keymap"       // SP1 later
    const val ABOUT = "about"         // SP1 later
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
            )
        }
        composable(Routes.SETTINGS) { Placeholder("Settings — SP1 later") }
        composable(Routes.KEYMAP) { Placeholder("Key mapping — SP1 later") }
        composable(Routes.ABOUT) { Placeholder("About — SP1 later") }
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
