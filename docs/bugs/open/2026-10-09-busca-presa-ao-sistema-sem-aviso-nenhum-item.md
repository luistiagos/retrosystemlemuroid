# [BUG] Busca fica presa ao último sistema aberto, sem nada na tela dizendo isso: jogo que existe dá "Nenhum item"

**Data:** 2026-10-09
**Status:** 🔴 Aberto — causa confirmada; correção proposta, não implementada
**Severidade:** Média — o cliente conclui que o jogo não existe no catálogo (aconteceu com o próprio agente de teste)
**Branch:** version9
**Origem:** observado durante o teste do bug `2026-10-09-variante-nao-baixada-midia-inacessivel` no Galaxy A12

---

## Sintoma

No Galaxy A12 (`RX8R90G1D6E`), a sequência foi Sistemas → 3DO → (modal de variantes, Cancelar) →
aba **Buscar** → "temple of doom" e depois "indiana". Os dois deram **"Nenhum item"**, mas o
catálogo tem os dois títulos, por exemplo no Amstrad CPC. A tela mostra só a caixa de busca: nada
indica que a busca está limitada ao 3DO.

## Análise (símbolos abertos)

- `MainActivity.kt` `LaunchedEffect(currentRoute)` (~l.263): só zera o sistema atual
  (`setCurrentMetaSystem(null)`) quando a rota nova **não** é `SEARCH` nem `SYSTEM_GAMES`.
  Entrar num sistema e tocar em Buscar mantém o sistema.
- `MainActivity.kt` `composable(MainRoute.SYSTEM_GAMES)` (~l.528):
  `setCurrentMetaSystem(metaSystemId)`.
- `MainViewModel.kt::buildStateFlow`: `currentSystemIds` = `MetaSystemID.valueOf(id).systemIDs`
  → `SearchScreen(systemIds = mainUIState.currentSystemIds)`.
- `SearchScreen.kt`: `systemIds` só vai para `viewModel.setSystemIds`; **não há indicador** do
  escopo de sistema. O escopo "só instalados" tem um `FilterChip` com X (l.82–96); o de sistema
  não tem nada. Com resultado vazio aparece o genérico `empty_view_default` ("Nenhum item").
- `GameSearchDao.kt::search`: com `systemIds` preenchido, acrescenta
  `AND games.systemId IN (...)`.
- O escopo `onlyInstalled` estava **desligado**: ele só liga ao chegar à Busca vindo de
  `INSTALLED` (`searchScopeInstalled`, ~l.264). A primeira suspeita ("filtro de só instalados")
  está descartada.

## Prova (banco copiado do A12)

O mesmo SQL do `GameSearchDao.search` (MATCH = `sanitizeFtsQuery`), rodado no banco
`databases/retrograde` do A12 (script `search_repro.py`, scratchpad da sessão):

| consulta | `systemIds` | linhas |
|---|---|---|
| `indiana` | nenhum | 75 (13 sistemas) |
| `indiana` | `['3do']` | **0** |
| `indiana` | `['amstradcpc']` | 9 |
| `temple of doom` | nenhum | 13 (4 sistemas) |
| `temple of doom` | `['3do']` | **0** |
| `temple of doom` | `['amstradcpc']` | 3 |

O índice FTS está certo. O vazio vem só do filtro de sistema.

## Causa-raiz

O comportamento é **intencional**: `prj.md` §17.2 "System-Context-Aware Search" (commit
`1951c59`, 2026-04-23) descreve que, quem entra num sistema e toca em Buscar, recebe só os jogos
daquele sistema. O defeito é de interface. O escopo fica invisível e não há como desfazê-lo na
própria tela de busca: a única saída é ir a Início, Favoritos ou Sistemas e voltar. O backlog
`2026-10-08-aba-jogos-instalados-salvos.md` (l.90) já estabelece a regra de que escopo restrito
mostra um chip descartável, mas ela só foi aplicada ao "só instalados".

## Hipóteses descartadas

- Filtro "só instalados" ligado: `onlyInstalled` só liga vindo da aba Instalados, e o caminho
  veio de `SYSTEM_GAMES`.
- Índice FTS desatualizado ou recriado pela troca de `CATALOG_VERSION`: a mesma query, sem
  `systemIds`, devolve 75 linhas no mesmo banco.
- Busca quebrada pelo "of" ou por várias palavras: `temple of doom` sem escopo devolve 13 linhas.

## Correção proposta (não implementada)

1. `SearchScreen`: quando `systemIds` não for vazio, mostrar um `FilterChip` como o de
   instalados, com o nome do sistema (ex.: "3DO Interactive Multiplayer") e X. O X chama
   `mainViewModel.setCurrentMetaSystem(null)` (callback novo `onClearSystemScope`).
2. Opcional: com escopo de sistema e resultado vazio, trocar "Nenhum item" por "Nenhum jogo em
   <sistema>", acompanhado da ação de buscar em todos.

Prova esperada: no aparelho, Sistemas → 3DO → Buscar → "indiana" mostra o chip do 3DO e
"Nenhum item"; tocar no X lista os Indiana Jones (Amstrad CPC e outros). Controle: Início →
Buscar → "indiana" lista resultados e não mostra chip.

## Validação

- [ ] Implementar e buildar
- [ ] Prova no aparelho (acima)

## Lição

Todo filtro que restringe resultados tem de aparecer na tela. Um escopo invisível transforma
"filtrado" em "não existe".
