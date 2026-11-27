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
| `res/layout/all_apps_personal_work_tabs.xml` | `DrawerTabStrip` | The drawer tab bar (Personal or All and the filters, then Work) inside the upstream tab strip. It hides the upstream pill buttons and passes Personal/Work clicks on to them. |
| `workprofile/PersonalWorkSlidingTabStrip` | `instanceof Button` | The strip skips the tab bar, which isn't a button, when styling and selecting its buttons. |
| `allapps/ActivityAllAppsContainerView` | `DrawerFilterSwipeHelper` | Sideways swipes switch filter tabs before the personal/work pager sees them. |
| `allapps/ActivityAllAppsContainerView` | `setDrawerTabBarState`, `showsTabBar` | The tab strip also shows without a work profile once there's a filter: `showsTabBar()` replaces `mUsingTabs` where the strip's visibility and space are decided (`replaceAppsRVContainer`, `setupHeader`, `updateHeaderScroll`, `updateSearchResultsVisibility`, header protection). `mUsingTabs` still means "the personal/work pager is in use". |
| `allapps/ActivityAllAppsContainerView` | `mDrawerTabBarHeight` | `layoutBelowSearchContainer`, `alignParentTop` and `layoutWithoutSearchContainer` leave the tab bar's real height instead of `all_apps_header_pill_height`, which "Hide tab labels" shrinks to a line. |
| `allapps/FloatingHeaderView` | `setWorkTabState` | Tells the tab bar whether there's a Work tab and whether its page is active. |
| `allapps/FloatingHeaderView` | `beginTabSwipe`, `setTabSwipeProgress` | While swiping between drawer tabs (`DrawerTabNeighbors`), the header moves with the finger between the two tabs' header positions, and is put back exactly if the swipe is cancelled. |

### App drawer folders

| File | Canary | What the hook does |
| --- | --- | --- |
| `allapps/BaseAllAppsAdapter` | `isSameFolder` | A renamed or re-sorted drawer folder redraws (upstream treats all folder rows as unchanged). |
| `folder/Folder` | "Drawer folders hide their title", "App drawer folders already show their name" | Drawer folders get a shorter footer and no title margin. |

### Drag and drop inside the app drawer

Logic: `lawnchair/.../allapps/drag/` (`DrawerDragDrop`, `DrawerDropTarget`, `DrawerOrganizer`),
registered in `LawnchairLauncher.onCreate`.

| File | Canary | What the hook does |
| --- | --- | --- |
| `Workspace` | `DrawerDragDrop.keepsDrawerOpen` | `onDragStart` doesn't go to `SPRING_LOADED` for a personal app dragged in the drawer, so the drawer stays open. Dragging above the search bar goes there later. |
| `folder/Folder` | `onDropInAppDrawer`, `!isInAppDrawer() \|\| mIsExternalDrag` | A drawer folder opened by hovering during a drawer drag (`beginExternalDrag`) takes the drop like a home screen folder, closes when the drag leaves (`completeDragExit`), and puts the app in place without the launcher model. `DrawerDragDrop.onDroppedInDrawerFolder` saves it when the folder closes. `sortContents` is `applySortMode` without its drawer check. |
| `dragndrop/LauncherDragController` | `DrawerDragDrop.isDrawerDragActive` | `exitDrag` doesn't close the drawer when a drawer drag is cancelled. |
| `folder/Folder` | `public void openToNameInAppDrawer`, `hidesTitleInAppDrawer` | A drawer folder made by dropping an app on another opens with its title showing and the keyboard up. |
| `folder/Folder` | `DrawerDragDrop.onDrawerFolderRenamed` | `onBackKey` hands a drawer folder's new name to Lawnchair, which saves it to its folder table once the folder has closed. |

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

### Widget stacks and the widget menu

Logic: `lawnchair/.../widgetstack/` (`WidgetStackInfo`, `WidgetStacks`, `WidgetStackView`,
`WidgetMenu`, `WidgetStackFlow`, `WidgetStackActivity`). Results of the stack's list, Android's
"allow widgets" prompt and widget setup screens come back through
`LawnchairLauncher.onActivityResult`.

