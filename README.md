# sidecar_launcher

**Sidecar Launcher** — a low-power portrait Android launcher with the look of
[desk_sidecar](https://github.com/ciabattarichri/desk_sidecar) and the PC's
[DankMaterialShell](https://github.com/AvengeMedia/DankMaterialShell): black AMOLED background,
rounded pills, Material 3 colours. No app drawer: widgets, clock and the full app list share one
screen.

> **Beta (v0.1.x).** Built and tested on a Pixel 8 (Android 17). Other phones work but the layout is
> tuned for 20:9 screens.

## Layout

```
┌───────────────────────────────┐
│                    ⚡ 100%     │  battery (system status bar hidden by default)
│ ┌───────────────────────────┐ │
│ │ 🔔 📅 Agenda 🏠 ☀ 🖼        │ │  widgets: notifications · agenda · Google Home
│ │                           │ │           weather (3-h table) · photos
│ └───────────────────────────┘ │
│ ┌───────────────────────────┐ │  player (only while something plays,
│ │ ◎ title · artist  ⏮ ⏯ ⏭   │ │  or double-tap the widgets)
│ └───────────────────────────┘ │
│   17      ┌────────────────┐  │
│   42      │ 🔍 Search apps │  │  clock (swipe: billable hours, rolling)
│ [Tue 24]  │ ★ pinned apps  │  │  app list A–Z, pinned on top
│ [🔍][📷]  │ A … Z          │  │  Google search · camera
└───────────────────────────────┘
```

The app list moves to the left in left-handed mode.

## Gestures

| Where | Gesture | Action |
|---|---|---|
| anywhere | pinch in (two fingers) | launcher settings |
| anywhere not scrolling | swipe down | notification shade |
| app | tap / long press | open / app info |
| app | swipe right / left | pin–unpin / hide |
| "Search apps" | type | search, hidden apps included; Enter opens the first |
| clock | long press / swipe | Clock app / next page (billable hours) |
| widgets | double tap | show/hide the player |
| widget | long press | Calendar, Google Home, Weather, notification history; **Photos → full-screen slideshow** with clock, now playing and synced lyrics |
| Home widget device | tap / long press | toggle / hide |

## Settings

Grouped in categories: look (side, colours from wallpaper / PC / DMS, background, status and gesture
bar, icon size, labels, themed icons, language system/IT/EN), animations, widgets & clock pages,
pinned & hidden apps, calendars, Google Home / weather / Billable Hours, photos (PC folder or
gallery, album, duration, lyrics), system shortcuts (notification access, default launcher, black
home wallpaper).

## With the PC (optional)

Photos from the PC slideshow folder, the PC's matugen palette and the Billable Hours settings come
from `phonedash` in desk_sidecar (pull server on port 8766, token-protected, Tailscale or LAN):

```sh
phonedash pair-launcher     # phone on USB; confirm "Pair with this PC?" on the phone
```

## Power

Built to do nothing while you don't look at it: minute-precision clock, every refresh loop paused
when the launcher isn't on screen, stepped player animations (30 s window by default), black
background. Measured on the Pixel 8: ~0.2% of one core and 1 frame per minute idle, no alarms, jobs
or wakelocks.

## Build and install

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd notification allow_listener dev.vq.sidecarlauncher/dev.vq.sidecarlauncher.PhoneListener
adb shell cmd package compile -m speed-profile -f dev.vq.sidecarlauncher   # precompile (faster, lighter)
```

Then *Settings → Apps → Default apps → Home app → Sidecar Launcher*. Without adb, notification
access needs *App info → ⋮ → Allow restricted settings* first. Quick guide in Italian:
[`release/GUIDA.md`](release/GUIDA.md). Releases: `scripts/make-release.sh` → [release/](release).

Requirements: Android 13+ (API 33); build with JDK 21+ and Android SDK platform 37.

## License

[PolyForm Noncommercial 1.0.0](LICENSE.md): free for any noncommercial use, commercial use not
allowed. Fonts keep their own licenses — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Written largely with an AI coding assistant (Claude Code), under human direction and review.
