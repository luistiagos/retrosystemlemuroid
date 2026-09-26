# [BUG] 17 cores embutidos no APK sem prefixo `lib` não são extraídos no build de release e são baixados de novo

**Data:** 2026-09-25
**Status:** ✅ **Resolvido** em 2026-09-25. Reproduzido e validado no AVD Android 7.1: extração e
jogo aberto sem rede, antes e depois.
**Severidade:** Alta. Em 17 sistemas, todo usuário baixava o core no primeiro jogo, **sem rede o
jogo não abria**, embora o core estivesse dentro do APK, e o APK carregava ~49 MB mortos por ABI.
**Branch:** version9 (Lemuroid) · `main` / tag `1.20.0` (lemuroid-cores)
**Origem:** investigação do working tree em 2026-09-25

---

## Sintoma

O `CoreID.kt` usava nome **sem** o prefixo `lib` para 17 cores, e o `bundled-cores` os empacotava
com esse nome:

| Core | Sistema | | Core | Sistema |
|------|---------|-|------|---------|
| `dolphin` | GameCube | | `opera` | 3DO |
| `yabasanshiro` | Saturn | | `picodrive` | 32X |
| `puae` | Amiga | | `atari800` | Atari 800 |
| `hatari` | Atari ST | | `neocd` | Neo Geo CD |
| `mednafen_pcfx` | PC-FX | | `virtualjaguar` | Jaguar |
| `o2em` | Odyssey² | | `gw` | Game & Watch |
| `sameduck` | Mega Duck | | `freechaf` | Channel F |
| `uzem` | Uzebox | | `lowresnx` | LowRes NX |
| `arduous` | Arduboy | | | |

No APK arm64 eles somam ~49 MB descompactados (dolphin 13,6 MB, puae 20 MB, …). Os outros 34
cores têm prefixo `lib` e não eram afetados.

## Causa-raiz

O instalador do Android só extrai de `lib/<abi>/` para o `nativeLibraryDir` as entradas cujo
nome começa com `lib` e termina com `.so`. Isso foi lido no fonte do
`com_android_internal_content_NativeLibraryHelper.cpp`, `NativeLibrariesIterator::next()`:

- **Android 7.1** (`android-7.1.2_r39`): filtro **sempre** ativo.
  ```cpp
  // Make sure the filename starts with lib and ends with ".so".
  if (strncmp(fileName + fileNameLen - LIB_SUFFIX_LEN, LIB_SUFFIX, LIB_SUFFIX_LEN)
      || strncmp(lastSlash, LIB_PREFIX, LIB_PREFIX_LEN)) {   // LIB_PREFIX = "/lib"
      continue;
  }
  ```
- **Android 13** (`android13-release`): o mesmo filtro, mas dentro de `if (!mDebuggable)`.
  **Só APK debuggable** extrai arquivo sem prefixo `lib`.

O app usa `extractNativeLibs="true"` / `useLegacyPackaging = true`, e o `GameLoader.findLibrary`
procura o core em `File(nativeLibraryDir, libretroFileName)` e depois no `filesDir`. Não há
caminho que leia o `.so` de dentro do APK. No release, então:

1. `findLibrary` não achava o core e devolvia `null`, e o `GameLoader.load` lançava
   `GameLoaderError.LoadCore`.
2. O `GameViewModelRetroGameView` pegava o `LoadCore`, mostrava "baixando core" e chamava
   `CoreDownloader.downloadCore`, que baixava de
   `raw.githubusercontent.com/luistiagos/libretrocores/1.19.0/lemuroid_core_<core>/src/main/jniLibs/<abi>/<arquivo>`.
3. Se o download falhava (sem rede, TV box offline, GitHub fora do ar), o jogo terminava em
   `requestFailureFinish(LoadCore)`.

A cópia embutida nunca era usada.

A origem do nome errado é documental: o `CoreID.kt` tinha, em 15 dos 17 cores, o comentário
"Buildbot Android nightlies ship this core WITHOUT the "lib" prefix. Copy the file name
literally.", e o guia `documentacao/prompts/adicionar-novo-sistema-libretro.md` mandava "use o
nome literal do arquivo que veio do build". Cada core novo sem `lib` repetia o erro por instrução.

## Por que ninguém viu

**O teste óbvio mente.** Nesta investigação, o `nativeLibraryDir` do `app.retrogamesystem.debug`
no SM-A127M (Android 13) **tinha** `dolphin_…`, `puae_…` e `yabasanshiro_…` extraídos. Isso
parecia descartar o problema, mas o build debug é *debuggable*, e o Android 13 pula o filtro
justamente nesse caso. Todo teste feito com o build de desenvolvimento escondia o bug.

E, com rede, o sintoma para o usuário era só um "baixando core" na primeira vez: parecia
comportamento previsto. Na reprodução, o app pré-correção chegou a baixar sozinho 13 dos 17 cores
(~45 MB) para `files/cores/1.19.0/` logo depois de instalado.

## Correção

1. **Rename dos `.so`** no `lemuroid-cores` (commit `0efcc7a`, 132 `git mv`, conteúdo idêntico):
   `<nome>_libretro_android.so` → `lib<nome>_libretro_android.so` no `bundled-cores` e em cada
   `lemuroid_core_<nome>`, em todas as ABIs.
