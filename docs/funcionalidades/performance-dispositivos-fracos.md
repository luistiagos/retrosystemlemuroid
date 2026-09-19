# Performance e Memória em Dispositivos Fracos (TV box / Smart TV)

**Data:** 2026-06-13
**Tipo:** Otimização de performance / memória
**Status:** implementado
**Alvo:** aparelhos de 1–2 GB de RAM (TV box, Smart TV, tablets antigos)

---

## 1. Contexto

O catálogo embedded tem **~57 sistemas** e **~58 mil entradas** no `catalog_manifest.txt`.
Em aparelhos fracos (TV box / Smart TV com 1–2 GB de RAM), qualquer estrutura que carregue
tudo de uma vez ou retenha memória de forma proporcional ao catálogo causa **OOM** (kill pelo
Low Memory Killer do Android) ou travamentos.

Este documento é a **fonte única** sobre o conjunto de otimizações de performance/memória
para esses aparelhos. Cobre tanto a infraestrutura que já existia quanto as correções da
rodada de **2026-06-13**.

> **Princípio de design:** o **pico de memória deve ser constante**, não proporcional ao
> tamanho do catálogo. Toda estrutura que tocava os 58k jogos de uma vez (carga, mapa,
> páginas) é streaming/limitada. A degradação em devices fracos é graciosa: shader mais
> barato, bitmaps menores e caches que encolhem sob pressão — **sem afetar aparelhos normais**.

---

## 2. Infraestrutura já existente (base do version8)

Antes da rodada de 2026-06-13, o app já tinha um conjunto sólido de otimizações. Documentadas
aqui para dar o quadro completo:

| Área | Mecanismo | Onde |
|------|-----------|------|
| **Banco pré-construído** | `retrograde-prebuilt.db` é **gerado em build-time** a partir do `catalog_manifest.txt` (`PrebuiltDbGenerator` em `buildSrc`) e empacotado em assets. No 1º launch o Room abre o DB via `createFromAsset` — **sem parsear 58k linhas no startup**. | `lemuroid-app/build.gradle.kts`, `buildSrc/.../PrebuiltDbGenerator.kt` |
| **Fast-skip do loader** | `ManifestQuickLoader` faz `countAll() >= expectedSize` e pula a carga inteira quando o DB já está populado. Só reprocessa em bump de `MANIFEST_SCHEMA_VERSION`. | `ManifestQuickLoader.kt` |
| **PRAGMA tuning** | `onOpen`: `synchronous=NORMAL`, `cache_size=-32000` (32 MB), `mmap_size=256 MB`, `temp_store=MEMORY`. | `LemuroidApplicationModule.retrogradeDb` |
| **WAL** | `JournalMode.WRITE_AHEAD_LOGGING` — leituras não bloqueiam escritas. | idem |
| **Pre-warm do DB** | Thread em background abre o `writableDatabase` para tirar o custo do open do caminho da UI. | idem |
| **Índices compostos** | Índices em `games` para as queries de catálogo/recentes/favoritos. | `GameDao.kt`, migrações |
| **Cache de imagem reduzido em low-RAM** | Coil: memory/disk cache caem para 0.08 / 0.10 em low-RAM (vs 0.20). | `CoverUtils.kt` |
| **Filtro de sistemas por RAM** | `HeavySystemFilter` oculta sistemas pesados conforme o tier do device. | `HeavySystemFilter.kt` |

### 2.1 Filtro de sistemas por RAM (`HeavySystemFilter`)

| Tier | RAM | Sistemas ocultos |
|------|-----|-----------------|
| `NORMAL` | > 2 GB | nenhum |
| `WEAK` | 1–2 GB | PSP, 3DS, GameCube |
| `ULTRA_WEAK` | ≤ 1 GB | + NDS, N64, PSX, DOS, Sega CD, Dreamcast, 3DO, Saturn, Amiga (todas), PC-FX, Atari ST |

`deviceTier()` prioriza a RAM física: **> 2 GB é sempre `NORMAL`**, mesmo que o OEM marque
`isLowRamDevice` (comum em TV boxes que mentem a flag).

---

## 3. Correções da rodada 2026-06-13

Seis otimizações **complementares** (sem sobreposição com o item 2). Todas compilam
(`assembleFreeBundleDebug` OK) e só afetam aparelhos fracos — em devices normais o
comportamento é idêntico ao anterior.

### 3.1 Paginação com `maxSize` — limita páginas retidas

**Arquivo:** `retrograde-util/.../paging/PagingUtils.kt`

