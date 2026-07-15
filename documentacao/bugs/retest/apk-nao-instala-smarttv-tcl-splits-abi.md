# APK não instala em Smart TV TCL — splits por ABI (2026-07-14)

## Sintoma

Sideload do `dist\retro-game-system.apk` via pendrive numa Smart TV TCL Android TV
(1 GB RAM, **8 GB ROM**, MediaTek MT9221, armeabi-v7a) falha com **"O app não foi
instalado"**. Mesmo sintoma do caso Motorola em
[correcoes-2026-05-28.md](../done/correcoes-2026-05-28.md), mas causa diferente
(lá era conflito de assinatura/applicationId; aqui não — o pacote `app.retrogamesystem`
é próprio e a TV nunca teve o app).

## Causa raiz (dupla)

1. **`dist\retro-game-system.apk` era byte-a-byte a build DEBUG** (356,6 MB,
   não minificada, `app.retrogamesystem.debug`, MD5 idêntico ao
   `freeBundle\debug\lemuroid-app-free-bundle-debug.apk`). Culpado: o
   `build_apk.ps1` localizava "o APK mais recente" em `build\outputs\apk`
   (recursivo, sem filtrar build type) e copiava para `dist\` — uma rodada de
   `-Debug` depois do release envenenou a distribuição.
2. **APK universal com 4 ABIs + `extractNativeLibs=true`**: ~926 MB de `.so`
   descomprimidos dentro do APK; a instalação copia o APK inteiro para `/data/app`
   **e** extrai ~176 MB de `.so` armeabi-v7a. Footprint de instalação ~530 MB numa
   TV com 8 GB de ROM (4-5 GB utilizáveis) → `INSTALL_FAILED_INSUFFICIENT_STORAGE`,
   que a UI da TV mostra como "O app não foi instalado".

Descartado durante o diagnóstico: ABI (armeabi-v7a estava no APK), minSdk (21),
`uses-feature` (nenhum `required="true"` além de GLES 2.0).

## Correção aplicada

1. **Splits por ABI** em `lemuroid-app\build.gradle.kts` (`splits { abi { ... } }`):
   um APK por ABI, só `arm64-v8a` + `armeabi-v7a`, sem universal. x86/x86_64 fora
   dos artefatos (emulador x86 deixa de instalar — adicionar ao `include` se precisar).
2. **`build_apk.ps1`**: localiza os APKs no diretório da variante buildada (não mais
   "o mais recente" recursivo); valida o prebuilt DB em cada split; **só copia para
   `dist\` em build release** (debug loga warning e não toca `dist\`); nomes
   `retro-game-system-arm64.apk` / `retro-game-system-armv7.apk` (+ sufixo de channel);
   `-Install` detecta o ABI do device via `adb shell getprop ro.product.cpu.abi` e
   instala o split correto.
3. **`release.ps1`**: mesmo esquema de cópia por ABI para `dist\`.
4. **`AppUpdateManager.kt`** (`fetchVersionInfo`): suporta campo novo `apkUrls`
   (mapa ABI→URL) no JSON do `app_version`, escolhido via `Build.SUPPORTED_ABIS`
   (ordem de preferência do device); fallback retrocompatível no `apkUrl` legado.
   Sem isso, uma TV armv7 baixaria o APK arm64 e falharia em loop silencioso
   (`INSTALL_FAILED_NO_MATCHING_ABIS` via `UpdateInstallReceiver`).
5. Apagado o artefato envenenado `dist\retro-game-system.apk`.

**Decisão consciente — NÃO mudar `useLegacyPackaging`/`extractNativeLibs`**:
`GameLoader.findLibrary` (`retrograde-app-shared\...\lib\game\GameLoader.kt:204-241`)
exige os `.so` dos cores extraídos como arquivo físico em `nativeLibraryDir` (dlopen
por path absoluto). Com `extractNativeLibs=false` nenhum core carrega no flavor
bundle. Ganho (~90 MB) não justifica refatorar o carregamento de cores agora.

## Status (2026-07-15)

Todo o código do fix (splits ABI, `build_apk.ps1`, `release.ps1`,
`AppUpdateManager.kt`, `build-and-upload.ps1`) foi implementado e validado
localmente. Uploads de distribuição já feitos. **Falta apenas o passo manual
do servidor** (seção abaixo) e a validação em device físico.

- **Splits + scripts**: verificado via `aapt dump badging` — `dist\retro-game-system-arm64.apk`
  e `dist\retro-game-system-armv7.apk` têm `native-code` com UMA ABI cada,
  `package name='app.retrogamesystem'` (sem `.debug`), assinados com o cert de
  release (`CN=Lemuroid`), `assets/retrograde-prebuilt.db` presente nos dois.
  versionCode 231 / versionName 1.17.0. Tamanhos: 105.9 MB (arm64) / 90.3 MB (armv7).
- **`build_apk.ps1 -Debug`**: testado — imprime o warning "Build DEBUG:
  artefatos NAO copiados para dist\" e os timestamps de `dist\*.apk` ficam
  inalterados (confirmado byte-a-byte antes/depois).
- **Upload HuggingFace** (`luisluis123/versions`, pasta `RetroGameSystem/`,
  nomes versionados `retro-game-system-1.17.0-{arm64,armv7}.apk`): feito,
  URLs `resolve/main` respondem 206.
- **Upload R2** (bucket `versions`, pasta `RetroGameSystem/`, nomes estáveis
  `retro-game-system-{arm64,armv7}.apk`): feito em 2026-07-14, bytes no
  bucket idênticos aos locais (110.995.731 / 94.641.603).
- **`build-and-upload.ps1`**: estendido para imprimir, no resumo final, o
  JSON pronto do `app_version` (usa `$cfg.publicBaseUrl` / `R2_PUBLIC_URL`
  para montar as URLs — sem isso, imprime aviso pedindo para configurar).

## Bloqueio atual — falta domínio público no R2

O endpoint `https://emuladores.pythonanywhere.com/app_version` retorna
**404** (JSON nunca foi publicado) e o bucket R2 `versions` não tem acesso
público habilitado ainda (`R2_PUBLIC_URL` vazio no `build.properties`;
`dl.digitalstoregames.com` não resolve). Decisão: usar URLs do R2 público
(nomes estáveis, egress grátis) em vez das URLs versionadas do HuggingFace.

