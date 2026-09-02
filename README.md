# Reel Blocker

Personal, sideloaded Android app. Two independent features:

1. **Skip Shorts / Reels** — presses Back when the full-screen YouTube Shorts or
   Instagram Reels player opens.
2. **Lock apps** — sends you Home with a toast whenever a locked app opens.

Each has a switch and an optional minutes field (blank or `0` = stay on until you
switch it off).

While either is on, an ongoing status-bar notification says which, and until when.

One permission (`POST_NOTIFICATIONS`), no dependencies, no AndroidX, no icons, no
tests, no analytics.

## Why this app cannot leak anything

**There is no `INTERNET` permission.** Android maps that permission to a Linux group,
so without it the kernel will not let this process open a network socket at all. The
app therefore cannot send anything anywhere — no telemetry, no screen contents,
nothing — regardless of what the code says.

The rest follows from the same audit:

- **What it can read:** YouTube and Instagram only. `packageNames` in the service
  config means the system never delivers events from any other app, and the code only
  calls `rootInActiveWindow` while handling one of those events.
- **What it stores:** six values — four booleans and two timestamps — in app-private
  storage, with `allowBackup="false"`. No screen content is ever written down.
- **What it can do:** press Back and press Home. That is the entire list. No file or
  storage access, no calls, no purchases.
- **What other apps can do to it:** nothing. The receiver is not exported and the
  service is protected by `BIND_ACCESSIBILITY_SERVICE`, which only the OS holds.
- **Getting rid of it:** always possible and immediate. No Device Admin, no overlay,
  no anti-uninstall — `Settings > Accessibility > Reel Blocker > off`, or a normal
  uninstall.

Worst case if it misbehaves is a Back press at the wrong moment.

---

## The battery design, in one page

The app has **no process of its own that ever runs on a schedule**. There is no
foreground service, no `WorkManager`, no polling, no VPN, no repeating alarm, no
timer thread. The OS does the waiting; the app only ever executes inside an event
callback it was handed.

Three mechanisms, in order of how much they save:

1. **`android:packageNames`** in
   [accessibility_service_config.xml](app/src/main/res/xml/accessibility_service_config.xml) —
   the system filters events by package *before* any IPC into this process, so
   everything you do in every other app on the phone costs this app literally
   nothing. This is the single biggest saving here.
