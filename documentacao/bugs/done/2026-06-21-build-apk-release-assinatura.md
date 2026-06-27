# [BUG] build_apk.ps1 (release) falhando — JAVA_HOME e assinatura

**Data:** 2026-06-21
**Status:** CORRIGIDO
**Severidade:** MÉDIA — bloqueava a geração do APK de release

---

## Sintoma

Rodar `.\build_apk.ps1` (default = `:lemuroid-app:assembleFreeBundleRelease`) falhava.

## Causa-raiz (duas falhas)

1. **`JAVA_HOME` no bootstrap do gradlew** — igual ao `build_and_install_connected.ps1`: o
   script chamava `gradlew.bat` sem definir `JAVA_HOME`, e o wrapper precisa do Java antes de
   ler o `gradle.properties`. Falhava com *"JAVA_HOME is not set"*.

2. **Keystore de release ausente** — após o fix de Java, o build falhava em
   `:lemuroid-app:validateSigningFreeBundleRelease`. O `build.gradle.kts` apontava o
   `signingConfig` de release para `$rootDir/release.jks`, que **não existe** (está no
   `.gitignore`, nunca foi versionado). O `debug.keystore` existia (por isso builds debug
   funcionavam).

## Correção

**Arquivos:** [build_apk.ps1](../../../build_apk.ps1), [lemuroid-app/build.gradle.kts](../../../lemuroid-app/build.gradle.kts)

1. Adicionada a função **`Resolve-JavaHome`** ao `build_apk.ps1` (lê `org.gradle.java.home` do
   `gradle.properties`, com fallback para o JBR do Android Studio e o PATH) e definição de
   `JAVA_HOME` antes do build — mesmo padrão do `build_and_install_connected.ps1`.

2. **Assinatura (decisão do usuário): usar a chave debug no release.** O `signingConfig`
   `release` passou a apontar para `$rootDir/debug.keystore` (alias `androiddebugkey`, senha
   `android`, validado no keystore — válido até 2045). Sem conflito de atualização porque o
   release tem `applicationId` próprio (`app.retrogamesystem`, sem o sufixo `.debug`).
   Comentário no `build.gradle.kts` indica como voltar a um `release.jks` dedicado no futuro.

## Validação
- `:lemuroid-app:assembleFreeBundleRelease` (R8/minify) — **BUILD SUCCESSFUL**.
- APK gerado: `dist/retro-game-system.apk` (~306 MB), **assinado**.
- `assets/retrograde-prebuilt.db` empacotado (32,5 MB → 9,2 MB comprimido); 51 cores arm64.

## Observação (fora do escopo da falha)
O APK universal tem ~306 MB por empacotar **51 cores × 4 ABIs** (arm64-v8a, armeabi-v7a, x86,
x86_64) sem filtro. Reduzir para `arm64-v8a + armeabi-v7a` cortaria ~metade sem afetar
celulares/TV box/Smart TV (todos ARM); só perderia o emulador de PC (x86_64). **Adiado por
decisão do usuário** — APK mantido com as 4 ABIs por ora.
