# Correções de Bugs — Verificação Recursiva

Data: 2026-05-30  
Módulos afetados: `lemuroid-app`  
Build final: `BUILD SUCCESSFUL`

---

## Resumo das rodadas de verificação

| Rodada | Bugs encontrados | Bugs corrigidos |
|--------|-----------------|-----------------|
| 1      | 3               | 3               |
| 2      | 0               | —               |

---

## Bug #1 — `onLongClick` silenciado em `LemuroidGameCard`

### Descrição
O parâmetro `onLongClick: () -> Unit` era aceito por `LemuroidGameCard` mas **nunca repassado** ao modificador `bounceClick`. Como resultado, pressionar longamente um card de jogo nas telas Home e Favoritos não abria o menu de contexto.

### Causa raiz
O `BounceClickModifier.kt` original implementava apenas `awaitFirstDown` + `waitForUpOrCancellation`, sem qualquer suporte a eventos de pressionamento longo.

### Correção

**Arquivo 1:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/shared/compose/ui/effects/BounceClickModifier.kt`

- Adicionado parâmetro `onLongClick: (() -> Unit)? = null`
- Substituído `awaitPointerEventScope { awaitFirstDown + waitForUpOrCancellation }` por `detectTapGestures(onPress, onTap, onLongPress)`, que suporta nativamente pressionar longo
- Chave do `pointerInput` atualizada para `(enabled, onLongClick)` para reiniciar o gesture detector quando o callback mudar

**Arquivo 2:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/shared/compose/ui/LemuroidGameCard.kt`

- `.bounceClick(onClick = onClick)` → `.bounceClick(onClick = onClick, onLongClick = onLongClick)`
- Removidos imports não utilizados: `ExperimentalFoundationApi`, `combinedClickable`
- Removida anotação `@OptIn(ExperimentalFoundationApi::class)` (não mais necessária)

### Impacto
Pressionar longamente um card em Home/Favoritos agora abre corretamente o menu de contexto.

---

## Bug #2 — Ações "Retomar" e "Reiniciar" no menu de contexto ignoravam a checagem de download

### Descrição
No menu de contexto (bottom sheet), as ações **"Retomar"** (`onGamePlay`) e **"Reiniciar"** (`onGameRestart`) chamavam `gameInteractor.onGamePlay/onGameRestart` diretamente, sem verificar se o ROM estava baixado. Para jogos que são placeholders de 0 bytes (catálogo HuggingFace), isso causaria falha imediata do emulador (tentando abrir um arquivo vazio).

### Causa raiz
A rotagem condicional (verificar se o jogo precisa ser baixado antes de lançar) estava implementada apenas em `onGameClick` (toque simples), mas não nas ações do menu de contexto.

### Correção

**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainActivity.kt`

Lambdas `onGamePlay` e `onGameRestart` passados para `MainGameContextActions` foram atualizados para usar o mesmo helper `isGamePlaceholder` (ver Bug #3):

```kotlin
onGamePlay = { game ->
    if (!isGamePlaceholder(game)) {
        gameInteractor.onGamePlay(game)
    } else {
        selectedGameForDownload.value = game  // abre dialog de download
    }
},
onGameRestart = { game ->
    if (!isGamePlaceholder(game)) {
        gameInteractor.onGameRestart(game)
    } else {
        selectedGameForDownload.value = game
    }
},
```

### Impacto
Tentar jogar/reiniciar um ROM ainda não baixado via menu de contexto agora exibe corretamente o dialog de download.

---

## Bug #3 — Lógica de disparo do dialog de download causava falso positivo para jogos locais normais

### Descrição (crítico)
A condição de `onGameClick` checava `downloadedFileNames.contains(game.fileName)` para decidir se devia abrir o dialog de download. O conjunto `downloadedFileNames` é populado **apenas** com ROMs baixados via o sistema on-demand (tabela `downloaded_roms`). Isso significava que jogos locais normais (copiados pelo usuário para o dispositivo, ou via SAF) — que nunca estarão nessa tabela — **sempre mostravam o dialog de download** em vez de iniciar o jogo.

### Causa raiz
Confusão entre "game em `downloadedFileNames`" e "game tem conteúdo no disco". O conjunto `downloadedFileNames` apenas rastreia downloads feitos pelo sistema on-demand; não representa o estado geral de disponibilidade do arquivo.

### Correção

**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainActivity.kt`

Adicionado import `java.io.File` e definido helper `isGamePlaceholder`:

```kotlin
val isGamePlaceholder = { game: Game ->
    val uri = Uri.parse(game.fileUri)
    uri.scheme == "file" &&
        uri.path?.let { File(it).length() == 0L } == true
}
```

A lógica substituída usa tamanho real do arquivo em disco:

