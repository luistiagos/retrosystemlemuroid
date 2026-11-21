---
fecha_por: dono
---

# [BUG] Redmi A1 (Android 12 Go): "Como o pacote parece ser inválido, o app não foi instalado" no Retro Game System (arm64 e armv7) e no RetroSystem PS2

**Data:** 2026-11-20
**Severidade:** alta: cliente recém-comprado (R$ 39) não conseguiu instalar nenhum app, pediu reembolso (`[191530]`) e o operador prometeu "uma versão específica" (`[191257]`, `[191595]`)
**Complexidade:** baixa se a causa for ABI ou arquivo errado (só orientação ao cliente); média se o armv7 1.17.37 de fato falhar num Android 12 Go 32-bit (exige aparelho/AVD equivalente e logcat)
**Branch:** version10
**Origem:** triagem do chamado de suporte #417 (sessão `207464419053606@lid`), aberto em 2026-11-20 16:04 UTC

---

## Sintoma

O cliente, num **Redmi A1** (`[191142]`) com **Android 12**, patch de 1/9/2024 (`[191221]`), 32 GB e ~8 GB livres
(`[191542]`), tentou instalar pelo Chrome e recebeu sempre o mesmo diálogo do instalador:

> Como o pacote parece ser inválido, o app não foi instalado. — `CONCLUÍDO`

O diálogo apareceu no **RetroSystem PS2** (`[191006]`, `[191198]`, `[191558]`) e no **Retro Game System**
(`[191009]`, `[191093]`, `[191197]`, `[191557]`). O cliente diz ter tentado também o `armv7`: *"Sim tentei esse nem foi
nem o retro foi"* (`[191206]`).

## Evidência

### Conversa (`Wpp_proccess`, horários em UTC)

- `[190957]` 15:41: o agente manda `retro-game-system-arm64.apk` + `retrosystem-ps2.apk` (não pergunta o aparelho antes).
- `[190961]` 15:42: print das Transferências do Chrome: *"retrosystem-ps2.apk 81,92 KB/57,53 MB • Uma hora restante"*,
  *"retro-game-system-arm64 (1).apk 8,79 MB/200,34 MB"*, mais *"um arquivo concluído anteriormente"*.
- `[190978]` 15:48: *"Certo instalei apareceu concluído"*. Seis minutos depois de um download de PS2 que previa
  "uma hora restante": o arquivo tocado provavelmente **não era** o desse download.
- `[191015]` 16:04: o agente afirma que o aparelho "parece ser de 32 bits". É um palpite, mas plausível (ver abaixo).
- `[191095]` 16:30 e `[191165]` 16:55: o `armv7` é enviado (pelo agente e pelo operador).
- `[191197]`/`[191198]` 17:04 e `[191557]`/`[191558]` 20:11: os prints de erro mostram **"dois arquivos APK"** na lista
  de Transferências. Pela descrição, a lista nunca aparece com três (arm64 + ps2 + armv7). **Não há prova de que o
  arquivo instalado tenha sido o `armv7`**: o título do diálogo é "Retro Game System" tanto no arm64 quanto no armv7.
- `[191252]` 17:37: *"Pq todo que baixei disse que não e compatível com celular"*. Pode ser paráfrase ou outra
  mensagem (`install_failed_incompatible`), vista em algum momento.
- `[190897]` 15:15, antes da compra: *"já teve outro compra não dá certo"*. Há uma compra anterior de outro fornecedor;
  pode haver app com o mesmo pacote instalado (ver hipótese 3).

### APKs publicados (baixados em 2026-11-20 ~20:45 UTC)

| URL | bytes | Last-Modified | versão | `native-code` | min/target | assinatura |
|---|---|---|---|---|---|---|
| `RetroGameSystem/retro-game-system-armv7.apk` | 158.513.870 | 2026-11-20 16:00:01 | 1.17.37 (268) | `armeabi-v7a` | 21/35 | v1+v2+v3 OK |
| `RetroGameSystem/retro-game-system-arm64.apk` | 195.323.821 | 2026-11-20 15:59:51 | 1.17.37 (268) | `arm64-v8a` | 21/35 | v1+v2+v3 OK |
| `rgs/ps2fork/retrosystem-ps2.apk` (`come.nanodata.armsx2`) | 57.528.441 | 2026-11-09 21:36:31 | 2.0.13 (2013) | **só** `arm64-v8a` | 26/37 | só v2 (ok para API ≥ 24) |

