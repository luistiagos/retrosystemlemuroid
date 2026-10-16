# [BACKLOG] Redesenho visual do Lemuroid inspirado nos temas do Pegasus

**Data:** 2026-10-13
**Status:** Fase 1, Fase 2 (2.1–2.6) e entregas 3.1–3.2 commitadas (`06c7911`, `df706f4`, `0f2962c`, `5d8725f`,
2.6 em `31cab86`, 3.1 + 3.2 em `773dbe2`). 3.3 (fundo ambiente da TV) em `2dc9412`; 3.4 (painel do item em foco, opção A) em `18cf57d`. Pendente: abrir o menu do jogo à mão.
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
  - **Feito depois (mesma sessão):** commit `df706f4` (2.3, sem os manifests); worktree removido.
- **2026-10-14 (entrega 2.4, registro analítico antes do código).** Árvore: só os dois `catalog_manifest.txt` da
  outra sessão modificados.
  - **Abertos:**
    - `M/feature/installed/InstalledGamesScreen.kt` — `Column` com `OutlinedTextField` (busca, `installed_search_hint`,
      limpar com `clear`) e `LazyVerticalGrid(Adaptive(144))`; vazio sem busca = `LemuroidEmptyView`, vazio com
      busca = `installed_no_search_results`; chave `"${systemId}_${title}_${id}"`. Toque e toque longo vêm da
      `MainActivity`.
    - `M/feature/installed/LemuroidInstalledGameCard.kt` — `ElevatedCard` + `bounceClick`, `LemuroidGameImage` +
      `LemuroidGameTexts`, chip do sistema (`shortTitleResId`, fallback `systemId`) no topo esquerdo, `CloudOff`
      vermelho + alfa 0,6 quando `!isAvailable`, pílula `Layers` + contagem quando `installedVariantsCount > 1`.
      Único chamador: a tela acima.
    - `M/feature/installed/InstalledGamesViewModel.kt` — **não muda.** `installedGames` (`WhileSubscribed`,
      `initialValue = emptyList()`: a tela vazia aparece um instante antes dos dados, comportamento já existente),
      busca com `debounce(150)` e `SystemSearchResolver`, `init` reconcilia a pasta gerenciada (R5).
    - `MainActivity.kt:378-396` `onInstalledGameClick` (mídia inacessível → toast; > 1 versão → seletor só das
      salvas; senão joga ou salva) e `:508-523` (rota `INSTALLED`, `Modifier.padding(padding)`). **Não mudam.**
    - `HomeLayouts.kt::SavedCover` (`:630-694`) já desenha o jogo salvo no visual novo: `CloudOff` em
      `LemuroidColors.ErrorBadge`, pílula `Layers` em `LemuroidColors.Bars` no canto inferior direito, alfa
      `UNAVAILABLE_ALPHA` 0,6. `SystemChip` (`:773`) é privado (pílula na cor de `SystemPalette`).
    - `GamesScreen.kt::CatalogGridCells` (`:313-328`) — grade de no mínimo 3 colunas, privada.
      `LemuroidGameGridItem` — capa 3:4 (`COVER_ASPECT` 0,75), `focusHighlight`, `combinedClickable`, título
      `cardTitle` 2 linhas, `generatedCoverTitle = false`.
    - R1–R9 e matriz da seção 8 de `2026-10-08-aba-jogos-instalados-salvos.md`: quase tudo é camada de dados (DAO,
      realinhador, monitor de mídia), que esta entrega não toca. O que é de UI: chip do sistema, contagem de
      versões, indicador de mídia inacessível com o jogo ainda na lista, busca por nome de sistema, busca que
      mantém a contagem de versões, Jogos salvos → Busca com escopo restrito.
  - **Hipóteses descartadas:**
    - *"Reaproveitar `LemuroidGameGridItem` com parâmetros novos."* Ele mostra `SaveStateOverlay` e o selo de
      variantes do catálogo. Aqui todo jogo está salvo (o ✓ seria ruído) e as versões contam só as salvas. Cartão
      próprio, montado com as mesmas peças.
    - *"Copiar `SavedCover` e `SystemChip` para o pacote `installed`."* Duas cópias divergem. Os selos de jogo
      salvo e o chip saem para componentes compartilhados, usados pela Início e pela aba.
  - **Desenho:** campo de busca em pílula (fundo `LemuroidColors.Surface`, sem contorno, ícones `TextSupport`);
    grade `CoverGridCells(gridCoverMinWidth)` (3 colunas em pé, mais deitado), margens/espaço de `LocalDimens`.
    Cartão: capa 3:4 com `cardCornerRadius`, chip do sistema compacto sobre a capa (topo esquerdo), `CloudOff`
    (topo direito) e pílula de versões (inferior direito), alfa 0,6 na capa se inacessível, título `cardTitle`
    2 linhas embaixo, `focusHighlight` + `combinedClickable`. Mensagem de busca sem resultado em `TextSupport`.
  - **Tasks:**
    1. `shared/compose/ui/CoverGridCells.kt` (novo) — `CatalogGridCells` sai do `GamesScreen` sem mudar a conta.
       Prova: catálogo do SNES com as mesmas colunas (3 em pé, 6 deitado no AVD).
    2. `shared/compose/ui/SystemChip.kt` (novo) — `SystemChip(systemId, compact)`; a Início usa o normal. Prova:
       destaque da Início igual à captura da 2.3.
    3. `feature/installed/SavedGameBadges.kt` (novo) — `BoxScope.SavedGameBadges(item)` (mídia + versões);
       `HomeLayouts.SavedCover` passa a usar. Prova: fileira da Início sem mudança visual.
    4. `LemuroidInstalledGameCard.kt` — cartão novo (assinatura mantida). `InstalledGamesScreen.kt` — busca e
       grade novas; chave e textos mantidos. Prova: roteiro abaixo.
    5. Verificação: build x86_64; AVD com 2+ jogos salvos (um com 2 versões); busca por título e por sistema
       ("snes"/"Super Nintendo"); toque (1 versão joga, 2 abre o seletor só das salvas); toque longo; deitado (mais
       colunas, D-pad); modo avião (abre, capas, joga); Jogos salvos → Busca com o chip de escopo. Mídia
       inacessível: `sm unmount` no AVD com SD, se der.
- **2026-10-14 (entrega 2.4, entrega):** Jogos salvos no visual novo, **sem commit**. Doc da entrega:
  [`docs/funcionalidades/2026-10-14-redesenho-jogos-salvos.md`](../funcionalidades/2026-10-14-redesenho-jogos-salvos.md)
  (capturas em `docs/funcionalidades/img/2026-10-14-redesenho-jogos-salvos*.jpg`).
  - **Feito:** as 4 tasks de código do registro analítico. Desvio: o campo de busca ganhou `onPreviewKeyEvent`
    (para baixo/cima saem do campo; esquerda sai com o campo vazio). Sem isso, o D-pad ficava preso no campo e a
    grade era inalcançável numa TV box. Provavelmente já acontecia com o `OutlinedTextField` antigo (mesmo
    `BasicTextField`), mas não foi medido.
  - **Verificação:** roteiro do item 5 no `lemu_api25_2gb` (instância própria, `emulator-5556`; o
    `emulator-5554` é de outro projeto e não foi tocado). Resultado por item no doc da entrega. Modo avião só
    valeu com `adb root` + `airplane_mode_on` + broadcast; `svc wifi disable` como `shell` não derruba a rede
    no API 25 (`SecurityException` no próprio `svc`).
  - **ktlint:** nenhum apontamento nos arquivos novos nem no `HomeLayouts`. No `GamesScreen`, 9 apontamentos
    antigos se deslocaram de linha (imports mexidos) e saíram do baseline; foram **corrigidos** (formatação),
    não congelados.
  - **Hipótese descartada:** *"O seletor de versões mostra download em versão já salva."* O ícone `Download` só
    aparece com `isDownloaded` (descrição `rom_download_badge_downloaded`): quer dizer "salvo". A confusão é do
    ícone, não da lógica.
  - **Ambiente:** `adb install` de 150 MB travou duas vezes neste AVD sem sair nada (o cliente ficou parado
    antes da cópia). `adb push` + `pm install` com `timeout` resolve em segundos.
