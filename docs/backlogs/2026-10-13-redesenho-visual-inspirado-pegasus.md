# [BACKLOG] Redesenho visual do Lemuroid inspirado nos temas do Pegasus

**Data:** 2026-10-13
**Status:** Fase 1, 2.1 (Sistemas) e 2.2 (Catálogo) commitadas (`06c7911`). Entrega 2.3 (Início + moldura
deitada) implementada e medida em 14/10/2026 (A/B sem regressão), sem commit. Pendente: abrir o menu
do jogo à mão.
**Origem:** caminho C do estudo [`2026-10-13-estudo-pegasus-frontend.md`](2026-10-13-estudo-pegasus-frontend.md).
Pedido do dono: visual mais moderno e atraente, sem alterar funcionalidade nem o core.

## Como usar este documento

- **Uma entrega por sessão.** Cada sessão lê este doc, executa a próxima entrega e atualiza a seção
  "Andamento" com o que foi feito e o próximo passo.
- **Legenda de caminhos:**
  - `M/` = `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/`
  - `TV/` = `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/`
  - `APP/` = `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/`
  - `LIB/` = `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/`
- **(S)** marca ponteiro que veio de subagente e não foi aberto por quem escreveu o plano. Abrir o arquivo
  antes de editar ou de escrever código sobre ele.

## Objetivo e decisões do dono

**Objetivo:** levar às telas do Lemuroid a linguagem visual dos temas do Pegasus. **Restrição:** nenhuma
funcionalidade muda e o core fica intocado.

Decisões tomadas em 13/10/2026:
- Começar pelo **mobile** (Compose). A TV (Leanback) vem depois.
- A direção visual é escolhida por **mockups** (Fase 0), antes de qualquer código.
- **Substituir tela a tela**, sem chave "clássico/novo". Os efeitos pesados desligam sozinhos em aparelho fraco.
- **O download sob demanda e a área de Jogos salvos são obrigatórios no visual novo** (seção própria abaixo).
- **Direção A (vitrine)**, escolhida depois dos mockups da Fase 0.
- **Celular deitado = desenho da TV** (destaque, fileiras e carrossel de sistemas); em pé, o desenho A. O app
  troca sozinho conforme a orientação. Detalhes em "Especificação visual › Deitado".

## Regra central: o que não muda

1. **Roteamento e fluxos.** Ficam intocados os lambdas do `NavHost` em `M/feature/main/MainActivity.kt:391-700`
   (`onGameClick`, `onGameLongClick`, o roteamento próprio de Jogos salvos `:426-444` e da Busca `:474-508`),
   os diálogos de salvar/excluir/crash/atualização e a lógica de `MainGameContextActions`,
   `GameVariantsModal` e `SaveQueueModal`. Só a aparência deles muda.
2. **Assinaturas das telas.** `HomeScreen`, `GamesScreen`, `MetaSystemsScreen`, `FavoritesScreen`,
   `SearchScreen` e `InstalledGamesScreen` mantêm os parâmetros. Muda o corpo de layout (ex.: o overload
   sem estado `HomeScreen.kt:148`). Parâmetro novo só opcional e só de apresentação.
3. **ViewModels, DAOs, banco e manifest.** Sem mudança de comportamento, sem coluna nova, sem migração. É
   permitido expor à UI um dado que já existe (leitura aditiva), sem mexer em query nem em escrita.
4. **Fora do redesenho:** a tela do jogo, o menu do jogo, o pad virtual e a estrutura dos Ajustes. Eles só
   herdam cores e tipografia do tema.
5. **Dependências.** Nenhuma versão sobe e nenhuma biblioteca entra. O único acréscimo é um arquivo de fonte.

## Download sob demanda e Jogos salvos no visual novo

### Por que este ponto tem seção própria
Nos temas do Pegasus todo jogo já está em disco. Não existe "jogo ainda não salvo", nem fila, nem uma área
de jogos salvos. O Lemuroid é o oposto: 61.040 jogos no catálogo e quase nenhum no aparelho. Esses dois
elementos não vêm de tema nenhum; são desenhados por nós e têm de ficar no centro do visual novo.

### Como funciona hoje (tem de continuar igual)
- **Vocabulário do app (pt-BR):** baixar é "Salvar", a fila é "fila de salvamento" e a aba se chama
  "Jogos salvos" (`title_installed`, `lemuroid-app/src/main/res/values-pt-rBR/strings.xml:4`).
- **Fluxo de salvar (mobile):** tocar num jogo não salvo abre a confirmação (`MainActivity.kt:812-836`), que
  chama `saveQueueManager.enqueue(game)` e abre o `SaveQueueModal` (`:874-879`). Ao terminar, aparece
  "Jogar agora?" (`:913-934`). A barra superior mostra o ícone e a barra de progresso da fila.
- **Estado da fila:** `SaveQueueManager.entries: StateFlow<List<SaveQueueEntry>>`
  (`APP/shared/roms/SaveQueueManager.kt:60`). Cada entrada tem `systemId`, `fileName`, `state`
  (`QUEUED`, `SAVING`, `PAUSED`, `SAVED`, `ERROR`) e `progress` (`:28-40`). A fila persiste entre sessões.
- **Aba Jogos salvos (mobile):** rota `INSTALLED`, tela `M/feature/installed/InstalledGamesScreen.kt:35`.
  - Dados: `InstalledGamesViewModel.installedGames` (`:123`), vindos de
    `GameDao.observeInstalledGameGroups` (`LIB/library/db/dao/GameDao.kt:361-401`).
  - Conteúdo: downloads do catálogo e ROMs locais, agrupados por `(systemId, title)` e ordenados pela última
    ação (jogou ou salvou).
  - Busca na própria aba, inclusive por nome de sistema (`InstalledGamesViewModel.kt:106-121`).
  - Cartão com chip do sistema, contagem de versões salvas e aviso de mídia inacessível
    (`LemuroidInstalledGameCard.kt:38-147`).
  - Regras de toque em `MainActivity.kt:426-444`: mídia inacessível avisa; mais de uma versão salva abre o
    seletor só com as salvas; uma versão joga direto.
  - Requisitos R1–R9 e matriz de aceite em `docs/backlogs/2026-10-08-aba-jogos-instalados-salvos.md`.
- **TV:** não tem área de Jogos salvos nem fila. Salvar é um diálogo bloqueante com progresso
  (`TV/game/TVRomDownloadDialog.kt` (S)). Nenhum arquivo de `TV/` usa `SaveQueueManager` nem
  `observeInstalledGameGroups`.

### Desenho proposto

**1. O estado de salvamento aparece no próprio cartão**, em todas as telas e no destaque da Home:

| Estado | Como aparece no cartão | De onde vem |
|---|---|---|
| Não salvo | Sem selo (é o caso da maioria) | Ausência nas duas fontes abaixo |
| Na fila | Ícone de espera sobre a capa | `SaveQueueEntry.state == QUEUED` |
| Salvando | Anel de progresso com a porcentagem | `SAVING` + `progress` |
| Pausado | Ícone de pausa | `PAUSED` |
| Erro | Ícone de erro | `ERROR` |
| Salvo | Selo de salvo (como hoje) | `downloadedGameKeys` (`MainViewModel`) |

- **Dados:** a `MainActivity` já tem o `SaveQueueViewModel`. Derivar dele um mapa de `"$systemId/$fileName"`
  (a mesma chave de `Game.downloadKey`) para a entrada da fila e passá-lo às telas como parâmetro opcional,
  ao lado de `downloadedGameKeys`. Nada muda no `SaveQueueManager`.
- **Destaque da Home:** o botão mostra "Jogar", "Salvar" ou "Salvando 42%". O toque chama o mesmo
  `onGameClick` de hoje.
- **Cuidado de implementação:** o `progress` muda várias vezes por segundo. Ler o estado por chave dentro do
  item e arredondar para 1%, para não recompor a grade inteira a cada atualização.

**2. Jogos salvos como destino de primeira classe (mobile):**
- A aba continua na barra inferior, com o mesmo ViewModel, a mesma query e as mesmas regras R1–R9.
- A tela é redesenhada como a "biblioteca" do aparelho: mesmo campo de busca, cartão novo de capa mantendo
  o chip do sistema, a contagem de versões e o estado de mídia inacessível.
- **Tem de funcionar sem rede.** A capa vem do cache do Coil ou é a capa gerada; nunca imagem quebrada.
- **Proposta (aparece nos mockups, o dono decide): fileira "Jogos salvos" na Home**, a primeira depois do
  destaque, com os 10 grupos mais recentes e um atalho para a aba. É o único acréscimo além da troca de
  aparência. Cuidados:
  - **Não** instanciar `InstalledGamesViewModel` na Home: o `init` dele varre a pasta de ROMs
    (`InstalledGamesViewModel.kt:61-97`).
  - Reaproveitar o fluxo do DAO. A query percorre a tabela `games` inteira (61 mil linhas) com funções de
    texto em `fileUri`, e roda de novo a cada mudança em `games` ou `downloaded_roms`. Começar a coletar só
    depois do primeiro quadro e medir no `lemu_api21_1gb` antes de adotar.

**3. Fila de salvamento:** `SaveQueueModal` no visual novo e indicador da barra superior mantido. Opcional:
faixa compacta "Salvando agora" no topo da Home, que abre o mesmo modal.

**4. TV:**
- `TVRomDownloadDialog` no visual novo, com o mesmo fluxo.
- **Fileira "Jogos salvos" na Home da TV é funcionalidade nova na TV**, não redesenho: a TV não tem seletor
  de versões salvas nem aviso de mídia inacessível. Fica como entrega própria (3.6), com doc de feature, e
  só começa com o aval do dono.

### Onde isso entra nas fases
- **Fase 0:** os mockups mostram os seis estados do cartão, a tela Jogos salvos e a Home com e sem a fileira.
- **Fase 1:** componente do selo/estado de salvamento.
- **Fase 2:** todo cartão recebe o estado (2.2 em diante); fileira na Home (2.3); Jogos salvos tem entrega
  própria (2.4), separada de Favoritos e Busca.
- **Fase 3:** diálogo de salvar (3.2) e fileira de Jogos salvos na TV (3.6).

### Verificação específica
- Enfileirar 3 jogos e conferir no cartão, no Catálogo, na Home, na Busca e no destaque: na fila → salvando
  com porcentagem → salvo. Pausar, retomar e cancelar pelo modal têm de refletir no cartão.
- Matar o app no meio de um salvamento e reabrir: os estados voltam (a fila é persistida).
- Jogos salvos: refazer a matriz de aceite da seção 8 de `2026-10-08-aba-jogos-instalados-salvos.md`, sem
  mudança de resultado.
- Modo avião: a tela Jogos salvos abre, mostra capas e os jogos iniciam.

## Restrições medidas (o que o desenho precisa respeitar)

| Restrição | Fato | Onde |
|---|---|---|
| Versões | Compose BOM 2024.02.02 (Compose 1.6, material3 1.2), Kotlin 2.0.21, Coil 2.6.0, Leanback 1.1.0-rc01, minSdk 21 | `buildSrc/src/main/java/deps.kt:6, 20, 28, 146-147` |
| Desfoque | `Modifier.blur` só funciona no Android 12+. O fundo "desfocado" será a capa decodificada em ~32 px, ampliada, com degradê por cima. | plataforma |
| Aparelho fraco | `CoverUtils.isLowRamDevice` já reduz cache, usa RGB_565, decodifica em 256 px e desliga o crossfade. Hoje nada desliga animação. | `APP/shared/covers/CoverUtils.kt:27-31, 46-86` |
| Dados do jogo | Só há título, sistema, capa e popularidade. `developer` é nulo em todo jogo do catálogo. Não há descrição, ano, gênero nem screenshot. | `LIB/library/db/entity/Game.kt:53-71`, `LIB/library/catalog/ManifestQuickLoader.kt:416` |
| Capas | 38% dos 45.780 jogos visíveis não têm capa (ZX Spectrum 96%, Atari 800 100%, GameCube 80%, SNES 13%). Hoje o substituto é um quadrado com iniciais em cor aleatória. | `catalog_manifest.txt`, `CoverUtils.kt:88-111` |
| Arte de sistema | 79 PNGs normais e 79 de foco, `game_system_*`: 69 no formato 277×192 / 333×230 com moldura branca embutida, 10 fora do padrão (ver Andamento, 2.1). `MetaSystemID.color()` é só um matiz por ordinal, não a cor da marca. | `LIB/library/SystemLogoResolver.kt`, `LIB/library/MetaSystemID.kt:408` |
| Tema | `AppTheme` só define cores. Não há tipografia, formas nem fonte própria, e o tema é sempre escuro. `Color.kt` existe em duas cópias idênticas (`src/debug` e `src/release`). | `M/shared/compose/ui/LemuroidTheme.kt:75-84` |
| Processo `:game` | `AppTheme` também é usado em `BaseGameActivity` e `GameMenuActivity` (S), que rodam no `:game`. Esse processo nunca mostra capa e precisa de cada MB para o core, então o Coil não deve ser inicializado nele. | `docs/funcionalidades/performance-dispositivos-fracos.md:111-125` |
| Listas | A chave de item em lista paginada tem de ser `"id_$id"`/`"idx_$it"`, senão há colisão e crash. | `M/feature/games/GamesScreen.kt:93, 127` |
| Controle remoto | A UI mobile roda em TV box sem Android TV e é navegada por D-pad. `LemuroidSystemCard` já trata foco. | `M/shared/compose/ui/LemuroidSystemCard.kt:47` |
| Banco | `fallbackToDestructiveMigration()` está ativo: uma migração esquecida apaga favoritos. Por isso o plano não cria coluna. | `APP/LemuroidApplicationModule.kt:262` |

