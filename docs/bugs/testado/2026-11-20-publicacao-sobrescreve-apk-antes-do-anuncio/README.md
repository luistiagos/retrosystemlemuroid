<!-- gerado por `pipeline.py gerar` (pipeline-global): não edite, o gerador reescreve este arquivo -->
# 2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio

Bug em `testado/`. Doc: [2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio.md](2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio.md).

Tasks concluídas: 3/3.

| task | tipo | estado | commits |
|---|---|---|---|
| [TASK-0013](tasks/implementado/TASK-0013-harness-publicacao-bucket-falso.md) | implementacao | implementado | `30d0afc` estado: harness da publicacao e rclone falso escritos, falta rodar (bug: 2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio); `6962d0d` test(publicacao): harness offline do build-and-upload.ps1 contra bucket R2 falso; S3 PASS, S1/S2 FAIL na base (bug: 2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio); `54291d1` docs(publicacao): teste rodada 1 aprovado; S1/S2 FAIL e S3 PASS na base, duas rodadas, sem restos |
| [TASK-0014](tasks/implementado/TASK-0014-publicacao-apk-caminho-versionado.md) | implementacao | implementado | `9072322` estado: analise do dev gravada antes da edicao (bug: 2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio); `8787f69` fix(publicacao): APK sobe para caminho versionado, anuncio aponta para ele e links fixos so depois (bug: 2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio); `04b5435` docs(publicacao): teste rodada 1 aprovado; harness 5/5 na lane e 0/5 com o script da base |
| [TASK-0016](tasks/implementado/TASK-0016-aceitacao.md) | prova | implementado | `7de4484` Aceitacao de 2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio passou |

Aceitação: passou ([evidencias/aceitacao.md](evidencias/aceitacao.md)).
Fecha por: dono.
