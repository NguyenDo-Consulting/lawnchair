<!--
Copyright 2026, Nguyen-Do Consulting
Licensed under the Apache License, Version 2.0. See LICENSE.txt.
-->

# Locked apps

> **Status:** 🟢 Plan A done and tested.

Design for locking apps behind a fingerprint, face or PIN.

## Hidden and locked are separate

Hiding and locking are two independent switches on an app. Locking never hides an app, and hiding
never locks one.

| Hidden | Locked | Drawer and home | Search | Opening it |
| --- | --- | --- | --- | --- |
| No | No | Shown | Normal results | Opens |
| Yes | No | Not shown | Normal results, including partial matches | Opens |
| No | Yes | Shown | Normal results | Asks for the unlock first |
| Yes | Yes | Not shown | Only when the full app name is typed (upper/lower case ignored) | Asks for the unlock first |

Searching for the full name always works, so no app can become unreachable. For example, a hidden
and locked Play Store can still be found and opened.

Upstream Lawnchair's "Show hidden apps in search results" setting (`hidden_apps_in_search`) is
removed. The rules above replace it.

## When the unlock is asked for

Like Android's own locks (Private Space opens with a fingerprint but is removed with the phone's
pattern or PIN), opening uses the unlock, and removing protection needs a code:

| Lock with | Open a locked app, enter Locked apps | Remove protection (the code) |
| --- | --- | --- |
| Fingerprint or face | Fingerprint or face, whichever the phone allows as secure | The phone's pattern, PIN or password |
| Separate PIN | The separate PIN | The separate PIN |

Separate PIN users get their own PIN as the code, not the phone's: they likely chose it because
someone else knows the phone's pattern.

