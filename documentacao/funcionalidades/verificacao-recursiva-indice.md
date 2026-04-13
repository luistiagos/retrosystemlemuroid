# Índice — Verificação Recursiva de Bugs

Data: 2026-05-31  
Status: **CONCLUÍDO** — zero bugs residuais confirmados

---

## Visão geral

Processo de verificação recursiva executado em 3 rodadas até atingir zero bugs.  
Cada rodada consistiu em: scan completo do codebase → correção de todos os bugs encontrados → re-scan.

| Rodada | Bugs encontrados | Bugs corrigidos | Bugs ignorados |
|--------|-----------------|-----------------|----------------|
| 1      | 39              | 39              | 0              |
| 2      | 5               | 4               | 1 (doc-only)   |
| 3      | 1               | 1               | 0              |
| **TOTAL** | **45**       | **44**          | **1 (doc)**    |

---

## Documentação detalhada

- [Rodada 1 — 39 bugs](correcoes-verificacao-recursiva-round1.md)
- [Rodadas 2 e 3 — 5+1 bugs](correcoes-verificacao-recursiva-rounds2-3.md)

---

## Categorias cobertas

| Categoria | Exemplos |
|-----------|---------|
| Coroutines | CancellationException engolida, GlobalScope sem error handling, flatMapMerge sem concurrency limit |
| Resource leaks | InputStream/ZipInputStream/ParcelFileDescriptor não fechados |
| Thread safety | TOCTOU em LruCache, operações Room não-suspend |
| WorkManager | setForeground() sem proteção (Android 12+), Worker sempre returning success |
| Segurança | URL HTTP→HTTPS, FTS query sem sanitização, sign-extension em byte parsing |
| UI/UX | Race condition em loadingState, return silencioso sem feedback ao usuário |
| Kotlin moderno | sumBy→sumOf, values()→entries, toLowerCase→lowercase, cast unsafe |
| ELF parsing | Endianness check ausente, byte sign-extension |
| Deprecations | BiosManager internal call, Timber args swapped |
