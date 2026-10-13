# [BACKLOG] Redesenho visual do Lemuroid inspirado nos temas do Pegasus

**Data:** 2026-10-13
**Status:** planejado; nenhuma fase iniciada. Próximo passo: Fase 0 (mockups).
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
| Arte de sistema | 79 PNGs normais (277×192) e 79 de foco (333×230), `game_system_*`. `MetaSystemID.color()` é só um matiz por ordinal, não a cor da marca. | `LIB/library/SystemLogoResolver.kt`, `LIB/library/MetaSystemID.kt:408` |
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
- Novos `Type.kt` (fonte em `res/font`), `Shape.kt` e `Dimens.kt`.
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
| 2.3 | Home | `M/feature/home/HomeScreen.kt:148-311` | `LazyColumn` com destaque e fileiras `LazyRow` no lugar de `homeGridSection` (`:284`). O destaque é o primeiro de `recentGames` ou, se vazio, de `discoveryGames`. Fileira "Jogos salvos", se aprovada na Fase 0 | Avisos do topo (`:213-255`), tela de carregamento (`:188`), diálogos, o toque chama o mesmo `onGameClicked` |
| 2.4 | **Jogos salvos** | `M/feature/installed/InstalledGamesScreen.kt`, `LemuroidInstalledGameCard.kt` | Tela no visual de biblioteca, cartão novo | Busca na aba, ordenação pela última ação, chip do sistema, contagem de versões, mídia inacessível, regras de toque (`MainActivity.kt:426-444`), requisitos R1–R9 |
| 2.5 | Favoritos e Busca | `M/feature/favorites/FavoritesScreen.kt` (S), `M/feature/search/SearchScreen.kt` (S), `LemuroidGameCard.kt` (S), `LemuroidEmptyView.kt` (S) | Cartões novos e estados vazios com ícone | Filtros da busca, escopo "só salvos", roteamento da busca (`MainActivity.kt:474-508`) |
| 2.6 | Moldura | `M/feature/main/MainTopBar.kt` (S), `MainNavigationBar.kt` (S), `MainGameContextActions.kt` (S), `GameVariantsModal.kt` (S), `SaveQueueModal.kt` (S) | Barra superior, barra inferior e folhas inferiores no visual novo | Indicadores da fila, ações do menu de contexto, pausar/retomar/cancelar |

O cabeçalho do catálogo (2.2) precisa do sistema. A rota já o tem (`MainActivity.kt:530-541`), então ele
entra como parâmetro opcional da `GamesScreen`.

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

### Ponteiros de subagente ainda não abertos (S)
- Mobile: `M/shared/compose/ui/LemuroidGameCard.kt:28`, `LemuroidGameGridItem.kt:34`,
  `LemuroidGameListRow.kt:28`, `LemuroidGameImage.kt:17` (decode 400 px, 256 em aparelho fraco; aspecto 0,75),
  `LemuroidSmallGameImage.kt:21`, `LemuroidEmptyView.kt:14`, `effects/BounceClickModifier.kt:22`.
- Moldura: `M/feature/main/MainNavigationRoutes.kt:38, 163`, `MainNavigationBar.kt:36`,
  `MainTopBar.kt:55-237`, `MainGameContextActions.kt:53`, `GameVariantsModal.kt:40`, `SaveQueueModal.kt:47`.
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

## Andamento

- **2026-10-13:** estudo e plano gravados. Nenhum código alterado.
  **Próximo passo:** Fase 0 (mockups). Abrir a sessão com: "Leia
  `docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md` e execute a Fase 0 (mockups)."
