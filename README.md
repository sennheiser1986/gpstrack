# GPS Track

An Android app that records where you go — a stripped-down Strava — with an on-device track
database, common-format export (GPX / KML / GeoJSON), and **opt-in** live location sharing
through scannable QR codes. The map, the QR code, and the QR scanner all live inside the app.

An optional, self-hosted [sharing server](server/) relays live positions between installs and
adds a small web side: an admin creates web logins, and a web user can ask to follow a device
from a browser — the device's owner allows or denies the request **in the app**.

- **Language / UI:** Kotlin, Jetpack Compose, Material 3
- **Maps:** osmdroid (online OpenStreetMap tiles, or a downloaded MapsForge vector map for
  fully offline use)
- **Storage:** Room / SQLite, entirely on-device
- **minSdk 26 / targetSdk 35**
- **Licence:** [MIT](LICENSE)

---

## Features

| Tab | What it does |
| --- | --- |
| **Record** | Start/stop a GPS recording. A foreground service keeps capturing while the app is backgrounded or the screen is off. The map shows the trail growing in real time. |
| **Tracks** | Every recording in the local database — name, date, distance, duration, point count. |
| *Track detail* | The track on the map with start/end markers, derived stats (distance, moving/total time, climb, average/max speed), rename, delete, and **export to GPX / KML / GeoJSON** via the system file picker. |
| **Map** | "Where is everyone" — your own marker plus every peer you have switched on, each with a last-seen time. Tap a legend row to centre the map on that dot. |
| **Share** | Your QR code (this install's permanent sharing ID + display name), a **broadcast on/off** switch, editable display name and server URL, **Scan** / **Image** / paste-ID ways to add people, and the list of people you follow. |
| **Manual** | Offline-map download control and all the privacy / permission notes. |

The default map location is central Brussels until the first GPS fix arrives.

---

## Build

Requires **JDK 17–21** (the Gradle version here rejects JDK 25+). The JDK bundled with Android
Studio works well; point `JAVA_HOME` at it, for example:

```bash
export JAVA_HOME="$HOME/Android/Android Studio/jbr"   # or any JDK 17–21
```

Then, from the repo root:

```bash
./gradlew :app:testPrimaryReleaseUnitTest   # run unit tests
./gradlew :app:assemblePrimaryRelease       # build the APK
```

The APK lands in `app/build/outputs/apk/primary/release/`. Without a signing key configured
(see below) the release APK is produced **unsigned**; a debug build is always signed with the
standard Android debug key:

```bash
./gradlew :app:installPrimaryDebug               # build + install debug on a connected device
```

### Point the app at a sharing server

Sharing is off until a server URL is set. Either bake a default in at build time:

```bash
./gradlew :app:assemblePrimaryRelease -PshareServerUrl=https://your-server.example
```

or leave it and set it at runtime on the **Share** tab. `https://gpstrack.example.org` is only
a placeholder default. Cleartext HTTP is allowed **only** for `localhost`, `127.0.0.1`, and the
emulator host alias `10.0.2.2` (see `app/src/main/res/xml/network_security_config.xml`);
everything else must be HTTPS.

### Offline map (optional)

The **Manual** tab has a "Download offline map" button. It fetches a MapsForge vector file
(Belgium by default, a few hundred MB) into the app's private storage; afterwards every map in
the app renders with no network. Override the source with
`-PofflineMapUrl=https://…/your-region.map`.

### Run two instances side by side

The `secondary` product flavor installs a second copy under application id
`io.github.sennheiser1986.gpstrack.b` ("GPS Track B") with its own database and sharing
identity — handy for testing sharing with a single device or emulator:

```bash
./gradlew :app:assemblePrimaryRelease :app:assembleSecondaryRelease -PshareServerUrl=http://10.0.2.2:8080
adb install -r app/build/outputs/apk/primary/release/app-primary-release.apk
adb install -r app/build/outputs/apk/secondary/release/app-secondary-release.apk
```

---

## Publishing to Google Play

1. **Create an upload key** (once). Never commit it.

   ```bash
   "$JAVA_HOME/bin/keytool" -genkeypair -v \
     -keystore gpstrack-upload-key.jks -alias gpstrack \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

2. **Tell Gradle about it.** Copy `keystore.properties.example` to `keystore.properties`
   (git-ignored) and fill in `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
   Alternatively set the environment variables `GPSTRACK_STORE_FILE`,
   `GPSTRACK_STORE_PASSWORD`, `GPSTRACK_KEY_ALIAS`, `GPSTRACK_KEY_PASSWORD`.

3. **Bump the version** in `app/build.gradle.kts` (`versionCode` +1, `versionName`) for each
   release.

4. **Build the App Bundle** Play expects:

   ```bash
   ./gradlew :app:bundlePrimaryRelease
   # app/build/outputs/bundle/primaryRelease/app-primary-release.aab
   ```

5. In the **Play Console**: create the app, enrol in **Play App Signing** (upload the key or a
   generated one), complete the Data safety form (this app collects location only for the
   features the user turns on, stores tracks on-device, and shares live position only while the
   user is broadcasting), and provide a privacy policy URL — a starting point is in
   [`PRIVACY.md`](PRIVACY.md).

6. Upload the `.aab` to a testing track, then promote to production.

**Permissions to expect a review note on:** `ACCESS_BACKGROUND_LOCATION` (needed so recording
and broadcasting survive the screen turning off) and `FOREGROUND_SERVICE_LOCATION`. The
in-app **Manual** tab and `PRIVACY.md` explain the user-facing justification.

R8/shrinking is left **off** so a fresh checkout builds and runs without keep-rule tuning for
osmdroid / MapsForge / ZXing / Room. To enable it, set `isMinifyEnabled` / add
`isShrinkResources = true` in `app/build.gradle.kts` and extend `app/proguard-rules.pro`.

---

## The sharing server

Everything about the FastAPI relay + web panel — local run, VPS install script, Apache +
Let's Encrypt, the admin CLI, and the follow-approval flow — is in
**[`server/README.md`](server/README.md)**. It keeps only the latest position per sharing ID in
memory (dropped ~2 min after the last update); accounts, devices, and follow grants live in a
small SQLite file.

Opening a `gpstrack://peer?id=<id>&name=<label>` link (the exact payload the QR encodes) adds
that install as a followed peer without needing the camera.

---

## Project layout

```
app/src/main/java/io/github/sennheiser1986/gpstrack/
  MainActivity.kt            tabs, permissions, QR-scan + export launchers, notification deep-link
  RecorderViewModel.kt       UI state; drives the recording and sharing services; prepares exports
  TrackRecorderApp.kt        osmdroid + notification-channel setup
  data/                      Room database, entities, DAO, repository
    TrackExporter.kt         GPX / KML / GeoJSON serialisers
    TrackStatistics.kt       distance / time / climb / speed from fixes
    GeoMath.kt               haversine helpers
  map/
    OfflineMap.kt            MapsForge tile provider over the downloaded .map file
    OfflineMapDownloadWorker.kt  resumable WorkManager download + progress notification
    OfflineMapRepository.kt / OfflineMapState.kt
  record/
    RecordingState.kt        in-memory live-recording state for the UI
    LocationRecordingService.kt   foreground GPS capture -> Room
  share/
    SharePreferences.kt      instance id + sharing settings
    PeersStore.kt            followed peers (id, label, visible)
    PeerDirectory.kt         in-memory latest peer positions for the UI
    FollowRequestDirectory.kt   in-memory web follow requests + queued decisions
    LocationShareClient.kt   POST /sync over HttpURLConnection
    LocationShareService.kt  foreground sharing loop + follow-request notifications
    ShareCodec.kt            QR payload encode/decode + bitmap
  ui/                        Compose screens + the shared osmdroid map view
server/                      FastAPI /sync relay, web admin panel, deploy scripts (see its README)
```

## Tests

```bash
./gradlew :app:testPrimaryReleaseUnitTest
```

Covers the geo maths, the track statistics, and the three export serialisers.
