/*
 * Modifications copyright 2026, Nguyen-Do Consulting
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

package app.lawnchair.qsb.providers

import app.lawnchair.qsb.ThemingMethod
import com.android.launcher3.R

data object Startpage : QsbSearchProvider(
    id = "startpage",
    name = R.string.search_provider_startpage,
    icon = R.drawable.ic_startpage,
    themingMethod = ThemingMethod.TINT,
    packageName = "com.startpage.app",
    className = "org.chromium.chrome.browser.searchwidget.SearchActivity",
    // NDShades: Lawnchair's partner segment is left out.
    // TODO: get ND Shades' own partner segment from Startpage and add it here.
    website = "https://startpage.com/",
    sponsored = false,
)
