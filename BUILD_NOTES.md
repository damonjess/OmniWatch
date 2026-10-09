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
- Added the first public-webcam milestone: optional stream metadata, a cyan Public Webcams overlay, and in-app HLS playback using AndroidX Media3/ExoPlayer.
- Included one verified HLS test webcam at Eernewoude, Netherlands. It is a public stream and is separate from the traffic-camera feeds.
- Added a searchable Public Webcams directory opened from the map screen. Selecting a result centres the map on the webcam marker.
- Added UK public webcam entries for Ramsgate Royal Harbour, Lyme Regis seafront/Cobb Harbour, and CMAL Scottish harbour webcams. These are labelled WEB and open the operator's official page because they do not currently expose a direct HLS URL to OmniWatch.
- Changed WEB webcam handling so pages and permitted embedded players load inside OmniWatch in a WebView; the app no longer launches an external browser for these entries. YouTube watch links are converted to privacy-enhanced embed URLs where possible.
- Expanded the catalogue from 4 to 14 records, including 10 UK public webcam pages: Blackpool, St Ives, Dover, Brighton, Deal, Barmouth, Bala, Porlock Weir, Hereford, and Cardiff.
- The ten SkylineWebcams entries are now labelled `SKYLINE` and play the operator's live feed in the in-app player instead of loading the website. Skyline publishes no stable playlist URL: the stream is a short-lived signed URL on `hd-auth.skylinewebcams.com`, so it is resolved from the camera page immediately before playback (the page's `source:'livee.m3u8?a=TOKEN'` becomes `https://hd-auth.skylinewebcams.com/live.m3u8?a=TOKEN`). The sheet shows `PUBLIC WEBCAM • LIVE HLS` and `LIVE • SkylineWebcams`.
- Selecting an entry in the Public Webcams directory now plays its live feed straight away in addition to centring the map, so a second marker tap is no longer needed.
- Non-Skyline WEB entries (YouTube, Twitch, Visit Dorset, CMAL) keep using the in-app WebView, because they still expose no direct HLS URL.
- The app now remembers which Overpass mirror last answered and tries it first, instead of always starting with the top of a fixed list. This matters on networks that refuse TCP to the main `overpass-api.de` host while community mirrors still work; previously every viewport change paid for the unreachable host first. The failure reason shown in the map header is also reset per fetch so it cannot report a stale cause.
- Insecam markers now open a native MJPEG viewer. Insecam publishes an HTTP viewer page whose embedded `mjpg/video.mjpg` endpoint is multipart JPEG, not HLS/MP4; it cannot be played by Media3 ExoPlayer. The app resolves the viewer page, reads JPEG frame boundaries on a worker thread, and draws frames in a native `InsecamMjpegView`. HLS/MP4 feeds continue using ExoPlayer.
- Webcam entries with an unusable page no longer open a browser view. OpenStreetMap's `contact:webcam` tag is free text and contains values such as `CPE510` (a Wi-Fi bridge model) and `hhttp://...` (a mistyped scheme), which made a WebView render Chromium's own error page. Only a genuine `http(s)` address is embedded now; anything else opens the camera's detail sheet so the raw tag is visible.
- Cleartext `http://` webcam snapshot URLs are upgraded to `https://` before loading. Android blocks cleartext for apps targeting recent API levels, so an http page could never load in the WebView; the snapshot hosts in the catalogue serve https.
- A webcam page that still fails to load now reports it in plain language instead of leaving Chromium's error page on screen, and the external-webcam sheet wraps its content instead of expanding to fill the screen with black.
- The map legend and overlays distinguish the regional sources.
- Legacy WebTRIS sensor records are removed when the real National Highways camera list loads.

Traffic Scotland is not enabled in this build because its official live-camera image service is an approved-subscriber FTP service, not an unrestricted public feed. It can be added when you have authorised Traffic Scotland credentials and permission to use the feed.

