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
