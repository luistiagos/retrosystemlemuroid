# [FEATURE] Redesenho visual — Moldura (entrega 2.6)

**Data:** 2026-10-15
**Status:** implementada e verificada no moto g86 5G, sem commit
**Branch:** version10
**Plano:** [`docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md`](../backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md) (Fase 2, item 2.6)

## O que mudou

A barra superior, a barra inferior e as três folhas inferiores (menu de contexto do jogo, versões do
jogo e fila de salvamento) passam ao visual da direção A. Nenhum fluxo mudou: os lambdas da
`MainActivity`, as ações do menu, o roteamento das versões e pausar/retomar/cancelar da fila são os
mesmos de antes.

- **Barra superior:** fundo `Bars` com borda inferior `Border`, título em Outfit (`screenTitle`), ações
  em `TextSupport` com contorno âmbar no foco do D-pad. O ícone da fila virou um anel âmbar em volta da
  seta (o mesmo do pé do trilho). As barras de progresso da operação e da fila são âmbar sobre `Border`.
  Busca em pílula `Surface`, cursor âmbar.
- **Catálogo:** a barra não diz mais "Jogos". Tem só o voltar e as ações; o nome do sistema já está no
  cabeçalho logo abaixo (especificação: "voltar e, abaixo, o nome do sistema em destaque").
- **Barra inferior:** própria (não mais `NavigationBar`), 64 dp + recuo da barra de navegação. As 5 abas
  na ordem de `MainNavigationRoutes`, sem rótulo como antes; a selecionada fica na pílula âmbar. No foco
  do D-pad a pílula ganha o contorno âmbar (a camada de estado do `NavigationBarItem` era quase invisível).
- **Folhas:** `LemuroidBottomSheet` (novo, `shared/compose/ui/`): fundo `Surface`, cantos de 16 dp, alça
  discreta, e `BottomSheetHeader` com título, linha de apoio e ação opcional.
  - **Menu de contexto:** capa 3:4 (capa gerada quando não há arte) + título + chip do sistema; ações
    com ícone em `TextSupport`, foco em pílula âmbar; as duas de exclusão em cor de erro.
  - **Versões:** capa do representante em 3:4 (ou a gerada), nome do arquivo, e o estado de salvamento
    da versão (✓ salvo; com a fila, também na fila/salvando/pausado/erro). Parâmetro novo opcional
    `saveQueueEntries`.
  - **Fila:** capa 48×64 sobre o tom escuro do sistema (fica no lugar da capa sem rede ou sem arte),
    estado em âmbar quando salvando e em cor de erro no erro, barra de progresso arredondada (cinza
    quando pausado), botões com contorno no foco, vazio com ícone.

### Pendências da 2.3 resolvidas aqui

1. **Recuo esquerdo dobrado da barra superior ao lado do trilho.** A `TopAppBar` usava os recuos
   padrão (`safeDrawing` Horizontal + Top) mesmo com o trilho já ocupando o lado da barra de navegação /
   recorte. Agora recebe `Top + End` quando há trilho, como o conteúdo do `Scaffold`.
2. **Foco volta ao trilho depois do catálogo.** A Início é recomposta do zero ao voltar; o foco caía no
   primeiro focável. O bloco do sistema aberto pelo D-pad guarda o nome (`rememberSaveable`) e pede o
   foco de volta ao ser composto de novo. Só quando o clique veio do teclado/D-pad (`InputMode.Keyboard`):
   no toque, nada acende.
3. **"Systems" repetido no topo deitado.** Com um sistema em foco, o cabeçalho não imprime mais o rótulo
   "Sistemas" (já é o título da fileira logo abaixo); a linha vazia mantém a altura para as fileiras não
   pularem.

## O que não mudou