**Problema:** `buildFlowPaging` usava `PagingConfig(pageSize)` sem `maxSize`. Ao rolar um
sistema grande (milhares de jogos), o Paging acumulava **todas** as páginas já carregadas em
memória até o LMK matar o app.

**Correção:** `maxSize = pageSize * 6`. O Paging exige
`maxSize >= pageSize + 2 * prefetchDistance` (= 3× pageSize); 6× dá folga de scroll-back.
Vale para **todos** os callers (catálogo, favoritos, busca, TV) por ser centralizado.

```kotlin
val config = PagingConfig(pageSize = pageSize, maxSize = pageSize * 6)
```

### 3.2 Chaves anti-colisão no Compose — corrige crash

**Arquivos:** `GamesScreen.kt`, `SearchScreen.kt`

**Problema:** `key = { games[it]?.id ?: it }` podia gerar **chave duplicada** no `LazyColumn`
quando o `id` de um item carregado coincidisse com o **índice** de um placeholder — cenário
tornado frequente pelo `maxSize` (páginas voltam a placeholders no scroll-back) → crash
`IllegalArgumentException: Key was already used`.

**Correção:** namespaces distintos para id carregado e índice de placeholder (mesmo padrão
que `FavoritesScreen.kt` já usava):

```kotlin
key = { games[it]?.id?.let { id -> "id_$id" } ?: "idx_$it" }
```

> O lado TV usa `PagingDataAdapter` do leanback (trata placeholders internamente) e o
> `HomeScreen` usa listas comuns — ambos já seguros.

### 3.3 Covers em RGB_565 + detecção de low-RAM por tier

**Arquivo:** `lemuroid-app/.../shared/covers/CoverUtils.kt`

**Problema:** (a) covers decodificadas em `ARGB_8888` (4 bytes/pixel); (b) `isLowRamDevice`
dependia só de `am.isLowRamDevice`, flag que muitos TV boxes de 1–2 GB **nunca setam**.

**Correção:**
- `isLowRamDevice` agora combina `am.isLowRamDevice` **com** `HeavySystemFilter.deviceTier`
  (qualquer tier ≠ NORMAL conta como low-RAM).
- Em low-RAM: `bitmapConfig(RGB_565)` + `allowRgb565(true)`. Covers são artwork opaco →
  RGB_565 (2 bytes/pixel) **corta a memória de bitmap pela metade** sem perda visível.

### 3.4 Ciclo de memória isolado por processo + trim agressivo

**Arquivo:** `lemuroid-app/.../LemuroidApplication.kt`

**Problema:** (a) o pré-aquecimento do Coil rodava em **todos** os processos, inclusive o
`:game` (que nunca exibe cover e precisa de cada MB para o core); (b) `onTrimMemory` só
limpava cache em `TRIM_MEMORY_MODERATE`+, tarde demais; sem `onLowMemory()`.

**Correção:**
- Pré-aquecimento do Coil **só no processo principal** (`isMainProcess()`).
- `onTrimMemory` reescrito: limpa o cache de imagem em `TRIM_MEMORY_UI_HIDDEN` (app em
  background) **e** em `TRIM_MEMORY_RUNNING_LOW` (foreground sob pressão — principal causa de
  kill em TV box). Sai cedo se não for o processo principal (não constrói `ImageLoader` no
  `:game` só para limpá-lo).
- Adicionado override de `onLowMemory()`.

### 3.5 Shader barato em device fraco (modo auto)

**Arquivo:** `lemuroid-app/.../shared/game/ShaderChooser.kt`

**Problema:** no modo "auto" (padrão), o default por sistema usa **CRT** para muitos sistemas
— caro na GPU de TV box e causa frame drops na emulação.

**Correção:** em device fraco (`HeavySystemFilter.isWeakDevice`), o auto cai para
`ShaderConfig.Sharp` (pass-through barato). Filtros escolhidos **explicitamente** pelo usuário
(`crt`/`lcd`/`smooth`/`sharp`) e o **HD mode** continuam totalmente respeitados.

### 3.6 `largeHeap`

**Arquivo:** `lemuroid-app/src/main/AndroidManifest.xml`

`android:largeHeap="true"` — rede de segurança para o processo principal lidar com o banco de
58k jogos + thumbnails sem estourar o heap gerenciado.

---

## 3-bis. Segunda rodada (2026-06-15) — ganhos de RAM/GPU por tier

Vinda da análise em [`performance-oportunidades.md`](performance-oportunidades.md), itens
P1.1 / P2.1 / P2.2. Baixo risco, impacto direto em memória.

### 3.7 PRAGMA do SQLite escalonado por tier

