# [FEATURE] Redesenho visual — Fase 1: fundação do design system

**Data:** 2026-10-13
**Branch:** version10
**Plano:** [`docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md`](../backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md)
(a seção "Especificação visual — direção A" é a referência dos valores; o registro completo da entrega,
com números, está no "Andamento").

## O que é

Base visual da direção A ("vitrine") para a UI mobile em Compose. Nenhuma tela muda de layout nesta fase;
mudam cores, fonte e a capa de jogo sem arte.

Arquivos em `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/shared/compose/ui/`:

| Arquivo | Conteúdo |
|---|---|
| `Color.kt` | `LemuroidColors` (fundo `#0E0F13`, superfície `#1A1C22`, barras `#14161B`, destaque âmbar `#F2B544`…) e o esquema M3 escuro derivado. Única cópia (as de `src/debug` e `src/release` foram apagadas). |
| `Type.kt` | Fonte Outfit 400/500/700 (`res/font/outfit_*.ttf`, SIL OFL 1.1, arquivos estáticos) em todos os slots do Material; títulos em negrito; `LocalLemuroidTypography` com `screenTitle`, `heroTitle`, `section`, `cardTitle` (12) e `support` (11). |
| `Shape.kt` | Raio 10 no cartão (`shapes.medium`), `PillShape`. |
| `Dimens.kt` | `LocalDimens`: em pé; deitado compacto (altura < 480 dp: capa 72×96, sistema 101×70); deitado grande / TV box (capa 150×200, sistema 208×144). Margens, espaço entre cartões, trilho de 80 dp, foco. |
| `SystemPalette.kt` | Cor de cada um dos 79 `MetaSystemID`, todas com contraste ≥ 4,5:1 contra branco. |
| `VisualQuality.kt` | `FULL` ou `LITE` (aparelho fraco pelo critério de `CoverUtils.isLowRamDevice`, ou animações desligadas no sistema), calculado uma vez por processo. |
| `GeneratedCover.kt` | Capa para jogo sem arte: tom escuro da cor do sistema + arte do console em mistura *screen* + título. Já usada por `LemuroidGameImage` quando não há URL ou o download falha. |
| `AmbientBackdrop.kt` | Fundo com a arte em 32 px esticada + degradê; em `LITE`, só degradê na cor do sistema. |
| `SaveStateBadge.kt` | `CoverSaveState` (não salvo, na fila, salvando N%, pausado, erro, salvo), `SaveStateOverlay` e `VariantsBadge`. |
| `SectionHeader.kt`, `effects/FocusHighlight.kt` | Título de fileira; contorno âmbar de foco para D-pad. |

`AppTheme` fornece tipografia, formas e os três `CompositionLocal` novos. Continua sem Coil, porque roda no
processo `:game`.

## O que ainda não usa a fundação

`AmbientBackdrop`, `SaveStateOverlay`, `VariantsBadge`, `SectionHeader`, `focusHighlight` e os tamanhos de
`Dimens` entram nas telas a partir da Fase 2. `LemuroidSmallGameImage` (lista e diálogos) continua com as
iniciais.

## Validação

- Build debug x86_64 e arm64, `lintFreeBundleDebug` e ktlint dos arquivos novos: OK.
- SM-A127M: Início e catálogo do ZX Spectrum com o tema novo; rolagem do ZX dentro do ruído da linha de
  base; um jogo de NES abre e roda.
- **Pendências:** o menu do jogo não foi aberto (adb não aciona o botão do pad no aparelho); no AVD de 2 GB
  (`LITE`) a rolagem do ZX passou de 1,55% para 38,1% de quadros lentos — investigar na entrega 2.2.

## Regras (também no `CLAUDE.md`, seção "Design system")

Sem `Modifier.blur`; efeito novo atrás de `LocalVisualQuality`; `AppTheme` sem Coil; chave de item em lista
paginada `"id_$id"`/`"idx_$it"`.
