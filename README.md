# Adolar Next Android

Offline-first Android music player and Adolar companion.

Adolar Next uses the application ID `net.polze.adolarnext`. It can therefore be
installed alongside the existing Adolar Radio app (`net.polze.adolarradio`)
without replacing it or sharing its private app data.

It has two surfaces backed by one player:

- Phone app: native station picker and playback controls connected to the app's media service.
- Android Auto: exposes a media service named "Adolar Next" that uses Adolar's session-aware Smart Shuffle via `/api/random` and streams tracks from `/api/stream/<id>`.

The phone app and Android Auto control the same MediaSession. Playback never lives in a WebView, so locking the phone or leaving the phone UI does not create a second player or interrupt track advancement. Android Auto does not allow arbitrary browser/WebView UIs in the car; music apps must expose a media session/browser service instead.

## Build and install without Play Store

1. Install Android Studio.
2. Open this folder as a project: `adolar-android`.
3. Let Android Studio sync Gradle dependencies.
4. On your phone, enable developer options:
   - Android settings -> About phone -> tap "Build number" 7 times.
   - Developer options -> enable "USB debugging".
5. Connect the phone by USB and press **Run** in Android Studio.
6. Open **Adolar Next** on the phone and enter your server URL, for example:

   ```text
   http://192.168.1.100:15002
   ```

## CI / local checks

The standalone repository runs the same checks locally and in GitHub Actions:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## Direct Last.fm scrobbling for local files

Local files are scrobbled by the Android app itself, independently of the
Adolar server. Obtain a Last.fm API account and provide its credentials outside
the repository, for example in `~/.gradle/gradle.properties`:

```properties
LASTFM_API_KEY=...
LASTFM_SHARED_SECRET=...
```

The user then connects Last.fm under **Local settings**. The resulting Last.fm
session key is stored with Android Keystore-backed encrypted preferences. Local
scrobbles remain in Room while offline and WorkManager sends them directly to
Last.fm when a network is available. Radio streams continue to be scrobbled by
Adolar Web; local sync events carry `lastfm_delivery=android_direct` so the
backend must not submit them a second time.

### Adolar Scrobbler (alternative to Last.fm, mutually exclusive)

The same **Local settings** screen can instead connect to a self-hosted
[Adolar Scrobbler](https://github.com/noyse27/adolar-scrobbler) instance,
which speaks the same last.fm-2.0-compatible protocol. Only one of the two is
ever active - connecting to either one clears the other's stored session and
drops whatever it still owed (see `LastFmScrobble.destination`). Provide its
credentials the same way:

```properties
ADOLAR_SCROBBLER_API_ROOT=https://scrobbler.example.com/2.0/
ADOLAR_SCROBBLER_API_KEY=...
ADOLAR_SCROBBLER_SHARED_SECRET=...
```

`API_KEY`/`SHARED_SECRET` are that instance's app credentials, shown to admins
under its own Settings page (App-Zugangsdaten). Unlike Last.fm, login uses a
direct username/password submission (`auth.getMobileSession`), since the
instance is admin-trusted rather than a third-party service - no OAuth-style
redirect is needed.

## Android Auto developer test

1. Open Android Auto settings on the phone.
2. Tap the Android Auto version repeatedly until developer settings are enabled.
3. In Android Auto developer settings, enable **Unknown sources**.
4. Start Android Auto. The app should appear as a media app named **Adolar Next**.

## Notes

- The installed app version is shown in both the native player header and the settings screen.
- The Android Auto media service supports Smart Shuffle playback, play/pause, previous/restart, and next. Its shuffle session survives media-service restarts and resets when the Adolar server URL changes.
- Playback runs as a foreground media service with a MediaStyle notification, wake lock, audio-focus handling, and automatic continuation after a completed track. This keeps playback alive when the phone UI is in the background or Android Auto disconnects from the app screen.
- Native playback uses two Media3 ExoPlayers for an eight-second equal-power crossfade. Both share a version-safe 384 MB disk cache; only the active player owns audio focus and the single MediaSession.
- Five upcoming tracks are fetched per request and queued locally, so preload and manual Next do not normally wait for another API round trip.
- Android Auto playback uses the public Adolar radio endpoints, so it does not need a login cookie.
- Cleartext HTTP is enabled because Adolar is commonly used on a local NAS URL.
