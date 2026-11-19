# [BUG] APK (`retro-game-system-armv7.apk`) não instala no Fire TV Stick e na Smart TV TCL Android

**Data:** 2026-11-17
**Severidade:** alta — impede a instalação do app na TV em dois chamados distintos; no #397 o cliente desistiu de tentar na TV após várias tentativas
**Complexidade:** alta — envolve tamanho/empacotamento dos cores em `:bundled-cores` (`libmamearcade_libretro_android.so` de 168,9 MiB) e ativos em `lemuroid-app/src/main/assets/`, ou investigação de rejeição do `PackageInstaller` em Fire OS / Android TV
**Branch:** version10
**Origem:** triagem dos chamados #344 (sessão `159056077574279@lid`) e #397 (sessão `50380083839110@lid`)

---

## Sintoma

Dois clientes relataram falha ao instalar o APK distribuído para TV (`retro-game-system-armv7.apk`, código `2835763` do Downloader e URL direta):

1. **Chamado #344 (2026-11-08, Fire TV Stick Lite):** baixou pelo Downloader (`2835763`); ao instalar, a tela do sistema exibiu `"O aplicativo não foi instalado."`. Persistiu mesmo após limpar cache/dados do Downloader e conferir instalação anterior.
2. **Chamado #397 (2026-11-17, Amazon Fire TV Stick + Smart TV TCL Android):**
   - **Fire TV Stick** (com **1,8 GB livres** em Configurações > Meu Fire TV > Sobre > Armazenamento e permissão de apps desconhecidos ativada para o Downloader): baixou `retro-game-system-armv7.apk` (`158,5 MB`, provavelmente `1.17.35`), abriu o instalador (`CANCELAR` / `INSTALAR`), a barra azul foi até o fim e exibiu apenas tela branca com `"CONCLUÍDO"` (sem botão `"ABRIR"` e sem mensagem de erro visível); o app não aparece em *Gerenciar aplicativos instalados*. O mesmo arquivo instalou normalmente no celular Android do cliente.
   - **Smart TV TCL Android:** instalou o Downloader, baixou via código `2835763` (`armv7`), depois testou `retro-game-system-arm64.apk` e também o link direto `armv7` pelo navegador da TV — em todas as tentativas exibiu `"O app não foi instalado."` (`"Retro Game System"`, `"CONCLUÍDO"`).

---

## Evidência

### Conversas (`Wpp_proccess`, UTC)

- **#344 (`159056077574279@lid`, Fire TV Stick Lite):**
  - `[172688]` 2026-11-08 15:46 customer: *"Ele pega no fire tv stick lait"*
  - `[172748]` 16:14 customer: print *"Retro Game System — O aplicativo não foi instalado."* (após código `2835763` no Downloader)
  - `[172772]` 16:31 customer: *"E o com o código ele não estala"*
  - `[172774]` 16:33 customer: *"Ele baixa mais na hora de instalar não instalar"*
  - `[172796]` 16:37, `[172824]` 16:45 customer: novos prints de *"O aplicativo não foi instalado."* após verificar apps instalados e limpar cache/dados do Downloader (`[172829]`, `[172831]` *"Mais não instalar"*).
- **#397 (`50380083839110@lid`, Fire TV Stick + TCL Android TV):**
  - `[186331]` 2026-11-17 14:39 customer: print de *Gerenciar aplicativos instalados* sem o Retro Game System.
  - `[186350]` 14:52 customer: *"Depois que cliquei em instalar, a barrinha dele vai até o final e depois mostra essa tela de concluído, mas não mostra tecla abrir"* (print `[186347]`: tela branca com `"Retro Game System"` e `"CONCLUÍDO"`).
  - `[186527]` 17:34 customer (na **TCL Android**): print *"O app não foi instalado."* (`"Retro Game System"`, `"CONCLUÍDO"`) com código `2835763`.
  - `[186535]` 17:54 customer (na **TCL Android**, após testar `retro-game-system-arm64.apk`): *"Dá a mesma msg que deu com o codigo"*.
  - `[186537]` 17:55 customer (na **TCL Android**, link direto `armv7` no navegador): *"App não foi instalado"*.
  - `[186658]` 18:38 customer (Fire Stick): print do Downloader/Files mostrando `/sdcard/Download/Downloader/retro-game-system-armv7.apk`, `17 de nov. de 2026 15:12, 158,5 MB`.
  - `[186667]` 18:39 customer: tela do instalador com `"Retro Game System"`, `"CANCELAR"`, `"INSTALAR"`.
  - `[186682]` 18:41 customer: *"A linha azul de instalando chega ao final e fica apenas a tecla concluído"* (print `[186684]` tela branca com `"CONCLUÍDO"`).
  - `[186767]` 19:11 customer: *"Tenho 1,8 gb livre"* (e `[186547]` *"O downloader está ativo sim"*).
  - `[186651]` 18:36 customer: *"Sim eu até já instalei no celular"* (confirma que o app instala no celular).

### Inspeção do APK publicado e do código-fonte

Conferido localmente em `dist/retro-game-system-armv7.apk` (`1.17.36`, `versionCode 267`, `163.525.849` bytes — o de `1.17.35` no print `[186658]` tinha `158,5 MB`):

