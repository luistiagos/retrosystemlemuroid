# Lemuroid — Project Specification

> **Spec-Driven Context Document** — update this file whenever you change architecture, add features, or modify data flows.  
> Last updated: 2026-04-19

---

## 1. Project Overview

**Lemuroid** is an Android multi-system emulator that integrates a HuggingFace-hosted ROM catalog (`luisluis123/lemusets`). ROMs are distributed as **0-byte placeholder files** (catalog entries). The actual ROM data is downloaded on-demand when the user taps a game.

- **Language**: Kotlin 100%
- **UI**: Jetpack Compose (phone/tablet) + Leanback (TV)
- **Min SDK**: 21
- **App package (debug)**: `com.swordfish.lemuroid.debug`
- **App package (release)**: `com.swordfish.lemuroid`
- **Build system**: Gradle Kotlin DSL (`build.gradle.kts` throughout)
- **Build command (dev)**: `./gradlew installFreeBundleDebug`

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

## 3. Supported Systems (43)

Defined in `retrograde-app-shared/.../library/SystemID.kt`:

### 3.1 Console & Handheld Systems (29)

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
| `ATARI2600` | `atari2600` | Atari 2600 |
| `ATARI7800` | `atari7800` | Atari 7800 |
| `ATARI5200` | `atari5200` | Atari 5200 |
| `PSX` | `psx` | PlayStation 1 |
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
| `NEOGEO` | `neogeo` | SNK Neo Geo |
| `FBNEO` | `fbneo` | FinalBurn Neo (arcade genérico) |
| `MAME2003PLUS` | `mame2003plus` | MAME 2003-Plus (arcade) |

### 3.2 Arcade Board Systems (14)

All use `CoreID.FBNEO` (`libfbneo_libretro_android.so`) and share the same endpoint `fbneo`.

| SystemID enum | DB name (`id`) | Board / Manufacturer |
|---------------|---------------|---------------------|
| `CPS1` | `cps1` | Capcom CPS-1 |
| `CPS2` | `cps2` | Capcom CPS-2 |
| `CPS3` | `cps3` | Capcom CPS-3 |
| `DATAEAST` | `dataeast` | Data East |
| `GALAXIAN` | `galaxian` | Namco Galaxian |
| `TOAPLAN` | `toaplan` | Toaplan |
| `TAITO` | `taito` | Taito |
| `PSIKYO` | `psikyo` | Psikyo |
| `PGM` | `pgm` | IGS PGM |
| `KANEKO` | `kaneko` | Kaneko |
| `CAVE` | `cave` | Cave |
| `TECHNOS` | `technos` | Technos Japan |
| `SETA` | `seta` | Seta |

---

## 4. Database Schema

**Class**: `RetrogradeDatabase` (`retrograde-app-shared/.../db/RetrogradeDatabase.kt`)  
**DB name**: `retrograde`  
**Room version**: `19`

### Migration history

| Migration | Change |
|-----------|--------|
| 8 → 9 | Add `datafiles` table |
| 9 → 10 | Add `downloaded_roms` table |
| 10 → 11 | Add index on `games.fileName` |
| 11 → 12 | `games SET systemId='neogeo' WHERE systemId='fbneo'` for 103 Neo Geo ROMs (superseded by 12→13) |
| 12 → 13 | `games SET systemId='neogeo' WHERE systemId='mame2003plus'` + `fbneo` for 103 Neo Geo filenames (ROMs were in `arcade/` folder, indexed as `mame2003plus`) |
| 13 → 14 | *(reserved — skipped in history)* |
| 14 → 15 | *(reserved — skipped in history)* |
| 15 → 16 | *(reserved — skipped in history)* |
| 16 → 17 | *(reserved — skipped in history)* |
| 17 → 18 | *(reserved — skipped in history)* |
| 18 → 19 | `CREATE INDEX index_games_isFavorite_lastPlayedAt ON games (isFavorite, lastPlayedAt)` — composite index for `selectFirstUnfavoriteRecents` query |

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

**Indices on `games`**:
| Index name | Columns | Unique |
|------------|---------|--------|
| `index_games_id` | `id` | ✅ |
| `index_games_fileUri` | `fileUri` | ✅ |
| `index_games_fileName` | `fileName` | ❌ |
| `index_games_title` | `title` | ❌ |
| `index_games_systemId` | `systemId` | ❌ |
| `index_games_lastIndexedAt` | `lastIndexedAt` | ❌ |
| `index_games_lastPlayedAt` | `lastPlayedAt` | ❌ |
| `index_games_isFavorite` | `isFavorite` | ❌ |
| `index_games_isFavorite_lastPlayedAt` | `isFavorite, lastPlayedAt` | ❌ | ← added v18→19, speeds up `selectFirstUnfavoriteRecents`

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

