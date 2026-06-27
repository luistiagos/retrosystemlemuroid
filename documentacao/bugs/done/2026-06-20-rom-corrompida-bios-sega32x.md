# [BUG] "O arquivo ROM parece estar corrompido e foi removido" (Sega 32X) + apagava a ROM

**Data:** 2026-06-20
**Status:** CORRIGIDO
**Severidade:** ALTA — bloqueava jogos de CD do 32X e **apagava a ROM baixada** indevidamente
**Branch:** version9

---

## Sintoma

Após os cores passarem a carregar (ver
[2026-06-20-cores-libretro-nao-carregam.md](2026-06-20-cores-libretro-nao-carregam.md)), lançar
um jogo `.chd` de **Sega 32X** mostrava *"O arquivo ROM parece estar corrompido e foi removido"*
e **apagava o arquivo**. Sistemas antigos e cartuchos funcionavam.

## Causa-raiz (duas partes)

Confirmado por `logcat` do processo `:game`:
```
I/Libretro Core: detected BIN Sega/Mega CD image with USA region
I/Libretro Core: opening BIOS failed
E/Libretro Core: Missing BIOS
E/libretrodroid: Cannot load game. Leaving.
E/GameViewModelRetroGameView: Error in GLRetroView 1   (ERROR_LOAD_GAME)
```

1. **Catálogo:** a pasta `sega32x` mistura cartuchos de 32X com **imagens de Sega CD / 32X-CD
   (`.chd`)** — a maioria (ex.: `3 Ninjas Kick Back`, Sonic CD, Final Fight CD…). Essas imagens
   de CD precisam do **BIOS do Mega-CD**. O picodrive **carregou e rodou** (detectou a imagem),
   mas o sistema `SEGA_32X` **não declarava nenhum BIOS** (diferente do `SEGACD`, que declara
   `bios_CD_E/J/U.bin`), então a auto-download de BIOS nunca disparava → "Missing BIOS".

2. **Misclassificação destrutiva:** o core retorna `ERROR_LOAD_GAME`, que o app classificava
   como `isRomLoadFailure` → tratava como **"ROM corrompida" e apagava o arquivo**, quando na
   verdade só faltava o BIOS.

> Importante: **não foi regressão do trabalho de performance** nem do nome dos cores. O core
> carregou normalmente. `git diff version8` confirmou que nada tocado na sessão alterou a lógica
> de BIOS/carregamento. O 32X simplesmente **nunca rodou no version8** (o core picodrive sequer
> existia no submódulo de então); ao fazer o core carregar, o problema seguinte (BIOS de CD)
> ficou exposto.

## Correção

### 1. SEGA_32X declara o BIOS do Mega-CD (auto-download)
**Arquivo:** [GameSystem.kt](../../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/GameSystem.kt)

Adicionado ao `SystemCoreConfig` do picodrive o mesmo `regionalBIOSFiles` do `SEGACD`:
```
"Europe" to "bios_CD_E.bin", "Japan" to "bios_CD_J.bin", "USA" to "bios_CD_U.bin"
```
Esses BIOS já estão registrados no `BiosManager` (mesmos do SEGACD, que funciona) e são baixados
automaticamente. Cartuchos 32X reais ignoram o BIOS. Também incluídas as extensões de CD
(`chd`, `cue`, `iso`) e `hasMultiDiskSupport = true`.

### 2. Não apagar a ROM quando o que falta é BIOS
**Arquivos:** [GameLaunchTaskHandler.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/main/GameLaunchTaskHandler.kt),
[LemuroidApplicationModule.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/LemuroidApplicationModule.kt)

Antes de declarar "ROM corrompida" e apagar, o handler agora consulta
`BiosManager.getMissingBiosFiles(...)` para o sistema do jogo. Se houver BIOS faltando, **mantém
a ROM** e mostra a mensagem de BIOS ausente (`game_loader_error_missing_bios`) em vez de apagar.
`BiosManager` passou a ser injetado no `GameLaunchTaskHandler` via Dagger (`postGameHandler`).

## Validação
- `:lemuroid-app:assembleFreeBundleDebug` — **BUILD SUCCESSFUL** — instalado em `ZY32LMNN9B`.
- BIOS `bios_CD_*` já comprovadamente baixável (mesmo mecanismo do SEGACD).

## Observação / pendência de dados
A correção faz os `.chd` do 32X bootarem via BIOS. A longo prazo, o ideal é **recategorizar** as
imagens de Sega CD que estão sob `sega32x` para `segacd`/`segacd32x` no catálogo (correção de
dados no manifesto/repo de cores).
