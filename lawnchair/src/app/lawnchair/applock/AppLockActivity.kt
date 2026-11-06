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

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import app.lawnchair.ui.theme.EdgeToEdge
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Asks for the locked apps' unlock, sets it up, and runs the reset wait. The system fingerprint
 * prompt needs an activity of its own, so every locked launch comes through here, see
 * [AppLockManager.requestUnlock]. See docs/ndc/locked-apps.md.
 */
class AppLockActivity : ComponentActivity() {

    private val manager by lazy { AppLockManager.getInstance(this) }
    private val store get() = manager.store
    private var screen by mutableStateOf<AppLockScreen>(AppLockScreen.None)
    private var finished = false
    private var promptSignal: CancellationSignal? = null

    /** A PIN typed once while setting one up, waiting to be typed again. */
    private var newPin: String? = null

    /** What a correct PIN leads to when it was asked for as the code, see [askCode]. */
    private var afterPin: (() -> Unit)? = null

    private val confirmPhoneLock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val confirmed = onPhoneLockConfirmed
        val canceled = onPhoneLockCanceled
        onPhoneLockConfirmed = null
        onPhoneLockCanceled = null
        if (it.resultCode == Activity.RESULT_OK) confirmed?.invoke() else canceled?.invoke()
    }
    private var onPhoneLockConfirmed: (() -> Unit)? = null
    private var onPhoneLockCanceled: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LawnchairTheme {
                EdgeToEdge()
                AppLockContent(
                    screen = screen,
                    actions = actions,
                )
            }
        }
        // Config changes don't recreate it (see the manifest), so this runs once per request.
        begin()
    }

    private fun begin() {
        val mode = intent.getIntExtra(EXTRA_MODE, MODE_UNLOCK)
        when {
            mode == MODE_SETUP -> {
                val method = LockMethod.fromId(intent.getStringExtra(EXTRA_METHOD))
                when {
                    store.isResetPending && store.resetRemaining() == 0L -> startSetup(method)
                    store.isResetPending -> showResetPending()
                    manager.wasJustCoded -> startSetup(method)
                    // No apps locked, e.g. the first setup: the phone's own screen lock guards it,
                    // so nobody can plant an unlock they know before apps get locked.
                    !manager.hasLockedApps -> verifyPhoneLock { startSetup(method) }
                    // Changing the unlock weakens it until the new one is set: the code first.
                    manager.isSetUp -> askCode { startSetup(method) }
                    else -> screen = resetConfirm(fromBackup = true)
                }
            }
            // Removing protection needs the code, never just a fingerprint or face.
            mode == MODE_WEAKEN -> when {
                !manager.hasLockedApps -> verifyPhoneLock { complete(true) }
                !manager.isSetUp -> screen = resetConfirm(fromBackup = true)
                else -> askCode(::afterSuccess)
            }
            store.isResetPending -> showResetPending()
            // No apps locked: the phone's own screen lock guards the settings instead.
            mode == MODE_VERIFY && !manager.hasLockedApps -> verifyPhoneLock { complete(true) }
            !manager.isSetUp -> {
                // Locked apps but no unlock: they came from a backup.
                if (manager.hasLockedApps) screen = resetConfirm(fromBackup = true) else complete(true)
            }
            else -> startUnlock()
        }
    }

    // ---- Normal unlock ----

    private fun startUnlock() {
        when (manager.method) {
            LockMethod.PIN -> showPin(PinPurpose.UNLOCK)
            LockMethod.BIOMETRIC_ONLY -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    screen = resetConfirm(fromBackup = true)
                    return
                }
                when (val result = BiometricKey.cipher(store.biometricAlias)) {
                    is BiometricKey.CipherResult.Ready -> showPrompt(
                        authenticators = Authenticators.BIOMETRIC_STRONG,
                        crypto = BiometricPrompt.CryptoObject(result.cipher),
                        negativeButton = getString(R.string.app_lock_forgot),
                        onNegative = { screen = resetConfirm(fromBackup = false) },
                        onSuccess = ::onUnlocked,
                    )
                    else -> screen = AppLockScreen.KeyInvalidated
                }
            }
            null -> screen = resetConfirm(fromBackup = true)
        }
    }

    private fun onUnlocked() {
        manager.onUnlocked()
        afterSuccess()
    }

    /**
     * Asks for the code, which removing protection needs: the separate PIN with [LockMethod.PIN],
     * otherwise the phone's own pattern, PIN or password, as a fingerprint or face may be someone
     * else's. Then runs [onOk].
     */
    private fun askCode(onOk: () -> Unit) {
        if (manager.method == LockMethod.PIN) {
            afterPin = onOk
            showPin(PinPurpose.UNLOCK)
        } else {
            verifyPhoneLock(onOk)
        }
    }

    /** Asks "That was me / That wasn't me" after a reset ended, then finishes. */
    private fun afterSuccess() {
        if (store.isResetChoicePending) {
            screen = AppLockScreen.ResetChoice(
                count = store.resetCount,
                requestedAt = DateFormat.getTimeFormat(this).format(Date(store.resetRequestedWallTime)),
            )
        } else {
            complete(true)
        }
    }

    // ---- PIN ----

    private fun showPin(purpose: PinPurpose, error: String? = null) {
        screen = AppLockScreen.Pin(purpose, error, store.pinLockoutRemaining())
    }

    private fun onPinEntered(purpose: PinPurpose, pin: String) {
        when (purpose) {
            PinPurpose.UNLOCK -> {
                if (store.pinLockoutRemaining() > 0) {
                    showPin(purpose)
                    return
                }
                screen = AppLockScreen.Pin(purpose, error = null, lockoutRemaining = 0, checking = true)
                lifecycleScope.launch {
                    val correct = withContext(Dispatchers.Default) { store.checkPin(pin) }
                    val action = afterPin
                    when {
                        !correct -> showPin(purpose, getString(R.string.app_lock_pin_wrong))
                        action != null -> {
                            afterPin = null
                            // The separate PIN is both the unlock and the code.
                            manager.onUnlocked()
                            manager.onCodeVerified()
                            action()
                        }
                        else -> onUnlocked()
                    }
                }
            }
            PinPurpose.CREATE -> {
                newPin = pin
                showPin(PinPurpose.CONFIRM)
            }
            PinPurpose.CONFIRM -> {
                if (pin != newPin) {
                    newPin = null
                    showPin(PinPurpose.CREATE, getString(R.string.app_lock_pin_mismatch))
                    return
                }
                newPin = null
                screen = AppLockScreen.Pin(purpose, error = null, lockoutRemaining = 0, checking = true)
                lifecycleScope.launch {
                    withContext(Dispatchers.Default) {
                        store.clearSecrets()
                        store.setPin(pin)
                    }
                    onSetUp(LockMethod.PIN)
                }
            }
        }
    }

    // ---- Setup ----

    /** Sets up [method], or lets the user choose one when it's null or can't be used. */
    private fun startSetup(method: LockMethod?) {
        if (method != null && manager.isAvailable(method)) onMethodChosen(method) else showChooseMethod()
    }

    private fun showChooseMethod() {
        screen = AppLockScreen.ChooseMethod(
            methods = LockMethod.entries.filter(manager::isAvailable),
            current = manager.method.takeIf { manager.isSetUp },
        )
    }

    private fun onMethodChosen(method: LockMethod) {
        when (method) {
            LockMethod.PIN -> showPin(PinPurpose.CREATE)
            LockMethod.BIOMETRIC_ONLY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val alias = store.unusedBiometricAlias
                BiometricKey.create(alias)
                val cipher = (BiometricKey.cipher(alias) as? BiometricKey.CipherResult.Ready)?.cipher
                if (cipher == null) {
                    BiometricKey.delete(alias)
                    showChooseMethod()
                    return
                }
                showPrompt(
                    authenticators = Authenticators.BIOMETRIC_STRONG,
                    crypto = BiometricPrompt.CryptoObject(cipher),
                    negativeButton = getString(android.R.string.cancel),
                    onNegative = {
                        BiometricKey.delete(alias)
                        showChooseMethod()
                    },
                    onSuccess = {
                        // Clears the PIN and the old key, and keeps the new one.
                        store.clearSecrets(keepBiometricAlias = alias)
                        store.biometricAlias = alias
                        onSetUp(LockMethod.BIOMETRIC_ONLY)
                    },
                    onCancel = {
                        BiometricKey.delete(alias)
                        showChooseMethod()
                    },
                    onRetry = {
                        BiometricKey.delete(alias)
                        showChooseMethod()
                    },
                )
            }
        }
    }

    private fun onSetUp(method: LockMethod) {
        manager.onSetUp(method)
        afterSuccess()
    }

    // ---- Reset ----

    private fun resetConfirm(fromBackup: Boolean) = AppLockScreen.ResetConfirm(
        fromBackup = fromBackup,
        wait = store.nextResetWait(),
        count = store.resetCount,
    )

    private fun onRequestReset() {
        if (manager.isPhoneLockSet()) {
            confirmPhoneLock(::requestReset, onCanceled = { screen = resetConfirm(fromBackup = false) })
        } else {
            requestReset()
        }
    }

    /**
     * Verifies the phone's own screen lock (pattern, PIN or password), like removing Android's
     * Private Space does, then runs [onConfirmed]. Right away on a phone without one.
     */
    private fun verifyPhoneLock(onConfirmed: () -> Unit) {
        if (!manager.isPhoneLockSet()) {
            onConfirmed()
            return
        }
        confirmPhoneLock(
            onConfirmed = {
                manager.onCodeVerified()
                onConfirmed()
            },
            onCanceled = { complete(false) },
        )
    }

    private fun requestReset() {
        store.requestReset()
        AppLockNotifications.showReset(this, store.resetRequestedWallTime)
        showResetPending()
    }

    private fun showResetPending() {
        screen = AppLockScreen.ResetPending(
            remaining = store.resetRemaining(),
            canUnlock = manager.isSetUp,
        )
    }

    /** Verifies the phone's own pattern, PIN or password. */
    private fun confirmPhoneLock(onConfirmed: () -> Unit, onCanceled: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            showPrompt(
                authenticators = Authenticators.DEVICE_CREDENTIAL,
                title = getString(R.string.app_lock_reset_verify_title),
                onSuccess = onConfirmed,
                onCancel = onCanceled,
                onRetry = onCanceled,
            )
        } else {
            @Suppress("DEPRECATION")
            val intent = getSystemService<KeyguardManager>()?.createConfirmDeviceCredentialIntent(
                getString(R.string.app_lock_reset_verify_title),
                null,
            )
            if (intent == null) {
                onConfirmed()
                return
            }
            onPhoneLockConfirmed = onConfirmed
            onPhoneLockCanceled = onCanceled
            confirmPhoneLock.launch(intent)
        }
    }

    // ---- System prompt ----

    @RequiresApi(Build.VERSION_CODES.R)
    private fun showPrompt(
        authenticators: Int,
        onSuccess: () -> Unit,
        crypto: BiometricPrompt.CryptoObject? = null,
        title: String = getString(R.string.app_lock_prompt_title),
        negativeButton: String? = null,
        onNegative: () -> Unit = {},
        onCancel: () -> Unit = { complete(false) },
        onRetry: () -> Unit = ::startUnlock,
    ) {
        screen = AppLockScreen.None
        val builder = BiometricPrompt.Builder(this)
            .setTitle(title)
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(false)
        if (negativeButton != null) {
            builder.setNegativeButton(negativeButton, mainExecutor) { _, _ -> onNegative() }
        }
        val prompt = builder.build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                promptSignal = null
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                promptSignal = null
                when (errorCode) {
                    BiometricPrompt.BIOMETRIC_ERROR_NEGATIVE_BUTTON -> Unit
                    BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
                    BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
                    -> onCancel()
                    else -> screen = AppLockScreen.Failed(
                        message = errString.toString(),
                        canForget = manager.method == LockMethod.BIOMETRIC_ONLY && manager.isSetUp,
                        retry = onRetry,
                    )
                }
            }
        }
        val signal = CancellationSignal()
        promptSignal = signal
        if (crypto != null) {
            prompt.authenticate(crypto, signal, mainExecutor, callback)
        } else {
            prompt.authenticate(signal, mainExecutor, callback)
        }
    }

    // ---- Finishing ----

    private fun complete(success: Boolean) {
        if (finished) return
        finished = true
        promptSignal?.cancel()
        finish()
        overrideActivityTransition()
        manager.finishRequest(success)
    }

    @Suppress("DEPRECATION")
    private fun overrideActivityTransition() {
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !finished) {
            finished = true
            promptSignal?.cancel()
            manager.finishRequest(false)
        }
    }

    private val actions = object : AppLockActions {
        override fun onPinEntered(purpose: PinPurpose, pin: String) = this@AppLockActivity.onPinEntered(purpose, pin)
        override fun onPinLockoutOver(purpose: PinPurpose) = showPin(purpose)
        override fun onForgot() {
            screen = resetConfirm(fromBackup = false)
        }
        override fun onMethodChosen(method: LockMethod) = this@AppLockActivity.onMethodChosen(method)
        override fun onRequestReset() = this@AppLockActivity.onRequestReset()
        override fun onUnlockNormally() = startUnlock()
        override fun onSetUpNow() = showChooseMethod()
        override fun onRefreshResetPending() = showResetPending()
        override fun onResetChoice(wasMe: Boolean) {
            store.resolveResetChoice(wasMe)
            complete(true)
        }
        override fun onCancel() = complete(false)
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_METHOD = "method"

        /** Asks for the unlock, see [AppLockManager.requestUnlock]. */
        const val MODE_UNLOCK = 0

        /** Asks even while unlocked, see [AppLockManager.requestVerify]. */
        const val MODE_VERIFY = 1

        /** Sets up the unlock or changes how it works, asking for the code first when needed. */
        const val MODE_SETUP = 2

        /** Asks for the code before removing protection, see [AppLockManager.requestWeaken]. */
        const val MODE_WEAKEN = 3

        /** [method] skips choosing one when setting up, e.g. for "Change PIN". */
        fun createIntent(context: Context, mode: Int, method: LockMethod? = null): Intent =
            Intent(context, AppLockActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_METHOD, method?.id)
                .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
}
