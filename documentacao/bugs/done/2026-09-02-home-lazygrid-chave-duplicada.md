# [BUG] Home crasha com `Key "<id>" was already used` — as três seções compartilham o namespace de chave do mesmo LazyVerticalGrid

**Data:** 2026-09-02
**Status:** Resolvido ✅
**Severidade:** Alta (crash na tela inicial; é o crash Java nº 1 em produção)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/main` (`SourceFile::I0.a.a`)
**Errors (serviço):** 27 ocorrências — 3258, 3256, 2972, 2937, 2932, 2921, 2911, 2848, 2411,
2156, 1897, 1690, 1488, 1444, 1424, 1412, 1358, 1356, 1344, 1268 (+7). Eco do mesmo evento em
`retrogamesystem/crash` (ver "Ruído associado").
**Aparelhos:** transversal — Samsung SM-S918B/S731B, Android 13/14/15/16, app 1.17.6 → 1.17.12
**Janela observada:** 2026-08-16 04:08 → 2026-08-30 20:34 (persiste em todas as versões do período)

---

## Sintoma

```
java.lang.IllegalArgumentException: Key "102564" was already used. If you are using
LazyColumn/Row please make sure you provide a unique key for each item.
	at I0.a.a(SourceFile:3)
	at J0.C.J(SourceFile:148)
	...
	at androidx.compose.ui.platform.U$d.doFrame(SourceFile:17)
	at android.view.Choreographer$CallbackRecord.run(Choreographer.java:1959)
```

A chave é um **número puro** (`102564`, `87556`) — o `Game.id`. O crash acontece no
`doFrame`, ou seja, na primeira composição da tela; para o usuário o app fecha ao abrir a
Home.

## Causa-raiz

[HomeScreen.kt:205](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/home/HomeScreen.kt#L205)
abre **um único** `LazyVerticalGrid`. Dentro dele, `homeGridSection` é invocada **três vezes** —
Recentes, Favoritos e Descubra
([HomeScreen.kt:256-276](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/home/HomeScreen.kt#L256-L276)) —
e cada uma emite seus itens com:

```kotlin
// HomeScreen.kt:297
items(games, key = { it.id }) { game -> ... }
```

`key` num `LazyGrid` é **global ao grid inteiro**, não por seção. As três listas vêm de
queries independentes em
[HomeViewModel.kt:284-286](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/home/HomeViewModel.kt#L284-L286)
(`favoritesGames`, `recentGames`, `discoveryGames`) e **nada garante disjunção** — em
particular Descubra sorteia do pool de populares sem excluir os outros dois. Basta um jogo
popular já jogado para o mesmo `Game.id` ser emitido duas vezes no mesmo grid, e o Compose
lançar. (Ver "Como reproduzir" para o caminho exato.)

Repare no contraste com as outras telas, que **já** namespaceiam a chave por serem paginadas:

| Tela | Chave |
|---|---|
| [GamesScreen.kt:93](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/games/GamesScreen.kt#L93) | `"id_$id"` / `"idx_$it"` |
| [FavoritesScreen.kt:38](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/favorites/FavoritesScreen.kt#L38) | idem |
| [SearchScreen.kt:113](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/search/SearchScreen.kt#L113) | idem |
| **HomeScreen.kt:297** | **`it.id` cru — três vezes no mesmo grid** |

Só a Home repete o namespace, e só a Home crasha.

## Como reproduzir

O caminho de colisão real é **Descubra × Recentes** ou **Descubra × Favoritos**:

1. Jogar um jogo popular com capa (entra em Recentes).
2. Reabrir a Home até que o mesmo jogo seja sorteado em Descubra.

`discoveryGames` sorteia do pool de populares **sem excluir** recentes/favoritos
([HomeViewModel.kt:346](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/home/HomeViewModel.kt#L346)),
então basta o jogo estar nos dois lugares.

> **Correção à hipótese original deste relatório:** "jogar e favoritar" **não**
> reproduz. Recentes vem de `selectFirstUnfavoriteRecents`, cujo SQL é
> `... WHERE lastPlayedAt IS NOT NULL AND isFavorite = 0 ...`
> ([GameDao.kt:80](../../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/GameDao.kt#L80)) —
> Recentes e Favoritos são disjuntos por construção. Quem não exclui ninguém é Descubra.
> Isso não muda a correção: a chave continua sendo o defeito.

Dentro de uma mesma seção não há duplicata: as três listas vêm de queries por `id`
(ou, no caso de Descubra, de um representante por `systemId`).

## Correção aplicada

A chave dos itens passou a ser namespaceada por seção, em
[HomeScreen.kt:301](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/home/HomeScreen.kt#L301):

```kotlin
private fun LazyGridScope.homeGridSection(
    sectionKey: String,   // novo — "recent" / "favorites" / "discover"
    title: String,
    ...
) {
    item(span = { GridItemSpan(maxLineSpan) }, key = "header_$sectionKey") { ... }

    items(games, key = { "$sectionKey/${it.id}" }) { game -> ... }
}
```

Duas decisões que divergem da proposta original do relatório:

- **A chave não é o `title`.** O relatório propunha `"${title}/${it.id}"`, mas `title`
  vem de `stringResource(...)` — é texto **localizado**. Chave de `LazyLayout` é
  identidade: precisa ser estável entre recomposições, entre trocas de idioma e entre
  processos (é o que o Compose serializa para restaurar `rememberSaveable` de item).
  Por isso foi introduzido um `sectionKey` literal e independente de locale, passado nos
  três call-sites (`"recent"`, `"favorites"`, `"discover"`).
- **O header também ganhou chave** (`"header_$sectionKey"`), como já fazia
  `GamesScreen` com `key = "sort_header"`.

> Não foi resolvido "deduplicando as listas no ViewModel": o mesmo jogo **pode**
> legitimamente aparecer em Descubra e em Recentes; o defeito é a chave, não o conteúdo.

## Validação

Validado no aparelho (**moto g86 5G, Android 16 / SDK 36, arm64-v8a**), com teste
diferencial contra um build de controle sem a correção.

**Como a colisão foi forçada de modo determinístico.** Descubra agrupa o pool (top-300 por
popularidade, com capa) por `systemId`, pega os top-10 de cada sistema e sorteia 1. Recentes
mostra os 10 `lastPlayedAt` mais recentes **não-favoritos**. Logo, marcando como recentes
exatamente os 10 candidatos de um sistema, qualquer sorteio desse sistema cai também em
Recentes. Escolhido `psx` (66 entradas no pool); conferido que não há empate de popularidade
na fronteira rank 10/11 (569 vs 557), o que torna o conjunto inequívoco:

    Final Fantasy IX ×4 (100350-100353), Castlevania: SOTN (99943), Diablo (100109),
    Resident Evil 2 ×3 (101341-101343), Silent Hill (101467)

O pool tinha 25 sistemas, então `psx` sobrevive ao `.take(CAROUSEL_MAX_ITEMS)` em ~40% dos
launches.

**Resultado.**

| Build | Ciclos | Crash `was already used` |
|---|---|---|
| **Sem** a correção (controle) | 12 | **3** |
| **Com** a correção | 20 | **0** |

Crash do controle, capturado no aparelho — mesma exceção do relatório de produção, agora
desofuscada, e com um dos ids semeados:

```
FATAL EXCEPTION: main
java.lang.IllegalArgumentException: Key "101342" was already used. If you are using
LazyColumn/Row please make sure you provide a unique key for each item.
  at androidx.compose.ui.layout.LayoutNodeSubcompositionsState.subcompose(SubcomposeLayout.kt:1055)
  at androidx.compose.foundation.lazy.layout.LazyLayoutMeasureScopeImpl.measure(LazyLayoutMeasureScope.kt:124)
  at androidx.compose.foundation.lazy.grid.LazyGridMeasuredItemProvider.getAndMeasure(...)
  at androidx.compose.foundation.lazy.grid.LazyGridMeasureKt.measureLazyGrid(LazyGridMeasure.kt:215)