- **2026-10-14 (entrega 2.5, registro analítico antes do código).** 2.4 commitada (`0f2962c`); árvore limpa.
  - **Abertos:**
    - `M/feature/favorites/FavoritesScreen.kt` — `LazyVerticalGrid(Adaptive(144))`, margem/espaço 16, chave
      `"id_$id"`/`"idx_$it"`, `LemuroidGameCard`. Mostra `LemuroidEmptyView` sempre que `itemCount == 0`,
      **inclusive enquanto a primeira página carrega** (o catálogo só mostra com `refresh` concluído).
      `FavoritesViewModel` (`selectFavorites`, paging 20) **não muda**.
    - `LemuroidGameCard.kt` — `ElevatedCard` + `bounceClick` (`pointerInput`, **não focável**: os favoritos não
      são alcançáveis pelo D-pad hoje), capa + `LemuroidGameTexts` (título e sistema), selo `Download` se salvo.
      **Único chamador:** `FavoritesScreen`.
    - `M/feature/search/SearchScreen.kt` — chips de escopo (sistema, "só salvos") como `FilterChip` padrão;
      `AnimatedContent` entre Idle (`game_page_search_suggestion`), Loading (anel), vazio (`empty_view_default`,
      ou `search_no_games_in_system` + botão `search_all_systems` com escopo de sistema) e resultados em
      `LazyColumn` de `LemuroidGameListRow` (já no visual novo desde a 2.2, mas sem `saveState`/`hasVariants`).
      `SearchViewModel` **não muda**.
    - `LemuroidEmptyView.kt` — `Box` + `Text` centrado. Chamadores: Favoritos, Busca, Catálogo, Sistemas,
      Jogos salvos (todos sem argumento, exceto a Busca com `text`).
    - `MainActivity.kt:534-606` — rotas `FAVORITES` e `SEARCH`; o `onGameClick` próprio da Busca com "só salvos"
      (`:561-600`) **não muda**. `saveQueueEntries` (`State`, `:358`) e `titlesWithVariants` (`:351`) já existem
      no escopo e já vão para o catálogo (`:640-642`).
    - Peças: `LemuroidGameGridItem` (capa 3:4, `SaveStateOverlay`, `VariantsBadge`, `focusHighlight`,
      `combinedClickable`), `CoverGridCells`, `SystemChip(compact)` (como no cartão de Jogos salvos),
      `rememberCoverSaveState`, `PillShape`, `LocalDimens`/`LocalLemuroidTypography`. `material-icons-extended`
      está nas dependências (`deps.kt:95`).
  - **Hipóteses descartadas:**
    - *"Manter `LemuroidGameCard` com restyle."* `bounceClick` não é focável; o cartão da grade já existe
      (`LemuroidGameGridItem`). O `LemuroidGameCard` fica sem chamador e é apagado.
    - *"Favoritos sem o sistema, como o catálogo."* O catálogo é de um sistema só; Favoritos mistura sistemas e
      hoje mostra o sistema (subtítulo). Vai o `SystemChip` compacto sobre a capa, como em Jogos salvos.
    - *"Busca em grade."* A busca cruza sistemas e tem o favorito na linha; a linha (2.2) já mostra sistema e
      estado. Fica em lista.
    - *"Texto novo para o vazio de Favoritos."* Exigiria strings em 3 idiomas; o pedido é ícone. Textos mantidos.
  - **Desenho:** Favoritos = `CoverGridCells(gridCoverMinWidth)` com `LemuroidGameGridItem` (+ chip do sistema,
    estado de salvamento, selo de variantes); vazio com ícone de coração só depois do carregamento. Busca = chips
    de escopo em pílula com margem de `LocalDimens`, linhas com estado e variantes, vazios com ícone (busca,
    busca sem resultado) e botão de ação em pílula. `LemuroidEmptyView` ganha `icon` e ação opcionais, texto em
    `TextSupport`.
  - **Tasks:**
    1. `LemuroidEmptyView.kt` — `icon`, `actionText`/`onActionClick` opcionais. Prova: outras telas sem ícone
       continuam iguais.
    2. `LemuroidGameGridItem.kt` — `showSystemChip` opcional. Prova: catálogo igual.
    3. `FavoritesScreen.kt` — grade nova, `titlesWithVariants`/`saveQueueEntries` opcionais; apagar
       `LemuroidGameCard.kt`. Prova: favoritos em pé/deitado, D-pad alcança os cartões, toque/toque longo.
    4. `SearchScreen.kt` — chips, vazios, parâmetros opcionais. Prova: busca com escopo de sistema, "só salvos",
       sem resultado, idle.
    5. `MainActivity.kt` — passa os dois parâmetros nas duas rotas. `CLAUDE.md` ("Comportamentos Conhecidos":
       o selo de variantes passa a aparecer em Favoritos e Busca).
- **2026-10-14 (entrega 2.5, entrega):** Favoritos e Busca no visual novo, **sem commit**. Doc da entrega:
  [`docs/funcionalidades/2026-10-14-redesenho-favoritos-busca.md`](../funcionalidades/2026-10-14-redesenho-favoritos-busca.md)
  (capturas em `docs/funcionalidades/img/2026-10-14-redesenho-favoritos-busca-*.jpg`).
  - **Feito:** as 5 tasks do registro analítico, sem desvio do desenho. O vazio com escopo de sistema da Busca
    deixou de ter componente próprio (`SearchEmptyView`): é o `LemuroidEmptyView` com ação.
  - **Verificação:** build x86_64, `ktlintMainSourceSetCheck` e `lintFreeBundleDebug` OK. Roteiro no moto g86 5G
    (o único aparelho conectado; debug arm64 instalado limpo), em pé e deitado: vazios, favoritar pela Busca,
    grade de Favoritos com chip, D-pad, toque longo, variantes → modal, não salvo → confirmação → fila → "Jogar
    agora?" → ✓ na linha, Jogos salvos → Busca com "Jogos instalados". `logcat -b crash` vazio.
  - **Não testado:** estados intermediários da fila nestas duas telas (download curto demais), botão "Buscar em
    todos os sistemas", AVDs, TV box, TalkBack, medição de quadros.
  - **Próximo passo:** commit da 2.5 (com o aval do dono); depois a 2.6 (Moldura), que herda as pendências da
    2.3 (recuo esquerdo dobrado da `TopAppBar` ao lado do trilho, foco que volta ao trilho depois do catálogo,
    "Systems" repetido no topo deitado). Abrir a sessão com: "Leia
    `docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md` e execute a entrega 2.6 (Moldura)."
  - **Feito depois:** commit `5d8725f` (2.5), com o aval do dono.
