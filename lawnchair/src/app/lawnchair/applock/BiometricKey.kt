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

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The key behind [LockMethod.BIOMETRIC_ONLY]. It can only be used right after a fingerprint or
 * face that Android rates as secure, and Android destroys it when a new fingerprint or face is
 * added, so someone who knows the phone's PIN can't add theirs and get in.
 */
internal object BiometricKey {
    private const val TAG = "BiometricKey"
    private const val KEYSTORE = "AndroidKeyStore"

    /**
     * Two aliases, used in turn: a new key is made under the unused one, and the old key is only
     * deleted once the new one has worked, so canceling a setup keeps the current unlock.
     */
    val ALIASES = listOf("ndshades_app_lock_0", "ndshades_app_lock_1")
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    sealed interface CipherResult {
        data class Ready(val cipher: Cipher) : CipherResult

        /** A fingerprint or face was added, or all were removed, since the key was made. */
        data object Invalidated : CipherResult
        data object Missing : CipherResult
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun create(alias: String) {
        delete(alias)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(spec)
            generateKey()
        }
    }

    /** A cipher to pass to the fingerprint prompt, which only unlocks it for a secure biometric. */
    fun cipher(alias: String): CipherResult {
        val key = try {
            keyStore().getKey(alias, null) as? SecretKey
        } catch (e: Exception) {
            Log.w(TAG, "Can't load the key", e)
            null
        } ?: return CipherResult.Missing
        return try {
            CipherResult.Ready(Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) })
        } catch (e: KeyPermanentlyInvalidatedException) {
            CipherResult.Invalidated
        } catch (e: Exception) {
            Log.w(TAG, "Can't use the key", e)
            CipherResult.Invalidated
        }
    }

    fun delete(alias: String) {
        try {
            keyStore().deleteEntry(alias)
        } catch (e: Exception) {
            Log.w(TAG, "Can't delete the key", e)
        }
    }

    fun deleteAll() {
        ALIASES.forEach(::delete)
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }
}
