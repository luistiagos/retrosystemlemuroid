# Correções — Verificação Recursiva (Rodada 2)

Data: 2026-05-30  
Resultado: 3 bugs encontrados e corrigidos em 3 rodadas. Rodada 4 confirmou zero bugs.  
Build final: `BUILD SUCCESSFUL` (configuration cache, todas as tasks UP-TO-DATE)

---

## Arquivos modificados

| Arquivo | Papel |
|---------|-------|
| `lemuroid-app/.../StreamingRomsManager.kt` | Gerenciador de download streaming via HuggingFace |
| `lemuroid-app/.../RomsDownloadManager.kt` | Gerenciador de download em lote via HuggingFace |

---

## Rodada 1 — 2 bugs corrigidos

### Bug 1.1 — Loop de escrita sem checkpoint de cancelamento em `downloadFileWithResume`

**Arquivo:** `StreamingRomsManager.kt` → `downloadFileWithResume()`  
**Severidade:** Alta  
**Caminho:** `doStreamingDownload()` → `downloadFileWithResume()` → loop `while (input.read(buffer)...)`

**Cenário:**  
O loop de cópia de bytes para disco não tinha nenhum ponto de verificação de cancelamento de coroutine (`ensureActive()`). Para arquivos grandes (ROMs de GBA/PSX/etc. com centenas de MB), ao usuário pausar ou cancelar o download, a coroutine só perceberia o cancelamento na próxima chamada suspend **após** o loop terminar — ou seja, somente depois de toda a escrita do arquivo atual concluir. Isso causava:

- Delay de cancelamento proporcional ao tamanho do arquivo (segundos a minutos)
- Impossibilidade de interromper um download de arquivo grande de forma responsiva
- Consumo desnecessário de bateria/dados mesmo após usuário cancelar

**Causa raiz:**  
`FileOutputStream.write()` e `InputStream.read()` são operações bloqueantes normais (não suspend). Sem um `ensureActive()` no loop, a coroutine nunca verifica se foi cancelada durante a escrita.

**Fix:**  
Adicionado `currentCoroutineContext().ensureActive()` dentro do loop de cópia, com dois novos imports:

```kotlin
// Novos imports adicionados:
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// Loop ANTES (sem checkpoint):
FileOutputStream(destFile, isResume).use { out ->
    body.byteStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        var n: Int
        while (input.read(buffer).also { n = it } != -1) {
            out.write(buffer, 0, n)
        }
    }
}

// Loop DEPOIS (com checkpoint a cada chunk de 256 KB):
FileOutputStream(destFile, isResume).use { out ->
    body.byteStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        var n: Int
        while (input.read(buffer).also { n = it } != -1) {
            out.write(buffer, 0, n)
            // Cancellation checkpoint: permite que pause/cancel tome efeito
            // imediatamente durante downloads de arquivos grandes sem esperar
            // o arquivo inteiro terminar.
            currentCoroutineContext().ensureActive()
        }
    }
}
```

**Impacto:** Após a correção, cancelar/pausar um download interrompe a escrita no próximo chunk de 256 KB (~milissegundos), em vez de aguardar o arquivo inteiro.

---

### Bug 1.2 — `hfApiUrl()` usava `limit=1000` em vez de `limit=10000`

**Arquivo:** `StreamingRomsManager.kt` → `hfApiUrl()`  
**Severidade:** Baixa (eficiência)

**Cenário:**  
A função `hfApiUrl()` montava a URL da API HuggingFace com `limit=1000`:

```kotlin
// Antes:
private fun hfApiUrl(path: String = HF_ROOT_PATH): String =
    "https://huggingface.co/api/datasets/$HF_DATASET/tree/main/$path?recursive=true&limit=1000"
```

O dataset do projeto tem ~23.000 arquivos. Com `limit=1000`, a função `fetchFileList` precisava fazer **23+ requisições paginadas** para obter a lista completa — consumindo quota da API HuggingFace desnecessariamente. O KDoc da própria função registrava o valor correto como `limit=10000`, indicando que esta era a intenção original.

**Causa raiz:** Divergência entre comentário (`10000`) e código (`1000`) — provavelmente erro de tipagem durante implementação.

**Fix:**

```kotlin
// Depois:
private fun hfApiUrl(path: String = HF_ROOT_PATH): String =
    "https://huggingface.co/api/datasets/$HF_DATASET/tree/main/$path?recursive=true&limit=10000"
```

**Impacto:** Com `limit=10000`, o dataset de 23k arquivos é coberto em **~3 requisições** em vez de 23+, reduzindo o consumo de quota da API HuggingFace em ~87%.

---

## Rodada 2 — Nenhum bug encontrado

Verificação completa cobriu todos os arquivos-chave não cobertos na Rodada 1:

