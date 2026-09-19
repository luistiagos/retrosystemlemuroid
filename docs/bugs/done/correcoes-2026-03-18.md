# Correções e melhorias — 2026-03-18

Registro de tudo que foi revisado, corrigido ou melhorado nesta data.
Foco principal: robustez do download em dispositivo físico (Moto G86 5G) e UX da barra de progresso.

---

## Arquivos envolvidos

| Arquivo | Caminho |
|---------|---------|
| `RomsDownloadManager.kt` | `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/` |
| `HomeScreen.kt` | `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/home/` |
| `AndroidManifest.xml` | `lemuroid-app/src/main/` |

---

## Sessão A — Revisão geral de código

### A-1 · Bug silencioso — `LinearProgressIndicator` com API obsoleta

**Arquivo:** `HomeScreen.kt`

**Problema:** Os dois `LinearProgressIndicator` no card de download (estados `Downloading` e
`Extracting`) usavam a forma depreciada `progress = Float`, enquanto `SettingsScreen.kt` já
usava a forma correta `progress = { Float }` (lambda).

**Correção:**
```kotlin
// ANTES (depreciado — gera warning de compilação)
LinearProgressIndicator(
    progress = state.progress,
    modifier = Modifier.fillMaxWidth(),
)

// DEPOIS (correto para Compose BOM 2024.02.02+)
LinearProgressIndicator(
    progress = { state.progress },
    modifier = Modifier.fillMaxWidth(),
)
```
Corrigido nos estados `Downloading` e `Extracting` do `HomeDownloadCard`.

---

## Sessão B — Robustez do download em rede móvel

### Diagnóstico: causa raiz dos "connection abort"

O `ping` do dispositivo confirmou que o HuggingFace é servido via
**AWS CloudFront** (nó `gru1.r.cloudfront.net`, São Paulo).

O CloudFront fecha conexões TCP longas **por design** (balanceamento de carga).
Para um arquivo de ~5 GB dividido em 4 segmentos de ~1,25 GB, cada segmento
sofre múltiplos resets inevitáveis:

```
SocketException: Connection reset
IOException: unexpected end of stream
IOException: Software caused connection abort
```

Com **5 retries** e backoff linear de até 30 s, um segmento de 1,25 GB com aborts
a cada poucos minutos falhava definitivamente em ~25 minutos. Com **30 retries**
e backoff exponencial, persiste por horas se necessário.

Segundo fator: o NAT da operadora tem timeout curto (~30–90 s), removendo
silenciosamente entradas de conexão ativas — causando abort do lado do cliente.

### B-1 · Aumento de retries e backoff exponencial

**Arquivo:** `RomsDownloadManager.kt`

**Problema:** `maxRetries = 5` com backoff linear (5 s, 10 s … 30 s) era insuficiente
para downloads de 5 GB em rede 5G com CloudFront.

**Correção:**
- `maxRetries` aumentado de **5 → 30** em `downloadSegment` e `downloadFile`
- Backoff trocado de linear para **exponencial com jitter**:
  `4 s → 8 s → 16 s → 32 s → 60 s …` (capped em 60 s + até 2 s aleatório)
- Novo método `retryDelayMs(attempt)` centraliza a lógica

```kotlin
private fun retryDelayMs(attempt: Int): Long {
    val base = (4_000L * (1L shl (attempt - 1).coerceAtMost(4)))
    val jitter = (Math.random() * 2_000).toLong()
    return (base + jitter).coerceAtMost(60_000L)
}
```

### B-2 · Fast-fail em erros HTTP 4xx permanentes

**Arquivo:** `RomsDownloadManager.kt`

**Problema:** Erros HTTP 4xx (ex: 403 Forbidden, 404 Not Found) consumiam todos os
30 retries um a um — o download ficava travado por ~20 minutos antes de falhar
com a mensagem correta.

**Correção:** Introduzida `PermanentHttpException`. Qualquer resposta HTTP 4xx
(exceto 416 "Range Not Satisfiable", que é tratado como sucesso) lança esta
exceção antes do catch genérico, que a re-lança imediatamente sem incrementar
o contador de tentativas.

```kotlin
private class PermanentHttpException(message: String) : IOException(message)

// No catch:
} catch (e: PermanentHttpException) {
    throw e // nunca retenta
}
```

### B-3 · Tratamento correto de HTTP 200 vs 206 em `downloadSegment`

**Arquivo:** `RomsDownloadManager.kt`

**Problema:** `downloadSegment` sempre fazia append ao arquivo `.seg*` (`written > 0`).
Se o servidor retornasse HTTP 200 (conteúdo integral) em vez de 206 (conteúdo parcial)
— ignorando o header `Range` — o arquivo seria corrompido com bytes duplicados.

**Correção:** Verificação explícita do código de resposta:
- `206` → append (resume normal)
- `200` → truncar o arquivo e resetar `written = 0`

```kotlin
val isPartial = response.code == 206
if (!isPartial) {
    Log.w(TAG, "Server returned 200 instead of 206, resetting offset")
    written = 0
}
FileOutputStream(segFile, isPartial && written > 0).use { ... }
```

### B-4 · Read timeout de 90 s para conexões travadas

**Arquivo:** `RomsDownloadManager.kt`

