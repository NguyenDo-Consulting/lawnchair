<!--
Copyright 2026, Nguyen-Do Consulting
Licensed under the Apache License, Version 2.0. See LICENSE.txt.
-->

- [x] 1. **Sort folder apps**: sort apps in folders
- [x] 2. **Folder swipe actions ("folder covers")**: tap a folder to open its first app, swipe on it to open the folder.
  This is one of the features Nova users miss most. It also builds directly on your icon-gesture work: `IconGestureKeys.kt:19`
  returns null for folders because they have no component key, so folders can't have gestures yet. A key based on the
  folder's ID would fix that. Low effort for you.
- [x] 3. **[Locked apps](locked-apps.md#design-rules)**: require a fingerprint or PIN before hidden apps are shown (in the hidden-apps screen, or in search
  results via hidden_apps_in_search). There's no BiometricPrompt anywhere in the code. This is a frequent request in every
  launcher community. Low to medium effort, and almost entirely in lawnchair/src.
- [ ] 4. **More gestures and actions**:
  - Pinch in/out on the home screen. Nova's pinch is popular, and there's no ScaleGestureDetector in the code.
  - New actions: open Lawnchair settings, toggle the flashlight, show hidden apps, expand notifications.
  - Medium effort. Pinch needs a hook in the workspace touch handling.
- [ ] 5. **App drawer sort order**: by install date, by most used, or by colour (Nova offers all three). The drawer is alphabetical
  only. "Most used" needs the usage-access permission, and the install date is free from PackageManager. It fits next to
  your changes in LawnchairAlphabeticalAppsList.kt.
- [ ] 6. **Page scroll effects**: transitions like cube, card stack and zoom when you swipe between home-screen pages. Nova users
  notice when these are missing, but they're more of a "nice to have". Medium effort. It needs a hook in Workspace/PagedView,
  which is upstream code.
- [ ] 7. **Widget stacks**: several widgets in one slot that you swipe between, as on iOS and Smart Launcher. Often requested, but a
  large amount of work across the model, drag-and-drop and backups.
- [ ] 8. **Subgrid / free icon placement**: Nova can put icons on half-cells. Lawnchair only allows widget overlap. I'd skip this one:
  it changes the Launcher3 grid model throughout, so every upstream merge would conflict, which goes against your rule of
  keeping changes in lawnchair/src.
- [ ] 9. **Robust Backup, Import, and Restore Ecosystem**: Detect Nova Launcher, Samsung One UI, or Pixel Launcher and automatically replicate icon layouts and folders.
- [ ] 10. **Per-element Resizing**: Allow individual scaling of text and icon on the home screen. Doing it in the app drawer might be too much.
- [ ] 11. **Convert Filter Chips to actual tabs**: Exactly what this says
- [ ] 12. **"Ask AI" in search**: a search result row that sends the query to the user's chosen AI app (Gemini, ChatGPT, Claude),
  like the web search provider does for browsers. Parked until something simpler comes up.
