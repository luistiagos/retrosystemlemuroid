# [FEATURE] Tela "Jogos salvos" em grade e "Ver todos" na TV

**Data:** 2026-10-18  
**Status:** implementado e validado; aguarda aval do dono para commit  
**Branch:** `redesenho-tv-jogos-salvos-tela` (a partir de `version10`)  
**Backlog:** `docs/backlogs/2026-10-18-tv-tela-jogos-salvos-ver-todos.md`  
**Plano Geral:** `docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md` (Fase 3, item 3.6 / backlog da TV)

---

## 1. Contexto e Motivação

Na entrega 3.6 (`docs/funcionalidades/2026-10-15-tv-fileira-jogos-salvos.md`), a Início da TV recebeu a fileira horizontal "Jogos salvos" limitada a 10 itens (`CAROUSEL_MAX_ITEMS`), sem o cartão "Ver todos", pois a interface de TV ainda não possuía uma tela dedicada para listar o acervo completo de jogos salvos.

Esta entrega adiciona:
1. **O cartão "Ver todos"** no final do carrossel da Início quando existirem mais de 10 jogos salvos.
2. **A tela dedicada `TVSavedGamesFragment`** em grade de 6 colunas (`TV_GAME_GRID_COLUMNS`), apresentando todos os jogos salvos com cartões informativos (capa, título e detalhe de status/sistema).
3. **Compartilhamento de regras de negócio:** extração de `TVSavedGameActions` para manter a consistência de clique entre a Início e a tela em grade (mídia inacessível avisa, múltiplas versões abrem seletor, versão única executa).

---

## 2. Decisões de Design (Aprovadas pelo Dono)

1. **Momento da entrega:** Implementar a tela imediatamente na branch `redesenho-tv-jogos-salvos-tela`.
2. **Busca na grade:** Sem busca nesta primeira versão (grade pura de visualização e navegação fluida por D-pad; busca por escopo fica para backlog futuro).
3. **Lista esvaziada (exclusão da última ROM):** Voltar automaticamente para a tela anterior (`popBackStack()`). Um cuidado especial foi adotado para ignorar a emissão inicial vazia do `StateFlow` (`emptyList()`) antes do término do carregamento da query.
4. **Layout dos cartões na grade:** `SavedGamePresenter(gameInteractor, showInfo = true)` exibindo capa, título do jogo e linha de detalhe (`savedGameDetail`: "N versões" ou "Mídia inacessível"; se normal, subtítulo do sistema). Em caso de mídia inacessível, opacidade da capa reduzida a 60% (`SAVED_GAME_UNAVAILABLE_ALPHA`).

---

## 3. Implementação Técnica

### A. Detecção de 11+ Itens no `TVHomeViewModel`
- No `TVHomeViewModel.savedGames`, a coleta do DAO agora usa `take(CAROUSEL_MAX_ITEMS + 1)` (11 itens).
- Isso permite que a Início saiba exatamente quando há itens adicionais além do limite do carrossel sem precisar carregar a lista completa de centenas de jogos na tela inicial.

### B. Cartão "Ver todos" na Início (`TVHomeFragment`)
- Adicionado `TVSettingType.SHOW_ALL_SAVED_GAMES` com rótulo `R.string.show_all` e ícone `ic_more_horiz_black_24dp`.
- `TVHomeFragment` utiliza `ClassPresenterSelector` na fileira `SAVED_ADAPTER`, registrando:
  - `InstalledGameGroupUiModel` → `SavedGamePresenter(gameInteractor, showInfo = false)`
  - `TVSetting` → `SettingPresenter()`
- Se a lista contiver mais de 10 itens, ela é truncada para 10 e recebe o `TVSetting(SHOW_ALL_SAVED_GAMES)` ao final.
- `LEANBACK_MULTI_DIFF_CALLBACK` foi estendido para calcular diffs tanto entre `InstalledGameGroupUiModel` quanto `TVSetting`.
- O clique no cartão "Ver todos" navega para `R.id.navigation_saved_games`.

### C. Extração de `TVSavedGameActions`
- Criado helper compartilhado em `com.swordfish.lemuroid.app.tv.shared.TVSavedGameActions`:
  - `onInstalledGameClick`: validação de disponibilidade (`isAvailable`), abertura do diálogo de variantes (`TVSavedVariantsDialog`) se `installedVariantsCount > 1`, ou execução direta via `gameInteractor.onGamePlay`.
- Utilizado igualmente em `TVHomeFragment` e `TVSavedGamesFragment`.

