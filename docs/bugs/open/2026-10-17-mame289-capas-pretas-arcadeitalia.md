# [BUG] Jogos de arcade (MAME 0.289) com capa preta ou imagem genérica

**Data:** 2026-10-17
**Status:** aberto — análise feita, correção não iniciada (decisões do dono pendentes, ver "Correção")
**Severidade:** baixa (visual; o jogo abre normalmente)
**Complexidade:** média — manifest + `GameDao` + `MANIFEST_SCHEMA_VERSION`; ver item 2 da causa
**Branch:** `version10`
**Origem:** observação da verificação 2 do redesenho (busca da TV, 2026-10-17)

## Sintoma

Na busca da TV por "1010", 3 dos 6 cartões de MAME (`mame289`) aparecem com a área da capa **toda preta**. Um quarto
("Hollywood Dreams") mostra uma arte genérica de MAME que não é do jogo. Nos dois casos, o esperado seria a capa
gerada (`GeneratedCover`/`TVCovers`), que é o que o app mostra quando não há capa.

## Evidência

- Banco do `lemu_tv_api25`: todos os jogos `mame289` têm `coverFrontUrl =
  https://adb.arcadeitalia.net/?mame=<rom>&type=title&resize=0` (a tela-título do jogo, não uma caixa).
- `curl` das capas: `csd`, `di4ever`, `indgldcm` → HTTP 200, `image/png`, ~1 KB, **640×480 com todos os pixels 0**.
  `holdrma6` → 140×105, 31 KB, md5 `d252a22533bc2ebece7f45a82278f641` (a arte genérica).
- Manifest: as **14.268** linhas `mame289/` usam essa mesma fonte (`catalog_manifest.txt` = cópia em `assets/`,
  `cmp` igual).
- Amostra aleatória de 400 (seed 42, script `sample_covers.py` no scratchpad da sessão): **357 imagem real, 33 preta
  (8%), 10 genérica (2,5%, sempre o mesmo md5)**. Projeção: ~1.200 pretas + ~350 genéricas.

## Causa-raiz

1. **Dado.** O arcadeitalia devolve uma tela-título capturada em branco para várias máquinas (caça-níqueis
   Aristocrat, sistemas sem vídeo na tela de título), e um placeholder próprio quando não tem imagem. Para o app, os
   dois são imagens válidas, então não há fallback. O lote veio no `b631880` (MAME 0.289).
2. **Propagação (achado lateral, mais grave).** Mudar a capa no manifest **não chega às instalações existentes**:
   - `ManifestQuickLoader.load()` grava `coverFrontUrl` só no `INSERT OR IGNORE` (linha ~423);
   - linha que já existe recebe `GameDao.updateManifestFieldsWithTitle`, que faz `UPDATE games SET title,
     popularityIndex, isRepresentative` — **sem capa**;
   - não há migração `loadedSchema < N` que mexa em capa (as que existem: `< 9`, `< 23`, `< 27`);
   - os outros escritores de `coverFrontUrl` são o scan da biblioteca (`LemuroidLibrary`, só arquivo em disco, via
     metadado) e o `SaveQueueManager` (fallback de `Game` em memória).
   Consequência: o passe de capas da **v31** (+4.711 capas, 3.577 capas erradas apagadas) só valeu para instalação
   nova (prebuilt) e para linhas inseridas depois. Quem já tinha o app segue com as capas antigas — inclusive as
   3.577 erradas. Não verificado em aparelho; a prova é o código acima.

## Hipóteses descartadas

- **Defeito do `TVCovers`/Coil** (imagem não carregou): falso. O download funciona e a imagem é preta de fato.
- **Problema só do AVD:** falso. É o conteúdo servido pelo arcadeitalia.

## Correção (proposta — aguarda decisões do dono)

1. **Dado:** varrer as 14.268 URLs (`GET` com 12 threads, ~20–40 min; respeitar o servidor) e **esvaziar** o campo 3
   das linhas pretas (todos os pixels 0) e das genéricas (md5 `d252a225…`), nos dois manifests. Esvaziar, nunca
   substituir por palpite (regra do passe v31).
2. **Propagação:** sem isto o passo 1 só vale para instalação nova. Opção recomendada:
   - `updateManifestFieldsWithTitle` passa a gravar `coverFrontUrl = :coverUrl` **quando o manifest tem capa**
     (não sobrescreve com `NULL` uma capa que o scan do LibretroDB tenha achado para ROM baixada);
   - o apagamento vai por migração única `loadedSchema < 42`: `UPDATE games SET coverFrontUrl = NULL WHERE
     coverFrontUrl = :url` para a lista de URLs esvaziadas no passo 1 (e, se o dono quiser, as 3.577 da v31).
   - Efeito colateral a aceitar: as +4.711 capas da v31 chegam de uma vez a quem já tinha o app.
3. Bump de `MANIFEST_SCHEMA_VERSION` (41 → 42) e linha na tabela do `CLAUDE.md`.
4. **Coordenar com a sessão de arcade/NAOMI**, dona do manifest de `mame289` nesta semana: a edição do manifest
   colide com ela.

**Decisões do dono:** (a) aprovar o desenho do item 2; (b) incluir ou não a limpeza retroativa das 3.577 capas da v31;
(c) fazer agora ou depois que a sessão de arcade fechar.

## Validação (a fazer)

- Script: contagem antes/depois de linhas `mame289` com capa; nenhuma URL preta ou genérica no manifest.
- `lemu_tv_api25` com o app já instalado (sem limpar dados): depois do update, os 3 cartões da busca "1010" mostram a
  capa gerada, e um jogo com capa nova da v31 passa a mostrá-la.
- Instalação nova: idem, pelo prebuilt.

## Lição

(a preencher)