- **2026-10-15 (entrega 2.6, registro analítico antes do código).** 2.5 commitada (`5d8725f`, `26dcc48`); árvore
  limpa.
  - **Abertos:**
    - `M/feature/main/MainTopBar.kt` — `MainTopBar` (`Column`: `LemuroidTopAppBar` + barra da operação + barra da
      fila, esta só sem operação). `LemuroidTopAppBar`: `TopAppBar` com `BottomAppBarDefaults.containerColor`,
      `windowInsets` **padrão** (`safeDrawing` Horizontal + Top: é o recuo esquerdo dobrado ao lado do trilho),
      título `route.titleId` (na Busca, `LemuroidSearchView`: `Surface` pílula com `tonalElevation` + `TextField`
      transparente com foco automático). `LemuroidTopBarActions`: fila (`BadgedBox` com anel de 10 dp), Ajuda,
      Sincronizar (`saveSyncEnabled`, desabilitado com operação), Configurações (`showBottomNavigation`). Único
      chamador: `MainActivity.kt:447`.
    - `M/feature/main/MainNavigationBar.kt` — `MainNavigationBar` (`AnimatedVisibility` por `showBottomNavigation`)
      → `NavigationBar` + `NavigationBarItem` sem rótulo, `navigateToTab`. `MainNavigationRail` (2.3) já no visual
      novo: itens 56×40 com `focusHighlight` e pílula `AccentPillOpaque`, fila no pé.
    - `M/feature/main/MainNavigationRoutes.kt` — 5 abas (Início, Favoritos, Sistemas, Jogos salvos, Buscar);
      `SYSTEM_GAMES` tem `parent = SYSTEMS`, `title_games` e **mostra** a barra/trilho.
    - `M/feature/main/MainGameContextActions.kt` — `ModalBottomSheet` padrão; cabeçalho `LemuroidSmallGameImage`
      40×40 (iniciais) + `LemuroidGameTexts`; entradas `Row` 56 dp `clickable` (retomar, reiniciar, favoritar,
      atalho, excluir ROM se `isGameDownloaded`, excluir do catálogo); cada uma chama o lambda e zera o estado.
      `FakeScrim` sem chamador (já registrado como código morto).
    - `M/feature/main/GameVariantsModal.kt` — título, `variants_subtitle`, `LazyColumn` (chave `it.id`) de linhas
      `combinedClickable` com a capa **do representante** (`AsyncImage` cru, sem capa gerada) e ícone `Download`
      para salvo. Toque/toque longo chamam `onDismiss` e o lambda.
    - `M/feature/main/SaveQueueModal.kt` — título + "limpar erros"; vazio; `LazyColumn` (chave `fileName`) com capa
      `AsyncImage(entry.coverUrl)` sobre `surfaceVariant`, título, `statusLabel`, pausar (SAVING)/retomar
      (PAUSED), cancelar (tudo menos SAVED; em ERROR chama `dismissError`), barra de progresso em SAVING/PAUSED.
    - `MainActivity.kt:415-470` — `Row { trilho; Scaffold }`; `contentWindowInsets` sem o lado do trilho; a
      `TopAppBar` não recebia o mesmo tratamento. `:836-866` contexto, `:982-1019` fila e variantes (lambdas
      **não mudam**). `saveQueueEntries` (`State`, `:358`) já no escopo.
    - `M/feature/home/HomeLayouts.kt` — `LandscapeHeader` (`:387-446`) com `Featured.OfSystem` imprime
      `title_systems` como rótulo, e a fileira logo abaixo tem o mesmo título (`:456`): o "Systems" repetido.
      `SystemTile` (`:476-506`) navega por `data.onSystemClick`; ao voltar, a Início é recomposta do zero e o
      foco cai no primeiro focável (o trilho).
    - Design system: `LemuroidColors` (Bars/Border/Surface/Accent/AccentPill), `LocalLemuroidTypography`,
      `PillShape`, `focusHighlight` (precisa vir antes do nó focável na cadeia), `SaveStateIndicator`,
      `rememberCoverSaveState`, `LemuroidGameImage(compact)`, `SystemChip(compact)`, `SystemPalette.darkTone`.
      Nenhuma outra tela usa `ModalBottomSheet` (grep em `lemuroid-app/src/main/java` fora de `feature/main/`).
  - **Hipóteses descartadas:**
    - *"`NavigationBarItem` com cores novas basta."* O foco do D-pad nele é só a camada de estado de 12%, quase
      invisível numa TV box. Item próprio com contorno âmbar na pílula, como o trilho.
    - *"Nome do sistema no título da barra do catálogo."* Repetiria o cabeçalho da 2.2 logo abaixo. A
      especificação pede "voltar e, abaixo, o nome do sistema": a barra do catálogo fica sem título.
    - *"Restaurar o foco em todo retorno à Início."* No toque, focar o bloco acenderia o contorno sem D-pad. Só
      quando o clique veio do teclado/D-pad (`InputMode.Keyboard`).
  - **Tasks:**
    1. `MainTopBar.kt` — cores das barras, borda inferior, títulos Outfit, botões com `focusHighlight`, fila com
       anel âmbar, barras de progresso âmbar, busca em pílula `Surface`; `windowInsets` opcional; sem título no
       `SYSTEM_GAMES`. `MainActivity` passa `Top + End` com o trilho. Prova: deitado com a barra de navegação à
       esquerda, sem recuo extra.
    2. `MainNavigationBar.kt` — barra inferior própria (64 dp + insets, pílula âmbar, contorno no foco). Prova:
       5 abas na ordem, D-pad visível.
    3. `shared/compose/ui/LemuroidBottomSheet.kt` (novo) — folha com `Surface`, cantos 16, alça e cabeçalho comuns.
    4. `MainGameContextActions.kt`, `GameVariantsModal.kt` (+ `saveQueueEntries` opcional), `SaveQueueModal.kt` —
       visual novo, lógica igual. Prova: roteiro funcional (toque longo, variantes, pausar/retomar/cancelar).
    5. `HomeLayouts.kt` — rótulo do sistema sem "Systems"; foco volta ao bloco do sistema depois do catálogo
       (só D-pad). Prova: D-pad Início ▸ sistema ▸ voltar.
- **2026-10-15 (entrega 2.6, entrega):** moldura no visual novo, **sem commit**. Doc da entrega:
  [`docs/funcionalidades/2026-10-15-redesenho-moldura.md`](../funcionalidades/2026-10-15-redesenho-moldura.md)
  (capturas em `docs/funcionalidades/img/2026-10-15-redesenho-moldura-*.jpg`).
  - **Feito:** as 5 tasks do registro analítico, mais o estado de salvamento por versão na folha de versões.
    Desvio: a barra inferior usa `LocalIndication` (o `rememberRipple` está depreciado na versão de Compose
    efetivamente resolvida e o build trata o aviso como erro).
  - **Verificação:** build x86_64 e arm64, `ktlintMainSourceSetCheck` e `lintFreeBundleDebug` OK. Roteiro no moto
    g86 5G (único aparelho), com a barra de navegação à esquerda deitado: sem recuo dobrado; D-pad sistema ▸
    catálogo ▸ voltar com o foco no bloco; menu de contexto, salvar → fila → pausar/retomar/cancelar, versões.
    `logcat -b crash` vazio.
  - **Hipótese descartada:** *"`rememberRipple(bounded = false)` na aba."* Depreciado como erro de compilação.
  - **Pendência nova:** ao entrar no catálogo pelo D-pad o foco cai no trilho (já acontecia; é da 2.2).
  - **Próximo passo:** commit da 2.6 (com o aval do dono); depois a Fase 3 (TV), entrega 3.1 (tema). Abrir a
    sessão com: "Leia `docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md` e execute a entrega 3.1
    (Tema da TV)."
- **2026-10-15 (entrega 3.1, registro analítico antes do código).** A 2.6 segue **sem commit** na árvore (outra
  sessão); a 3.1 não toca em nenhum arquivo dela além deste doc.
  - **Abertos:**
    - `res/values/leanback-themes.xml` — `LemuroidLeanbackTheme` (`Theme.Leanback`): marca = `leanback_foreground`,
      busca/acento = `main_color`, fundo = `leanback_background`, cartão com `infoVisibility=always`; sobrescreve
      `lb_basic_card_bg_color`/`lb_basic_card_info_bg_color`. `LemuroidLeanbackPreferencesTheme` (Ajustes da TV):
      `colorPrimary` = `main_color`.
    - `res/values/leanback-colors.xml` e a cópia **idêntica** em `src/debug/res/values` (`diff` vazio): três tons
      cinza (`surface_elevation_0/1/3dp` de `retrograde-util`).
    - **`main_color` não pode mudar:** é também o fundo do ícone (`lemuroid_launcher_background.xml`) e da splash
      (`splash-theme.xml`), e o `src/debug/res/values/material-colors.xml` o sobrescreve em vermelho no debug. A TV
      ganha cores próprias (`leanback_accent` etc.); quem usa `main_color` na TV passa a usá-las:
      `TVCardFocusHighlight.kt:39` (por `getIdentifier`) e `activity_tv_main.xml:23` (progresso).
    - `TV/shared/TVCardFocusHighlight.kt::setupOnCard` — borda de 3 dp no `foreground` ao focar
      (`setFocusBorderCompat`: `View.foreground` com guard + reflexão em `FrameLayout`, pitfall 12). Chamadores:
      `GamePresenter`, `SystemPresenter` (troca a arte no foco pelo callback), `SettingPresenter`.
    - Presenters: os três criam `ImageCardView` cru; `GamePresenter` e `SystemPresenter` pintam `content_text` de
      `Color.LTGRAY` por código.
    - `TV/home/TVHomeFragment.kt` — `BrowseSupportFragment` com painel de cabeçalhos (padrão `HEADERS_ENABLED`),
      linhas por `TVListRowPresenter`, sem título. `TVGamesFragment`/`TVFavoritesFragment`
      (`VerticalGridSupportFragment`, sem título nem cabeçalho). `TVSearchFragment` (`SearchSupportFragment`, uma
      linha por `TVListRowPresenter`).
    - Leanback 1.1.0-rc01 (AAR do cache do Gradle, `values.xml` e `javap`): `lb_basic_card_title_text_color`,
      `lb_basic_card_content_text_color`, `lb_browse_header_color` e `lb_browse_header_description_color` são
      sobrescrevíveis como já se faz com `lb_basic_card_bg_color`; o orbe de busca lê `defaultSearchColor` e
      `defaultSearchIconColor` (ícone branco sobre âmbar daria ~1,9:1). `RowPresenter()` cria `RowHeaderPresenter()`
      (layout `lb_row_header`, público); o painel lateral usa `RowHeaderPresenter(R.layout.lb_header)`, construtor
      **`@RestrictTo`** (lint `RestrictedApi`). `BaseRowSupportFragment.getVerticalGridView()` é público.
  - **Hipóteses descartadas:**
    - *"`android:fontFamily="@font/outfit_*"` no tema."* Só vale no Android 8+; a TV box do público é 7.1.
    - *"Percorrer a árvore no `OnGlobalLayoutListener`."* O `RecyclerView` engole o `requestLayout` durante a
      rolagem: cartão que entra rolando ficaria sem a fonte até o próximo layout.
    - *"Trocar o seletor do painel lateral por `RowHeaderPresenter(lb_header)`."* Construtor restrito. E embrulhar o
      presenter original arrisca o `cast` interno do `HeadersSupportFragment`. Fica o
      `addOnChildAttachStateChangeListener` no `VerticalGridView` do painel, que dispara em toda vinculação.
    - *"Contorno afastado 3 dp, como no celular."* O `ListRowPresenter` recorta o cartão no contorno arredondado
      (`clipToOutline`): nada fora dos limites do cartão aparece. Equivalente dentro do cartão: anel âmbar de 3 dp
      por fora e anel escuro de 2 dp por dentro, que separa o âmbar de capa clara.
    - *"Fonte nos Ajustes da TV."* Exigiria engatar nas listas do `leanback-preference`; Ajustes estão fora do
      redesenho (herdam só cores, pelo tema). Registrado como não feito.
  - **Tasks:**
    1. `leanback-colors.xml` (main) com os tokens da direção A; apagar a cópia de `src/debug`. Prova: build debug.
    2. `leanback-themes.xml` — marca = barras, acento/busca = âmbar, ícone da busca escuro, textos dos cartões e
       cabeçalhos; Ajustes com `colorPrimary`/`colorAccent` âmbar. `activity_tv_main.xml` com o progresso âmbar.
    3. `TV/shared/TVCardFocusHighlight.kt` — anel âmbar + anel escuro, cor por `R.color`, sem `getIdentifier`.
    4. `TV/shared/TVTypography.kt` (novo) — Outfit por `ResourcesCompat.getFont` (falha = fonte padrão),
       `styleCard`, cabeçalho de fileira (`TVRowHeaderPresenter` em `TVListRowPresenter`), painel lateral
       (`TVHomeFragment`) e campo da busca (`TVSearchFragment`). Presenters sem `Color.LTGRAY`.
    5. Prova: `lemu_tv_api25` (Android 7.1) — Início, sistema, favoritos, busca, ajustes, foco por D-pad, menu de
       contexto; `logcat -b crash` vazio; build, `ktlintMainSourceSetCheck`, `lintFreeBundleDebug`.
