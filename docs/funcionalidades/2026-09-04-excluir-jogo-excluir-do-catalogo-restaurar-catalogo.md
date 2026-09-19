# [FEATURE] Excluir ROM, excluir do catálogo e restaurar catálogo

**Data:** 2026-09-04
**Branch:** version9

## Motivação

No menu de contexto do jogo (long-press) só existia **Excluir ROM baixada**, e ela aparecia
apenas quando `downloadedGameKeys` continha a chave do jogo — ou seja, sumia para qualquer jogo
que não estivesse baixado e também para ROMs cujo arquivo existe em disco mas não têm linha em
`downloaded_roms` (importadas pelo usuário, ou baixadas antes de a tabela existir). Faltava
ainda uma forma de tirar um jogo **do catálogo** e uma forma de desfazer isso.

## O que foi implementado

### 1. Excluir ROM baixada (comportamento preservado)

Continua apagando **só o arquivo** — o jogo permanece no catálogo como placeholder e pode ser
baixado de novo. Duas mudanças:

- Passou a ter diálogo de confirmação (é destrutiva e fica a um toque de distância no menu).
- O gate `isGameDownloaded` ganhou fallback por disco:
  `downloadedGameKeys.contains(key) || !isGamePlaceholder(game)`. Antes, uma ROM presente em
  disco sem linha em `downloaded_roms` não oferecia a opção.

### 2. Excluir do catálogo (novo)

`RomOnDemandManager.deleteFromCatalog(game)`:

1. Resolve **todas as variantes** do grupo `(systemId, title)` via
   `GameDao.selectVariantsByTitleOnce`. O catálogo mostra um card por título (o representante),
   então apagar só a linha clicada deixaria o jogo vivo na Busca com o card sumido do catálogo.
2. Para cada variante: apaga o arquivo de disco de verdade (**sem** deixar placeholder 0-byte,
   diferente do `deleteRom`) ou o diretório de extração multi-disco, e remove a linha de
   `downloaded_roms`.
3. Grava as chaves `"systemId/fileName"` em `CatalogRemovals`.
4. Deleta as linhas de `games`.

Apagar o arquivo por completo (em vez de truncar) é o que impede a `LibraryIndexWork` de
reencontrar o jogo no scan e reinseri-lo.

### 3. `CatalogRemovals` — por que a lista precisa existir

Deletar a linha de `games` sozinho **não** é permanente: o `ManifestQuickLoader` reinsere todas
as entradas do manifest sempre que roda uma passada completa (troca de `versionCode`, bump de
`MANIFEST_SCHEMA_VERSION`), e o jogo voltaria sozinho depois de um update.

`CatalogRemovals` (`retrograde-app-shared/.../lib/library/catalog/CatalogRemovals.kt`) guarda as
chaves removidas num `StringSet` de SharedPreferences (`catalog_removals_prefs`) e expõe um
`StateFlow` para a UI. O `ManifestQuickLoader.load()`:

- pula essas chaves ao montar a lista `games` (então nem reinsere, nem as protege da limpeza de
  catálogo obsoleto);
- desconta `removedKeys.size` do `expectedSize` usado no fast-skip, senão o limiar de 98% ficaria
  calibrado errado conforme o usuário fosse removendo jogos.

Sem tabela nova: nenhuma migração de Room, nenhum impacto no `identityHash` nem no
`retrograde-prebuilt.db`.

### 4. Restaurar catálogo (Ajustes ▸ ROMs)

`SettingsViewModel.resetCatalog()`:

1. `CatalogRemovals.clear()`
2. `ManifestQuickLoader.forceReload()`
3. `LibraryIndexScheduler.triggerCatalogQuickLoad()`

O item mostra a contagem de jogos removidos no subtítulo, pede confirmação, fica desabilitado
durante a operação e emite toast (`displayToast`) ao terminar.

> **`forceReload` usa flag própria (`force_full_reload`), não limpa `loaded_manifest_schema`.**
> Zerar o schema faria `loadedSchema` virar `-1` e re-disparar **todas** as migrações one-time
> guardadas por `loadedSchema < N` — inclusive a reclassificação de arcade da v27, que varre o
> manifest inteiro mexendo em arquivos. A flag some no fim da passada completa.

