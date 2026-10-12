# [BUG] Busca fica presa ao último sistema aberto, sem nada na tela dizendo isso: jogo que existe dá "Nenhum item"

**Data:** 2026-10-09
**Status:** 🟢 Fechado — corrigido com FilterChip descartável do sistema e ação de buscar em todos no estado vazio
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
- `SearchScreen.kt`: `systemIds` só ia para `viewModel.setSystemIds`; **não havia indicador** do
  escopo de sistema. O escopo "só instalados" tinha um `FilterChip` com X (l.82–96); o de sistema
  não tinha nada. Com resultado vazio aparecia o genérico `empty_view_default` ("Nenhum item").
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

O índice FTS está certo. O vazio vinha só do filtro de sistema.

## Causa-raiz

O comportamento de filtrar por sistema é intencional (`prj.md` §17.2 "System-Context-Aware Search"),
mas a interface não informava o filtro ativo e não dava meios para desfazê-lo na tela de busca.
O escopo ficava invisível: a única saída era sair da busca e ir a Início, Favoritos ou Sistemas e voltar.

## Hipóteses descartadas

- Filtro "só instalados" ligado: `onlyInstalled` só liga vindo da aba Instalados, e o caminho
  veio de `SYSTEM_GAMES`.
- Índice FTS desatualizado ou recriado pela troca de `CATALOG_VERSION`: a mesma query, sem
  `systemIds`, devolve 75 linhas no mesmo banco.
- Busca quebrada pelo "of" ou por várias palavras: `temple of doom` sem escopo devolve 13 linhas.

## Correção

1. `MainViewModel.kt`:
   - Adicionado `currentMetaSystem: MetaSystemID? = null` em `MainViewModel.UiState`.
   - Exposto o `MetaSystemID` resolvido em `buildStateFlow()`.
2. `SearchScreen.kt`:
   - Recebe `systemName: String? = null` e `onClearSystemScope: () -> Unit = {}`.
   - Resolve o nome do sistema pelo parâmetro ou fallback via `GameSystem.findByIdOrNull(id)?.metaSystemID()?.titleResId`.
   - Exibe no topo da tela um `FilterChip` selecionado com o nome do sistema (ex.: "3DO Interactive Multiplayer") e ícone de fechar (X).
   - Tocar no chip (ou no X) limpa imediatamente o filtro chamando `viewModel.setSystemIds(null)` e `onClearSystemScope()`.
   - Suporta scroll horizontal na barra de chips quando múltiplos filtros estão ativos (ex.: sistema + só instalados).
   - Quando a busca não encontra nenhum jogo no sistema (`Ready` com `itemCount == 0`), exibe a mensagem contextual `search_no_games_in_system` ("Nenhum jogo em <sistema>") acompanhada de um botão de ação `search_all_systems` ("Buscar em todos os sistemas") que também limpa o escopo com 1 toque.
3. `MainActivity.kt`:
   - Passa `systemName = mainUIState.currentMetaSystem?.let { stringResource(it.titleResId) }` e `onClearSystemScope = { mainViewModel.setCurrentMetaSystem(null) }` para `SearchScreen`.
4. `strings.xml` (en e pt-BR):
   - Adicionadas as strings `search_no_games_in_system` e `search_all_systems`.
5. `prj.md`:
   - Documentado o novo comportamento do chip de escopo de sistema e a ação de limpar busca na seção 17.2.

## Validação

- Compilação Kotlin executada com sucesso (`:lemuroid-app:compileFreeBundleDebugKotlin` — BUILD SUCCESSFUL).
- Build e instalação do APK no Galaxy A12 conectado (`RX8R90G1D6E`) via `build_and_install_connected.ps1 -LaunchAfterInstall` com sucesso.
- **Teste no aparelho (A12):**
  1. *Sistemas → 3DO → Buscar:* o `FilterChip` "3DO Interactive Multiplayer" com ícone de fechar (X / "Limpar") apareceu visível no topo da tela.
  2. *Digitar "indiana":* como o 3DO não tem jogos com "indiana", a tela exibiu o estado vazio contextual *"Nenhum jogo em 3DO Interactive Multiplayer"* acompanhado do botão *"Buscar em todos os sistemas"*.
  3. *Tocar em "Buscar em todos os sistemas":* o filtro foi limpo instantaneamente, o chip sumiu e a lista de jogos Indiana Jones (Amiga, Amstrad CPC, etc.) foi carregada com sucesso.
  4. *Tocar no X do chip:* re-executado Sistemas → 3DO → Buscar; tocar diretamente no X do chip removeu o filtro imediatamente e expandiu a busca para todo o catálogo.
  5. *Controle (Início → Buscar):* a busca abriu sem chip ativo, retornando jogos diretamente em todo o catálogo.

## Lição

Todo filtro que restringe resultados tem de aparecer na tela. Um escopo invisível transforma
"filtrado" em "não existe".
