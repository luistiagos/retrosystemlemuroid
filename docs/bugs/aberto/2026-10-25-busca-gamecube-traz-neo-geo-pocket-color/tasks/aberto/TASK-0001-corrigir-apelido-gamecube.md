---
tipo: implementacao
demandas: [2026-10-25-busca-gamecube-traz-neo-geo-pocket-color]
depende_de: []
---
# Busca de jogos instalados: apelido "gamecube" aponta para o GameCube (gc), não para o Neo Geo Pocket Color (ngc)

## Objetivo

Buscar `gamecube` (ou `game cube`) nas telas de jogos instalados/salvos (celular, rota `INSTALLED`; TV,
`TVSavedGamesFragment`) tem de listar os jogos de **GameCube** (`SystemID.GAMECUBE`, dbname `gc`), nunca os de
**Neo Geo Pocket Color** (`SystemID.NGC`, dbname `ngc`). Causa e rastreio na seção "Planejamento" do doc da demanda.

## O que mudar

`lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/search/SystemSearchResolver.kt`:

1. `commonAliases`: `"gamecube" to listOf("gc")` (era `ngc`) e acrescentar `"game cube" to listOf("gc")`. **Não**
   mapear `ngc` para o GameCube: o laço de `GameSystem.all()` já resolve `ngc` para o NGPC, que é o certo.
2. Tornar a leitura de string injetável para o teste rodar na JVM (o módulo só tem JUnit; `Context.getString` é
   `final` e nos stubs lança, e o `runCatching` zeraria o título):
   ```kotlin
   class SystemSearchResolver(private val stringOf: (Int) -> String) {
       constructor(context: Context) : this({ id -> context.getString(id) })
   ```
   e no laço trocar `context.getString(...)` por `stringOf(...)`, mantendo o `runCatching`. Os três call-sites
   (`MainActivity.kt:288`, `TVSavedGamesFragment.kt:61`, via `InstalledGamesViewModel`) usam o construtor com
   `Context` e não mudam.

Comentário no código citando a demanda `2026-10-25-busca-gamecube-traz-neo-geo-pocket-color` ao lado do apelido.

## Teste que prova

Novo `lemuroid-app/src/test/java/com/swordfish/lemuroid/app/shared/SystemSearchResolverTest.kt` (JUnit 4; pacote `com.swordfish.lemuroid.app.shared`, importando o resolver de `.search` -- a pasta `search/` não existe em `src/test` e a reserva da onda só aceita pasta existente).
`stringOf` falso: um mapa com `com.swordfish.lemuroid.lib.R.string.game_system_title_gc` → "Nintendo GameCube",
`game_system_abbr_gc` → "GC", `game_system_title_ngc` → "Neo Geo Pocket Color", `game_system_abbr_ngc` → "NGC";
qualquer outro id lança (o `runCatching` do resolver o trata como vazio). Asserções (comparar como conjunto):

- `gamecube` → `{gc}` (sem o fix: `{ngc}` — o teste tem de falhar na base);
- `game cube` → `{gc}`;
- `nintendo gamecube` → `{gc}` (sem o fix: `{ngc, gc}`);
- `ngc` → `{ngc}`;
- `gc` → `{gc}`.

Se o `R` da lib não for alcançável no teste, usar `GameSystem.findById(SystemID.GAMECUBE.dbname).titleResId` etc.
(`GameSystem` roda na JVM: ver `GameSystemSingleScreenTest`).

Estilo: `gradlew :lemuroid-app:ktlintCheck` sem apontamento nos dois arquivos (o resolver não tem passivo no
baseline).

## Plano de teste

- **Offline:** o teste acima no `build` do helper; linha de base sem falha nova.
- **T2 (aparelho, pela UI):** app debug com um jogo de **Neo Geo Pocket Color** baixado (catálogo ▸ Neo Geo Pocket
  Color ▸ um jogo pequeno ▸ baixar) e, se der, um de GameCube instalado. Tela de jogos instalados ▸ busca ▸
  `gamecube`. Print. Base (controle positivo): o jogo de NGPC aparece. Fix: o NGPC **não** aparece (o GC aparece, se
  houver). Repetir `nintendo gamecube` (fix: só GC) e `ngc` (fix: NGPC aparece).
- **T3:** na mesma tela, `snes`, `neo geo` e o título de um jogo instalado continuam achando o que achavam.

## Correcao (pipeline 20261114-1244, rodada 1)

