---
tipo: analise
demandas: [2026-11-25-resolucao-interna-sistemas-3d]
depende_de: [TASK-0017]
---

# TASK-0018 — Capturar, no aparelho, os valores que cada core declara e se aplica na hora

- **Criterio:** EARS-4, EARS-5
- **Toca:** `docs/features/aberto/2026-11-25-resolucao-interna-sistemas-3d/design.md` — tabela "Valores declarados
  pelos cores". Nenhum código.
- **Como:** com o APK da TASK-0017 no moto g86, abrir um jogo de PS1, GameCube, DS, Saturn, 3DO e N64; em Opções do
  core, anotar a lista crua de cada opção de resolução, na ordem (é a `description` que o core reporta; a leitura
  estática do `.so` não a dá com segurança). Trocar para o 2º valor e ver, por print, se a nitidez muda na hora ou só
  depois de reiniciar o jogo. Se uma opção **não aparecer**, o core não reporta a chave: registrar e voltar ao dono
  antes da TASK-0019.
- **Testes novos:** -- (análise; a lista capturada vira o dado do teste `valoresContidosNosDeclaradosPeloCore` da TASK-0019)
- **Testes existentes:** --
- **Pronto local:** tabela do design preenchida, com prints em `evidencias/` e a linha de logcat
  `Updating core variable` de cada troca
- **Publicacao:** n/a — análise
- **Rollback:** n/a

#### Execucao
- Build: --
- Testes executados: --
- E2E: --
- Desvios do design: --
