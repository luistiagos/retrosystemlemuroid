# [BUG] Sistemas e capas "sumidos" na version9 (design de capas antigo)

**Data:** 2026-06-19
**Status:** RESOLVIDO
**Severidade:** ALTA (percebida) — na verdade era estado de repositório, não perda de dados
**Branch:** version8 / version9

---

## Sintoma

A maioria dos sistemas novos havia "sumido" e a UI voltara ao **design de capas antigo**
(sem o esquema normal/hover). Suspeita inicial: alguma correção teria eliminado os sistemas.

## Causa-raiz

**Não houve perda de dados nem regressão de código.** O repositório **local** estava **17
commits atrás** do `origin/version8`:

- `version8`, `version9` e `HEAD` locais apontavam para `0accce9` (estado antigo, com apenas 26
  sistemas e a UI de capas antiga).
- O `origin/version8` real estava em `095f1dc`, com os 16 commits que adicionaram os ~57
  sistemas, as imagens atualizadas e o esquema **normal/hover** (`SystemLogoResolver` + imagens
  `*_hover.png`).

As correções de performance haviam sido feitas sobre essa base velha — por isso "só existiam"
26 sistemas do ponto de vista local. Os dados nunca estiveram ausentes; estavam no remoto, que
o clone local não havia puxado.

## Correção

1. Backup do trabalho local em andamento (stash + patch).
2. `git fetch` + **fast-forward** de `version9` para `origin/version8` (`095f1dc`). Como a base
   velha era ancestral, foi fast-forward limpo, sem perder commits.
3. `version9` recriada a partir da `version8` atualizada; trabalho de performance/fixes
   reaplicado sobre a base correta.

**Resultado:** 57 sistemas no manifesto (58.347 linhas), 79 entradas em `GameSystem.kt`,
imagens atualizadas + esquema normal/hover restaurados.

## Lição
Antes de diagnosticar "sumiço" de dados, conferir `git fetch` + estado local vs `origin`
(`git rev-parse HEAD origin/<branch>` e `git log local..origin/<branch>`). O que parecia
regressão era apenas um clone local desatualizado.