- **2026-10-15 (entrega 3.1, entrega):** tema da TV pronto, **sem commit**. Doc da entrega:
  [`docs/funcionalidades/2026-10-15-redesenho-tema-tv.md`](../funcionalidades/2026-10-15-redesenho-tema-tv.md)
  (capturas antes/depois em `docs/funcionalidades/img/2026-10-15-redesenho-tema-tv-*.jpg`).
  - **Feito:** as tasks 1–4 do registro analítico. Regra 9 nova na seção "Design system" do `CLAUDE.md`.
  - **Verificação:** build x86, `ktlintMainSourceSetCheck` e `lintFreeBundleDebug` OK. O ktlint acusou 8
    apontamentos antigos do baseline que mudaram de linha; foram corrigidos, sem regenerar o baseline. No
    `lemu_tv_api25` (7.1), a Outfit renderiza e o D-pad passou por painel, fileiras, catálogo rolado, busca e
    Ajustes com o foco visível. `logcat -b crash` vazio.
  - **Não verificado:** menu de contexto (o `adb` não reproduz o toque longo neste AVD; o código não foi
    tocado), jogar, TV box real.
  - **Pendências:** títulos longos cortam antes com a Outfit (3.2, capa retrato); o fundo do painel dos Ajustes
    continua o do `leanback-preference`.
  - **Próximo passo:** commit da 2.6 e da 3.1 (com o aval do dono); depois a 3.2. Abrir a sessão com: "Leia
    `docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md` e execute a entrega 3.2 (Cartões e diálogo
    de salvar da TV)."
- **2026-10-15 (entrega 3.2, registro analítico antes do código).** 2.6 e 3.1 continuam **sem commit** na árvore;
  a 3.2 é escrita por cima delas (`GamePresenter`, `SystemPresenter`, `SettingPresenter` e `TVHomeFragment` já
  modificados pela 3.1). Nada é commitado nesta sessão sem o aval do dono.
  - **Abertos:**
    - `TV/shared/GamePresenter.kt` — `ImageCardView` com `setMainImageDimensions(cardSize, cardSize)` (176 dp,
      `retrograde-app-shared` `dimens.xml:30`); capa por `CoverUtils.loadCover` (Coil no `mainImageView`, com
      `fallback`/`error` = `TextDrawable` de iniciais em cor aleatória). `loadCover` só tem este chamador;
      `getFallbackDrawable` também é usado por `ShortcutsGenerator` e `LemuroidSmallGameImage` (não mudam).
    - Chamadores do `GamePresenter`: `TVHomeFragment.recreateAdapter` (Favoritos, com `SettingPresenter` na mesma
      fileira para o "ver todos", e Recentes), `TVGamesFragment:47`, `TVFavoritesFragment:43`,
      `TVSearchFragment:95`. Grades com `numberOfColumns = 4` (`TVGamesFragment`, `TVFavoritesFragment`).
    - `TV/home/SystemPresenter.kt` — quadrado de 176 dp, arte `SystemLogoResolver` em `FIT_CENTER` com padding de
      22 dp sobre `MetaSystemID.color()`; troca a arte no foco pelo callback do `TVCardFocusHighlight`.
    - `TV/game/TVRomDownloadDialog.kt` — três `android.app.AlertDialog` no tema da Activity: confirmação
      (`rom_download_dialog_*`), progresso (título, status, `ProgressBar` horizontal, `setCancelable(false)`,
      Cancelar → `cancelActiveDownload()`), erro (com guarda de Activity finalizando). Chamador único:
      `MainTVActivity.kt:177` (`onPlaceholderGame`).
    - Design system mobile: `SystemPalette.color/colorForSystemId/darkTone` (Compose `Color`, sem Coil);
      `GeneratedCover.kt` com `SystemArtCache` **privado** (LRU por bytes, `inScaled = false`, RGB_565 em `LITE`);
      `LemuroidSystemImage.drawSystemArt` (recorte de 2,2% da moldura branca, mistura *screen*, arte larga
      ajustada inteira).
    - Leanback 1.1.0-rc01 (AAR do cache, `values.xml` e `javap`): título do `ImageCardView` com `maxLines=1`;
      `setMainImage(null)` deixa o `mainImageView` `INVISIBLE` e o construtor também o cria `INVISIBLE` sem
      drawable; pôr o drawable direto no `ImageView` (como o Coil faz) não muda a visibilidade.
  - **Medido no `lemu_tv_api25`:** tela 1920×1080 a 320 dpi = 960×540 dp. Grade atual: 4 colunas de 176 dp
    ocupam ~728 dp. Com 150 dp, 5 colunas ocupam ~782 dp; 6 não cabem com o zoom do foco.
  - **Hipóteses descartadas:**
    - *"Reaproveitar o `GeneratedCover` do Compose."* Os presenters são `View`; a capa gerada da TV é um
      `Drawable` que repete o desenho (tom escuro + arte em *screen*), sem título — o título já está no cartão.
    - *"Título do cartão em 2 linhas pelo tema."* Valeria para todo `ImageCardView` (ajustes, sistemas). Fica por
      código só no cartão de jogo, com 2 linhas fixas para a grade não ficar com alturas desiguais.
    - *"`alertDialogTheme` no `LemuroidLeanbackTheme`."* Mudaria também o `TVAppUpdateDialog`, fora da entrega. O
      tema do diálogo vai explícito no `AlertDialog.Builder` do `TVRomDownloadDialog`.
  - **Tasks:**
    1. `GeneratedCover.kt` — `SystemArtCache` passa de `private` a `internal` (a TV reaproveita o cache).
    2. `TV/shared/TVCovers.kt` (novo) — `Drawable` de arte de sistema (cor + arte recortada em *screen*) e
       `loadCover`: sem URL = capa gerada; com URL = Coil com o tom do sistema enquanto carrega e capa gerada no
       erro. Prova: catálogo do Arduboy (sem capas) e um sistema com capas.
    3. `GamePresenter` 150×200 (`res/values/dimens.xml` do app), título em 2 linhas; `SystemPresenter` 208×144
       com `SystemPalette`; `SettingPresenter` com largura/altura (na fileira de Favoritos usa o tamanho do jogo).
       Chamadores atualizados; grades com 5 colunas. Prova: Início, catálogo, favoritos, busca no AVD de TV.
    4. `TVRomDownloadDialog` — tema `LemuroidTVDialog` (superfície, cantos 16, âmbar, foco visível), layout com
       capa, título, sistema e status; mesmos três diálogos, mesmos botões e handlers. Prova: salvar um jogo
       (confirmação → progresso → joga), cancelar no meio, erro.
    5. Verificação: build x86, `ktlintMainSourceSetCheck`, `lintFreeBundleDebug`, `logcat -b crash` vazio.
