# Lemuroid — Project Specification

> **Spec-Driven Context Document** — update this file whenever you change architecture, add features, or modify data flows.  
> Last updated: 2026-04-13

---

## 1. Project Overview

**Lemuroid** is an Android multi-system emulator that integrates a HuggingFace-hosted ROM catalog (`luisluis123/lemusets`). ROMs are distributed as **0-byte placeholder files** (catalog entries). The actual ROM data is downloaded on-demand when the user taps a game.

- **Language**: Kotlin 100%
- **UI**: Jetpack Compose (phone/tablet) + Leanback (TV)
- **Min SDK**: 21
- **App package (debug)**: `com.swordfish.lemuroid.debug`
- **App package (release)**: `com.swordfish.lemuroid`
- **Build system**: Gradle Kotlin DSL (`build.gradle.kts` throughout)
- **Build command (dev)**: `./gradlew assembleFreeDynamicDebug`

---

## 2. Module Map

| Module | Role |
|--------|------|
| `lemuroid-app` | Main app — all UI (Compose + TV), ViewModels, Workers, ROM delivery |
| `retrograde-app-shared` | Shared lib — Room DB, entities, DAOs, GameLoader, Storage, BIOS, Saves, Cores |
| `lemuroid-app-ext-free` | Free flavor — CoreUpdaterImpl (GitHub releases), SaveSync stub, ReviewManager |
| `lemuroid-app-ext-play` | Play Store flavor — CoreUpdaterImpl, SaveSyncManagerImpl (Google Drive), ReviewManager |
| `lemuroid-metadata-libretro-db` | LibretroDB metadata — game title/cover/system lookup |
| `lemuroid-touchinput` | Touch controller — 24 radial layouts, one per system |
| `retrograde-util` | Utility extensions (coroutines, math, file, etc.) |

### Dependency Injection
- **Framework**: Dagger 2
- **Entry points**: `LemuroidApplicationComponent`, `LemuroidApplicationModule`

---

## 3. Supported Systems (28)

Defined in `retrograde-app-shared/.../library/SystemID.kt`:

| SystemID enum | DB name (`id`) | System |
|---------------|---------------|--------|
| `NES` | `nes` | Nintendo Entertainment System |
| `SNES` | `snes` | Super Nintendo |
| `GENESIS` | `md` | Sega Mega Drive / Genesis |
| `SEGACD` | `scd` | Sega CD |
| `GB` | `gb` | Game Boy |
| `GBC` | `gbc` | Game Boy Color |
| `GBA` | `gba` | Game Boy Advance |
| `N64` | `n64` | Nintendo 64 |
| `SMS` | `sms` | Sega Master System |
| `PSP` | `psp` | PlayStation Portable |
| `NDS` | `nds` | Nintendo DS |
| `GG` | `gg` | Sega Game Gear |
| `ATARI2600` | `a26` | Atari 2600 |
| `ATARI7800` | `a78` | Atari 7800 |
| `PSX` | `psx` | PlayStation 1 |
| `FBNEO` | `fbneo` | FinalBurn Neo (arcade) |
| `NEOGEO` | `neogeo` | SNK Neo Geo |
| `MAME2003PLUS` | `mame2003plus` | MAME 2003-Plus (arcade) |
| `PC_ENGINE` | `pce` | PC Engine / TurboGrafx-16 |
| `LYNX` | `lynx` | Atari Lynx |
| `NGP` | `ngp` | Neo Geo Pocket |
| `NGC` | `ngc` | Neo Geo Pocket Color |
| `WS` | `ws` | WonderSwan |
| `WSC` | `wsc` | WonderSwan Color |
| `DOS` | `dos` | DOS (DOSBox) |
| `NINTENDO_3DS` | `3ds` | Nintendo 3DS (Citra) |
| `MSX` | `msx` | MSX |
| `MSX2` | `msx2` | MSX2 |

---

## 4. Database Schema

**Class**: `RetrogradeDatabase` (`retrograde-app-shared/.../db/RetrogradeDatabase.kt`)  
**DB name**: `retrograde`  
**Room version**: `13`

