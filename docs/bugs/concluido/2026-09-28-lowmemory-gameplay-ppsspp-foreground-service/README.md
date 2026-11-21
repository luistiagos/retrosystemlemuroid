<!-- gerado por `pipeline.py gerar` (pipeline-global): não edite, o gerador reescreve este arquivo -->
# 2026-09-28-lowmemory-gameplay-ppsspp-foreground-service

Bug em `concluido/`. Doc: [2026-09-28-lowmemory-gameplay-ppsspp-foreground-service.md](2026-09-28-lowmemory-gameplay-ppsspp-foreground-service.md).

Tasks concluídas: 3/3.

| task | tipo | estado | commits |
|---|---|---|---|
| [TASK-0007](tasks/no-ar/TASK-0007-lowmemory-fgs-leitura.md) | implementacao | no-ar | `3299e5d` feat(telemetria): lowmemory do processo principal diz o trabalho em primeiro plano (fgs=); `e9a6659` estado: build e ktlint verdes, pronto para teste; `0f50dd1` teste rodada 1 aprovado: offline verde, T3/T4 no emulador; T2 sem sinal ate a TASK-0008 |
| [TASK-0008](tasks/no-ar/TASK-0008-lowmemory-fgs-marcadores.md) | implementacao | no-ar | `ba24f6a` fix(telemetria): servico de download e Workers em foreground marcam o fgs (bug: 2026-09-28-lowmemory-gameplay-ppsspp-foreground-service); `085f74c` estado: build e ktlint verdes, pronto para teste; `8093fa8` teste rodada 1 aprovado: marcador download/library no aparelho, controle positivo na base sem marcador |
| [TASK-0010](tasks/no-ar/TASK-0010-aceitacao.md) | prova | no-ar | `5acc76a` Aceitacao de 2026-09-28-lowmemory-gameplay-ppsspp-foreground-service passou; `3a598db` Aceitacao de 2026-09-28-lowmemory-gameplay-ppsspp-foreground-service passou (logcat dos PIDs do teste) |

Aceitação: passou ([evidencias/aceitacao.md](evidencias/aceitacao.md)).
Fecha por: prova.
