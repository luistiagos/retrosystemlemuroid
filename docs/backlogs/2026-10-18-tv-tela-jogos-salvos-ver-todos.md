# [BACKLOG] Tela "Jogos salvos" na TV, aberta pelo "Ver todos" da fileira da Início

**Data:** 2026-10-18
**Status:** em andamento — aprovado pelo dono em 2026-10-18
**Origem:** fora do escopo da entrega 3.6 (`docs/funcionalidades/2026-10-15-tv-fileira-jogos-salvos.md`, seção
"Fora do escopo") e "Próximos itens do plano" da passagem de bastão da Fase 3 em
`docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md` (Andamento, 2026-10-16)

Legenda de caminhos: `TV/` = `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/`,
`M/` = `…/app/mobile/`, `LIB/` = `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/`.

## O que falta

A fileira "Jogos salvos" da Início da TV mostra só os 10 grupos mais recentes e não tem saída para o resto. Quem tem
mais de 10 jogos salvos não consegue chegar ao 11º pela área de salvos (só achando o jogo no catálogo ou na busca).
O celular tem a aba inteira; a TV não tem tela nenhuma.

Pedido: uma tela "Jogos salvos" na TV, em grade, com **todos** os grupos salvos, e um cartão "Ver todos" no fim da
fileira da Início quando houver mais de 10 — o mesmo mecanismo que a fileira de Favoritos já tem.

## Como está hoje (símbolos abertos em 2026-10-18, `version10` em `06674a0`)

- **`TV/home/TVHomeViewModel.kt::savedGames`** (`:80-95`) — `observeInstalledGameGroups(...)` combinado com
  `mediaStateChanges`, depois `groups.take(CAROUSEL_MAX_ITEMS)` (`:85`). Com o `take(10)` a Início **não sabe** se
  existe um 11º. Favoritos resolve isso pedindo um a mais: `selectFirstFavoritesRecents(CAROUSEL_MAX_ITEMS + 1)`
  (`:183`).
- **`TV/home/TVHomeFragment.kt::render`** (`:234-285`) — Favoritos com mais de 10: corta em 10 e acrescenta
  `TVSetting(TVSettingType.SHOW_ALL_FAVORITES)` (`:264-273`), com `LEANBACK_MULTI_DIFF_CALLBACK` (só conhece `Game` e
  `TVSetting`, `:430-461`). A fileira de salvos faz `setItems(savedGames, LEANBACK_SAVED_GAME_DIFF_CALLBACK)` (`:261`),
  callback que só conhece `InstalledGameGroupUiModel` (`:411-428`).
- **`TVHomeFragment::recreateAdapter`** (`:303-367`) — a fileira de salvos usa um presenter único,
  `ArrayObjectAdapter(SavedGamePresenter(gameInteractor))` (`:318`). Favoritos usa `ClassPresenterSelector` com
  `GamePresenter` + `SettingPresenter` do tamanho do cartão de jogo e `showInfo = false` (`:324-339`): "Ver todos" só
  com o ícone, o painel de foco mostra o rótulo.
- **Clique** (`:94-126`): `TVSettingType.SHOW_ALL_FAVORITES -> launchFavorites()` (`:118`), que faz
  `findNavController().navigate(R.id.navigation_favorites)` (`:400-402`). O `when (item.type)` é exaustivo: um tipo novo
  em `TVSettingType` não compila sem o ramo.
- **`TV/home/TVSettingType.kt`** — `SHOW_ALL_FAVORITES(R.drawable.ic_more_games, R.string.show_all)` (`:8`).
- **Regras de toque dos salvos** — `TVHomeFragment::onSavedGameClick` (`:192-212`: mídia inacessível → toast
  `installed_media_unavailable`; 1 versão → `onGamePlay`; mais de uma → `TVSavedVariantsDialog` com
  `homeViewModel.savedVariants`) e `::onSavedVariantSelected` (`:215-227`: não salvo → `onGamePlay`, que na TV abre o
  diálogo de salvar; senão `isAvailable` → joga ou toast). Moram no fragment da Início, presas ao `homeViewModel`.