2. **`CoreID.kt`**: os 17 nomes com `lib`. Os comentários "copy the file name literally" foram
   removidos e trocados por um comentário único na propriedade `libretroFileName`, explicando por
   que o prefixo é obrigatório.
3. **Download coordenado com tag nova**: tag `1.20.0` criada no `luistiagos/libretrocores`
   (aponta para `0efcc7a`) e `CoreDownloader.CORES_VERSION` = `"1.20.0"`. A `1.19.0` ficou
   intocada, então versões antigas do app continuam baixando os nomes antigos. O
   `CoreUpdaterImpl` lê a mesma constante.
4. **Trava de build** `verifyBundledCores`
   ([BundledCoresVerifier.kt](../../../buildSrc/src/main/kotlin/BundledCoresVerifier.kt), registrada
   em [lemuroid-app/build.gradle.kts](../../../lemuroid-app/build.gradle.kts)), em todo variant:
   falha se algum `.so` em `jniLibs` do `lemuroid-cores` ou do próprio `lemuroid-app` não começar
   com `lib`, ou se os nomes do
   `CoreID.kt` e de `bundled-cores/src/main/jniLibs/arm64-v8a/` não forem o mesmo conjunto (e as
   demais ABIs não forem subconjunto dele). Renomear de um lado só voltaria ao download silencioso.
5. Na trava de release `verifyCoresPublished` (ver
   [[2026-09-25-lemuroid-cores-submodulo-divergente-commit-nao-publicado]]): a tag
   `CORES_VERSION` tem que conter `lemuroid_core_<coreName>/…/arm64-v8a/<libretroFileName>` de todo
   `CoreID`. Isso pega "renomeou e esqueceu de criar a tag".
6. Guia de novo sistema e Pitfall 14 do [CLAUDE.md](../../../CLAUDE.md) reescritos: o `.so` é
   **renomeado** para `lib…` ao copiar do buildbot.

Cores já baixados em `files/cores/1.19.0/` com o nome antigo ficam órfãos no aparelho de quem os
baixou. Inofensivo (o `GameLoader` passa a achar o embutido primeiro), mas ocupa até ~45 MB.

## Validação

Feita no AVD `lemu_api25_2gb` (Android 7.1.1, x86_64), onde o filtro do instalador vale **até
para APK debug**. É a mesma condição de um release em qualquer Android. APK
`assembleFreeBundleDebug -PdevAbi=x86_64`.

| | Antes (nomes antigos) | Depois |
|---|---|---|
| `.so` de core no APK (`lib/x86_64/`) | 51 (17 sem `lib`) | 51 (0 sem `lib`) |
| Extraídos no `nativeLibraryDir` | **34**: faltam exatamente os 17 | **51** |
| Jogo de LowRes NX, **sem rede** (`Active default network: none`), sem cache em `files/cores` | `GameLoader: Error while preparing game` → `CoreDownloader … 1.19.0/…/lowresnx_libretro_android.so` → 10 tentativas (`Unable to resolve host`) → `Direct core download failed` ~70 s depois | `Setting state to loaded` em <1 s, core rodando (`VIDEOFRAMES … size=160x128`), nenhuma linha do `CoreDownloader`, `files/cores` nem criado |

O jogo de teste foi um programa `.nx` de 4 linhas escrito para o teste (BASIC em texto puro),
colocado no caminho de um item do catálogo de LowRes NX e aberto pelo deep link
`retrogamesystem://<appId>/play-game/id/<id>`. Removido depois.

- **Release**: `assembleFreeBundleRelease -PdevAbi=x86_64` (com R8, `verifyBundledCores` e
  `verifyCoresPublished` rodando dentro do build) instalado no mesmo AVD como
  `app.retrogamesystem` (`pkgFlags` sem `DEBUGGABLE`): 51 cores no APK, **51 extraídos**.
- Tag remota: `raw.githubusercontent.com/luistiagos/libretrocores/1.20.0/…/libdolphin_libretro_android.so`,
  `liblowresnx…`, `libyabasanshiro…` (armv7) e `libsnes9x…` → **200**; o nome antigo na `1.20.0` →
  404; o nome antigo na `1.19.0` → 200 (versões antigas do app não quebram).
- Travas: `verifyBundledCores` passa. Com um `foo_libretro_android.so` solto no
  `bundled-cores/…/arm64-v8a`, falha com "not named lib*.so" e "no CoreID uses this file name". A
  igualdade de conjunto com os 51 arquivos do arm64 prova que o parser do `CoreID.kt` leu as 51
  entradas.
- `ktlintCheck` de `:lemuroid-app` e `:retrograde-app-shared` passa.

**Não testado:** jogo dos outros 16 cores em aparelho. O mecanismo é o mesmo para os 17 (nome do
arquivo × filtro do instalador), e a extração 51/51 cobre todos.

## Lição

Build debug e build release instalam de forma diferente. No Android 13+, o instalador trata APK
*debuggable* de modo mais permissivo. Validação de empacotamento (o que é extraído, o que o
instalador aceita) tem que ser feita no artefato que o usuário recebe, ou num Android ≤ 12, onde
o filtro vale até para debug.

E a causa não estava no código: estava num comentário e num guia que mandavam **copiar o nome
literal**. Instrução errada em doc se replica a cada core novo. Por isso a regra agora é uma trava
de build, não um parágrafo.
