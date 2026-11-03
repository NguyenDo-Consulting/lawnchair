package app.lawnchair.gestures.handlers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.util.Log
import android.widget.Toast
import androidx.core.content.getSystemService
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.R
import app.lawnchair.util.ComponentKeySerializer
import app.lawnchair.util.IntentSerializer
import app.lawnchair.util.UserHandlerSerializer
import com.android.launcher3.util.ComponentKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class OpenAppGestureHandler(
    context: Context,
    private val target: OpenAppTarget,
) : GestureHandler(context) {

    override suspend fun onTrigger(launcher: LawnchairLauncher) {
        when (target) {
            is OpenAppTarget.App -> {
                val key = target.key
                launcher.getSystemService<LauncherApps>()?.startMainActivity(
                    key.componentName,
                    key.user,
                    null,
                    null,
                )
            }

            is OpenAppTarget.Shortcut -> {
                try {
                    launcher.getSystemService<LauncherApps>()?.startShortcut(
                        target.packageName,
                        target.id,
                        null,
                        null,
                        target.user,
                    )
                } catch (e: ActivityNotFoundException) {
                    onShortcutUnavailable(launcher, e)
                } catch (e: IllegalStateException) {
                    onShortcutUnavailable(launcher, e)
                } catch (e: SecurityException) {
                    onShortcutUnavailable(launcher, e)
                }
            }
        }
    }

    // The shortcut was removed by its app, or its app was uninstalled.
    private fun onShortcutUnavailable(launcher: LawnchairLauncher, e: Exception) {
        Log.w(TAG, "Can't start shortcut", e)
        Toast.makeText(launcher, R.string.shortcut_not_available, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "OpenAppGestureHandler"
    }
}

@Serializable
sealed class OpenAppTarget {
    @Serializable
    @SerialName("app")
    data class App(
        @Serializable(ComponentKeySerializer::class) val key: ComponentKey,
    ) : OpenAppTarget()

    @Serializable
    @SerialName("shortcut")
    data class Shortcut(
        @Serializable(IntentSerializer::class) val intent: Intent,
        @Serializable(UserHandlerSerializer::class) val user: UserHandle,
        val packageName: String,
        val id: String,
    ) : OpenAppTarget()
}
