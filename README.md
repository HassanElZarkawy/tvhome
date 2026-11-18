# TV Home — tvOS 18-style launcher for the Mi TV Stick

A custom home screen for the Xiaomi Mi TV Stick (Android 10, `aquaman`) that
looks like Apple's tvOS 18 home: a full-screen **hero backdrop from your
Jellyfin Continue Watching**, the show/movie logo top-left, a clock pill
top-right, and your apps in a **horizontal strip docked at the bottom**
(second row peeking, like tvOS). No ad rows, no sponsored content.

## Features

- Hero art from Jellyfin (`HomeJelly`): **random picks from the whole library**
  (shuffled server-side, refetched every time you come home), rotating every
  30 s with a crossfade under a 20% dimming overlay. Art priority is
  **Thumb → Backdrop → Primary**; the hero shows pure artwork — no title or
  logo overlays (thumbs carry their own title art).
- Press **UP** from the dock → full-screen hero ("swipe up for full screen"
  equivalent). **BACK** or **DOWN** returns, with focus preserved on the same
  tile (the dock slides off-screen rather than being removed, so the system's
  auto-focus can't reset your position).
- **Family panel** top-right (dock-styled translucent frame): circular
  portraits of hassan, israa, youssef and layla with the word "Family".
  Photos live in `res/drawable/avatar_*.jpg` — replace and rebuild to update.
  Clock sits top-left.
- **macOS-style dock**: a translucent rounded tray spanning 80% of the screen
  width at the bottom; every app occupies an equal slot (icons shrink
  automatically if the tray fills up). Focused apps magnify ~1.4× with a glow
  ring, and the app name shows in a tooltip above the tray.
- Images cached on disk (~40 MB cap) so the hero appears instantly at boot;
  the pool refetches whenever you come home, with retries if the network
  isn't up yet (e.g. right after boot).
- **Zero-background policy**: 10 s after you land back on the home screen, the
  launcher kills the background processes of every dock app
  (`KILL_BACKGROUND_PROCESSES` — no prompt, install-time permission). The kill
  is cancelled the instant a dock tile is clicked, so an app can never be
  killed mid-cold-start (that once corrupted XP Player's WebView cache dir →
  black screens). Android protects the *just-exited* app in a "previous app"
  slot (oom_adj 700), so that one process lingers until the kernel reclaims it
  under memory pressure; everything else in the dock starts clean on next
  launch. Full force-stop would require device-owner provisioning (deliberately
  not used).
- Long-press the **Settings** tile to edit the Jellyfin server/API key.

## Rebuild & install

```bash
./gradlew assembleDebug
adb connect 192.168.0.235:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press Run (Gradle wrapper included,
JDK 17+ required).

## Architecture

- `MainActivity.kt` — hero layer (two crossfading ImageViews), the dock
  (a LinearLayout tray — no RecyclerView, equal slots via layout weights),
  fullscreen mode, focus bookkeeping, Jellyfin setup dialog.
- `JellyfinClient.kt` — tiny REST client on `HttpURLConnection` + `org.json`.
  Auth = API key header (`X-Emby-Token`); pool = `/Users/{id}/Items/Resume` +
  `/Items/Latest`; images served straight from `/Items/{id}/Images/{Thumb|
  Backdrop|Primary|Logo}` with a disk cache in `cacheDir/jellyfin`.
- Config lives in `shared_prefs/tvhome.xml` (`jelly_url`, `jelly_key`,
  `jelly_user`). Seeded via adb for this install; the in-app dialog can
  change it later.
- The manifest declares `CATEGORY_HOME` at `android:priority="999"` because
  this firmware's RoleManager is stripped (set-home-activity is a no-op) and
  the home is resolved by raw intent priority. BACK is swallowed at the
  activity level — the home screen must never finish() (black screen).

## Device state

Disabled via `pm disable-user --user 0` (reversible with `pm enable <pkg>`):

- `com.google.android.tvlauncher` — stock Google TV launcher (ad rows)
- `com.miui.tv.analytics`, `com.google.android.tungsten.setupwraith`,
  `com.mitv.tvhome.atv`, `com.google.android.katniss`,
  `com.google.android.apps.mediashell`, `com.xiaomi.mitv.updateservice`

To go back to the stock launcher:

```bash
adb shell pm enable com.google.android.tvlauncher
adb shell am force-stop com.zarkawy.tvhome
```

To reset the Jellyfin config: clear the app's data, or long-press the
Settings tile on the home screen.
