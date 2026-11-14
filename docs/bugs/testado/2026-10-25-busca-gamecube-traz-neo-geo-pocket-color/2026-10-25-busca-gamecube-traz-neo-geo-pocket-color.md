---
fecha_por: prova
tasks: [TASK-0001, TASK-0002]
---
# [BUG] Buscar "gamecube" nos jogos instalados/salvos traz jogos de Neo Geo Pocket Color, não de GameCube

- **Data:** 2026-10-25
- **Status:** aberto (deduzido do código, ainda não reproduzido no aparelho)
- **Severidade:** Baixa. Afeta só a busca de jogos instalados/salvos, e "nintendo gamecube" ainda acha o GameCube, misturado com NGPC.
- **Complexidade:** Baixa (troca de um valor num mapa de apelidos)
- **Branch:** redesenho-tv-jogos-salvos-tela (onde foi achado)
- **Origem:** achado lateral do planejamento do Wii (`docs/backlog/arquivados/2026-10-25-suporte-nintendo-wii.md`)

---

## Sintoma

Na busca da tela de jogos instalados/salvos (celular) e da tela "Jogos salvos" (TV), digitar `gamecube` lista os
jogos de **Neo Geo Pocket Color**, e nenhum de GameCube.

## Evidência

- `lemuroid-app/.../shared/search/SystemSearchResolver.kt:52`: `"gamecube" to listOf("ngc")`.
- `retrograde-app-shared/.../library/SystemID.kt:25`: `NGC("ngc")` é o Neo Geo Pocket Color. O GameCube é
  `GAMECUBE("gc")` (`:71`).
- Rastreio de `resolveSystemIds("gamecube")`:
  - a regra 1 dos apelidos (casamento exato) adiciona `ngc`;
  - no laço de `GameSystem.all()`, o título "Nintendo GameCube" não casa: não é igual à busca, não começa por ela e
    não está contido nela;
  - a abreviação `GC` e o dbname `gc` também não são iguais a `gamecube`;
  - resultado: `["ngc"]`.
- Com `nintendo gamecube`, a regra 3 (token) adiciona `ngc` e o título adiciona `gc`. Resultado: `[ngc, gc]`, os dois
  sistemas misturados.
- Quem chama: `InstalledGamesViewModel.kt:113` (passa os ids a `searchInstalledGameGroups`) e
  `TVSavedGamesFragment.kt:61`.

## Causa-raiz

"NGC" é uma abreviação comum de *Nintendo GameCube*, mas neste projeto `ngc` é o dbname do Neo Geo Pocket Color. O
apelido foi escrito com o dbname errado.

## Correção (proposta)

`"gamecube" to listOf("gc")`, e acrescentar `"game cube" to listOf("gc")`. Não mapear a busca `ngc` para o GameCube: o
laço de dbnames já a resolve para o Neo Geo Pocket Color, que é o certo. Quando o Wii entrar, o mesmo mapa ganha
`"wii"` e `"nintendo wii"` (ver o backlog do Wii).

## Validação (a fazer)

- Não existe teste do `SystemSearchResolver`. Criar um teste unitário com `gamecube`, `nintendo gamecube` e `ngc`.
- No aparelho, buscar `gamecube` com um jogo de GC e um de NGPC instalados.

## Lição

Apelido de busca aponta para o **dbname** do `SystemID`, não para a sigla popular do console. Conferir em `SystemID.kt`.

## Planejamento (pipeline 20261114-1244, 2026-11-14)

**Veredito:** corrigir — uma task `implementacao` (`corrigir-apelido-gamecube`): trocar o apelido `"gamecube"` de `ngc` para `gc`, acrescentar `"game cube"`, e cobrir o `SystemSearchResolver` com teste de unidade.
**Complexidade:** baixa — valor errado num mapa; o único acréscimo é tornar a busca de títulos injetável para o teste rodar na JVM (callers não mudam).
**Severidade:** baixo — só a busca das telas de jogos instalados/salvos; `nintendo gamecube` e `gc` já acham o GameCube (misturado com NGPC no primeiro caso).
**Fecha por:** `prova` — a Aceitação reproduz no aparelho (basta um jogo de NGPC instalado: buscar `gamecube` o lista antes do fix e não lista depois). Sem `casador`: o defeito não gera erro na telemetria.
**Arquivos a alterar (reserva da onda):**
- `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/search/SystemSearchResolver.kt` — apelidos + construtor injetável
- `lemuroid-app/src/test/java/com/swordfish/lemuroid/app/shared/SystemSearchResolverTest.kt` — novo

### Causa raiz (símbolos abertos)