| Tipo de jogo | `uri.scheme` | `File.length()` | `isGamePlaceholder` | Ação |
|---|---|---|---|---|
| ROM local normal | `file` | > 0 | `false` | Jogar direto ✓ |
| Placeholder streaming (0-byte) | `file` | `== 0` | `true` | Mostrar dialog ✓ |
| ROM baixado via on-demand | `file` | > 0 | `false` | Jogar direto ✓ |
| Jogo via SAF | `content` | — | `false` | Jogar direto ✓ |

**Nota de design:** O conjunto `downloadedFileNames` ainda é mantido e usado para:
1. Exibir o badge de download (ícone nos cards/linhas)
2. Mostrar o item "Excluir ROM baixado" no menu de contexto

### Impacto
Jogos locais normais (não-streaming) não exibem mais o dialog de download ao serem clicados.

---

## Arquivos modificados nesta sessão

| Arquivo | Tipo de mudança |
|---------|----------------|
| `BounceClickModifier.kt` | Suporte a `onLongClick`, use `detectTapGestures` |
| `LemuroidGameCard.kt` | Repassa `onLongClick`, remove imports mortos |
| `MainActivity.kt` | `isGamePlaceholder`, fix ações do menu de contexto |

---

---

# Correções de Bugs — Verificação Recursiva (Sessão 2)

Data: 2026-05-30  
Módulos afetados: `lemuroid-app`, `retrograde-app-shared`  
Build final: `BUILD SUCCESSFUL`

---

## Resumo das rodadas de verificação

| Rodada | Bugs encontrados | Bugs corrigidos |
|--------|-----------------|-----------------|
| 1 (Rodada 1) | 7 | 7 |
| 2 (Rodada 2) | 3 | 3 |
| 3 (Rodada 3) | 0 | — |

---

## Bug R1-1 — `animateItem()` remanescente em `HomeScreen` e `MetaSystemScreen`

### Descrição
`animateItem()` ainda presente em dois arquivos que não foram cobertos pela correção anterior:
- `HomeScreen.kt` linha 280: `modifier = Modifier.fillMaxWidth().animateItem()`
- `MetaSystemScreen.kt` linha 52: `modifier = Modifier.animateItem()`

Causa o mesmo problema de itens desaparecendo / reordenação visual indesejada ao atualizar listas.

### Arquivos corrigidos
- `lemuroid-app/.../feature/home/HomeScreen.kt`
- `lemuroid-app/.../feature/systems/MetaSystemScreen.kt`

### Correção
Removido `.animateItem()` de ambos os modificadores.

---

## Bug R1-2 — Anotações `@OptIn(ExperimentalFoundationApi::class)` e imports não utilizados

### Descrição
Após a remoção de `animateItem()` na sessão anterior, as seguintes anotações e imports ficaram órfãos:
- `GamesScreen.kt`: import `ExperimentalFoundationApi` + `@OptIn`
- `FavoritesScreen.kt`: import `ExperimentalFoundationApi` + `@OptIn`
- `SearchScreen.kt`: import `ExperimentalFoundationApi` + `@OptIn`
- `HomeScreen.kt`: import `ExperimentalFoundationApi` + `@OptIn`

### Arquivos corrigidos
- `lemuroid-app/.../feature/games/GamesScreen.kt`
- `lemuroid-app/.../feature/favorites/FavoritesScreen.kt`
- `lemuroid-app/.../feature/search/SearchScreen.kt`
- `lemuroid-app/.../feature/home/HomeScreen.kt`

### Correção
Removidos imports e anotações `@OptIn(ExperimentalFoundationApi::class)` não utilizados.

---

## Bug R1-3 — `activeCall` vazando em `RomOnDemandManager.downloadToFile`

### Descrição
O campo `activeCall` era definido antes de `call.execute().use { ... }`, mas limpo com `activeCall = null` **após** o bloco `use {}`, fora de um `finally`. Se qualquer exceção fosse lançada dentro do bloco (ex.: 429 ou IOError), `activeCall` ficaria apontando para um call já finalizado/cancelado para sempre. A próxima chamada a `cancelActiveDownload()` chamaria `.cancel()` num objeto inativo.

### Arquivo corrigido
`lemuroid-app/.../shared/roms/RomOnDemandManager.kt`

### Correção
Envolvido o bloco `call.execute().use { ... }` em `try { ... } finally { activeCall = null }`, garantindo a limpeza em todos os caminhos de saída.

```kotlin
// Antes
call.execute().use { response -> ... }
activeCall = null   // nunca executado se exceção ocorrer

// Depois
try {
    call.execute().use { response -> ... }
    onProgress(1f)
} finally {
    activeCall = null   // sempre executado
}
```

---