- **2026-10-15 (entrega 3.2, entrega):** cartões e diálogo de salvar da TV prontos, **sem commit**. Doc da entrega:
  [`docs/funcionalidades/2026-10-15-redesenho-cartoes-tv.md`](../funcionalidades/2026-10-15-redesenho-cartoes-tv.md)
  (capturas em `docs/funcionalidades/img/2026-10-15-redesenho-cartoes-tv-*.jpg`).
  - **Feito:** as tasks 1–4 do registro analítico.
  - **Desvio do registro:** o tema do diálogo vai pelo id no `AlertDialog.Builder(activity, R.style.LemuroidTVDialog)`.
    Com `Builder(ContextThemeWrapper)` o diálogo saiu cinza e sem cantos: o construtor resolve o
    `?android:alertDialogTheme` do contexto, não o tema do wrapper (hipótese descartada na prova do AVD).
  - **Verificação:** build x86, `ktlintMainSourceSetCheck` (4 imports corrigidos) e `lintFreeBundleDebug` OK. No
    `lemu_tv_api25`: Início, fileira de sistemas com foco, catálogo da Amiga CD32 (5 colunas, títulos em 2 linhas,
    capas geradas), busca, salvar → progresso → cancelar, jogo salvo → joga. `logcat -b crash` vazio.
  - **Não verificado:** diálogo de erro, salvar → jogar ponta a ponta com o diálogo novo, Favoritos (o AVD não tem),
    menu de contexto, TV box real.
  - **Pendências:** foco do botão do diálogo (âmbar a 25%) a conferir em TV box real; `systems_card_padding`
    (`retrograde-app-shared`) sem uso.
  - **Commits (15/10/2026, com o aval do dono):** 2.6 em `31cab86`; 3.1 + 3.2 juntas em `773dbe2` (mexem nos mesmos
    arquivos de TV). A remoção da cópia `src/debug/res/values/leanback-colors.xml` (3.1) saiu no `31cab86`, por já
    estar no índice; sem efeito no build, a cópia era idêntica à de `main`.
  - **Próximo passo:** a 3.3. Abrir a sessão com:
    "Leia `docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md` e execute a entrega 3.3 (Fundo ambiente
    da TV)."
- **2026-10-15 (entrega 3.3, registro analítico antes do código).** Escrito com 2.6, 3.1 e 3.2 ainda sem commit; outra
  sessão as commitou no meio da 3.3 (`31cab86`, `773dbe2`), e a árvore ficou só com a 3.3. Nada é commitado sem o aval do dono.
  - **Abertos:**
    - `TV/home/TVHomeFragment.kt` — `BrowseSupportFragment`; só `onItemViewClickedListener` (itens `Game`,
      `MetaSystemInfo`, `TVSetting`). `TVGamesFragment`/`TVFavoritesFragment` — `VerticalGridSupportFragment` com
      `PagingDataAdapter` (item `null` = placeholder do paging). `TVGamesFragment` tem `args.metaSystemId`.
      `TVSearchFragment` — `SearchSupportFragment`, uma linha paginada. Os três tipos têm
      `setOnItemViewSelectedListener` público (`javap` do AAR 1.1.0-rc01). `grep BackgroundManager|OnItemViewSelectedListener`
      em `lemuroid-app/src/main/java`: nenhuma ocorrência.
    - `MainTVActivity` — `activity_tv_main.xml` é um `FrameLayout` sem fundo; o fundo vem do `windowBackground`
      do `LemuroidLeanbackTheme` (`leanback_background`). Layouts `lb_browse_fragment`, `lb_vertical_grid_fragment`
      e `lb_search_fragment` não declaram fundo: o que o `BackgroundManager` pinta no decor aparece atrás das
      fileiras (o painel de cabeçalhos continua opaco, `defaultBrandColor`).
    - `BackgroundManager` (bytecode do AAR): `attach` duas vezes lança `IllegalStateException` (guard por
      `isAttached`); troca com fade de 500 ms e no máximo uma troca a cada 500 ms (`getRunnableDelay`); sem camada de
      escurecimento por padrão (`lb_background.xml` só tem `imagein`/`imageout`); `onStop` com
      `autoReleaseOnStop` (padrão) solta o drawable e o `onStart` o recupera do `BackgroundContinuityService` por
      **`drawable.getConstantState().newDrawable()`** — um `Drawable` próprio sem `ConstantState` daria NPE ao
      voltar do jogo. Por isso o fundo é um `BitmapDrawable`.
    - `TVCovers.kt` (3.2) — `SystemArtDrawable` (cor + arte em *screen*, sem a moldura) e `systemArt` (privado,
      `SystemArtCache`); `SystemPalette.color/colorForSystemId/darkTone`; `VisualQuality.of(context)` (uma vez por
      processo, sem Coil). `AmbientBackdrop` (mobile): imagem a 32 px, alpha 0,6, degradê por cima; em `LITE`, só o
      degradê do tom escuro do sistema.
  - **Hipóteses descartadas:**
    - *"`setDrawable` com um `Drawable` próprio (capa + degradê)."* Sem `ConstantState`, quebra no `onStart` (acima).
    - *"`BitmapDrawable` da capa de 32 px direto no `BackgroundManager`."* Ele estica sem manter a proporção (capa
      3:4 numa tela 16:9) e não leva o degradê. O fundo é composto num bitmap de 96×54 (capa recortada ao centro +
      degradê), que o `BitmapDrawable` amplia com filtro bilinear — o "desfoque" da direção A.
    - *"Capa por Coil com o padrão do app."* Bitmap de hardware não desenha num `Canvas` de software:
      `allowHardware(false)`.
    - *"Arte do sistema pelo Coil a 32 px."* A moldura branca do PNG viraria uma faixa clara nas bordas. Usa o
      `SystemArtDrawable` (já recorta a moldura) desenhado no bitmap pequeno.
  - **Tasks:**
    1. `TV/shared/TVAmbientBackground.kt` (novo) — liga o `BackgroundManager` (uma vez), espera 300 ms parado no
       item, compõe o bitmap 96×54 e chama `setDrawable`. Jogo: capa (Coil 32 px) ou, sem capa, arte do sistema;
       sistema: arte do sistema; ajuste: fundo neutro; `null` (placeholder): ignora. `LITE`: só o degradê do tom escuro
       do sistema, sem Coil. `TVCovers.systemArt` passa a `internal`.
    2. Fragments — `TVHomeFragment`, `TVGamesFragment` (começa com a arte do sistema), `TVFavoritesFragment`,
       `TVSearchFragment` (começa neutro) com `setOnItemViewSelectedListener`.
    3. Prova no `lemu_tv_api25`: Início (fileiras de jogos, sistemas, ajustes), catálogo, busca, voltar ao Início, jogar
       e voltar (fundo restaurado, sem crash); build x86, `ktlintMainSourceSetCheck`, `lintFreeBundleDebug`,
       `logcat -b crash` vazio.
- **2026-10-15 (entrega 3.3, entrega):** fundo ambiente da TV pronto, **sem commit**. Doc da entrega:
  [`docs/funcionalidades/2026-10-15-redesenho-fundo-ambiente-tv.md`](../funcionalidades/2026-10-15-redesenho-fundo-ambiente-tv.md)
  (capturas em `docs/funcionalidades/img/2026-10-15-redesenho-fundo-ambiente-tv-*.jpg`).
  - **Feito:** as tasks 1–2 do registro analítico.
  - **Desvios do registro:** bitmap de 48×27 em vez de 96×54 (a 96×54 a arte do sistema ainda saía legível, sem
    cara de desfoque; a capa já chega a 32 px). `isAutoReleaseOnStop = false` no `attach`.
  - **Hipóteses descartadas na prova:**
    - *"O `onStart` devolve o fundo do `BackgroundContinuityService`."* Só o `attach` lê o serviço; com o
      auto-release padrão, ao voltar do jogo (ou da Home) o fundo ficava neutro.
    - *"O fundo não troca no catálogo"* (primeira prova). Não era defeito: o `lemu_tv_api25` (2 GB) é tier `WEAK`, logo
      `LITE`, onde todo jogo do mesmo sistema tem o mesmo degradê. Conferido por reflexão nos portões do
      `postChangeRunnable` (alpha 255, sem animação, fragment retomado) e com `FULL` forçado num build temporário.
    - *"`runCatching` em volta do `execute` do Coil."* Engolia o cancelamento: a seleção anterior aplicaria um fundo velho.
  - **Verificação:** build x86, `ktlintMainSourceSetCheck` (1 apontamento novo corrigido) e `lintFreeBundleDebug`.
    `LITE` real e `FULL` forçado no `lemu_tv_api25`: Início, catálogo, voltar, Ajustes, busca, Home + reabrir, jogar e
    voltar. `logcat -b crash` vazio.
  - **Não verificado:** Favoritos (o AVD não tem), TV box real, `gfxinfo`.
  - **Pendências:** fade de 500 ms do `BackgroundManager` também em `LITE`; contraste do campo de busca sobre capa clara.
  - **Feito depois:** commit `2dc9412` (3.3), com o aval do dono.
  - **Próximo passo:** avaliar a 3.4 (painel do item em
    foco). Abrir a sessão com: "Leia `docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md` e avalie a
    entrega 3.4 (Painel do item em foco na Home da TV)."
