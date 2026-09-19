# [BUG] Menu de contexto não aparece ao dar long press nas variantes de um jogo no catálogo

**Data:** 2026-09-05
**Status:** Resolvido ✅
**Severidade:** Média (funcionalidade inacessível para jogos com múltiplas variantes)
**Branch:** version9

---

## Sintoma

Ao navegar pelo catálogo do Lemuroid, quando um jogo possui múltiplas versões/fontes (ex: USA, EUR, JP):
1. O usuário clica no card do jogo no catálogo, abrindo o modal de seleção de variantes (`GameVariantsModal`).
2. Ao realizar um toque longo (long press) em qualquer uma das versões listadas, nada acontecia.
3. As opções de menu de contexto (como "Excluir rom baixada", "Excluir do catálogo", "Continuar", "Reiniciar", "Adicionar aos favoritos", "Fixar no launcher") só estavam acessíveis diretamente em jogos de versão única (ao dar long press no card do catálogo).

## Causa-raiz

1. Em `GameVariantsModal.kt`, o componente `VariantRow` utilizava `Modifier.combinedClickable(onClick = onClick)` sem passar o parâmetro `onLongClick`.
2. A função composable `GameVariantsModal` não expunha callback `onVariantLongClick`.
3. Em `MainActivity.kt`, a chamada de `GameVariantsModal` não definia ação de long click para encaminhar a variante selecionada ao `selectedGameState` (que gerencia a exibição do `MainGameContextActions`).

## Correção aplicada

1. Adicionado o parâmetro `onVariantLongClick: (Game) -> Unit` a `GameVariantsModal` e `VariantRow`.
2. Repassado `onLongClick` para o `Modifier.combinedClickable(onClick, onLongClick)` de cada `VariantRow`.
3. Na `MainActivity.kt`, configurado `onVariantLongClick = { variant -> pendingVariantsGame.value = null; selectedGameState.value = variant }`.
4. Ao dar long press em qualquer uma das variantes listadas no modal, o modal de variantes é fechado e o menu de contexto inferior `MainGameContextActions` é aberto apontando exatamente para o objeto `variant` selecionado, permitindo executar todas as ações de contexto (como "Excluir rom baixada" daquela versão específica ou "Excluir do catálogo").

## Validação

- Compilado com sucesso via `.\gradlew.bat :lemuroid-app:compileFreeDynamicDebugKotlin` (BUILD SUCCESSFUL).
- Verificado que o encadeamento de estado em Compose fecha o modal de variantes e abre o `MainGameContextActions` para a variante clicada.

## Lição

Ao utilizar `Modifier.combinedClickable` em listas de seleção (como modais de variantes), é essencial repassar tanto `onClick` quanto `onLongClick` para que todas as ações de contexto disponíveis para o item possam ser acionadas a partir do próprio modal.
