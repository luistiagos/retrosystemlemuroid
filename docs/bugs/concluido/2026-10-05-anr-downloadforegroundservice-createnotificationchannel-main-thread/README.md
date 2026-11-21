<!-- gerado por `pipeline.py gerar` (pipeline-global): não edite, o gerador reescreve este arquivo -->
# 2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread

Bug em `concluido/`. Doc: [2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread.md](2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread.md).

Tasks concluídas: 3/3.

| task | tipo | estado | commits |
|---|---|---|---|
| [TASK-0005](tasks/no-ar/TASK-0005-canal-notificacao-uma-vez-por-processo.md) | implementacao | no-ar | `559c15b` fix(notificacoes): criar canais de notificacao uma vez por processo (bug: 2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread); `59e84cf` estado: build interrompido por falta de memoria, falta build e enqueue-test; `13b23b1` estado: build verde, pronto para teste; `caa2229` teste rodada 1 aprovado: T2 com controle positivo (9 -> 0 createNotificationChannels/30 s), T3 e T4 no emulador; `4b66511` evidencias do teste na pasta da demanda (evidencias/TASK-0005), fora de tasks/ |
| [TASK-0006](tasks/no-ar/TASK-0006-notificacao-download-fora-da-main-com-throttle.md) | implementacao | no-ar | `240a0dc` estado: analise da implementacao registrada (bug: 2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread); `7afc802` fix(download): notificacao da fila so quando muda, ate 1/s, montada e postada no IO (bug: 2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread); `b8cb735` estado: codigo commitado, build encerrado por falta de memoria da maquina (bug: 2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread); `1ab01e2` estado: build, ktlint e lint verdes; 7 testes novos passando (bug: 2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread); `d18e538` teste rodada 1 aprovado: T2 com controle positivo, T3 e T4 no emulador (bug: 2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread) |
| [TASK-0009](tasks/no-ar/TASK-0009-aceitacao.md) | prova | no-ar | `e3e2d95` Aceitacao de 2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread passou |

Aceitação: passou ([evidencias/aceitacao.md](evidencias/aceitacao.md)).
Fecha por: prova.