- **2026-10-15 (entrega 3.4, avaliação — nenhum código alterado).** Aguarda a decisão do dono entre as opções abaixo.
  - **Abertos:**
    - `TV/home/TVHomeFragment.kt` — único `BrowseSupportFragment` do app (`grep BrowseSupportFragment` em `src/main`).
      Fileiras: Favoritos (`GamePresenter` + "ver todos"), Recentes (`GamePresenter`), Sistemas (`SystemPresenter`),
      Ajustes (`SettingPresenter`). O `onItemViewSelectedListener` já existe (3.3) e só alimenta o
      `TVAmbientBackground` — o painel engataria no mesmo ponto.
    - **Os cartões já mostram o que o painel mostraria.** `GamePresenter`: título em 2 linhas + `GameUtils.getGameSubtitle`
      (nome curto do sistema; `developer` é nulo no catálogo). `SystemPresenter`: nome + "N jogos". `SettingPresenter`:
      rótulo. Tema: `infoVisibility=always` (`leanback-themes.xml:25`). Não há outro dado do jogo (ano, gênero,
      descrição — hipótese já descartada na análise do plano).
    - Capturas da 3.3 (960×540 dp): a fileira em foco é alinhada a 167 dp do topo (`lb_browse_rows_margin_top`); na
      primeira fileira a faixa de cima tem só o orbe de busca (x 56–110, y 27–87 dp); da segunda em diante o orbe some e
      as fileiras sobem.
    - Leanback 1.1.0-rc01 (`javap` + `res/` do AAR): `BrowseSupportFragment.onCreate` lê `browseRowsMarginTop` do tema
      do contexto (`mContainerListAlignTop`, também passado ao painel de cabeçalhos) — como só a Início é
      `BrowseSupportFragment`, mudar o atributo no `LemuroidLeanbackTheme` só afeta ela. `updateTitleViewVisibility`
      mostra o `TitleView` só com o painel de cabeçalhos aberto ou na primeira fileira com conteúdo.
      `setBrowseTransitionListener` é público (para esconder o painel quando os cabeçalhos abrem).
      `BaseCardView.setInfoVisibility` é público (cartão sem texto por instância, sem mexer no tema).
      `lb_title_view.xml`: orbe à esquerda, `title_text`/`title_badge` à direita.
  - **Hipóteses descartadas:**
    - *"Usar o título do próprio `BrowseSupportFragment` (`title = game.title`)."* O `TitleView` some a partir da segunda
      fileira (`updateTitleViewVisibility`): o painel sumiria justamente nas fileiras de Sistemas e Ajustes.
    - *"`onInflateTitleView` com um `TitleView` próprio maior."* Mesmo problema: herda a regra de visibilidade do título.
    - *"Trocar o `BrowseSupportFragment` por `RowsSupportFragment` num layout nosso."* Perde orbe de busca e painel de
      cabeçalhos; reescrita da Início, não redesenho.
    - *"Painel somado aos cartões como estão."* Título e sistema apareceriam duas vezes na tela, a ~150 dp de distância.
      Custo de manutenção sem ganho de informação.
  - **Opções para o dono:**
    - **A (recomendada) — vitrine da prancha A:** painel fixo no topo (título Outfit 700 em até 2 linhas, chip com a cor
      da `SystemPalette` e o nome do sistema; em sistema, nome + "N jogos"; em ajuste, o rótulo), e os cartões de **jogo
      e sistema da Início** só com a imagem (`setInfoVisibility` por instância; catálogo, favoritos e busca continuam com
      título). É o mesmo arranjo que a Início deitada do celular já usa ("nas fileiras só a capa; o título do item em
      foco fica no topo"). **Efeito colateral a aceitar:** jogo sem capa na fileira vira só a capa gerada (sem título, por
      decisão da 3.2) — ZX Spectrum/Arduboy nos Favoritos ficam iguais entre si até o foco chegar neles.
    - **B — não fazer a 3.4.** A 3.3 já dá o fundo ambiente, e o cartão já identifica o item. Fecha a Fase 3 visual
      com a 3.5/3.6 pendentes de decisão própria.
  - **Tasks, se A:**
    1. `res/layout/tv_focus_panel.xml` + `TV/home/TVFocusPanel.kt` (novo): título, chip e linha de apoio; não focável;
       troca imediata no `FULL` com *fade* curto, sem animação no `LITE`; `Game`/`MetaSystemInfo`/`TVSetting`/`null`
       (placeholder mantém o anterior). Prova: foco por D-pad nas quatro fileiras.
    2. `TVHomeFragment.onCreateView` — sobrepor o painel à `BrowseFrameLayout` (abrir antes como o `BrowseSupportFragment`
       guarda a raiz: se usa `getView()` em algum ponto, embrulhar num `FrameLayout` quebra); alimentar pelo
       `onItemViewSelectedListener` existente; esconder com `setBrowseTransitionListener` quando os cabeçalhos abrem.
    3. `browseRowsMarginTop` no `LemuroidLeanbackTheme` (valor a medir: painel ~90–110 dp entre o orbe e a fileira, sem
       a fileira de jogo de 200 dp + zoom passar dos 540 dp). Prova: captura em 960×540 dp com a fileira de jogos e a de
       sistemas em foco.
    4. `GamePresenter`/`SystemPresenter` com parâmetro `showInfo` (padrão `true`); a Início passa `false`. Ajustes ficam
       com rótulo (ícone sozinho não diz o que faz).
    5. Verificação: build x86, `ktlintMainSourceSetCheck`, `lintFreeBundleDebug`, `lemu_tv_api25` em `LITE` e `FULL`
       forçado (Início, abrir/fechar cabeçalhos, busca e voltar, jogar e voltar, menu de contexto), `logcat -b crash`
       vazio; capturas antes/depois no doc da entrega.
  - **Próximo passo:** decisão do dono (A ou B). Se A, abrir a sessão com: "Leia
    `docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md` e execute a entrega 3.4 (opção A)."
