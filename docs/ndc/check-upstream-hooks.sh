#!/usr/bin/env bash
# Copyright 2026, Nguyen-Do Consulting
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
#
# Checks that every ND Shades hook in an upstream Launcher3 file is still there, for example
# after merging a new Android version's Launcher3 or a Lawnchair release. Run it from the repo
# root (Git Bash on Windows):
#
#     bash docs/ndc/check-upstream-hooks.sh
#
# Each hook is listed with a "canary": an identifier or comment that's only there because of
# the hook. A missing canary means the merge dropped the hook, and the feature next to it is
# broken even if the app still compiles. See docs/ndc/upstream-merge.md for what each hook
# does and how to put it back.
#
# When you add a hook to an upstream file, add a line here too.

set -u
cd "$(git rev-parse --show-toplevel)" || exit 2

# feature | file | canary (fixed string)
HOOKS=$(cat <<'EOF'
Icon swipe gestures|src/com/android/launcher3/BubbleTextView.java|IconSwipeTarget
Icon swipe gestures|src/com/android/launcher3/BubbleTextView.java|IconGestureKeys.forItem
Icon swipe gestures|src/com/android/launcher3/Workspace.java|static IconSwipeTarget findIconInCellLayout
Icon swipe gestures|src/com/android/launcher3/Hotseat.java|isTouchOnIconWithSwipeGesture
Icon swipe gestures|src/com/android/launcher3/folder/FolderPagedView.java|mTouchOnIconWithVerticalSwipe
Icon swipe gestures|src/com/android/launcher3/folder/FolderPagedView.java|public boolean isTouchOnIconWithSwipeGesture
Icon swipe gestures|quickstep/src/com/android/launcher3/uioverrides/touchcontrollers/PortraitStatesTouchController.java|IconSwipeHitTest
Icon swipe gestures|quickstep/src/com/android/launcher3/uioverrides/touchcontrollers/StatusBarTouchController.java|IconSwipeHitTest
Shortcut gestures|src/com/android/launcher3/model/BgDataModel.kt|GestureShortcutPins
Shortcut gestures|src/com/android/launcher3/popup/LauncherPopupLiveUpdateHandler.java|ITEM_TYPE_DEEP_SHORTCUT
Folder swipe gestures|src/com/android/launcher3/folder/FolderIcon.java|FolderIconSwipeGestures
Folder swipe gestures|src/com/android/launcher3/folder/FolderIcon.java|hasConfiguredVerticalIconSwipeGesture
Folder swipe gestures|src/com/android/launcher3/model/ModelWriter.java|FolderGestures.onItemsDeleted
Folder swipe gestures|src/com/android/launcher3/model/GridSizeMigrationDBController.java|FolderGestures.onFolderIdChanged
Drawer filters|res/layout/all_apps_personal_work_tabs.xml|DrawerTabStrip
Drawer filters|src/com/android/launcher3/workprofile/PersonalWorkSlidingTabStrip.java|instanceof Button
Drawer filters|src/com/android/launcher3/allapps/ActivityAllAppsContainerView.java|DrawerFilterSwipeHelper
Drawer filters|src/com/android/launcher3/allapps/ActivityAllAppsContainerView.java|setDrawerTabBarState
Drawer filters|src/com/android/launcher3/allapps/ActivityAllAppsContainerView.java|topMargin += mDrawerTabBarHeight
Drawer filters|src/com/android/launcher3/allapps/ActivityAllAppsContainerView.java|boolean tabBar = showTabs || mHasDrawerFilters
Drawer filters|src/com/android/launcher3/allapps/FloatingHeaderView.java|setWorkTabState
Drawer filters|src/com/android/launcher3/allapps/FloatingHeaderView.java|public void setTabSwipeProgress
Drawer folders|src/com/android/launcher3/allapps/BaseAllAppsAdapter.java|isSameFolder
Drawer folders|src/com/android/launcher3/folder/Folder.java|Drawer folders hide their title
Drawer folders|src/com/android/launcher3/folder/Folder.java|App drawer folders already show their name
Folder sorting|src/com/android/launcher3/folder/Folder.java|FolderSortSheet
Folder sorting|src/com/android/launcher3/folder/Folder.java|FolderSortMode.FREE
Folder sorting|src/com/android/launcher3/folder/Folder.java|public void applySortMode
Folder sorting|src/com/android/launcher3/folder/FolderPagedView.java|updateFolderOptionsButton
Folder sorting|lawnchair/res/layout/user_folder_icon_normalized.xml|folder_options
Folder blur|src/com/android/launcher3/folder/FolderSpringAnimatorSet.kt|addFolderBlurAnimator
Folder blur|src/com/android/launcher3/folder/FolderAnimationSpringBuilderManager.kt|FolderBlur.reset
Folder blur|src/com/android/launcher3/statehandlers/BaseDepthController.kt|folderBlur
Folder blur|quickstep/src/com/android/quickstep/util/BaseDepthControllerImpl.java|mFolderBlur
Dock search above icons|src/com/android/launcher3/DeviceProfile.java|getDockSearchBarAboveIcons
Dock search above icons|src/com/android/launcher3/DeviceProfile.java|isQsbAboveIconsOnPhone
Dock search above icons|src/com/android/launcher3/DeviceProfile.java|getPhoneHotseatTopPadding
Dock labels on tall grids|src/com/android/launcher3/DeviceProfile.java|Math.max(0, cellPaddingY)
Legacy shortcuts in folders|src/com/android/launcher3/model/data/FolderInfo.java|ITEM_TYPE_SHORTCUT
Backup restore|src/com/android/launcher3/model/ModelDbController.java|consumePendingSanitize
Backup restore|src/com/android/launcher3/provider/RestoreDbTask.java|RestoreProfiles.findProfileOnThisDevice
Backup restore|src/com/android/launcher3/provider/RestoreDbTask.java|WORKSPACE_SCREEN_ORDER).isBlank()
Branding|build.gradle|com.nguyendoconsulting.launcher
Branding|build.gradle|ndshades/res
Branding|build.gradle|NDShades.
Branding|build.gradle|apply from: 'ndshades.gradle'
EOF
)

