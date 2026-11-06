/*
 * Copyright 2026, Nguyen-Do Consulting
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.applock

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.launcher3.R
import kotlinx.coroutines.delay

internal sealed interface AppLockScreen {
    /** Nothing drawn: the system prompt is up, or about to be. */
    data object None : AppLockScreen

    data class Pin(
        val purpose: PinPurpose,
        val error: String?,
        val lockoutRemaining: Long,
        val checking: Boolean = false,
    ) : AppLockScreen

    data class Failed(val message: String, val canForget: Boolean, val retry: () -> Unit) : AppLockScreen

    /** The fingerprint key was destroyed because a fingerprint or face was added. */
    data object KeyInvalidated : AppLockScreen

    /** Explains the reset wait before starting it. */
    data class ResetConfirm(val fromBackup: Boolean, val wait: Long, val count: Int) : AppLockScreen

    data class ResetPending(val remaining: Long, val canUnlock: Boolean) : AppLockScreen

    data class ChooseMethod(val methods: List<LockMethod>, val current: LockMethod?) : AppLockScreen

    /** "That was me / That wasn't me" after a reset was canceled or completed. */
    data class ResetChoice(val count: Int, val requestedAt: String) : AppLockScreen
}

internal enum class PinPurpose { UNLOCK, CREATE, CONFIRM }

