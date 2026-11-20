---
tipo: implementacao
demandas: [2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread]
depende_de: []
---
# Criar os canais de notificação uma vez por processo, não a cada notificação

## Objetivo

`NotificationsManager` chama `NotificationManager.createNotificationChannel` (transação Binder síncrona
para o `system_server`, `INotificationManager.createNotificationChannels`) **em toda** montagem de
notificação. O `DownloadForegroundService` monta uma notificação a cada tick de progresso do download, na
main thread — e foi exatamente esse Binder que segurou a main por 5 s no ANR (telemetria 9303, Realme
RMX5020, Android 16, app 1.17.23). Canal é idempotente no sistema: basta criá-lo uma vez por processo.

## O que mudar

`lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/shared/NotificationsManager.kt`:

- `NotificationsManager::createDownloadNotificationChannel` e `NotificationsManager::createDefaultNotificationChannel`
  (privadas, hoje sem guarda nenhuma, `if (SDK_INT >= O)` e chamada direta) passam a criar o canal só na
  primeira vez **no processo**: flag `@Volatile` (ou `AtomicBoolean`) por canal no `companion object`
  (a classe é instanciada de novo a cada uso — `DownloadForegroundService.buildNotification` faz
  `NotificationsManager(applicationContext)` a cada tick —, então flag de instância não serve).
- A flag só liga **depois** que `createNotificationChannel` retornou sem exceção (se lançar, a próxima
  chamada tenta de novo). Corrida entre duas threads criando o mesmo canal é inofensiva (o sistema trata
  como update do mesmo id); não precisa de lock.
- Não mudar id, nome, importância nem som/vibração dos canais (os usuários já têm o canal criado; mudar
  importância de canal existente não tem efeito e confundiria a leitura).
- Comentário curto citando o id da demanda
  (`2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread`).

**ktlint (obrigatório, arrasta `lemuroid-app/config/ktlint/baseline.xml`):** o arquivo tem 14 apontamentos
congelados no baseline (linhas 3, 142–165 e **215**, esta dentro de `createDownloadNotificationChannel`). Qualquer
linha inserida acima deles faz o passivo "reaparecer" (`CLAUDE.md`, Estilo, ⚠️ baseline casa por linha). Como a
mudança mexe justamente na região, **corrija os 14 apontamentos do arquivo** (import-ordering, function-signature
do `romQueueNotification`, multiline-expression-wrapping, argument-list-wrapping — formatação apenas, sem mudar
comportamento) e **apague à mão o bloco `<file name="src/main/java/com/swordfish/lemuroid/app/mobile/shared/NotificationsManager.kt">`**
do `baseline.xml`. Não rodar `ktlintGenerateBaseline` (manual, seção Dev). Critério: `gradlew :lemuroid-app:ktlintCheck`
sem apontamento em `NotificationsManager.kt`.

## Teste que prova

- Sem teste JVM (a lógica é a chamada ao `NotificationManager` do framework; o projeto não tem Robolectric).
  A prova é a do Plano de teste abaixo (`am trace-ipc`), mais o `build` (compila + 54 testes de base).

## Plano de teste da task

- **Offline:** `git diff` mostra a guarda nas duas funções privadas e nenhuma outra mudança de
  comportamento; `:lemuroid-app:ktlintCheck` sem apontamento no arquivo; baseline sem o bloco do arquivo.
- **T2 (aparelho, pela UI):** app aberto na Início; tocar num jogo do catálogo **não baixado** de PSX ou
  Dreamcast (≥ 200 MB, para o download durar) → confirmar o download → abrir a fila (ícone de download na
  barra) e deixar baixando. Com o download em curso:
  `adb -s <serial> shell am trace-ipc start`, esperar 30 s, `adb -s <serial> shell am trace-ipc stop --dump-file /data/local/tmp/ipc.txt`,
  `adb -s <serial> pull /data/local/tmp/ipc.txt`. No bloco do processo `app.retrogamesystem.debug`, contar as
  transações `createNotificationChannels`.
  - **Sinal do defeito (base):** dezenas/centenas de `createNotificationChannels` em 30 s, com stack em
    `NotificationsManager.createDownloadNotificationChannel` ← `romQueueNotification` ← `DownloadForegroundService`.
  - **Esperado (fix):** 0 `createNotificationChannels` nos 30 s (o canal já foi criado no `startForeground`
    do começo do download, antes de ligar o trace).
  - Depois: cancelar o download no modal da fila (X) para não encher o armazenamento.
- **Controle positivo:** o mesmo roteiro com o APK da base (`git checkout --detach <base>` + `build`), manual,
  seção Teste (E2E).
- **T3:** as outras notificações que usam o canal padrão continuam aparecendo — indexar biblioteca
  (Ajustes ▸ reescanear) mostra a notificação de indexação; abrir um jogo mostra "jogo em execução".

## Correcao (pipeline 20261119-2228, rodada 1)

- `NotificationsManager::createDefaultNotificationChannel` e `::createDownloadNotificationChannel`: retornam cedo
  quando a flag do canal ja esta ligada; a flag liga **depois** do bloco `if (SDK_INT >= O)` (so se
  `createNotificationChannel` retornou sem lancar; abaixo da API 26 liga direto, nao ha o que criar).
- `companion object`: `@Volatile private var defaultChannelCreated` / `downloadChannelCreated` (por processo; a
  classe e instanciada a cada notificacao — `DownloadForegroundService.buildNotification`).
- Id, nome, importancia, som e vibracao dos canais inalterados.
- ktlint: os 13 apontamentos do baseline corrigidos (import `MainActivity` na ordem; assinatura do
  `romQueueNotification` quebrada por parametro; `multiline-expression-wrapping` nas atribuicoes de
  `mainIntent`/`contentIntent`/`title`/`text`/`builder`/`mChannel`; `argument-list-wrapping` do `getActivity`) e o
  bloco `<file name=".../NotificationsManager.kt">` apagado a mao de `lemuroid-app/config/ktlint/baseline.xml`.
  So formatacao, sem mudanca de comportamento.
- Plano de teste: sem ajuste.

## Estado

- Feito: fix + ktlint do arquivo + baseline (commit 3085a37); `:lemuroid-app:ktlintMainSourceSetCheck` e
  `ktlintTestSourceSetCheck` BUILD SUCCESSFUL.
- 2026-11-20: o `build --bug TASK-0005` foi morto pelo Claude Code por falta de memoria na maquina (nao e falha do
  build).
- 2026-11-20: `build --bug TASK-0005` refeito: BUILD SUCCESSFUL (assembleFreeBundleDebug + testFreeBundleDebugUnitTest).
  Proximo: `enqueue-test --bug TASK-0005` (sessao de teste: Plano de teste da task, T2/T3 no aparelho).
