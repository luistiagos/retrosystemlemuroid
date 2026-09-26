# [BUG] ROMs comerciais (75 MB) staged para commit em `tmp/test_roms/`

**Data:** 2026-09-25
**Status:** ✅ **Resolvido**
**Severidade:** Alta (se entrar num commit e for enviado ao remoto, fica para sempre no histórico:
75 MB em todo clone e ROMs comerciais publicadas)
**Branch:** version9
**Origem:** investigação do working tree após [[2026-09-03-investigacao-sigsegv-glthread-pc-desmapeado]]

---

## Sintoma

`git status` mostra, no índice:

```
A  tmp/test_roms/kof97.zip      28 MB   The King of Fighters '97 (Neo Geo)
A  tmp/test_roms/msh.zip        20 MB   Marvel Super Heroes (CPS-2)
A  tmp/test_roms/neogeo.zip      2 MB   BIOS Neo Geo
A  tmp/test_roms/rbffspec.zip   25 MB   Real Bout Fatal Fury Special (Neo Geo)
A  tmp/kof97_gameplay.png
A  tmp/msh_gameplay.png
A  tmp/rbffspec_gameplay.png
A  tmp/ui.xml
```

O `git add` aconteceu em 2026-09-25, **enquanto outra investigação rodava no mesmo working
tree**: entre dois `git status` seguidos, os arquivos passaram de `??` para `A`. Não era uma
sessão do Claude Code (`ListAgents` não listava nenhuma outra no Lemuroid); a hipótese é o
agente do IDE Antigravity, cujos processos Java estavam abertos, ou um `git add` manual.

## Causa-raiz

As ROMs foram baixadas do repositório remoto de ROMs (`luistiagos/fbneo` no Hugging Face) para
validar em aparelho a correção de [[2026-09-22-fbneo-retro-run-segv-gameplay]]. Elas ficaram em
`tmp/`, e esse diretório **é versionado de propósito**. O comentário no `.gitignore` diz: "O
resto de `tmp/` continua versionado de propósito (cores do buildbot, aarcheck, etc.)". Nenhuma
regra cobria ROMs. Um `git add tmp/` (ou `git add -A`) pega tudo.

Os screenshots e o `ui.xml` seguem a prática já existente: `tmp/` versiona dezenas de `v-*.png`
e `window*.xml` de validações anteriores. **O problema eram só as ROMs.**

## Correção

Confirmado antes de agir: nenhum commit novo apareceu entre a abertura do bug e a correção
(`git log` sem entradas de ROM), então o stage ainda estava solto no índice de outro ator, não
no meio de um commit em andamento.

1. `git restore --staged tmp/test_roms/` — tirou as 4 ROMs do índice sem apagar do disco
   (voltaram a `??`).
2. Acrescentada a regra `tmp/test_roms/` ao `.gitignore`, com comentário próprio: diferente do
   resto de `tmp/` (que é ignorado caso a caso), aqui a exclusão é por padrão — qualquer coisa
   nova em `test_roms/` é ROM, nunca material versionável.
3. Screenshots (`kof97_gameplay.png`, `msh_gameplay.png`, `rbffspec_gameplay.png`) e `ui.xml`
   **permaneceram staged**: seguem a prática já estabelecida de versionar material de validação
   em `tmp/` e não são o problema deste bug.

## Validação

- `git status --short tmp/test_roms` sem saída (nem `A`, nem `??`). ✅ confirmado.
- `git check-ignore -v tmp/test_roms/kof97.zip` aponta para a nova regra
  (`.gitignore:78:tmp/test_roms/`). ✅ confirmado.
- `git log --all --format=%h -- tmp/test_roms` vazio, confirmando que nunca foram commitadas.
  ✅ confirmado — segue vazio.

## Lição

Diretório versionado "de propósito" precisa de lista explícita do que **não** entra. `tmp/` é
onde cai todo o material de validação em aparelho, e ROM é o insumo natural dessa validação. A
regra certa é ignorar por padrão o que não pode ser publicado, e não confiar em quem faz o
`git add`.
