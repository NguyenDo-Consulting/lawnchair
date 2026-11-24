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

package app.lawnchair.ui.preferences.components.search

import android.Manifest
import android.app.WallpaperManager
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.qsb.DockSearchBarPosition
import app.lawnchair.ui.preferences.components.wallpaperDrawable
import app.lawnchair.ui.theme.preferenceGroupColor
import app.lawnchair.util.FileAccessManager
import app.lawnchair.util.FileAccessState
import app.lawnchair.util.openAppPermissionSettings
import com.android.launcher3.R
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * NDShades: The home screen wallpaper behind [content], showing the part that's behind the dock
 * search bar on the home screen, for the dock search bar preview. Reading the wallpaper needs photo and video access, which Lawnchair
 * only asks for outside Play builds; this asks for it in every build, with a "Show wallpaper"
 * button under the preview. Without it, the preview keeps the plain card behind it.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WallpaperBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val fileAccessManager = remember { FileAccessManager.getInstance(context) }
    val access by fileAccessManager.wallpaperAccessState.collectAsStateWithLifecycle()
    val hasPermission = access == FileAccessState.Full
    // Android doesn't share a live wallpaper's picture, only its app's sample thumbnail, so a live
    // wallpaper keeps the plain card
    val wallpaperManager = remember { WallpaperManager.getInstance(context) }
    val isLiveWallpaper = wallpaperManager.wallpaperInfo != null
    val wallpaper = if (isLiveWallpaper) null else wallpaperDrawable(hasPermission)

    val permissions = rememberMultiplePermissionsState(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        },
    ) { fileAccessManager.refresh() }

    Column {
        Box(
            modifier = modifier
                .clip(MaterialTheme.shapes.large)
                .background(preferenceGroupColor()),
            contentAlignment = Alignment.Center,
        ) {
            val barPosition = DockSearchBarPosition.current
            if (wallpaper != null && barPosition != null) {
                Canvas(Modifier.matchParentSize()) { drawWallpaperBehindBar(wallpaper, barPosition) }
            } else if (wallpaper != null) {
                // The home screen hasn't laid out the bar since ND Shades started: the dock's spot
                Image(
                    painter = rememberDrawablePainter(wallpaper),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.BottomCenter,
                )
            }
            content()
        }

        // Access doesn't help with a live wallpaper, so the button is only for still ones
        if (!isLiveWallpaper && wallpaper == null && !hasPermission) {
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        // Once denied for good, Android no longer asks, so open the app's permissions
                        if (permissions.shouldShowRationale) {
                            context.openAppPermissionSettings()
                        } else {
                            permissions.launchMultiplePermissionRequest()
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(imageVector = Icons.Rounded.Wallpaper, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.show_wallpaper))
                }
            }
        }
    }

    // Picks up access granted on the app's permission page
    LifecycleResumeEffect(Unit) {
        fileAccessManager.refresh()
        onPauseOrDispose { }
    }
}

/**
 * NDShades: Draws the wallpaper as the home screen shows it, then keeps the strip of the screen
 * around the search bar: the screen's full width shrunk to this card's width, centered on the bar.
 * Android scales a still wallpaper to cover the screen and centers it; the side-to-side shift
 * between home screen pages is left out.
 */
private fun DrawScope.drawWallpaperBehindBar(wallpaper: Drawable, bar: DockSearchBarPosition.Position) {
    val imageWidth = wallpaper.intrinsicWidth.toFloat()
    val imageHeight = wallpaper.intrinsicHeight.toFloat()
    if (imageWidth <= 0f || imageHeight <= 0f) return

    // Screen pixels to card pixels, and the top of the screen strip this card shows
    val scale = size.width / bar.screenWidth
    val stripTop = bar.centerY - size.height / scale / 2f

    // The wallpaper on the screen
    val coverScale = max(bar.screenWidth / imageWidth, bar.screenHeight / imageHeight)
    val left = (bar.screenWidth - imageWidth * coverScale) / 2f
    val top = (bar.screenHeight - imageHeight * coverScale) / 2f

    drawIntoCanvas { canvas ->
        wallpaper.setBounds(
            (left * scale).roundToInt(),
            ((top - stripTop) * scale).roundToInt(),
            ((left + imageWidth * coverScale) * scale).roundToInt(),
            ((top + imageHeight * coverScale - stripTop) * scale).roundToInt(),
        )
        wallpaper.draw(canvas.nativeCanvas)
    }
}
