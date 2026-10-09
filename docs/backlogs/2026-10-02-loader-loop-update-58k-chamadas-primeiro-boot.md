# [BACKLOG] Primeiro boot: o loop de `updateManifestFieldsWithTitle` custa ~15 s (58 mil chamadas ao Room)

**Data:** 2026-10-02
**Origem:** custo residual de
`docs/bugs/done/2026-10-02-prebuilt-fileuri-sem-encoding-recria-catalogo.md`

## Situação

Toda instalação nova faz a passada completa do `ManifestQuickLoader.load()` (`loadedSchema = -1`, os
fast-paths não valem). Com o prebuilt já correto, essa passada não muda nada no banco — e ainda assim
leva ~25 s no AVD `lemu_api25_2gb`. Rodada instrumentada (instalação limpa, Timber temporário):

| etapa | ms |
|---|---|
| montar `games` (58.156 `Game`) | 1.434 |
| `insertIfNotExists(games)` (todos ignorados) | 4.080 |
| **loop `updateManifestFieldsWithTitle` (uma chamada `suspend` por linha existente)** | **14.588** |
| `selectAll()` da limpeza de órfãos | 1.944 |
| filtro + delete dos órfãos | 495 |

O `catalogReady` só vira `true` no fim disso.

## O que já foi descartado

**SQL condicional** (`… AND (title != :title OR popularityIndex != … OR isRepresentative != …)`, para
não disparar os triggers FTS em linha que não muda): 3 rodadas por variante, sem diferença mensurável
(com: 24,1 / 22,8 / 23,6 s; sem: 24,2 / 24,5 / 19,7 s). O custo é por chamada, não pela escrita.

## Proposta

Comparar em memória e só chamar o UPDATE para linha que muda:

1. Ler as linhas existentes uma vez (`fileUri, title, popularityIndex, isRepresentative` — query
   enxuta, não `selectAll`), num `HashMap` por `fileUri`.
2. No laço de `existingGames`, pular quando os três campos já batem. Instalação nova a partir do
   prebuilt → zero chamadas; bump de schema → só as linhas que mudaram.
3. Reaproveitar a mesma leitura na limpeza de órfãos, que hoje faz um `selectAll()` à parte.

Prova: mesma instrumentação, ≥3 rodadas com `pm clear` antes e depois; o loop deve cair de ~14,6 s
para perto de zero. Validar também um bump de `MANIFEST_SCHEMA_VERSION` numa instalação existente
(os títulos alterados têm que chegar ao banco).

> Medir sempre ≥3 rodadas: no emulador a mesma variante deu 13,8 s e 23,6 s.
