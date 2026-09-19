# Atualização Automática do Aplicativo

## Visão Geral

O app verifica e instala novas versões do APK sozinho, sem loja externa. Os dados do usuário (ROMs, saves, states, preferências) são preservados.

**O mecanismo é um arquivo estático publicado ao lado do APK.** Toda vez que `build-and-upload.ps1` roda, ele publica o APK no R2 e, como última escrita, um `version.json` no mesmo diretório. O app lê esse arquivo, compara o `versionCode` com o seu e oferece a atualização a quem estiver atrasado. Não existe passo manual entre publicar uma versão e os usuários serem avisados dela.

> **Mudança de 2026-08-13.** Antes disso o app consultava `https://emuladores.pythonanywhere.com/app_version` — rota que responde **404**. A verificação falhava em toda abertura, o erro era engolido em silêncio (por design, para não virar popup a cada launch) e **nenhum usuário jamais foi avisado de uma versão nova**. O `build-and-upload.ps1` apenas *imprimia* o JSON no fim da execução para ser colado à mão naquela rota; esse passo nunca aconteceu. Detalhes em [`../bugs/done/2026-08-13-atualizacao-in-app-endpoint-404.md`](../bugs/done/2026-08-13-atualizacao-in-app-endpoint-404.md). A mesma lógica está em produção no ARMSX2 (`rgs/ps2/version.json`).

---

## Arquivos

| Arquivo | Papel |
|---|---|
| `lemuroid-app/.../app/shared/updates/AppUpdateManager.kt` | Lê o `version.json`, baixa o APK (com resume, retry e verificação de SHA-256) e instala |
| `lemuroid-app/.../app/shared/updates/AppUpdateViewModel.kt` | Máquina de estados da UI mobile (Compose) |
| `lemuroid-app/.../app/shared/updates/UpdateInstallReceiver.kt` | `BroadcastReceiver` dos callbacks do `PackageInstaller` |
| `lemuroid-app/.../app/tv/shared/TVAppUpdateDialog.kt` | Mesmo fluxo na interface de TV (leanback usa fragments, não Compose) |
| `lemuroid-app/src/main/res/xml/update_provider_paths.xml` | `FileProvider` expondo `cacheDir/updates/` |
| `build-and-upload.ps1` | Gera e publica o `version.json` (passo 4/4) |

---

## O arquivo de anúncio (`version.json`)

```
https://versions.digitalstoregames.com/RetroGameSystem/version.json
```

Conteúdo real publicado na 237 (encurtado nos hashes):

```json
{
  "versionCode": 237,
  "versionName": "1.17.6",
  "channel": "default",
  "apkUrl": "https://versions.digitalstoregames.com/RetroGameSystem/retro-game-system-arm64.apk?v=237",
  "sha256": "80f7ec9ea860e089…",
  "size": 111560246,
  "apkUrls": {
    "arm64-v8a":   "…retro-game-system-arm64.apk?v=237",
    "armeabi-v7a": "…retro-game-system-armv7.apk?v=237"
  },
  "apkSha256": { "arm64-v8a": "80f7ec9e…", "armeabi-v7a": "…" },
  "apkSizes":  { "arm64-v8a": 111560246, "armeabi-v7a": 95194853 }
}
```

- **Splits por ABI.** O app percorre `Build.SUPPORTED_ABIS` (ordem de preferência do device: arm64 antes de armv7) e usa a primeira ABI presente em `apkUrls`, com o `sha256`/`size` correspondentes.
- **Os mapas são paralelos de propósito**, não objetos aninhados dentro de `apkUrls`: o parser já instalado em campo lê `apkUrls[abi]` como string, e um objeto aninhado viraria uma URL-lixo na mão dele.
- **`apkUrl`/`sha256`/`size` na raiz** são o fallback legado (apontam para o arm64), para clientes que não conhecem os mapas.
- **`channel`**: se vier diferente de `BuildConfig.APP_UPDATE_CHANNEL`, o app recusa o update — evita trocar uma edição por outra acidentalmente.

### O `?v=<versionCode>` não é enfeite

