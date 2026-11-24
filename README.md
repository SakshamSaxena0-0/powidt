# Sparkle Widget for Poweramp

A borderless adaptive-glass home-screen widget for [Poweramp](https://powerampapp.com).

- **Card:** a 28dp rounded card that takes its tint from the current album art.
- **Top:** rounded-square cover, title and artist, and five controls.
- **Middle:** an audio-format pill and a like button.
- **Bottom:** a per-track waveform scrubber.

It uses only Poweramp's official public API ([maxmpz/powerampapi](https://github.com/maxmpz/powerampapi)). The only dependency is `androidx.palette`.

## Layout

| Area | Contents |
| --- | --- |
| Card | Per-track diagonal gradient bitmap: `#D914181D` blended with 22% of the cover's dominant color, a faint glow from the vibrant swatch, clipped to 28dp corners. Falls back to `#CC121417` glass. |
| Top | 104dp cover with 20dp corners. Beside it, centered vertically: title 20sp bold (up to 2 lines) and artist 14sp `#99FFFFFF`. |
| Controls | Their own full-width row in the middle of the card: shuffle, previous, play/pause (56dp disc), next, repeat, drawn about 15% larger than the art scale. |
| Pill row | Format pill such as `FLAC • 96kHz` or `MP3 • 320kbps`, hidden when Poweramp reports no format. Heart: filled for "like" and 2–5 star ratings. |
| Bottom | Waveform of 2dp pill bars with 2dp gaps on a thin baseline: played `#FFFFFFFF`, unplayed `#2EFFFFFF`. Tap anywhere on it to seek. Elapsed and remaining times in 12sp with tabular figures. |

The sizes above are the base (1.0×) design. On Android 12+, the widget scales to the size you give it:

- **Everything** is solved against the widget's height first, then capped by its width. On a 4 × 3 One UI widget (320 × 343dp) that's about 1.2× for the art and 1.35× for the controls.
- **Text** grows slightly more than the art and can use the Size option to go further.
- **Short two-row widgets** scale down so nothing gets clipped.

## Customize

Open the **Sparkle Widget** app. A live preview stays pinned at the top while you change options below it. Every change is saved and applied to your home-screen widgets right away.

| Option | Choices |
| --- | --- |
| Size | 80–130%. Art and controls grow until the top row is full; text keeps growing within the card's height. |
| Card | **Immersive** (default): a blurred, color-boosted copy of the album art fills the card. Also **Glass** (palette gradient), **Dark**, or **Clear** (no card). Opacity, album-color strength (or art brightness for Immersive) and corner radius (0–40dp) are adjustable. |
| Album art | Rounded, circle or square |
| Accent color | White, album color, sky, mint, rose, amber or lilac. Applies to the play button, the played part of the progress bar and the filled heart; the play glyph switches dark or light for contrast. |
| Progress bar | Waveform or line with a knob |
| Show | Status line ("NOW PLAYING · 12 / 48"), artist, shuffle & repeat, audio format, like button, quick actions (equalizer, library), time labels. Hiding shuffle & repeat leaves room for bigger art and controls. |
| Effects | Glass edge and sheen (rim light + top sheen), glow and shadows (art shadow, colored art glow, play-button bloom, progress playhead glow), film grain. |

Settings are stored in `WidgetPrefs`. On Android 11 and lower, the size and accent options that need Android 12 widget APIs are ignored.

## Premium details

- **Lit play button:** an accent gradient disc with a specular highlight and hairline rim, rendered as one bitmap.
- **Progress bar:** played bars fade through the accent color toward a glowing playhead with a dot riding the baseline.
- **Hi-Res badge:** a gold `HI-RES · FLAC 24/96` capsule for lossless audio that is 24-bit or above 48kHz; everything else gets a frosted pill such as `FLAC • 44.1kHz`.
- **Accent states:** shuffle, repeat and the like heart take the accent color when on.
- **Quick actions:** open Poweramp's equalizer (`ACTION_OPEN_EQ`) or library (`ACTION_OPEN_LIBRARY`) directly.

## Everything in the app

**Motion**
- **Breathing background:** two light blobs in the album's colors slowly drift while music plays. They are animated vector drawables inside an indeterminate ProgressBar, so the launcher runs them and this app's process can sleep.
- **Crossfade on track change:** the art and card sit in two ViewFlipper slots and fade between them. `setDisplayedChild` is only sent when the slot actually changes, because it replays the fade.
- **Now-playing bars:** three bouncing bars next to the status line, tinted with the accent.
- **Vinyl:** the art drawn as a record with grooves, the cover as its label, and a reflection that spins while playing.

**Layouts** (Auto picks one per widget size)
- **Classic:** art and title on top, big controls, quick actions, progress.
- **Compact:** for short widgets: art, title, progress and transport in one row.
- **Hero:** for tall widgets: big centered art, text, controls, and a scrollable up-next queue (tap a row to play it).
- **Studio:** compact header, a big waveform, large times, controls.

**Themes and styles**
- **Theme presets:** Aurora, Midnight, Vinyl, Minimal, Studio Gold, Neon.
- **Fonts:** Classic, Condensed, Editorial (serif), Mono.
- **Accent:** album, Material You wallpaper, or a fixed color.
- **Progress styles:** waveform, mirrored waveform, dot matrix, segmented meter, line.

**Per-widget styles**
- Long-press a widget, then Settings, then "Give this widget its own style".
- Widgets without their own style follow the app's main style.

**Features**
- **Up next:** the next track in the playing list, plus the Hero queue. Needs Poweramp library access, from the app's Setup section. Hidden while shuffle is on, because the next track isn't predictable then.
- **EQ presets:** a "Presets" chip cycles Poweramp's equalizer presets (`SET_EQU_PRESET`). The EQ icon opens the equalizer.
- **Sleep timer:** cycles 15, 30, 60 minutes, then off. A live countdown shows in the middle of the times row.
- **Side buttons:** shuffle and repeat, or −10/+10 s seek jumps, or none.
- **Output device:** Bluetooth device name, USB DAC, wired headphones, or phone speaker. Shown between the times when there is room.
- **Volume buttons** (optional), and the library shortcut.
- **Quick actions:** added in priority order only while they fit next to the format pill and the heart.
- **Listening stats:** today, this week, streak, a 7-day chart, top artists and most played. Built from the widget's own Poweramp events; a play counts after 30 s.

## Behavior

| Control | Action |
| --- | --- |
| Play / previous / next | Broadcast straight to Poweramp's `PowerampAPIReceiver` |
| Shuffle | Toggles off ↔ shuffle songs |
| Repeat | Cycles off → list → one |
| Heart | Toggles like (rating 5) ↔ no rating, via `SET_RATING` |
| Cover, title, artist | Open Poweramp |

Shuffle, repeat and the heart go through the app's own receiver, which updates the icon immediately and then forwards the command to Poweramp. Poweramp's mode broadcast has no explicit variant, so a background app can't rely on receiving it.

The waveform is a deterministic pattern seeded by title + duration, so no audio decoding or extra permissions are needed. The unplayed base bitmap is cached per track and size. On each tick, the app draws the played bars over a copy and sends it only when another bar lights up. The bitmap is `ALPHA_8`, which the ImageView tints white, so it is a quarter of the size of an ARGB bitmap.

The time labels use `Chronometer`s, so they tick inside the launcher even while the app sleeps.

### Live waveform (optional)

Without extra permissions, the waveform updates on track, play/pause and seek events, and about every 30 s while playing.

If you grant **notification access** in the setup screen, Android keeps the app's process alive, so the waveform fills in continuously. Notifications themselves are ignored.

## Build and install

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Requirements: JDK 17+, Android SDK (compileSdk 34), minSdk 26, Poweramp build 995+ (for ratings).

After installing, open **Sparkle Widget**, tap **Add to home screen**, then pause and resume Poweramp once. Poweramp refreshes its list of `*_EXPLICIT` broadcast receivers on start or resume.

## Code map

| File | Role |
| --- | --- |
| `PA.java` | Poweramp API constants, commands, album-art loading |
| `PlayerState.java` | Track, status and mode state from broadcasts (incl. codec, sample rate, rating); position extrapolation; persisted |
| `Events.java` | One serial worker: broadcasts and taps → state → widget updates; reads sticky broadcasts |
| `WidgetGraphicsHelper.java` | Rounded art, Palette ambient colors, glass gradient, waveform, format pill text |
| `VisualCache.java` | Per-track art and palette (memory + disk); per-size backgrounds and waveforms |
| `WidgetRenderer.java` | RemoteViews per widget at its real size; waveform-only partial updates |
| `Ticker` / `LiveProgressService` | One-second loop and the optional keep-alive |
| `SetupActivity` | Status, live preview, pin-widget button |

`tools/simulate_poweramp.sh` sends fake Poweramp broadcasts, for testing on an emulator.
