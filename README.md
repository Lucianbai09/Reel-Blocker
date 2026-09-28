# Reel Blocker

Personal Android app that bounces you out of YouTube Shorts and Instagram Reels, stops
the Instagram and TikTok feeds from scrolling, and can lock YouTube, Instagram, TikTok
and Discord entirely. Built to cost nothing when it isn't blocking.

## Features

- **Block Shorts / Reels / TikTok** — presses Back when a full-screen Shorts or Reels
  player opens, and stops the Instagram home feed and the TikTok feed from scrolling.
  The feeds need a one-time setup tap each, below.
- **Lock apps** — sends you Home with a "Locked" toast when a locked app opens.
  YouTube, Instagram, TikTok and Discord are toggled separately.
- **Pause blocking** — the escape hatch. Enter minutes, tap **Pause**, and everything
  above stops for that long, counts down in the notification, and comes back on by
  itself. The switches stay on the whole time.
- **Do Not Disturb** — optional; rides along with the lock to make it a study mode.
  Uses priority mode, so alarms and starred contacts still get through.
- **Status notification** — shows what's on, or what's paused and for how much longer.

Both blocking features are independent; either or both can be on.

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

Open the app and flip a switch. There is no per-switch timer: a switch is on until you
turn it off. The status line under each one shows `Off`, `On until you switch it off`,
or `Paused until 3:45 PM`.

To stop everything: turn the switches off, or
`Settings > Accessibility > Reel Blocker > off`.

Note that **Lock apps takes priority over Block Shorts** — if YouTube is locked, you
get sent home before Shorts detection ever runs. Same for TikTok: lock it and you can't
open it at all, so tick that box only if you don't want the gentler feed block instead.

## Pausing

This replaced per-switch timers, which had it backwards: they turned blocking *off*
after a while, so the longer you left the app alone the less it did. A pause is the
other direction — blocking is the standing state, and you buy a window out of it.

1. Have something switched on.
2. Type minutes into the **Pause blocking** box and tap **Pause**.
3. The notification switches to `Paused until 3:45 PM` with a live countdown.
4. When it runs out, everything comes back by itself. **Resume blocking now** in the app
   ends it early.

One timestamp is the whole feature. Both switches stay armed and simply stop acting
until the clock passes it, so there is no state to put back afterwards and nothing that
can get out of step. Do Not Disturb lifts with everything else and comes back with it.

The countdown in the notification is drawn and ticked by SystemUI from that single
timestamp, so it costs this app nothing at all: posted once when the pause starts, never
updated, and it keeps counting even if the process is killed.

**A pause cannot outstay its welcome.** The alarm that ends it is inexact and non-wakeup,
the same as before, so it can land late — but it is not what makes blocking resume. The
timestamp is checked every time anything wakes the service, and while paused the service
stays subscribed to window changes for the apps it covers, so opening a blocked app after
the deadline restores blocking on the spot. All the alarm does is swap the notification
back over when nothing else was going to wake us.

## Setting up feed blocking

Instagram and TikTok each need one setup tap. Their feeds are lists like any other, so
the only thing separating one from your DMs, search results or a profile grid is the view
id of whatever is being scrolled. Those ids belong to the apps and get renamed, so the
app learns yours instead of shipping a guess that would silently block the wrong list.

1. Open Reel Blocker and tap **Set up Instagram feed** (or **Set up TikTok feed**).
2. Open that app, land on the feed, and scroll it once.
3. Come back. The line should read `Instagram feed set up` / `TikTok feed set up`.

That is it, and it survives updates to Reel Blocker. If an app renames its feed and
scrolling starts working again, tap **Re-learn** and repeat.

Scroll the feed and nothing else during step 2 — whatever you scroll first is what gets
learned. If you catch the wrong list, just re-learn. Only one setup is armed at a time,
so tapping the other button cancels the first.

While the switch is on, scrolling a learned feed snaps you back to the top. Everything
else keeps scrolling normally: Instagram DMs, search, Explore and profiles, and on
TikTok the Inbox, Profile and search.

### What this looks like on TikTok

TikTok is always full-screen, so there is no "leave the player" to press Back for — the
feed *is* the player, and Back just exits the app. So TikTok gets the feed treatment and
nothing else: **you stay on the first video and it won't scroll off it**, while the rest
of the app stays reachable. That is the same shape as the Instagram home feed, where you
see the top post and can't scroll past it.

Because of that, TikTok is deliberately absent from the view-id array — there is no id
there that could match it, and the service checks that list before it fetches the window,
so TikTok costs nothing on that path.

TikTok ships under two package names — `com.zhiliaoapp.musically` worldwide and
`com.ss.android.ugc.trill` in some regions and on TikTok Lite — and which one is
installed isn't knowable from inside the app. Both are carried everywhere and both answer
to the one TikTok checkbox, so whichever you have just works.

### How the rewind behaves

The rewind jumps straight to the top where the feed supports that action, and pages up
one screen at a time where it does not. Each of our own scrolls fires the next scroll
event, so the chain walks itself to the top and stops there, since scrolling backward
at the top does nothing.

It is deliberately one action per event and never a tight loop. A backward scroll is
animated, so firing several back to back just cancels and restarts the same animation
and moves about one page in total, which is what let a hard fling outrun an earlier
version of this.