- `aapt2 dump badging`, `apksigner verify -v` e `zipfile.testzip()`: os três estão íntegros. `extractNativeLibs=true` nos três.
- O `armv7` traz dois `.so` que não são ELF: `lib/armeabi-v7a/libcitra_libretro_android.so` (92 B) e
  `libcitra_legacy_libretro_android.so` (106 B), já anotados em
  `docs/bugs/aberto/2026-11-17-apk-nao-instala-fire-stick-e-tv-tcl/2026-11-17-apk-nao-instala-fire-stick-e-tv-tcl.md`.
  No Android 12 o `NativeLibraryHelper` só os copia e não confere ELF (`copyFileIfChanged`), então **não** derrubam a instalação.
- **O arm64 foi trocado no meio do download do cliente.** `dist/retro-game-system-arm64.apk` local (1.17.36) tem
  **200.337.932 B = os "200,34 MB"** do print `[190961]` (15:42). O servidor passou a servir 1.17.37 às 15:59:51.
  O servidor **honra `If-Range`** (validador antigo → `200` com o arquivo inteiro), então o Chrome reinicia em vez de
  colar pedaços. **Ignora `If-Match`**: devolveu `206` com ETag inexistente, quando o certo seria `412`.
  Cliente que retoma download com `If-Match` (o `DownloadManager` do Android) pode montar um APK híbrido se a
  publicação acontecer no meio do download.

### O que o instalador do Android 12 mostra para cada falha (AOSP `android12-release`)

- `PackageManager.installStatusToPublicStatus`: `INSTALL_FAILED_NO_MATCHING_ABIS` → `STATUS_FAILURE_INCOMPATIBLE`
  ("não é compatível"); `INSTALL_FAILED_INVALID_APK`, `INSTALL_PARSE_FAILED_*` (inclui `NO_CERTIFICATES`),
  `INSTALL_FAILED_VERSION_DOWNGRADE` e `INSTALL_FAILED_BAD_SIGNATURE` → `STATUS_FAILURE_INVALID` ("pacote parece ser inválido").
- `PackageInstallerSession.extractNativeLibraries` propaga o código do `NativeLibraryHelper`. Em AOSP puro, APK
  só-arm64 num aparelho 32-bit daria "não é compatível", **não** "inválido". Precedente de que a mensagem engana:
  em `docs/bugs/concluido/correcoes-2026-05-28/correcoes-2026-05-28.md`, este mesmo texto era conflito com um pacote
  já instalado (`com.swordfish.lemuroid` com outra chave).
- Erro na fase 1 (APK truncado, central directory quebrado) mostra outro diálogo ("problema ao analisar o pacote").
  O "inválido" vem do commit da sessão: o APK abriu, o manifesto foi lido e a falha veio depois.

### Reprodução em emulador Android 12 32-bit (2026-11-20 ~22:40 UTC)

Como não existe imagem Android 12 ARM 32-bit nem Go no SDK, a mais próxima é a `system-images;android-31;android-tv;x86`,
com userspace **32-bit** (`ro.system.product.cpu.abilist64=` vazio). AVD `lemu_api31_32bit`: 2 GB de RAM, 8 GB de
`/data`. Ela **não** tem tradução ARM (`ro.dalvik.vm.native.bridge=0`, sem `libndk_translation`/`libhoudini`).

| Teste | Resultado |
|---|---|
| `adb install` armv7 / arm64 / PS2 originais | os três: `INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries, res=-113` (esperado: o emulador é x86) |
| `armv7` com `lib/armeabi-v7a/` renomeado para `lib/x86/` (mesmos 55 `.so`, stubs do 3DS e MAME de 169 MiB; reassinado com o `debug.keystore`; `scratchpad/rezip.py`) | **`Success` em 16 s**, `primaryCpuAbi=x86`, `versionName=1.17.37`, dex2oat32 OK |
| `-prop ro.config.low_ram=true` (simular Go) | ignorado: a imagem é `user`, sem `adb root` |
| Diálogo do instalador para `-113` | não capturado: o `InstallStart` recusa a intent vinda do shell (`uid 2000 needs to declare permission android.permission.REQUEST_INSTALL_PACKAGES`), e a imagem de TV não tem gerenciador de arquivos |

**Conclusão do teste:** o **conteúdo** do armv7 1.17.37 instala num Android 12 32-bit com 2 GB. A extração de
350 MiB de `.so`, os stubs não-ELF, o tamanho do APK e a assinatura v1+v2+v3 com o mesmo certificado passam.
O que o emulador **não** cobre: ARM real, ROM da Xiaomi, `low_ram`, instalação pela UI (Chrome →
PackageInstaller). Uma falha do armv7 no aparelho do cliente tem de vir de uma dessas diferenças ou do lado do
aparelho (arquivo, pacote homônimo).