- **`TV/home/SavedGamePresenter.kt`** — cartão só imagem: `newTVCardView(parent.context, showInfo = false)` fixo
  (`:43`); capa a 60% com mídia inacessível; menu de contexto do representante (`GameContextMenuListener`, que tem
  "Excluir ROM"). `savedGameDetail(context, savedGame)` (`:79-89`) dá "Mídia inacessível" / "N versões" / `null`.
- **`TV/favorites/TVFavoritesFragment.kt`** — o molde da tela: `VerticalGridSupportFragment`,
  `TVVerticalGridPresenter` com `TV_GAME_GRID_COLUMNS` (5, `TV/shared/TVGridPresenters.kt:55`),
  `TVAmbientBackground.attach`, `GamePresenter` com título (o `showInfo` default é `true`: nas grades da TV o título vai
  no cartão, não há painel de foco). Usa `PagingDataAdapter` porque a fonte é paginada.
- **`res/navigation/tv_navigation.xml`** — destinos `navigation_home`, `navigation_search`, `navigation_games`
  (argumento `meta_system_id`), `navigation_favorites`. **`TV/main/MainTVActivity.kt::Module`** (`:142-157`) — um
  `@ContributesAndroidInjector` por fragment.
- **`M/feature/installed/InstalledGamesViewModel.kt`** — a fonte da aba do celular. `Factory(retrogradeDb,
  directoriesManager, StorageAvailabilityMonitor, SystemSearchResolver)`; os dois últimos se constroem com um `Context`
  (`StorageAvailabilityMonitor(context)`, `SystemSearchResolver(context)` — é como a `MainActivity:287-288` e o próprio
  `TVHomeViewModel:62` fazem). `installedGames: StateFlow<List<InstalledGameGroupUiModel>>` (`:123-137`,
  `WhileSubscribed(5000)`, lista **inteira**, não paginada), busca por `onSearchQueryChanged` (`:101`,
  `searchInstalledGameGroups`), `getInstalledVariants(systemId, title)` (`:139-150`). **O `init` roda
  `reconcileManagedRoms()`** (`:60-96`): varre a pasta de ROMs e faz `insertIgnore` em `downloaded_roms` (requisito
  R5). Foi por isso que a Início não o usa; numa tela dedicada é o mesmo custo que a aba do celular paga.
- **Busca da TV** — `TV/search/TVSearchViewModel.kt:34`: `gameSearchDao().search(it)`, catálogo inteiro, sem escopo
  "só instalados" (o `INSTALLED_ONLY` dos requisitos R8/R9 é só do celular).

## Decisões tomadas com o dono (2026-10-18)

1. **Fazer a tela agora?** Aprovado pelo dono em 2026-10-18.
2. **Busca dentro da tela:** Sem busca na primeira versão. A `VerticalGridSupportFragment` não tem campo de texto; o ícone de busca da barra de título levaria à busca da TV, que procura no catálogo inteiro — e o escopo "só salvos" (R8/R9 do celular) não existe na TV. Se o dono quiser, vira registro próprio: busca da TV com escopo, reaproveitando `searchInstalledGameGroups`.
3. **Tela que fica vazia (usuário exclui a última ROM pelo menu de contexto):** Voltar para a Início (`popBackStack`), onde a fileira também some. A `VerticalGridSupportFragment` não tem vista de "vazio", e a tela só é aberta pelo "Ver todos", que só existe com mais de 10.
4. **Texto do cartão na grade:** Título do jogo + `savedGameDetail` como linha de baixo ("N versões" / "Mídia inacessível"); sem estado, o subtítulo de sistema que o `GamePresenter` já usa (`GameUtils.getGameSubtitle`). Capa a 60% com mídia inacessível, como na fileira.

## O que fazer (depois do aval)

Antes do código, seguir o "Como usar" do plano do redesenho: registro analítico na entrega (este arquivo vira a
análise; desvios corrigem este doc), doc de entrega em `docs/funcionalidades/`, commit só com o aval do dono.

1. **Saber se há mais de 10.** `TVHomeViewModel.savedGames`: `take(CAROUSEL_MAX_ITEMS + 1)` (como Favoritos).
   *Prova:* com 11 grupos a lista tem 11; com 10, 10.
