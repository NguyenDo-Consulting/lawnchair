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

package app.lawnchair

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import app.lawnchair.util.isDefaultLauncher
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherConstants.ActivityCodes.REQUEST_HOME_ROLE

/**
 * NDShades: Asks once, the first time ND Shades opens, to become the default home app. It never
 * asks again after that, whatever the answer; the tip card in Settings covers anyone who switches
 * away later.
 */
object DefaultHomePrompt {

    private const val TAG = "DefaultHomePrompt"

    // Device-only: this file is in neither ND Shades' backup nor Android's cloud backup, so a
    // restore onto a new phone still gets asked there.
    private const val PREFS_NAME = "ndshades_device"
    private const val KEY_ASKED = "default_home_asked"

    fun askOnce(launcher: Launcher) {
        val prefs = launcher.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return
        prefs.edit { putBoolean(KEY_ASKED, true) }

        if (launcher.isDefaultLauncher()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = launcher.getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (!roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                        // REQUEST_HOME_ROLE: Launcher shows a "set as default in Settings" toast
                        // when this is declined.
                        launcher.startActivityForResult(
                            roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                            REQUEST_HOME_ROLE,
                        )
                    }
                    return
                }
            }
            // Android 9 and older have no dialog; open the Default home app page instead.
            launcher.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't ask to become the default home app", e)
        }
    }
}
