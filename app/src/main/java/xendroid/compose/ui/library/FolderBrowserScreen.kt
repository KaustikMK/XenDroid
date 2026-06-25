package xendroid.compose.ui.library

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Self-contained java.io.File directory browser for REAL-PATH (All Files Access) mode.
 * Replaces the SAF OPEN_DOCUMENT_TREE picker, which MIUI/HyperOS refuses. Starts at the
 * primary external storage root, lists subdirectories only (sorted, hidden skipped), and
 * lets the user confirm the current directory as the games folder.
 *
 * listFiles() may return null for an unreadable directory (e.g. Android/data) even with
 * All Files Access -- that is handled as "no entries" rather than a crash.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowserScreen(
    onFolderChosen: (path: String) -> Unit,
    onCancel: () -> Unit,
) {
    val root = remember { Environment.getExternalStorageDirectory() ?: File("/") }
    var current by remember { mutableStateOf(root) }

    // Subdirectories of the current dir: directories only, no hidden, sorted by name.
    val subDirs = remember(current.absolutePath) {
        current.listFiles()
            ?.filter { it.isDirectory && !it.isHidden }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        current.absolutePath,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    // Up to parent, but never above the storage root.
                    val canGoUp = current.absolutePath != root.absolutePath &&
                        current.parentFile != null
                    IconButton(
                        onClick = {
                            if (canGoUp) current = current.parentFile!! else onCancel()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (canGoUp) "Up" else "Cancel",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Action row: confirm the CURRENT directory, or cancel.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onFolderChosen(current.absolutePath) }) {
                    Text("Use this folder")
                }
            }

            if (subDirs.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text(
                        "No sub-folders here. Use \"Use this folder\" to pick this directory.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(subDirs, key = { it.absolutePath }) { dir ->
                        ListItem(
                            headlineContent = {
                                Text(dir.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "Open",
                                )
                            },
                            modifier = Modifier.clickable { current = dir },
                        )
                    }
                }
            }
        }
    }
}