O cache de borda na frente do R2 continua servindo os bytes **antigos** na URL canônica por tempo indeterminado após o upload, e ignora `Cache-Control: no-cache` do cliente. Medido no ARMSX2 em 2026-08-10: o anúncio já dizia 1.0.9 enquanto a URL do APK ainda entregava o binário 1.0.8. A query string muda a chave de cache, então cada release busca bytes frescos. Sem isso o app baixa a versão errada, o SHA-256 não bate e a atualização entra em loop de erro até o cache expirar sozinho.

---

## Fluxos

### Ao abrir o app (mobile e TV)

```
onCreate()
  └─ checkOnStartup()
        ├─ passou menos de 12h desde a última checagem? → não faz nada
        └─ GET version.json
              ├── versionCode remoto > local, e o usuário não dispensou essa versão
              │      → diálogo "Atualização X disponível — atualizar agora?"
              └── caso contrário → silêncio (erro de rede também é silencioso)
```

O throttle de 12h e o "Agora não" evitam que o mesmo diálogo apareça a cada abertura. **"Agora não" só vale para aquela versão**: quando sair uma mais nova, o usuário é perguntado de novo.

### Pelas opções (verificação manual)

```
Configurações → "Verificar atualizações"
  └─ checkManually()   ← ignora o throttle de 12h E a versão dispensada
        ├── disponível  → diálogo "Atualizar agora?"
        ├── atualizado  → "Não há atualizações disponíveis no momento."
        └── erro        → mostra a mensagem (aqui o erro é visível: o usuário pediu)
```

Mobile: `SettingsScreen.kt`, seção Misc. TV: `tv_settings.xml`, categoria Misc (chave `pref_key_check_update`).

### Download e instalação

```
"Atualizar"
  ├─ o app pode instalar pacotes? (canRequestPackageInstalls)
  │     NÃO → diálogo "Permissão necessária" → tela do sistema
  │            (checado ANTES de baixar ~110 MB que o sistema recusaria instalar)
  └─ SIM → download com progresso
             ├─ retoma de .part via header Range (até 3 tentativas)
             ├─ confere tamanho e SHA-256 contra o anunciado
             │     hash errado → apaga tudo e recomeça do zero
             └─ PackageInstaller (fallback: FileProvider + ACTION_VIEW)
```

A verificação de SHA-256 é o que impede um download truncado em rede móvel de virar *"o pacote parece ser inválido"* na cara do usuário: o instalador nunca vê um APK corrompido.

---

## Máquina de Estados — `AppUpdateViewModel.State`

| Estado | Quando ocorre |
|---|---|
| `Idle` | Inicial / após fechar diálogo |
| `Checking` | Requisição HTTP em andamento |
| `UpdateAvailable(info)` | Nova versão encontrada — diálogo de confirmação |
| `NoUpdate` | Verificação manual sem novidade — diálogo informativo |
| `PermissionRequired(info)` | Falta "instalar apps desconhecidos" — diálogo com link para o sistema |
| `Downloading(progress)` | APK baixando — diálogo com barra de progresso |
| `Installing` | `PackageInstaller` comprometeu a sessão |
| `Error(message)` | Falha de rede, hash ou instalação |

---

## Publicação (`build-and-upload.ps1`)

```powershell
.\build-and-upload.ps1
```

1. Build release (splits arm64 + armv7) com bump automático de `versionCode`/`versionName`
2. **Gate de versão**: lê o `versionCode` de dentro de cada APK em `dist\` com `aapt2` e aborta se não bater com o que seria anunciado
3. Upload versionado para o HuggingFace (histórico)
4. Upload para o R2 + sidecars `.sha256` + verificação pela API S3 + purga do cache de borda + **verificação pela URL pública** (baixa o que o cliente baixaria e compara o SHA-256)
5. **Anúncio**: publica o `version.json`, purga o cache dele e confere o JSON já publicado

O anúncio é a **última** escrita de propósito: só vai ao ar depois que os APKs foram provados pela URL pública. Assim nenhum cliente vê "versão nova" apontando para bytes que ainda não estão sendo servidos corretamente.

`-SkipR2` gera o `version.json` em `dist/` mas não publica — nesse caso ninguém é avisado da versão, e o script avisa isso no resumo.

### Por que existe o gate de versão

O bump acontece **antes** do build, e o `$VERSION_CODE` do script vem do `build.gradle.kts` — não do binário. Se o build falhar no meio (aconteceu de verdade: OOM do R8 em 2026-08-14, depois do bump), `dist\` fica com o **APK anterior** enquanto o `.kts` já diz a versão nova. Publicar nesse estado faria o anúncio apontar a versão nova para os bytes velhos:

```
version.json diz 236  →  usuário baixa 111 MB  →  instala  →  continua no 235
                      →  próximo boot: "236 disponível"  →  loop infinito
