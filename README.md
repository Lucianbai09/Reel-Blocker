# Reel Blocker

Personal Android app that bounces you out of YouTube Shorts and Instagram Reels, and
can lock those apps entirely. Built to cost nothing when it isn't blocking.

## Features

- **Skip Shorts / Reels** — presses Back when the full-screen player opens.
- **Lock apps** — sends you Home with a "Locked" toast when a locked app opens.
  YouTube and Instagram are toggled separately.
- **Timers** — blank or `0` means stay on until you switch it off; any number is
  minutes until it turns itself off.
- **Status notification** — shows what's on and until when, while anything is on.

Both features are independent; either or both can be on.

## Install

1. On the phone, open
   <https://github.com/Lucianbai09/Reel-Blocker/releases/download/latest/app-debug.apk>
2. Tap the downloaded file. Chrome will ask to be allowed to install unknown apps —
   allow it, then tap **Install**.
3. Open the app and tap **Open Accessibility Settings**, then turn **Reel Blocker** on.

**If the toggle is greyed out** saying *"For your security, this setting is currently
unavailable"* — Android does this to every sideloaded app:

1. Dismiss the dialog (dismissing it is what unlocks the fix)
2. `Settings > Apps > Reel Blocker` → **⋮** → **Allow restricted settings**
3. Back to `Settings > Accessibility` and turn it on

## Use

Open the app, flip a switch. Put a number in the minutes box first if you want a
timer. The status line under each switch shows `Off`, `On until 3:45 PM`, or
`On until you switch it off`.

To stop everything: turn the switches off, or
`Settings > Accessibility > Reel Blocker > off`.

Note that **Lock apps takes priority over Skip Shorts** — if YouTube is locked, you
get sent home before Shorts detection ever runs.

## Updating

Push to `main`. GitHub rebuilds the APK and replaces the release; re-tap the link
above. If an install fails with *"App not installed"*, uninstall the old copy first.

## Safety

**There is no `INTERNET` permission.** Android enforces that at the kernel level, so
this app cannot open a network socket — nothing it sees can leave the phone, ever.

- Sees YouTube and Instagram only; the system filters everything else out before it
  reaches the app.
- Stores six values (four booleans, two timestamps) privately, with backup disabled.
  Screen content is never written down.
- Can press Back and press Home. That's the whole list of things it can do.
- No Device Admin, no overlay, no anti-uninstall — uninstall works normally.

Its one permission is `POST_NOTIFICATIONS`, for the status notification. Deny it and
everything else still works.

## Battery

Nothing runs in the background. There's no foreground service, no polling, and no
repeating alarms — the accessibility service only executes inside events the OS hands
it, and it asks the system for **zero** event types when both switches are off.

Timers are an end timestamp checked when something already woke us, not a countdown.
The status notification is a plain notification, not a foreground service, so it costs
one message when state changes and nothing while it's displayed.

To verify with `adb`, with both switches off:

```
adb shell dumpsys accessibility
```

`eventTypes` should read `0` for this service. With only the lock on it should show
window-state-changed and not content-changed.

## If Shorts stops getting blocked

A YouTube or Instagram update renamed a view id. They're the array at the top of
[BlockerService.kt](app/src/main/java/com/reelblocker/BlockerService.kt#L20). To read
the current ones, open the feed on the phone and run:

```
adb shell uiautomator dump /sdcard/w.xml && adb pull /sdcard/w.xml
```

then grep the dump for `resource-id` and replace the matching line. Don't add extra
ids speculatively — each one is another lookup on every event.

Ids must match the full-screen player only. `clips_video_container` and
`reel_recycler` were both removed for also matching feed-embedded Shorts and Reels,
which turned scrolling a normal home feed into a back press you couldn't escape.

## Building locally

You don't need to — GitHub builds it. If you want `adb` anyway, install
[Android Studio](https://developer.android.com/studio), open this folder, and press
Run with the phone connected over USB with developer mode on. `gradle-wrapper.jar`
isn't in the repo; Android Studio generates it on first sync.

Move the project out of OneDrive first — Gradle churns `build/` constantly and
OneDrive sync fights it.
