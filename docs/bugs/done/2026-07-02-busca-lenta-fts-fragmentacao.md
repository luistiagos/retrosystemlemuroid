# [BUG] Busca de títulos lenta — índice FTS4 fragmentado

**Data:** 2026-07-02
**Status:** CORRIGIDO
**Severidade:** MÉDIA/ALTA — busca praticamente inutilizável ("resultados demoram") e piora com o uso
**Branch:** version9

---

## Sintoma

Ao buscar um título, a **digitação é fluida**, mas a **lista de resultados demora muito** a
aparecer depois que o usuário para de digitar. Persistia mesmo após o `debounce(400ms)` do
`SearchViewModel` já estar presente (re-adicionado em 2026-07-01) — ou seja, o debounce **não
era** a causa desta vez. Tende a piorar quanto mais o app é usado.

## Causa-raiz

Índice **FTS4 fragmentado em muitos segmentos**.

A tabela `fts_games` usa content externo (`content="games"`) e triggers que mantêm o índice:

```sql
CREATE TRIGGER games_bu BEFORE UPDATE ON games ... DELETE FROM fts_games WHERE docid=old.id;
CREATE TRIGGER games_au AFTER  UPDATE ON games ... INSERT INTO fts_games(docid, title) ...;
```

Esses triggers disparam em **qualquer** UPDATE na tabela `games` — não só quando o `title`
muda. O `ManifestQuickLoader.load()` faz duas escritas em massa que os disparam:

1. **Rewrite da URI sentinela** (`rewritePrebuiltUris`) no **primeiro boot**: um único UPDATE
   que toca **~30 mil linhas** (`file:///lemuroid_prebuilt/...` → `romsDir` real). Cada linha
   → `games_bu` (DELETE do fts) + `games_au` (INSERT no fts).
2. **Refresh dos campos do manifest** (`updateManifestFieldsWithTitle`) a cada bump de
   `MANIFEST_SCHEMA_VERSION`: milhares de UPDATEs.

Resultado: dezenas de milhares de delete+insert no índice FTS4 → o índice fica espalhado em
muitos segmentos nas shadow tables (`%_segdir`/`%_segments`). O FTS4 **não** faz merge
automático (sem `automerge`), então cada `MATCH` precisa fazer merge-scan de todos os
segmentos — especialmente caro em queries de **prefixo** (`sanitizeFtsQuery` acrescenta `*` a
cada termo). Como o rewrite/refresh nunca era seguido de um `optimize`, o índice ficava
fragmentado **permanentemente** → busca lenta que só piora.

## Correção

Adicionado o comando de defragmentação do FTS4 — `INSERT INTO fts_games(fts_games)
VALUES('optimize')` — que colapsa todos os segmentos em um só. Três pontos:

1. **Build-time** — [PrebuiltDbGenerator.kt](../../../buildSrc/src/main/kotlin/PrebuiltDbGenerator.kt)
   (`populateFtsBulk`): `optimize` logo após o bulk populate, para o DB embarcado já sair
   com um único segmento (baseline ótimo).
2. **Runtime, encapsulado** — [GameSearchDao.kt](../../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/GameSearchDao.kt):
   novo método `optimize(db: SupportSQLiteDatabase)` (mantém a SQL do FTS junto da definição
   da tabela). Executado via `execSQL` — **não** cai no pitfall de PRAGMA do Android 14+
   porque o comando `optimize` não retorna resultset.
3. **Runtime, orquestração** — [ManifestQuickLoader.kt](../../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/catalog/ManifestQuickLoader.kt):
   - Novo `FTS_MAINTENANCE_VERSION` (=1) + pref `KEY_FTS_OPTIMIZED`.
   - `maybeOptimizeFtsIndex(prefs)` — roda o defrag **uma vez** (gated por versão) nos
     caminhos fast-return/fast-skip, para consertar installs **já fragmentados** por um
     rewrite de primeiro boot anterior.
   - `optimizeFtsIndex(prefs)` — **incondicional** no fim do caminho pesado (que acabou de
     rodar rewrite/refresh e fragmentou o índice).
   - Sempre chamado **depois** de `_catalogReady.value = true`, para não atrasar a Home no
     primeiro boot (o defrag roda em background, off-UI, no `Dispatchers.IO` do loader).

Sem bump de versão do Room / identity hash — os triggers em si não foram alterados (isso
exigiria migração + regeneração do prebuilt). O `optimize` limpa a fragmentação de forma
durável: para o usuário atual, o rewrite de 30k linhas já aconteceu (boots seguintes reescrevem
0 linhas), então uma única passada de `optimize` resolve.

## Validação

- `:buildSrc:compileKotlin` — **BUILD SUCCESSFUL**.
- `:retrograde-app-shared:compileDebugKotlin` — **BUILD SUCCESSFUL** (só warnings de
  deprecation pré-existentes).
- Verificação pendente no dispositivo: buscar um título e confirmar que os resultados aparecem
  logo após o debounce (sem o atraso longo).

## Lição

- Índice **FTS4 com content externo + triggers em `UPDATE` genérico** fragmenta a cada escrita
  em massa na tabela base. Toda operação que toca muitas linhas de `games` (rewrite de URI,
  refresh de campos, scans grandes) **deve** ser seguida de `INSERT INTO fts(fts)
  VALUES('optimize')`, senão o `MATCH` degrada permanentemente.
- "Busca lenta" tem **duas** causas independentes neste app: (a) perda do `debounce` no
  `SearchViewModel` (ver memória `search-debounce-recurring-regression`); (b) esta —
  fragmentação do índice FTS4. Descartar (a) antes de investigar (b).
- Idealmente os triggers deveriam disparar só em `AFTER UPDATE OF title` (não em qualquer
  update), evitando a fragmentação na origem — mas isso é mudança de schema (migração +
  regeneração do prebuilt). Fica como melhoria futura; o `optimize` já neutraliza o sintoma.
