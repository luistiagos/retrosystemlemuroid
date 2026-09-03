# [BUG] Grid da TV crasha com `Invalid item position -1(-1)` no `VerticalGridView` do leanback

**Data:** 2026-09-02
**Status:** 🟢 Resolvido — animações preditivas desligadas nos grids leanback alimentados por dados vivos
**Severidade:** Baixa-Média (crash em tela de catálogo/favoritos da TV; 1 ocorrência registrada)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/main`
(`SourceFile::androidx.recyclerview.widget.RecyclerView$v.I`)
**Errors (serviço):** 2187
**Aparelho:** Google Chromecast, **armeabi-v7a**, Android 14, app 1.17.10
**Observado em:** 2026-08-25 00:30

---

## Sintoma

```
java.lang.IndexOutOfBoundsException: Invalid item position -1(-1). Item count:100
androidx.leanback.widget.VerticalGridView{… #7f0a007c app:id/browse_grid},
adapter:androidx.leanback.widget.v0$b@…, layout:androidx.leanback.widget.s@…,
context:com.swordfish.lemuroid.app.tv.main.MainTVActivity@…
	at androidx.recyclerview.widget.RecyclerView$v.I(SourceFile:528)     <- Recycler.tryGetViewHolderForPositionByDeadline
	at androidx.leanback.widget.s.N2(SourceFile:3)                       <- GridLayoutManager
	at androidx.leanback.widget.o0.P(SourceFile:70)
	at androidx.leanback.widget.p0.Q(SourceFile:254)
	at androidx.leanback.widget.s.s3(SourceFile:25)
	at androidx.leanback.widget.s.Z0(SourceFile:204)                     <- onLayoutChildren
	at androidx.recyclerview.widget.RecyclerView$a.run(SourceFile:32)
	at android.view.Choreographer$CallbackRecord.run(Choreographer.java:1339)
```

### Correção de diagnóstico: não é a Home

A leitura inicial assumiu que `browse_grid` fosse o grid do `BrowseSupportFragment` (a Home da
TV). **Não é.** No AAR do leanback 1.1.0-rc01 o id `browse_grid` aparece só em
`lb_vertical_grid.xml` / `lb_vertical_grid_fragment.xml` — o layout do
`VerticalGridSupportFragment` (`VerticalGridPresenter.createGridViewHolder` infla
`R.layout.lb_vertical_grid` e pega `R.id.browse_grid`). A Home usa `container_list`; as linhas
dela usam `row_content`.

As duas telas que são `VerticalGridSupportFragment` neste app são
[TVGamesFragment.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/games/TVGamesFragment.kt)
(catálogo por sistema) e
[TVFavoritesFragment.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/favorites/TVFavoritesFragment.kt).
Ambas vivem dentro da `MainTVActivity` — daí o `context:` do stack apontar para ela. `Item
count:100` é a contagem da query daquela tela, não da Home.

## Causa-raiz (confirmada na fonte do leanback)

`GridLayoutManager` traduz índice de grid → posição de adapter subtraindo um delta
(`GridLayoutManager.java:1633-1646`, leanback 1.1.0-rc01):

```java
public int getMinIndex()  { return mPositionDeltaInPreLayout; }
public int getCount()     { return mState.getItemCount() + mPositionDeltaInPreLayout; }
public int createItem(int index, …) {
    View v = getViewForPosition(index - mPositionDeltaInPreLayout);   // ← aqui nasce o -1
```

`mPositionDeltaInPreLayout` é zerado em `saveContext`/`leaveContext` e só é calculado por
`updatePositionDeltaInPreLayout()`, chamado nos **dois** call-sites sob `state.isPreLayout()`
(`GridLayoutManager.java:1505` e `:2213`). O comentário do próprio método diz o que ele é:

```java
// in prelayout, first child's getViewPosition can be smaller than old adapter position
// if there were items removed before first visible index.
mPositionDeltaInPreLayout = mGrid.getFirstVisibleIndex() - lp.getViewLayoutPosition();
```

Ou seja: **o delta é exatamente o número de itens removidos acima da viewport**, e só existe
durante o pre-layout.

O defeito está no laço de prepend do `StaggeredGridDefault.prependVisibleItemsWithoutCache`,
que para em `itemIndex < 0` em vez de parar em `mProvider.getMinIndex()`:

```java
if (itemIndex < 0 || (!oneColumnMode && checkPrependOverLimit(toLimit))) {
    return filledOne;
}
…
int size = prependVisibleItemToRow(itemIndex--, rowIndex, location);
```

Com delta = 1, descer até o índice de grid 0 pede ao Recycler a posição de adapter **-1** →
`IndexOutOfBoundsException: Invalid item position -1(-1). Item count:100`. Nenhum outro caminho
produz posição negativa: append e prepend-com-cache respeitam `getMinIndex()`/`getCount()`.

### Por que dispara aqui

O pre-layout só roda quando existe `ItemAnimator` (recyclerview 1.2.1,
`RecyclerView.java:3947-3957`):

```java
mState.mRunSimpleAnimations = mFirstLayoutComplete && mItemAnimator != null && …;
mState.mRunPredictiveAnimations = mState.mRunSimpleAnimations && animationTypeSupported && …;
```

e `BaseGridView` instala o animator padrão no construtor (`BaseGridView.java:275` faz cast de
`getItemAnimator()` para `SimpleItemAnimator` justamente para desligar só a *change animation*).

O gatilho do lado do app é o `PagingDataAdapter` do leanback alimentado por `PagingSource` do
Room. As queries dessas telas reordenam sozinhas quando o banco muda:

```sql
-- GameDao.selectBySystem / selectBySystems
ORDER BY (downloaded_roms.fileName IS NOT NULL) DESC, games.title ASC, games.id DESC
```

Um download que termina escreve em `downloaded_roms`, invalida a `PagingSource` e **move a
linha daquele jogo para o topo** — remoção acima da viewport, que é a condição exata do delta.
Em Favoritos vale o mesmo: desfavoritar remove a linha da lista. O `RecyclerView$a.run` do
stack é o `mUpdateChildViewsRunnable`, isto é, um layout disparado por notificação do adapter
(e não por `onLayout`), o que bate com update de dados chegando com a tela já montada.

### Suspeita descartada: desalinhamento de versões androidx

A suspeita registrada na abertura (leanback × recyclerview desalinhados, por causa dos
`NoSuchFieldError: ACTION_SCROLL_IN_DIRECTION`) **não se sustenta para este crash**:

- Versões resolvidas em `freeBundleDebugRuntimeClasspath` (`:lemuroid-app:dependencies`):
  `androidx.leanback:leanback:1.1.0-rc01`, `androidx.recyclerview:recyclerview:1.2.1` e
  `androidx.core:core:1.13.1` (o `1.8.0` do `deps.kt` é elevado por constraint transitivo).
- `ACTION_SCROLL_IN_DIRECTION` não é referenciado por nenhuma classe do AAR do leanback
  1.1.0-rc01 nem pelo fonte do recyclerview 1.2.1 — ambos são anteriores ao campo, que só
  aparece em `androidx.core` 1.12+. E o `core` empacotado é o 1.13.1, que **tem** o campo. Aquele
  `NoSuchFieldError` vem de outro artefato/outra build (as letras da ofuscação não são
  comparáveis entre versões do app) e segue como item próprio.

O bug do prepend, ao contrário, está no leanback empacotado hoje e não foi corrigido upstream.

## Como reproduzir

Não reproduzido em bancada. Receita provável: abrir o catálogo de um sistema na TV (ou
Favoritos), rolar até sair da primeira linha e deixar um download terminar — o jogo baixado
sobe para o topo enquanto o grid está montado.

## Correção

Novo [TVGridPresenters.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/shared/TVGridPresenters.kt)
com dois presenters que desligam o `ItemAnimator` do grid — sem animator o RecyclerView nunca
roda o pre-layout, `mPositionDeltaInPreLayout` fica sempre 0 e `createItem` volta a mapear 1:1
para posições válidas:

```kotlin
class TVVerticalGridPresenter : VerticalGridPresenter() {
    override fun initializeGridViewHolder(vh: VerticalGridPresenter.ViewHolder) {
        super.initializeGridViewHolder(vh)
        vh.gridView.setAnimateChildLayout(false)
    }
}

class TVListRowPresenter : ListRowPresenter() { /* mesma coisa em getGridView() */ }
```

Aplicado nos quatro grids leanback alimentados por dados vivos:

| Tela | Antes | Depois |
|------|-------|--------|
| `TVGamesFragment` | `VerticalGridPresenter()` | `TVVerticalGridPresenter()` |
| `TVFavoritesFragment` | `VerticalGridPresenter()` | `TVVerticalGridPresenter()` |
| `TVHomeFragment` | `ArrayObjectAdapter(ListRowPresenter())` | `ArrayObjectAdapter(TVListRowPresenter())` |
| `TVSearchFragment` | `ArrayObjectAdapter(ListRowPresenter())` | `ArrayObjectAdapter(TVListRowPresenter())` |

As duas primeiras são a tela do crash. Home e Busca entram porque sofrem a mesma churn
estrutural pelo mesmo mecanismo (`setItems` com `DiffCallback` a cada mudança no banco; nova
`submitData` a cada tecla digitada na busca) e o `GridLayoutManager` do laço defeituoso é o
mesmo do `HorizontalGridView` das linhas.

O que se perde: a animação de entrada/saída de item nesses grids — que aqui só apareceria em
reordenação vinda de escrita no banco. O zoom de foco **não** é afetado: vem do
`FocusHighlightHelper`, não do `ItemAnimator`.

## Validação

- [x] `:lemuroid-app:compileFreeBundleDebugKotlin` — build concluído com sucesso.
- [x] Fonte do leanback 1.1.0-rc01 e do recyclerview 1.2.1 lidos (sources jar do Google Maven):
      confirmados os call-sites de `updatePositionDeltaInPreLayout()` sob `isPreLayout()`, o
      `index - mPositionDeltaInPreLayout` em `createItem` e a dependência do pre-layout em
      `mItemAnimator != null`.
- [x] `browse_grid` localizado só em `lb_vertical_grid*.xml` dentro do AAR — confirma que a tela
      do crash é `VerticalGridSupportFragment`, não a Home.
- [ ] Validação manual em Chromecast (deixar um download terminar com o grid aberto) requer o
      aparelho.

## Lição

Id de view em stack de crash é evidência dura e barata de conferir: `browse_grid` estava no AAR
e teria evitado a suspeita sobre a tela errada. E adapter paginado do Room em grid leanback é
combinação instável por natureza — a query reordena sozinha a cada escrita no banco, então
qualquer caminho do `GridLayoutManager` que dependa de animação preditiva é bomba-relógio; em
TV, animação de item não vale o risco.
