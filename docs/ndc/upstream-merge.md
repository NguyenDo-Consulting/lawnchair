<!--
Copyright 2026, Nguyen-Do Consulting
Licensed under the Apache License, Version 2.0. See LICENSE.txt.
-->

# Merging a new upstream into ND Shades

How to bring a new Launcher3 (for example the Android 17 one) or a new Lawnchair release into
`ndc-main` without losing ND Shades features.

## How the ND Shades changes are laid out

ND Shades forked Lawnchair `16-dev` at commit `85493b9776`. Its changes sit in three places:

| Where | What | Merge risk |
| --- | --- | --- |
| New files: `lawnchair/src/app/lawnchair/**` (e.g. `folder/`, `allapps/filters/`, `gestures/Folder*`, `backup/ShortcutRecreator.kt`), `ndshades/`, `ndshades.gradle`, `NOTICE`, `docs/ndc/` | All feature logic, branding resources and build scripts | None. Upstream doesn't have these files. They break only through compile errors when an upstream API they call changes. |
| Lawnchair's own files in `lawnchair/` | Changes to Lawnchair code (backup, preferences, About, popups, …) | Conflicts only when Lawnchair changes the same lines. They don't change in a Launcher3 merge. |
| Upstream Launcher3 files: `src/`, `quickstep/`, `res/`, `build.gradle` | Small hooks that call into the code above | Conflicts on every Launcher3 merge. This guide is mostly about these. |

Every ND Shades change is marked so it can be told apart from upstream Lawnchair's:

- Comments start with `NDShades:` (`// NDShades:`, `/** NDShades: */`, `<!-- NDShades: -->`).
  Lawnchair's own changes use `Lawnchair:`, `LC:` or `LC-Note:`. Where ND Shades changed a line
  Lawnchair had already marked, both are kept, e.g. `// LC-Note: isQsbOnTop. NDShades: …`.
- Every file ND Shades changed has a `Modifications copyright 2026, Nguyen-Do Consulting` line in
  its header.

To list every ND Shades change in an upstream file:

```sh
git grep -n "NDShades:" -- src quickstep res build.gradle
```

## Merge steps

1. Branch off `ndc-main`, e.g. `git switch -c merge/a17 ndc-main`.
2. Merge the new upstream branch (`git merge upstream/<branch>`).
3. Resolve conflicts. In each conflicted file:
   - Take upstream's version of the surrounding code.
   - Put back every `NDShades:` line from our side. The hook table below says what each one does,
     so you can re-apply it to the new code even when upstream rewrote the method or converted the
     file to Kotlin.
   - Keep the `Modifications copyright 2026, Nguyen-Do Consulting` header line.
4. Run the hook check. It fails if a merge silently dropped a hook, even when the build passes:

   ```sh
   bash docs/ndc/check-upstream-hooks.sh
   ```

   - `MISSING`: put the hook back (see the table).
   - `MOVED`: upstream converted the file between Java and Kotlin. Check the hook came across.
   - `UNLISTED`: a file has ND Shades changes the script doesn't know about. Add its hooks to the
     script.
5. Build (`assembleLawnWithQuickstepPlayRelease`). Compile errors in `lawnchair/` mean an upstream
   API changed. See "APIs ND Shades relies on" below.
6. Check the database version (see "Database") before installing on a phone with ND Shades data.
7. Install on a phone and go through the test list at the end.

## Hooks in upstream files, by feature

The canary column is what `check-upstream-hooks.sh` looks for.

### Icon swipe gestures on the dock, folders and pinned shortcuts

Logic: `lawnchair/.../gestures/` (`IconSwipeTarget`, `IconSwipeHitTest`, `IconGestureKeys`,
`FolderIconSwipeGestures`, `FolderGestures`, `GestureShortcutPins`) and
`lawnchair/.../hotseat/HotseatPagedView.kt`.