**Arquivo:** `lemuroid-app/.../LemuroidApplicationModule.kt` (`retrogradeDb`)

**Problema:** o `tuningCallback` aplicava valores **fixos** para todo aparelho
(`cache_size = -32000` = 32 MB de page cache committed; `mmap_size = 256 MB`). Em 1 GB isso
rouba RAM da UI/emulador, e com **minSdk 21 + ABIs 32-bit** (TV box armeabi-v7a) um mmap de
256 MB pode falhar/fragmentar o address space.

**Correção:** valores escalonados via `HeavySystemFilter.deviceTier`:

| PRAGMA | NORMAL | WEAK | ULTRA_WEAK |
|--------|--------|------|------------|
| `cache_size` | -32000 (32 MB) | -8000 (8 MB) | -4000 (4 MB) |
| `mmap_size` | 256 MB | 64 MB | 0 (off) |
| `temp_store` | MEMORY | MEMORY | DEFAULT |
| `synchronous` | NORMAL | NORMAL | NORMAL |

Libera ~24–28 MB em ULTRA_WEAK e remove o risco de mmap em 32-bit. O DB é prebuilt e
read-mostly, então o cache menor só custa um pouco mais de I/O de leitura.

### 3.8 Decode de cover menor em low-RAM

**Arquivo:** `lemuroid-app/.../shared/compose/ui/LemuroidGameImage.kt`

Em low-RAM o request decodifica `.size(256)` em vez de `.size(400)` — ~30–40% menos bytes por
cover, imperceptível no tamanho da célula do grid (`GridCells.Adaptive(140–144.dp)`).
`CoverUtils.isLowRamDevice` virou público para o Composable reusar o mesmo critério (calculado
uma vez via `remember`).

### 3.9 `crossfade` desligado em low-RAM

**Arquivos:** `lemuroid-app/.../shared/covers/CoverUtils.kt` (nível do `ImageLoader`) e
`LemuroidGameImage.kt` (nível do request).

O crossfade mantém **dois** bitmaps vivos durante a animação e faz alpha-blend a cada item que
entra na tela — custo de GPU/memória ao rolar em TV box. Desligado em low-RAM (`crossfade(!lowRam)`);
é puramente estético. `LemuroidSmallGameImage` herda o default do `ImageLoader`, então também
se beneficia sem mudança.

---

## 4. O que foi deliberadamente NÃO aplicado

**Carga do manifesto em chunks / `SoftReference` no `CatalogCoverProvider`.**
Numa base anterior fazia sentido, mas o version8 já resolve isso melhor com o **banco
pré-construído em build-time** + fast-skip: o `catalog_manifest.txt` de 58k linhas quase nunca
é parseado no startup. Trocar o `lazy` do `CatalogCoverProvider` por `SoftReference` traria
**risco de re-parsear 58k linhas no meio de um scan** (quando o GC coletasse o mapa entre
chamadas de `getCoverUrl`). O ganho seria marginal e o risco real — por isso ficou de fora.

---

## 5. Resumo dos arquivos alterados

**Rodada 2026-06-13:**

| Arquivo | Mudança |
|---------|---------|
| `retrograde-util/.../paging/PagingUtils.kt` | `maxSize = pageSize * 6` |
| `lemuroid-app/.../feature/games/GamesScreen.kt` | chave anti-colisão |
| `lemuroid-app/.../feature/search/SearchScreen.kt` | chave anti-colisão |
| `lemuroid-app/.../shared/covers/CoverUtils.kt` | RGB_565 + low-RAM via `HeavySystemFilter` |
| `lemuroid-app/.../LemuroidApplication.kt` | trim/pre-warm isolado por processo + `onLowMemory` |
| `lemuroid-app/.../shared/game/ShaderChooser.kt` | auto → `Sharp` em device fraco |
| `lemuroid-app/src/main/AndroidManifest.xml` | `largeHeap="true"` |

**Rodada 2026-06-15 (P1.1 / P2.1 / P2.2):**

| Arquivo | Mudança |
|---------|---------|
| `lemuroid-app/.../LemuroidApplicationModule.kt` | PRAGMA SQLite escalonado por tier |
| `lemuroid-app/.../shared/covers/CoverUtils.kt` | `isLowRamDevice` público + `crossfade(!lowRam)` |
| `lemuroid-app/.../shared/compose/ui/LemuroidGameImage.kt` | decode `.size(256)` + crossfade off em low-RAM |

---

## 6. Oportunidades futuras

Ver a análise de oportunidades adicionais em
[`performance-oportunidades.md`](performance-oportunidades.md).