```

**Correção ao "Sintoma" deste relatório: o crash NÃO é na primeira composição.** A primeira
tentativa de validação abriu a Home 15 vezes no build **sem** a correção e não crashou
nenhuma vez — o teste era inválido e por pouco daria um falso "corrigido". A exceção nasce em
`LayoutNodeSubcompositionsState.subcompose`, ou seja, exige que os dois itens de mesma chave
estejam **compostos ao mesmo tempo**. Abrindo na posição 0 só Recentes está na janela de
composição; Descubra está fora. O crash só aparece ao **rolar** até a última linha de
Recentes coexistir com a primeira do Descubra — no controle, invariavelmente no 3º swipe.
O `doFrame` da stack de produção é o frame de *measure* do scroll, não o primeiro frame.

Consequência prática para reproduzir em campo: não basta abrir a Home, é preciso rolar.

## Lição

`key` em `LazyColumn`/`LazyRow`/`LazyVerticalGrid` é **global ao layout inteiro**, não por
seção. Toda função de extensão de `LazyGridScope`/`LazyListScope` que emite itens e é
chamada mais de uma vez no mesmo container precisa receber um discriminador de seção e
compor a chave com ele. Um `key = { it.id }` correto isoladamente vira crash só porque a
função foi chamada duas vezes.

Corolário: chave de item nunca sai de `stringResource` nem de qualquer coisa localizável.

## Ruído associado

O grupo `retrogamesystem/crash` ("Java crash: crash", 59 ocorrências, log = a string `crash`)
é em boa parte o **eco** deste mesmo crash: os IDs são adjacentes aos deste bug
(3259/3258, 2973/2972, 2938/2937, 2933/2932, 2922/2921, 2912/2911, 2849/2848, 2412/2411) —
o `UncaughtExceptionHandler` reporta ao vivo e, na sessão seguinte,
`CrashTelemetry.reportPastExits` reporta de novo pelo `ApplicationExitInfo`, agora sem stack.
Ver [[2026-09-02-telemetria-lowmemory-e-eco-de-crash]].

## Próximos passos

- [x] Aplicar a chave namespaceada e recompilar.
- [x] Validar no aparelho: jogo em Recentes que também caia em Descubra (moto g86 5G, Android 16).
- [ ] Conferir se o eco em `retrogamesystem/crash` some junto (confirma a correlação).