| File | Canary | What the hook does |
| --- | --- | --- |
| `BubbleTextView` | `IconSwipeTarget`, `IconGestureKeys.forItem` | App and shortcut icons implement `IconSwipeTarget`. Gesture keys come from `IconGestureKeys.forItem` (upstream: the component key), so a pinned shortcut has its own gestures. |
| `folder/FolderIcon` | `FolderIconSwipeGestures` | Folder icons implement `IconSwipeTarget`. `onTouchEvent` hands swipes to `FolderIconSwipeGestures` first and cancels the click and long press when one fires. |
| `Workspace` | `static IconSwipeTarget findIconInCellLayout` | The hit test returns `IconSwipeTarget` (upstream `BubbleTextView`) and is `public static`, so the dock pages use it too. |
| `Hotseat` | `isTouchOnIconWithSwipeGesture` | A left/right swipe that starts on a dock icon with a gesture isn't handed to the workspace pager. |
| `folder/FolderPagedView` | `mTouchOnIconWithVerticalSwipe` | Inside an open folder, a mostly vertical swipe on an icon with an up/down gesture isn't taken for paging. `isTouchOnIconWithSwipeGesture` is public for `IconSwipeHitTest`. |
| `PortraitStatesTouchController`, `StatusBarTouchController` (quickstep) | `IconSwipeHitTest` | Swipe up to the drawer and swipe down for notifications leave touches on icons with gestures alone, on the workspace, dock and open folders. |
| `model/BgDataModel` | `GestureShortcutPins` | Shortcuts used only by swipe gestures count as in use, so the model doesn't unpin them. |
| `model/ModelWriter` | `FolderGestures.onItemsDeleted` | A deleted folder's gestures are removed. |
| `model/GridSizeMigrationDBController` | `FolderGestures.onFolderIdChanged` | A grid change that gives a folder a new id moves its gestures with it. |
| `popup/LauncherPopupLiveUpdateHandler` | `ITEM_TYPE_DEEP_SHORTCUT` | A pinned shortcut's menu doesn't get a Widgets option added later. |

### App drawer filters

Logic: `lawnchair/.../allapps/filters/`.

| File | Canary | What the hook does |
| --- | --- | --- |
| `res/layout/all_apps_content.xml` | `DrawerFilterChipsRow` | The chip row above the floating header. |
| `allapps/ActivityAllAppsContainerView` | `DrawerFilterSwipeHelper` | Sideways swipes switch chips before the personal/work pager sees them. |
| `allapps/FloatingHeaderView` | `setWorkTabActive` | Chips only apply on the personal tab. |

### App drawer folders

| File | Canary | What the hook does |
| --- | --- | --- |
| `allapps/BaseAllAppsAdapter` | `isSameFolder` | A renamed or re-sorted drawer folder redraws (upstream treats all folder rows as unchanged). |
| `folder/Folder` | "Drawer folders hide their title", "App drawer folders already show their name" | Drawer folders get a shorter footer and no title margin. |

### Per-folder sorting and the folder options button

Logic: `lawnchair/.../folder/` (`FolderSortMode`, `FolderSorter`, `FolderSortSheet`,
`FolderUsageStats`, `ShortcutLaunchCounter`). The button is in
`lawnchair/res/layout/user_folder_icon_normalized.xml`, Lawnchair's copy of the upstream folder
layout. If upstream changes `res/layout/user_folder_icon_normalized.xml`, bring those changes
into Lawnchair's copy too.

| File | Canary | What the hook does |
| --- | --- | --- |
| `folder/Folder` | `FolderSortSheet`, `FolderSortMode.FREE`, `public void applySortMode` | Wires the ⋮ button; re-sorts on bind, open, drop and add; turns off drag reordering in sorted folders. `applySortMode` stays in `Folder` because it needs the private `updateItemLocationsInDatabaseBatch`. |
| `folder/FolderPagedView` | `updateFolderOptionsButton` | Re-centers the title when the page count changes. |

### Blur behind open folders

Logic: `lawnchair/.../folder/FolderBlur.kt`.

| File | Canary | What the hook does |
| --- | --- | --- |
| `statehandlers/BaseDepthController` | `folderBlur` | Interface property for the blur amount. |
| `quickstep/.../util/BaseDepthControllerImpl` | `mFolderBlur` | Applies it to the wallpaper blur and to the workspace and dock. |
| `folder/FolderSpringAnimatorSet` | `addFolderBlurAnimator` | Animates the blur with the folder. It stays here because it uses this file's private spring helper. |
| `folder/FolderAnimationSpringBuilderManager` | `FolderBlur.reset` | Clears the blur when the folder animation resets. |

### Dock search bar above the icons, and dock labels

| File | Canary | What the hook does |
| --- | --- | --- |
| `DeviceProfile` | `getDockSearchBarAboveIcons`, `isQsbAboveIconsOnPhone`, `getPhoneHotseatTopPadding` | Swaps the search bar and icons in the phone dock layout. This is layout math in upstream methods (`getHotseatLayoutPadding`, `getQsbOffsetY`, `getHotseatBarBottomPadding`), so check it by eye after every Launcher3 merge, even when it merges cleanly. |
| `DeviceProfile` | `Math.max(0, cellPaddingY)` | Labels aren't drawn over icons on grids with more rows than fit. |