```

Ler o `versionCode` de dentro do APK é o único jeito de fechar essa porta. O gate roda sempre que há APK em `dist\`, inclusive com `-SkipBuild`, e é pulado com aviso se o `aapt2` não for encontrado no SDK.

---

## Canais de catálogo/update

Builds distintos podem ter catálogos e fluxos de atualização separados sem criar flavors Gradle. Documentação operacional: [`canais-catalogo-update.md`](canais-catalogo-update.md).

| Propriedade | Padrão | Uso |
|---|---|---|
| `catalogChannel` | `default` | Identifica a edição/catálogo do APK. |
| `catalogManifest` | `lemuroid-app/src/main/assets/catalog_manifest.txt` | Manifest alternativo usado para gerar o `retrograde-prebuilt.db` e empacotado como asset. |
| `appUpdateChannel` | valor de `catalogChannel` | Canal consultado pelo update. |
| `appUpdateBaseUrl` | `https://versions.digitalstoregames.com/RetroGameSystem` | **Diretório** onde vive o anúncio. |
| `appUpdateEndpoint` | `<base>/version.json` ou `<base>/version-<channel>.json` | URL exata, caso precise sobrescrever. |
| `catalogApplicationIdSuffix` | vazio | Sufixo opcional para instalar edições lado a lado, ex.: `.ps2`. |

Canal ≠ `default` lê um arquivo separado (`version-ps2.json`), então um build de teste nunca dispara atualização nos clientes do canal estável. No lado da publicação, `$env:APP_UPDATE_CHANNEL` define qual arquivo o script escreve.

---

## Instalação por versão do Android

| API Level | Método | Comportamento |
|---|---|---|
| ≥ 31 (Android 12+) | `PackageInstaller` + `USER_ACTION_NOT_REQUIRED` | Sistema pode dispensar o prompt |
| 26–30 (Android 8–11) | `PackageInstaller` | Sistema exibe prompt de confirmação |
| Fallback | `FileProvider` + `ACTION_VIEW` | Abre o instalador de APK padrão |

Em API ≥ 26 a instalação exige que o usuário tenha liberado "instalar apps desconhecidos" para o app — daí o estado `PermissionRequired`.

**Autoridade do FileProvider:** `${applicationId}.update_provider`, expondo `cacheDir/updates/` (`<cache-path name="updates" path="updates/" />`). O APK baixado fica em `cacheDir/updates/lemuroid-update.apk` e o sistema o remove sozinho quando faltar espaço.

**Permissão no manifesto:** `android.permission.REQUEST_INSTALL_PACKAGES`.

---

## Preservação de dados

A instalação substitui apenas os arquivos do APK (`install -r`):

- `getExternalFilesDir("ROMs")`, `"saves"`, `"states"` → **preservados**
- `SharedPreferences` e banco Room → **preservados**

---

## SharedPreferences (`app_update_prefs`)

| Chave | Significado |
|---|---|
| `update_last_check_ms` | Timestamp da última checagem (throttle de 12h) |
| `update_skipped_version_code` | `versionCode` que o usuário dispensou com "Agora não" |

---

## Validação em aparelho

Testado ponta a ponta em **moto g86 5G (arm64, Android 15)** em 2026-08-14, publicando duas versões reais:

| Etapa | Resultado |
|---|---|
| Publicação da 236 (gate + verificação pública + anúncio) | OK — `version.json` de 990 bytes, relido pela URL pública |
| 236 instalada, app aberto | Sem diálogo — correto, 236 anunciado = 236 instalado |
| Publicação da 237 | OK — gate confirmou 237 dentro dos dois APKs |
| Configurações → Verificar atualizações | Diálogo **"Atualização 1.17.6 disponível"** |
| Toque em "Atualizar", sem permissão de instalação | Diálogo **"Permissão necessária"** — *antes* do download |
| Permissão concedida, novo toque | Download com progresso até **100%** (111 MB) |
| SHA-256 | Conferiu — nenhum erro, seguiu direto para o instalador |
| Instalação | **In-place, sem prompt** — Android 12+ honrou `USER_ACTION_NOT_REQUIRED` (mesmo assinante) |
| App reaberto | `versionCode=237 / versionName=1.17.6`, home normal, não oferece atualização de novo |

