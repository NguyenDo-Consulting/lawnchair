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

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.util.ComponentKey
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Locked apps: which apps are locked, the unlock that opens them, and when it expires. Lives in
 * memory only, so the next launch after Android closes the launcher asks again.
 *
 * Everything that opens an app from the launcher asks [needsUnlock] first, and if it does,
 * [requestUnlock] shows [AppLockActivity] and opens the app after. See docs/ndc/locked-apps.md.
 */
class AppLockManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    internal val store = AppLockStore(appContext)
    private val prefs2 = PreferenceManager2.getInstance(appContext)

    private var unlocked = false

    /** When the screen went off, on [AppLockStore.now], or -1 while it's on. */
    private var screenOffAt = -1L

    /** The one launch let through right after an unlock with [RelockMode.IMMEDIATELY]. */
    private var pass: Pair<String, UserHandle>? = null
    private var passUntil = 0L

    /** Whether the code (separate PIN, or the phone's pattern/PIN) was entered, see [wasJustCoded]. */
    private var codeVerified = false

    /** Whether the Locked apps screen is open and was unlocked, see [beginVisit]. */
    private var visitActive = false

    private var parsedLockedApps: Pair<Set<String>, Set<ComponentKey>> = emptySet<String>() to emptySet()
    private val listeners = CopyOnWriteArraySet<Runnable>()

    internal var pendingRequest: Request? = null
        private set

    internal class Request(val onSuccess: () -> Unit, val onCancel: () -> Unit)

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_SCREEN_ON -> applyRelock()
                    Intent.ACTION_SHUTDOWN -> store.checkpoint()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // ---- What's locked ----

    val lockedApps: Set<String> get() = prefs2.lockedApps.firstCached(prefs2)

    val hasLockedApps get() = lockedApps.isNotEmpty()

    val relockMode get() = RelockMode.fromId(prefs2.appLockRelock.firstCached(prefs2))

    fun isLocked(key: ComponentKey) = key.toString() in lockedApps

    /** Whether any locked app is in [packageName], so its shortcuts and other activities lock too. */
    fun isPackageLocked(packageName: String, user: UserHandle): Boolean =
        lockedKeys().any { it.componentName.packageName == packageName && it.user == user }

    private fun lockedKeys(): Set<ComponentKey> {
        val current = lockedApps
        val (source, keys) = parsedLockedApps
        if (source === current) return keys
        val parsed = current.mapNotNullTo(mutableSetOf()) { ComponentKey.fromString(it) }
        parsedLockedApps = current to parsed
        return parsed
    }

    // ---- Method ----

    /** Whether the unlock has been set up. Locked apps without it came from a backup. */
    val isSetUp: Boolean
        get() = when (store.method) {
            null -> false
            LockMethod.PIN -> store.hasPin
            else -> true
        }

    val method get() = store.method

    /** Whether [method] can be used on this phone. */
    fun isAvailable(method: LockMethod): Boolean = when (method) {
        LockMethod.PIN -> true
        LockMethod.BIOMETRIC_ONLY -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            isPhoneLockSet() &&
            appContext.getSystemService<BiometricManager>()
                ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun isPhoneLockSet() = appContext.getSystemService<KeyguardManager>()?.isDeviceSecure == true

    // ---- The unlock ----

    val isUnlocked: Boolean
        get() {
            applyRelock()
            return unlocked
        }

    /** Whether opening something of [packageName] has to ask for the unlock first. */
    fun needsUnlock(packageName: String?, user: UserHandle?): Boolean {
        if (packageName == null) return false
        val owner = user ?: Process.myUserHandle()
        if (!isPackageLocked(packageName, owner)) return false
        if (isUnlocked) return false
        val passed = pass == (packageName to owner) && SystemClock.elapsedRealtime() < passUntil
        pass = null
        return !passed
    }

    /**
     * Asks for the unlock if opening [packageName] needs it, and opens it with [launch] after.
     * Returns whether it asked, in which case the caller doesn't open it.
     */
    @MainThread
    fun interceptLaunch(context: Context, packageName: String?, user: UserHandle?, launch: () -> Unit): Boolean {
        if (!needsUnlock(packageName, user)) return false
        val owner = user ?: Process.myUserHandle()
        requestUnlock(context, onSuccess = {
            pass = packageName!! to owner
            passUntil = SystemClock.elapsedRealtime() + PASS_MS
            launch()
        })
        return true
    }

    /** Shows the unlock unless an unlock is still active, then runs [onSuccess]. */
    @MainThread
    fun requestUnlock(context: Context, onSuccess: () -> Unit, onCancel: () -> Unit = {}) {
        if (isSetUp && isUnlocked) {
            onSuccess()
            return
        }
        start(context, AppLockActivity.MODE_UNLOCK, onSuccess, onCancel)
    }

    /**
     * Always asks, even while an unlock is active: for entering the Locked apps screen, removing a
     * lock, and backups. With no apps locked, it asks for the phone's own screen lock instead, and
     * runs [onSuccess] right away on a phone without one.
     */
    @MainThread
    fun requestVerify(context: Context, onSuccess: () -> Unit, onCancel: () -> Unit = {}) {
        if (!hasLockedApps && !isPhoneLockSet() && !store.isResetPending) {
            onSuccess()
            return
        }
        start(context, AppLockActivity.MODE_VERIFY, onSuccess, onCancel)
    }

    /**
     * Asks for the code before something that removes protection: removing a lock, a less frequent
     * relock, restoring a backup. The code is the separate PIN with [LockMethod.PIN], otherwise the
     * phone's own pattern, PIN or password, never just a fingerprint or face. On the Locked apps
     * screen, the code lasts until the screen is left, so removing several locks asks once per visit.
     * Anywhere else, it asks every time.
     */
    @MainThread
    fun requestWeaken(context: Context, onSuccess: () -> Unit, onCancel: () -> Unit = {}) {
        if (wasJustCoded || (!hasLockedApps && !isPhoneLockSet() && !store.isResetPending)) {
            onSuccess()
            return
        }
        start(context, AppLockActivity.MODE_WEAKEN, onSuccess, onCancel)
    }

    /**
     * Sets up the unlock, or changes how it works. Asks for the code first unless it was just
     * entered: the phone's own screen lock while no apps are locked, as for the first setup. Once a
     * reset's wait is over, it sets up without asking.
     */
    @MainThread
    fun requestSetup(context: Context, method: LockMethod? = null, onSuccess: () -> Unit, onCancel: () -> Unit = {}) {
        start(context, AppLockActivity.MODE_SETUP, onSuccess, onCancel, method)
    }

    private fun start(
        context: Context,
        mode: Int,
        onSuccess: () -> Unit,
        onCancel: () -> Unit,
        method: LockMethod? = null,
    ) {
        pendingRequest?.onCancel?.invoke()
        pendingRequest = Request(onSuccess, onCancel)
        // Outside a visit to the Locked apps screen, a code entered earlier never counts. One
        // entered while getting into the screen (with no apps locked) carries into that visit.
        if (!visitActive) codeVerified = false
        context.startActivity(AppLockActivity.createIntent(context, mode, method))
    }

    internal fun finishRequest(success: Boolean) {
        val request = pendingRequest ?: return
        pendingRequest = null
        if (success) request.onSuccess() else request.onCancel()
    }

    /** Called after any successful normal unlock: starts the unlock and cancels a pending reset. */
    internal fun onUnlocked() {
        if (store.isResetPending) {
            store.endReset()
            AppLockNotifications.cancelReset(appContext)
        }
        screenOffAt = -1
        if (relockMode != RelockMode.IMMEDIATELY) setUnlocked(true)
    }

    /** Called after the code was entered. Doesn't unlock anything by itself. */
    internal fun onCodeVerified() {
        codeVerified = true
    }

    /** Whether the code was entered during this visit to the Locked apps screen. */
    internal val wasJustCoded get() = visitActive && codeVerified

    /** The Locked apps screen was entered and unlocked. A code entered from here on lasts until [endVisit]. */
    fun beginVisit() {
        visitActive = true
    }

    /** The Locked apps screen was left, or the app or screen went off: the code is asked again. */
    fun endVisit() {
        visitActive = false
        codeVerified = false
    }

    fun lockNow() {
        setUnlocked(false)
    }

    private fun onScreenOff() {
        store.checkpoint()
        when (relockMode) {
            RelockMode.IMMEDIATELY, RelockMode.SCREEN_OFF -> setUnlocked(false)
            RelockMode.SCREEN_OFF_5_MIN -> if (unlocked) screenOffAt = store.now()
            RelockMode.RESTART -> Unit
        }
    }

    private fun applyRelock() {
        when (relockMode) {
            RelockMode.IMMEDIATELY -> if (unlocked) setUnlocked(false)
            RelockMode.SCREEN_OFF_5_MIN -> {
                // Only while the screen is off, so a phone left on stays unlocked.
                if (screenOffAt >= 0 && store.now() - screenOffAt >= RELOCK_DELAY_MS) setUnlocked(false)
                if (!isScreenOff()) screenOffAt = -1
            }
            else -> Unit
        }
    }

    private fun isScreenOff() =
        appContext.getSystemService<android.os.PowerManager>()?.isInteractive == false

    private fun setUnlocked(value: Boolean) {
        if (unlocked == value) return
        unlocked = value
        if (!value) screenOffAt = -1
        listeners.forEach { it.run() }
    }

    /** [listener] runs when the locked apps lock or unlock, e.g. to cover or uncover widgets. */
    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    // ---- Changes from settings ----

    internal fun onSetUp(method: LockMethod) {
        store.method = method
        if (store.isResetPending) {
            // Setting up again after the wait completes the reset.
            store.endReset()
            AppLockNotifications.cancelReset(appContext)
        }
        // Setting up took the code (or a finished reset), so changing it again in the same visit
        // doesn't ask.
        codeVerified = true
        if (relockMode != RelockMode.IMMEDIATELY) setUnlocked(true)
    }

    /** Called after the relock timing or the locked apps change. */
    fun onSettingsChanged() {
        applyRelock()
        listeners.forEach { it.run() }
    }

    companion object {
        private const val PASS_MS = 10_000L
        private const val RELOCK_DELAY_MS = 5 * 60_000L

        @Volatile
        private var instance: AppLockManager? = null

        @JvmStatic
        fun getInstance(context: Context): AppLockManager = instance ?: synchronized(this) {
            instance ?: AppLockManager(context).also { instance = it }
        }
    }
}
