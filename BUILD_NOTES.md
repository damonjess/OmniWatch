# OmniWatch M180 marker build

## Changes

- Replaced the incorrect WebTRIS sensor-as-camera implementation.
- WebTRIS contains traffic-monitoring sensors such as MIDAS/TMU and does not provide CCTV image feeds; its records were causing the red-X placeholder image.
- National Highways cameras now load from the public camera catalogue at `openhighways.uk/api/cameras`.
- Camera coordinates and names come from actual National Highways CCTV records.
- Live images use the official National Highways host:
  `https://public.highwaystrafficcameras.co.uk/cctvpublicaccess/images/{camera-id}.jpg`
- London/TfL markers remain **red**.
- National Highways/M180 markers are **purple** (`#7B1FA2`).
- Added public regional camera feeds from Traffic Wales, TrafficWatchNI, and Essex Highways.
- Added 146 additional Sheffield City Council traffic-camera locations from its public ArcGIS dataset (212 records published, 146 new non-duplicate locations merged).
- Replaced the previous inaccurate North Lincolnshire records with the council's official WFS dataset: 11 current locations, including Brigg, Scunthorpe, and Haxey.
- Council popup titles now prefer the official `detailed_location` field, preventing a marker from displaying an unrelated area name such as Central Park.
- Added the public webcam milestone:
  - Public Webcams button on the map header.
  - Public Webcams searchable directory activity (`PublicWebcamsActivity`).
  - Search by webcam name, town, or operator; selecting a result centres the map on that webcam.
  - In-app HLS video playback for streams like Eernewoude using Media3 / ExoPlayer.
  - WEB webcams load inside OmniWatch using an embedded WebView inside the bottom sheet (no longer launching external browser).
  - YouTube watch links (e.g. Ramsgate) are automatically converted to privacy-enhanced embedded player URLs (`youtube-nocookie.com/embed/...`).
- The map legend and overlays distinguish the regional sources.
- Legacy WebTRIS sensor records are removed when the real National Highways camera list loads.

## Android Studio setup

1. Open this project in Android Studio.
2. Copy your existing `local.properties` into the project root beside `settings.gradle.kts`.
3. Sync Gradle and run the `app` configuration.

## Build verification

- `./gradlew test assembleDebug`
- Result: successful
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
