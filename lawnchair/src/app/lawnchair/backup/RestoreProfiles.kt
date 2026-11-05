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

package app.lawnchair.backup

import android.content.Context
import android.os.Process
import android.os.UserHandle
import com.android.launcher3.pm.UserCache
import com.android.launcher3.provider.RestoreDbTask

/**
 * Finds the profiles of a restored home screen when Android can't. The launcher maps a backed
 * up work profile to the current one through Android's backup service, which only knows that
 * mapping after Android restored a phone during setup. A Lawnchair backup restored from a file
 * has none, so its work apps (e.g. Teams in a dock folder) would be deleted.
 */
object RestoreProfiles {

    /**
     * Returns this phone's profile with the given serial number, for a restore from a backup
     * file, or null. The number is only kept for that kind of restore: after Android's own
     * restore the backed up numbers belong to the old phone.
     */
    @JvmStatic
    fun findProfileOnThisDevice(context: Context, profileSerial: Long): UserHandle? {
        if (RestoreDbTask.isPending(context)) return null
        val userCache = UserCache.INSTANCE.get(context)
        return userCache.userProfiles.firstOrNull { user ->
            user != Process.myUserHandle() &&
                userCache.getSerialNumberForUser(user) == profileSerial
        }
    }
}
