# [BUG] Buscar "gamecube" nos jogos instalados/salvos traz jogos de Neo Geo Pocket Color, não de GameCube

- **Data:** 2026-10-25
- **Status:** aberto (deduzido do código, ainda não reproduzido no aparelho)
- **Severidade:** Baixa. Afeta só a busca de jogos instalados/salvos, e "nintendo gamecube" ainda acha o GameCube, misturado com NGPC.
- **Complexidade:** Baixa (troca de um valor num mapa de apelidos)
- **Branch:** redesenho-tv-jogos-salvos-tela (onde foi achado)
- **Origem:** achado lateral do planejamento do Wii (`docs/backlogs/2026-10-25-suporte-nintendo-wii.md`)

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
