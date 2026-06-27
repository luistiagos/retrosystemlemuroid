# [BUG] Catálogo de um sistema reordena sozinho ("dançando") — impossível selecionar ROM

**Data:** 2026-06-19
**Status:** CORRIGIDO (confirmado no dispositivo)
**Severidade:** ALTA — bloqueava o uso (não dava para abrir nenhum jogo)
**Branch:** version9

> ⚠️ Nota histórica: a primeira hipótese registrada para este bug (ordenação SQL não-total)
> estava **errada**. O documento abaixo reflete a causa-raiz **real**, confirmada por teste no
> dispositivo.

---

## Sintoma

Ao abrir um sistema qualquer, a listagem do catálogo **mudava de posição constantemente,
sozinha, sem interação** — os itens ficavam "dançando", tornando impossível selecionar uma ROM.

## Causa-raiz (real)

O `maxSize = pageSize * 6` adicionado ao `buildFlowPaging`
([PagingUtils.kt](../../../retrograde-util/src/main/java/com/swordfish/lemuroid/common/paging/PagingUtils.kt))
na rodada de performance. Em listas **maiores que `maxSize` (120 itens)** — qualquer catálogo
de sistema cheio — o Paging 3 fica **descartando e recarregando páginas**. Com placeholders
habilitados (padrão) e as **chaves de item dependentes de posição** que o catálogo usa
(`item carregado → "id_<id>"`, `placeholder → "idx_<index>"`), ao descartar uma página os itens
viram placeholder e ao recarregar voltam a item carregado → **a chave troca e o LazyColumn
reordena/anima os itens** repetidamente.

Por que **Favoritos** e **Home** não bugavam: têm poucos itens, nunca passam de 120, então o
Paging nunca descarta página.

### Hipótese inicial descartada
Suspeitou-se de `ORDER BY` não-total (empates resolvidos de forma não-determinística) exposto
pelo `maxSize`. Adicionou-se um desempate único `id` nas queries. **Não resolveu** — o problema
era o ciclo descarta/recarrega de página na camada de UI, não a ordenação.

## Diagnóstico (como foi confirmado)
- Captura de `logcat` durante a reprodução: **sem tempestade de escrita no banco** (nenhum log
  de loader/scan em loop) → descartada invalidação contínua da `PagingSource`.
- Correlação: só listas > 120 itens bugavam; Favoritos/Home (curtas) não → aponta `maxSize`.

## Correção

**Arquivo:** [PagingUtils.kt](../../../retrograde-util/src/main/java/com/swordfish/lemuroid/common/paging/PagingUtils.kt)

Removido o `maxSize` do `PagingConfig` (volta a `PagingConfig(pageSize)`). Comentário no código
explica o porquê para evitar regressão. Custo de memória praticamente nulo: entidades `Game`
são leves; o que pesa (bitmaps das capas) já é limitado pelo cache do Coil, independente do
Paging.

O desempate `id` nas queries do `GameDao` foi **mantido** — é boa prática de Room+Paging
(ordem total estável), embora não fosse a causa.

## Origem
Regressão introduzida pela própria otimização de performance (`maxSize`), que pretendia limitar
páginas retidas em RAM em dispositivos fracos. O ganho real era irrisório para este app, então
a remoção é a decisão correta.

## Validação
- `:lemuroid-app:assembleFreeBundleDebug` — **BUILD SUCCESSFUL**.
- Reinstalado no dispositivo `ZY32LMNN9B` — usuário confirmou que a lista parou de dançar.

## Lição
`PagingConfig.maxSize` + placeholders + chave de item dependente de posição = churn visual em
qualquer lista maior que `maxSize`. Se for usar `maxSize`, ou desabilitar placeholders ou usar
chave estável independente de posição.