- **2026-10-15 (entrega 3.4, opção A — registro analítico antes do código).** O dono escolheu a opção A.
  - **Abertos nesta sessão:** `TVHomeFragment` (inteiro), `GamePresenter`, `SystemPresenter`, `SettingPresenter`,
    `TVSetting`/`TVSettingType`, `TVAmbientBackground`, `TVTypography`, `TVCardFocusHighlight`, `TVGridPresenters`,
    `leanback-themes.xml`, `leanback-colors.xml`, `dimens.xml` (`tv_*`), `GameUtils.getGameSubtitle` (nome curto do
    sistema por `GameSystem.shortTitleResId`), `MetaSystemInfo`, `SystemChip` (mobile: nome curto, negrito, branco,
    pílula na cor da `SystemPalette`, padding 10/3). Leanback 1.1.0-rc01 por `javap -c` (cópia em scratchpad):
    - `BrowseSupportFragment.onCreate` lê `browseRowsMarginTop` de `getContext()` (a Activity) para
      `mContainerListAlignTop`; ele vira a linha de alinhamento da fileira selecionada (`setMainFragmentAlignment`,
      reaplicado em **todo** `onStart` e em cada transição de cabeçalhos — `expandMainFragment`), o alinhamento do
      painel de cabeçalhos e o `pivotY` do `ScaleFrameLayout`. Sobrescrever o alinhamento por código seria desfeito
      nesses pontos.
    - `onCreateView` devolve a raiz `browse_dummy` (`FrameLayout`); `browse_frame` e o `TitleView` ficam dentro dela.
    - `RowsSupportFragment.setAlignment`: `NO_EDGE`, `itemAlignmentOffset 0` — o **conteúdo** da fileira (não o
      cabeçalho) encosta na linha; o cabeçalho fica ~30 dp acima (captura da 3.3: linha 167, texto "Systems" em
      145–162, cartões em 183). A fileira anterior termina ~57 dp acima da linha.
    - `BrowseTransitionListener` (`onHeadersTransitionStart/Stop(withHeaders)`), `isShowingHeaders()`,
      `getRowsSupportFragment()` são públicos.
    - `ImageCardView`: `lbImageCardViewType = ImageOnly` (0) remove o `mInfoArea` no construtor; `setTitleText` com
      título nulo não faz nada. O construtor `ImageCardView(Context)` lê `?imageCardViewStyle` do tema do contexto.
  - **Hipóteses descartadas (corrigem a avaliação acima):**
    - *"Mudar `browseRowsMarginTop` no `LemuroidLeanbackTheme` só afeta a Início."* Falso: o atributo também é o
      `paddingTop` de `Widget.Leanback.GridItems.VerticalGridView` (`itemsVerticalGridStyle`), a grade do
      `VerticalGridSupportFragment` — catálogo e favoritos desceriam 69 dp. (`grep browseRowsMarginTop` no `values.xml`
      do AAR: só o `BrowseSupportFragment` em código e essa linha de estilo; o `Rows.VerticalGridView` não tem
      `paddingTop`, e a busca usa `lb_search_browse_rows_align_top`.) Correção: o tema sobe o atributo **e** fixa
      `itemsVerticalGridStyle` num estilo nosso com o `paddingTop` de hoje (167 dp).
    - *"`setInfoVisibility` por instância esconde o texto."* Só há `always`/`activated`/`selected`; não existe
      "nunca", e `selected` mostraria o título justamente no cartão em foco, repetindo o painel. Cartão só-imagem =
      `ImageCardView` sobre `ContextThemeWrapper` com um `imageCardViewStyle` `ImageOnly`.
    - *"Sobrepor o painel basta."* A fileira anterior à selecionada continua desenhada acima dela (na 3.3, o "1010"
      aparece no topo com Sistemas em foco) e ficaria embaixo do painel. Correção: `clipBounds` na `VerticalGridView`
      da `RowsSupportFragment`, cortando o que fica acima do cabeçalho da fileira selecionada.
  - **Geometria (960×540 dp):** orbe em y 27–87 (só na 1ª fileira); painel ancorado embaixo, terminando em y 188,
    conteúdo de até ~94 dp (título 24sp em 2 linhas + chip) → começa em ≥ 94; corte das fileiras em y 200; linha de
    alinhamento 236 (+69). Fileira de jogo só-imagem: 236 + 16 + 200 = 452, + zoom do foco < 540.
  - **Tasks:**
    1. `res/layout/tv_focus_panel.xml` + `TV/home/TVFocusPanel.kt`: título (Outfit 700, 24sp, 2 linhas), chip
       (pílula na cor da `SystemPalette`, nome curto do sistema; em sistema, "N jogos"; ajuste sem chip). Não focável,
       fora da acessibilidade (o cartão já é lido). `FULL`: fade de 150 ms; `LITE`: troca seca. `null` mantém.
    2. `TVHomeFragment`: painel na raiz devolvida por `super.onCreateView`; alimentado pelo `onItemViewSelectedListener`
       da 3.3; escondido por `setBrowseTransitionListener` (cabeçalhos abrindo) e no início se `isShowingHeaders()`;
       corte das fileiras por `registerFragmentLifecycleCallbacks` (no `onCreate`, como o `TVTypography`) na
       `RowsSupportFragment`.
    3. Tema: `browseRowsMarginTop` = 236 dp; `itemsVerticalGridStyle` com `paddingTop` 167 dp (grades iguais a hoje).
    4. `GamePresenter(showInfo)`, `SystemPresenter(showInfo)`, `SettingPresenter(showInfo)`, padrão `true`. A Início
       passa `false` para jogos, sistemas e o "Ver todos" da fileira de Favoritos (desvio da avaliação: com os jogos
       só-imagem, o "Ver todos" com rótulo deixaria a fileira com alturas desiguais; o painel mostra "Ver todos" no
       foco). A fileira Ajustes mantém o rótulo.
    5. Prova no `lemu_tv_api25`: medir as linhas com `uiautomator dump` (cabeçalho, corte, painel), catálogo e busca
       sem deslocamento (comparar com as capturas da 3.2/3.3), abrir/fechar cabeçalhos, jogar e voltar, menu de
       contexto; `LITE` e `FULL` forçado; build x86, `ktlintMainSourceSetCheck`, `lintFreeBundleDebug`,
       `logcat -b crash` vazio.
- **2026-10-15 (entrega 3.4, opção A — entrega):** painel do item em foco na Início da TV pronto, **sem commit**. Doc da
  entrega: [`docs/funcionalidades/2026-10-15-redesenho-painel-foco-tv.md`](../funcionalidades/2026-10-15-redesenho-painel-foco-tv.md)
  (capturas em `docs/funcionalidades/img/2026-10-15-redesenho-painel-foco-tv-*.jpg`).
  - **Feito:** as tasks 1–4 do registro analítico acima.
  - **Desvios do registro:** corte em 196 dp (não 200): medido no `uiautomator dump`, o cabeçalho da fileira selecionada
    ocupa 208–236 e os cartões da fileira de cima terminam em ~180, então 196 deixa 12–16 dp de folga para cada lado.
    Título com `includeFontPadding=false`: com o padding, um título de 2 linhas começava a 2 dp do orbe (agora 9 dp).
  - **Hipótese descartada na prova:** *"Aplicar o conteúdo direto no listener."* Ao voltar do catálogo, o Leanback
    despacha a seleção de dentro da passada de layout das fileiras; o `requestLayout` do `setText` se perdia e o painel
    ficava vazio (log temporário: evento `MetaSystemInfo` chegando com o painel criado; `dumpsys activity top`: título
    com largura 0). Correção: conteúdo aplicado num `post`.
  - **Verificação:** build x86 e `ktlintMainSourceSetCheck` OK; `lintFreeBundleDebug` BUILD SUCCESSFUL. No `lemu_tv_api25`
    (`LITE` real e `FULL` forçado num build temporário, revertido): cabeçalhos abertos/fechados, as quatro fileiras,
    título longo, "Ver todos", catálogo inalterado (cartões em 135 dp, como na 3.2) e voltar, busca e voltar, menu de
    contexto, jogar e voltar. `logcat -b crash` vazio. 11 favoritos temporários no banco do AVD, desfeitos no fim.
  - **Não verificado:** TV box real, escala de fonte > 1,0, digitar na busca, `gfxinfo`.
  - **Feito depois:** commit `18cf57d` (3.4), com o aval do dono.
  - **Próximo passo:** decidir 3.5 (selo de salvo na TV) e 3.6 (fileira
    "Jogos salvos" na TV), que dependem de decisão própria; ou abrir o menu do jogo à mão (pendência antiga).
- **2026-10-15 (passagem de bastão: 3.5 e 3.6 em sessões paralelas).** O dono pediu as duas entregas, cada uma numa
  sessão nova. **O pedido vale como o aval da 3.6** exigido na Fase 3 ("só com o aval do dono").
  - **Estado de partida:** `version10` em `30b68e7`, árvore limpa, tudo enviado. Fase 3 visual (3.1–3.4) fechada.
  - **Ponteiros conferidos (só existência, por `grep`; abrir antes de escrever código):**
    `DownloadedRomDao.observeAllDownloadedKeys(): Flow<List<String>>` (`LIB/library/db/dao/DownloadedRomDao.kt:36`);
    `GameDao.observeInstalledGameGroups(` (`LIB/library/db/dao/GameDao.kt:398`); estados de salvamento do mobile em
    `M/shared/compose/ui/SaveStateBadge.kt` (`CoverSaveState`: NotSaved/Queued/Saving/Paused/Error/Saved, e
    `CoverSaveState.of(entry, isDownloaded)`). Cartões da TV: `GamePresenter` (+ `newTVCardView`, `showInfo`),
    capas por `TVCovers`; Início: `TVHomeFragment.recreateAdapter`/`update` + `TVHomeViewModel`.
  - **Colisão entre as duas sessões:** as duas mexem em `TVHomeViewModel`/`TVHomeFragment`, e a 3.6 usa o
    `GamePresenter` que a 3.5 altera. Cada sessão trabalha num **git worktree próprio** (`EnterWorktree`), faz
    `git pull --rebase` antes de commitar e resolve o conflito no seu lado. Há um só `lemu_tv_api25`: quem o encontrar
    em uso lança uma segunda instância com `-read-only` (`Start-Process`, ver `CLAUDE.md`) ou espera; nunca reinstalar
    por cima do APK da outra sessão no meio de um teste (memória "sessões paralelas no mesmo checkout").
  - **3.5 — selo de salvo na TV.** Escopo: nos cartões de jogo da TV (Início, catálogo, favoritos, busca), selo de
    "salvo" (✓ na cor de destaque, como o `Saved` do mobile), lido de `observeAllDownloadedKeys` exposto aos
    ViewModels da TV (leitura aditiva, sem query nova). Decidir na análise: só "salvo" ou também fila/salvando
    (exigiria o `SaveQueueManager`, que a TV não usa: na TV o salvamento é o `TVRomDownloadDialog`, síncrono);
    como o presenter recebe o conjunto de chaves sem reconstruir as grades paginadas; formato da chave
    (`systemId/fileName`? conferir no DAO). Variante `LITE` sem animação. Prova: salvar um jogo pelo diálogo e ver o
    selo aparecer no catálogo e na Início sem reabrir a tela; excluir a ROM pelo menu de contexto e o selo sumir.
  - **3.6 — fileira "Jogos salvos" na TV.** Funcionalidade nova: gera doc de feature próprio em
    `docs/funcionalidades/`. Escopo do plano: fileira na Início alimentada por `observeInstalledGameGroups`, seletor
    simples quando o grupo tem mais de uma versão salva, aviso de mídia inacessível. Reaproveitar as regras do mobile
    (`M/feature/installed/`, requisitos R1–R9 da seção "Download sob demanda e Jogos salvos", e o roteamento de toque
    `MainActivity.kt` de Jogos salvos) — abrir e listar o que a TV precisa repetir antes de desenhar. Ordem da fileira
    na Início, rótulo (`title_installed`, "Jogos salvos") e o painel de foco (3.4) mostrando o item: decidir e
    registrar. Prova: fileira aparece só com jogo salvo; jogar; versões; mídia inacessível (SD do AVD com
    `-sdcard` + `sm unmount`, ver `CLAUDE.md`).
  - **As duas seguem o "Como usar":** registro analítico neste doc antes do primeiro código, doc de entrega em
    `docs/funcionalidades/`, verificação da seção "Verificação" (build x86, ktlint, lint, AVD de TV), commit só com o
    aval do dono.