### Hardware do cliente (prints do Device Info HW, 2026-11-20 ~23:50 UTC, repassados pelo dono)

| Campo | Valor |
|---|---|
| Modelo | Xiaomi `220733SG` (Redmi A1), plataforma `mt6761` |
| Android / kernel | 12 / `4.19.191-perf` (build de 2024-09-02) |
| SoC | MediaTek Helio A22 (MT6761V/WBB), 4× Cortex-A53, 850–2001 MHz |
| **Modo / suportado** | **32-bit** / 64-bit (a CPU é 64-bit, o sistema é 32-bit) |
| Máquina | `armv7l` |
| **ABIs suportadas** | **`armeabi-v7a armeabi`** (sem `arm64-v8a`) |
| GPU | PowerVR Rogue GE8300, OpenGL ES 3.2 |
| RAM | 2 GB |

**Hipótese 1 confirmada** (o ABI, não o texto da mensagem): neste aparelho o `retro-game-system-arm64.apk` e o PS2
(`come.nanodata.armsx2`, só `arm64-v8a`) **nunca** instalam, em nenhuma versão. O único arquivo possível é o
`retro-game-system-armv7.apk`. O ABI não explica a falha do armv7, que tem `lib/armeabi-v7a/` e instalou no AVD
Android 12 32-bit. Continua faltando o código exato da falha do armv7 (pedido ao cliente via SAI).

### Build de teste para o cliente: armv7 sem MAME e com pacote próprio (2026-11-20, pedido do dono, NÃO publicado)

Objetivo: testar duas hipóteses de uma vez, sem depender do SAI: conflito de pacote (hipótese 3) e tamanho (`lib/` de
350 MiB extraído num Android Go de 2 GB).

- **Pacote:** `-PcatalogApplicationIdSuffix=<sufixo>` já existe em `lemuroid-app/build.gradle.kts` (`applicationId =
  "app.retrogamesystem$catalogApplicationIdSuffix"`). Os providers do manifesto usam `${applicationId}`
  (`.update_provider`, `.androidx-startup`), então não há `CONFLICTING_PROVIDER` com o app padrão.
- **Atualização:** `-PappUpdateChannel=<canal>` faz o app ler `version-<canal>.json`. Se o arquivo não existe, `AppUpdateManager.fetchVersionInfo`
  lança `IOException`. `AppUpdateViewModel.checkOnStartup` engole a exceção e volta a `Idle`, sem popup. Sem isso o app ofereceria
  "atualizar" para o APK padrão, que é outro pacote.
- **Sem MAME:** propriedade nova `-PexcludeCores=mamearcade` → `packaging.jniLibs.excludes`. O `BundledCoresVerifier` lê
  a pasta-fonte `bundled-cores/src/main/jniLibs`, não o APK, então não acusa.
- **Efeito colateral:** os jogos MAME 0.289 não abrem nessa build. `GameLoader.findLibrary` não acha o core, e
  `GameViewModelRetroGameView` chama `CoreDownloader.downloadCore` (tag `1.24.0`). Esse arquivo está em **Git LFS**:
  `curl -sIL https://raw.githubusercontent.com/luistiagos/libretrocores/1.24.0/lemuroid_core_mamearcade/src/main/jniLibs/armeabi-v7a/libmamearcade_libretro_android.so`
  → `Content-Length: 134` (o ponteiro LFS, `oid sha256:6812…`, `size 177130180`). Abaixo de `MIN_VALID_CORE_SIZE`
  (10 KiB) → falha de núcleo. **Achado à parte:** o download sob demanda do MAME (flavor `dynamic` e fallback)
  está quebrado para todos. Registrar como bug próprio.

**Gerado** (BUILD SUCCESSFUL, 6m42s):
`./gradlew :lemuroid-app:assembleFreeBundleRelease -PcatalogApplicationIdSuffix=lite32 -PappUpdateChannel=lite32
-PexcludeCores=mamearcade "-PappLabel=Retro Game System Lite"`.
Cópia local fora do repo, não publicada: `C:\projects\lemuroid\apk-teste-cliente\retro-game-system-lite-armv7.apk`.

