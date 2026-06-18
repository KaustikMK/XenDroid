package xendroid.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import xendroid.compose.ui.AppNavHost
import xendroid.compose.ui.theme.xendroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Application.onCreate already ran the GPU probe + (on Adreno 830) eager
        // libe.so load. Build the manual-DI container off applicationContext.
        val container = AppContainer(applicationContext)
        enableEdgeToEdge()
        setContent {
            xendroidTheme {
                // Each screen owns its own Scaffold/TopAppBar chrome (GameLibraryScreen
                // already does), so no outer Scaffold here — avoids double insets.
                AppNavHost(container)
            }
        }
    }
}
