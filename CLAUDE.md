# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

> **Do not run gradle commands.** The user builds and tests locally themselves via Android Studio.

```bash
./gradlew build              # Full build
./gradlew assembleDebug      # Build debug APK
./gradlew assembleRelease    # Build release APK
./gradlew test               # Run unit tests
./gradlew testDebugUnitTest  # Run a single build variant's unit tests
./gradlew connectedAndroidTest  # Run instrumented tests (requires device/emulator)
./gradlew lint               # Run lint
./gradlew clean              # Clean build artifacts
```

## Project Structure

Single-module Android app (`app/`) with a companion PHP web app in `linien/` (not part of the Android build).

- **App ID**: `com.rwa.wienerlinien`
- **Min SDK**: 26 (Android 8.0), **Target/Compile SDK**: 36
- **Language**: Kotlin, UI: Jetpack Compose + Material 3
- **Dependencies**: Version catalog at `gradle/libs.versions.toml`

## Architecture

MVVM + Repository pattern, manual DI via `WienerLinienApplication`.

```
WienerLinienApplication       ← creates and holds all singletons
  ├── OkHttpClient            ← shared, has RateLimitInterceptor (max 1 req/s)
  ├── WienerLinienApi         ← raw HTTP calls to OGD API
  ├── DepartureRepository     ← fetches departures + traffic info, file cache, DataStore
  └── StopRepository          ← loads/searches haltestellen JSON, in-memory cache

DepartureViewModel            ← AndroidViewModel, shared across Abfahrt/Favoriten/Info tabs
StoerungenViewModel           ← separate ViewModel, only used by StoerungenScreen
```

**Screens and tabs** (`MainScreen` hosts a bottom nav with four tabs):
- `DepartureScreen` — stop input, GPS, search, departure list
- `FavoritenScreen` — saved stops list
- `StoerungenScreen` — all active disruptions
- `InfoScreen` — version, stats, data freshness

`DepartureViewModel` is the **single shared ViewModel** for the Abfahrt, Favoriten, and Info tabs. All three screens receive the same instance; do not create a separate ViewModel for these.

## State Classes (`UiModels.kt`)

All sealed UI state classes live in `UiModels.kt`:
- `DepartureState` — `Idle | Loading | Success | Error(ErrorType)`
- `GpsState` — `Idle | Searching | Found(stopName, distanceMeters) | Error(GpsErrorType)`
- `StopUpdateState` — `Idle | Running | Success | Error`
- `StoerungenState` — `Loading | Success(disruptions, asOf) | Error`

`DepartureState.Error.ErrorType` values: `API_LIMIT`, `SOURCE_DOWN` (5xx + code), `SOURCE_DOWN_GENERIC` (network), `NO_DEPARTURES`.

## Key Design Decisions

**API rate limit**: The Wiener Linien OGD API allows max 1 request/second. `RateLimitInterceptor` enforces this globally on the shared `OkHttpClient`. Never add parallel OkHttp calls through the shared client. `DepartureRepository.getDepartures()` makes exactly two sequential calls per refresh cycle (monitor + trafficInfo).

**StopRepository HTTP client**: `StopRepository` has its **own** `OkHttpClient` (separate from the shared one, no rate limiting) with a 5-minute call timeout per file. This is intentional — the OGD CSV server runs at ~2 KB/s, downloads can take several minutes.

**Haltestellen data** — three-level fallback in `StopRepository.getStops()`:
1. Internal storage: `context.filesDir/haltestellen_mit_linien.json` (after any successful update)
2. Attempt live download via `downloadAndUpdate()`
3. Bundled asset: `app/src/main/assets/haltestellen_mit_linien.json` (~830 KB, ~1100 stops)

After running `linien/linien_mapper.php`, copy the new JSON over the bundled asset to update the seed. `StopUpdateWorker` (WorkManager, weekly) and a manual trigger in Settings (24 h cooldown enforced client-side) call `StopRepository.downloadAndUpdate()`.

**Search behaviour** (`StopRepository.search()`): debounced 500 ms in ViewModel, minimum 2 characters. All-digit queries match by `stopId` prefix; text queries match by name (contains, case-insensitive). Results sorted: exact → starts-with → contains.

**Caching**: API responses cached as JSON in `context.cacheDir/monitor_{stopId}.json` with a timestamp. Stale cache (≤ 5 min old) is served when the live API returns 5xx or a network error (`monitorCode == 0`).

**Polling**: `DepartureViewModel.startPolling()` runs a coroutine loop. Interval is 30 s for fresh live data, 60 s for cached/error states. The spinner only shows on the first load; subsequent refreshes update silently.

**Disruption filtering**: `refTrafficInfoCategoryId == 2` = real service disruptions. Category 1 (elevators) and 3 (notices) are intentionally excluded everywhere.

**Persistence** (DataStore in `DepartureRepository`): `last_stop_id`, `favorites` (Gson-serialised `List<Favorite>`), `keep_screen_on`, `theme_mode` (`"system"|"light"|"dark"`), `gps_declined`, `last_manual_update` (epoch ms).

**Line colors** (`DepartureScreen.lineColor()`): U-Bahn use official Wiener Linien brand colors. All other logic (bus, tram, S-Bahn, night bus) is in the `when` block at the bottom of `DepartureScreen.kt`. The `0-minute` departure entries blink via an infinite `animateFloat` animation.

**Localization**: Three string resource sets — `values/` (DE, default), `values-en/` (EN), `values-b+es+419/` (ES-LatAm).

**Date/time parsing**: Always use `java.time` (e.g. `OffsetDateTime`, `DateTimeFormatter`) — never `SimpleDateFormat`. The OGD API returns timestamps in ISO 8601 format (`+02:00` with colon), which `SimpleDateFormat`'s `Z` pattern does not reliably handle across DST transitions. `java.time` is available from min SDK 26.

## OGD API Endpoints

| Endpoint | Purpose |
|---|---|
| `/ogd_realtime/monitor?stopId={id}` | Live departure times |
| `/ogd_realtime/trafficInfoList?categoryId=2` | Service disruptions |
| OGD CSV files in `/ogd_realtime/doku/ogd/` | Stop/line data for weekly update |