**URL resolution logic**:
1. Call `find_by_file` endpoint → returns plain-text URL for the correct repository (source of truth).
2. If endpoint returns a URL → use it directly. The endpoint may point to any repository (e.g. PCE ROMs are in a different repo than `luisluis123/lemusets`).
3. If endpoint returns `null` (game not in DB, HTTP 404 or empty body) → fall back to direct HuggingFace URL via `buildHuggingFaceUrl(game)` using `luisluis123/lemusets/roms/<system>/<file>`.

> ⚠️ Do NOT add a HEAD-check validation step between steps 1 and 2. The endpoint is the source of truth — discarding its URL causes 404s for systems hosted in other repos (e.g. PCE).

**Key behaviors**:
- 5 retry attempts with back-off
- `activeCall: Call?` field — cancelled via `activeCall?.cancel()` on scope cancellation
- `ensureActive()` in write loop
- On cancel: restores 0-byte placeholder so the file remains a valid catalog entry
- On success: `downloadedRomDao.insert(DownloadedRom(...))` + `LibraryIndexScheduler.scheduleLibrarySync()`
- `deleteRom(game)`: truncates file to 0 bytes + `downloadedRomDao.delete(fileName)`
- `isPaused: StateFlow<Boolean>` — exposed so `RomDownloadDialog` can show Pause/Resume button state
- `HUGGINGFACE_BASE` = `https://huggingface.co/datasets/luisluis123/lemusets/resolve/main/roms`

### 5.7 RomSystemMapper

**File**: `lemuroid-app/.../roms/RomSystemMapper.kt`

Maps Lemuroid `systemId` (or HF folder alias) → pythonanywhere endpoint system name. Full mapping:

```
// Atari
atari2600, a26       → atari2600
atari7800, a78       → atari7800
atari5200, a52       → atari5200
lynx                 → lynx

// Nintendo
nes                  → nes
snes                 → snes
gb                   → gb
gbc                  → gbc
gba                  → gba
n64                  → n64
nds                  → nds
3ds                  → 3ds

// Sega
md, megadrive        → megadrive
scd, megacd          → megacd
sms                  → mastersystem
gg                   → gamegear

// Sony
psx                  → psx
psp                  → psp

// NEC
pce                  → pcengine

// SNK
ngp                  → ngp
ngc                  → ngpc        ← (not "neogeocd")

// Bandai
ws                   → wswan
wsc                  → wswanc

// Arcade boards (all → fbneo endpoint)
fbneo                → fbneo
neogeo               → neogeo
cps1, cps2, cps3     → fbneo
dataeast, galaxian   → fbneo
toaplan, taito       → fbneo
psikyo, pgm          → fbneo
kaneko, cave         → fbneo
technos, seta        → fbneo
mame2003plus, arcade → mame2003plus

// Microsoft / NEC
dos                  → dos
msx                  → msx
msx2                 → msx2
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

**Scheduler**: `LibraryIndexScheduler` — two entry points:
- `scheduleLibrarySync()` — automated triggers (streaming download, on-demand download, post-extraction). **Does NOT show progress bar in UI.**
- `scheduleManualLibrarySync()` — user-triggered triggers (folder change via `StorageFrameworkPickerLauncher`, SD/USB mount via `MediaMountedReceiver`). **Shows progress bar in Home screen.**

Both use separate WorkManager `UNIQUE_WORK_ID`s (`LibraryIndexWork` vs `LibraryIndexWork_manual`) but enqueue the same `LibraryIndexWork` worker.

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
| `SETTINGS_TRANSFER` | `settings/transfer` | ❌ |
| `SETTINGS_TRANSFER_EXPORT` | `settings/transfer/export` | ❌ (parent=TRANSFER) |
| `SETTINGS_TRANSFER_IMPORT` | `settings/transfer/import` | ❌ (parent=TRANSFER) |
| `SETTINGS_ROMSET` | `settings/romset` | ❌ |
| `SETTINGS_ROMSET_EXPORT` | `settings/romset/export` | ❌ (parent=ROMSET) |
| `SETTINGS_ROMSET_IMPORT` | `settings/romset/import` | ❌ (parent=ROMSET) |

---

## 11. Home Screen

**ViewModel**: `HomeViewModel`  
**UI state** (`UIState` data class):

| Field | Purpose |
|-------|---------|
| `favoritesGames` | List of favorite games (carousel) |
| `recentGames` | Last-played games (carousel, max 10) |
| `discoveryGames` | Discovery/random games section |
| `indexInProgress` | True while any library operation running (scan or core update) |
| `isInitialLoadComplete` | False until first DB result arrives — drives initial loading spinner |
| `userScanInProgress` | True while a **user-triggered** manual scan is running — drives `LinearProgressIndicator` in Home |
| `showNoNotificationPermissionCard` | Permission prompt card |
| `showNoMicrophonePermissionCard` | Permission prompt card |
| `showNoGamesCard` | "No games found" card |
| `showDesmumeDeprecatedCard` | DesMuME deprecation warning |
| `showDownloadPromptDialog` | Auto-prompt for ROM download |

**Loading/progress UI in HomeScreen**:
1. **Startup spinner** — `CircularProgressIndicator` shown centered while `isInitialLoadComplete == false`; disappears as soon as the first `UIState` arrives from the DB (typically <300ms with AOT).
2. **Scan progress bar** — `LinearProgressIndicator` (indeterminate) shown at top of content while `userScanInProgress == true`. Only visible for user-triggered scans (folder change, SD card mount). Does NOT appear during automated catalog indexing.

**Key flows**:
- `getDownloadRomsState(): Flow<DownloadRomsState>` — combines `RomsDownloadManager.state` + `noGamesFlow` + `indexingFlow`. Overrides `Done→Idle` when ROMs are missing and not indexing (handles directory deletion case).
- `wifiStatusFlow()` — monitors WiFi availability via `ConnectivityManager`
- `mobileSwitchEvent: Flow<String>` — emits mobile network label (e.g. "4G") when WiFi is lost during active download

---

## 11.1 System Games Screen (`GamesScreen.kt`)

**File**: `lemuroid-app/.../mobile/feature/games/GamesScreen.kt`  
**Route**: `SYSTEM_GAMES` (`systems/{metaSystemId}`)  
**Purpose**: Exibe a lista paginada de jogos de um sistema específico.

### Ordenação da lista

Os jogos são ordenados pelo `GameDao` com `ORDER BY (downloaded_roms.fileName IS NOT NULL) DESC`, garantindo que ROMs baixados apareçam **no topo** da lista.

### Scroll automático ao retornar do jogo

Ao sair de um jogo (a `BaseGameActivity` termina e o app retoma o `MainActivity`), o `GamesScreen` volta ao topo automaticamente.

**Mecanismo**:
- `listState = rememberLazyListState()` — estado do `LazyColumn`
- `coroutineScope = rememberCoroutineScope()` — escopo para chamadas suspend
- `DisposableEffect(lifecycleOwner)` com um `LifecycleEventObserver` que observa `Lifecycle.Event.ON_RESUME`
- No `ON_RESUME`: `coroutineScope.launch { listState.scrollToItem(0) }`
- O observer é removido no `onDispose` do `DisposableEffect`

**Regra Compose**: todos os `remember*` são declarados **antes** do `if (games.itemCount == 0) { return }` para não violar a regra de que `remember` não pode ocorrer após return condicional.

**Por quê importa**: após baixar um jogo via on-demand e jogar, o usuário retorna ao `GamesScreen` já rolado para o topo, onde o jogo baixado aparece em destaque (posição 1 na lista).

---

## 12. Emulation Cores

**Version**: `1.17.0`  
**Source**: `https://github.com/Swordfish90/LemuroidCores/releases/download/1.17.0/<abi>/<libname>.so`  
**Storage path on device**: `files/cores/1.17.0/<libname>.so`

