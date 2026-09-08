# Reel Blocker

Personal Android app that bounces you out of YouTube Shorts and Instagram Reels, and
can lock YouTube, Instagram and Discord entirely. Built to cost nothing when it isn't blocking.

## Features

- **Block Shorts / Reels** — presses Back when the full-screen player opens, and
  stops the Instagram home feed from scrolling. Needs a one-time setup tap, below.
- **Lock apps** — sends you Home with a "Locked" toast when a locked app opens.
  YouTube, Instagram and Discord are toggled separately.
- **Timers** — blank or `0` means stay on until you switch it off; any number is
  minutes until it turns itself off.
- **Do Not Disturb** — optional; rides along with the lock to make it a study mode.
  Uses priority mode, so alarms and starred contacts still get through.
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

Note that **Lock apps takes priority over Block Shorts** — if YouTube is locked, you
get sent home before Shorts detection ever runs.

## Setting up Instagram feed blocking

The home feed is a list like any other, so the only thing separating it from your DMs,
search results or a profile grid is the view id of whatever is being scrolled. Those
ids belong to Instagram and get renamed, so the app learns yours instead of shipping a
guess that would silently block the wrong list.

1. Open Reel Blocker and tap **Set up feed blocking**.
2. Open Instagram, land on the home feed, and scroll it once.
3. Come back. The line under the switch should read `Home feed blocked. Learned as ...`

That is it, and it survives updates to Reel Blocker. If Instagram renames the feed and
scrolling starts working again, tap **Re-learn feed** and repeat.

Scroll the home feed and nothing else during step 2 — whatever you scroll first is what
gets learned. If you catch the wrong list, just re-learn.

While the switch is on, scrolling the feed snaps you back to the top. Everything else
in Instagram — DMs, search, Explore, profiles — keeps scrolling normally.

The rewind runs as one burst rather than a page at a time, and stops by itself as soon
as the feed reports it is at the top, so a fling cannot outrun it. Event coalescing
also drops from 500ms to 100ms while a feed is being blocked, because at 500ms the
rewind landed half a second after the gesture and read as broken. That shorter window
is the one place this app knowingly trades battery for responsiveness, and it applies
only while Block Shorts is on and a feed has been learned.

**Back is never pressed on the home feed.** Back there exits Instagram, which is what
made the earlier attempt at this unusable and why it was removed in 532ac65. Scrolling
back is the only action that stops the feed without throwing you out of the app.

## Updating

Push to `main`. GitHub rebuilds the APK and replaces the release; re-tap the link
above. If an install fails with *"App not installed"*, uninstall the old copy first.

## Safety

**There is no `INTERNET` permission.** Android enforces that at the kernel level, so
this app cannot open a network socket — nothing it sees can leave the phone, ever.

- Sees YouTube, Instagram and Discord only, and only while a toggle needs them; the
  system filters everything else out before it reaches the app. Discord is dropped
  from the filter entirely unless the lock is on and its box is ticked.
- Stores nine values (seven booleans, two timestamps) privately, with backup disabled.
  Screen content is never written down.
- Can press Back and press Home. That's the whole list of things it can do.
- No Device Admin, no overlay, no anti-uninstall — uninstall works normally.

It has two permissions, both narrow:

- `POST_NOTIFICATIONS` — the status notification.
- `ACCESS_NOTIFICATION_POLICY` — switches Do Not Disturb on and off. It does **not**
  allow reading any notification; that would be `BIND_NOTIFICATION_LISTENER_SERVICE`,
  which this app deliberately does not have and never will.

Deny either and everything else still works.

## Battery

Nothing runs in the background. There's no foreground service, no polling, and no
repeating alarms — the accessibility service only executes inside events the OS hands
it, and it asks the system for **zero** event types when both switches are off.

Timers are an end timestamp checked when something already woke us, not a countdown.

Scroll events are the priciest tier, since they fire throughout a gesture rather than
once per screen. They are only ever subscribed to while something acts on them: the
one-off feed capture, or feed blocking once a feed id has been learned. Leave feed
blocking unconfigured and the app never asks for a scroll event at all.

Discord is filtered more tightly than the other two. It is a chat app, so it fires
content-changed events on every message, and Block Shorts never acts on it. The
service therefore narrows the package list as well as the event types, and asks to be
woken for Discord only when the lock is on and its box is ticked.
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
