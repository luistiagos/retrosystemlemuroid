<!-- gerado por `pipeline.py gerar` (pipeline-global): não edite, o gerador reescreve este arquivo -->
# 2026-11-20-publicacao-manual-sem-commit-versao-duplicada

Bug em `testado/`. Doc: [2026-11-20-publicacao-manual-sem-commit-versao-duplicada.md](2026-11-20-publicacao-manual-sem-commit-versao-duplicada.md).

Tasks concluídas: 3/3.

| task | tipo | estado | commits |
|---|---|---|---|
| [TASK-0011](tasks/implementado/TASK-0011-trava-versao-anunciada.md) | implementacao | implementado | `f8a72fe` fix(publicacao): trava no build-and-upload.ps1 aborta antes do bump se a versao nao passa da anunciada (bug: 2026-11-20-publicacao-manual-sem-commit-versao-duplicada); `065088e` estado: compile-check verde, pronto para teste; `24e15cb` teste rodada 1 aprovado (T1 38/41 casos, T2 4 rodadas da receita, evidencia) |
| [TASK-0012](tasks/implementado/TASK-0012-lembrete-commit-tag-publicacao.md) | implementacao | implementado | `f6a2ba4` feat(publicacao): lembrete de commit/tag/push do bump no fim do build-and-upload (bug: 2026-11-20-publicacao-manual-sem-commit-versao-duplicada); `8aec970` docs(publicacao): teste rodada 1 aprovado; lembrete visto no build-and-upload real (harness) + achado lateral do harness |
| [TASK-0015](tasks/implementado/TASK-0015-aceitacao.md) | prova | implementado | `8a0215f` Aceitacao de 2026-11-20-publicacao-manual-sem-commit-versao-duplicada passou |

Aceitação: passou ([evidencias/aceitacao.md](evidencias/aceitacao.md)).
Fecha por: dono.