### Migration history

| Migration | Change |
|-----------|--------|
| 8 → 9 | Add `datafiles` table |
| 9 → 10 | Add `downloaded_roms` table |
| 10 → 11 | Add index on `games.fileName` |
| 11 → 12 | `games SET systemId='neogeo' WHERE systemId='fbneo'` for 103 Neo Geo ROMs (superseded by 12→13) |
| 12 → 13 | `games SET systemId='neogeo' WHERE systemId='mame2003plus'` + `fbneo` for 103 Neo Geo filenames (ROMs were in `arcade/` folder, indexed as `mame2003plus`) |

### 4.1 `games` table — `Game.kt`

| Column | Type | Notes |
|--------|------|-------|
| `id` | Int (PK, autoGenerate) | |
| `fileName` | String | e.g. `1942a.zip` |
| `fileUri` | String (unique index) | `file:///...` path |
| `title` | String | |
| `systemId` | String | matches `SystemID.dbname` |
| `developer` | String? | |
| `coverFrontUrl` | String? | |
| `lastIndexedAt` | Long | epoch ms |
| `lastPlayedAt` | Long | epoch ms |
| `isFavorite` | Boolean | |

No size or download-status field — download tracking is in `downloaded_roms`.

### 4.2 `datafiles` table — `DataFile.kt` (migration v8→9)

| Column | Type | Notes |
|--------|------|-------|
| `id` | Int (PK, autoGenerate) | |
| `gameId` | Int (FK → games.id) | |
| `fileName` | String | |
| `fileUri` | String | |
| `lastIndexedAt` | Long | |
| `path` | String | |

Used for multi-file games (e.g. PSX `.bin`/`.cue`).

### 4.3 `downloaded_roms` table — `DownloadedRom.kt` (migration v9→10)

| Column | Type | Notes |
|--------|------|-------|
| `fileName` | String (PK) | matches `games.fileName` |
| `fileSize` | Long | bytes |
| `downloadedAt` | Long | epoch ms |

**Logic**: absence = placeholder (0-byte on disk); presence = ROM has real content.

### 4.4 DAOs

- `GameDao` — CRUD + paged queries on `games`
- `DataFileDao` — CRUD on `datafiles`
- `DownloadedRomDao` — `insert()`, `delete()`, `isDownloaded(fileName): Boolean`, `getDownloadedFileNames(): Flow<Set<String>>`
- `GameSearchDao` — FTS search over `games`

---

## 5. ROM Delivery Stack

### 5.1 HuggingFace Dataset

- **Repo**: `luisluis123/lemusets` (dataset type)
- **Structure**: `roms/<system>/<game>.<ext>`  
  Example: `roms/arcade/1942a.zip`, `roms/snes/super_mario_world.sfc`