## Referências visuais do Pegasus

Só a linguagem visual serve de inspiração. Nenhum QML nem asset de tema é copiado: o Flixnet é
CC-BY-NC-SA, o gameOS é GPL-3.0 e o neoretro não declara licença.

- **gameOS e Flixnet ("vitrine"):** jogo em destaque no topo, fileiras horizontais, fundo com a arte do item em foco.
- **neoretro e COLORFUL ("cores por sistema"):** cada sistema com sua cor e a arte do console.
- **switchOS ("blocos limpos"):** blocos grandes, layout limpo, tipografia simples.

Elementos que o redesenho adota, qualquer que seja a direção escolhida:
1. Fundo ambiente com a arte do jogo em destaque ou em foco.
2. Destaque (hero) na Home.
3. Fileiras horizontais na Home.
4. Identidade por sistema: paleta curada mais a arte de console que já existe.
5. Fonte própria e hierarquia de texto.
6. Cartão de capa com estados visíveis: pressionado, foco, estado de salvamento, variantes.
7. **Capa gerada** para jogo sem arte: cor do sistema, arte do sistema e título.
8. Movimento sutil, desligado em aparelho fraco.

## Fases

### Fase 0 — Protótipo visual (sem código do app)
- **Entrega:** uma página de mockups (artifact HTML) em moldura de celular (360×800) e de TV (1280×720),
  com 2–3 direções: vitrine, cores por sistema e blocos limpos.
- **Telas por direção:** Home (com e sem a fileira "Jogos salvos"), Sistemas, catálogo do SNES (com capas),
  catálogo do ZX Spectrum (quase sem capas), **Jogos salvos** e Home da TV.
- **Também mostra:** os seis estados de salvamento do cartão e a capa gerada.
- **Dados reais:** cerca de 40 linhas do `catalog_manifest.txt` e os PNGs `game_system_*`.
- **Decisões que saem daqui:** direção, paleta, fonte (licença OFL, ex.: Inter ou Barlow Condensed),
  raios e espaçamentos, colunas, rótulos na barra inferior e a fileira "Jogos salvos" na Home.
- **Saída:** seção "Especificação visual" neste doc, com os tokens (cores, tipos, espaços, movimento).
- **Pronto quando:** o dono escolher uma direção.

### Fase 1 — Fundação (as telas ainda não mudam de layout)
Arquivos em `M/shared/compose/ui/`:
- `Color.kt`: mover para `src/main` e apagar as duas cópias idênticas de `src/debug` e `src/release`.
- `LemuroidTheme.kt` (`AppTheme`): passar `typography` e `shapes` e fornecer os `CompositionLocal` novos.
  Nada aqui pode tocar no Coil, por causa do `:game`.
- Novos `Type.kt` (Outfit em `res/font`), `Shape.kt` e `Dimens.kt`. O `Dimens.kt` tem dois conjuntos de
  tamanho para o desenho deitado: compacto (altura < 480 dp, celular) e grande (TV box).
- Novo `SystemPalette.kt`: mapa `MetaSystemID` → cor, com `MetaSystemID.color()` como reserva.
- Novo `VisualQuality.kt`: `FULL` ou `LITE`, calculado **uma vez** a partir de `CoverUtils.isLowRamDevice`
  e da escala de animação do sistema. Todo efeito novo consulta esse valor.
- Componentes novos:
  - `AmbientBackdrop`: capa em ~32 px mais degradê; em `LITE`, só degradê na cor do sistema.
  - `GeneratedCover`: substitui o quadrado de iniciais dentro de `LemuroidGameImage` (S).
  - Selo/estado de salvamento (os seis estados da seção acima).
  - Modificador `focusHighlight` (escala e borda no foco) e `SectionHeader`.
- **Antes de mexer:** medir e registrar aqui a linha de base de desempenho (ver "Verificação").

### Fase 2 — Telas mobile (uma entrega por item, nesta ordem)
| # | Tela | Arquivos | O que muda | O que tem de continuar igual |
|---|---|---|---|---|
| 2.1 | Sistemas | `M/feature/systems/MetaSystemScreen.kt:42`, `LemuroidSystemCard.kt`, `LemuroidSystemImage.kt` | Bloco com a cor do sistema, a arte do console, o nome e a contagem | Navegação para `systems/{id}` e a troca de arte no foco |
| 2.2 | Catálogo do sistema | `M/feature/games/GamesScreen.kt`, `LemuroidGameGridItem.kt` (S), `LemuroidGameListRow.kt` (S) | Cabeçalho do sistema, cartões novos com estado de salvamento, grade adaptativa no lugar de 3 colunas fixas | Chaves `"id_$id"`, volta ao topo no `ON_RESUME` (`:68`, `:109`), pref `catalog_layout_grid` (`:54`), ordenação, selo de variantes |
| 2.3 | Home | `M/feature/home/HomeScreen.kt:148-311` | `LazyColumn` com destaque e fileiras `LazyRow` no lugar de `homeGridSection` (`:284`). O destaque é o primeiro de `recentGames` ou, se vazio, de `discoveryGames`. Fileira "Jogos salvos". **Deitado:** desenho da TV, com o trilho lateral e o carrossel de Sistemas | Avisos do topo (`:213-255`), tela de carregamento (`:188`), diálogos, o toque chama o mesmo `onGameClicked`; o carrossel usa a rota `systems/{id}` que já existe |
| 2.4 | **Jogos salvos** | `M/feature/installed/InstalledGamesScreen.kt`, `LemuroidInstalledGameCard.kt` | Tela no visual de biblioteca, cartão novo | Busca na aba, ordenação pela última ação, chip do sistema, contagem de versões, mídia inacessível, regras de toque (`MainActivity.kt:426-444`), requisitos R1–R9 |
| 2.5 | Favoritos e Busca | `M/feature/favorites/FavoritesScreen.kt` (S), `M/feature/search/SearchScreen.kt` (S), `LemuroidGameCard.kt` (S), `LemuroidEmptyView.kt` (S) | Cartões novos e estados vazios com ícone | Filtros da busca, escopo "só salvos", roteamento da busca (`MainActivity.kt:474-508`) |
| 2.6 | Moldura | `M/feature/main/MainTopBar.kt` (`:55-212`), `MainNavigationBar.kt` (S), `MainGameContextActions.kt` (S), `GameVariantsModal.kt` (S), `SaveQueueModal.kt` (S) | Barra superior, barra inferior e folhas inferiores no visual novo | Indicadores da fila, ações do menu de contexto, pausar/retomar/cancelar, as 5 abas na ordem de `MainNavigationRoutes` |

O cabeçalho do catálogo (2.2) precisa do sistema. A rota já o tem (`MainActivity.kt:530-541`), então ele
entra como parâmetro opcional da `GamesScreen`.

**Ordem com o desenho deitado:** o trilho lateral é peça da moldura, mas a Início deitada (2.3) depende dele.
O trilho entra como primeira parte da 2.3 e já vale para todas as telas deitadas; a 2.6 fica com o visual da
barra superior, da barra inferior e das folhas. As entregas 2.2, 2.4 e 2.5 conferem a própria tela deitada
(trilho e número de colunas).

### Fase 3 — TV (Leanback personalizado, sem migrar para Compose)
A TV fica em Leanback porque é mais leve em TV box e não exige subir o Compose.
- **3.1 Tema:** `lemuroid-app/src/main/res/values/leanback-themes.xml` (S) e `leanback-colors.xml` (S; há
  cópia em `src/debug/res/values`), mais `TV/shared/TVCardFocusHighlight.kt` (S). A fonte é aplicada por
  código (`ResourcesCompat.getFont`), porque `android:fontFamily` com `res/font` só vale no Android 8+.
- **3.2 Cartões e diálogo de salvar:** `TV/shared/GamePresenter.kt` passa a capa retrato no lugar do
  quadrado de 176 dp (`:28`). `TV/home/SystemPresenter.kt:31` usa `SystemPalette`. Ajustar as 4 colunas de
  `TV/games/TVGamesFragment.kt:34` e `TV/favorites/TVFavoritesFragment.kt:30`. `TVRomDownloadDialog` (S) no
  visual novo, com o mesmo fluxo.
- **3.3 Fundo ambiente:** `BackgroundManager` mais `setOnItemViewSelectedListener` em `TVHomeFragment`,
  `TVGamesFragment`, `TVFavoritesFragment` e `TVSearchFragment`. Hoje nenhum dos dois é usado. Um ajudante
  único (`TVAmbientBackground`) faz a espera de ~300 ms, carrega o bitmap minúsculo e, em `LITE`, usa só a
  cor do sistema.
- **3.4 Painel do item em foco** na Home (título e sistema). Avaliar depois da 3.3.
- **3.5 (opcional) Selo de salvo na TV.** Exige expor `DownloadedRomDao.observeAllDownloadedKeys()` (S) nos
  ViewModels da TV.
- **3.6 Fileira "Jogos salvos" na TV.** Funcionalidade nova na TV; só com o aval do dono. Precisa do fluxo
  `observeInstalledGameGroups`, de um seletor simples para mais de uma versão salva e do aviso de mídia
  inacessível. Gera doc de feature próprio.

### Fase 4 — Arte extra (opcional, só depois de medir)
- **Medir:** amostra de 300 jogos com capa do libretro-thumbnails e `HEAD` em `Named_Snaps`, `Named_Titles`
  e `Named_Logos` (essas pastas existem nos repos do SNES e do ZX Spectrum). Resultado: cobertura por sistema.
- **Se compensar:** derivar a URL em tempo de execução, trocando `/Named_Boxarts/` por `/Named_Snaps/`, só
  no destaque e no fundo ambiente. Não cria coluna nem migração. As falhas já ficam memorizadas pelo
  `ThrottleFailedThumbnailsInterceptor` (S).
- Alcance máximo: 13.807 linhas do catálogo (as que têm capa do libretro-thumbnails).

## Verificação (em toda entrega)

1. **Build e estilo:** `./gradlew :lemuroid-app:assembleFreeBundleDebug -PdevAbi=x86_64` (x86 para o AVD de
   TV), `./gradlew ktlintCheck` e `./gradlew :lemuroid-app:lintFreeBundleDebug`.
2. **Aparelhos:** `lemu_api21_1gb` (modo `LITE`), `lemu_api25_2gb`, `lemu_tv_api25` e o SM-A127M.
3. **Roteiro funcional, idêntico antes e depois:**
   - jogo salvo → joga;
   - jogo não salvo → confirmação → fila → "Jogar agora?";
   - jogo com variantes → modal;
   - toque longo → retomar, reiniciar, favoritar, atalho, excluir ROM, excluir do catálogo;
   - ordenação, grade/lista, busca com escopo e "só salvos";
   - Jogos salvos: ordenação, busca, versões, mídia inacessível;
   - TV: jogar, salvar, menu de contexto, busca, ajustes.