**CoreUpdaterImpl** (free flavor, `lemuroid-app-ext-free`):
- Downloads from GitHub releases
- Validates ELF ABI compatibility (`AbiUtils.isElfCompatible`) before installing
- Deletes outdated cores from previous version directories

**22 cores** (defined in `CoreID.kt`):

| CoreID | Core lib name | Systems |
|--------|---------------|---------|
| `STELLA` | `libstella_libretro_android.so` | Atari 2600 |
| `PROSYSTEM` | `libprosystem_libretro_android.so` | Atari 7800 |
| `A5200` | `liba5200_libretro_android.so` | Atari 5200 |
| `FCEUMM` | `libfceumm_libretro_android.so` | NES |
| `SNES9X` | `libsnes9x_libretro_android.so` | SNES |
| `GAMBATTE` | `libgambatte_libretro_android.so` | GB, GBC |
| `MGBA` | `libmgba_libretro_android.so` | GBA |
| `MUPEN64_PLUS_NEXT` | `libmupen64plus_next_gles3_libretro_android.so` | N64 |
| `GENESIS_PLUS_GX` | `libgenesis_plus_gx_libretro_android.so` | Genesis, SegaCD, SMS, GG |
| `HANDY` | `libhandy_libretro_android.so` | Lynx |
| `MEDNAFEN_PCE_FAST` | `libmednafen_pce_fast_libretro_android.so` | PC Engine |
| `MEDNAFEN_NGP` | `libmednafen_ngp_libretro_android.so` | NGP, NGC |
| `MEDNAFEN_WSWAN` | `libmednafen_wswan_libretro_android.so` | WS, WSC |
| `PCSX_REARMED` | `libpcsx_rearmed_libretro_android.so` | PSX |
| `PPSSPP` | `libppsspp_libretro_android.so` | PSP |
| `DESMUME` | `libdesmume_libretro_android.so` | NDS (deprecated) |
| `MELONDS` | `libmelonds_libretro_android.so` | NDS |
| `CITRA` | `libcitra_libretro_android.so` | Nintendo 3DS |
| `FBNEO` | `libfbneo_libretro_android.so` | FBNeo, Neo Geo, CPS1/2/3, e todos os boards arcade |
| `MAME2003PLUS` | `libmame2003_plus_libretro_android.so` | MAME2003Plus |
| `DOSBOX_PURE` | `libdosbox_pure_libretro_android.so` | DOS |
| `FMSX` | `libfmsx_libretro_android.so` | MSX, MSX2 |

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