As ROMs já apagadas **não** voltam: os jogos reaparecem como placeholder, prontos para baixar.
Isso está dito no texto do diálogo de confirmação.

### 5. UI de TV

O menu de contexto da TV (`GameContextMenuListener`, um `View.OnCreateContextMenuListener` usado
pelo `GamePresenter` do leanback) não tinha **nenhuma** das duas exclusões — nunca teve. Como ele
só recebe `gameInteractor` e `game`, as ações entraram no próprio `GameInteractor`:

- `supportsRomDeletion()` — `true` só quando o `RomOnDemandManager` foi injetado. O provider do
  `MainTVActivity.Module` passa; o do mobile não, porque lá o `MainActivity` chama o manager
  direto e a confirmação é um `AlertDialog` do Compose. Sem isso o mobile mostraria dois
  diálogos.
- `isRomDownloaded(game)` — gate da entrada "Excluir ROM baixada". Na TV é só o teste de disco
  (`!isGamePlaceholder`), sem consultar `downloaded_roms`: o gate por DB é justamente o que
  escondia a opção indevidamente no mobile.
- `onDeleteRom` / `onDeleteFromCatalog` — `AlertDialog` de confirmação e execução no
  `lifecycleScope` do activity. Guard `isFinishing || isDestroyed` antes do `show()`: o menu de
  contexto sobrevive ao activity na TV e `show()` num token morto lança `BadTokenException`
  (mesma raiz do pitfall 7).

Em **Ajustes ▸ Diversos** da TV entrou o `Preference` `pref_key_reset_catalog`, tratado no
`onPreferenceTreeClick` do `TVSettingsFragment`. Sem ele o usuário de TV box poderia esvaziar
parte do catálogo sem nenhum caminho de volta — a UI mobile não existe naquele aparelho. O
summary mostra a contagem de removidos (atualizado no `onResume`), a preference fica desabilitada
durante a operação e o toast sai por `displayToast`.

O toast fica **fora** do `finally`: sair da tela cancela o `lifecycleScope`, e nesse caso o reset
termina sozinho no próximo boot (a flag `force_full_reload` já está persistida) — anunciar
"Catálogo restaurado" ali seria mentira.

## Arquivos

| Arquivo | Mudança |
|---------|---------|
| `lib/library/catalog/CatalogRemovals.kt` | **novo** — lista persistente de remoções |
| `lib/library/catalog/ManifestQuickLoader.kt` | filtro de removidos, `KEY_FORCE_FULL_RELOAD`, `forceReload()` |
| `lib/library/db/dao/GameDao.kt` | `selectVariantsByTitleOnce` (suspend) |
| `app/shared/roms/RomOnDemandManager.kt` | `deleteFromCatalog()` |
| `app/mobile/feature/main/MainGameContextActions.kt` | entrada "Excluir do catálogo" |
| `app/mobile/feature/main/MainActivity.kt` | dois diálogos de confirmação + fallback do `isGameDownloaded` |
| `app/mobile/feature/settings/general/SettingsViewModel.kt` | `resetCatalog`, `catalogRemovalCount`, `catalogResetInProgress/Completed` |
| `app/mobile/feature/settings/general/SettingsScreen.kt` | item "Restaurar catálogo" |
| `app/shared/GameInteractor.kt` | `romOnDemandManager` opcional, `onDeleteRom`, `onDeleteFromCatalog`, `supportsRomDeletion`, `isRomDownloaded`, `confirmDestructiveAction` |
| `app/shared/GameContextMenuListener.kt` | duas entradas novas no menu de contexto da TV |
| `app/tv/main/MainTVActivity.kt` | passa `romOnDemandManager` ao `GameInteractor` |
| `app/tv/settings/TVSettingsFragment.kt` | `handleResetCatalog` + summary com contagem |
| `res/xml/tv_settings.xml`, `res/values/keys.xml` | preference `reset_catalog` |
| `res/values{,-pt-rBR,-pt-rPT}/strings.xml` | 13 strings novas |