### Backup and restore

Logic: `lawnchair/.../backup/` (`LawnchairBackup`, `NovaBackupConverter`, `RestoreProfiles`,
`ShortcutRecreator`, `BackupShortcutPins`).

| File | Canary | What the hook does |
| --- | --- | --- |
| `model/ModelDbController` | `consumePendingSanitize` | After a restore, the backup is cleaned up in the new process, against the restored grid and profiles. |
| `provider/RestoreDbTask` | `RestoreProfiles.findProfileOnThisDevice` | Work apps come back when a backup is restored on the same phone. |
| `provider/RestoreDbTask` | `WORKSPACE_SCREEN_ORDER).isBlank()` | Empty pages in a backup are kept. |
| `model/data/FolderInfo` | `ITEM_TYPE_SHORTCUT` | Legacy shortcuts (e.g. contacts from Nova) can be in folders. |

### Branding

| File | Canary | What the hook does |
| --- | --- | --- |
| `build.gradle` | `com.nguyendoconsulting.launcher` | Play Store application id. Never change it: it's the installed app's identity. |
| `build.gradle` | `ndshades/res`, `NDShades.`, `apply from: 'ndshades.gradle'` | ND Shades icons, APK name, app name and strings for every build. `ndshades.gradle` writes the strings at build time from upstream's strings, so new upstream strings and translations are branded automatically. |

## Database

ND Shades raised Lawnchair's Room database (`lawnchair/.../data/AppDatabase.kt`) from version 3
to 4, adding `Folders.sortMode` (`MIGRATION_3_4`, schema in
`schemas/app.lawnchair.data.AppDatabase/4.json`). If upstream Lawnchair also goes to version 4
with a different change, phones already on ND Shades's version 4 will never run upstream's
migration. In that case:

1. Bump the version past both, e.g. to 5.
2. Add a migration from 4 to 5 that checks which columns exist (`PRAGMA table_info`) and adds
   whatever is missing, because a version-4 database could be either upstream's or ours.
3. Keep upstream's own migrations as they are.

ND Shades's preferences (gesture keys like `folder:<id>` and `shortcut:<key>`, drawer filters,
folder blur, dock search position) are new keys in `PreferenceManager2`. They don't collide
unless upstream adds a key with the same name.

## APIs ND Shades relies on

These upstream APIs are called from ND Shades code in `lawnchair/`. If a merge changes them, the
build fails in the ND Shades file. Fix the call there rather than in the upstream file.

- `Launcher`: `getDragLayer`, `getWorkspace`, `getHotseat`, `getAppsView`, `getDepthController`,
  `getModelWriter`, `startActivitySafely` (overridden in `LawnchairLauncher` to count shortcut
  launches).
- `Folder`: `getOpen`, `getContent`, `getInfo`, `isInAppDrawer`, `applySortMode`.
- `FolderPagedView`: `getPageCount`, `isTouchOnIconWithSwipeGesture`.
- `BaseDragLayer`: `isEventOverView`, `mapCoordInSelfToDescendant`.
- `SystemShortcut` factories (`APP_INFO`, `WIDGETS`, `UNINSTALL_APP`, `REMOVE`), wrapped in
  `LawnchairLauncher.hideForPinnedShortcuts`.
- `LauncherPrefs.WORKSPACE_SCREEN_ORDER`, which is Lawnchair's.

## Test after a merge

On a phone, with a restored ND Shades backup that has folders, a work profile and pinned
shortcuts:

- [ ] Swipe gestures on a home screen app, a dock app, a dock folder, an app inside an open folder
      and a pinned shortcut. Swiping up or down on them doesn't open the drawer or notifications.
- [ ] Folder ⋮ button: sort A–Z and Most used. Dragging inside a sorted folder doesn't reorder.
- [ ] Folder blur on the home screen and in the app drawer, opening and closing.
- [ ] Drawer filter chips: tap and swipe between them; the work tab has no chips.
- [ ] Drawer folders: edit from the long-press menu, rename, re-sort; the drawer updates.
- [ ] Dock search bar above the icons, then below again.
- [ ] Back up, then restore: empty pages, work apps, other apps' pinned shortcuts and contact
      shortcuts come back.
- [ ] Settings > About shows ND Shades and the Lawnchair credit; the app name and icon are
      ND Shades.