missing=0
moved=0
declare -A listed

while IFS='|' read -r feature file canary; do
    [ -z "$feature" ] && continue
    path="$file"
    # Upstream converts Java files to Kotlin now and then.
    if [ ! -f "$path" ]; then
        case "$path" in
            *.java) alt="${path%.java}.kt" ;;
            *.kt) alt="${path%.kt}.java" ;;
            *) alt="" ;;
        esac
        if [ -n "$alt" ] && [ -f "$alt" ]; then
            path="$alt"
            echo "MOVED    $file -> $alt (check the hook survived the conversion)"
            moved=$((moved + 1))
        fi
    fi
    listed["$path"]=1
    if [ ! -f "$path" ]; then
        echo "MISSING  [$feature] $file is gone; find where upstream moved it and re-add: $canary"
        missing=$((missing + 1))
    elif ! grep -qF -- "$canary" "$path"; then
        echo "MISSING  [$feature] $path no longer has: $canary"
        missing=$((missing + 1))
    fi
done <<< "$HOOKS"

# Leftover conflict markers in a hooked file.
for path in "${!listed[@]}"; do
    if [ -f "$path" ] && grep -qE '^(<<<<<<<|>>>>>>>) ' "$path"; then
        echo "CONFLICT $path still has merge conflict markers"
        missing=$((missing + 1))
    fi
done

# Upstream files with ND Shades changes that this list doesn't cover yet.
while IFS= read -r path; do
    if [ -z "${listed[$path]:-}" ]; then
        echo "UNLISTED $path has NDShades changes; add its hooks to this script"
    fi
done < <(git grep -lE 'NDShades:|Nguyen-Do Consulting' -- src quickstep res build.gradle \
    'lawnchair/res/layout/user_folder_icon_normalized.xml' 2>/dev/null)

count=$(grep -c '|' <<< "$HOOKS")
if [ "$missing" -eq 0 ]; then
    echo "OK: all $count hooks found ($moved in files that moved)."
else
    echo "$missing of $count hooks need attention."
    exit 1
fi