### D. Cartão Informativo em `SavedGamePresenter`
- Construtor atualizado com `showInfo: Boolean = false`.
- Se `showInfo == true`:
  - `cardType = BaseCardView.CARD_TYPE_INFO_UNDER`
  - Apresenta título do jogo (`titleText`) e linha de detalhe (`contentText`) com a contagem de versões ou aviso de mídia inacessível.
  - Na Início (`showInfo == false`), o comportamento original sem info (`CARD_TYPE_MAIN_ONLY`) é rigorosamente mantido.

### E. Tela em Grade `TVSavedGamesFragment`
- Subclasse de `VerticalGridSupportFragment` com `VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_SMALL, false)`.
- Número de colunas: `TV_GAME_GRID_COLUMNS` (6 colunas).
- Utiliza `InstalledGamesViewModel` (injetado via `MainTVActivity.Module` e factory com `StorageAvailabilityMonitor` e `SystemSearchResolver`).
- Coleta `installedGames` e atualiza o `ArrayObjectAdapter` com `setItems` e `LeanbackItemDiffCallback`.
- Integração com `TVAmbientBackground` no `OnItemViewSelectedListener` para atualizar o papel de parede ambiente ao navegar pelos jogos salvos.
- Monitoramento de lista vazia: com a flag `hasHadItems`, aciona `parentFragmentManager.popBackStack()` se a lista for esvaziada por exclusão de ROMs.
- Destino `navigation_saved_games` registrado em `tv_navigation.xml`.

---

## 4. Arquivos Modificados e Criados

| Arquivo | Status | Descrição |
|---|---|---|
| `TVHomeViewModel.kt` | Modificado | `take(CAROUSEL_MAX_ITEMS + 1)` para detectar overflow |
| `TVSettingType.kt` | Modificado | Adicionado enum `SHOW_ALL_SAVED_GAMES` |
| `TVHomeFragment.kt` | Modificado | `ClassPresenterSelector`, corte em 10 itens + "Ver todos", diff callback e navegação |
| `TVSavedGameActions.kt` | Criado | Helper compartilhado de clique em jogo salvo |
| `SavedGamePresenter.kt` | Modificado | Suporte a `showInfo: Boolean = false` e textos na grade |
| `TVSavedGamesFragment.kt` | Criado | Fragmento em grade para TV com Leanback e background ambiente |
| `tv_navigation.xml` | Modificado | Adicionado fragmento `navigation_saved_games` |
| `MainTVActivity.kt` | Modificado | Adicionado `@ContributesAndroidInjector` para `TVSavedGamesFragment` |
| `docs/backlogs/2026-10-18-tv-tela-jogos-salvos-ver-todos.md` | Criado | Backlog e registro de decisões do dono |
| `docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md` | Modificado | Atualização do status da tarefa da TV |

---

## 5. Validação e Qualidade de Código

1. **Compilação:** `./gradlew :lemuroid-app:assembleFreeBundleDebug -PdevAbi=x86` → **BUILD SUCCESSFUL**.
2. **Estilo de código:** `./gradlew ktlintCheck` → **BUILD SUCCESSFUL** (sem violações; imports limpos).
3. **Análise estática:** `./gradlew :lemuroid-app:lintFreeBundleDebug -PdevAbi=x86` → **BUILD SUCCESSFUL** (zero erros de lint em 13m 54s).
4. **Verificação no emulador TV (`lemu_tv_api25` - API 25 x86):**
   - **Cenário 1 (<= 10 jogos salvos):** Com 10 jogos salvos na base de dados, a fileira "Saved games" renderizou exatamente os 10 cartões sem exibir o cartão "Ver todos".
   - **Cenário 2 (> 10 jogos salvos):** Ao adicionar o 11º e 12º jogos, o carrossel exibiu os 10 primeiros itens acompanhados do cartão "Show All" ("Ver todos") com o ícone de grade no final.
   - **Cenário 3 (Navegação D-Pad):** Navegando com `KEYCODE_DPAD_RIGHT` até o final do carrossel, o foco pousou no cartão "Show All" com destaque âmbar e título correspondente no painel de foco superior.
   - **Cenário 4 (Grade em TVSavedGamesFragment):** Ao pressionar `KEYCODE_DPAD_CENTER`, a tela dedicada de jogos salvos abriu instantaneamente exibindo a grade de 6 colunas, títulos dos jogos, sistema/status no subtítulo dos cartões, zoom suave e tema ambiente.
   - **Cenário 5 (Retorno via Back):** Ao pressionar `KEYCODE_BACK`, o fragmento realizou `popBackStack()` retornando à Home da TV mantendo exatamente o foco no cartão "Show All".
   - **Estabilidade:** `logcat -b crash` permaneceu totalmente limpo (zero exceções e zero falhas de layout).

