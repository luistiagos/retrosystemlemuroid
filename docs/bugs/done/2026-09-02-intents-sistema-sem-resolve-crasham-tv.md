# [BUG] Intents do sistema lançadas sem checar resolução crasham o app em TV / Fire TV

**Data:** 2026-09-02
**Status:** Resolvido ✅ (guard aplicado em todos os call-sites; falta confirmação em aparelho)
**Severidade:** Média (crash direto ao tocar num item de Ajustes; atinge só TV/Fire TV, que é
parte do público-alvo)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/main`
(`Instrumentation.java::android.app.Instrumentation.checkStartActivityResult`)
**Errors (serviço):**
- 3738, 3737 — `GET_CONTENT` `application/zip` (importar romset)
- 1394 — `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` (permissão de armazenamento)

**Aparelhos:** Amazon AFTSSS (Fire TV Stick), Android 9, app 1.17.12 · TCL Smart TV Pro,
Android 11, app 1.17.9 — ambos **armeabi-v7a**
**Observado em:** 2026-08-18 17:49 e 2026-09-01 23:38

---

## Sintoma

Fire TV Stick, ao tentar importar um romset:

```
android.content.ActivityNotFoundException: No Activity found to handle Intent
{ act=android.intent.action.GET_CONTENT cat=[android.intent.category.OPENABLE] typ=application/zip }
	at android.app.Instrumentation.checkStartActivityResult(Instrumentation.java:2007)
	...
	at androidx.compose.ui.platform.AndroidComposeView.dispatchKeyEvent(SourceFile:30)
```

TCL Smart TV, ao tentar conceder acesso a todos os arquivos:

```
android.content.ActivityNotFoundException: No Activity found to handle Intent
{ act=android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION }
	at android.app.Instrumentation.checkStartActivityResult(Instrumentation.java:2075)
```

O `dispatchKeyEvent` no stack mostra que a ativação veio do **controle remoto** — é o fluxo
normal de navegação em TV, não um caso exótico.

## Causa-raiz

Duas chamadas lançam Intent de sistema assumindo que existe alguém para atendê-la. Em Android
TV e Fire OS essa premissa é falsa: builds de TV frequentemente não embarcam
`DocumentsUI`/`Files`, e a tela de "acesso a todos os arquivos" não existe.

### 1. Seletor de arquivo `application/zip`

[RomsetImportScreen.kt:40](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/settings/romset/RomsetImportScreen.kt#L40)
+ [RomsetImportScreen.kt:62](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/settings/romset/RomsetImportScreen.kt#L62):

```kotlin
val filePicker = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { ... }
...
onPickFile = { filePicker.launch("application/zip") }
```

`ActivityResultContracts.GetContent` monta `ACTION_GET_CONTENT` + `CATEGORY_OPENABLE` e chama
`startActivityForResult` **sem** `resolveActivity`. Sem handler → `ActivityNotFoundException`
não capturada → crash.

### 2. Permissão de acesso a todos os arquivos

[SettingsScreen.kt:361-366](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/settings/general/SettingsScreen.kt#L361-L366):

```kotlin
val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { … }
…
val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
```

Mesmo padrão: `startActivity` direto. O fallback já previsto (do `MANAGE_APP_…` específico
para o `MANAGE_…` genérico) cobre o caso de o *primeiro* não existir, mas não o de **nenhum
dos dois** existir — que é o da TCL.

## Como reproduzir

- Fire TV Stick (ou emulador Android TV sem DocumentsUI): Ajustes → importar romset → escolher
  arquivo.
- Android TV sem a tela de acesso a arquivos: Ajustes → permissão de armazenamento.

## Correção

Padrão único para toda Intent implícita de sistema, em
[SafeIntents.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/SafeIntents.kt):

```kotlin
fun Context.startActivitySafely(intent: Intent, @StringRes fallbackMessage: Int? = null): Boolean

