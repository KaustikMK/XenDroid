package aenu.ax360e.compose.ui.library

import aenu.ax360e.compose.data.Game

/** Drives the library screen. NoFolder vs empty Loaded are deliberately distinct
 *  so the UI shows "pick a folder" vs "folder has no games". */
sealed interface LibraryUiState {
    /** No SAF tree uri persisted yet -> prompt to pick a folder. */
    data object NoFolder : LibraryUiState
    /** Scanning the chosen folder. */
    data object Loading : LibraryUiState
    /** Scan finished. [games] may be empty (folder has no recognized titles). */
    data class Loaded(val games: List<Game>) : LibraryUiState
    /** Persisted tree uri exists but the SAF grant is gone/invalid. */
    data object PermissionLost : LibraryUiState
    /** Device has no Vulkan -> emulator cannot run; hard gate. */
    data object NoVulkan : LibraryUiState
    /** Scan/load failed unexpectedly -> show the message + a Retry. */
    data class Error(val message: String) : LibraryUiState
}