Sheffield source: `https://sheffield-city-council-open-data-sheffieldcc.hub.arcgis.com/datasets/c5b5971a1c1248faa95649d081849aa1_7/explore`

North Lincolnshire source: `https://map.northlincs.gov.uk/GetOWS.ashx?VERSION=1.0.0&SERVICE=WFS&REQUEST=GetFeature&TYPENAME=cctv&MAPSOURCE=nlincs_mapsources%2Fnlc_wfs&OUTPUTFORMAT=geojson`

Public webcam test source: `https://webcam-friesemeren.nl/pages/cameras/eernewoude.php`

## Android Studio setup

1. Open this project in Android Studio.
2. Copy your existing `local.properties` into the project root beside `settings.gradle.kts`.
3. Sync Gradle and run the `app` configuration.

The project no longer pins the Gradle daemon to an unavailable Java 25 download. Android Studio's bundled JDK (Java 17 or newer; Java 21 is recommended for this project) can be used from **Settings > Build Tools > Gradle > Gradle JDK**.

The National Highways CCTV catalogue and image host are public and do not require the WebTRIS subscription key. Keep your existing `local.properties` if you also use the key for other traffic integrations.

## Build verification

The source changes were statically checked in this environment. An APK could not be produced here because the sandbox does not have an Android SDK (`SDK location not found`); Android Studio should build it after selecting an installed SDK and copying `local.properties`.

- `./gradlew testDebugUnitTest assembleDebug`
- Result: successful (30 unit tests)
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Verified on a physical device over adb: Blackpool (SkylineWebcams) opens `PUBLIC WEBCAM • LIVE HLS`, plays the live promenade feed, and reports `LIVE • SkylineWebcams` with no player or runtime errors in logcat.
- `PublicWebcamStreamsTest` covers the playlist extraction off-device; the signed URL itself is only valid for one visit, so end-to-end playback is checked on a device rather than in a unit test.

## Live-feed verification

The M180 camera record `21020` currently resolves to:
`https://public.highwaystrafficcameras.co.uk/cctvpublicaccess/images/21020.jpg`

The endpoint returned HTTP 200 with `image/jpeg` during verification.

## TrafficVision UK/Ireland catalogue

- Added a filtered `trafficvision_uk_ie.json` asset from TrafficVision.Live's public catalogue.
- The asset contains 5,717 records: 5,431 United Kingdom and 286 Ireland cameras.
- TrafficVision markers use a lime marker (`#AEEA00`) and the legend label `TrafficVision UK/Ireland`. The original gold (`#F9A825`) sat too close to the OpenStreetMap orange (`#FB8C00`) for the two overlays to be told apart on the map, so TrafficVision was moved to the one hue the other eight sources leave free.
- Image and hybrid feeds open in the existing in-app live-image sheet. Other feed metadata is retained in the detail card for future provider-specific playback support.
- Only UK and Ireland records are bundled; the worldwide catalogue is not loaded into the APK.
- Source: `https://trafficvision.live/`
- The filtered catalogue was generated from TrafficVision's public catalogue manifest and shards on 2026-10-08.

### TrafficVision playback fix