4. **Controle remoto na UI mobile:** percorrer cada tela só com D-pad; o foco tem de estar sempre visível.
5. **Desempenho:** `adb shell dumpsys gfxinfo <pkg> reset`, rolar os catálogos do ZX Spectrum (10.400) e do
   SNES, depois `dumpsys gfxinfo <pkg>` e `dumpsys meminfo <pkg>`. Critério proposto contra a linha de base
   da Fase 1, no `lemu_api25_2gb`: quadros lentos no máximo 5 pontos percentuais acima e memória (PSS) no
   máximo 20 MB acima. No `lemu_api21_1gb`, 2 min de rolagem sem `OutOfMemoryError`.
6. **Capturas antes/depois** de cada tela, anexadas ao doc da entrega.
7. **Salvamento e Jogos salvos:** a "Verificação específica" da seção própria.
8. **Deitado:** girar o celular em cada tela entregue; o desenho troca e nada some. Girar com o diálogo de
   salvar aberto e no meio de um salvamento: o comportamento tem de ser o mesmo de antes da entrega. No
   `lemu_api25_2gb` deitado, navegar só pelas setas: o foco fica visível e o destaque da Início acompanha o
   foco. O `lemu_tv_api25` não serve aqui: ele abre a UI Leanback, não a mobile.

## Documentação

- Cada entrega gera um `[FEATURE]` em `docs/funcionalidades/AAAA-MM-DD-redesenho-<tela>.md`, com capturas
  e números.
- Na Fase 1, o `CLAUDE.md` do projeto ganha a seção do design system com as regras: não usar
  `Modifier.blur`, efeito novo atrás de `VisualQuality`, `AppTheme` sem Coil, chave de item em lista.

## Riscos

| Risco | Mitigação |
|---|---|
| Efeitos pesam em TV box de 1–2 GB | `VisualQuality.LITE` automático, imagem de fundo em ~32 px, critério de desempenho por entrega |
| Foco invisível ou preso com D-pad | `focusHighlight` em todo cartão e item 4 da verificação |
| 38% dos jogos sem capa deixam a grade pobre | `GeneratedCover` desenhada na Fase 0, testada no catálogo do ZX Spectrum |
| Progresso do salvamento recompõe a grade inteira | Ler o estado por chave dentro do item e arredondar para 1% |
| Fileira "Jogos salvos" na Home pesa no boot | Coletar depois do primeiro quadro; medir no `lemu_api21_1gb`; sem `InstalledGamesViewModel` na Home |
| Tema compartilhado quebra o menu do jogo (`:game`) | `AppTheme` sem Coil; abrir um jogo e o menu em toda entrega da Fase 1 |
| Fonte aumenta o APK | Um arquivo de fonte variável ou 2–3 pesos; medir o APK de release antes e depois |
| Fonte não aparece na TV com Android 7 | Aplicar por código, não por XML (Fase 3.1) |
| Dois desenhos da Início (em pé e deitado) dobram o trabalho e o teste | Fileiras, cartões e selos são os mesmos componentes; só o arranjo muda. Item 8 da verificação em toda entrega |
| Celular deitado tem só ~360 dp de altura | Sem título sob as capas nas fileiras, tamanhos compactos abaixo de 480 dp, ações que recolhem ao rolar |

## Análise registrada

### Símbolos abertos
- `M/feature/home/HomeScreen.kt` — overload sem estado (`:148-282`): `LazyVerticalGrid(Adaptive(140.dp))`;
  avisos no primeiro item (`:213-255`); carregando (`:188-202`); `homeGridSection` (`:284-311`).
- `M/feature/games/GamesScreen.kt::GamesScreen` (`:40-142`) — grade `Fixed(3)` (`:78-79`) ou lista (`:119`)
  conforme a pref (`:54-57`); chaves (`:93`, `:127`); volta ao topo no `ON_RESUME` (`:68-76`, `:109-117`).
- `M/feature/main/MainActivity.kt` — `NavHost` (`:391-550`): HOME `:396`, INSTALLED `:414`, FAVORITES `:448`,
  SEARCH `:460`, SYSTEMS `:515`, SYSTEM_GAMES `:529`. Confirmação de salvar `:812-836`; `SaveQueueModal`
  `:874-879`; `GameVariantsModal` `:881-911`; "Jogar agora?" `:913-934`.
- `M/shared/compose/ui/LemuroidTheme.kt::AppTheme` (`:75-84`) — só `colorScheme`.
- `M/shared/compose/ui/LemuroidSystemCard.kt` (`:23-63`) — `onFocusChanged` (`:47`) troca a arte pela de foco.
- `M/feature/installed/` — `InstalledGamesScreen.kt` (`:35-117`), `InstalledGamesViewModel.kt` (`:27-147`),
  `LemuroidInstalledGameCard.kt` (`:38-147`).
- `M/feature/main/SaveQueueViewModel.kt` (`:17-57`) — `entries`, `hasActiveOrQueued`, `activeProgress`, `hasErrors`.
- `APP/shared/roms/SaveQueueManager.kt` (`:28-63`) — estados, `SaveQueueEntry`, `entries`, `justCompleted`.
- `LIB/library/db/dao/GameDao.kt::observeInstalledGameGroups` (`:361-401`) — CTE sobre `games` inteiro com
  `LEFT JOIN downloaded_roms`.
- `LIB/library/db/entity/Game.kt` (`:53-71`) e `SaveQueueItem.kt`.
- `APP/shared/covers/CoverUtils.kt` (`:20-112`) — `isLowRamDevice`, `buildImageLoader`, `getFallbackDrawable`.
- `LIB/library/SystemLogoResolver.kt` (`:5-92`) e `LIB/library/MetaSystemID.kt` (`:10`, `:408-409`).
- `buildSrc/src/main/java/deps.kt` (`:4-6`, `:18`, `:20`, `:28`, `:146-147`).
- `M/feature/main/MainNavigationRoutes.kt` (`:38-174`) — `MainNavigationRoutes` define as 5 abas da barra
  inferior: Início, Favoritos, Sistemas, Jogos salvos, Buscar. `SETTINGS` tem `showBottomNavigation = false`.
- `M/feature/main/MainTopBar.kt` (`:55-212`) — título da rota (ou o campo de busca, na rota `SEARCH`), voltar
  quando há `parent`, e as ações: fila (com `hasSaveQueueActive`), Ajuda, Sincronizar (com
  `saveSyncEnabled`) e Configurações (com `showBottomNavigation`). Abaixo, barras de progresso da operação e
  da fila.
- `lemuroid-app/src/main/AndroidManifest.xml:60-63` — a `MainActivity` não declara `screenOrientation` nem
  `configChanges`: gira e é recriada na rotação.
- `M/feature/game/MobileGameScreen.kt:71-72` — a tela do jogo decide a orientação por
  `constraints.maxWidth > constraints.maxHeight`.
- A preferência grade/lista do catálogo: chave em `res/values/keys.xml:26`, chave nas Configurações
  (`M/feature/settings/general/SettingsScreen.kt:232`), lida em `GamesScreen.kt:54`.
- Docs: `docs/backlogs/2026-10-05-subir-padkit-1-0-0-compose-kotlin.md`,
  `docs/backlogs/2026-10-08-aba-jogos-instalados-salvos.md` (`:30-110`),
  `docs/funcionalidades/performance-dispositivos-fracos.md` (`:108-126`).

### Comandos que provaram algo (rodados na raiz do repo)

| Comando | Resultado |
|---|---|
| `awk -F'\|' '$5=="1"{split($1,p,"/"); t[p[1]]++; T++; if($3==""){n[p[1]]++; N++}} END{…}' lemuroid-app/src/main/assets/catalog_manifest.txt` | 45.780 representantes, 17.374 sem capa (38,0%). zxspectrum 5.336/5.556, atari800 5.322/5.322, gc 680/846, lowresnx 883/883, snes 315/2.472 |
| `awk -F'\|' '$3 ~ /libretro-thumbnails/' <manifest> \| wc -l` | 13.807 capas do libretro-thumbnails |
| `diff lemuroid-app/src/debug/java/…/ui/Color.kt lemuroid-app/src/release/java/…/ui/Color.kt` | idênticos |
| `grep -rn "BackgroundManager\|OnItemViewSelectedListener\|headersState" <TV/>` | nenhuma ocorrência |
| `grep -rn "setMainImageDimensions\|ImageCardView(" <TV/>` | `GamePresenter.kt:28,34`, `SystemPresenter.kt:25,37`, `SettingPresenter.kt:17,27` |
| `grep -n numberOfColumns` em `TVGamesFragment.kt` e `TVFavoritesFragment.kt` | `:34` e `:30`, ambos 4 |
| `ls retrograde-app-shared/src/main/res/drawable/game_system_*.png` e `file` | 79 normais (277×192) e 79 `_hover` (333×230) |
| `grep -n "fallbackToDestructiveMigration\|version = \|MANIFEST_SCHEMA_VERSION ="` | `LemuroidApplicationModule.kt:262`; `RetrogradeDatabase.kt:36` = 24; `ManifestQuickLoader.kt:157` = 38 |
| `grep -rl "observeInstalledGameGroups\|SaveQueueManager" <APP/>` | só arquivos de `mobile/`, `shared/roms/`, `NotificationsManager` e `LemuroidApplicationModule`; nenhum de `tv/` |
| `grep 'name="title_installed"' …/values-pt-rBR/strings.xml` | `:4` "Jogos salvos"; `:314` "Salvar"; `:318` "Adicionar à fila de salvamento?" |
| API do GitHub, `contents/` de `libretro-thumbnails/Nintendo_-_Super_Nintendo_Entertainment_System` e `Sinclair_-_ZX_Spectrum` | `Named_Boxarts`, `Named_Logos`, `Named_Snaps`, `Named_Titles` |
| `grep -rn "requestedOrientation\|setRequestedOrientation\|SCREEN_ORIENTATION" lemuroid-app/src/main` | nenhuma ocorrência |
| `unzip -p ~/.gradle/caches/modules-2/files-2.1/androidx.compose.material3/material3-android/1.2.1/*/material3-release.aar classes.jar`, depois `unzip -l` com `grep` | `NavigationRailKt`, `NavigationRailItemDefaults`, `EnterAlwaysScrollBehavior` presentes |

### Hipóteses descartadas
- **"Usar `Modifier.blur` no fundo."** Só funciona no Android 12+, e o alvo inclui Android 5–7. Fica a capa
  reduzida com degradê.
- **"Migrar a TV para Compose."** Exigiria subir o Compose (hoje 1.6) e adicionar `androidx.tv`; o padrão de
  minSdk das bibliotecas AndroidX novas é 23, contra o 21 do projeto (conferir por artefato se um dia
  voltar à mesa). O Leanback também é mais leve em TV box.
- **"Guardar screenshot no banco ou no manifest."** Coluna nova pede migração Room, mudança no
  `PrebuiltDbGenerator` e bump de `MANIFEST_SCHEMA_VERSION`, com `fallbackToDestructiveMigration()` ativo.
  Fica a URL derivada em tempo de execução.
- **"Painel de detalhes com descrição, ano e gênero, como nos temas."** O catálogo não tem esses dados.
- **"Usar `MetaSystemID.color()` como cor do sistema."** É um matiz por ordinal; precisa de paleta curada.
- **"Subir o Compose para ganhar transições novas."** Fora do redesenho; tem backlog próprio.
- **"Copiar QML ou assets de temas do Pegasus."** Licenças e tecnologia diferentes; só inspiração.
- **"Instanciar `InstalledGamesViewModel` na Home para a fileira de salvos."** O `init` varre a pasta de ROMs.
- **"Chave clássico/novo nos Ajustes."** Descartada por decisão do dono.
- **Direções B (cores por sistema) e C (blocos limpos).** O dono escolheu a A.
- **"Manter a barra inferior com o celular deitado."** Ela come 64 dos ~360 dp de altura; o trilho lateral
  usa a largura que sobra.

### Ponteiros de subagente ainda não abertos (S)
- Mobile: `M/shared/compose/ui/LemuroidGameCard.kt:28`, `LemuroidGameGridItem.kt:34`,
  `LemuroidGameListRow.kt:28`, `LemuroidGameImage.kt:17` (decode 400 px, 256 em aparelho fraco; aspecto 0,75),
  `LemuroidSmallGameImage.kt:21`, `LemuroidEmptyView.kt:14`, `effects/BounceClickModifier.kt:22`.