internal interface AppLockActions {
    fun onPinEntered(purpose: PinPurpose, pin: String)
    fun onPinLockoutOver(purpose: PinPurpose)
    fun onForgot()
    fun onMethodChosen(method: LockMethod)
    fun onRequestReset()
    fun onUnlockNormally()
    fun onSetUpNow()
    fun onRefreshResetPending()
    fun onResetChoice(wasMe: Boolean)
    fun onCancel()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppLockContent(screen: AppLockScreen, actions: AppLockActions) {
    when (screen) {
        AppLockScreen.None -> Unit
        is AppLockScreen.Pin -> PinPad(screen, actions)
        else -> Surface(modifier = Modifier.fillMaxSize(), color = BottomSheetDefaults.ScrimColor) {
            when (screen) {
                is AppLockScreen.Failed -> LockDialog(
                    title = stringResource(R.string.app_lock_failed_title),
                    text = screen.message,
                    confirm = stringResource(R.string.app_lock_try_again) to screen.retry,
                    secondary = if (screen.canForget) stringResource(R.string.app_lock_forgot) to actions::onForgot else null,
                    onDismiss = actions::onCancel,
                )
                AppLockScreen.KeyInvalidated -> LockDialog(
                    title = stringResource(R.string.app_lock_key_invalidated_title),
                    text = stringResource(R.string.app_lock_key_invalidated_text),
                    confirm = stringResource(R.string.app_lock_reset) to actions::onForgot,
                    onDismiss = actions::onCancel,
                )
                is AppLockScreen.ResetConfirm -> {
                    val context = LocalContext.current
                    val wait = formatDuration(context, screen.wait)
                    val text = buildString {
                        append(
                            if (screen.fromBackup) {
                                stringResource(R.string.app_lock_reset_from_backup_text, wait)
                            } else {
                                stringResource(R.string.app_lock_reset_confirm_text, wait)
                            },
                        )
                        if (screen.count > 0) {
                            append("\n\n")
                            append(stringResource(R.string.app_lock_reset_count_text, screen.count + 1))
                        }
                    }
                    LockDialog(
                        title = stringResource(
                            if (screen.fromBackup) R.string.app_lock_reset_from_backup_title else R.string.app_lock_reset_confirm_title,
                        ),
                        text = text,
                        confirm = stringResource(R.string.app_lock_reset_request) to actions::onRequestReset,
                        onDismiss = actions::onCancel,
                    )
                }
                is AppLockScreen.ResetPending -> {
                    val ready = screen.remaining == 0L
                    if (!ready) {
                        LaunchedEffect(screen) {
                            delay(minOf(screen.remaining, 30_000L))
                            actions.onRefreshResetPending()
                        }
                    }
                    LockDialog(
                        title = stringResource(R.string.app_lock_reset_pending_title),
                        text = if (ready) {
                            stringResource(R.string.app_lock_reset_ready_text)
                        } else {
                            stringResource(R.string.app_lock_reset_pending_text, formatDuration(LocalContext.current, screen.remaining))
                        },
                        confirm = when {
                            ready -> stringResource(R.string.app_lock_set_up_now) to actions::onSetUpNow
                            screen.canUnlock -> stringResource(R.string.app_lock_unlock_to_cancel) to actions::onUnlockNormally
                            else -> null
                        },
                        secondary = if (ready && screen.canUnlock) {
                            stringResource(R.string.app_lock_unlock_to_cancel) to actions::onUnlockNormally
                        } else {
                            null
                        },
                        dismissLabel = stringResource(R.string.app_lock_close),
                        onDismiss = actions::onCancel,
                    )
                }
                is AppLockScreen.ChooseMethod -> ChooseMethodDialog(screen, actions)
                is AppLockScreen.ResetChoice -> {
                    BackHandler { actions.onResetChoice(wasMe = false) }
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(stringResource(R.string.app_lock_reset_choice_title)) },
                        text = { Text(stringResource(R.string.app_lock_reset_choice_text, screen.requestedAt, screen.count)) },
                        confirmButton = {
                            TextButton(onClick = { actions.onResetChoice(wasMe = false) }) {
                                Text(stringResource(R.string.app_lock_reset_choice_not_me))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { actions.onResetChoice(wasMe = true) }) {
                                Text(stringResource(R.string.app_lock_reset_choice_me))
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LockDialog(
    title: String,
    text: String,
    confirm: Pair<String, () -> Unit>?,
    onDismiss: () -> Unit,
    secondary: Pair<String, () -> Unit>? = null,
    dismissLabel: String = stringResource(android.R.string.cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(24.dp)) },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Row {
                secondary?.let { (label, action) -> TextButton(onClick = action) { Text(label) } }
                confirm?.let { (label, action) -> TextButton(onClick = action) { Text(label) } }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

@Composable
private fun ChooseMethodDialog(screen: AppLockScreen.ChooseMethod, actions: AppLockActions) {
    var selected by remember(screen) { mutableStateOf(screen.current ?: screen.methods.first()) }
    AlertDialog(
        onDismissRequest = actions::onCancel,
        title = { Text(stringResource(R.string.app_lock_method_label)) },
        text = {
            Column {
                screen.methods.forEach { method ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { selected = method }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == method, onClick = { selected = method })
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(stringResource(method.labelRes), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(method.descriptionRes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.onMethodChosen(selected) }) { Text(stringResource(R.string.app_lock_continue)) }
        },
        dismissButton = {
            TextButton(onClick = actions::onCancel) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun PinPad(screen: AppLockScreen.Pin, actions: AppLockActions) {
    var pin by remember(screen) { mutableStateOf("") }
    val locked = screen.lockoutRemaining > 0
    if (locked) {
        LaunchedEffect(screen) {
            delay(screen.lockoutRemaining)
            actions.onPinLockoutOver(screen.purpose)
        }
    }
    BackHandler(onBack = actions::onCancel)
    val enabled = !locked && !screen.checking
    val submit = {
        if (pin.length >= AppLockStore.MIN_PIN_LENGTH && enabled) actions.onPinEntered(screen.purpose, pin)
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(
                    when (screen.purpose) {
                        PinPurpose.UNLOCK -> R.string.app_lock_pin_enter
                        PinPurpose.CREATE -> R.string.app_lock_pin_create
                        PinPurpose.CONFIRM -> R.string.app_lock_pin_confirm
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            // One dot per digit typed, so the PIN itself never shows.
            Text(
                text = if (pin.isEmpty()) " " else "●".repeat(pin.length),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val message = when {
                locked -> stringResource(R.string.app_lock_pin_lockout, formatDuration(LocalContext.current, screen.lockoutRemaining))
                screen.error != null -> screen.error
                screen.purpose == PinPurpose.CREATE -> stringResource(R.string.app_lock_pin_length, AppLockStore.MIN_PIN_LENGTH)
                else -> " "
            }
            Text(
                text = message,
                color = if (locked || screen.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.weight(1f))
            Column(
                modifier = Modifier.widthIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { digit ->
                            PinKey(enabled = enabled, onClick = {
                                if (pin.length < AppLockStore.MAX_PIN_LENGTH) pin += digit
                            }) { Text(digit, style = MaterialTheme.typography.headlineSmall) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PinKey(enabled = enabled && pin.isNotEmpty(), onClick = { pin = pin.dropLast(1) }) {
                        Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = stringResource(R.string.app_lock_pin_delete))
                    }
                    PinKey(enabled = enabled, onClick = {
                        if (pin.length < AppLockStore.MAX_PIN_LENGTH) pin += "0"
                    }) { Text("0", style = MaterialTheme.typography.headlineSmall) }
                    PinKey(enabled = enabled && pin.length >= AppLockStore.MIN_PIN_LENGTH, onClick = submit) {
                        Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.app_lock_continue))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row {
                if (screen.purpose == PinPurpose.UNLOCK) {
                    TextButton(onClick = actions::onForgot) { Text(stringResource(R.string.app_lock_forgot)) }
                }
                TextButton(onClick = actions::onCancel) { Text(stringResource(android.R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PinKey(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1.4f),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** e.g. "10 minutes", "1 hour 20 minutes", "24 hours". Rounds up to the next minute. */
internal fun formatDuration(context: Context, millis: Long): String {
    val totalMinutes = ((millis + 59_999) / 60_000).coerceAtLeast(1)
    val hours = (totalMinutes / 60).toInt()
    val minutes = (totalMinutes % 60).toInt()
    val res = context.resources
    val hoursText = res.getQuantityString(R.plurals.app_lock_hours, hours, hours)
    val minutesText = res.getQuantityString(R.plurals.app_lock_minutes, minutes, minutes)
    return when {
        hours == 0 -> minutesText
        minutes == 0 -> hoursText
        else -> context.getString(R.string.app_lock_hours_minutes, hoursText, minutesText)
    }
}