fun <I> ActivityResultLauncher<I>.launchSafely(
    context: Context,
    input: I,
    @StringRes fallbackMessage: Int,
): Boolean
```

Ambos devolvem `false` quando não há handler, logam via Timber e — quando recebem
`fallbackMessage` — avisam o usuário por `displayToast` (SafeToast, pitfall 7). O
`fallbackMessage` é opcional justamente para encadear tentativas: só a última da cadeia fala.
Além de `ActivityNotFoundException` capturam `SecurityException` (handler existe mas não é
exportado — para o usuário dá no mesmo).

### Por que try/catch e não `resolveActivity`

O caminho "checar antes" foi **descartado de propósito**. O app não declara `<queries>` no
`AndroidManifest.xml` (conferido: zero ocorrências), então a partir da API 30 o filtro de
visibilidade de pacotes faz `resolveActivity`/`queryIntentActivities` devolverem `null` mesmo
quando existe um handler instalado. Um guard por resolve — e, pela mesma razão, a ideia de
esconder o item quando "não houver handler" — esconderia botões que funcionam na maioria dos
aparelhos. Iniciar Activity por Intent implícita continua permitido sem `<queries>`, então o
try/catch acerta nos dois sentidos: só reage quando a Intent realmente não abriu.

### Call-sites corrigidos

| Arquivo | Intent | O que passou a acontecer |
|---------|--------|--------------------------|
| [RomsetImportScreen.kt:66](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/settings/romset/RomsetImportScreen.kt#L66) | `GetContent("application/zip")` | toast `settings_no_file_picker` apontando o caminho alternativo que a própria tela oferece (copiar o ZIP para a **raiz** de SD/USB e tocar em Atualizar — é o que `RomsetImportManager.findRomsetOnVolumes()` varre) |
| [RomsetExportScreen.kt:55](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/settings/romset/RomsetExportScreen.kt#L55) | `OpenDocumentTree` | toast `settings_no_folder_picker` |
| [TransferExportScreen.kt:73](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/settings/transfer/TransferExportScreen.kt#L73) | `OpenDocumentTree` | toast `settings_no_folder_picker` |
| [SettingsScreen.kt:354-381](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/settings/general/SettingsScreen.kt#L354-L381) | `MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` → `MANAGE_ALL_FILES_ACCESS_PERMISSION` | cadeia de fallback preservada; se **nenhuma** abrir, toast `settings_no_all_files_screen` **e o switch volta para desligado** |
| [Android.kt:45](../../../retrograde-util/src/main/java/com/swordfish/lemuroid/common/Android.kt#L45) | `APPLICATION_DETAILS_SETTINGS` | `displayDetailsSettingsScreen()` passou a devolver `Boolean` e a engolir a exceção — chamada logo depois de um "negar" permissão, era um crash esperando |
| [UpdateInstallReceiver.kt:22](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/updates/UpdateInstallReceiver.kt#L22) | `confirmIntent` do `PackageInstaller` | exceção num `BroadcastReceiver` mata o processo; agora a confirmação só não abre |

O switch de "acesso a todos os arquivos" precisou de mais que o guard: sem a tela, o estado
ligado seria mentira — o scan continuaria sem enxergar nada fora das pastas já acessíveis. Por
isso o `booleanPreferenceState` foi hasteado para um `val` e revertido quando nenhuma das duas
Intents abre.

### Call-sites auditados e deixados como estavam

A varredura cobriu todo `startActivity` / `startActivityForResult` / `launch` de contrato dos
módulos. O que não mudou, e por quê:

- **Intents explícitas** (`Intent(context, MinhaActivity::class.java)`): não podem faltar
  handler. `InputDevicesSettingsScreen`, `GamePadPreferencesHelper`, `SaveSyncPreferences`,
  `SaveSyncSettingsScreen`, `GameCrashActivity`, `TVFolderPickerLauncher`, `TVHomeFragment`,
  `BaseGameActivity` (menu do jogo e lançamento do jogo).
- **Já guardados**: `MainActivity.requestBatteryOptimizationExemption` (catch de
  `ActivityNotFoundException`), `MainActivity`/`TVAppUpdateDialog` no
  `buildAllowInstallIntent` (`runCatching`), `StorageFrameworkPickerLauncher`
  (`OPEN_DOCUMENT_TREE` com catch + `dialog_saf_not_found`), `openSafFolderInFileManager` /
  `openFolderInFileManager` (catch de `Exception` com toast do caminho).
- **`AppUpdateManager.installViaIntent`**: é o último fallback do instalador e roda dentro de
  `downloadAndInstall`, cujos dois chamadores (`AppUpdateViewModel.startUpdate` e
  `TVAppUpdateDialog`) envolvem tudo em `try/catch (Exception)` e viram estado de erro na UI —
  não é caminho de crash.
- **`ActivityResultContracts.RequestPermission`** (`HomeScreen`, `MainTVActivity`): o
  `ActivityResultRegistry` trata `ACTION_REQUEST_PERMISSIONS` por
  `ActivityCompat.requestPermissions`, sem Intent implícita.
- **`ActivateGoogleDriveActivity`**: só existe no flavor `play`, e o `signInIntent` do Play
  Services é outro problema (ausência de Play Services), fora do escopo deste bug.

### Strings novas

`settings_no_file_picker`, `settings_no_folder_picker`, `settings_no_all_files_screen` em
`values/strings.xml` (en) e `values-pt-rBR/strings.xml`.

## Validação

- `./gradlew :lemuroid-app:compileFreeBundleDebugKotlin` e `:retrograde-util:compileDebugKotlin`
  passam.
- **Falta** confirmar num Fire TV Stick e numa TV TCL que o toque no item mostra o aviso em vez
  de crashar. Os dois aparelhos do report estão fora de alcance aqui; o sinal de sucesso é o
  desaparecimento de `checkStartActivityResult` na telemetria `retrogamesystem/main`.

## Lição

Intent implícita é chamada de rede a um serviço que pode não existir — em TV box e Fire OS a
falta de handler é o caso comum, não a exceção. E o guard tem que ser **try/catch**, não
"checar antes": desde a API 30, sem `<queries>` no manifesto, `resolveActivity` mente por
omissão, então um guard por resolve troca um crash por uma feature invisível. O `launch()` de
`ActivityResultLauncher` conta como Intent implícita — ele chama `startActivityForResult` de
forma síncrona, e foi por ali que o crash do Fire TV subiu junto com o `dispatchKeyEvent` do
controle remoto.
