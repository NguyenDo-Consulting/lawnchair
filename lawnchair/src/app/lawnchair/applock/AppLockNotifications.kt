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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.text.format.DateFormat
import androidx.core.content.getSystemService
import com.android.launcher3.R
import java.util.Date

/** The notification that stays up while a reset of the locked apps' unlock is waiting. */
internal object AppLockNotifications {
    private const val CHANNEL_ID = "app_lock"
    private const val RESET_ID = 0x4c4f434b // "LOCK"

    fun showReset(context: Context, requestedAt: Long) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.app_lock_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val time = DateFormat.getTimeFormat(context).format(Date(requestedAt))
        // Opening it asks for the normal unlock, which cancels the reset.
        val intent = PendingIntent.getActivity(
            context,
            0,
            AppLockActivity.createIntent(context, AppLockActivity.MODE_UNLOCK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle(context.getString(R.string.app_lock_reset_notification_title))
            .setContentText(context.getString(R.string.app_lock_reset_notification_text, time))
            .setStyle(Notification.BigTextStyle().bigText(context.getString(R.string.app_lock_reset_notification_text, time)))
            .setContentIntent(intent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        try {
            manager.notify(RESET_ID, notification)
        } catch (e: SecurityException) {
            // Notifications are off. The reset still waits.
        }
    }

    fun cancelReset(context: Context) {
        context.getSystemService<NotificationManager>()?.cancel(RESET_ID)
    }
}
