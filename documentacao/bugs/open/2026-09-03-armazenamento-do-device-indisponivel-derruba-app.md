# [BUG] (investigação) Armazenamento do aparelho em estado ruim derruba o app — `SQLiteFullException` e `workdb` em storage adotado que sumiu

**Data:** 2026-09-03
**Status:** 🔵 Investigação — causa é do ambiente do aparelho, mas o app morre em vez de degradar
**Severidade:** Baixa (2 ocorrências, ambas em Chromecast; não há perda de dados do usuário)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/main`
**Errors (serviço):** 2 ocorrências — 1696, 2003
**Aparelhos:** Google Chromecast HD e Google Chromecast, **armeabi-v7a**, Android 14, app 1.17.9 / 1.17.10
**Observado em:** 2026-08-22 03:36 e 2026-08-23 22:28

> Classificação honesta: **a causa não é nossa.** O que é nosso é a exceção subir sem tratamento
> e matar o processo. Registrado como fronteira (ambiente do usuário, mas ação nossa possível),
> no mesmo espírito de [[2026-09-02-telemetria-lowmemory-e-eco-de-crash]].

---

## Sintoma

### 2003 — disco cheio durante uma transação do Room

```
android.database.sqlite.SQLiteFullException: database or disk is full (code 13 SQLITE_FULL)
	at android.database.sqlite.SQLiteConnection.nativeExecute(Native Method)
	at android.database.sqlite.SQLiteSession.endTransactionUnchecked(SQLiteSession.java:439)
	at android.database.sqlite.SQLiteDatabase.endTransaction(SQLiteDatabase.java:757)
	…
	at androidx.room.a$a$b.invokeSuspend(SourceFile:13)
	at kotlinx.coroutines… (Dispatchers.IO)
	Suppressed: DiagnosticCoroutineContextException: [T0{Cancelling}@…, Dispatchers.IO]
```

Thread `DefaultDispatcher-worker-3`. A falha é no **commit** da transação, não na query. Sendo
uma exceção não capturada dentro de uma coroutine sem handler, o processo cai.

### 1696 — `workdb` do WorkManager num storage adotado que não existe mais

```
java.lang.IllegalStateException: The file system on the device is in a bad state.
WorkManager cannot access the app's internal data store.
	at androidx.work.impl.utils.ForceStopRunnable.run(SourceFile:83)
Caused by: SQLiteCantOpenDatabaseException: Cannot open database
  '/mnt/expand/3ebe6f94-5e81-40a1-bef3-d68f1a64e712/user/0/app.retrogamesystem/no_backup/androidx.work.workdb'
  … Directory …/no_backup doesn't exist
```

Thread `WM.task-1`. O app foi movido para **storage adotado** (`/mnt/expand/<uuid>`) e o volume
não está montado — ou foi reformatado. O `ForceStopRunnable` do WorkManager transforma isso numa
`IllegalStateException` deliberada, que também não é capturada.

## Causa-raiz

Ambas são **estado do dispositivo**, não defeito de lógica:

- 2003: o Chromecast (armazenamento de 8 GB, e o app baixa ROMs) encheu. SQLite não consegue
  nem fechar a transação.
- 1696: `/mnt/expand/<uuid>` é o caminho de *adoptable storage*. Quando o cartão/volume adotado
  some, todo o diretório de dados do app desaparece com ele. O WorkManager detecta e aborta de
  propósito — [é comportamento documentado da própria biblioteca](https://issuetracker.google.com/issues/138326753).

O que é nosso: nos dois casos a exceção sobe até o `UncaughtExceptionHandler` e o app fecha.
Para o usuário isso é indistinguível de um bug — e a tela que ele vê é a genérica.

## Como reproduzir

- **2003:** encher o armazenamento do aparelho (`fallocate`/copiar arquivos até sobrar < 1 MB) e
  disparar qualquer escrita no catálogo (favoritar um jogo, terminar um download).
- **1696:** mover o app para um cartão SD adotado, desligar, remover o cartão, ligar.

## Próximos passos

- [ ] Envolver as escritas do Room que rodam fora da UI num `runCatching` que trate
      `SQLiteFullException`/`SQLiteCantOpenDatabaseException` e avise via `displayToast`, em vez
      de deixar a coroutine derrubar o processo. Ponto de decisão: **quais** escritas podem
      falhar silenciosamente sem mentir para o usuário (favoritar: sim, registrar download
      concluído: não).
- [ ] Avaliar se vale filtrar estas duas assinaturas na `CrashTelemetry` — são estado do
      aparelho, e vão continuar chegando. O precedente é o filtro de `lowmemory`.
- [ ] Só investir em correção se o volume crescer; com 2 eventos em ~3 semanas, a prioridade é
      não gastar rodada de triagem futura relendo estes dois.

## Lição

"Sem espaço" e "volume sumiu" não são condições exóticas no público-alvo deste app — TV box e
Chromecast têm 8 GB e o app existe para baixar ROMs. Toda escrita em disco no caminho de
background precisa de um plano para o caso de o disco não estar lá; o default (exceção não
tratada em coroutine = processo morto) é o pior deles.
