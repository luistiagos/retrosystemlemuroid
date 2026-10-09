# [BUG] Escolher variante ainda não baixada mostra "Mídia inacessível" em vez de baixar (relatado em 3DO)

**Data:** 2026-10-09
**Status:** 🟡 Corrigido no código — falta prova no aparelho
**Severidade:** Alta — nenhum jogo com mais de uma variante (região/revisão) pode ser baixado pelo modal de variantes
**Branch:** version9
**Origem:** relato do dono: 3DO, Super Street Fighter II e GEX dão "Mídia inacessível" ao escolher

---

## Sintoma

Ao tocar num jogo 3DO com várias regiões (ex.: *Super Street Fighter II Turbo*, 5 variantes;
*Gex*, 2 variantes), abre o modal de variantes; ao escolher uma variante não baixada aparece o
toast "Mídia inacessível" e nada acontece. O 3DO é só onde foi visto: o defeito vale para
qualquer sistema, desde que o título tenha mais de uma variante.

## Análise (símbolos abertos)

- `MainActivity.kt::onGameClick` (~l.347) — título em `titlesWithVariants` abre o modal
  (`pendingVariantsGame`); senão vai direto para `isGamePlaceholder` → jogar ou baixar.
- `MainActivity.kt::GameVariantsModal.onVariantSelected` (~l.888) — chamava
  `storageAvailabilityMonitor.isGameAvailable(variant)` **antes** de `isGamePlaceholder(variant)`;
  se falso, toast `installed_media_unavailable` e `return`.
- `StorageAvailabilityMonitor.kt::isGameAvailable` — para `file://` devolve
  `file.exists() && file.length() > 0`. Placeholder de streaming é arquivo de **0 byte**, logo
  devolve `false` para toda variante não baixada.
- `MainActivity.kt::isGamePlaceholder` (~l.1078) — `file://` com `length() == 0` = placeholder.
  Os dois testes são complementares: todo placeholder é "indisponível" para o monitor.
- Manifest: `grep -E "^3do/(Super Street Fighter|Gex)" catalog_manifest.txt` → Gex 2 linhas,
  SSF2 Turbo 5 linhas (mesmo título → variantes). CHD é arquivo único, o formato não é a causa.
- Segundo relato do dono (mesmo dia): Amstrad CPC, *Indiana Jones*. `grep -i indiana
  lemuroid-app/src/main/assets/catalog_manifest.txt | grep ^amstradcpc` → Fate of Atlantis 2,
  Last Crusade 4, Temple of Doom 3 linhas com o mesmo título: os três abrem o modal. Mesma causa.

## Causa-raiz

Regressão do commit `78b7ff2` (2026-10-08, aba de instalados), que inseriu o check de
disponibilidade no `onVariantSelected` sem considerar o placeholder. O check foi pensado para o
modal aberto pela aba de instalados (variante instalada num cartão SD removido), mas o mesmo
modal serve Home/Sistemas/Busca, onde a variante normalmente ainda não foi baixada.

## Hipóteses descartadas

- Core Opera/BIOS ausente: o toast sai antes de qualquer launch (`return@launch`).
- Formato CHD / caminho: `isGameAvailable` só olha tamanho do arquivo; o placeholder tem 0 byte
  em qualquer sistema.

## Correção

`onVariantSelected`: testar `isGamePlaceholder` primeiro → placeholder vai para o download
(`pendingDownloadGame`); só um arquivo não-placeholder passa por `isGameAvailable` (que ainda
cobre `content://` inacessível) antes de `gameInteractor.onGamePlay`.

Os outros dois usos (`InstalledGamesScreen.onGameClick` e busca com escopo "instalados")
operam só sobre variantes instaladas e ficam como estão.

## Validação

- [x] Build `:lemuroid-app:assembleFreeBundleDebug` verde (2026-10-09; JDK `D:\DevCaches\jdk-17` passado por `-Dorg.gradle.java.home`, porque o do `gradle.properties` nao existe nesta maquina)
- [ ] No aparelho: 3DO → Gex → escolher variante não baixada → abre o diálogo de download
- [ ] Controle positivo: variante já baixada joga direto; título sem variante segue igual

## Lição

Um check novo num fluxo compartilhado (o modal de variantes) tem de ser testado em todas as
telas que abrem esse fluxo, não só na que motivou o check.
