# Correções — 19 de Abril de 2026

Horário: **19/04/2026**  
Build final: **BUILD SUCCESSFUL** — APK instalado no dispositivo `ZY32LMNN9B`.

---

## Correção 1 — `deleteByLastIndexedAtLessThan` deletava ROMs baixados via on-demand

### Arquivo
`retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/GameDao.kt`

### Severidade
**CRÍTICO** — regressão observada pelo usuário: "baixei 2 ROMs, uma ficou com o ícone verde e a outra sumiu da lista"

### Sintoma
Ao baixar N ROMs via download on-demand em sequência, exatamente 1 desaparecia completamente da lista de jogos (não ficava sem badge — sumia do catálogo). O padrão era N downloads → N-1 com ícone verde + 1 completamente ausente.

### Causa raiz
O método `deleteByLastIndexedAtLessThan(startedAtMs)` em `GameDao`, chamado no bloco `finally` de `LemuroidLibrary.indexLibrary()`, apagava qualquer `Game` cujo `lastIndexedAt` fosse menor que o timestamp de início do scan corrente. Isso era correto para jogos cujos arquivos foram removidos do disco, mas tornava-se um problema com a seguinte sequência de eventos:

1. ROM A é baixada → arquivo escrito em disco → `scheduleLibrarySync(W1)` enfileirado
2. ROM B é baixada (antes de W1 terminar) → `scheduleLibrarySync(W2)` enfileirado — com `APPEND_OR_REPLACE`, se W1 ainda está `ENQUEUED`, W2 o **substitui**; se W1 já está `RUNNING`, W2 fica na fila após W1
3. Em qualquer um dos casos, existe uma janela de tempo onde:
   - Um scan é executado com `startedAtMs = T_novo`
   - Por uma condição de corrida, exceção parcial, ou substituição do trabalho anterior, um dos jogos baixados não teve seu `lastIndexedAt` atualizado para `T_novo` durante esse scan
4. O `cleanUp(T_novo)` no `finally` chama `deleteByLastIndexedAtLessThan(T_novo)` e **deleta o jogo** da tabela `games`

A proteção correta é: **jogos que o usuário baixou explicitamente via on-demand (`downloaded_roms`) jamais devem ser deletados pelo cleanup do scanner**, independentemente de terem sido encontrados ou não no scan atual.

### Correção

```kotlin
// ANTES — apagava QUALQUER jogo com lastIndexedAt antigo:
@Query("DELETE FROM games WHERE lastIndexedAt < :lastIndexedAt")
suspend fun deleteByLastIndexedAtLessThan(lastIndexedAt: Long)

// DEPOIS — protege jogos que o usuário baixou explicitamente:
@Query("DELETE FROM games WHERE lastIndexedAt < :lastIndexedAt AND fileName NOT IN (SELECT fileName FROM downloaded_roms)")
suspend fun deleteByLastIndexedAtLessThan(lastIndexedAt: Long)
```

### Por que esta abordagem
- **Mínimo invasivo**: mudança de 1 linha de SQL, sem alterar lógica de scan ou WorkManager
- **Correto semanticamente**: um jogo que o usuário baixou explicitamente deve permanecer no catálogo enquanto estiver em `downloaded_roms`. O cleanup serve para remover jogos cujos *arquivos foram deletados do disco*, não jogos que o usuário fez download
- **Sem efeitos colaterais**: quando o usuário deleta um ROM baixado (via "Excluir ROM"), `downloadedRomDao.deleteByFileName()` remove o registro de `downloaded_roms`, e o próximo scan trata o placeholder 0-byte normalmente — o jogo volta ao estado de catálogo sem o badge de downloaded
- **Defesa em profundidade**: mesmo que a condição de corrida no WorkManager (`APPEND_OR_REPLACE`) ou uma exceção parcial no scan cause um game a não ter `lastIndexedAt` atualizado, ele está protegido

---

## Status do Build

```
BUILD SUCCESSFUL
APK: lemuroid-app-free-bundle-release.apk
Dispositivo: ZY32LMNN9B
Install: Success
```