- Moldura: `MainNavigationBar.kt:36`, `MainGameContextActions.kt:53`, `GameVariantsModal.kt:40`,
  `SaveQueueModal.kt:47`.
- Telas: `M/feature/favorites/FavoritesScreen.kt:17`, `M/feature/search/SearchScreen.kt:47`,
  `M/feature/home/HomeViewModel.kt:59, 77, 121`, `M/feature/main/MainViewModel.kt:50, 59`.
- TV: `TV/home/TVHomeFragment.kt:38, 58-89, 173-233`, `TV/home/TVHomeViewModel.kt:30-126`,
  `TV/search/TVSearchFragment.kt:29`, `TV/shared/TVCardFocusHighlight.kt:36-57`,
  `TV/game/TVRomDownloadDialog.kt:19-109`, `lemuroid-app/src/main/res/values/leanback-themes.xml:4-28`.
- Outros: `APP/shared/covers/ThrottleFailedThumbnailsInterceptor.kt:8-31` (memoriza até 1.024 URLs que
  falharam, até o processo morrer), `LIB/library/HeavySystemFilter.kt:69-87`,
  `APP/LemuroidApplication.kt:125-161`, `LIB/library/db/dao/DownloadedRomDao.kt:36`.

## Fora de escopo (registrar, não corrigir aqui)

Achados dos levantamentos, a confirmar antes de virar trabalho:
- `CLAUDE.md` diz "Room versão 23", e o código está na 24 (`RetrogradeDatabase.kt:36`).
- A TV não agrupa variantes, não mostra selo de salvo, não tem fila e não aplica o filtro por RAM. São
  diferenças funcionais que este redesenho não altera (exceto a entrega 3.6, se aprovada).
- Possível código morto (S): `RomDownloadDialog`, `HomeDownloadCard`, `FakeScrim`.
- `APP/LemuroidApplication.kt:158-161` (S) tem um segundo `isLowRamDevice`, com critério diferente e sem
  chamadores.
- Os 11 MB de PNG de sistema ficam em `drawable/` sem qualificador de densidade (S).
- Subir Compose/Kotlin continua no backlog próprio (`2026-10-05-subir-padkit-1-0-0-compose-kotlin.md`).

## Especificação visual — direção A (escolhida em 13/10/2026)

**Esta seção é a referência visual.** Os mockups da Fase 0 eram um artifact do claude.ai, que sumiu da conta
duas vezes. Por decisão do dono (13/10/2026), não foram republicados. As pranchas não foram guardadas no
repositório.

| Token | Valor |
|---|---|
| Fundo / superfície / barras | `#0E0F13` / `#1A1C22` / `#14161B` (borda `#23262E`) |
| Texto / apoio | `#F2F0EA` / `#A4A7B0`; apoio sobre o fundo ambiente `#C9CBD2` |
| Destaque | âmbar `#F2B544`, texto sobre ele `#1A1300`; item selecionado em pílula `rgba(242,181,68,0.16)` |
| Erro | `#B3261E` |
| Fonte | Outfit (OFL) 400/500/700. Título de tela 20–22, título do destaque 22–24, seção 15–16, cartão 12, apoio 11 |
| Raio | 10 no cartão em pé, 8 nos cartões do celular deitado; pílula em botão e chip |
| Margem / espaço | 16 em pé, 24 deitado (a partir do trilho) / 12 entre cartões |
| Capa | 3:4 |
| Foco (D-pad) | contorno âmbar 3 px afastado 3 px; escala 1,04 em pé |
| Fundo ambiente | capa do destaque (ou arte do sistema, no catálogo) desfocada + degradê para o fundo; em `LITE`, só o degradê |
| Capa gerada | tom escuro da cor do sistema + arte do console (mistura *screen*) + título |

### Em pé

- **Barra superior:** título da tela e as ações que já existem em `LemuroidTopBarActions`
  (`MainTopBar.kt:151-212`): fila (só com fila ativa), Ajuda, Sincronizar (só se ativado) e Configurações (só
  nas rotas com barra inferior). No catálogo: voltar e, abaixo, o nome do sistema em destaque.
- **Barra inferior:** as 5 abas de hoje, na ordem de `MainNavigationRoutes` (`MainNavigationRoutes.kt:163-174`):
  Início, Favoritos, Sistemas, Jogos salvos, Buscar. A selecionada fica em pílula âmbar.
- **Início:** destaque, faixa "Salvando agora", fileiras Jogos salvos, Recentes e Descubra.
- **Catálogo:** grade de 3 colunas ou lista, conforme a preferência que já existe nas Configurações
  (`SettingsScreen.kt:232`, lida em `GamesScreen.kt:54`).
- **Jogos salvos:** grade de 3 colunas, chip do sistema sobre a capa, contagem de versões, mídia inacessível.

### Deitado: o desenho da TV no celular

Pedido do dono (13/10/2026): com o celular deitado, a Início usa o desenho da prancha "A · TV — Início"
(destaque, fileiras e carrossel de sistemas). Em pé, continua o desenho acima. O app troca sozinho conforme a
orientação.

- **Critério:** largura maior que a altura da janela, o mesmo teste que a tela do jogo já usa
  (`MobileGameScreen.kt:71-72`). Não depende do tipo de aparelho: a TV box sem Android TV, que mostra a UI
  mobile sempre deitada e navega por D-pad, também recebe este desenho.
- **A `MainActivity` já gira hoje:** o manifesto não declara `screenOrientation` nem `configChanges` para ela
  (`AndroidManifest.xml:60-63`), e nenhum código chama `setRequestedOrientation`. A rotação recria a Activity,
  os ViewModels sobrevivem e o desenho é escolhido de novo. Não há mudança de manifesto.
- **Moldura:** a barra inferior vira um **trilho lateral** de 80 dp (`NavigationRail`, que existe no material3
  do projeto: conferido no AAR 1.2.1 do cache do Gradle), com as mesmas 5 abas. O indicador da fila fica no pé
  do trilho (anel com a porcentagem e a contagem) e está sempre visível. Ajuda, Sincronizar e Configurações
  ficam no canto superior direito e somem ao rolar para baixo; voltam ao rolar para cima
  (`TopAppBarDefaults.enterAlwaysScrollBehavior`, também presente no AAR). O trilho respeita o recorte da
  câmera (insets de `displayCutout`).
- **Início deitada:** fundo ambiente na tela toda; à esquerda, "Continue jogando", título, chip do sistema e o
  botão Jogar/Salvar/"Salvando 42%". Depois vêm a fileira Recentes e o **carrossel de Sistemas**. Rolando,
  aparecem Jogos salvos e Descubra. Nas fileiras só aparece a capa, sem título embaixo: o título do item em
  destaque (ou em foco, no D-pad) fica no topo, como na TV.
- **D-pad:** o destaque acompanha o foco, e o fundo ambiente troca depois de ~300 ms parado no item (a mesma
  regra da entrega 3.3).
- **Tamanhos:** celular (altura < 480 dp): capa 72×96, cartão de sistema 101×70. TV box (altura ≥ 480 dp):
  os da prancha "A · TV" (capa 150×200, sistema 208×144).
- **Outras telas deitadas:** o desenho de pé, com o trilho no lugar da barra inferior e mais colunas (o
  catálogo do SNES deitado tem 7).
- **O carrossel de Sistemas na Início é um acréscimo**, como a fileira Jogos salvos. O toque num sistema
  navega para `systems/{id}`, a mesma rota da aba Sistemas, e o voltar retorna à Início. A lista tem de ser a
  mesma da aba Sistemas, com o mesmo filtro por RAM: segundo o `CLAUDE.md`, ela vem do `MetaSystemsViewModel`,
  que aplica o `HeavySystemFilter` (não aberto; abrir na entrega 2.3).

**Estado de salvamento:** não salvo = sem selo; na fila = selo com relógio;
salvando = capa escurecida + anel com a porcentagem; pausado = selo de pausa; erro = selo vermelho com "!";
salvo = selo com "✓" na cor de destaque. Variantes = selo de cópia no canto inferior direito.
Botão do destaque: "Jogar" (cheio), "Salvar" (claro) e "Salvando 42%" (barra de progresso dentro do botão).

**Paleta de sistemas proposta** (`SystemPalette`, tons com contraste ≥ 4,5:1 para texto branco): SNES
`#6A4FD0`, PlayStation `#4F5665`, N64 `#17753F`, Mega Drive `#2452A8`, GBA `#3D48B8`, ZX Spectrum `#0B7381`,
NES `#B3261E`, Dreamcast `#B04E0C`, Nintendo DS `#5C616B`, GBC `#7E35A3`. Os outros 69 sistemas ficam para a
Fase 1.

**Técnica a validar na Fase 1:** na capa gerada, a arte `game_system_*` (fundo preto) é sobreposta à cor do
sistema com mistura *screen* (no mockup, `mix-blend-mode: screen`; no Compose, `BlendMode.Screen`). No
mockup o desfoque usa `filter: blur`; no app continua valendo a capa em ~32 px ampliada.

**Adotado pelo padrão da prancha A (o dono pode mudar a qualquer momento):**
- Fileira "Jogos salvos" na Início: sim. Em pé, logo depois do destaque; deitado, depois do carrossel de
  Sistemas.
- Faixa "Salvando agora": sim, em pé. Deitado, o indicador no pé do trilho cumpre esse papel.
- Capa 3:4.
- Rótulos e ordem das abas: os de hoje. A primeira rodada de mockups mostrava 6 abas ("Salvos", "Ajustes"),
  feitas sem abrir o código. O app tem 5, e Configurações fica na barra superior. As pranchas da A foram
  corrigidas; as da B e da C, não.

## Andamento

