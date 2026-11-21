# O harness tests/test-publicacao-anuncio.ps1 nao copia o publish-guard.ps1 e quebra antes de rodar o build-and-upload.ps1

- **Origem:** achado lateral do pipeline em TASK-0012 (execução 20261120-2117)

- Esperado: `powershell -NoProfile -File tests/test-publicacao-anuncio.ps1 -Cenario sucesso` passa (como na rodada da TASK-0013).
- Observado (origin/version10 @ 54291d1, 2026-11-20): o harness monta a raiz temporaria copiando so `build-and-upload.ps1` e
  `build-env.ps1` (linhas ~170-171); o `build-and-upload.ps1` faz `. (Join-Path $PROJECT_ROOT "publish-guard.ps1")` (linha 97,
  TASK-0011), entao o script.err.log traz `CommandNotFoundException ... publish-guard.ps1` e o harness morre com
  "Nao e possivel chamar um metodo em uma expressao de valor nulo".
- Evidencia: acrescentando so `Copy-Item (Join-Path $repoRoot 'publish-guard.ps1') -Destination $raiz` numa copia, o cenario
  `sucesso` passa (1/1). Correcao: essa linha no harness.