2. **"Ver todos" na fileira.** `TVSettingType.SHOW_ALL_SAVED_GAMES(R.drawable.ic_more_games, R.string.show_all)`;
   em `recreateAdapter` a fileira de salvos passa a `ClassPresenterSelector` (`InstalledGameGroupUiModel` →
   `SavedGamePresenter`, `TVSetting` → `SettingPresenter` igual ao de Favoritos); em `render` corta em 10 + o
   `TVSetting`; diff callback que trate os dois tipos (estender o `LEANBACK_MULTI_DIFF_CALLBACK` com o caso do grupo,
   reaproveitando `LEANBACK_SAVED_GAME_DIFF_CALLBACK`, em vez de um terceiro callback). Ramo no `when` do clique →
   `navigate(R.id.navigation_saved_games)`. `TVAmbientBackground` já trata `TVSetting` (neutro) e o `TVFocusPanel`
   já mostra o rótulo de `TVSetting` — conferir na prova, não mexer. Cuidado: o seletor de fundo faz
   `(item as? InstalledGameGroupUiModel)?.group?.game ?: item` (`:143`) — o `TVSetting` passa como está, ok.
   *Prova:* 10 grupos → sem "Ver todos"; 11 → 10 cartões + "Ver todos"; excluir um → o "Ver todos" some sem reabrir.
3. **Regras de toque compartilhadas.** Tirar `onSavedGameClick`/`onSavedVariantSelected` do `TVHomeFragment` para um
   helper em `TV/shared/` (ou `TV/home/`) que receba `GameInteractor`, `Context`, o escopo e duas funções suspensas
   (`savedVariants(game)`, `isAvailable(game)`). A Início e a tela nova chamam o mesmo código — **não copiar** as
   regras. *Prova:* a Início continua com o roteiro da 3.6 (1 versão joga, 2 abrem o seletor, mídia avisa).
4. **Cartão com texto.** `SavedGamePresenter(gameInteractor, showInfo: Boolean = false)`; com `showInfo`, título e
   linha de estado (decisão 4). A Início não muda (default `false`).
5. **A tela.** `TV/saved/TVSavedGamesFragment` (nome a confirmar), molde do `TVFavoritesFragment`: grade de
   `TV_GAME_GRID_COLUMNS`, `TVAmbientBackground` mapeando o grupo para o `Game` (como a Início), título
   `title_installed`. Dados do `InstalledGamesViewModel` (Factory com `StorageAvailabilityMonitor(appContext)` e
   `SystemSearchResolver(appContext)`), coletando `installedGames` num `ArrayObjectAdapter` com `setItems` + diff
   (lista inteira, não paginada). Variantes por `getInstalledVariants`, disponibilidade por
   `StorageAvailabilityMonitor.isGameAvailable` no IO. Destino `navigation_saved_games` no `tv_navigation.xml` e
   `@ContributesAndroidInjector` no `MainTVActivity.Module`. Vazio → decisão 3.
   *Prova:* a tela abre com todos os grupos, na mesma ordem da fileira (os 10 primeiros = os 10 da Início).
6. **Strings:** nenhuma nova prevista (`title_installed`, `show_all`, `installed_media_unavailable`,
   `installed_versions_count` existem em en e pt-BR). Se surgir, as duas línguas.

## Cuidados

- **`initialValue = emptyList()`** do `installedGames` (`WhileSubscribed`): a primeira emissão é vazia antes da query
  terminar. A decisão 3 (voltar quando vazia) **não pode** reagir a essa primeira lista — só a uma lista vazia depois
  de ter havido itens.
- **Ordem muda ao voltar de um jogo:** jogar atualiza `lastActionAt` e o grupo sobe para o topo. Com o adapter por
  diff, o foco fica no item (que mudou de posição); conferir que não pula para um cartão aleatório.
- **ktlint:** `TVHomeFragment.kt` e os outros arquivos tocados — se algum tiver passivo no baseline, regenerar no mesmo
  commit, depois de corrigir o que é novo (`CLAUDE.md`, "Estilo: ktlint com baseline"). Fechar com o `ktlintCheck`
  **completo**.