O que isso prova: o anúncio publicado é lido, o canal é validado, a escolha por ABI funciona, a integridade é conferida, a permissão é checada antes do gasto de banda, e a troca do pacote acontece sem intervenção.

> **Descoberta do teste — o throttle morde.** O aparelho já havia checado ao abrir a 236, então quando a 237 subiu **abrir o app não mostrou nada**: a checagem automática só volta a rodar 12h depois. Foi preciso o caminho manual. É o comportamento desenhado (e o do ARMSX2), mas na prática significa: **publicar duas versões no mesmo dia faz a segunda só aparecer pelas opções.**

---

## Pendências

| # | Pendência | Impacto |
|---|---|---|
| 1 | **Fluxo de TV nunca exercitado em aparelho.** O [`TVAppUpdateDialog.kt`](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/shared/TVAppUpdateDialog.kt) compila e está ligado no `MainTVActivity` e no `tv_settings.xml`, mas nenhuma TV/TV box rodou o fluxo. A build **armeabi-v7a existe justamente para esses aparelhos**. | Alto — é metade do público-alvo da distribuição |
| 2 | **Gap de uma vez só.** Quem está em versionCode ≤ 235 tem o endpoint 404 gravado dentro do APK: é inalcançável e **precisa de uma instalação manual única**. Da 236 em diante se sustenta sozinho. | Alto, mas por definição não tem solução remota |
| 3 | **Canal ≠ `default` nunca publicado nem lido.** O caminho `version-<canal>.json` existe nos dois lados (Gradle e script) mas nunca rodou. | Médio — só afeta edições alternativas |
| 4 | **Estado `Installing` não tem saída.** Se o usuário cancelar o prompt do sistema (Android 8–11, onde o prompt é obrigatório), o estado fica `Installing` para sempre; `checkManually()` passa a retornar cedo e o botão "Verificar atualizações" fica mudo até reiniciar o app. | Médio — não testado, o aparelho de teste é Android 15 e instalou sem prompt |
| 5 | **O update ignora "Baixar somente via WiFi".** Essa preferência (`wifi_only_download`, em `streaming_roms_prefs`) vale só para ROMs. A atualização baixa ~110 MB sem consultá-la. | Médio — usuário com plano limitado pode levar um susto |
| 6 | **Throttle de 12h vs. releases no mesmo dia** (ver acima). Uma alternativa seria checar sempre que o `versionCode` anunciado for diferente do último visto, em vez de só por tempo. | Baixo — o caminho manual cobre |
| 7 | **`update_skipped_version_code` guarda só a última versão dispensada.** Dispensar a 240 e depois a 241 esquece a 240 — irrelevante na prática, já que só a mais nova é oferecida. | Nenhum na prática |

### Nota operacional (não é do app)

O build release não cabia na máquina de desenvolvimento: 32 GB de RAM com limite de *commit* de ~36 GB, e o R8 morreu duas vezes com `Native memory allocation failed`. O culpado não era o Gradle — era o Kotlin Language Server do VS Code (extensão `fwcd.kotlin`) segurando 7,5 GB. Para buildar release nessa máquina:

```powershell
.\gradlew.bat --stop
.\gradlew.bat --% assembleFreeBundleRelease --no-parallel --max-workers=1 `
  -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g" `
  -Dkotlin.compiler.execution.strategy=in-process
.\build-and-upload.ps1 -NoBump
```

Aumentar o arquivo de paginação do Windows resolveria de forma permanente.

---

## Data de implementação

- **2026-04-17** — versão original (endpoint pythonanywhere, que nunca existiu).
- **2026-08-13** — migração para o `version.json` no R2, verificação de integridade (SHA-256 + tamanho), retry com resume, checagem de permissão, throttle/skip e suporte na TV.
- **2026-08-14** — gate de `versionCode` via `aapt2` na publicação; validado em aparelho com as versões 236 e 237 publicadas de verdade.