- `SystemSearchResolver.kt::commonAliases`: `"gamecube"` → `gc` (era `ngc`, o Neo Geo Pocket Color) e novo `"game cube"` → `gc`.
- `SystemSearchResolver` passa a receber `stringOf: (Int) -> String`; construtor secundário com `Context` mantém os três call-sites intactos. `runCatching` preservado.
- Teste novo `SystemSearchResolverTest` (2 testes, ids via `GameSystem.findById(...).titleResId/shortTitleResId`): `gamecube`/`game cube`/`nintendo gamecube` → `{gc}`; `ngc` → `{ngc}`; `gc` → `{gc}`. `build` do helper: BUILD SUCCESSFUL, suíte nova 2 testes 0 falhas.
- ktlint (`ktlintMainSourceSetCheck`/`ktlintTestSourceSetCheck` de `:lemuroid-app`): nenhum apontamento nos dois arquivos.
- Plano de teste sem ajuste (T2/T3 como acima).

## Teste

**Rodada 1 (pipeline 20261114-1244), aprovado.** Base sincronizada `aa3e3df` (`origin/version10`); fix `f2a17e8`.
Aparelho: emulador `lemu_3ds_api34` (`emulator-5554`, Android 14, x86_64; o A12 não estava no adb), app
`app.retrogamesystem.debug` 1.17.34-DEBUG. Evidência em
`../../evidencias/TASK-0001/` (prints e logcat versionados; os APKs ficam fora do git, `*.apk` no `.gitignore`).

- **Offline:** diff `aa3e3df...HEAD` toca só os dois arquivos reservados e o corpo desta task. `build` do helper:
  BUILD SUCCESSFUL; `testFreeBundleDebugUnitTest` 96 testes / 0 falhas / 1 pulado (a linha de base de 54 do manual
  está desatualizada; nenhuma falha), `SystemSearchResolverTest` 2/0. `ktlintTestSourceSetCheck` passa;
  `ktlintMainSourceSetCheck` falha com 18 apontamentos **só** em `TelemetryReporter.kt`, que esta task não toca e que é
  igual na base (commit `3145b78`): registrado como achado lateral
  `docs/bugs/aberto/2026-11-14-ktlint-vermelho-telemetryreporter/` (commit `bbe9907`). Nenhum apontamento nos dois
  arquivos da task.
- **Dado de teste:** baixado pela UI (Busca ▸ "Pocket Adventure" ▸ Save) `Sonic The Hedgehog - Pocket Adventure (WR).ngc`
  (Neo Geo Pocket Color, `downloaded_roms` = `ngc`). Nenhum jogo de GameCube instalado (ISO de ~1,4 GB não cabe no
  teto de 10 min de download).
- **Controle positivo (base `aa3e3df`):** APK `base-aa3e3df-x86_64.apk`, sha256 `f64f11c9…7928f87`, igual ao
  `base.apk` instalado (`pm path` + `sha256sum`), PID 5392. Saved games ▸ busca:
  `gamecube` → **o jogo de NGPC aparece** (`base-t2-gamecube.png`, o sinal do sintoma); `nintendo gamecube` → NGPC
  aparece; `game cube` → nada; `ngc`, `neo geo`, `sonic` → NGPC; `snes` → nada (nenhum SNES instalado).
- **T2 (fix `bbe9907`, mesmo código de `f2a17e8`):** APK `fix-bbe9907-x86_64.apk`, sha256 `3e981176…e603edc9`, igual ao
  instalado, PID 8545. Mesmo caminho: `gamecube`, `nintendo gamecube` e `game cube` → "No installed games match"
  (`fix-t2-gamecube.png`, `fix-t2-nintendo-gamecube.png`, `fix-t2-game-cube.png`): **sinal ausente**. `ngc` → NGPC
  aparece (`fix-t2-ngc.png`): comportamento correto presente. A metade "o GC aparece" não foi vista na UI (sem jogo de
  GC instalado); fica provada pelo `SystemSearchResolverTest` (`gamecube`/`game cube`/`nintendo gamecube` → `{gc}`) e
  pela consulta SQL, que filtra por `systemId IN (:matchedSystemIds)`.
- **T3:** `neo geo` e o título (`sonic`) continuam achando o NGPC, `snes` segue vazio, igual à base
  (`fix-t2-neo-geo.png`, `fix-t2-sonic.png`, `fix-t2-snes.png`). Testes de unidade sem falha nova (acima).
- **T4:** `fix-logcat-pid8545.txt` (logcat `--pid` do launch do fix): sem `AndroidRuntime`/`FATAL`/ANR/`libc`, buffer
  `crash` sem entrada dos dois PIDs. Os únicos `E` são `OpenGLRenderer: Unable to match the desired swap behavior`, ruído
  do emulador que aparece igual na base (`base-logcat-pid5392.txt`, 10 ocorrências).
- **Não testado:** a tela de TV (`TVSavedGamesFragment`), que usa o mesmo resolver pelo construtor com `Context`
  (inalterado), sem AVD de TV no slot.