7. **pythonanywhere endpoint** — `emuladores.pythonanywhere.com/find_by_file` is the single lookup service for on-demand URLs. Its response URL is used directly without any HEAD-check validation — the endpoint is the source of truth and may return URLs from different repositories depending on the system (e.g. PCE ROMs are not in `luisluis123/lemusets`). Only when the endpoint returns empty or 404 does `RomOnDemandManager` fall back to `luisluis123/lemusets` and emit `DownloadResult.NotFound` if the fallback also fails.

8. **SSL on old Android (< 7.1)** — TV Boxes and old devices lack ISRG Root X1 (Let's Encrypt CA) in their system trust store. All 8 `OkHttpClient` instances call `.applyConscryptTls()` which applies a `trustAll` X509TrustManager via two independent layers (system TLS first, then Conscrypt). This is unconditional across all build types (debug and release). The permanent fix (bundling ISRG Root X1 cert) is deferred to a future release.

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
| `CoreID.kt` | `retrograde-app-shared/.../library/CoreID.kt` |
| `HeavySystemFilter.kt` | `retrograde-app-shared/.../library/HeavySystemFilter.kt` |
| `TransferViewModel.kt` | `lemuroid-app/.../settings/transfer/TransferViewModel.kt` |
| `GameExportManager.kt` | `retrograde-app-shared/.../transfer/GameExportManager.kt` |
| `GameImportManager.kt` | `retrograde-app-shared/.../transfer/GameImportManager.kt` |
| `TransferManifest.kt` | `retrograde-app-shared/.../transfer/TransferManifest.kt` |
| `RomsetViewModel.kt` | `lemuroid-app/.../settings/romset/RomsetViewModel.kt` |
| `RomsetExportManager.kt` | `retrograde-app-shared/.../romset/RomsetExportManager.kt` |
| `RomsetImportManager.kt` | `retrograde-app-shared/.../romset/RomsetImportManager.kt` |
| `catalog_manifest.txt` | `lemuroid-app/src/main/assets/catalog_manifest.txt` |
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

---

## 20. Transfer Games (Transferir Jogos)

**Added**: 2026 sessions  
**Access**: Settings → Transfer Games (⚙ → Transferir jogos)  
**Routes**: `SETTINGS_TRANSFER` → `SETTINGS_TRANSFER_EXPORT` / `SETTINGS_TRANSFER_IMPORT`

### Purpose

Export/import selected games (ROMs + saves + states + optional APK) via SD card or USB drive for offline device-to-device transfer.

### Archive Format

Directory structure on external media:
```
lemuroid-export/
├── manifest.json           ← metadata (version, game list, APK flag)
├── roms/                   ← ROM files
├── saves/                  ← SRAM saves (.srm)
├── states/{coreName}/      ← Emulator save states
├── state-previews/{coreName}/ ← State preview images (.png)
└── app/                    ← Optional: lemuroid-v{version}.apk
```

### Manifest (`manifest.json`)

**Class**: `TransferManifest` (`retrograde-app-shared/.../transfer/TransferManifest.kt`)

```kotlin
@Serializable
data class TransferManifest(
    val version: Int = 1,
    val exportDate: Long,
    val appVersion: String,
    val appVersionCode: Int,
    val includesApk: Boolean = false,
    val apkFileName: String? = null,
    val games: List<TransferGameEntry>,
)

@Serializable
data class TransferGameEntry(
    val fileName: String,
    val title: String,
    val systemId: String,
    val developer: String? = null,
    val coverFrontUrl: String? = null,
    val isFavorite: Boolean = false,
    val dataFiles: List<TransferDataFileEntry> = emptyList(),
)
```

### Export Flow

1. User selects games (checkbox list) + toggle "Include APK"
2. Taps "Choose folder and export" → SAF folder picker
3. `GameExportManager.export()`:
   - Creates `lemuroid-export/` dir structure
   - Copies ROMs, saves, states, state previews
   - Optionally copies APK (`lemuroid-v{version}.apk`)
   - Writes `manifest.json`
4. Progress phases: `COPYING_APK → COPYING_ROMS → COPYING_SAVES → COPYING_STATES → WRITING_MANIFEST`

### Import Flow

1. On screen load, `GameImportManager.findManifestOnVolumes()` searches for `lemuroid-export/manifest.json` on external volumes
2. Shows manifest info (game count, source version)
3. User taps "Import all"
4. `GameImportManager.import()`: copies ROMs, saves, states → triggers library re-scan

### Key Classes

| Class | File | Purpose |
|-------|------|---------|
| `TransferViewModel` | `lemuroid-app/.../settings/transfer/TransferViewModel.kt` | UI state + orchestration |
| `GameExportManager` | `retrograde-app-shared/.../transfer/GameExportManager.kt` | Export logic |
| `GameImportManager` | `retrograde-app-shared/.../transfer/GameImportManager.kt` | Import logic + volume discovery |
| `TransferManifest` | `retrograde-app-shared/.../transfer/TransferManifest.kt` | Manifest data class |
| `TransferProgress` | `retrograde-app-shared/.../transfer/TransferProgress.kt` | Progress sealed class |
| `TransferSettingsScreen` | `lemuroid-app/.../settings/transfer/TransferSettingsScreen.kt` | Menu (Export/Import links) |
| `TransferExportScreen` | `lemuroid-app/.../settings/transfer/TransferExportScreen.kt` | Game selection + export progress |
| `TransferImportScreen` | `lemuroid-app/.../settings/transfer/TransferImportScreen.kt` | Import detection + progress |

### Progress States

```kotlin
sealed class TransferProgress {
    object Idle
    data class InProgress(currentIndex, totalCount, currentGameName, phase: Phase)
    data class Completed(gamesExported: Int)
    data class Error(message: String)
}
```

---

## 21. ROM Set (Romset)

**Added**: 2026 sessions  
**Access**: Settings → ROM Set (⚙ → ROM Set)  
**Routes**: `SETTINGS_ROMSET` → `SETTINGS_ROMSET_EXPORT` / `SETTINGS_ROMSET_IMPORT`

### Purpose

Bulk export/import **only ROM files** as a single ZIP. No saves, states, or metadata — just ROMs organized by system folder. Useful for backup and migration.

### ZIP Format

```
romset-{appVersion}.zip
├── snes/
│   ├── game1.sfc
│   └── game2.sfc
├── nes/
│   ├── game1.nes
│   └── game2.nes
└── ... (preserves system folder structure from roms directory)
```

### Export Flow

1. UI shows estimated total size of all downloaded ROMs
2. User taps "Choose folder and export" → SAF folder picker
3. `RomsetExportManager.export()`:
   - Gets downloaded ROM filenames from `downloadedRomDao` (authoritative)
   - Falls back to filesystem walk for locally-added ROMs
   - Creates single `romset-{version}.zip` preserving folder structure
4. Progress phase: `COMPRESSING`

### Import Flow

1. `RomsetImportManager.findRomsetOnVolumes()` searches all volumes for `.zip` files at volume root
2. Shows list of discovered ZIPs (clickable)
3. User can also tap "Pick a ZIP file..." for manual SAF file picker
4. `RomsetImportManager.importFromFile()`:
   - Two passes: count entries, then extract
   - Skips files that already exist with content (dedup)
   - Triggers library re-scan
5. Progress phase: `EXTRACTING`

### Key Classes

| Class | File | Purpose |
|-------|------|---------|
| `RomsetViewModel` | `lemuroid-app/.../settings/romset/RomsetViewModel.kt` | UI state + orchestration |
| `RomsetExportManager` | `retrograde-app-shared/.../romset/RomsetExportManager.kt` | ZIP creation |
| `RomsetImportManager` | `retrograde-app-shared/.../romset/RomsetImportManager.kt` | ZIP extraction + volume discovery |
| `RomsetProgress` | `retrograde-app-shared/.../romset/RomsetProgress.kt` | Progress sealed class |
| `RomsetSettingsScreen` | `lemuroid-app/.../settings/romset/RomsetSettingsScreen.kt` | Menu (Export/Import links) |
| `RomsetExportScreen` | `lemuroid-app/.../settings/romset/RomsetExportScreen.kt` | Size estimate + export |
| `RomsetImportScreen` | `lemuroid-app/.../settings/romset/RomsetImportScreen.kt` | ZIP list + picker + import |

### Difference from Transfer Games

| Feature | Transfer Games | ROM Set |
|---------|---------------|---------|
| **Content** | ROMs + saves + states + APK | ROMs only |
| **Format** | Directory (`lemuroid-export/`) | Single ZIP (`romset-{v}.zip`) |
| **Selection** | Per-game checkboxes | All downloaded |
| **Metadata** | `manifest.json` with titles/covers | None |
| **Use case** | Device-to-device migration | Backup/restore ROM collection |

---

## 22. Catalog Manifest (catalog_manifest.txt)

**File**: `lemuroid-app/src/main/assets/catalog_manifest.txt`  
**Purpose**: Embedded list of all available ROM paths, allowing the app to display the full catalog without network access on first use.

### Format

Newline-delimited relative paths: `{systemFolder}/{romFilename}`:
```
3ds/Game Title (Region).3ds
snes/Super Mario World (USA).sfc
wsc/Digimon Tamers (Japan).zip
arcade/metalslug.zip
```

### Flow

1. **App startup** → `StreamingRomsManager.doStreamingDownload()`
2. **Load manifest** → `loadCatalogFromAssets()` reads `catalog_manifest.txt`
3. **Filter** → `HeavySystemFilter.excludedCatalogPrefixes()` removes heavy systems for weak devices
4. **Create placeholders** → `populateFromEmbeddedCatalog()` creates 0-byte files in `romsDir/{system}/{rom}`
5. **Index** → Every 500 files triggers `LibraryIndexScheduler.sync()`
6. **System detection** → `LibretroDBMetadataProvider.parentContainsSystem()` matches folder segment to `SystemID.dbname`
7. **DB populated** → `games` table gets records with `systemId` derived from folder name
8. **UI updates** → `GameDao.selectSystemsWithCount()` returns systems with count > 0

### System Folder → systemId Mapping

The folder name in the manifest path IS the `SystemID.dbname` (exact match by path segment):
```
wsc/game.zip     → systemId = "wsc"     → MetaSystemID.WSC
snes/game.sfc    → systemId = "snes"    → MetaSystemID.SNES
arcade/game.zip  → systemId = "mame2003plus" (via scoring heuristic)
```

### Catalog Version

`StreamingRomsManager` tracks catalog version in `PREF_CATALOG_VERSION`. Bumped when manifest is updated with new systems/entries, forcing re-population even if previous download was marked done.

---

## 23. Startup Performance

**Diagnosed and fixed**: 2026-04-17

### Root cause of 10-second black screen (fresh install)

On first cold start (fresh install, reboot), ART compiled **Room + SQLite infrastructure** just-in-time (JIT), which took **7.7 seconds** before any DB query returned results. Normal warm starts were unaffected (JIT cache already present).

### Fixes applied

#### 1. `baseline-prof.txt` — AOT compilation rules

**File**: `lemuroid-app/src/main/baseline-prof.txt`

Added Room + SQLite class patterns so ART compiles them ahead-of-time at install:
```
HSPLandroidx/room/**;->**(**)**
HSPLandroidx/sqlite/**;->**(**)**
HSPLandroid/database/sqlite/*;->**(**)**
HSPLandroid/database/*;->**(**)**
HSPLandroidx/room/RoomDatabase;->**(**)**
HSPLandroidx/room/RoomDatabase$Builder;->**(**)**
HSPLandroidx/room/InvalidationTracker;->**(**)**
```

#### 2. Composite DB index (migration 18 → 19)

**`selectFirstUnfavoriteRecents`** was doing a full table scan (`WHERE isFavorite=0 AND lastPlayedAt NOT NULL ORDER BY lastPlayedAt DESC`), taking **239ms** without JIT. Added composite index `(isFavorite, lastPlayedAt)` reducing it to **<30ms**.

#### 3. `installFreeBundleDebugAndCompile` Gradle task

**File**: `lemuroid-app/build.gradle.kts`

Custom task that installs the APK then immediately runs:
```
adb shell cmd package compile -m speed -f com.swordfish.lemuroid.debug
```
Forces AOT compilation for debug builds (equivalent to what release builds get at install time).

### Measured results (after fixes)

| Metric | Before (JIT cold) | After (AOT) |
|--------|-------------------|-------------|
| `am start -W` TotalTime | ~1400ms | **~620ms** |
| First DB query (Room) | **7706ms** | **29ms** |
| First `UIState` emitted (T4) | **7969ms** | **141ms** |
| Black screen duration | **~10 seconds** | **<0.3 seconds** |

### Stable timing breakdown (AOT, cold start)

| Phase | Duration | Description |
|-------|----------|-------------|
| Zygote fork → `Application.onCreate` | ~50ms | Process creation |
| Dagger/Hilt inject | ~115ms | DI graph setup |
| Compose + NavHost setup | ~315ms | First frame |
| Room queries + combine | ~140ms | DB IO + flow combine |
| Compose recomposition | ~25ms | Grid render |

### PendingOperationsMonitor — operation types

`PendingOperationsMonitor` tracks WorkManager jobs by unique ID:

| Operation enum | Work ID | `isPeriodic` | Tracked by |
|----------------|---------|-------------|------------|
| `LIBRARY_INDEX` | `LibraryIndexWork` | false | `anyLibraryOperationInProgress()`, `isDirectoryScanInProgress()` |
| `LIBRARY_INDEX_MANUAL` | `LibraryIndexWork_manual` | false | `isUserLibraryScanInProgress()`, `isDirectoryScanInProgress()` |
| `CORE_UPDATE` | `CoreUpdateWork` | false | `anyLibraryOperationInProgress()` |
| `SAVES_SYNC_PERIODIC` | periodic work ID | true | `anySaveOperationInProgress()` |
| `SAVES_SYNC_ONE_SHOT` | one-shot work ID | false | `anySaveOperationInProgress()` |

### `debounceAfterFirst` utility

**File**: `retrograde-util/.../coroutines/FlowUtils.kt`

```kotlin
fun <T> Flow<T>.debounceAfterFirst(timeoutMillis: Long): Flow<T>
```

Emits the **first value immediately** (no delay), then debounces subsequent values by `timeoutMillis`. Used in `HomeViewModel` and `TVHomeViewModel` to show content instantly while preventing rapid back-to-back UI recompositions.

Previously both ViewModels used plain `debounce(100ms)` which delayed even the first item by 100ms.

---

## 24. PS4 Controller Fix (TV Box — MXQ Pro)

**Diagnosed and fixed**: 2026-04-20

### Symptom

Sony DualShock 4 (PS4) controller worked correctly in menus but produced no response inside games on the MXQ Pro TV Box.

### Root Cause

The MXQ Pro TV Box exposes its IR remote as an input device named **`sunxi-ir-uinput`** with `sources = 0x301` (`SOURCE_KEYBOARD | SOURCE_DPAD | SOURCE_GAMEPAD`). Because it declares `SOURCE_GAMEPAD`, it passed Lemuroid's `isSupported()` filter and was registered as a gamepad.

Port mapping in `getGamePadsPortMapperObservable()` sorts devices by `controllerNumber` and assigns ports by index. With `sunxi-ir-uinput.controllerNumber = 0`, the IR remote always sorted first and occupied **port 0 (player 1)**. The PS4 controller (`controllerNumber = 1`) was pushed to **port 1 (player 2)**. The libretro core received all PS4 button events on port 1 while player 1 had no physical controller — the game did not respond.

```
sunxi-ir-uinput  controllerNumber=0 → index 0 → port 0  (player 1)
Sony PS4         controllerNumber=1 → index 1 → port 1  (player 2)
→ game only responds to player 1 → no input registered
```

Evidence captured from `INPUT_DIAG` diagnostic logs:
```
registered gamepad id=1  name=sunxi-ir-uinput  sources=769  controllerNumber=0
registered gamepad id=47 name=Sony Interactive Entertainment Wireless Controller controllerNumber=1
keysFlow deviceId=47 ... port=1 action=0   ← ALL PS4 events on port 1
```

### Fix

Two changes were made:

#### 1. Blacklist `sunxi-ir-uinput` — `InputDeviceManager.kt`

**File**: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/input/InputDeviceManager.kt`

Added `"sunxi-ir-uinput"` to `BLACKLISTED_DEVICES`:

```kotlin
private val BLACKLISTED_DEVICES =
    setOf(
        "virtual-search",
        "sunxi-ir-uinput",   // ← MXQ Pro IR remote falsely declares SOURCE_GAMEPAD
    )
```

With the IR remote excluded, the PS4 controller takes index 0 → port 0 → player 1, and the emulator responds normally.

#### 2. Priority-based gamepad sorting — `InputDeviceManager.kt`

Added a `gamepadPriority()` member extension function and changed `getAllGamePads()` to sort by priority descending, then `controllerNumber` as tiebreaker. This ensures real joysticks always occupy the lowest port slots regardless of TV box kernel ordering:

```kotlin
// Score 3: SOURCE_JOYSTICK + has motion ranges  → PS4, Xbox, GameSir, etc.
// Score 2: SOURCE_JOYSTICK only                 → rare, but still a real controller
// Score 1: has motion ranges (hat axes, etc.)   → D-pad-only gamepads
// Score 0: no joystick source, no axes          → TV remotes masquerading as gamepads
private fun InputDevice.gamepadPriority(): Int {
    var score = 0
    if ((sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) score += 2
    if (motionRanges.isNotEmpty()) score += 1
    return score
}
```

This works generically across all TV boxes — no dependency on device names.

#### 2. `dispatchKeyEvent` override — `BaseGameActivity.kt`

**File**: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt`

Added an override that intercepts `SOURCE_GAMEPAD` / `SOURCE_JOYSTICK` key events before Jetpack Compose navigation can consume them:

```kotlin
override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    val isGamepad = (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    if (isGamepad) {
        val handled = when (event.action) {
            KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
            KeyEvent.ACTION_UP -> onKeyUp(event.keyCode, event)
            else -> false
        }
        if (handled) return true
    }
    return super.dispatchKeyEvent(event)
}
```

### Files Modified

| File | Change |
|------|--------|
| `lemuroid-app/.../input/InputDeviceManager.kt` | Added `"sunxi-ir-uinput"` to `BLACKLISTED_DEVICES`; added `gamepadPriority()` member extension; changed sort order to `compareByDescending { gamepadPriority() }.thenBy { controllerNumber }` |
| `lemuroid-app/.../shared/game/BaseGameActivity.kt` | Added `dispatchKeyEvent` override for gamepad events |

### Notes

- `BLACKLISTED_DEVICES` is a last-resort mechanism for devices that wrongly declare gamepad sources. The comment in the source code explains this intent.
- The `dispatchKeyEvent` fix is a defensive measure against Compose consuming gamepad events; the blacklist fix is the primary solution.
- This issue is specific to Android TV Boxes that expose IR remotes as input devices with `SOURCE_GAMEPAD`. Other devices are unaffected.

---

## 25. Port Assignment (Atribuição de Portas)

**Added**: 2026-04-21

### Purpose

Allows the user to manually define which physical controller occupies each player slot (port 0 = Player 1, port 1 = Player 2, etc.). This is useful when the automatic priority ordering does not match the user's preference, or when playing multiplayer with two controllers of different types.

### Access

Settings → External devices → General → **Port assignment**

(`SETTINGS_INPUT_DEVICES` → `SETTINGS_PORT_ASSIGNMENT`)

### Automatic Priority (when no manual order is saved)

`getAllGamePads()` sorts by `gamepadPriority()` descending, then `controllerNumber` as tiebreaker:

| Score | Criteria | Example devices |
|-------|----------|----------------|
| 3 | `SOURCE_JOYSTICK` + has motion ranges | PS4, Xbox, GameSir, 8BitDo |
| 2 | `SOURCE_JOYSTICK` only | Rare |
| 1 | Has motion ranges, no `SOURCE_JOYSTICK` | D-pad-only gamepads |
| 0 | Neither | TV IR remotes masquerading as gamepads |

### Manual Order (Port Assignment Screen)

When the user saves a custom order, `getGamePadsPortMapperObservable()` uses `combine` to merge the enabled inputs flow with the saved order flow. Devices are placed in the order the user defined; any newly connected device not in the saved order is appended at the end.

**UI**: List of currently connected controllers with Player N labels and ↑/↓ arrow buttons. "Reset to automatic" clears the saved order and restores priority-based sorting.

### Architecture

**Data storage**: Single `pref_key_port_order` key in `SharedPreferences`, JSON-serialized as `PortOrder(descriptors: List<String>)` where each entry is `InputDevice.descriptor` (a hardware-level stable ID, survives reconnections).

**Reactive flow**: `getGamePadsPortMapperObservable()` combines two flows:
```
getEnabledInputsObservable() ─┐
                               combine → portMappings: Map<Int, Int> → (InputDevice?) -> Int?
getPortOrderFlow()            ─┘
```

**Key files**:

| File | Role |
|------|------|
| `lemuroid-app/.../input/InputDeviceManager.kt` | `getPortOrderFlow()`, `savePortOrder()`, updated `getGamePadsPortMapperObservable()`, `gamepadPriority()` |
| `lemuroid-app/.../settings/inputdevices/PortAssignmentViewModel.kt` | UI state, `moveUp()`, `moveDown()`, `resetOrder()` |
| `lemuroid-app/.../settings/inputdevices/PortAssignmentScreen.kt` | Composable list with ↑/↓ buttons per device |
| `lemuroid-app/.../settings/inputdevices/InputDevicesSettingsScreen.kt` | Added "Port assignment" menu link in General group |
| `lemuroid-app/.../feature/main/MainNavigationRoutes.kt` | `SETTINGS_PORT_ASSIGNMENT` route |
| `lemuroid-app/.../feature/main/MainActivity.kt` | Composable wiring for new route |
| `lemuroid-app/src/main/res/values/strings.xml` | 6 new English strings |
| `lemuroid-app/src/main/res/values-pt-rBR/strings.xml` | 6 new Portuguese (BR) strings |

### Strings

| Key | EN | PT-BR |
|-----|----|-------|
| `settings_gamepad_title_port_assignment` | Port assignment | Atribuição de portas |
| `settings_gamepad_subtitle_port_assignment` | Assign controllers to player slots | Definir qual controle é o jogador 1, 2, etc. |
| `settings_port_assignment_group_title` | Controllers (drag to reorder) | Controles (use as setas para reordenar) |
| `settings_port_assignment_player` | Player %1$d | Jogador %1$d |
| `settings_port_assignment_reset` | Reset to automatic | Restaurar automático |
| `settings_port_assignment_reset_subtitle` | Priority: analog joysticks first, TV remotes last | Prioridade: joysticks analógicos primeiro, controles de TV por último |
| `settings_port_assignment_no_devices` | No controllers connected... | Nenhum controle conectado... |