`MainNavigationRoutes`, `navigateToTab`, o trilho lateral (2.3), os lambdas de `MainGameContextActions`,
`GameVariantsModal` e `SaveQueueModal` na `MainActivity`, as chaves das listas (`it.id`, `fileName`),
`statusLabel` (só reformatado para o ktlint), `SaveQueueViewModel`/`SaveQueueManager`. Banco, DAO,
manifest e dependências: nada.

## Arquivos

| Arquivo | Mudança |
|---|---|
| `M/feature/main/MainTopBar.kt` | Visual; `windowInsets` opcional; sem título no `SYSTEM_GAMES` |
| `M/feature/main/MainNavigationBar.kt` | Barra inferior própria (`BarItem`) |
| `M/feature/main/MainGameContextActions.kt` | Folha, cabeçalho e ações novos |
| `M/feature/main/GameVariantsModal.kt` | Folha e linhas novas; `saveQueueEntries` opcional |
| `M/feature/main/SaveQueueModal.kt` | Folha e linhas novas |
| `M/shared/compose/ui/LemuroidBottomSheet.kt` | Novo: `LemuroidBottomSheet`, `BottomSheetHeader`, `SheetMargin` |
| `M/feature/main/MainActivity.kt` | Recuos da barra com o trilho; fila para as versões |
| `M/feature/home/HomeLayouts.kt` | Rótulo do sistema; foco de volta ao bloco |

## Verificação

- `assembleFreeBundleDebug -PdevAbi=x86_64` e arm64, `ktlintMainSourceSetCheck` e `lintFreeBundleDebug`:
  **BUILD SUCCESSFUL**. O `statusLabel` da fila mudou de linha e saiu do baseline do ktlint: foi
  **corrigido** (formatação), não congelado.
- **moto g86 5G** (Android 16, debug arm64 instalado por cima, dados mantidos; o único aparelho
  conectado), barra de navegação de 3 botões:
  - **Deitado com a barra de navegação à esquerda** (o caso da pendência 1): título logo depois do
    trilho, sem recuo a mais.
  - D-pad: trilho → bloco do Amiga CD32 (cabeçalho sem "Sistemas") → OK abre o catálogo (barra só com
    voltar) → voltar: **o foco está no bloco do Amiga CD32**.
  - Em pé: barra inferior com a pílula; contorno âmbar no botão Ajuda em modo D-pad.
  - Toque longo no destaque → menu de contexto novo; "Excluir ROM" ausente (jogo não salvo), como antes.
  - Destaque → "Salvar ROM?" → Salvar → folha da fila: anel no topo, barra de progresso, "Salvando… 0%";
    pausar (selo de pausa no destaque, faixa "Pausado", barra cinza) → retomar ("Salvando… 14%") →
    cancelar (folha vazia com ícone; anel do topo, selo e faixa somem).
  - Busca "tetris" → Tetris (Amiga) com selo de variantes → folha de versões com 2 linhas e capa gerada.
  - `logcat -b crash` vazio.
- **Não testado:** AVDs (`lemu_api21_1gb`, `lemu_api25_2gb`), TV box, TalkBack, estado de erro na fila,
  "limpar erros", versão salva com ✓ na folha de versões, medição de quadros (as telas não mudaram;
  só a moldura).

Capturas em `docs/funcionalidades/img/2026-10-15-redesenho-moldura-*.jpg`: `inicio-em-pe`,
`deitado-sistema-focado`, `deitado-catalogo`, `deitado-volta-do-catalogo`, `menu-contexto`,
`fila-salvando`, `fila-pausado`, `busca`, `variantes`.

## Limitações conhecidas

- Ao **entrar** no catálogo pelo D-pad, o foco cai no trilho (aba Início), não no primeiro cartão. Já
  acontecia antes; é do catálogo (2.2), não da moldura.
- No foco, o `combinedClickable` dos cartões ainda pinta a indicação padrão do Material além do contorno
  (pendência da 2.2, inalterada).
- A barra inferior não tem rótulo nas abas, como antes (decisão "rótulos de hoje" da especificação).
