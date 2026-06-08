# Atualização Automática do Aplicativo

## Visão Geral

A funcionalidade de atualização automática permite que o Lemuroid verifique e instale novas versões do APK diretamente, sem que o usuário precise acessar lojas externas. Os dados do usuário (ROMs, saves, states) são totalmente preservados durante o processo.

---

## Arquivos Criados

| Arquivo | Papel |
|---|---|
| `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/updates/AppUpdateManager.kt` | Lógica principal: busca metadados da versão, faz download do APK e inicia a instalação |
| `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/updates/AppUpdateViewModel.kt` | ViewModel com máquina de estados; expõe `StateFlow` para a UI |
| `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/updates/UpdateInstallReceiver.kt` | `BroadcastReceiver` que recebe callbacks do `PackageInstaller` |
| `lemuroid-app/src/main/res/xml/update_provider_paths.xml` | Configuração do `FileProvider` para expor `cacheDir/updates/` |

---

## Arquivos Modificados

| Arquivo | O que foi adicionado |
|---|---|
| `lemuroid-app/src/main/res/values/strings.xml` | 11 strings em português para os diálogos de atualização |
| `lemuroid-app/src/main/AndroidManifest.xml` | Permissão `REQUEST_INSTALL_PACKAGES`, `UpdateInstallReceiver`, `FileProvider` |
| `lemuroid-app/.../feature/settings/general/SettingsScreen.kt` | Opção "Verificar atualizações" na seção Misc Settings |
| `lemuroid-app/.../feature/main/MainActivity.kt` | `AppUpdateViewModel`, chamada `checkOnStartup()` e bloco de diálogos |

---

## Fluxo de Verificação na Inicialização

```
onCreate()
  └─ updateViewModel.checkOnStartup()
        └─ [background] GET https://emuladores.pythonanywhere.com/app_version
              ├── versionCode remoto > versionCode local?
              │     SIM → State.UpdateAvailable(info)  → diálogo "Atualizar agora?"
              └── NÃO → State.Idle (silencioso)
```

## Fluxo Manual (via Configurações)

```
Settings → "Verificar atualizações"
  └─ updateViewModel.checkManually()
        ├── atualização disponível → diálogo "Atualizar agora?"
        └── já atualizado         → diálogo "Você já tem a versão mais recente"
```

---

## Máquina de Estados — `AppUpdateViewModel.State`

| Estado | Quando ocorre |
|---|---|
| `Idle` | Estado inicial / após fechar diálogo |
| `Checking` | Requisição HTTP em andamento |
| `UpdateAvailable(info)` | Nova versão encontrada — diálogo de confirmação |
| `NoUpdate` | Verificação manual sem novidade — diálogo informativo |
| `Downloading(progress)` | APK sendo baixado — diálogo com barra de progresso |
| `Installing` | `PackageInstaller` comprometeu a sessão |
| `Error(message)` | Falha de rede ou instalação |

---

## Endpoint do Servidor

```
GET https://emuladores.pythonanywhere.com/app_version
```

Resposta esperada:
```json
{
  "versionCode": 232,
  "versionName": "1.18.0",
  "apkUrl": "https://example.com/lemuroid-1.18.0.apk"
}
```

A comparação é feita por `versionCode` (inteiro). O download é iniciado apenas quando `versionCode remoto > BuildConfig.VERSION_CODE`.

### Canais de catálogo/update

Builds distintos podem ter catálogos e fluxos de atualização separados sem criar novas flavors Gradle. O build aceita propriedades:

Documentação operacional completa: [`canais-catalogo-update.md`](canais-catalogo-update.md).

| Propriedade | Padrão | Uso |
|---|---|---|
| `catalogChannel` | `default` | Identifica a edição/catálogo do APK. |
| `catalogManifest` | `lemuroid-app/src/main/assets/catalog_manifest.txt` | Manifest alternativo usado para gerar o `retrograde-prebuilt.db` e empacotado como asset. |
| `appUpdateChannel` | valor de `catalogChannel` | Canal consultado pelo update. |
| `appUpdateBaseUrl` | `https://emuladores.pythonanywhere.com/app_version` | Base usada para montar endpoint por canal. |
| `appUpdateEndpoint` | base ou `base/<channel>` | Endpoint exato, caso precise sobrescrever. |
| `catalogApplicationIdSuffix` | vazio | Sufixo opcional para instalar edições lado a lado, ex.: `.ps2`. |

Exemplo:

```powershell
.\gradlew.bat :lemuroid-app:assembleFreeBundleRelease `
  -PcatalogChannel=ps2 `
  -PcatalogManifest=..\..\fetchimagers\catalog_manifest_ps2.txt `
  -PcatalogApplicationIdSuffix=.ps2
```

Esse APK lê o catálogo alternativo, gera o DB prebuilt a partir dele e consulta:

```
GET https://emuladores.pythonanywhere.com/app_version/ps2
```

Resposta recomendada para canal:

```json
{
  "channel": "ps2",
  "versionCode": 232,
  "versionName": "1.18.0-ps2",
  "apkUrl": "https://emuladores.pythonanywhere.com/static/apks/retro-game-system-ps2-v1.18.0.apk"
}
```

Se `channel` vier no JSON e for diferente de `BuildConfig.APP_UPDATE_CHANNEL`, o app recusa o update para evitar trocar uma edição por outra acidentalmente.

---

## Instalação por Android Version

| API Level | Método | Comportamento |
|---|---|---|
| ≥ 31 (Android 12+) | `PackageInstaller` + `USER_ACTION_NOT_REQUIRED` | Instalação silenciosa sem prompt |
| 26–30 (Android 8–11) | `PackageInstaller` | Sistema exibe prompt de confirmação |
| < 21 (Android < 5) | `FileProvider` + `ACTION_VIEW` | Abre o instalador de APK padrão |

---

## Preservação de Dados

A instalação usa `install -r` (replace), que substitui apenas os arquivos do APK.

- `getExternalFilesDir("ROMs")` → **preservado**
- `getExternalFilesDir("saves")` → **preservado**
- `getExternalFilesDir("states")` → **preservado**
- Preferências do usuário (`SharedPreferences`) → **preservadas**

---

## Configuração do FileProvider

**Autoridade:** `${applicationId}.update_provider`

**Caminho exposto (`update_provider_paths.xml`):**
```xml
<cache-path name="updates" path="updates/" />
```

O APK baixado é armazenado em `cacheDir/updates/lemuroid-update.apk` e removido automaticamente pelo sistema quando a memória estiver baixa.

---

## Permissões Adicionadas ao Manifesto

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

Esta permissão é necessária para que o `PackageInstaller` consiga instalar pacotes fora da Play Store.

---

## Strings Adicionadas

| Chave | Valor |
|---|---|
| `update_dialog_title` | `Atualização %1$s disponível` |
| `update_dialog_message` | `Uma nova versão do Lemuroid está disponível. Deseja atualizar agora?` |
| `update_dialog_yes` | `Atualizar` |
| `update_dialog_no` | `Agora não` |
| `update_downloading_title` | `Baixando atualização…` |
| `update_downloading_message` | `%1$d%% concluído` |
| `update_no_update_title` | `Sem atualizações` |
| `update_no_update_message` | `Você já tem a versão mais recente do Lemuroid.` |
| `update_error_title` | `Erro na atualização` |
| `settings_title_check_update` | `Verificar atualizações` |
| `settings_description_check_update` | `Buscar nova versão do aplicativo` |

---

## Data de Implementação

2026-04-17