## Bug R1-4 — HTTP 429 tratado como erro permanente em `RomsDownloadManager.downloadFile`

### Descrição
O método `downloadFile` em `RomsDownloadManager` usava a faixa `if (response.code in 400..499)` para lançar `PermanentHttpException` (nunca retentada). Isso incluía o código 429 (rate limit), fazendo o download falhar definitivamente ao invés de aguardar e tentar novamente.

### Arquivo corrigido
`lemuroid-app/.../shared/roms/RomsDownloadManager.kt`

### Correção
Adicionada verificação explícita de 429 **antes** da faixa 4xx, lançando `IOException` (retentável) em vez de `PermanentHttpException`:

```kotlin
// Adicionado antes do bloco 4xx
if (response.code == 429) {
    val retryAfterSec = response.header("Retry-After")?.toLongOrNull() ?: 60L
    throw IOException("Rate limited (429), retry after ${retryAfterSec}s")
}
// 4xx (exceto 416, 429) = erro permanente — falha imediata
if (response.code in 400..499)
    throw PermanentHttpException(...)
```

---

## Bug R2-1 — `EmbeddedBiosInstaller` não verificava integridade do arquivo instalado

### Descrição
`EmbeddedBiosInstaller.installIfNeeded` usava apenas `if (destination.exists()) continue`, sem verificar se o arquivo era válido. Um arquivo BIOS corrompido ou de 0 bytes (resultado de uma instalação interrompida) nunca seria reinstalado, causando falha silenciosa de emulação.

### Arquivo corrigido
`lemuroid-app/.../shared/bios/EmbeddedBiosInstaller.kt`

### Correção
Adicionada verificação de tamanho comparado ao asset antes de pular:

```kotlin
if (destination.exists()) {
    val expectedSize = runCatching {
        context.assets.openFd("bios/$fileName").use { it.length }
    }.getOrElse { -1L }
    val sizeOk = expectedSize < 0L || destination.length() == expectedSize
    if (sizeOk && destination.length() > 0L) continue
    // Corrompido ou vazio — deletar e reinstalar
    destination.delete()
}
```

---

## Bug R2-2 — `RomsDownloadManager.fetchHfTree` não tratava HTTP 429

### Descrição
`fetchHfTree` fazia a chamada `httpClient.newCall(request).execute()` sem verificar o código 429. Um rate-limit na API de listagem do HuggingFace causaria `IOException` genérico sem retentativa adequada, fazendo o download principal falhar.

### Arquivo corrigido
`lemuroid-app/.../shared/roms/RomsDownloadManager.kt`

### Correção
Adicionado loop de retentativa por página (`while (true)`) com:
- Verificação explícita de 429
- Extração do header `Retry-After`
- Delay respeitando o tempo de espera indicado pelo servidor
- Máximo de 5 tentativas por página antes de falhar definitivamente

---

## Bug R2-3 — `StreamingRomsManager.fetchFileList` era função bloqueante sem tratamento de 429

### Descrição
`fetchFileList` era declarado como `fun` (não-suspend), impossibilitando o uso de `delay()` para retentativa de 429. Qualquer rate-limit na listagem de arquivos faria a função lançar `IOException` genérico, que o loop de retentativa externo tentaria novamente com apenas 3–15 segundos de espera, ignorando o header `Retry-After` (geralmente 60 segundos).

### Arquivo corrigido
`lemuroid-app/.../shared/roms/StreamingRomsManager.kt`

### Correção
- Convertida `fetchFileList` para `suspend fun`
- Adicionado loop de retentativa por página com tratamento correto de 429 e `Retry-After`
- Máximo de 5 tentativas por página antes de propagar a exceção

---

## Arquivos modificados nesta sessão (Sessão 2)

| Arquivo | Tipo de mudança |
|---------|----------------|
| `HomeScreen.kt` | Remove `animateItem()`, import e `@OptIn` não usados |
| `MetaSystemScreen.kt` | Remove `animateItem()` |
| `GamesScreen.kt` | Remove import e `@OptIn(ExperimentalFoundationApi::class)` não usados |
| `FavoritesScreen.kt` | Remove import e `@OptIn(ExperimentalFoundationApi::class)` não usados |
| `SearchScreen.kt` | Remove import e `@OptIn(ExperimentalFoundationApi::class)` não usados |
| `RomOnDemandManager.kt` | Fix `activeCall` leak com try-finally |
| `RomsDownloadManager.kt` | Fix 429 em `downloadFile`; add retry loop em `fetchHfTree` |
| `StreamingRomsManager.kt` | Converte `fetchFileList` para suspend; add retry loop com 429 |
| `EmbeddedBiosInstaller.kt` | Add verificação de integridade por tamanho do arquivo |