- **2026-10-15 (entrega 3.5 — registro analítico, antes do código).** Worktree `.claude/worktrees/selo-tv`, branch
  `redesenho-3.5-selo-tv` a partir de `cf6b120`.
  - **Símbolos abertos:**
    - `LIB/library/db/dao/DownloadedRomDao.kt::observeAllDownloadedKeys` — `SELECT systemId || '/' || fileName`;
      chave = `Game.downloadKey` (`LIB/library/db/entity/Game.kt:71`, `"$systemId/$fileName"`). Flow do Room: emite
      a cada escrita em `downloaded_roms`.
    - Quem escreve na tabela: `RomOnDemandManager.downloadRom` (`insert`, :228 — é o que o `TVRomDownloadDialog`
      chama) e `deleteRom` (`delete`, :338 — "Excluir ROM" do `GameContextMenuListener`, o menu de contexto da TV).
      Logo o fluxo basta para as duas provas (salvar → selo; excluir → some).
    - `TV/shared/GamePresenter.kt` — usado por `TVGamesFragment:50`, `TVFavoritesFragment:46`,
      `TVSearchFragment:99` (paginados) e `TVHomeFragment:220/238` (favoritos e recentes, `showInfo = false`;
      `recreateAdapter` cria presenters novos a cada mudança de fileiras). Os quatro fragments injetam
      `retrogradeDb`.
    - `TV/shared/TVCardFocusHighlight.kt` — a borda de foco usa o `foreground` do cartão: o selo **não** pode ir no
      foreground.
    - `TV/shared/TVCovers.loadCover` — põe o `mainImageView` VISIBLE/alpha 1 antes do Coil; o overlay da
      `ImageView` acompanha a capa.
    - `M/shared/compose/ui/SaveStateBadge.kt::CoverBadge` — referência visual do `Saved`: círculo 22 dp, margem 6 dp,
      canto superior direito, fundo `Accent` e ✓ `OnAccent` 14 dp. Na TV: `leanback_accent`/`leanback_on_accent`.
  - **Decisões:**
    1. **Só "salvo".** A TV não usa o `SaveQueueManager` (nenhuma referência em `TV/`): o salvamento é o
       `TVRomDownloadDialog`, modal e síncrono — não há fila a mostrar no cartão.
    2. **Sem rebuild das grades:** um objeto por fragment, `TVSavedGames` (`TV/shared/`), guarda o conjunto de
       chaves e os `ViewHolder`s ligados (`WeakHashMap`, só main thread). O `GamePresenter` recebe esse objeto
       (parâmetro opcional), registra o holder no bind e o tira no unbind; quando o conjunto muda, só os cartões
       ligados são repintados. Os presenters recriados da Início compartilham a mesma instância.
    3. **Fluxo coletado no fragment, não no ViewModel.** O handoff sugeria expor nos ViewModels, mas a 3.6 mexe no
       `TVHomeViewModel` em paralelo: `TVSavedGames.attach(fragment, retrogradeDb)` coleta
       `observeAllDownloadedKeys` em `launchOnState(STARTED)`, mesmo molde do `TVAmbientBackground.attach`. Recoleta
       a cada `onStart` (tabela pequena) — aceitável.
    4. **Desenho:** `Drawable` próprio (círculo + ✓ por `Path`, sem recurso vetorial) no `ViewOverlay` do
       `mainImageView` (API 18), bounds fixos a partir de `tv_game_card_width`. 24 dp na TV (o cartão tem 150 dp,
       contra ~110 do mobile), margem 6 dp. Sem animação → a variante `LITE` é a mesma coisa.
  - **Hipóteses descartadas:** `ImageCardView.badgeImage` — fica na área de info, que os cartões da Início não têm
    (`ImageOnly`); `foreground` — já é a borda de foco; expor no ViewModel — colisão com a 3.6 sem ganho.
  - **Tasks:** (a) `TV/shared/TVSavedGames.kt` (novo: holder + drawable + `attach`); (b) `GamePresenter` com
    `savedGames: TVSavedGames? = null`; (c) ligar nos 4 fragments. Prova: build x86_64, `ktlintCheck`, lint, e no
    `lemu_tv_api25`: salvar um jogo pelo diálogo → selo no catálogo e na Início sem reabrir; "Excluir ROM" → some.
- **2026-10-15 (entrega 3.5 implementada).** Conforme o registro acima, sem desvio. Arquivos: `TV/shared/TVSavedGames.kt`
  (novo), `TV/shared/GamePresenter.kt` (parâmetro `savedGames`) e os quatro fragments. Build x86, ktlint do código e
  lint verdes; no `lemu_tv_api25` (2ª instância `-read-only`): selo na Início e no catálogo, some ao excluir a ROM e
  volta ao salvar pelo diálogo, sem reabrir a tela; rolagem da grade sem selo em cartão reciclado. Doc da entrega:
  `docs/funcionalidades/2026-10-15-redesenho-selo-salvo-tv.md`. **Para a 3.6:** a fileira "Jogos salvos" decide se
  passa `savedGames` (todos os cartões teriam o selo). **Próximo passo:** aval do dono → `git pull --rebase` sobre o
  `version10` (a 3.6 mexe no `TVHomeFragment`) → commit → push.
- **2026-10-16 (entrega 3.5 commitada).** Commit `e50c567` no `version10`, com o aval do dono. Fase 3 aguarda só a 3.6.
- **2026-10-15 (entrega 3.6: fileira "Jogos salvos" na TV).** Branch `redesenho-3.6-jogos-salvos-tv`, worktree
  `.claude/worktrees/jogos-salvos-tv`, a partir de `cf6b120`. Análise, implementação, validação e capturas em
  `docs/funcionalidades/2026-10-15-tv-fileira-jogos-salvos.md`.
  - **Feito:** fileira "Jogos salvos" em primeiro na Início da TV (10 grupos mais recentes, mesma query e checagem de
    mídia do celular, coletada depois do primeiro quadro e quente a partir daí); regras de toque do celular (mídia
    inacessível avisa, mais de uma versão abre o seletor só com as salvas, uma versão joga); seletor
    `TVSavedVariantsDialog`; capa a 60% e texto no painel de foco ("N versões" / "Mídia inacessível") no lugar dos
    selos do celular. O `GamePresenter` (que a 3.5 altera) não foi tocado; a fileira tem presenter próprio.
  - **Colisão esperada com a 3.5:** `TVHomeViewModel` (construtor e `Factory` ganharam `DirectoriesManager`) e
    `TVHomeFragment` (`update` virou `render`, `recreateAdapter` ganhou `includeSavedGames`). Resolver no rebase.
  - **Verificação:** build x86 e `ktlintMainSourceSetCheck` OK; `ktlintKotlinScriptCheck` falha no
    `lemuroid-app/build.gradle.kts`, que a entrega não toca; `lintFreeBundleDebug` BUILD SUCCESSFUL. Roteiro no `lemu_tv_api25`
    (`-read-only`, `-sdcard`) sem crash.
  - **Segunda rodada (16/10):** pt-BR, mídia que volta (download no SD, `sm unmount`/`mount`) e custo da query
    (0,171 s / 60.872 linhas no x86) conferidos. Revisão achou e corrigiu: a fileira chega depois e em cima, e o adapter
    novo mantinha o índice selecionado (foco pulava de fileira); agora a fileira selecionada é mantida pelo id.
  - **Próximo passo:** commit com o aval do dono (`git pull --rebase` antes, por causa da 3.5); backlog de tela
    "Jogos salvos" na TV com "Ver todos".
- **2026-10-16 (3.6 rebaseada sobre a 3.5 e enviada).** Conflito no `TVHomeFragment`: as duas entregas criaram um
  campo `savedGames`; o da 3.5 (`TVSavedGames`) virou `savedBadges`. A fileira "Jogos salvos" usa `SavedGamePresenter`
  (sem selo). Build x86 e `ktlintMainSourceSetCheck` OK; no `lemu_tv_api25`: fileira "Jogos salvos" sem selo e
  Recentes com o ✓, sem crash. **Fase 3 fechada.** Resta a Fase 4 (opcional).