| Campo | Valor |
|---|---|
| Pacote | `app.retrogamesystem.lite32`, `1.17.38` (269), label "Retro Game System Lite" |
| ABI | só `armeabi-v7a`, 54 `.so`, sem `libmamearcade` (o `libmame2003_plus`, 24 MB, continua) |
| Tamanho | 106.039.283 B (o armv7 publicado tem 158.513.870 B); 318 MB descomprimido |
| Assinatura | v1+v2+v3 OK (`debug.keystore`) |
| SHA-256 | `c62911ce64b07ea53029d22343e5a95df2e132004f8f5df6df873abbc458b78b` |

Como ler o resultado: **instala** → a causa é conflito de pacote ou tamanho (o SAI no armv7 normal separa as duas).
**Mesmo "inválido"** → nem o pacote nem o MAME são a causa. Sobram parse/assinatura/dexopt no aparelho, e só se esclarece
com o código detalhado (SAI).

## Hipóteses

1. **(CONFIRMADA quanto ao ABI) PS2 e Retro arm64 nunca instalam neste aparelho.** O Redmi A1 é Android 12 **Go** com Helio A22
   ([mi.com](https://www.mi.com/global/redmi-a1/specs/)). Na mesma linha, a Xiaomi documenta o Redmi A3 como 32-bit
   ([FAQ KA-189890](https://www.mi.com/global/support/faq/details/KA-189890)). Se o `ro.product.cpu.abilist` for só
   `armeabi-v7a,armeabi`, o PS2 (só arm64, sem build 32-bit) é impossível, e o arm64 do Retro também. Contra: o texto
   da mensagem não é o que o AOSP mostraria (o OEM pode mapear diferente).
2. **O cliente nunca instalou o `armv7`.** Os prints mostram sempre dois APKs na lista, e o título "Retro Game System"
   não separa arm64 de armv7. É coerente com reabrir os arquivos antigos.
3. **Pacote homônimo já instalado** (compra anterior, `[190897]`): um app `app.retrogamesystem` ou
   `come.nanodata.armsx2` com outra chave ou `versionCode` maior daria `VERSION_DOWNGRADE`/conflito. Pelo precedente
   de 2026-05-28, aparece como "inválido". Explicaria o "inválido" nos **três** arquivos.
4. **Arquivo corrompido**: improvável. Os três publicados verificam, o servidor honra `If-Range` e o armv7 foi
   enviado (16:30) depois da republicação (16:00).

## O que fazer

1. **Pedir ao cliente** (operador, sem custo de build):
   - print da lista de Transferências do Chrome com **nome e tamanho** de cada APK. O armv7 certo é
     `retro-game-system-armv7.apk`, **158,5 MB**; apagar todos os outros antes;
   - em Configurações > Apps, procurar "Retro Game System", "RetroSystem PS2", "ARMSX2", "Lemuroid" ou apps da compra
     anterior. Desinstalar o homônimo e tentar o armv7 de novo (hipótese 3);
   - ~~opcional: instalar um app de informação de hardware e mandar a linha **ABI**~~ feito: `armeabi-v7a armeabi`;
   - **pendente:** instalar o armv7 pelo SAI (Split APKs Installer) e mandar o print do erro detalhado (`INSTALL_FAILED_*`).
2. **Se o armv7 de 158,5 MB, recém-baixado e sem homônimo instalado, ainda falhar**: reproduzir num Android 12 Go
   32-bit (aparelho ou AVD `android-31` Go, se houver imagem 32-bit) com `adb install -r` e logcat de
   `PackageManager`/`PackageInstallerSession`/`NativeLibraryHelper`, para ter o `INSTALL_FAILED_*` exato.
3. **PS2 no Redmi A1:** hipótese 1 confirmada pelo ABI (2026-11-20): não há "versão específica" possível. O ARMSX2 é só arm64, e
   PS2 num Helio A22 com 2–3 GB não roda. Decisão do dono: como responder ao cliente/reembolso (fora deste repo).
4. **Infra (`versions.digitalstoregames.com`):** responder `412` a `If-Match` divergente e/ou publicar com nome
   versionado + troca atômica do link. Defeito secundário, não provado como causa aqui (registrar no repo do
   servidor de versões se o dono quiser).

## Escopo — o que NÃO é este bug

- Não é o bug da Fire TV/TCL (`2026-11-17-apk-nao-instala-fire-stick-e-tv-tcl`): lá a mensagem é "O app não foi
  instalado" (genérica) e as hipóteses são armazenamento/Fire OS. Aqui sobram 8 GB e a mensagem é "inválido".
- Respostas do agente (afirmar 32-bit sem saber o aparelho, `[191015]`; contradizer o operador às 20:06, `[191519]`,
  depois de o humano prometer uma versão) são do `digitalstoregamesproject`, não deste repo.