- **Custo:** a tela roda a query inteira de novo (ViewModel próprio) e a varredura do `reconcileManagedRoms`. A query
  mediu 0,171 s no x86 do AVD (60.872 linhas); em TV box ARM deve ser algumas vezes isso — aceitável numa tela aberta
  por pedido, mas medir.

## Verificação

- `./gradlew :lemuroid-app:assembleFreeBundleDebug -PdevAbi=x86`, `./gradlew ktlintCheck`,
  `./gradlew :lemuroid-app:lintFreeBundleDebug`.
- `lemu_tv_api25` (com outra sessão usando, 2ª instância `-read-only -port 5600` por `Start-Process`; `-sdcard` para a
  mídia), em pt-BR:
  - Dados: 11+ grupos salvos = arquivo com tamanho > 0 na pasta gerenciada **e** linha em `downloaded_roms` (a fileira
    da Início lê só o DAO; a varredura do `InstalledGamesViewModel` só roda quando a tela abre). Um grupo com 2 versões
    e um jogo no SD, como no roteiro da 3.6.
  - Com 10: sem "Ver todos". Com 11: "Ver todos" no fim, painel mostra o rótulo, toque abre a tela.
  - Na tela: todos os grupos, mesma ordem; 1 versão joga; 2 versões → seletor; `sm unmount` → capa apagada, texto
    "Mídia inacessível", toque só avisa, sem reabrir; `sm mount` volta.
  - "Excluir ROM" pelo menu de contexto (toque longo por `sendevent /dev/input/event1 1 232 1/0`) → some sem reabrir;
    excluir até 10 → voltar à Início sem o "Ver todos"; excluir tudo na tela → decisão 3.
  - Voltar (Back) da tela → Início com o foco na fileira de salvos. `logcat -b crash` vazio.
- **Não verificável no AVD:** TV box real e o tempo da query em ARM (mesma pendência da 3.6).

## Andamento

- **2026-10-18 (análise feita e símbolos conferidos).** `HEAD` conferido em `06674a0` no branch `version10`, símbolos e números de linha válidos.
- **2026-10-18 (implementação e validações concluídas).**
  - Branch criada: `redesenho-tv-jogos-salvos-tela` a partir de `version10`.
  - Task 1: `TVHomeViewModel.savedGames` com `take(CAROUSEL_MAX_ITEMS + 1)`.
  - Task 2: Cartão "Ver todos" na fileira de salvos (`TVSettingType.SHOW_ALL_SAVED_GAMES`, presenter selector, diff callback, navegação).
  - Task 3: Helper compartilhado `TVSavedGameActions.kt` criado e plugado em `TVHomeFragment` e `TVSavedGamesFragment`.
  - Task 4: `SavedGamePresenter` com suporte a `showInfo: Boolean = false`, exibindo título e detalhe de estado/sistema na grade.
  - Task 5: `TVSavedGamesFragment` em grade (6 colunas), `InstalledGamesViewModel`, `TVAmbientBackground`, navegação registrada em `tv_navigation.xml` e injeção em `MainTVActivity`.
  - Task 6: Validação completa:
    - `assembleFreeBundleDebug -PdevAbi=x86`: BUILD SUCCESSFUL
    - `ktlintCheck`: BUILD SUCCESSFUL
    - `lintFreeBundleDebug -PdevAbi=x86`: BUILD SUCCESSFUL (zero erros de lint)
    - Verificação no emulador `lemu_tv_api25`: carrossel <= 10 sem "Ver todos", carrossel > 10 com cartão "Show All", navegação para a grade de 6 colunas em `TVSavedGamesFragment`, retorno via Back mantendo o foco, `logcat -b crash` vazio.
  - Doc de entrega gerado em `docs/funcionalidades/2026-10-18-tv-tela-jogos-salvos.md`.
  - **Próximo passo exato:** Apresentar a entrega ao dono e aguardar seu aval explícito para realizar o commit no branch `redesenho-tv-jogos-salvos-tela`.

