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
- The map legend and overlays distinguish the regional sources.
- Legacy WebTRIS sensor records are removed when the real National Highways camera list loads.

Traffic Scotland is not enabled in this build because its official live-camera image service is an approved-subscriber FTP service, not an unrestricted public feed. It can be added when you have authorised Traffic Scotland credentials and permission to use the feed.

Sheffield source: `https://sheffield-city-council-open-data-sheffieldcc.hub.arcgis.com/datasets/c5b5971a1c1248faa95649d081849aa1_7/explore`

## Android Studio setup

1. Open this project in Android Studio.
2. Copy your existing `local.properties` into the project root beside `settings.gradle.kts`.
3. Sync Gradle and run the `app` configuration.

The National Highways CCTV catalogue and image host are public and do not require the WebTRIS subscription key. Keep your existing `local.properties` if you also use the key for other traffic integrations.

## Build verification

- `./gradlew test assembleDebug`
- Result: successful
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

## Live-feed verification

The M180 camera record `21020` currently resolves to:
`https://public.highwaystrafficcameras.co.uk/cctvpublicaccess/images/21020.jpg`

The endpoint returned HTTP 200 with `image/jpeg` during verification.