| File | Canary | What the hook does |
| --- | --- | --- |
| `model/WorkspaceItemProcessor` | `WidgetStacks.loadStack` | Loads stack rows (item type 1000). |
| `model/WorkspaceItemProcessor` | `WidgetStacks.isPossibleStackChild` | Keeps widgets whose container is a stack; upstream deletes any widget not on the home screen or dock. |
| `model/WorkspaceItemProcessor` | `WidgetStacks.finalizeLoad` | Puts each stack's widgets in it, deletes empty stacks and widgets in a missing stack, and turns a one-widget stack back into that widget in place. |
| `model/GridSizeMigrationDBController` | `WidgetStacks.migrationKey`, "a widget stack's widgets go with it" | Changing the grid moves stacks like folders, keeps their size and copies or removes their widgets with them. Without it the migration deletes stacks as an unknown item type. |
| `model/GridSizeMigrationLogic` | `WidgetStacks.ITEM_TYPE_WIDGET_STACK` | Same, for the newer migration's "remove replaced items" step. |
| `util/ItemInflater` | `WidgetStackView.create` | Builds a stack's view; each widget in it is made like a home screen widget. Upstream throws "Invalid Item Type". |
| `Launcher` | `WidgetStacks.deleteStack` | `removeItem`: removing a stack removes its widgets and releases their ids through the Undo queue. |
| `Launcher` | `WidgetStacks.bindInStack` | `bindAppWidget`: a widget placeholder that's replaced once the launcher is showing (or after a rotation) is replaced inside its stack. Upstream adds it to a page, which crashes for a widget in a stack. |
| `Workspace` | `WidgetStacks.findWidgetView` | `getWidgetForAppWidgetId`: also looks inside stacks. Without it, finishing a restored widget's setup can't find a widget in a stack and leaves its "set up" placeholder for good. |
| `Workspace` | `WidgetStacks.removeWidgets` | `removeItemsByMatcher` (when `persistChanges`) and `removeWidget`: an uninstalled app's widget, or one Android says is gone, is also taken out of its stack right away; the stack then dissolves or disappears by Edit's rules. Upstream only clears pages and folders. |
| `Workspace` | `WidgetMenu.showForView` | `beginDragShared`: a long-press on a widget or stack opens its menu, and the drag waits for a move, like an app's menu. Upstream widgets have no menu. |
| `quickstep/.../logging/StatsLogCompatManager` | `as? CollectionInfo` | Usage logging looks up an item's container and casts it to a folder; a widget in a stack has the stack as its container, so the cast is a safe one. Upstream's hard cast crashes. |
| `quickstep/.../model/QuickstepModelDelegate` | `WidgetStackInfo` | `getContainer`: the home screen snapshot log treats a stack container as "no folder" instead of crashing on the cast. |

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

Widget stacks are rows in Launcher3's favorites table with item type 1000
(`WidgetStacks.ITEM_TYPE_WIDGET_STACK`), far above upstream's types. Their widgets are ordinary
widget rows whose container is the stack's id. If upstream ever uses 1000, pick another number and
add a database step that rewrites existing stack rows.

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
- Widget stacks: `ModelWriter` (`addItemToDatabase`, `moveItemInDatabase`, `modifyItemInDatabase`,
  `updateItemInDatabase`, `deleteWidgetInfo`, `deleteItemFromDatabase`), `LauncherWidgetHolder`
  (`allocateAppWidgetId`, `deleteAppWidgetId`, `startConfigActivity`), `Workspace`
  (`getWidgetForAppWidgetId`, `getViewByItemId`, `removeWorkspaceItem`, `addInScreen`),
  `DragView.detachContentView`, `DragOptions.PreDragCondition`, `AppWidgetResizeFrame.showForWidget`,
  `LauncherAppWidgetProviderInfo` spans, `WidgetSizes`, `LoaderCursor`, `CellPosMapper`.

## Test after a merge

On a phone, with a restored ND Shades backup that has folders, a work profile and pinned
shortcuts:

- [ ] Swipe gestures on a home screen app, a dock app, a dock folder, an app inside an open folder
      and a pinned shortcut. Swiping up or down on them doesn't open the drawer or notifications.
- [ ] Folder ⋮ button: sort A–Z and Most used. Dragging inside a sorted folder doesn't reorder.
- [ ] Folder blur on the home screen and in the app drawer, opening and closing.
- [ ] Drawer tabs: with filters, All, the filters and Work (last); tap and swipe between them,
      the underline follows. Without filters, Personal | Work, or no tab bar without a work profile.
      With "Hide tab labels", a thin line where the bar flashes on each swipe.
- [ ] Drawer folders: edit from the long-press menu, rename, re-sort; the drawer updates.
- [ ] Drag in the drawer: hold over a folder until it opens and drop in a spot (free sort) or
      anywhere (sorted), onto an app (new folder opens to be named), out of a folder, to another
      tab at the edge, and above the search bar to the home screen with Remove and Uninstall.
- [ ] Dock search bar above the icons, then below again.
- [ ] Widget stacks: long-press a widget for its menu, letting go leaves it open, moving drags
      the widget. Create a stack, swipe between its widgets, edit it, drag it to Remove and Undo.
      Change the grid size; the stack stays.
- [ ] Back up, then restore: empty pages, work apps, other apps' pinned shortcuts and contact
      shortcuts come back.
- [ ] Settings > About shows ND Shades and the Lawnchair credit; the app name and icon are
      ND Shades.
