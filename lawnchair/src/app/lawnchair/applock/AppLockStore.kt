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
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min

/**
 * The unlock's secrets and the reset wait, in their own file.
 *
 * Neither ND Shades backups (they copy named files only) nor Android's backup (its scheme
 * includes named files only) pick this file up, so a restore never brings back or replaces the
 * unlock. Clearing the app's storage erases it along with everything else.
 */
internal class AppLockStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val resolver = context.applicationContext.contentResolver

    // ---- Clock ----

    /**
     * Milliseconds on a clock that only moves forward while the phone is on. Changing the phone's
     * date and time doesn't move it, so a wait can't be skipped that way. Time while the phone is
     * off isn't counted, along with the time since the last call before a shutdown that
     * [checkpoint] didn't catch, which only ever makes a wait longer.
     */
    @Synchronized
    fun now(): Long {
        val elapsed = SystemClock.elapsedRealtime()
        val boot = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1)
        val total = prefs.getLong(KEY_CLOCK_TOTAL, 0)
        val lastElapsed = prefs.getLong(KEY_CLOCK_ELAPSED, 0)
        val lastBoot = prefs.getInt(KEY_CLOCK_BOOT, -1)
        val sameBoot = boot == lastBoot && elapsed >= lastElapsed
        val newTotal = total + if (sameBoot) elapsed - lastElapsed else elapsed
        prefs.edit {
            putLong(KEY_CLOCK_TOTAL, newTotal)
            putLong(KEY_CLOCK_ELAPSED, elapsed)
            putInt(KEY_CLOCK_BOOT, boot)
        }
        return newTotal
    }

    /** Saves the clock, e.g. before a shutdown, so the time so far keeps counting. */
    fun checkpoint() {
        now()
    }

    // ---- Method and secrets ----

    var method: LockMethod?
        get() = LockMethod.fromId(prefs.getString(KEY_METHOD, null))
        set(value) = prefs.edit { putString(KEY_METHOD, value?.id) }

    val hasPin get() = prefs.contains(KEY_PIN_HASH)

    /** The alias of the current [BiometricKey]. */
    var biometricAlias: String
        get() = prefs.getString(KEY_BIOMETRIC_ALIAS, null) ?: BiometricKey.ALIASES[0]
        set(value) = prefs.edit { putString(KEY_BIOMETRIC_ALIAS, value) }

    /** The alias a new [BiometricKey] goes under, so the current one stays until it works. */
    val unusedBiometricAlias get() = BiometricKey.ALIASES.first { it != biometricAlias }

    fun setPin(pin: String) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit {
            putString(KEY_PIN_SALT, salt.encode())
            putString(KEY_PIN_HASH, hash(pin, salt).encode())
            remove(KEY_PIN_FAILURES)
            remove(KEY_PIN_LOCKOUT_UNTIL)
        }
    }

    /** Whether [pin] is the PIN. Slow on purpose: call it off the main thread. */
    fun checkPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_PIN_SALT, null)?.decode() ?: return false
        val expected = prefs.getString(KEY_PIN_HASH, null)?.decode() ?: return false
        val matches = MessageDigest.isEqual(hash(pin, salt), expected)
        prefs.edit {
            if (matches) {
                remove(KEY_PIN_FAILURES)
                remove(KEY_PIN_LOCKOUT_UNTIL)
            } else {
                val failures = prefs.getInt(KEY_PIN_FAILURES, 0) + 1
                putInt(KEY_PIN_FAILURES, failures)
                if (failures >= FREE_PIN_TRIES) {
                    // 30 s after the 5th wrong PIN, doubling after each one after it, up to an hour.
                    val lockout = min(PIN_LOCKOUT_MS shl min(failures - FREE_PIN_TRIES, 20), MAX_PIN_LOCKOUT_MS)
                    putLong(KEY_PIN_LOCKOUT_UNTIL, now() + lockout)
                }
            }
        }
        return matches
    }

    /** How long until the PIN can be tried again after too many wrong ones, or 0. */
    fun pinLockoutRemaining(): Long {
        val until = prefs.getLong(KEY_PIN_LOCKOUT_UNTIL, 0)
        return (until - now()).coerceAtLeast(0)
    }

    /**
     * Forgets the unlock, keeping the reset wait and its count, and the [BiometricKey] under
     * [keepBiometricAlias] if given.
     */
    fun clearSecrets(keepBiometricAlias: String? = null) {
        prefs.edit {
            remove(KEY_METHOD)
            remove(KEY_PIN_HASH)
            remove(KEY_PIN_SALT)
            remove(KEY_PIN_FAILURES)
            remove(KEY_PIN_LOCKOUT_UNTIL)
        }
        BiometricKey.ALIASES.filter { it != keepBiometricAlias }.forEach(BiometricKey::delete)
    }

    // ---- Reset wait ----

    /** The number of reset requests in a row. */
    val resetCount get() = prefs.getInt(KEY_RESET_COUNT, 0)

    val isResetPending get() = prefs.getLong(KEY_RESET_READY_AT, -1) >= 0

    /** How long until a pending reset can be completed, or 0 once it can. */
    fun resetRemaining(): Long {
        val readyAt = prefs.getLong(KEY_RESET_READY_AT, -1)
        if (readyAt < 0) return 0
        return (readyAt - now()).coerceAtLeast(0)
    }

    /** When the pending or last reset was requested, on the wall clock, for showing only. */
    val resetRequestedWallTime get() = prefs.getLong(KEY_RESET_REQUESTED_WALL, 0)

    /**
     * Starts a reset wait and returns how long it is: 10 minutes, doubling with each request in a
     * row up to 24 hours. The count starts over after 30 days without a request.
     */
    fun requestReset(): Long {
        val now = now()
        val lastRequest = prefs.getLong(KEY_RESET_LAST_REQUEST_AT, -1)
        val previousCount = if (lastRequest >= 0 && now - lastRequest >= COUNT_EXPIRY_MS) 0 else resetCount
        val count = previousCount + 1
        val wait = resetWaitFor(count)
        prefs.edit {
            putInt(KEY_RESET_COUNT, count)
            putLong(KEY_RESET_LAST_REQUEST_AT, now)
            putLong(KEY_RESET_READY_AT, now + wait)
            putLong(KEY_RESET_REQUESTED_WALL, System.currentTimeMillis())
            remove(KEY_RESET_CHOICE_PENDING)
        }
        return wait
    }

    /** How long the next reset request would wait. */
    fun nextResetWait(): Long {
        val lastRequest = prefs.getLong(KEY_RESET_LAST_REQUEST_AT, -1)
        val expired = lastRequest >= 0 && now() - lastRequest >= COUNT_EXPIRY_MS
        return resetWaitFor((if (expired) 0 else resetCount) + 1)
    }

    /**
     * Ends the pending reset, canceled by a normal unlock or completed by setting up the unlock
     * again. Either way, the user is asked whether it was them, see [resolveResetChoice].
     */
    fun endReset() {
        prefs.edit {
            remove(KEY_RESET_READY_AT)
            putBoolean(KEY_RESET_CHOICE_PENDING, true)
        }
    }

    /** Whether "That was me / That wasn't me" is still to be asked. */
    val isResetChoicePending get() = prefs.getBoolean(KEY_RESET_CHOICE_PENDING, false)

    /** "That was me" starts the count over. "That wasn't me" keeps counting. */
    fun resolveResetChoice(wasMe: Boolean) {
        prefs.edit {
            if (wasMe) {
                remove(KEY_RESET_COUNT)
                remove(KEY_RESET_LAST_REQUEST_AT)
            }
            remove(KEY_RESET_CHOICE_PENDING)
        }
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.encode() = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decode() = Base64.decode(this, Base64.NO_WRAP)

    companion object {
        private const val FILE_NAME = "ndshades_app_lock"

        private const val KEY_CLOCK_TOTAL = "clock_total"
        private const val KEY_CLOCK_ELAPSED = "clock_elapsed"
        private const val KEY_CLOCK_BOOT = "clock_boot"
        private const val KEY_METHOD = "method"
        private const val KEY_BIOMETRIC_ALIAS = "biometric_alias"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_FAILURES = "pin_failures"
        private const val KEY_PIN_LOCKOUT_UNTIL = "pin_lockout_until"
        private const val KEY_RESET_COUNT = "reset_count"
        private const val KEY_RESET_LAST_REQUEST_AT = "reset_last_request_at"
        private const val KEY_RESET_READY_AT = "reset_ready_at"
        private const val KEY_RESET_REQUESTED_WALL = "reset_requested_wall"
        private const val KEY_RESET_CHOICE_PENDING = "reset_choice_pending"

        private const val SALT_BYTES = 16
        private const val HASH_BITS = 256
        private const val PBKDF2_ITERATIONS = 120_000
        private const val FREE_PIN_TRIES = 5
        private const val PIN_LOCKOUT_MS = 30_000L
        private const val MAX_PIN_LOCKOUT_MS = 60 * 60_000L

        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 16

        private const val FIRST_RESET_WAIT_MS = 10 * 60_000L
        private const val MAX_RESET_WAIT_MS = 24 * 60 * 60_000L
        private const val COUNT_EXPIRY_MS = 30 * 24 * 60 * 60_000L

        /** 10 minutes for the first request in a row, doubling with each one, up to 24 hours. */
        fun resetWaitFor(count: Int): Long {
            val doublings = (count - 1).coerceIn(0, 20)
            return min(FIRST_RESET_WAIT_MS shl doublings, MAX_RESET_WAIT_MS)
        }
    }
}