2. **Runtime narrowing** via `setServiceInfo()` in
   [BlockerService.kt](app/src/main/java/com/reelblocker/BlockerService.kt#L64).
   The XML declares the maximum event set; the service asks for the minimum the
   currently-active features need:

   | state | `eventTypes` |
   |---|---|
   | both toggles off | `0` — subscribed to nothing |
   | lock only | `TYPE_WINDOW_STATE_CHANGED` |
   | shorts-skip on | `TYPE_WINDOW_STATE_CHANGED or TYPE_WINDOW_CONTENT_CHANGED` |

3. **`android:notificationTimeout="500"`** — the system coalesces content-changed
   events to at most one per 500 ms on its side, so scrolling a feed cannot flood the
   app.

Per event the work is bounded: one `event.packageName` check (free — already in the
event object), an in-memory prefs read, a cooldown comparison, then at most one
`rootInActiveWindow` call and **three** indexed view-id lookups. There is no recursive
walk of the node tree anywhere, and there must never be one — it is the most
expensive thing an accessibility service can do.

**Timers cost nothing.** A timer is an end timestamp compared against
`System.currentTimeMillis()` at a moment the app was already awake. The one-shot
`AlarmManager.set(AlarmManager.RTC, ...)` per feature is **inexact and non-wakeup**,
so the system batches it with work it was already doing. Its only job is to let the
service drop its event subscription promptly; the timestamp check is what actually
enforces the deadline, so a late alarm — or one that never fires — cannot leave
anything blocked past its end time.

**Reboot:** no `BOOT_COMPLETED` receiver. The system rebinds an enabled accessibility
service after boot, and `onServiceConnected()` re-arms both alarms from stored state.

---

## Installing on the phone

Two routes. **Route A installs nothing on the PC** and is the one to use.

### Route A — let GitHub build the APK

[.github/workflows/build.yml](.github/workflows/build.yml) builds the APK on
GitHub's machines and attaches it to a release, so the phone can install it directly.

1. Push the repo:

   ```
   git add -A
   git commit -m "Reel Blocker"
   git push -u origin main
   ```

2. Open the repo's **Actions** tab and wait for *Build APK* to go green. Three to
   four minutes on the first run, faster afterwards.
3. **On the phone**, open the repo's **Releases** page in Chrome and tap
   `app-debug.apk` under the release named *Latest build*.
4. Chrome asks to be allowed to install unknown apps — allow it, then tap
   **Install**.

Every later push rebuilds and replaces that same release, so updating is: push, wait,
re-tap the file.

The debug signing key is cached between runs so updates install over the top. If
GitHub evicts that cache — it drops after roughly a week with no builds — an update
can fail with *"App not installed"*. Uninstall the old copy and install again; you
lose only the toggle settings, which take ten seconds to redo.

Note that Route A always trips the restricted-settings dialog described below,
because tapping a downloaded APK is unambiguously a sideload.

### Route B — Android Studio

Only worth it if you want `adb`, which the battery verification needs.

#### 1. Install Android Studio

Download from <https://developer.android.com/studio> and run the installer with the
defaults. It bundles everything needed: JDK 17, the Android SDK, and `adb`. Nothing
else has to be installed separately.

On first launch it downloads the SDK — allow several minutes.

#### 2. Open this project

`File > Open`, select the `Reel-Blocker` folder, and wait for the Gradle sync to
finish. The first sync downloads Gradle 8.7 and the Android plugin, so it is slow.

> **This folder is inside OneDrive.** Gradle rewrites `build/` and `.gradle/`
> constantly, and OneDrive tries to sync every one of those writes, which makes builds
> slow and can fail them outright with file-lock errors. Either right-click the
> `Reel-Blocker` folder in File Explorer and pick **Always keep on this device** ▸ then
> exclude it via OneDrive settings, or simply move the project somewhere outside
> OneDrive such as `C:\dev\Reel-Blocker`. Moving it is the reliable fix.

This sync is also what generates `gradle/wrapper/gradle-wrapper.jar`, which is **not**
in the repo — it is a binary and cannot be authored as text. If you would rather
create it by hand with a Gradle already on your PATH:

```
gradle wrapper --gradle-version 8.7
```

If Android Studio complains that the Android Gradle Plugin version is too old, accept
its upgrade prompt, or edit the versions in [build.gradle.kts](build.gradle.kts).
Pinned here: AGP 8.5.2, Gradle 8.7, Kotlin 1.9.24, `compileSdk`/`targetSdk` 34,
`minSdk` 26.

#### 3. Put the phone in developer mode

On the phone:

1. `Settings > About phone`, tap **Build number** seven times.
2. `Settings > System > Developer options`, turn on **USB debugging**.

#### 4. Plug the phone in and hit Run

Connect it by USB. If the phone shows "Allow USB debugging?", accept it. Set the USB
mode to **File transfer** if the phone is not detected — some cables and the default
"charging only" mode will not expose it.

The phone should now appear in the device dropdown in the Android Studio toolbar.
Press the green **Run** triangle. That builds a debug APK, signs it with the local
debug key, installs it, and launches it. No release signing and no Play Store are
involved.

### Turn on the accessibility service (both routes)

`Settings > Accessibility > Reel Blocker > on`. The app's own screen shows a red
warning and a shortcut button while the service is off.

### If you see "Restricted setting"

Android 13 and newer block sideloaded apps from turning on accessibility services.
The toggle is greyed out and tapping it says *"For your security, this setting is
currently unavailable."*

To clear it:

1. Dismiss that dialog — dismissing it is what makes the override option appear.
2. `Settings > Apps > Reel Blocker`, then the **⋮** menu at the top right.
3. Tap **Allow restricted settings**.
4. Go back to `Settings > Accessibility` and turn the service on.

Installing through Android Studio usually avoids this, because `adb install` is not
always flagged as a sideload. Copying an APK to the phone and tapping it always is.

### Where adb lives afterwards

The battery verification commands below need `adb`. Android Studio installs it at:

```
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
```

Easiest way to run them is the **Terminal** tab inside Android Studio, which already
has it on the PATH. Standalone alternative, about 10 MB, no installer:
<https://developer.android.com/tools/releases/platform-tools>

---

## Adding a third app

Two edits, both one line:

1. `android:packageNames` in
   [accessibility_service_config.xml](app/src/main/res/xml/accessibility_service_config.xml)
2. the package constants and `lockedApp()` in
   [Prefs.kt](app/src/main/java/com/reelblocker/Prefs.kt)

For Shorts-style skipping you also need that app's feed view id in `shortsViewIds`
(see below).

---

## Testing detection (no adb needed)

**You do not need adb to find out whether this works.** Turn on Skip Shorts, open
YouTube, and tap into a Short. If it bounces you straight back out, the view ids are
right for your installed version and there is nothing more to do. Same for Instagram
Reels.

adb is only needed to *diagnose* a failure, not to test for one.

## When detection breaks

The five entries in `shortsViewIds` at the top of
[BlockerService.kt](app/src/main/java/com/reelblocker/BlockerService.kt#L20) are
**app-owned identifiers, not Android APIs**. A big YouTube or Instagram update can
rename them, and then Feature 1 silently stops working while everything else keeps
running.

Each id in the list is corroborated by at least one independent open-source blocker,
so they are not invented: `reel_recycler`, `clips_viewer_view_pager` and
`clips_video_container` are used by
[AntiScroll](https://github.com/yadavnikhil03/AntiScroll), and `reel_progress_bar` by
[Shorts-Blocker](https://github.com/atick-faisal/Shorts-Blocker). That is corroboration,
not proof — none of them has been checked against the versions on this phone.

To read the real ones off your own install: open the Shorts or Reels player on the
phone, leave it on screen, then

```
adb shell uiautomator dump /sdcard/w.xml
adb pull /sdcard/w.xml
```

and grep the dump for `resource-id`. Pick the id of the container that is present in
the full-screen player and absent everywhere else in the app, and replace the matching
line. **Do not pad the list further "just in case"** — every extra id is another lookup
on every event.

Note that the other blockers all reach their ids by **recursively walking the node
tree** and substring-matching. That is more robust to renames and is exactly what the
battery contract here forbids, which is the deliberate trade this app makes: cheaper
per event, more brittle across app updates.

Scope note: only the full-screen player is blocked. The Shorts shelf on the YouTube
home feed and the Explore grid stay usable — those are browsing, not infinite scroll,
and covering them would mean more lookups per event.

---

## Verifying the battery claims

### 1. Both toggles off means subscribed to nothing

```
adb shell dumpsys accessibility
```

Find this service's registered `AccessibilityServiceInfo` and confirm its
`eventTypes` is `0` (different builds print it as `eventTypes: 0`, `eventTypes=0`, or
an empty type list). This is the invariant that matters: the service must be
subscribed to **zero** event types, not merely returning early from its handler — an
early return still pays for the IPC.

For a readable copy rather than scrolling the terminal:

```
adb shell dumpsys accessibility > acc.txt
```

then read the enabled/bound services section.

### 2. Lock only means no content-changed

Turn on Lock, leave Skip Shorts off, then run the same command. `eventTypes` should
show window-state-changed **only**; `TYPE_WINDOW_CONTENT_CHANGED` (`0x00000800`)
must be absent. That is the expensive tier and it is only on while Skip Shorts is
active.

### 3. Nothing attributed after real use

```
adb shell dumpsys batterystats --reset
```

then use the phone normally for a few hours, then

```
adb shell dumpsys batterystats --charged com.reelblocker
```

There should be essentially nothing: no wakelocks, no jobs, no foreground time, and
no alarms beyond at most one per timer you actually set.

### What has NOT been verified

**None of the above was run.** This project was written on a machine with no JDK, no
Android SDK, and no `adb`, so nothing here has been compiled, installed, or measured.
The battery design is argued from the API contracts, not observed.

The one thing worth checking first on a real device is verification step 1 — that
runtime `setServiceInfo(eventTypes = 0)` actually stops delivery rather than just
recording a zero. The per-event package guard in `onAccessibilityEvent` is
deliberately kept as a belt-and-braces second line so behaviour stays correct either
way, but if `dumpsys` does not show `eventTypes` dropping to `0`, that is a real
finding and the design needs revisiting rather than papering over.

---

## State

`SharedPreferences`, one file, six keys: `shorts_on`, `shorts_until`, `lock_on`,
`lock_until`, `lock_youtube`, `lock_instagram`. `*_until` is epoch millis, `0` = no
timer.

One rule, in one place ([Prefs.kt](app/src/main/java/com/reelblocker/Prefs.kt)):

```kotlin
active = on && (until == 0L || System.currentTimeMillis() < until)
```

Every "is this on right now" question goes through it, so an expired timer is off the
moment it expires whether or not the alarm has landed.

---

## Deliberately absent

Recursive node-tree walks, `flagRetrieveInteractiveWindows`, exact or wakeup alarms,
boot receivers, overlays (`SYSTEM_ALERT_WINDOW`), `INTERNET`, foreground services,
anti-uninstall protection, analytics, accounts, sync, themes, icons, tests.

**The status notification is not a foreground service.** Those get conflated because
a foreground service is legally required to show one, which is why VPN apps always
have both. A plain notification is owned by the system once posted and drawn by
SystemUI — this process can be killed and it stays up. It costs one IPC at the moment
state changes and nothing at all while it is showing, so it does not create a resident
process and does not breach invariant 2.

A full-screen lock Activity was **not** built, on purpose: starting an Activity from
the background is blocked on Android 10+ without `SYSTEM_ALERT_WINDOW`, which would
mean an extra permission screen, a resident overlay in memory, and a component that
can wedge you out of your own phone. Home plus a toast says the same thing for one
line of code and zero permissions.
