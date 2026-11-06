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
- [x] 4. **App drawer sort order**: by install date, by most used, or by color (Nova offers all three). The drawer is alphabetical
  only. "Most used" needs the usage-access permission, and the install date is free from PackageManager. It fits next to
  your changes in LawnchairAlphabeticalAppsList.kt.
- [ ] 5. **More gestures and actions**:
  - Pinch in/out on the home screen. Nova's pinch is popular, and there's no ScaleGestureDetector in the code.
  - New actions: open Lawnchair settings, toggle the flashlight, show hidden apps, expand notifications.
  - Medium effort. Pinch needs a hook in the workspace touch handling.
- [ ] 6. **Page scroll effects**: transitions like cube, card stack and zoom when you swipe between home-screen pages. Nova users
  notice when these are missing, but they're more of a "nice to have". Medium effort. It needs a hook in Workspace/PagedView,
  which is upstream code.
- [ ] 7. **Widget stacks**: several widgets in one slot that you swipe between, as on iOS and Smart Launcher. Often requested, but a
  large amount of work across the model, drag-and-drop and backups.
- [ ] 8. **Subgrid / free icon placement**: Nova can put icons on half-cells. Lawnchair only allows widget overlap. I'd skip this one:
  it changes the Launcher3 grid model throughout, so every upstream merge would conflict, which goes against your rule of
  keeping changes in lawnchair/src.
- [ ] 9. **Per-element resizing**: scale the icon and label of individual items on the home screen. Doing it in the app drawer
  might be too much. Medium effort. It needs a hook in BubbleTextView, which is upstream code, plus a new field in the model
  and in backups.
- [ ] 10. **Convert filter chips to actual tabs**: show a tab bar with an indicator instead of chips. Swiping between filters
  already works (DrawerFilterSwipeHelper.kt), so this is mostly visual. The tricky part is how it sits next to the upstream
  personal/work tab pager. Low to medium effort.
- [ ] 11. **"Ask AI" in search**: a search result row that sends the query to the user's chosen AI app (Gemini, ChatGPT, Claude),
  like the web search provider does for browsers. Low effort. It fits the existing search provider model in lawnchair/src.
  Parked until something simpler comes up.