| Arquivo | Resultado |
|---------|-----------|
| `HomeViewModel.kt` | Sem bugs — `cancelStreamingDownload` e `pauseStreamingDownload` coerentes |
| `HomeScreen.kt` | Sem bugs — `HomeStreamingCard` corretamente usa `AnimatedVisibility` |
| `RomDownloadDialog.kt` | Sem bugs — dialog exibe corretamente progresso e ações |
| `LemuroidLibrary.kt` | Sem bugs — `cleanUp()` e `indexLibrary()` seguros |
| `GameLauncher.kt` | Sem bugs — `findLibrary(...)!!` envolto em `runCatching` (seguro) |
| `SearchViewModel.kt` | Sem bugs |
| `FavoritesViewModel.kt` | Sem bugs |
| `MainViewModel.kt` | Sem bugs |
| `GamesViewModel.kt` | Sem bugs |
| `BiosManager.kt` | Sem bugs |
| `EmbeddedBiosInstaller.kt` | Sem bugs |
| `GameLoader.kt` | Sem bugs |
| `GameDao.kt` | Sem bugs |
| `DownloadedRomDao.kt` | Sem bugs |
| `LibraryIndexScheduler.kt` | Sem bugs |
| `RomSystemMapper.kt` | Sem bugs |

---

## Rodada 3 — 1 bug corrigido

### Bug 3.1 — `fetchHfTree()` usava `!!` inseguro em `response.body`

**Arquivo:** `RomsDownloadManager.kt` → `fetchHfTree()`  
**Severidade:** Média  
**Linha original:** ~519

**Cenário:**  
A função `fetchHfTree()` (chamada por sistema por `doDownload()` no `RomsDownloadManager`) buscava a lista de arquivos de cada pasta de sistema no HuggingFace. Após verificar que a resposta era bem-sucedida (`response.isSuccessful`), usava o operador `!!` para acessar o body:

```kotlin
// Antes (código problemático):
if (!response.isSuccessful)
    throw IOException("HuggingFace API error ${response.code} for /$folderPath")
val body = response.body!!.string()   // ← !! pode lançar NullPointerException
```

Em OkHttp, `response.body` é tecnicamente nullable. Embora seja raro na prática, um interceptor de rede mal configurado ou uma resposta malformada pode resultar em `body == null` mesmo com código HTTP 200. Nesse caso, `!!` lançaria `NullPointerException` em vez de  `IOException`, que:

1. **Não seria capturado** pelo `catch (e: IOException)` do loop de retry interno
2. **Propagaria como falha não recuperável**, quebrando o download completo em vez de tentar novamente
3. **Inconsistência** com `StreamingRomsManager.fetchFileList()`, que corretamente usa `?.string() ?: throw IOException(...)`

**Fix:**  
Substituído `!!` por verificação null-safe consistente com o padrão já usado em `StreamingRomsManager`:

```kotlin
// Depois (seguro):
if (!response.isSuccessful)
    throw IOException("HuggingFace API error ${response.code} for /$folderPath")
val body = response.body?.string()
    ?: throw IOException("Empty response from HuggingFace API for /$folderPath")
```

**Impacto:** Qualquer caso de `body == null` agora lança `IOException` em vez de `NullPointerException`, sendo capturado pelo loop de retry existente e tratado de forma recuperável.

---

## Rodada 4 — Nenhum bug encontrado

Varredura final com grep em todos os arquivos `.kt` do projeto:

| Padrão pesquisado | Resultado |
|-------------------|-----------|
| `\.body!!` (unsafe body em toda codebase) | **0 ocorrências** ✓ |
| `GlobalScope\.` (uso não intencional de GlobalScope) | 3 ocorrências — todas intencionais e documentadas ✓ |
| `runBlocking` | 0 ocorrências em código de produção ✓ |
| `\.value!!` em respostas de rede | 0 novas ocorrências ✓ |

---

## Resumo das rodadas

| Rodada | Bugs encontrados | Bugs corrigidos | Build |
|--------|-----------------|-----------------|-------|
| 1      | 2               | 2               | ✓ SUCCESSFUL |
| 2      | 0               | —               | ✓ UP-TO-DATE |
| 3      | 1               | 1               | ✓ SUCCESSFUL |
| 4      | 0               | —               | ✓ UP-TO-DATE |

**Total desta sessão: 3 bugs corrigidos.**

---

## Referência cruzada com correções anteriores

Esta sessão é continuação das verificações recursivas documentadas em:

- [`correcoes-2026-03-29.md`](correcoes-2026-03-29.md) — KEEP→REPLACE race condition, Thread.sleep→delay, dialog reaparecia após cancelar
- [`correcoes-bugs-verificacao-recursiva.md`](correcoes-bugs-verificacao-recursiva.md) — onLongClick silenciado, menu de contexto sem checagem de download, animateItem crash, LEFT JOIN sort, GameInteractor coroutine leak

Todos os módulos do sistema de download streaming agora estão livres de bugs conhecidos.