- `SystemSearchResolver::commonAliases` (`SystemSearchResolver.kt:52`) — `"gamecube" to listOf("ngc")`. Confirmado no worktree (base `f7310ea`).
- `SystemID` (`retrograde-app-shared/.../library/SystemID.kt`) — `NGC("ngc")` (:25) é o Neo Geo Pocket Color; `GAMECUBE("gc")` (:71).
- `strings-game-system.xml` (retrograde-app-shared) — `game_system_title_gc` = "Nintendo GameCube", `game_system_abbr_gc` = "GC"; `game_system_title_ngc` = "Neo Geo Pocket Color", `game_system_abbr_ngc` = "NGC". Sem tradução em `values-pt-rBR` (o título é o mesmo em qualquer idioma).
- `SystemSearchResolver::resolveSystemIds` — regra 1 (exato/prefixo) põe os ids do apelido; regra 3 casa apelido de palavra única com token; depois o laço de `GameSystem.all()` casa dbname/token, título (igual, prefixo, ou multi-palavra contido na busca) e abreviação. Erro: `context.getString` dentro de `runCatching` → título `""` (não lança). Rastreio de `gamecube`: só a regra 1 casa (`ngc`); título "nintendo gamecube" não começa por "gamecube" nem está contido na busca; `gc` ≠ `gamecube` → `[ngc]`. `nintendo gamecube` → `[ngc, gc]`.
- Call-sites (provados): `InstalledGamesViewModel.kt:113` (`resolveSystemIds(trimmed).ifEmpty { listOf("__none__") }` → `GameDao.searchInstalledGameGroups`), instanciado por `MainActivity.kt:288` (rota `MainRoute.INSTALLED`, :531) e `TVSavedGamesFragment.kt:61`. Nenhum outro uso (`grep -rn "SystemSearchResolver\|resolveSystemIds" --include=*.kt`).
- `GameDao::searchInstalledGameGroups` — `MatchingGroups` aceita o grupo quando `title LIKE %q%` OU `fileName LIKE %q%` OU `systemId IN (:matchedSystemIds)`. Por isso todo jogo NGPC instalado aparece ao buscar `gamecube`; com o fix aparecem os de GC (mais títulos/arquivos que contenham "gamecube").

### Provas

- `grep -n "gamecube" SystemSearchResolver.kt` → `52: "gamecube" to listOf("ngc"),`.
- `cat SystemID.kt` → `NGC("ngc")` linha 25, `GAMECUBE("gc")` linha 71.
- `grep -n testImplementation lemuroid-app/build.gradle.kts` → só `junit:junit:4.13.2` (sem Mockito/Robolectric): um `Context` não se fabrica no teste; `Context.getString` dos stubs lança "not mocked" e o `runCatching` zera o título — daí o construtor injetável.
- `grep -n SystemSearchResolver lemuroid-app/config/ktlint/baseline.xml` → nada (sem passivo; não há baseline a regenerar).

### Hipóteses descartadas

- Mapear também `ngc` → GameCube: errado; `ngc` é o dbname/abreviação do NGPC e o laço já o resolve certo.
- Testar com `Context` falso via `ContextWrapper`: `getString(int)` é `final` em `Context` e passa por `getResources()`, stub sem implementação na JVM; o teste não provaria a regra do título.
- Outros apelidos com dbname errado: conferidos contra `SystemID.kt` (`snes`, `nes`, `fds`, `md`, `scd`, `sms`, `gg`, `psx`, `psp`, `n64`, `gb`, `gbc`, `gba`, `nds`, `3ds`, `mame2003plus`, `fbneo`, `cps1..3`, `neogeo`, `ngp`, `dc`, `atari2600/5200/7800`, `ws`, `wsc`, `pce`, `pcecd`): todos existem. Só `gamecube` está errado.

### Tarefas

- [ ] `corrigir-apelido-gamecube` (implementacao) — `SystemSearchResolver::commonAliases` + construtor `(Int) -> String` com secundário `(Context)` — `SystemSearchResolverTest` (`gamecube`→`[gc]`, `game cube`→`[gc]`, `nintendo gamecube`→`[gc]`, `ngc`→`[ngc]`, `gc`→`[gc]`).

### Plano de teste

- **Offline (sem slot):** `SystemSearchResolverTest` no `build` do helper; sem o fix, `gamecube` devolve `[ngc]` (o teste falha). `ktlintCheck` sem apontamento novo nos dois arquivos.
- **T2 reprodução como o usuário (E2E, no slot):** estado de partida: app debug com ao menos um jogo de **Neo Geo Pocket Color** baixado (catálogo ▸ Neo Geo Pocket Color ▸ qualquer jogo pequeno ▸ baixar) e, se possível, um de **GameCube** instalado. Celular: Início ▸ aba/tela de jogos instalados (rota `INSTALLED`) ▸ campo de busca ▸ digitar `gamecube`. Sinal: print da lista. Sem o fix: aparece o jogo de NGPC (e nenhum de GC). Com o fix: o jogo de NGPC **não** aparece; o de GC aparece (se houver). Repetir com `nintendo gamecube` (com o fix: só GC) e `ngc` (com o fix: o NGPC continua aparecendo).
- **Controle positivo:** APK da base (`git checkout --detach <base>` + `build`): buscar `gamecube` lista o jogo de NGPC.
- **T3 regressão direcionada:** na mesma tela, `snes`, `neo geo` (NGPC continua vindo: o título "Neo Geo Pocket Color" começa pela busca), busca por título de um jogo instalado; na TV (`TVSavedGamesFragment`, se houver emulador de TV no slot) só se barato — mesmo resolver. Testes de unidade da linha de base sem falha nova.

### Sessões seguintes

- dev: effort low, ultrathink não — troca localizada + teste simples.
- teste: effort medium, ultrathink não — precisa baixar um jogo de NGPC no aparelho e comparar dois prints.