- **2026-10-13:** estudo e plano gravados. Nenhum código alterado.
- **2026-10-13 (Fase 0):** mockups publicados como artifact do claude.ai (link morto; ver a segunda rodada) com
  dados reais (19 capas e 14 artes `game_system_*`, 12 títulos do SNES e 12 do ZX Spectrum tirados do
  `catalog_manifest.txt`, contagens reais por sistema). Nenhum código do app alterado.
  - **Não verificado:** as pranchas não foram abertas nem renderizadas por quem as escreveu. Antes de decidir,
    o dono confere se todas aparecem (em especial as seis que importam outra prancha: "sem a fileira" e
    "ZX Spectrum" de cada direção).
  - **A TV dos mockups mostra só "Recente" e "Sistemas"**, sem selo de salvo e sem fileira de salvos (3.5 e
    3.6 seguem dependendo do aval do dono). As fileiras reais da Início da TV não foram abertas no código.
  - **Achados fora de escopo (registrar, não corrigir aqui):** (1) três URLs de capa do libretro-thumbnails
    devolveram arquivo de 14–62 bytes em vez de imagem (Ocarina of Time Europe Rev 1, Pokémon Crystal
    Australia, Resident Evil 2 Europe Disc 1) — provável link simbólico no repo, capa quebrada no app;
    (2) capas trocadas no ZX Spectrum: "Mayhem" usa a capa de Donkey Kong Country (`co70qn`) e
    "Earthbound (1986)" a do EarthBound do SNES; (3) dois representantes para o mesmo jogo no SNES ("Legend
    of Zelda - A Link to the Past" e "The Legend of Zelda - A Link to the Past", este sem capa).
- **2026-10-13 (Fase 0, segunda rodada):** o primeiro artifact sumiu da conta e os mockups foram republicados
  no link atual. O dono escolheu a **direção A** e pediu o desenho da TV para o celular deitado. Entraram 3
  pranchas deitadas (Início, Início rolada, catálogo do SNES). Ao abrir `MainNavigationRoutes.kt` e
  `MainTopBar.kt`, apareceu o erro das 6 abas; as pranchas da A foram corrigidas para as 5 abas reais, com
  Ajuda e Configurações na barra superior, e perderam o botão grade/lista, que não existe ali (a preferência
  fica nas Configurações). A seção "Especificação visual" virou a da direção A, com a parte "Deitado".
  Nenhum código do app alterado.
  - **Não verificado:** as pranchas novas e as corrigidas não foram abertas nem renderizadas por quem as
    escreveu.
  - O segundo artifact também sumiu da conta, sem ação desta sessão. O dono decidiu não republicar e seguir
    só com a seção "Especificação visual" deste doc.
  **Próximo passo:** Fase 1 (fundação). Abrir a sessão com: "Leia
  `docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md` e execute a Fase 1 (fundação) com a
  Especificação visual da direção A."
- **2026-10-13 (Fase 1, registro analítico antes do código):**
  - **Abertos:** `M/shared/compose/ui/LemuroidTheme.kt::AppTheme` (só `colorScheme`, `darkTheme = true`
    sempre); `Color.kt` de `src/debug` e `src/release` (idênticos; `md_theme_*` só usados pelo tema — `grep`
    fora de `Color.kt`/`LemuroidTheme.kt` não acha nada); `LemuroidGameImage.kt` e `LemuroidSmallGameImage.kt`
    (fallback = `CoverUtils.getFallbackDrawable` → `TextDrawable` com iniciais e cor aleatória; o erro/ausência
    de URL cai nele via `fallback`/`error` do `AsyncImage`); `CoverUtils.kt::isLowRamDevice` (só
    `ActivityManager` + `HeavySystemFilter.deviceTier`; não toca no Coil); `HeavySystemFilter.deviceTier`;
    `effects/BounceClickModifier.kt` (em TV desliga a animação via `isTvDevice`); `LemuroidTexts.kt`;
    `SystemLogoResolver.kt` e `MetaSystemID.kt` (79 entradas; `color()` = matiz por ordinal);
    `GameSystem.findByIdOrNull(dbname)` + `metaSystemID()` levam de `Game.systemId` ao `MetaSystemID`.
    `AppTheme` é usado por `MainActivity`, `BaseGameActivity`, `GameMenuActivity`,
    `GamePadBindingActivity` e `GamePadShortcutBindingActivity`.
  - **Uso dos tokens hoje** (`grep -rhoE "colorScheme\.[a-zA-Z]+"` em `lemuroid-app/src/main`):
    `onSurfaceVariant` 17, `surfaceVariant` 7, `primary` 6, `error` 5 (4 deles como **cor de texto** em telas
    de Ajustes), demais ≤ 2. Tipografia: `bodyMedium` 34, `titleMedium` 19, `bodySmall` 13, `titleSmall` 4,
    `labelSmall` 4. `shapes.large` 1.
  - **Decisões:**
    1. `colorScheme.error` **não** recebe `#B3261E`: ele é usado como cor de texto e daria 2,9:1 sobre o fundo.
       O `#B3261E` da especificação vai para `errorContainer` (fundo de selo) e para `LemuroidColors.errorBadge`;
       `error` fica no vermelho claro padrão do M3 escuro (`#F2B8B5`).
    2. Tipografia: tamanhos dos slots do Material ficam os padrões (que já batem com a especificação:
       `titleLarge` 22, `headlineSmall` 24, `titleMedium` 16, `labelSmall` 11); só entram a família Outfit e o
       peso 700 nos títulos. O "cartão 12" vira estilo nomeado (`LemuroidTypography.cardTitle`), para não
       encolher os 4 usos atuais de `titleSmall`.
    3. Fonte: **estáticas** 400/500/700 (`Outfitio/Outfit-Fonts`, OFL), não a variável do Google Fonts —
       `Font(resId, weight)` com arquivo variável não aplica eixo de peso abaixo do Android 8.
    4. Paleta de sistemas: as 10 da especificação + 69 propostas; script conferiu 79/79 nomes do enum e
       contraste mínimo **5,08:1** com branco.
    5. `VisualQuality` é calculado uma vez por processo (cache estático) e entregue por `CompositionLocal` no
       `AppTheme`; no `:game` ele só lê `ActivityManager`, sem Coil.
  - **Tasks:** `Color.kt` → `src/main` (apaga debug/release); `Type.kt` + `res/font/outfit_*.ttf`; `Shape.kt`;
    `Dimens.kt`; `SystemPalette.kt`; `VisualQuality.kt`; `AppTheme` com tipografia, formas e locals;
    componentes `AmbientBackdrop`, `GeneratedCover` (dentro de `LemuroidGameImage`), `SaveStateBadge`,
    `focusHighlight`, `SectionHeader`. Prova: build x86_64, ktlint, lint, abrir jogo + menu do jogo no AVD.
- **2026-10-13 (Fase 1, entrega):** fundação pronta, **sem commit**.
  - **Feito:** `Color.kt` em `src/main` (cópias de debug/release apagadas) com `LemuroidColors`; `Type.kt` +
    `res/font/outfit_{regular,medium,bold}.ttf` (estáticos de `Outfitio/Outfit-Fonts`, OFL); `Shape.kt`
    (`medium` = 10, `PillShape`); `Dimens.kt` (em pé / deitado compacto < 480 dp / deitado grande; `rowCover` e
    `systemCard` de pé são provisórios até a 2.3); `SystemPalette.kt` (79 cores, `when` exaustivo — a "reserva"
    `MetaSystemID.color()` ficou desnecessária: sistema novo sem cor não compila); `VisualQuality.kt`; `AppTheme`
    com tipografia, formas e `LocalVisualQuality`/`LocalDimens`/`LocalLemuroidTypography`. Componentes:
    `GeneratedCover` (com cache LRU de 12 artes, decodificadas fora da main com `inScaled = false`),
    `AmbientBackdrop`, `SaveStateBadge.kt` (`CoverSaveState.of(entry, isDownloaded)`, `SaveStateOverlay`,
    `VariantsBadge` — usa só strings que já existiam), `effects/FocusHighlight.kt`, `SectionHeader`.
    `LemuroidGameImage` mostra a capa gerada quando não há URL ou quando o Coil dá erro (`onError`); enquanto
    carrega, o fundo é o tom escuro do sistema. `CLAUDE.md` ganhou a seção "Design system".
  - **O que já muda na tela, sem mudar layout:** fonte Outfit, cores da direção A (pílula âmbar na barra
    inferior, superfícies `#1A1C22`/`#14161B`) e a capa gerada no lugar das iniciais (só em
    `LemuroidGameImage`; `LemuroidSmallGameImage` — lista e diálogos — continua com iniciais até a 2.2/2.6).
  - **Verificação:**
    - `assembleFreeBundleDebug -PdevAbi=x86_64` OK; `lintFreeBundleDebug` OK; `ktlintMainSourceSetCheck` sem
      apontamento nos arquivos desta entrega. **A task falha por violações pré-existentes** em arquivos não
      tocados (`RomOnDemandManager` 46, `MainActivity` 28, `LemuroidInstalledGameCard` 11,
      `InstalledGamesViewModel` 9, `GameLauncher` 8, `StorageAvailabilityMonitor` 7…), fora do baseline.
    - APK: conteúdo **+162 KB** comprimido (fontes 117 KB, dex 45 KB; `unzip -lv` entrada a entrada, nenhuma
      outra mudou).
    - **Linha de base e depois, no SM-A127M** (Android 13; debug arm64; roteiro: Sistemas ▸ ZX Spectrum,
      30 `input swipe` de 150 ms, `dumpsys gfxinfo`/`meminfo`). A base é o debug 1.17.25 já instalado: `git log`
      não mostra mudança em `feature/games`, `feature/home`, `shared/` nem `shared/covers` desde aquele build.

      | ZX Spectrum | quadros lentos (3 rodadas) | p90 | PSS |
      |---|---|---|---|
      | Base | 47,4% / 74,4% / 68,2% | 113–150 ms | 264–276 MB |
      | Fase 1 | 59,7% / 61,7% / 64,3% | 113–150 ms | 272–303 MB |

      Dentro do ruído; a variação entre rodadas da mesma build é maior que a diferença. **O celular é ruim para
      medir quadros** (só ~300 quadros em 30 gestos, p90 > 100 ms já na base): a métrica de quadros da
      "Verificação" precisa de outro roteiro ou aparelho. A PSS sobe de rodada em rodada nas duas builds.
    - **AVD `lemu_api25_2gb` (tier `WEAK` → `LITE`), antes de o dono pedir para usar o celular:** SNES base 2,90%
      (PSS 221 MB); ZX base **1,55%** → Fase 1 **38,1%** (p90 8 → 32 ms; PSS 219 → 196 MB). Só o ZX, onde 96%
      dos jogos usam a capa gerada, foi medido depois. **Regressão aberta, não investigada:** suspeitos são o
      `Text` de 3 linhas por capa e o `drawImage` com `BlendMode.Screen` sobre bitmap RGB_565 no renderizador do
      emulador. Resolver na 2.2 (que refaz o cartão do catálogo), medindo num aparelho `LITE` real ou no AVD.
    - **`:game`:** jogo (Super Mario Bros. 3, NES) aberto no celular com a Fase 1; a `GameActivity` monta o pad
      dentro do `AppTheme` e não houve crash (`logcat -b crash` vazio). **O menu do jogo não foi aberto:** o
      botão do pad não responde a `input tap`/`swipe` nem a `KEYCODE_ESCAPE`, e o shell do SM-A127M não tem
      permissão para `sendevent` em `/dev/input/event3`. Falta abrir à mão uma vez.
    - Capturas antes/depois (Início e ZX Spectrum, AVD e celular) ficaram só no scratchpad da sessão.
  - **Próximo passo:** commit desta entrega (com o aval do dono); abrir o menu do jogo à mão; depois a 2.1
    (Sistemas). Abrir a sessão com: "Leia `docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md` e
    execute a entrega 2.1 (Sistemas)."
- **2026-10-13 (entrega 2.1, registro analítico antes do código):**
  - **Abertos:**
    - `M/feature/systems/MetaSystemScreen.kt` — overload público (`:21-39`): `collectAsState(null)`, anel de
      carregamento enquanto `null`, `navController.navigate("systems/${it.metaSystem.name}")`. Overload privado
      (`:42-68`): `LemuroidEmptyView` se vazio; `LazyVerticalGrid(Adaptive(144.dp))`, margem e espaço 16, chave
      `metaSystems[it].metaSystem`.
    - `M/feature/systems/MetaSystemsViewModel.kt::availableMetaSystems` — `StateFlow<List<MetaSystemInfo>?>`
      (`Eagerly`, `null` = carregando); aplica `HeavySystemFilter` e, em TV, esconde PSP/3DS; ordena pelo nome.
      **Não muda.**
    - `M/shared/compose/ui/LemuroidSystemCard.kt` — `ElevatedCard(onClick)` com `clip(20.dp)`, `onFocusChanged`
      → `isFocused` → `LemuroidSystemImage(system, hovered)`; texto por `LemuroidTexts` (`titleSmall` +
      `labelSmall`). O subtítulo é `remember(titleResId)`: **não** acompanha a contagem se ela mudar com a tela
      aberta (a chave do item é o sistema, então a composição é reaproveitada).
    - `M/shared/compose/ui/LemuroidSystemImage.kt` — `Image(painterResource(SystemLogoResolver.resolve(...)))`
      em caixa 1,6:1 sobre um degradê azul fixo. Único chamador: `LemuroidSystemCard`. `painterResource`
      decodifica na main e na densidade da tela (PNG em `drawable/` sem qualificador).
    - `APP/shared/systems/MetaSystemInfo.kt` — `metaSystem`, `count`, `getName(context)`.
    - `LIB/library/SystemLogoResolver.kt::resolve(metaSystem, hovered)` — devolve `imageResId` ou o `_hover`.
    - Fundação que a entrega usa: `Dimens.kt` (`screenMargin`, `cardSpacing`, `cardCornerRadius`,
      `systemCardWidth`), `SystemPalette.color`/`darkTone`, `effects/FocusHighlight.kt::focusHighlight`
      (tem de vir **antes** do `clickable` e do `clip`), `GeneratedCover.kt::rememberSystemArt` +
      `SystemArtCache`, `Type.kt` (`support` 11), `Color.kt` (`Text`, `TextSupportOnBackdrop`).
    - `MainActivity.kt:515-528` — a rota `SYSTEMS` só passa `modifier`, `navController` e o ViewModel. Não muda.
  - **Comandos que provaram algo:**
    - `file retrograde-app-shared/src/main/res/drawable/game_system_*.png | grep -v -E "277 x 192|333 x 230"`
      → **20 arquivos fora do padrão** (10 sistemas): 9 marcas de arcade em 479×221 (dataeast, galaxian,
      kaneko, pgm, psikyo, seta, taito, technos, teoplan — logotipo numa placa branca, e a versão de foco
      parece idêntica) e `sc3000` em 720×332 (foto com fundo transparente). A linha "79 normais (277×192) e
      79 de foco (333×230)" da tabela acima estava errada: são 69 + 69 nesse formato.
    - Script PIL (pixel a pixel na linha do meio e na diagonal do canto): nas 69 artes padrão há **moldura
      branca embutida** de 3 px (4 px na de foco) com canto arredondado; some por completo com corte de 5 px
      (277) / 6 px (333), ou seja, ~2,2% da largura. O fundo da arte de foco varia por sistema (SNES azul, NES
      foto escura, PSX cinza): não é "a mesma arte, maior".
    - `adb -s emulator-5554 emu avd name` → `pp_api34`: o emulador que está no ar é de outro projeto. Não tocar;
      subir os AVDs `lemu_*` à parte.
  - **Hipóteses descartadas:**
    - *"Desenhar a arte inteira dentro do bloco."* A moldura branca vira um cartão dentro do cartão (é o que se
      vê hoje na capa gerada do PSP na Início). Fica o corte de ~2,2% + preenchimento da área.
    - *"`rememberSystemArt` serve para a troca no foco como está."* Não: `produceState(inicial, resId)` não
      reinicia o valor quando a chave muda, e o `if (value == null)` pula a carga — a arte nunca trocaria. Tem
      de ser estado indexado por `resId`.
    - *"O cache de 12 artes basta."* A tela mostra 10–15 blocos (até ~30 numa TV box) mais a arte de foco;
      passa a ser limitado por bytes.
    - *"Cortar para preencher em toda arte."* As 10 fora do padrão têm proporção 2,17: cortar os lados corta o
      logotipo. Arte com proporção longe da caixa é encaixada inteira, centrada.
    - *"Texto sobre a arte, com degradê."* A arte com mistura *screen* tem áreas claras; o texto fica num rodapé
      próprio, no tom escuro do sistema, e a arte esmaece até ele.
  - **Desenho:** bloco de canto 10 (8 deitado). Em cima, área 277:192 na cor do sistema com a arte em mistura
    *screen*, sem a moldura, esmaecendo para o rodapé; embaixo, rodapé no tom escuro (`SystemPalette.darkTone`)
    com o nome (`titleSmall` em negrito, 1 linha) e a contagem (`support`). Foco: `focusHighlight` + a arte de
    foco, como hoje. Grade: `Adaptive(144 dp)` (208 dp na TV box), margem `screenMargin`, espaço `cardSpacing`.
  - **Tasks:**
    1. `GeneratedCover.kt` — `rememberSystemArt` indexado por `resId`; `SystemArtCache` limitado por bytes.
       Prova: a capa gerada continua aparecendo no catálogo do ZX Spectrum.
    2. `LemuroidSystemImage.kt` — recebe `MetaSystemID`; cor do sistema + arte (*screen*, sem moldura) +
       esmaecimento. Prova: captura da tela Sistemas; SNES, Taito e SC-3000 conferidos.
    3. `LemuroidSystemCard.kt` — bloco novo, `focusHighlight`, `clickable`; contagem indexada por `count`.
       Prova: toque abre `systems/{id}`; D-pad mostra o contorno e troca a arte.
    4. `MetaSystemScreen.kt` — só margens, espaço e largura mínima da grade pelo `LocalDimens`. Prova: em pé e
       deitado; `gfxinfo` da rolagem antes/depois.
- **2026-10-13 (entrega 2.1, entrega):** tela Sistemas no visual novo, **sem commit**. Doc da entrega:
  [`docs/funcionalidades/2026-10-13-redesenho-sistemas.md`](../funcionalidades/2026-10-13-redesenho-sistemas.md)
  (capturas antes/depois em `docs/funcionalidades/img/`).
  - **Feito:** as 4 tasks do registro analítico, sem desvio do desenho. `LemuroidSystemImage` passou a receber
    `MetaSystemID` (único chamador: `LemuroidSystemCard`) e expõe `SYSTEM_ART_ASPECT` e `fadeTo`, para o
    carrossel da 2.3 usar a mesma peça.
  - **Verificação:**
    - `assembleFreeBundleDebug -PdevAbi=x86_64` OK. `ktlintMainSourceSetCheck`: nenhum apontamento nos 4
      arquivos tocados (`grep` no relatório); a task segue falhando pelas violações antigas de outros arquivos.
    - `lintFreeBundleDebug` OK, mas só com `--no-build-cache`: a primeira rodada caiu em
      `:bundled-cores:lintAnalyzeDebugAndroidTest` com "Failed to load cache entry … invalid stored block
      lengths" (entrada corrompida no cache local do Gradle, fora do código).
    - `lemu_api25_2gb` (`LITE`): toque abre o catálogo do sistema e o voltar retorna; D-pad mostra o contorno
      âmbar, troca a arte pela de foco e o OK navega; rolagem até o ZX Spectrum; deitado dá 4 colunas e mantém
      a posição; `logcat -b crash` vazio. As seis marcas de arcade visíveis (Galaxian, PGM, Psikyo, Taito,
      Technos, Toaplan) aparecem como placa centrada.
    - `lemu_api21_1gb` (Android 5.0, `ULTRA_WEAK`): Sistemas abre, a mistura *screen* aparece certa, foco e OK
      funcionam, sem `FATAL` no logcat. O `gfxinfo` dessa API não traz o resumo de quadros.
    - SM-A127M (`FULL`, pt-BR): Sistemas abre e rola, sem crash.
    - **Rolagem no `lemu_api25_2gb`** (3 rodadas de 16 gestos): build 1.17.24 que estava no snapshot, **31,3% /
      53,2% / 26,9%** de quadros lentos (p90 19–32 ms, PSS 84–87 MB); 2.1, processo novo, **0,9% / 4,7% / 3,4%**
      (p90 9–12 ms, PSS 96–99 MB). A base é anterior à Fase 1 e o processo dela vinha do snapshot, então a
      diferença de PSS não se atribui só a esta entrega.
    - **Celular:** 60,3% / 72,9% / 90,0% de quadros lentos (p90 109–150 ms). **Sem linha de base** da tela
      Sistemas no celular (a captura do "antes" foi feita, a medição não); é a mesma faixa do ZX na Fase 1.
  - **Pendências novas:**
    - **Contorno de foco cortado embaixo:** ao descer com o D-pad a grade rola só até a borda do bloco, e a
      parte do contorno abaixo dele (6 dp) sai da área visível. É do `focusHighlight`, não desta tela: resolver
      na 2.2 antes de o catálogo adotá-lo.
    - Não testado: TalkBack, SC-3000 (não aparece nos aparelhos de teste) e o tamanho "deitado grande" (TV box).
  - **Próximo passo:** commit da Fase 1 + 2.1 (com o aval do dono); depois a 2.2. Abrir a sessão com: "Leia
    `docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md` e execute a entrega 2.2 (Catálogo do
    sistema)."
- **2026-10-14 (entrega 2.2, registro analítico antes do código):** Fase 1 e 2.1 continuam sem commit.
  - **Abertos:**
    - `M/feature/games/GamesScreen.kt::GamesScreen` — sem mudança desde o registro da Fase 0. Cabeçalho de
      ordenação é o item `"sort_header"`; o vazio (`LemuroidEmptyView`) só aparece com `refresh` concluído e
      `itemCount == 0`.
    - `M/feature/games/GamesViewModel.kt` — `games` por `buildFlowPaging(20)`; **não muda**.
    - `retrograde-util/…/paging/PagingUtils.kt::buildFlowPaging` — `PagingConfig(pageSize)` com placeholders
      ligados (padrão): `itemCount` é o total do sistema assim que a primeira página chega. Dá a contagem do
      cabeçalho sem query nova.
    - `LemuroidGameGridItem.kt` — `Column` com `clip(8)` + `combinedClickable`; capa 3:4; selo de baixado
      (ícone `Download`) no canto superior direito e de variantes no superior esquerdo; título `labelMedium`
      centrado, 2 linhas. Único chamador: `GamesScreen`.
    - `LemuroidGameListRow.kt` — `Surface` + `combinedClickable`; `LemuroidSmallGameImage` 40×40 (iniciais);
      `LemuroidGameTexts`; ícones de variantes e de baixado; `FavoriteToggle`. Chamadores: `GamesScreen` **e**
      `SearchScreen.kt:209` — o visual novo da linha chega também à Busca (antes da 2.5).
    - `LemuroidGameImage.kt` (Fase 1) — decode 400/256 px; capa gerada sem URL ou no `onError`.
    - `GeneratedCover.kt` (Fase 1/2.1) — desenha a arte **inteira**, sem o corte da moldura que a 2.1 pôs só em
      `LemuroidSystemImage.kt::drawSystemArt` (privada).
    - `SaveStateBadge.kt` (Fase 1) — `CoverSaveState.of(entry, isDownloaded)`, `SaveStateOverlay`,
      `VariantsBadge` (canto inferior direito). Ainda sem chamador.
    - `effects/FocusHighlight.kt` — contorno desenhado fora dos limites do nó; nada pede ao contêiner que role
      até ele (causa do contorno cortado da 2.1). `effects/BounceClickModifier.kt` usa `pointerInput`, **não é
      focável**: o cartão do catálogo continua no `combinedClickable` (D-pad).
    - `M/feature/main/SaveQueueViewModel.kt` — `entries` (lista); `SaveQueueManager.kt:101-109` e `:189-197`
      montam a entrada com `systemId`/`fileName` do `Game`, então `"$systemId/$fileName"` == `Game.downloadKey`
      (`Game.kt:71`).
    - `MainActivity.kt` — `downloadedGameKeys`/`titlesWithVariants` coletados com `.value` no `setContent`;
      rota `SYSTEM_GAMES` (`:529-549`) já tem `metaSystemId`. A barra superior do catálogo diz "Jogos"
      (`MainNavigationRoutes.kt:65-70`, `title_games`) e é da 2.6.
    - `GameUtils.getGameSubtitle` — nome curto do sistema (+ desenvolvedor, nulo no catálogo).
  - **Comandos que provaram algo** (`lemu_api25_2gb`, `LITE`, build da árvore atual = Fase 1 + 2.1, já que
    nenhum fonte é mais novo que o APK de 13/10 23:23; roteiro: Sistemas ▸ ZX Spectrum, 3 rodadas de 16
    `input swipe 540 1450 540 450 150`, `dumpsys gfxinfo`/`meminfo`; scripts `tap.sh`/`measure.sh` no
    scratchpad da sessão):
    - Base 2.2: **0,00% / 0,47% / 0,00%** de quadros lentos, p90 6–7 ms, PSS 127–129 MB. A rolagem chega à
      região de capas geradas (captura). **A regressão de 38,1% da Fase 1 não reproduz** no build atual.
    - Capturas: na capa gerada o título aparece duas vezes (na capa e embaixo) e a arte do ZX mostra a
      moldura branca.
    - `adb -s emulator-5554 emu avd name` → `pp_api34` (outro projeto, não tocar); o AVD desta entrega é o
      `emulator-5556`.
  - **Hipóteses descartadas:**
    - *"A capa gerada pesa em `LITE` (`Text` de 3 linhas ou `BlendMode.Screen`)."* Não reproduz (acima). Não há
      o que otimizar às cegas; a medição depois da entrega usa o mesmo roteiro.
    - *"`bounceClick` como estado de pressionado do cartão."* Não é focável: quebraria o D-pad.
    - *"Ler `saveQueue.value` no `setContent` e passar o mapa como valor."* O `progress` muda várias vezes por
      segundo e o `Game` (outro módulo, sem compilador Compose) não é estável: recomporia todos os itens
      visíveis a cada tique. Vai o `State` até o item, lido por `derivedStateOf` por chave.
    - *"`GridCells.Adaptive` puro."* Em celular estreito (largura < ~340 dp) cai para 2 colunas; a
      especificação pede 3 em pé. Grade própria com mínimo de 3.
    - *"Fundo ambiente na tela inteira do catálogo."* Fica só no cabeçalho, que rola junto: a grade rola sobre
      fundo liso.
  - **Desenho:** cabeçalho (item `"sort_header"`, largura total) com `AmbientBackdrop` da arte do sistema, bloco
    `LemuroidSystemImage`, nome do sistema, contagem (`system_grid_details`) e os dois chips de ordenação (na
    mesma linha quando deitado). Cartão: capa 3:4 com canto `cardCornerRadius`, `SaveStateOverlay` (canto
    superior direito), `VariantsBadge` (inferior direito), título `cardTitle` 2 linhas à esquerda,
    `focusHighlight`. Capa gerada **sem título** no cartão da grade (o título já vem embaixo) e com a arte
    centrada e sem moldura. Grade: colunas = máx(3, largura ÷ `gridCoverMinWidth`) — 96 dp em pé, 84 deitado
    compacto (7 colunas num celular de ~730 dp), 120 na TV box. Linha (lista): capa 3:4 pequena (gerada sem
    título), título, subtítulo, indicador de estado em linha, favorito; foco = fundo em pílula âmbar.
  - **Tasks:**
    1. `LemuroidSystemImage.kt` — `drawSystemArt` aceita uma área; `GeneratedCover.kt` usa (sem moldura),
       ganha `showTitle` e centra a arte sem título. Prova: captura do ZX sem moldura.
    2. `LemuroidGameImage.kt` — parâmetros opcionais `generatedCoverTitle` e `compact` (decode 160 px).
    3. `SaveStateBadge.kt` — `rememberCoverSaveState(key, isDownloaded, entries)` com `derivedStateOf` e
       `SaveStateIndicator` (versão em linha). `SaveQueueViewModel.entriesByKey` (leitura aditiva).
    4. `effects/FocusHighlight.kt` — `BringIntoViewRequester` com o retângulo do contorno (+ escala). Prova:
       D-pad até a última linha visível do catálogo e da tela Sistemas, contorno inteiro.
    5. `Dimens.kt` — `gridCoverMinWidth`.
    6. `LemuroidGameGridItem.kt` e `LemuroidGameListRow.kt` — cartão e linha novos (assinaturas antigas mantidas,
       parâmetro novo opcional).
    7. `GamesScreen.kt` — cabeçalho, grade própria, `metaSystem` e `saveQueueEntries` opcionais; chaves, volta
       ao topo e pref intocadas. `MainActivity.kt` passa os dois. Prova: roteiro funcional + `gfxinfo` com o
       mesmo roteiro + deitado.
- **2026-10-14 (entrega 2.2, código):** as 7 tasks acima foram escritas (01:37–01:42) e o
  `assembleFreeBundleDebug -PdevAbi=x86_64` passou (APK 01:45). A sessão que escreveu caiu no `adb install`
  (processo travado, não removido). A sessão seguinte revisou o código contra o desenho: chaves `"id_$id"`/
  `"idx_$it"`, volta ao topo no `ON_RESUME`, pref `catalog_layout_grid` e vazio intocados;
  `MetaSystemID.valueOf(metaSystemId)` já era avaliado na mesma rota pela factory do `GamesViewModel` (sem
  modo de falha novo); `SearchScreen.kt:209` compila com os parâmetros novos opcionais. Falta a verificação.
- **2026-10-14 (entrega 2.2, entrega):** catálogo no visual novo, **sem commit**. Doc da entrega:
  [`docs/funcionalidades/2026-10-14-redesenho-catalogo.md`](../funcionalidades/2026-10-14-redesenho-catalogo.md)
  (capturas em `docs/funcionalidades/img/2026-10-14-redesenho-catalogo*.jpg`).
  - **Feito:** as 7 tasks do registro analítico, sem desvio do desenho.
  - **Verificação** (`lemu_api25_2gb`, `LITE`, APK de 14/10 01:45, mais novo que todo fonte):
    - Roteiro funcional: variantes → modal; não salvo → "Save ROM?" → fila → "Play now?"; no cartão, na fila
      (relógio), salvando (anel 43% → 84%), pausado e salvo (pelo modal), e o selo some ao cancelar; toque
      longo → menu; A-Z/Popularidade; lista; deitado (chips na linha do nome, 6 colunas em ~683 dp, estado
      mantido na rotação). `logcat -b crash` vazio.
    - **D-pad:** contorno inteiro na última linha visível: a pendência da 2.1 está resolvida no `focusHighlight`.
    - **Rolagem ZX Spectrum** (`measure.sh`, mesmo roteiro da base 2.2): **3,06% / 0% / 0%** de quadros lentos,
      p90 8–9 ms, PSS 128–129 MB, contra base 0% / 0,47% / 0%, p90 6–7 ms, PSS 127–129 MB. Dentro do critério.
    - **Não rodados:** `ktlintMainSourceSetCheck` (o dono interrompeu a execução) e `lintFreeBundleDebug`.
      Não testados: `lemu_api21_1gb`, SM-A127M, TV box, TalkBack, selo de erro.
  - **Pendências novas:**
    - **Decisão do dono:** o cabeçalho conta os representantes (Amstrad CPC 2.696) e o bloco da tela Sistemas
      conta também as variantes escondidas (4.231). Manter, trocar pela contagem do bloco ou tirar.
    - No foco, o `combinedClickable` ainda pinta a indicação padrão do Material além do contorno.
    - Fora de escopo: `sort_popularity` padrão (`values/strings.xml:19`) está em português.
  - **Próximo passo:** ktlint e lint desta entrega; commit da Fase 1 + 2.1 + 2.2 (com o aval do dono); depois a
    2.3 (Home). Abrir a sessão com: "Leia `docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md` e
    execute a entrega 2.3 (Home)."
- **2026-10-14 (ajustes pós-2.2, registro analítico antes do código).** Decisões do dono: (1) **toda contagem
  de jogos mostra o total, com as variantes**, nunca só os agrupados; (2) traduzir "Popularidade" para inglês;
  (3) conferir se todos os textos aparecem traduzidos em espanhol; (4) commit liberado.
  - **Abertos:**
    - `GameDao.selectSystemsWithCount()` (`GROUP BY systemId` sobre `games` inteiro, com variantes) e
      `SystemCount(systemId, count)`. Chamadores: `MetaSystemsViewModel` (Sistemas mobile), `TVHomeViewModel`
      (`SystemPresenter`, Sistemas TV) e `HomeViewModel.dsGamesCount`. **Os três já são total.**
    - `GamesScreen` (2.2) é a **única** contagem agrupada: `games.itemCount` do paging de `selectGrouped*`.
    - `GamesViewModel` — `metaSystemId: MutableStateFlow`, `games` por `selectGrouped*`. Recebe a contagem.
    - Outros `%d` de contagem na UI (`installed_versions_count`, `transfer_*`, `settings_*_reset_catalog_count`)
      não contam jogos de um sistema; ficam como estão.
    - `values/strings.xml:19` `sort_popularity` = "Popularidade" (o padrão do app é inglês).
  - **Comandos** (`strings_audit.py` no scratchpad; compara chaves traduzíveis de `values/` com o locale):
    - `lemuroid-app` `values-es-rES`: 170 de 404 presentes, **234 faltando** (praticamente tudo o que entrou
      depois da tradução original: fila de salvamento, variantes, Jogos salvos, transferência, romset,
      atualização, idioma, travamento do core…).
    - `retrograde-app-shared` `values-es-rES`: 116 de 311, 195 faltando, dos quais ~160 são nomes/siglas de
      sistema (iguais em espanhol, o fallback serve) e ~35 são opções de core e acesso a arquivos.
    - `values-pt-rBR` do app: faltam `sort_popularity`, `sort_alphabetical`, `variants_badge`,
      `variants_subtitle`, `save_confirm_title` e 4 `notification_rom_queue_*` — com "Popularity" no padrão, o
      pt-BR passaria a mostrar inglês.
  - **Hipótese descartada:** *"Query nova `COUNT(*) WHERE systemId IN (...)`."* Desnecessário: a de contagem
    por sistema já existe e já alimenta a tela Sistemas; somar no ViewModel garante o mesmo número dos dois
    lados.
  - **Tasks:** (a) `GamesViewModel.totalGames` = soma de `selectSystemsWithCount` nos `systemIDs` do
    meta-sistema; `GamesScreen` usa no cabeçalho. Prova: cabeçalho do Amstrad CPC = 4.231, igual ao bloco.
    (b) `sort_popularity` = "Popularity" no padrão; pt-BR recebe as 9 que faltam. (c) `values-es-rES` dos dois
    módulos recebe as strings de texto que faltam (nomes de sistema ficam no fallback). Prova: o script dá 0
    faltando (fora sistemas/números) e o AVD em espanhol mostra o catálogo, a fila e os diálogos traduzidos.
- **2026-10-14 (ajustes pós-2.2, entrega):** as três tasks (a)–(c) feitas, mais as 14 opções de core que
  faltavam no pt-BR do `retrograde-app-shared`. Detalhes em `docs/funcionalidades/2026-10-14-redesenho-catalogo.md`
  ("Ajustes pedidos pelo dono").
  - **Provas:** `assembleFreeBundleDebug -PdevAbi=x86_64` OK; `strings_audit.py`/`dump_missing.py` dão 0
    strings de texto faltando em es-ES e pt-BR (o script de inserção recusa string com placeholder diferente
    do original); no `lemu_api25_2gb`, cabeçalho do Amstrad CPC = **4231** (en "games", es "juegos", pt
    "Jogos"), igual ao bloco de Sistemas; catálogo, diálogos de salvar, fila, menu de contexto, Juegos
    guardados e Ajustes em espanhol. `logcat -b crash` vazio. AVD devolvido em en-US.
  - **Regra nova (dono):** toda contagem de jogos mostra o total com as variantes. Contagem nova tem de sair
    de `selectSystemsWithCount` (ou equivalente sem filtro de `isRepresentative`), nunca de `itemCount` do
    paging agrupado.
  - **Próximo passo:** ktlint e lint; depois a 2.3 (Home). Abrir a sessão com: "Leia
    `docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md` e execute a entrega 2.3 (Home)."
- **2026-10-14 (entrega 2.3, registro analítico antes do código).** Fase 1 + 2.1 + 2.2 + ajustes já commitados
  (`06c7911`); árvore limpa no início.
  - **Abertos:**
    - `M/feature/home/HomeScreen.kt` — overload público (`:55-146`): `ON_RESUME` → `updatePermissions`, diálogo
      de troca para rede móvel, coleta `getViewStates`/`getDownloadRomsState`/`getStreamingRomsState`.
      Overload privado (`:148-282`): diálogo do prompt, carregando (`:188-202`), `LazyVerticalGrid(Adaptive(140))`
      com o bloco de avisos (`:213-255`: varredura, notificação, microfone, DeSmuME, `HomeStreamingCard`) e
      **três** seções — Recentes, **Favoritos** e Descubra (`homeGridSection`, chave `"$sectionKey/${id}"`). A
      especificação lista só Jogos salvos/Recentes/Descubra; Favoritos **fica** (tirar seria mudar função).
      `HomeDownloadCard` não tem chamador (código morto já registrado).
    - `M/feature/home/HomeViewModel.kt` — `UIState` (recentes, favoritos e descubra, até 10 cada, já filtrados por
      `HeavySystemFilter`); `Factory(appContext, retrogradeDb, coresSelection)`, único chamador
      `MainActivity.kt:406`. **Nada muda** além de um fluxo novo de leitura.
    - `M/feature/main/MainActivity.kt::MainScreen` — `Scaffold(topBar = MainTopBar, bottomBar =
      MainNavigationBar)`; cada rota aplica `Modifier.padding(padding)`. `onGameClick` (`:351-365`); toque da aba
      Jogos salvos (`:430-448`: mídia inacessível → toast; > 1 versão salva → seletor só das salvas; senão
      joga/salva). `saveQueueEntries` já é `State` (2.2). `directoriesManager` disponível.
    - `M/feature/main/MainNavigationBar.kt` — `NavigationBar` com as 5 abas de `MainNavigationRoutes`;
      `navigate(route) { popUpTo(start) { saveState = false }; launchSingleTop; restoreState = false }`;
      some (`AnimatedVisibility`) quando `showBottomNavigation == false`.
    - `M/feature/main/MainTopBar.kt` — `LemuroidTopAppBar` (`TopAppBar` sem `scrollBehavior`) e
      `LemuroidTopBarActions` (fila só com `hasSaveQueueActive`, Ajuda, Sincronizar, Configurações). Embaixo, as
      barras de progresso da operação e da fila.
    - `M/feature/main/SaveQueueViewModel.kt` — `entries`, `entriesByKey`, `hasActiveOrQueued`, `activeProgress`.
      `SaveQueueEntry` tem `title`, `coverUrl`, `systemId`, `fileName`, `state`, `progress`.
    - `M/feature/systems/MetaSystemsViewModel.kt` — `availableMetaSystems` (`Eagerly`, `null` = carregando): filtra
      por `HeavySystemFilter.excludedSystems(deviceTier)` e, em TV, PSP/3DS; soma as contagens por meta-sistema
      (total com variantes) e ordena pelo nome. `MetaSystemScreen.kt` navega com `"systems/${metaSystem.name}"`.
    - `LIB/library/db/dao/GameDao.kt::observeInstalledGameGroups` + `InstalledGameGroup(game,
      installedVariantsCount, lastActionAt)`. `InstalledGamesViewModel` monta `romsDirPrefix`/`managedMarker` a
      partir do `DirectoriesManager`, combina com `StorageAvailabilityMonitor.mediaStateChanges` e marca
      `isAvailable` (`isGameAvailable` faz `stat`/`openInputStream`: I/O). O `init` varre a pasta de ROMs — **não**
      instanciar na Home.
    - `M/feature/installed/LemuroidInstalledGameCard.kt` — chip do sistema (`GameSystem.shortTitleResId`), alfa 0,6
      e ícone `CloudOff` se inacessível, pílula `Layers` + contagem se > 1 versão.
    - Fundação: `Dimens.kt` (`rowCoverWidth/Height` e `systemCardWidth/Height` por modo; os de pé marcados
      "provisórios até a 2.3"), `SaveStateBadge.kt` (`rememberCoverSaveState`, `SaveStateOverlay`),
      `AmbientBackdrop.kt` (`model` + `systemColor`; em `LITE` só degradê), `SectionHeader.kt` (título + ação),
      `effects/FocusHighlight.kt`, `LemuroidGameImage.kt`, `LemuroidSystemImage.kt` (277:192, `fadeTo`),
      `SystemPalette.colorForSystemId/darkTone`, `Type.kt` (`heroTitle`, `section`, `cardTitle`, `support`).
    - `MainActivity.kt:1087` `isGamePlaceholder` = `file://` com tamanho 0. Jogo importado (fora da pasta
      gerenciada) **não** está em `downloadedGameKeys` (vem de `downloaded_roms`) e mesmo assim joga.
  - **Comandos que provaram algo:**
    - `javap` no `classes.jar` do `material3-android-1.2.1` do cache do Gradle: `NavigationRail(modifier,
      containerColor, contentColor, header, windowInsets, content)`, `NavigationRailItem` (altura fixa 56 dp),
      `ScaffoldDefaults.getContentWindowInsets`, `TopAppBarDefaults.enterAlwaysScrollBehavior(state, canScroll,
      snap, fling)` e `TopAppBar(…, windowInsets, colors, scrollBehavior)` presentes.
    - Strings reaproveitadas (en/pt-BR/es-ES conferidas): `download_complete_play` (Jogar), `save_action_save`
      (Salvar), `save_queue_saving_progress` (Salvando… %d%%), `save_queue_waiting/paused/error`, `show_all`,
      `recent`, `favorites`, `discover`, `title_installed`, `installed_media_unavailable`.
  - **Hipóteses descartadas:**
    - *"Usar o `NavigationRail` do material3 com os itens dele."* Item de 56 dp fixos: 5 abas + barra de status +
      indicador da fila passam dos ~360 dp do celular deitado. Trilho próprio (itens de 48 dp, abas roláveis, fila
      fixa no pé), com as mesmas cores da barra inferior.
    - *"Trilho dentro do conteúdo do `Scaffold`."* Cada rota já aplica `padding(padding)` com o recuo esquerdo do
      recorte/barra: daria recuo dobrado e exigiria mexer nas 20 rotas. Fica `Row { trilho; Scaffold }`, com o
      `Scaffold` sem os recuos do lado do trilho.
    - *"Rótulo do botão do destaque só por `downloadedGameKeys`."* ROM importada joga mas diria "Salvar". O
      destaque faz o mesmo teste de `isGamePlaceholder` (um `stat`, fora da main) para o seu único jogo.
    - *"Instanciar o `MetaSystemsViewModel` sempre na Home."* O carrossel só existe deitado; em pé a Home não paga
      a query.
    - *"Fileira Jogos salvos pelo `InstalledGamesViewModel`."* Ver acima (`init` varre o disco). A Home ganha um
      fluxo próprio com a mesma query e o mesmo `isAvailable`, coletado só depois do primeiro quadro.
  - **Desenho:**
    - **Em pé** (`LazyColumn`): avisos (o mesmo bloco); **destaque** (fundo ambiente com a capa, capa 3:4 à
      esquerda, rótulo "Continue jogando"/"Descubra", título, chip do sistema, botão Jogar/Salvar/"Salvando 42%");
      **faixa "Salvando agora"** (só com fila ativa; abre o `SaveQueueModal`); fileiras Jogos salvos (com
      "Mostrar todos" → aba), Recentes, Favoritos, Descubra (`LazyRow`, capa 104×139 com título embaixo).
    - **Deitado:** trilho lateral em todas as rotas com barra inferior (abas + indicador da fila no pé); barra
      superior some ao rolar (`enterAlwaysScrollBehavior`, exceto na Busca) e perde o ícone da fila. Início: fundo
      ambiente na tela toda (capa do item em destaque/foco, troca depois de 300 ms parado), cabeçalho com rótulo,
      título, chip e botão; fileiras só de capas (72×96 / 150×200), na ordem Recentes, **Sistemas** (blocos só com
      a arte, 101×70 / 208×144), Jogos salvos, Favoritos, Descubra. O foco num cartão leva o título ao cabeçalho;
      num sistema, o nome e a contagem.
    - Toque: o mesmo `onGameClick`; Jogos salvos usa o mesmo lambda da aba (extraído para um `val`, corpo igual);
      sistema navega para `systems/{id}`.
  - **Tasks:**
    1. `SaveQueueViewModel.activeCount` (leitura aditiva). Prova: contagem no trilho com 3 na fila.
    2. `MainNavigationBar.kt` — `navigateToTab` comum; `MainNavigationRail`. Prova: abas e fila no trilho deitado,
       D-pad entra e sai do trilho.
    3. `MainTopBar.kt` — `scrollBehavior` e `showSaveQueueAction` opcionais. Prova: barra some/volta ao rolar.
    4. `HomeViewModel` — `savedGames` (mesma query da aba, 10 grupos, `isAvailable`); `Factory` recebe
       `DirectoriesManager`. Prova: fileira igual ao topo da aba Jogos salvos.
    5. `HomeScreen.kt` + `HomeLayouts.kt` (novo) — em pé e deitado. Prova: roteiro funcional, D-pad, rotação,
       `gfxinfo` da Início antes/depois.
    6. `MainActivity.kt` — moldura deitada, parâmetros novos da Home, `onInstalledGameClick`.
    7. Strings: `home_hero_continue` (en/pt-BR/es-ES).
- **2026-10-14 (entrega 2.3, entrega):** Início no visual novo e moldura deitada, **sem commit**. Doc da entrega:
  [`docs/funcionalidades/2026-10-14-redesenho-home.md`](../funcionalidades/2026-10-14-redesenho-home.md)
  (capturas em `docs/funcionalidades/img/2026-10-14-redesenho-home*.jpg`).
  - **Feito:** as 7 tasks do registro analítico. Desvios: (a) o botão do destaque usa `remember` +
    `LaunchedEffect` em vez de `produceState` (o lint acusa `ProduceStateDoesNotAssignValue` mesmo com a
    atribuição); (b) os avisos do topo perderam o `spacedBy`, que deixava ~64 dp vazios com todos escondidos (já
    acontecia antes); (c) o rótulo do topo deitado segue a fileira do item focado ("Continue jogando" nos
    recentes, o nome da fileira nas outras).
  - **Verificação:** build x86_64 e `lintFreeBundleDebug` OK; ktlint sem apontamento no código novo (o passivo
    reindentado do `MainActivity` aparece em outras linhas). Roteiro no `lemu_api25_2gb`: salvar pelo destaque →
    confirmação → anel/"Saving… 16%"/faixa → ✓/"Play"/"Play now?"; deitado: trilho, barra que some ao rolar, D-pad
    (trilho ↔ conteúdo, topo e fundo seguem o foco), carrossel → catálogo → voltar. `logcat -b crash` vazio.
  - **Medição inválida (refazer):** base 7,1% / 10,8% / 27,5% de quadros lentos (PSS ~93 MB); depois 42% / 86% /
    90% (PSS ~98 MB). Mas o controle (tela Sistemas, intocada) deu 54% / 81% contra 0,9–4,7% na 2.1, e entre as
    duas medições subiu o emulador `pp_api34` de outro projeto (host a 30–40% de CPU). A primeira tentativa do
    "depois" rodou junto com o lint e uma rolagem virou toque (abriu um jogo). O APK do HEAD não foi regerado para
    um A/B porque o checkout tem mudanças de outra sessão (`catalog_manifest.txt` nos dois caminhos), que um
    `stash` atrapalharia. Roteiro: `measure_home.sh` (force-stop, abre a Início, 3 × [6 gestos para cima + 6 para
    baixo], `gfxinfo`/`meminfo`).
  - **Não commitar** `catalog_manifest.txt` / `lemuroid-app/src/main/assets/catalog_manifest.txt`: não são desta
    entrega.
  - **Pendências novas:** recuo esquerdo dobrado da `TopAppBar` ao lado do trilho quando a barra de sistema/recorte
    está à esquerda (2.6); foco volta ao trilho depois do catálogo; "Systems" repetido no topo deitado.
  - **Próximo passo:** com só o `lemu_api25_2gb` no ar, medir A/B (APK do `06c7911` e o desta entrega, intercalados)
    e conferir no `lemu_api21_1gb`; depois commit da 2.3 (com o aval do dono) e a 2.4 (Jogos salvos). Abrir a sessão
    com: "Leia `docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md` e feche a medição da entrega 2.3."
- **2026-10-14 (entrega 2.3, medição fechada):** **sem regressão.** A tabela está no doc da entrega (seção de
  verificação).
  - **Como:** A/B intercalado (depois → antes → depois → antes) num moto g86 5G (Android 16, 120 Hz, depuração
    sem fio `192.168.18.112`), por troca do plano: o dono conectou um aparelho real no lugar do `lemu_api25_2gb`.
    O SM-A127M não apareceu no USB (o Windows não listava nenhum `VID_04E8`). APK da base compilado do `06c7911`
    num `git worktree` em `C:\projects\lemuroid\wt-06c7911` (submódulo por `--reference` ao local), sem tocar no
    checkout compartilhado. Roteiro: `measure.sh <rótulo> <home|systems>` (scratchpad da sessão), gestos
    y 2000↔800.
  - **Números:** Início antes 0–0,08% de quadros lentos, p90 12–14 ms, PSS 214–216 MB; depois 0–0,18%,
    p90 11–13 ms, PSS 207–217 MB. Controle (Sistemas) igual nos dois lados (0–0,07%, p90 11–15 ms), então a
    medição vale.
  - **Hipótese descartada:** *"A Início nova tem 42–90% de quadros lentos"* (medição anterior). O controle
    também dava 54–81% com o `pp_api34` disputando a CPU do host. Era o host, não o código.
  - **`lemu_api21_1gb`:** Início abre e rola, zero `FATAL EXCEPTION`. A fileira Jogos salvos mostra PSP no tier
    `ULTRA_WEAK`, igual à aba (sem `HeavySystemFilter`). **Decisão do dono (14/10/2026): fica assim.** Jogos
    salvos (aba e fileira) mostram tudo o que está salvo, em qualquer tier. Não aplicar o filtro de RAM ali.
  - **Limite:** o g86 é forte. Desempenho em TV box segue sem medida (sem aparelho).
  - **Próximo passo:** commit da 2.3, com o aval do dono, **sem** os dois `catalog_manifest.txt` (são de outra
    sessão); depois remover o worktree (`git worktree remove C:\projects\lemuroid\wt-06c7911`); depois a 2.4
    (Jogos salvos). Abrir a sessão com: "Leia `docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md`
    e execute a entrega 2.4 (Jogos salvos)."