**Problema:** `readTimeout(0)` (sem timeout) fazia o download **travar silenciosamente**
quando o CDN parava de enviar dados mas mantinha o TCP aberto — o worker entrava
em deadlock sem possibilidade de retry.

**Correção:**
```kotlin
// ANTES
.readTimeout(0, TimeUnit.SECONDS)  // trava para sempre se CDN parar de enviar

// DEPOIS
.readTimeout(90, TimeUnit.SECONDS) // força IOException após 90 s sem dados → retry
```

---

## Sessão C — WAKE_LOCK explícito no manifest

### C-1 · `WAKE_LOCK` não declarado explicitamente

**Arquivo:** `AndroidManifest.xml`

**Problema:** O WorkManager declara `WAKE_LOCK` internamente via manifest merge, mas
OEMs como Motorola e Samsung têm uma camada extra de otimização de bateria que pode
ignorar permissões mergeadas. No Moto G86 5G, isso pode causar a suspensão da CPU
durante o download (bloqueio de tela + bateria).

**Correção:** Permissão declarada explicitamente no manifest do app:
```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

> **Nota:** O bloqueio de tela por si só **não corta o download** — o Foreground Service
> (`dataSync`) mantém a CPU e a rede ativas. O risco real é a otimização de bateria OEM.
> Recomenda-se configurar **"Sem restrições de bateria"** nas configurações do app:
> Configurações → Apps → LemuroiDebug → Bateria → Irrestrito.

---

## Sessão D — UX: barra de progresso não voltava para 0% ao retomar

### D-1 · Barra de progresso resetava para 0% ao clicar "Tentar novamente"

**Arquivo:** `RomsDownloadManager.kt`

**Problema:** Ao clicar "Tentar novamente" após uma falha (ex: 51% baixado), a barra
*parecia* voltar para 0% por 10–15 segundos porque:

1. O estado `WorkInfo.State.ENQUEUED` emitia `Downloading(0f)` enquanto o WorkManager
   colocava o trabalho na fila.
2. `downloadFileParallel` só chamava `onProgress` ao receber o **primeiro byte novo**,
   não ao detectar os bytes já salvos nos arquivos `.seg*`.

O download **física e corretamente** continuava de 51% — mas a UI dava a impressão
de reinício completo, confundindo o usuário.

**Correção em três partes:**

**D-1a — Persistência do progresso (`PREF_LAST_DOWNLOAD_PROGRESS`):**
```kotlin
private const val PREF_LAST_DOWNLOAD_PROGRESS = "last_download_progress"
```
Salvo a cada ~2% durante a fase de download. Limpo ao concluir ou cancelar.

**D-1b — Estado ENQUEUED usa o progresso salvo:**
```kotlin
// ANTES
info.state == WorkInfo.State.ENQUEUED -> DownloadRomsState.Downloading(0f)

// DEPOIS
info.state == WorkInfo.State.ENQUEUED ->
    DownloadRomsState.Downloading(prefs.getFloat(PREF_LAST_DOWNLOAD_PROGRESS, 0f))
```

**D-1c — Progresso semeado emitido imediatamente em `downloadFileParallel`:**
```kotlin
// Após calcular os bytes já baixados dos .seg* files:
if (totalDownloaded.get() > 0L) {
    onProgress((totalDownloaded.get().toFloat() / totalSize).coerceAtMost(0.99f))
}
// Só depois inicia os downloads paralelos
```

**Fluxo após o fix:**

| Momento | UI mostra |
|---------|-----------|
| Erro em 51% | Barra em 51%, botão "Tentar novamente" |
| Clique em "Tentar novamente" → ENQUEUED | Barra em **51%** (do pref salvo) |
| Worker inicia → RUNNING (antes do 1º byte) | Barra em **51%** (semeada dos .seg*) |
| Download continua | Barra sobe de 51% em diante, sem flash de 0% |

---

## Tabela resumo — todas as correções de 2026-03-18

| ID | Severidade | Arquivo(s) | Sintoma | Fix |
|----|-----------|-----------|---------|-----|
| A-1 | Baixo (warning) | `HomeScreen` | `LinearProgressIndicator` com API depreciada | Forma lambda `progress = { ... }` |
| B-1 | **Alto** | `RomsDownloadManager` | 5 retries esgotados por resets do CloudFront CDN | 30 retries + backoff exponencial com jitter |
| B-2 | **Alto** | `RomsDownloadManager` | Erros 4xx consumiam todos os retries | `PermanentHttpException` — fast-fail imediato |
| B-3 | **Médio** | `RomsDownloadManager` | Servidor 200 em vez de 206 corromperia o arquivo | Verificação `isPartial` + truncate se necessário |
| B-4 | **Alto** | `RomsDownloadManager` | Download travado silenciosamente (CDN sem fechar TCP) | `readTimeout(90s)` em vez de sem timeout |
| C-1 | **Médio** | `AndroidManifest` | Motorola OEM pode suspender CPU durante download | `WAKE_LOCK` declarado explicitamente |
| D-1 | **Médio** (UX) | `RomsDownloadManager` | Barra de progresso voltava para 0% ao retomar | `PREF_LAST_DOWNLOAD_PROGRESS` + ENQUEUED seed + emit imediato |
