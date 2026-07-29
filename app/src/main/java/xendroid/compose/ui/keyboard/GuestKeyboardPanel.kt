package xendroid.compose.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xendroid.compose.Emulator

/**
 * Answers a guest text-entry prompt (XamShowKeyboardUI).
 *
 * An in-window Surface, not a Dialog: a Dialog takes window focus and would trip
 * the host activity's focus-loss pause. The emulator holds a kernel dispatch
 * thread until answered, so [onAccept]/[onCancel] must fire for every request.
 *
 * Laid out for a landscape-locked window with windowSoftInputMode=adjustNothing:
 * the IME leaves very little height and the window never resizes, so the panel is
 * top-aligned rather than centred, which also survives the API 29 devices that do
 * not report IME insets at all.
 */
@Composable
fun GuestKeyboardPanel(
    request: Emulator.KeyboardRequest,
    onAccept: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxUnits = request.maxLength.let { if (it <= 0) Int.MAX_VALUE else it }
    var text by remember(request.id) {
        mutableStateOf(clampToUtf16Units(request.defaultText.orEmpty(), maxUnits))
    }
    val focusRequester = remember(request.id) { FocusRequester() }

    LaunchedEffect(request.id) { focusRequester.requestFocus() }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            // Swallow taps meant for the game surface underneath.
            .pointerInput(request.id) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val compact = maxHeight < 400.dp
        val outerPadding = if (compact) 8.dp else 24.dp
        val innerPadding = if (compact) 12.dp else 20.dp

        Surface(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(outerPadding),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
            ) {
                val description = request.description.orEmpty()
                if (description.isNotEmpty()) {
                    Text(
                        description,
                        maxLines = if (compact) 1 else 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = clampToUtf16Units(it, maxUnits) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (description.isEmpty()) 0.dp
                                 else if (compact) 8.dp else 16.dp)
                        .heightIn(min = 56.dp)
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAccept(text) }),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    TextButton(onClick = { onAccept(text) }) { Text("OK") }
                }
            }
        }
    }
}

/** Trims to [maxUnits] UTF-16 code units without splitting a surrogate pair. */
internal fun clampToUtf16Units(text: String, maxUnits: Int): String {
    if (maxUnits <= 0) return ""
    if (text.length <= maxUnits) return text
    var cut = maxUnits
    if (Character.isHighSurrogate(text[cut - 1])) cut--
    return text.substring(0, cut)
}