| Where | Action | Asks for |
| --- | --- | --- |
| Launcher | Open a locked app | The unlock, unless one is still active (see [Relocking](#relocking)) |
| Settings | Enter the **Locked apps** screen | The unlock. With no apps locked (including the first visit, which starts setup), the phone's own pattern, PIN or password instead. A phone without a screen lock goes straight in. |
| Locked apps screen | Uncheck an app; ⋮ → Deselect all, Inverse selection, Reset | The code |
| Locked apps screen | Change Lock with, Change PIN | The code |
| Locked apps screen | Relock less often (e.g. Immediately → When phone restarts) | The code |
| Locked apps screen | Check an app; relock more often | Nothing |
| Long-press, then Customize | Lock an app | Nothing (the phone's pattern/PIN, then setup, if no unlock is set up yet) |
| Long-press, then Customize | Remove a lock | The code |
| Settings | Create a backup | The unlock, if any app is locked |
| Settings | Restore a backup, import a Nova backup | The code, if any app is locked |
| Anywhere | Hide or unhide an app | Nothing |

On the Locked apps screen, the code lasts until the screen is left (including the app going to the
background or the screen turning off), so removing several locks in one visit asks once, and the
next visit asks again. Everywhere else, every removal asks.

The rule: ask only when protection gets weaker, and ask for a code, never just a fingerprint or
face, which may be someone else's. Backups count: a backup reveals the list of locked apps, and
restoring an older backup could remove locks. Setting up or changing the unlock with no apps
locked counts too: otherwise someone could set up an unlock they know, and the owner's next lock
from long-press, which asks for nothing, would use it.

## Settings

Everything lives on one new screen, **Settings → App drawer → Locked apps**:

- **Lock with**: how the user unlocks (see below).
- **Change PIN**: shown when Lock with is Separate PIN.
- **Relock locked apps**: when an unlock expires.
- **The list of locked apps**.

Entering the screen asks for the unlock (or the phone's screen lock, with no apps locked).
Removing anything inside asks for the code, see above. The **⋮** menu has Select all, Deselect all,
Inverse selection and Reset, like Hidden apps; Deselect all or Reset removes every lock at once.

## Lock with

| Option | What unlocks | Notes |
| --- | --- | --- |
| Fingerprint or face | A fingerprint or face that Android rates as secure, among those registered when the lock was set up, including other people's fingerprints. An app can't choose between fingerprint and face: the system offers whichever the phone has as secure. | Default when the phone supports it. On a Pixel 8 or later, face and fingerprint both work. On most Samsung phones and older Pixels this means fingerprint only, because their face unlock isn't rated secure. Needs a phone screen lock, because Android won't register a fingerprint without one. |
| Separate PIN | A PIN that exists only in ND Shades | Always offered. Works on phones without a fingerprint sensor or screen lock. |

Fingerprint or face needs Android 11 or later, which has the system prompt it uses. Older phones
only get Separate PIN.

A third option, Phone screen lock (fingerprint or face, falling back to the phone's pattern/PIN),
was built and then dropped: with removals needing a code, it overlapped the other two.

### Fingerprint or face: new fingerprints

The unlock is tied to a secret key that Android destroys whenever a new fingerprint or face is
added to the phone. This stops someone who knows the phone's PIN from adding their own finger and
using it to open locked apps.

It doesn't stop anyone whose fingerprint or face was already on the phone when the lock was set
up: Android only tells an app that a registered fingerprint matched, never which one, and doesn't
tell apps how many are registered. Pixels allow several fingerprints, so a partner's finger
registered earlier opens locked apps too (confirmed on a Pixel 10 Pro XL). Pixels allow only one
face, so in practice this is about fingerprints. Samsung allows one face plus an "alternative look",
not yet checked for whether that can be a different person. The setup screen says this, and points
people who share fingerprints on a phone to Separate PIN.

When the key is gone, the app says "A new fingerprint or face was added" and the user has to go
through the [reset](#forgot-or-locked-out). After the reset, a new key is created that works with
the fingerprints currently registered.

### Separate PIN storage

Only a salted hash of the PIN is saved, never the PIN itself. After 5 wrong PINs, the next try
waits 30 seconds, doubling with each further wrong PIN up to an hour.

The PIN hash, the fingerprint key's name, Lock with, and the reset wait live in their own file
(`ndshades_app_lock`). Neither ND Shades backups nor Android's own backup include it.

## Backups

- Creating or restoring a backup, or importing a Nova backup, asks for the unlock when any app is
  locked.
- Backups include the list of locked apps and the Relock setting.
- Backups leave out the unlock itself: Lock with, the separate PIN's hash (a short PIN can be
  guessed from its hash by anyone who gets the backup file), and the fingerprint key (which can't
  leave the phone anyway).
- Restoring on the same phone keeps its unlock. After a restore on a phone where the lock isn't
  set up, the locked apps stay locked and the user sets up the unlock again. Until they do,
  opening a locked app starts that setup, which goes through the
  [reset wait](#forgot-or-locked-out) so a restore can't be used to skip it.
- When creating a backup of the layout with locked apps, the user is warned: "The backup keeps
  which apps are locked, but not your fingerprint, PIN or other unlock. After restoring it on a
  phone where the lock isn't set up, you'll need to wait 10 minutes before you can set up the lock
  again."

## Relocking

One unlock opens all locked apps until it expires, the same way Samsung's Secure Folder and Pixel's
Private Space work. **Relock locked apps** options:

- **Immediately**: every launch asks.
- **When screen turns off** (default)
- **5 minutes after screen turns off**
- **When phone restarts**

The unlock is kept only in memory. If Android closes the launcher in the background, the next
launch asks again.

## Forgot or locked out

Covers a forgotten separate PIN, a scarred finger, a broken sensor, and a key destroyed by a new
fingerprint.

1. Tap **Forgot**, then verify with the phone's screen lock.
2. A waiting period starts. A notification stays up the whole time: "Locked apps reset requested.
   Unlock to cancel."
3. When the wait ends, the user sets up the lock again. **Their locked apps stay locked.**
4. Unlocking normally at any time during the wait cancels the reset.

While waiting, locked apps stay locked and the normal unlock still works.

**Waiting time**: the first reset waits 10 minutes. Each further reset request doubles the wait,
up to a cap of 24 hours: 10 min, 20 min, 40 min, 1 h 20 min, 2 h 40 min, 5 h 20 min, 10 h 40 min,
21 h 20 min, then 24 h. Canceled and completed requests both count toward the doubling. There is
no recovery code or other instant reset.

**Warnings and the reset count**: the count is the number of reset requests in a row. The user is
told about it at three points:

- **Requesting a reset**: "This is reset request 3 in a row. You'll wait 40 minutes."
- **Canceling a reset** by unlocking normally: "Someone requested a locked apps reset at 2:14 PM.
  This is reset request 3 in a row."
  - **That wasn't me** (default): keep counting. The next request waits longer.
  - **That was me**: the count starts over. The next request waits 10 minutes.
- **Completing a reset** (setting up the lock again after the wait): the same warning and the
  same two choices. Whoever completes the reset holds the new lock, so letting them choose gives
  nothing away.

The count also starts over after 30 days with no reset requests.

The wait is measured on a clock that only runs while the phone is on and that changing the phone's
date and time doesn't move. Restarting doesn't skip it, and time while the phone is off doesn't
count, so a wait can only get longer, never shorter.

**Phones without a screen lock**: step 1 has nothing to verify against. These phones can only use
Separate PIN, and the reset goes straight to the wait.

## What else locking covers

| Leak | Handling |
| --- | --- |
| Home-screen widgets | Covered with a lock screen until unlocked |
| Notification text in the long-press menu | Nothing to do: this Launcher3 no longer shows notification text there, only dots. |
| Suggested apps row | Hidden apps, locked or not, are filtered out |
| Search history | A query that exactly matches a hidden and locked app isn't saved |
| App shortcuts (long-press items, pinned shortcuts) | Ask for the unlock like the app itself |
| Swipe gestures that open an app | Ask for the unlock like the app itself |
| Split screen, app pairs, taskbar drag | Ask for the unlock. These only run when the launcher owns recents, which needs root. |

## Limits

ND Shades is a launcher, so it can only lock what the launcher opens. Locking is meant to stop
casual snooping, not a determined person. A locked app can still be opened from:

- another launcher: every phone ships with one, and switching the default takes a few taps
- **Android Settings → Apps → Open**
- a notification
- the Play Store's **Open** button
- Overview (recent apps)

Anyone holding the phone can also go to **Android Settings → Apps → ND Shades → Clear storage**, or
uninstall and reinstall. That erases every lock, the reset wait and its history, and the whole home
screen layout. No app can block this, but it can't be done quietly: the owner comes back to a
reset home screen with no locks.

The app should say all of this plainly when setting up the lock.

## Plans

**Plan A**: lock everything the launcher opens, as described above. Done and tested.

**Plan B: not planned.** Decided 2026-11-06: locking is meant for the casual snooper, and plan A
already covers that. The notes below are kept in case it's ever revisited.

Plan B was an optional **Strict lock** that uses the accessibility service to notice when a locked
app comes to the front from anywhere, and covers it until unlocked.

- An app counts as left when none of its windows is visible on any screen. Losing focus to the other
  half of a split screen doesn't count. A picture-in-picture window counts as visible.
- Folding or unfolding a foldable isn't leaving and doesn't turn the screen off. The unlock survives
  it.
- Google Play requires a clear explanation before the accessibility permission is requested, plus a
  written justification when the app is submitted.
- Plan B still isn't real security: anyone holding the phone can turn the accessibility service
  off in Android Settings. Real isolation needs the OS, such as Android's Private Space or
  Samsung's Secure Folder.

Root-only Overview support (QuickSwitch) is out of scope.

## Where the code goes

New code lives in `lawnchair/src/app/lawnchair/applock/`:

- `AppLockManager`: which apps are locked, the in-memory unlock and when it relocks, and the
  unlock requests.
- `AppLockStore`: the unlock's secrets, the reset wait, and the clock the waits run on.
- `BiometricKey`: the key behind Fingerprint or face.
- `AppLockActivity` and `AppLockContent`: a transparent screen that shows the system fingerprint
  prompt or the PIN pad, sets up the unlock, and runs the reset. The system prompt needs an
  activity of its own, so every locked launch goes through this screen.
- `AppLockNotifications`: the notification shown during a reset wait.
- `LockedAppsPreferences`: the **Locked apps** settings screen.
- Strings: `lawnchair/res/values/ndshades_app_lock_strings.xml`, so upstream's `strings.xml` stays
  as it is.

Hooks, all in Lawnchair's own files, none in Launcher3:

| Hook | File |
| --- | --- |
| Launches: icons, folders, search, shortcuts, long-press items | `LawnchairLauncher.startActivitySafely` (an existing ND Shades override) |
| Gestures | `gestures/handlers/OpenAppGestureHandler.kt` |
| Search rules | `search/algorithms/SearchUtils.kt`, `search/algorithms/engine/provider/apps/AppSearchProvider.kt`, `search/algorithms/LawnchairAppSearchAlgorithm.kt` |
| Search history | `allapps/AllAppsSearchInput.kt` |
| Lock switch | `override/CustomizeDialog.kt` |
| Backup, restore and Nova import unlock | `backup/ui/CreateBackupScreen.kt`, `RestoreBackupScreen.kt`, `RestoreNovaBackupScreen.kt` |
| Widget cover | `LawnchairAppWidgetHostView.kt` (Lawnchair's subclass of Launcher3's widget view) |
| Suggested apps | `predictions/LawnchairPredictionEngine.kt` |
| Settings | `preferences2/PreferenceManager2.kt`: `locked_apps`, `app_lock_relock`; `hidden_apps_in_search` removed |

No new dependency: Android's own `BiometricPrompt` (Android 11+) is used, not `androidx.biometric`.

## Testing

On the Pixel 4a (Android 13+, release build) and the Pixel 3 (Android 12, fingerprint only):

- every launch path and every Lock with option
- each relock option
- adding a new fingerprint (the key should be destroyed and a reset required)
- the reset wait, including canceling it and restarting the phone during it
- backup and restore with locks present, including restoring a backup that has no locks
- widget cover, exact-name search, search history