- The first import kept only `imageUrl` and iframe `playerUrl`, so every TrafficVision marker opened a single still image. The catalogue's actual playback fields were dropped.
- Re-pulled the catalogue from the site's own endpoints (`POST /api/session` for an `x-tv-session` token, then `/api/catalog/manifest` and `/api/catalog/shards/<hash>.json`) and enriched `trafficvision_uk_ie.json` in place: `streamUrl` now carries the catalogue `videoUrl` for all 984 hybrid/video feeds, and `youtubeVideoId` is present on the 53 YouTube feeds. The 5,717 UK/Ireland records are unchanged otherwise.
- `TrafficVisionDataLoader` writes `streamType` (`HLS` or `MP4`) from the stream URL and exposes the YouTube id plus a watch URL, so the app can tell a playlist from a file from a page.
- New `CameraStreams` classifies a URL into `HLS`, `MP4`, or `PAGE` (pure, unit tested).
- Tapping a marker now routes by feed: direct **HLS/MP4** plays in the in-app Media3 player, an operator page or YouTube id opens the embedded player, and a snapshot-only camera opens the live-image sheet, which now re-fetches the frame every 7 seconds instead of freezing on the first one.
- The player attaches `Referer: https://trafficvision.live/` (and a browser User-Agent) for TrafficVision cameras only, because `media.trafficvision.live` answers 403 without it. Public webcam streams keep their existing headers.
- Progressive MP4 (TfL JamCams) loops, since each agency file is a short clip rather than a continuous stream.
- Every video camera also carries a snapshot, so a stream that has gone offline (several ozolio relays now answer 404) falls back to the still image with `Live video unavailable — showing the latest image instead` rather than a dead player.
- TrafficVision markers were changed from gold to lime (`#AEEA00`) so they are no longer mistaken for the orange OpenStreetMap markers.
- Tests: `CameraStreamsTest` covers the classifier, `TrafficVisionDataLoaderTest` asserts the bundled catalogue keeps its video and YouTube fields, and `CameraEntityTest` checks a hybrid camera maps to a playable HLS node. `./gradlew testDebugUnitTest assembleDebug` passes (51 unit tests).

### Viewport render fix

- The visible-area box was clamped to 0.6° and that **clamped** box was then used to query the local camera table, so at a wide zoom only a central slice of the map ever drew: most of the 5,717 TrafficVision cameras could never appear, and neither could any other source outside the slice.
- The clamp now applies only to the Overpass request, which is the call that has to stay small because it hits a shared public API. The local table is queried with the true viewport, so every camera in view is drawn.
- Measured on device with a temporary wide starting zoom: 10,640 cameras rendered in one viewport with no ANR. The starting zoom is back at its real value of 12.0.

### ISS live tracking overlay

- Added a live International Space Station layer: a magenta marker (`#E040FB`) that follows the station on the map, an `ISS` button in the map header that centres on it, a legend entry, and a tracker sheet with NASA's live high-definition view plus live telemetry (`OVER` / `LAT` / `LNG` / `ALT` / `VEL` / `VIS`). This mirrors the ISS view on TrafficVision.Live.
- Position comes from the public wheretheiss.at feed (`GET /v1/satellites/25544`), polled every 5 seconds while the screen is visible and stopped in `onPause`. TrafficVision's own ISS catalogue record carries a **frozen** position (44.8228, -28.2395), so it cannot place the marker: the coordinates have to be re-read while the app is open.
- The country under the station comes from `GET /v1/coordinates/{lat},{lon}`, resolved to a readable name with the platform's locale data (`PE` -> `Peru`). That second request only runs while the sheet is open, at most once a minute, and its 404 over open ocean is treated as "no country" rather than a failure.
- The video is NASA's own live stream (`awQzjn72bI0`, the "Live High-Definition Views from the International Space Station" feed named in the ISS entry) played through the existing embedded-player sheet, so the ISS reuses the app's WebView path. The standard-resolution feed `M3HKLzjvKPc` is recorded in the code as the alternate.
- The ISS embed needs the **ordinary** webcam Referer. An earlier version gave it `https://www.youtube.com/` on the reasoning that a YouTube stream should carry YouTube's referer; that was wrong, and the player rendered YouTube error `152-4`, which YouTube's own reports describe as being caused by a missing or invalid Referer. A YouTube host cannot be the embedder of its own embed, so the special case was removed and the ISS now uses the same `https://www.cmassets.co.uk/` referer as every other embedded page.
- The station is deliberately **not** a row in the camera table: it moves, and a moving row would fight the viewport queries. It lives in its own overlay that viewport refreshes never clear.
- `IssTelemetry` formats every value (pure, unit tested, pinned to `Locale.US` so grouped thousands and the decimal point do not change with the device locale). `./gradlew testDebugUnitTest assembleDebug` passes (57 unit tests).