## Passo manual pendente (servidor)

1. **Cloudflare**: habilitar acesso público ao bucket `versions` (subdomínio
   `r2.dev` ou domínio custom) e preencher `R2_PUBLIC_URL=<url>` em
   `build.properties`.
2. Rodar `.\build-and-upload.ps1` (ou `-SkipBuild -SkipHF -SkipR2` se só
   quiser reimprimir o resumo com APKs já enviados) e copiar o JSON impresso
   no resumo final.
3. Publicar esse JSON na rota `/app_version` do pythonanywhere (código do
   servidor não está neste repo).

```json
{
  "versionCode": 231,
  "versionName": "1.17.0",
  "channel": "default",
  "apkUrl": "https://.../retro-game-system-arm64.apk",
  "apkUrls": {
    "arm64-v8a": "https://.../retro-game-system-arm64.apk",
    "armeabi-v7a": "https://.../retro-game-system-armv7.apk"
  }
}
```

`apkUrl` (legado) aponta para o arm64 (maioria dos devices em campo).

## Validação para mover a done/

1. ✅ `.\build_apk.ps1` → 2 APKs em `dist\` (`-arm64` e `-armv7`, ~100-160 MB cada);
   `aapt dump badging` mostra `native-code` com UMA ABI, `package name='app.retrogamesystem'`
   (sem `.debug`), sem `application-debuggable`.
2. ✅ `.\build_apk.ps1 -Debug` → `dist\` intocado (warning no console).
3. ⬜ **TV TCL**: desinstalar o app debug atual (libera ~530 MB), sideload do
   `retro-game-system-armv7.apk` via pendrive, abrir um jogo leve (smoke test do
   core armv7).
4. ⬜ Celular arm64: instalar `retro-game-system-arm64.apk`, abrir um jogo.
5. ⬜ Updater: após publicar JSON+APKs no servidor (passo manual acima), checar
   update nos dois devices.