The runaway backstop counts scrolls the feed *refused*, not scrolls attempted. An
earlier version capped attempts, so spam-scrolling burned through the budget with real
scrolls and the block switched itself off for as long as scrolling continued — giving
up hardest exactly when it was needed most. Only a feed that will not scroll back stops
it now.

Event coalescing also drops from 500ms to 100ms, because at 500ms the rewind landed
half a second after the gesture and read as broken. That is the one place this app
knowingly trades battery for responsiveness, so it is scoped as tightly as possible:
only while Block Shorts is on, nothing is paused, and **the app actually on screen is
one whose feed has been learned**.

That last condition matters more than it looks. The timeout is a property of the
service, not of one event type, so shortening it also un-throttles content-changed —
and that handler fetches the whole window root plus id lookups. Left short everywhere,
watching a YouTube video would have cost five times the root fetches for a feature
YouTube cannot use at all.

**Back is never pressed on a home feed.** Back there exits the app, which is what made
the earlier attempt at this unusable and why it was removed in 532ac65. Scrolling back
is the only action that stops a feed without throwing you out of the app.

## Updating

Push to `main`. GitHub rebuilds the APK and replaces the release; re-tap the link
above. If an install fails with *"App not installed"*, uninstall the old copy first.

## Safety

**There is no `INTERNET` permission.** Android enforces that at the kernel level, so
this app cannot open a network socket — nothing it sees can leave the phone, ever.

- Sees YouTube, Instagram, TikTok and Discord only, and only while a switch needs them;
  the system filters everything else out before it reaches the app. Discord is dropped
  from the filter entirely unless the lock is on and its box is ticked, and TikTok until
  either its feed has been set up or its box is ticked.
- Stores twelve values privately, with backup disabled: eight on/off flags, one
  timestamp, the two learned view ids, and which feed a setup tap is waiting on. Screen
  content is never written down.
- Can press Back, press Home, and scroll a list back to the top. That's the whole list
  of things it can do.
- No Device Admin, no overlay, no anti-uninstall — uninstall works normally.

It has two permissions, both narrow:

- `POST_NOTIFICATIONS` — the status notification and the pause countdown.
- `ACCESS_NOTIFICATION_POLICY` — switches Do Not Disturb on and off. It does **not**
  allow reading any notification; that would be `BIND_NOTIFICATION_LISTENER_SERVICE`,
  which this app deliberately does not have and never will.

Deny either and everything else still works. Notably there is **no exact-alarm
permission**, because the pause never needed one; see [Pausing](#pausing).

## Battery

Nothing runs in the background. There's no foreground service, no polling, and no
repeating alarms — the accessibility service only executes inside events the OS hands
it, and it asks the system for **zero** event types when both switches are off.

The pause is an end timestamp checked when something already woke us, not a countdown.
While one is running the service drops to the cheapest tier it has — window changes
only, for the apps it covers — which costs one event per switch into a blocked app and
is what guarantees blocking can't stay off past its deadline on a late alarm.

Scroll events are the priciest tier, since they fire throughout a gesture rather than
once per screen. They are only ever subscribed to while something acts on them: a
one-off feed capture, or feed blocking once a feed id has been learned. Leave both feeds
unconfigured and the app never asks for a scroll event at all. On the scroll path itself,
nothing fetches a node until the app being scrolled is one with a feed set up — so if
you configured TikTok and not Instagram, scrolling Instagram costs a couple of in-memory
reads and no node fetch at all.

Discord and TikTok are filtered more tightly than the other two, from opposite ends.
Discord is a chat app, so it fires content-changed events on every message, and Block
Shorts never acts on it; it is in the filter only when the lock is on and its box is
ticked. TikTok has no player to back out of, so the only thing blocking can do there is
rewind the feed — with no feed id learned there is nothing to act on, and it is kept out
of the filter entirely.

The status notification is a plain notification, not a foreground service, so it costs
one message when state changes and nothing while it's displayed. The pause countdown
inside it is ticked by SystemUI, not by this app.

To verify with `adb`, with both switches off:

```
adb shell dumpsys accessibility
```

`eventTypes` should read `0` for this service. With only the lock on it should show
window-state-changed and not content-changed, and during a pause it should show
window-state-changed only.

## If Shorts stops getting blocked

A YouTube or Instagram update renamed a view id. They're the array at the top of
[BlockerService.kt](app/src/main/java/com/reelblocker/BlockerService.kt#L21). To read
the current ones, open the feed on the phone and run:

```
adb shell uiautomator dump /sdcard/w.xml && adb pull /sdcard/w.xml
```

then grep the dump for `resource-id` and replace the matching line. Don't add extra
ids speculatively — each one is another lookup on every event.

Ids must match the full-screen player only. `clips_video_container` and
`reel_recycler` were both removed for also matching feed-embedded Shorts and Reels,
which turned scrolling a normal home feed into a back press you couldn't escape.

TikTok is not in that array and should not be added to it — pressing Back on TikTok
exits the app. If TikTok scrolling stops getting blocked, it's the learned feed id that
went stale: tap **Re-learn TikTok feed** instead.

## Building locally

You don't need to — GitHub builds it. If you want `adb` anyway, install
[Android Studio](https://developer.android.com/studio), open this folder, and press
Run with the phone connected over USB with developer mode on. `gradle-wrapper.jar`
isn't in the repo; Android Studio generates it on first sync.

Move the project out of OneDrive first — Gradle churns `build/` constantly and
OneDrive sync fights it.
