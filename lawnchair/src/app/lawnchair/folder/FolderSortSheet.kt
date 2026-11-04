package app.lawnchair.folder

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.lawnchair.LawnchairLauncher
import app.lawnchair.ui.ModalBottomSheetContent
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.views.ComposeBottomSheet
import com.android.launcher3.R
import com.android.launcher3.folder.Folder

object FolderSortSheet {

    /** Whether the open folder's options button can be shown in this context. */
    @JvmStatic
    fun isSupported(context: Context): Boolean = findLauncher(context) != null

    /**
     * Opened from the ⋮ button in an open home screen or dock folder.
     */
    @JvmStatic
    fun show(folder: Folder) {
        val launcher = findLauncher(folder.context) ?: return
        val info = folder.info
        ComposeBottomSheet.show(launcher) {
            var mode by remember { mutableStateOf(FolderSortMode.of(info)) }
            ModalBottomSheetContent(
                title = { Text(stringResource(R.string.folder_sort_label)) },
                buttons = {
                    OutlinedButton(
                        onClick = { close(true) },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            FolderSortMode.set(info, mode, launcher.modelWriter)
                            folder.applySortMode()
                            close(true)
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            ) {
                FolderSortOptions(
                    selected = mode,
                    onSelect = { mode = it },
                )
            }
        }
    }

    /** Folders also open in the taskbar, which has no launcher model to save the sort mode to. */
    private fun findLauncher(context: Context): LawnchairLauncher? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is LawnchairLauncher) return current
            current = current.baseContext
        }
        return null
    }
}

/**
 * Radio list of [FolderSortMode]s. "Most used" needs usage access, so without it this explains
 * the alphabetical fallback and offers a shortcut to grant it.
 */
@Composable
fun FolderSortOptions(
    selected: FolderSortMode,
    onSelect: (FolderSortMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasUsageAccess by remember { mutableStateOf(FolderUsageStats.hasUsageAccess(context)) }
    LifecycleResumeEffect(context) {
        hasUsageAccess = FolderUsageStats.hasUsageAccess(context)
        onPauseOrDispose {}
    }
    val colors = ListItemDefaults.colors(containerColor = Color.Transparent)

    Column(modifier) {
        FolderSortMode.entries.forEach { mode ->
            val needsAccess = mode == FolderSortMode.MOST_USED && !hasUsageAccess
            PreferenceTemplate(
                title = { Text(stringResource(mode.labelRes)) },
                description = if (needsAccess) {
                    { Text(stringResource(R.string.folder_sort_most_used_needs_access)) }
                } else {
                    null
                },
                startWidget = {
                    RadioButton(selected = mode == selected, onClick = null)
                },
                onClick = { onSelect(mode) },
                colors = colors,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (selected == FolderSortMode.MOST_USED && !hasUsageAccess) {
            ClickablePreference(
                label = stringResource(R.string.folder_sort_grant_usage_access),
                modifier = Modifier.padding(horizontal = 8.dp),
                colors = colors,
            ) {
                openUsageAccessSettings(context)
            }
        }
    }
}

private fun openUsageAccessSettings(context: Context) {
    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}
