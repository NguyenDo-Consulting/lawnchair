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

package app.lawnchair.folder

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.util.FloatProperty
import android.view.View
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.patrykmichalik.opto.core.firstBlocking

/**
 * Optional blur behind an open folder. On the home screen, the depth controller's
 * `folderBlur` blurs the wallpaper, workspace and dock. In the app drawer, whose background is
 * already blurred, [VIEW_BLUR_FRACTION] blurs the drawer itself.
 */
object FolderBlur {

    /** Blurs a view by a fraction of the max depth blur radius. */
    @JvmField
    val VIEW_BLUR_FRACTION =
        object : FloatProperty<View>("blurFraction") {
            override fun setValue(view: View, value: Float) {
                view.setTag(R.id.folder_blur_fraction, value)
                if (!Utilities.ATLEAST_S) return
                if (value <= 0f) {
                    view.setRenderEffect(null)
                    return
                }
                val radius = value *
                    view.resources.getDimensionPixelSize(R.dimen.max_depth_blur_radius_enhanced)
                view.setRenderEffect(
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
                )
            }

            override fun get(view: View): Float =
                view.getTag(R.id.folder_blur_fraction) as? Float ?: 0f
        }

    /** Whether the user turned on the blur behind open folders. */
    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        PreferenceManager2.getInstance(context).folderBackgroundBlur.firstBlocking()

    /** Removes the blur, when the folder animation resets the launcher. */
    @JvmStatic
    fun reset(launcher: Launcher) {
        launcher.depthController?.folderBlur?.updateValue(0f)
        VIEW_BLUR_FRACTION.set(launcher.appsView, 0f)
    }
}
