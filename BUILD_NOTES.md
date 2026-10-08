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

The National Highways CCTV catalogue and image host are public and do not require the WebTRIS subscription key. Keep your existing `local.properties` if you also use the key for other traffic integrations.

## Build verification

- `./gradlew testDebugUnitTest assembleDebug`
- Result: successful (30 unit tests)
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Verified on a physical device over adb: Blackpool (SkylineWebcams) opens `PUBLIC WEBCAM • LIVE HLS`, plays the live promenade feed, and reports `LIVE • SkylineWebcams` with no player or runtime errors in logcat.
- `PublicWebcamStreamsTest` covers the playlist extraction off-device; the signed URL itself is only valid for one visit, so end-to-end playback is checked on a device rather than in a unit test.

## Live-feed verification

The M180 camera record `21020` currently resolves to:
`https://public.highwaystrafficcameras.co.uk/cctvpublicaccess/images/21020.jpg`

The endpoint returned HTTP 200 with `image/jpeg` during verification.