- **Splits ABI e assinatura:** `aapt2 dump badging` confirma `native-code: 'armeabi-v7a'`, `package: name='app.retrogamesystem'`, `minSdkVersion:'21'`, `targetSdkVersion:'35'`, `uses-feature` todos `required="false"` (apenas OpenGL ES 2.0). `apksigner verify --verbose` verifica OK em v1, v2 e v3 (assinado com `debug.keystore` conforme `lemuroid-app/build.gradle.kts`). Logo, a regressão anterior de APK universal/debug descrita em `docs/bugs/no-ar/apk-nao-instala-smarttv-tcl-splits-abi/apk-nao-instala-smarttv-tcl-splits-abi.md` continua corrigida no build.
- **Crescimento expressivo do footprint do APK `armv7` desde jul/2026 (`90,3 MB` APK / `~176 MB` `.so` → `158,5–163,5 MB` APK / `350,62 MiB` `.so`):**
  - `lib/armeabi-v7a/` soma **350,62 MiB** descomprimidos (com `android:extractNativeLibs="true"` em `lemuroid-app/src/main/AndroidManifest.xml`), puxado sobretudo por **`libmamearcade_libretro_android.so`** (**177.130.180 bytes = 168,92 MiB descomprimidos / 54,87 MiB comprimidos**). Embora o commit `b631880` (2026-10-16) descreva o MAME 0.289 como *on-demand*, a variante `freeBundle` (`bundleImplementation(project(":bundled-cores"))`) e a task `materializeNativeLibs` em `lemuroid-cores/bundled-cores/build.gradle.kts` empacotam todos os `lemuroid_core_*` dentro do APK.
  - `assets/` soma **97,43 MiB** descomprimidos (`23,01 MiB` comprimidos): `retrograde-prebuilt.db` (`45,25 MiB`), `catalog_manifest.txt` (`9,55 MiB`) e **4 backups não rastreados** (`catalog_manifest.txt.bak_20261004_*` e `20261014_*`) presentes em `lemuroid-app/src/main/assets/` somando **31,7 MB** descomprimidos.
  - Stubs de symlink em `lib/armeabi-v7a/`: `libcitra_libretro_android.so` (92 B) e `libcitra_legacy_libretro_android.so` (106 B) estão presentes como texto de symlink dentro do APK `armv7` (3DS é `arm64-v8a` apenas).
- **Telemetria (`errors`):** últimas entradas em `Amazon AFTKRT` chegaram até `1.17.25` (2026-11-04) e em `TCL Smart TV Pro` (`armeabi-v7a`) até `1.17.28` (2026-11-11); ainda não há registros de `1.17.34`–`1.17.36` nesses dispositivos.

---

## Hipóteses — nenhuma verificada

Sem `adb logcat` / `adb install` em um Fire TV Stick (Fire OS) ou na TCL Android TV afetada, não há confirmação do código `INSTALL_FAILED_*` retornado pelo `PackageManager`:

1. **Armazenamento insuficiente (`INSTALL_FAILED_INSUFFICIENT_STORAGE`):** a soma do APK (`~156 MiB`), staging do `PackageInstaller` (`~156 MiB`), bibliotecas extraídas (`350,62 MiB`) e dex/oat ultrapassa ~670 MB durante a instalação, além do limite mínimo reservado pelo Android/Fire OS (~10% ou ~500 MB de `/data` em TVs de 8 GB). Explica diretamente a TCL Android TV de 8 GB (ver `docs/bugs/no-ar/apk-nao-instala-smarttv-tcl-splits-abi/apk-nao-instala-smarttv-tcl-splits-abi.md`) e o Fire TV Stick Lite (`#344`). Porém, no `#397` o cliente relatou `1,8 GB` livres no Fire Stick (`[186767]`), o que deixa em aberto se a UI do Fire OS inclui partição/cache diferente do `/data` checado pelo `PackageManager` ou se há outra causa no Fire OS.
2. **Rejeição específica do `PackageInstaller` do Fire OS / TV:** verificar se algum elemento do `AndroidManifest.xml` (`<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">`, `REQUEST_INSTALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`), a assinatura `debug.keystore` com `enableV3Signing = true`, ou os stubs de 92–106 bytes em `lib/armeabi-v7a/libcitra_*.so` causam falha silenciosa em Fire OS (`CONCLUÍDO` sem `ABRIR`).

---

## O que fazer

1. Testar `adb install dist/retro-game-system-armv7.apk` e instalação via UI/Downloader em um Fire TV Stick (ou Fire OS) e em uma Android TV 8 GB capturando `adb logcat` (`PackageManager`, `PackageInstaller`, `NativeLibraryHelper`) para registrar o código exato de falha.
2. Remover os arquivos `lemuroid-app/src/main/assets/catalog_manifest.txt.bak_*` (`31,7 MB` indevidos no APK) e avaliar tirar `libmamearcade_libretro_android.so` (`168,9 MiB` em `armeabi-v7a`) do bundle padrão (ou ao menos do split `armv7`), baixando-o sob demanda via `CoreDownloader`.
