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

package app.lawnchair.gestures

/**
 * A home screen or dock icon that can have swipe gestures: an app or shortcut
 * ([com.android.launcher3.BubbleTextView]) or a folder ([com.android.launcher3.folder.FolderIcon]).
 * The pagers and the swipe-up controllers ask it whether a touch on it belongs to the icon.
 */
interface IconSwipeTarget {
    /** Whether a swipe left or right on this icon does something right now. */
    fun hasConfiguredHorizontalIconSwipeGesture(): Boolean

    /** Whether a swipe up or down on this icon does something right now. */
    fun hasConfiguredVerticalIconSwipeGesture(): Boolean
}