- **0-byte files are intentional placeholder catalog entries** (spec decision #7 in `especificacao_versao2.md`). They allow the app to show the full library without pre-downloading all ROMs.

### 5.2 StreamingRomsManager — bulk catalog download (primary)

**File**: `lemuroid-app/.../shared/roms/StreamingRomsManager.kt`

**Purpose**: Downloads the entire HF catalog file-by-file into the Lemuroid ROMs directory. Files arrive as 0-byte placeholders (because HF returns empty content for those), which is the intended result. After each file the library index is triggered.

**State sealed class** (`StreamingRomsState`):
```kotlin
Idle
Downloading(progress: Float, currentFile: String, downloadedFiles: Int, totalFiles: Int)
Paused
Done
Error(message: String)
```

**SharedPreferences** (prefs name `streaming_roms_prefs`):

| Key | Purpose |
|-----|---------|
| `streaming_download_done` | Catalog download completed flag |
| `streaming_download_started` | Has ever started |
| `streaming_download_paused` | Currently paused |
| `wifi_only_download` | Restrict to WiFi |
| `streaming_downloaded_files` | Count of files already downloaded (for resume) |

**API URLs**:
```
List files:   https://huggingface.co/api/datasets/luisluis123/lemusets/tree/main/roms?recursive=true&limit=10000
Download:     https://huggingface.co/datasets/luisluis123/lemusets/resolve/main/<path>?download=true
```

**Key behaviors**:
- `ensureActive()` called every 256KB in write loop (cooperative cancellation)
- `limit=10000` (~3 API pages for the full catalog)
- Supports pause/resume/cancel
- After completion, triggers `LibraryIndexScheduler.scheduleLibrarySync()`

**Data class**:
```kotlin
data class HuggingFaceFileEntry(val path: String, val size: Long, val downloadUrl: String)
```

### 5.3 StreamingRomsWork

**File**: `lemuroid-app/.../shared/roms/StreamingRomsWork.kt`

WorkManager `CoroutineWorker`. `UNIQUE_WORK_ID = "streaming_roms_work"`.  
`enqueue(replace: Boolean = false)` — uses `KEEP` policy by default, `REPLACE` when `replace=true`.

### 5.4 RomsDownloadManager — legacy batch download

**File**: `lemuroid-app/.../shared/roms/RomsDownloadManager.kt`

Downloads per-system ZIPs from HuggingFace and extracts them. Uses `limit=1000` per-folder with `Link` header pagination. State: `DownloadRomsState` sealed class (Idle, Downloading, Done, Error).

### 5.5 RomsDownloadWork

WorkManager worker wrapping `RomsDownloadManager`.

### 5.6 RomOnDemandManager — single ROM on-demand download (primary for gameplay)

**File**: `lemuroid-app/.../shared/roms/RomOnDemandManager.kt`

**Purpose**: Downloads one specific ROM when the user taps a 0-byte placeholder.

**Endpoint**:
```
GET https://emuladores.pythonanywhere.com/find_by_file?path=<fileName>&source_id=1&system=<endpointSystem>
```
- Response: plain text URL, or empty/404 = not found
- `endpointSystem` comes from `RomSystemMapper`

**Result sealed class**:
```kotlin
sealed class DownloadResult {
    object Success : DownloadResult()
    object NotFound : DownloadResult()
    data class Failure(val message: String) : DownloadResult()
}
```

**Key behaviors**:
- 5 retry attempts with back-off
- `activeCall: Call?` field — cancelled via `activeCall?.cancel()` on scope cancellation
- `ensureActive()` in write loop
- On cancel: restores 0-byte placeholder so the file remains a valid catalog entry
- On success: `downloadedRomDao.insert(DownloadedRom(...))` + `LibraryIndexScheduler.scheduleLibrarySync()`
- `deleteRom(game)`: truncates file to 0 bytes + `downloadedRomDao.delete(fileName)`
- `isPaused: StateFlow<Boolean>` — exposed so `RomDownloadDialog` can show Pause/Resume button state

### 5.7 RomSystemMapper

**File**: `lemuroid-app/.../roms/RomSystemMapper.kt`

Maps Lemuroid `systemId` (or HF folder alias) → pythonanywhere endpoint system name. 43 entries:

```
md, megadrive        → megadrive
scd, megacd          → megacd
sms, mastersystem    → mastersystem
gg, gamegear         → gamegear
pce, pcengine        → pcengine
ngp                  → ngp
ngc, neogeocd        → neogeocd
fbneo                → fbneo
neogeo               → neogeo
mame2003plus, arcade → mame2003plus
a26, atari2600       → atari2600
a78, atari7800       → atari7800
msx, msx2            → msx
(all others map as-is: nes, snes, gba, gbc, gb, n64, psx, psp, nds, lynx, ws, wsc, dos, 3ds)
```

---

## 6. Click → Launch Flow (on-demand)

```
User taps a game card
  ↓
MainActivity.onGameClick(game)
  ↓
isGamePlaceholder(game)?
  = uri.scheme == "file" && File(uri.path).length() == 0L
  ↓
  YES → selectedGameForDownload.value = game
          → RomDownloadDialog shown
          → user confirms → downloadState = Downloading
          → LaunchedEffect → romOnDemandManager.downloadRom(game) { progress }
          → DownloadResult.Success → onDownloadComplete(game) → gameInteractor.onGamePlay(game)
          → DownloadResult.NotFound → show "not found" error
          → DownloadResult.Failure(msg) → show error message
  NO  → gameInteractor.onGamePlay(game)
          → gameLauncher.launchGameAsync(activity, game, loadSave=true, useLeanback)
          → BaseGameActivity.launchGame() → startActivityForResult(REQUEST_PLAY_GAME=1001)
```

### RomDownloadDialog states

| State | UI |
|-------|----|
| `Idle` | Cover image + title + confirm text + Download / Cancel buttons |
| `Downloading` | Cover + title + progress bar + Pause / Cancel buttons |
| `Paused` | Cover + title + progress bar (isPaused=true) + Resume / Cancel buttons |
| `NotFound` | Cover + title + "not found" message + Close button |
| `Error(msg)` | Cover + title + error message + Close button |
| `Done` | Dialog auto-dismisses, game launches |

---

## 7. Long-Press Context Menu

**File**: `lemuroid-app/.../main/MainGameContextActions.kt`

Bottom sheet shown on long-press of any game card. Parameters:
- `isGameDownloaded: Boolean` — drives Delete visibility
- `onDeleteRom: ((Game) -> Unit)?` — present only for downloaded ROMs

**Actions**:
| Action | Icon | Condition |
|--------|------|-----------|
| Resume (Play) | `PlayArrow` | always |
| Restart | `RestartAlt` | always |
| Add/Remove Favorite | `Favorite`/`FavoriteBorder` | toggles |
| Create Shortcut | `AppShortcut` | `shortcutSupported` |
| Delete ROM | `Delete` | `isGameDownloaded && onDeleteRom != null` |

---

## 8. Corruption Detection Flow

```
BaseGameActivity finishes → setResult(RESULT_ERROR, intent)
  intent extras:
    PLAY_GAME_RESULT_ERROR      = error message string
    PLAY_GAME_RESULT_GAME       = Game (serializable)
    PLAY_GAME_RESULT_IS_ROM_LOAD_FAILURE = Boolean
    PLAY_GAME_RESULT_LEANBACK   = Boolean
    PLAY_GAME_RESULT_SESSION_DURATION = Long (ms)

GameLaunchTaskHandler.handleErrorWithCorruptionCheck()
  IF isRomLoadFailure == true
    AND downloadedRomDao.isDownloaded(fileName) == true
  THEN
    romOnDemandManager.deleteRom(game)   ← truncates to 0-byte, removes from downloaded_roms
    show corruption dialog ("ROM may be corrupted, will re-download next play")
```

**Result codes** (in `BaseGameActivity`):
```kotlin
RESULT_ERROR           = Activity.RESULT_FIRST_USER + 2   // = 3
RESULT_UNEXPECTED_ERROR = Activity.RESULT_FIRST_USER + 3  // = 4
REQUEST_PLAY_GAME      = 1001
```

---

## 9. Library Indexing

**Key class**: `LemuroidLibrary` — scans the ROMs directory, creates/updates `games` rows.

**Workers** (WorkManager):

| Worker | Purpose |
|--------|---------|
| `LibraryIndexWork` | Scans ROM files, populates DB |
| `CoreUpdateWork` | Downloads/updates core `.so` files |
| `StreamingRomsWork` | Runs `StreamingRomsManager` (catalog download) |
| `RomsDownloadWork` | Runs `RomsDownloadManager` (legacy ZIP download) |
| `SaveSyncWork` | Google Drive save sync (Play flavor) |
| `CacheCleanerWork` | Clears stale cache entries |
| `ChannelUpdateWork` | Updates Android TV channel |

**Scheduler**: `LibraryIndexScheduler.scheduleLibrarySync()` — called after any ROM change.

### DocumentFileParser — 0-byte handling

`parseStandardFile()`: if `fileSize <= 0` → CRC32 = null, file is indexed as placeholder.  
`isGameEntry()`: returns `false` for entries with `fileSize <= 0` inside ZIPs → 0-byte ZIPs are not parsed as compressed ROMs.

---

## 10. Navigation (Mobile)

### Bottom navigation (4 tabs)

Defined in `MainNavigationRoutes` enum:

| Tab | Route | Icons |
|-----|-------|-------|
| Home | `home` | `Home` / `Outlined.Home` |
| Favorites | `favorites` | `Favorite` / `FavoriteBorder` |
| Systems | `systems/home` | `VideogameAsset` / `Outlined.VideogameAsset` |
| Search | `search` | `Search` / `Outlined.Search` |

### Full route map (`MainRoute` enum)

| Route enum | Path | Bottom nav |
|------------|------|-----------|
| `HOME` | `home` | ✅ |
| `FAVORITES` | `favorites` | ✅ |
| `SEARCH` | `search` | ✅ |
| `SYSTEMS` | `systems/home` | ✅ |
| `SYSTEM_GAMES` | `systems/{metaSystemId}` | ✅ (parent=SYSTEMS) |
| `SETTINGS` | `settings/home` | ❌ |
| `SETTINGS_ADVANCED` | `settings/advanced` | ❌ |
| `SETTINGS_BIOS` | `settings/bios` | ❌ |
| `SETTINGS_CORES_SELECTION` | `settings/cores` | ❌ |
| `SETTINGS_INPUT_DEVICES` | `settings/inputdevices` | ❌ |
| `SETTINGS_SAVE_SYNC` | `settings/savesync` | ❌ |

---

## 11. Home Screen

**ViewModel**: `HomeViewModel`  
**UI state** (`UIState` data class):

| Field | Purpose |
|-------|---------|
| `favoritesGames` | List of favorite games (carousel) |
| `recentGames` | Last-played games (carousel, max 10) |
| `discoveryGames` | Discovery/random games section |
| `indexInProgress` | True while library scan running |
| `showNoNotificationPermissionCard` | Permission prompt card |
| `showNoMicrophonePermissionCard` | Permission prompt card |
| `showNoGamesCard` | "No games found" card |
| `showDesmumeDeprecatedCard` | DesMuME deprecation warning |
| `showDownloadPromptDialog` | Auto-prompt for ROM download |

**Key flows**:
- `getDownloadRomsState(): Flow<DownloadRomsState>` — combines `RomsDownloadManager.state` + `noGamesFlow` + `indexingFlow`. Overrides `Done→Idle` when ROMs are missing and not indexing (handles directory deletion case).
- `wifiStatusFlow()` — monitors WiFi availability via `ConnectivityManager`
- `mobileSwitchEvent: Flow<String>` — emits mobile network label (e.g. "4G") when WiFi is lost during active download

---

## 12. Emulation Cores

**Version**: `1.17.0`  
**Source**: `https://github.com/Swordfish90/LemuroidCores/releases/download/1.17.0/<abi>/<libname>.so`  
**Storage path on device**: `files/cores/1.17.0/<libname>.so`

**CoreUpdaterImpl** (free flavor, `lemuroid-app-ext-free`):
- Downloads from GitHub releases
- Validates ELF ABI compatibility (`AbiUtils.isElfCompatible`) before installing
- Deletes outdated cores from previous version directories

**17 cores**:

| Core lib name | Systems |
|---------------|---------|
| `libfbneo_libretro_android.so` | FBNeo, MAME2003Plus |
| `libcitra_libretro_android.so` | Nintendo 3DS |
| `libgambatte_libretro_android.so` | GB, GBC |
| `libmgba_libretro_android.so` | GBA |
| `libfceumm_libretro_android.so` | NES |
| `libgenesis_plus_gx_libretro_android.so` | Genesis, SegaCD, SMS, GG |
| `libhandy_libretro_android.so` | Lynx |
| `libmednafen_pce_fast_libretro_android.so` | PC Engine |
| `libmednafen_ngp_libretro_android.so` | NGP, WSC |
| `libmednafen_wswan_libretro_android.so` | WS |
| `libmednafen_vb_libretro_android.so` | Virtual Boy |
| `libmelonds_libretro_android.so` | NDS |
| `libmupen64plus_next_gles3_libretro_android.so` | N64 |
| `libpcsx_rearmed_libretro_android.so` | PSX |
| `libsnes9x_libretro_android.so` | SNES |
| `libstella_libretro_android.so` | Atari 2600 |
| `libprosystem_libretro_android.so` | Atari 7800 |

---

## 13. Game Activity

**Base class**: `BaseGameActivity` (extends `ImmersiveActivity`)  
**Mobile impl**: `GameActivity`  
**TV impl**: `TVGameActivity`  
**Emulation widget**: `GLRetroView` (from `retrograde-game-library`)

**Key injected dependencies**:
- `SettingsManager` — SharedPreferences + DataStore for per-game settings
- `StatesManager` / `StatesPreviewManager` — save states
- `SavesManager` — legacy SRAM saves
- `CoreVariablesManager` — libretro core variables
- `InputDeviceManager` / `RumbleManager` — controller input
- `GameLoader` — loads ROM + core + saves into GLRetroView
- `ControllerConfigsManager` — per-system controller bindings

---

## 14. Build Flavors

```
Dimension: opensource → free | play
Dimension: cores      → bundle | dynamic
```

**Common dev build**: `assembleFreeDynamicDebug`

| Variant | Description |
|---------|-------------|
| `free` | Open-source, no Google Play services |
| `play` | Play Store edition with Google Drive save sync |
| `bundle` | Cores bundled in APK |
| `dynamic` | Cores downloaded at runtime |

---

## 15. Known Design Decisions & Constraints

1. **0-byte files are intentional** — catalog placeholders per `especificacao_versao2.md` decision #7. FBNeo/MAME error "ROM is missing" when launching a placeholder is expected and should be intercepted by the `isGamePlaceholder` check before reaching the emulator.

2. **`isGamePlaceholder` check** (in `MainActivity`):
   ```kotlin
   val isGamePlaceholder = { game: Game ->
       val uri = Uri.parse(game.fileUri)
       uri.scheme == "file" && uri.path?.let { File(it).length() == 0L } == true
   }
   ```
   If this check is not applied on a code path (e.g. TV activity, shortcut launch), the emulator will receive a 0-byte file and emit ROM-missing errors.

3. **Long-press "Play" bypasses `isGamePlaceholder`** — `MainGameContextActions` calls `onGamePlay` directly. If a placeholder appears in the context menu, pressing Play will go straight to the emulator. This is a known gap.

4. **`downloaded_roms` table is the source of truth** for "has real content". Do not rely on `File.length()` alone in any new code — use `downloadedRomDao.isDownloaded(fileName)` for DB operations and `File.length() == 0L` only for UI-side routing.

5. **Library re-scan after download** — `LibraryIndexScheduler.scheduleLibrarySync()` must be called after any ROM file is created/replaced. Both `StreamingRomsManager` and `RomOnDemandManager` already do this.

6. **HuggingFace rate limits** — `StreamingRomsManager` uses `limit=10000` to minimize API pages. Large catalogs may require multiple pages; the `Link` header in `RomsDownloadManager` handles pagination.

7. **pythonanywhere endpoint** — `emuladores.pythonanywhere.com/find_by_file` is the single lookup service for on-demand URLs. If it returns empty or 404, `RomOnDemandManager` emits `DownloadResult.NotFound`.

---

## 16. Key File Index (quick lookup)

| File | Location |
|------|----------|
| `SystemID.kt` | `retrograde-app-shared/.../library/SystemID.kt` |
| `Game.kt` | `retrograde-app-shared/.../db/entity/Game.kt` |
| `DownloadedRom.kt` | `retrograde-app-shared/.../db/entity/DownloadedRom.kt` |
| `DataFile.kt` | `retrograde-app-shared/.../db/entity/DataFile.kt` |
| `RetrogradeDatabase.kt` | `retrograde-app-shared/.../db/RetrogradeDatabase.kt` |
| `Migrations.kt` | `retrograde-app-shared/.../db/dao/Migrations.kt` |
| `DownloadedRomDao.kt` | `retrograde-app-shared/.../db/dao/DownloadedRomDao.kt` |
| `GameLoader.kt` | `retrograde-app-shared/.../game/GameLoader.kt` |
| `DocumentFileParser.kt` | `retrograde-app-shared/.../storage/local/DocumentFileParser.kt` |
| `LemuroidLibrary.kt` | `retrograde-app-shared/.../library/LemuroidLibrary.kt` |
| `StreamingRomsManager.kt` | `lemuroid-app/.../shared/roms/StreamingRomsManager.kt` |
| `StreamingRomsWork.kt` | `lemuroid-app/.../shared/roms/StreamingRomsWork.kt` |
| `RomOnDemandManager.kt` | `lemuroid-app/.../shared/roms/RomOnDemandManager.kt` |
| `RomsDownloadManager.kt` | `lemuroid-app/.../shared/roms/RomsDownloadManager.kt` |
| `RomSystemMapper.kt` | `lemuroid-app/.../roms/RomSystemMapper.kt` |
| `MainActivity.kt` | `lemuroid-app/.../mobile/feature/main/MainActivity.kt` |
| `MainNavigationRoutes.kt` | `lemuroid-app/.../mobile/feature/main/MainNavigationRoutes.kt` |
| `MainGameContextActions.kt` | `lemuroid-app/.../mobile/feature/main/MainGameContextActions.kt` |
| `RomDownloadDialog.kt` | `lemuroid-app/.../mobile/feature/main/RomDownloadDialog.kt` |
| `GameLaunchTaskHandler.kt` | `lemuroid-app/.../mobile/feature/main/GameLaunchTaskHandler.kt` |
| `HomeViewModel.kt` | `lemuroid-app/.../mobile/feature/home/HomeViewModel.kt` |
| `HomeScreen.kt` | `lemuroid-app/.../mobile/feature/home/HomeScreen.kt` |
| `GamesScreen.kt` | `lemuroid-app/.../mobile/feature/games/GamesScreen.kt` |
| `GameInteractor.kt` | `lemuroid-app/.../mobile/shared/GameInteractor.kt` |
| `BaseGameActivity.kt` | `lemuroid-app/.../shared/game/BaseGameActivity.kt` |
| `CoreUpdaterImpl.kt` (free) | `lemuroid-app-ext-free/.../core/CoreUpdaterImpl.kt` |
| `SettingsManager.kt` | `lemuroid-app/.../mobile/feature/settings/SettingsManager.kt` |
| `ControllerConfigsManager.kt` | `lemuroid-app/.../shared/settings/ControllerConfigsManager.kt` |
| `LibraryIndexScheduler.kt` | `lemuroid-app/.../shared/library/LibraryIndexScheduler.kt` |
| `LibretroDBMetadataProvider.kt` | `lemuroid-metadata-libretro-db/.../libretrodb/LibretroDBMetadataProvider.kt` |
| `BiosManager.kt` | `retrograde-app-shared/.../bios/BiosManager.kt` |
| `EmbeddedBiosInstaller.kt` | `lemuroid-app/.../shared/bios/EmbeddedBiosInstaller.kt` |
| `GameSystem.kt` | `retrograde-app-shared/.../library/GameSystem.kt` |
| `MetaSystemID.kt` | `retrograde-app-shared/.../library/MetaSystemID.kt` |
| `ShaderChooser.kt` | `lemuroid-app/.../shared/game/ShaderChooser.kt` |
---

## 17. Embedded BIOS Files

BIOS files embedded directly in the APK (`lemuroid-app/src/main/assets/bios/`) and installed automatically by `EmbeddedBiosInstaller` on first run.

| File | MD5 | System | Notes |
|------|-----|--------|-------|
| `BIOSGBA.ROM` | — | GBA | Game Boy Advance BIOS |
| `MSX.ROM` | `364A1A579FE81E4A940A999043A76E35` | MSX, MSX2 | MSX BIOS |
| `MSX2.ROM` | `EC3A01C91F24FBFF838D67D460C751C7` | MSX2 | MSX2 BIOS |
| `MSXDOS2.ROM` | `6418D091CD6907BBCF940324339E43BB` | MSX | MSX-DOS2 |
| `neogeo.zip` | `DFFB72F116D36D025068B23970A4F6DF` (CRC32 `362E948D`) | NEOGEO (via FBNEO core) | Neo Geo system BIOS |

`EmbeddedBiosInstaller` is called at app startup. It copies each file from assets into the app's system BIOS directory if not already present.

---

## 18. SNK Neo Geo System

**Added**: 2026-04-13

The NEOGEO system is a dedicated sub-system carved out of the generic FBNeo arcade collection. It uses the FBNeo core but exposes a separate system card with the SNK Neo Geo logo.

### Key files changed

| File | Change |
|------|--------|
| `SystemID.kt` | Added `NEOGEO("neogeo")` after `MSX2` |
| `MetaSystemID.kt` | Added `NEOGEO(R.string.game_system_title_neogeo, R.drawable.game_system_neogeo, listOf(SystemID.NEOGEO))` + mapping in `fromSystemID()` |
| `GameSystem.kt` | Added `GameSystem(SystemID.NEOGEO, ...)` using `CoreID.FBNEO`, `requiredBIOSFiles = ["neogeo.zip"]`, `scanByPathAndFilename = true`, extension `.zip` |
| `strings-game-system.xml` | Added `game_system_title_neogeo = "SNK Neo Geo"`, `game_system_abbr_neogeo = "NeoGeo"` |
| `ShaderChooser.kt` | `SystemID.NEOGEO → CRT` shader, `NEOGEO → upscale32Bits` |
| `RomSystemMapper.kt` | `"neogeo" to "neogeo"` |
| `BiosManager.kt` | `Bios("neogeo.zip", MD5, "Neo Geo BIOS", SystemID.FBNEO, isEmbedded=true)` |
| `EmbeddedBiosInstaller.kt` | `"neogeo.zip"` added to `BIOS_FILES` |
| `game_system_neogeo.png` | Drawable in `retrograde-app-shared/.../res/drawable/` |
| `libretro-db.sqlite` | 103 rows changed `system='fbneo'` → `'neogeo'` |
| `LibretroDatabase.kt` | version bumped 8 → 9 (force re-copy assets DB on upgrade) |

### LibretroDBMetadataProvider scoring (for `arcade/` folder)

Neo Geo ROMs reside in the `arcade/` HuggingFace folder alongside other MAME ROMs. `findByPathAndFilename` uses a scoring system:

| Score | Condition |
|-------|-----------|
| 3 | `dbname == "neogeo"` — libretro-db explicitly tags this ROM as Neo Geo |
| 2 | Exact path segment match (e.g. file is in a `neogeo/` folder) |
| 1 | `arcade/` folder + `mame2003plus` system |
| 0 | Alias-only match (fbneo, etc.) |

`FOLDER_ALIASES` entries added: `"arcade" to "neogeo"`, `"fbneo" to "neogeo"` — so `parentContainsSystem()` allows neogeo candidates from within those folders.

### 103 Neo Geo ROM filenames

Covered by Migration 12→13 (`Migrations.kt`). Includes: Metal Slug series, King of Fighters series (kof94–kof2003), Samurai Shodown series (samsho–samsho4), Fatal Fury series, Art of Fighting series, Garou: Mark of the Wolves, and more. Full list in `Migrations.VERSION_12_13.NEOGEO_ROMS`.

---

## 19. MSX Systems

**Added**: prior sessions (before 2026-04-09)

| SystemID | DB name | Core |
|----------|---------|------|
| `MSX` | `msx` | `libfmsx_libretro_android.so` (fMSX) |
| `MSX2` | `msx2` | `libfmsx_libretro_android.so` (fMSX) |

**BIOS files** (all embedded): `MSX.ROM`, `MSX2.ROM`, `MSX2P.ROM`, `MSXDOS2.ROM`.  
**RomSystemMapper**: both `msx` and `msx2` map to endpoint system `"msx"`.  
**HuggingFace folder**: `roms/msx/`
