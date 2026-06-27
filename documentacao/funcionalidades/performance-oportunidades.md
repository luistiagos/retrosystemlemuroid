# Análise de Oportunidades de Performance (TV box / Smart TV)

**Data:** 2026-06-13
**Tipo:** Análise / backlog de performance
**Status:** análise (nenhuma alteração de código aplicada nesta análise)
**Base:** version9 (= version8 + correções de 2026-06-13, ver
[`performance-dispositivos-fracos.md`](performance-dispositivos-fracos.md))

---

## Como ler

Cada oportunidade tem **Impacto** (ganho esperado em aparelho fraco) e **Risco**
(chance de regressão / esforço). Ordenado por valor (impacto alto × risco baixo primeiro).

> Nada aqui foi aplicado ainda — é o mapa do que ainda dá para melhorar **além** do que já
> está implementado.

---

> **Atualização 2026-06-15:** os itens **1.1, 2.1 e 2.2 já foram implementados** — ver
> [`performance-dispositivos-fracos.md`](performance-dispositivos-fracos.md) §3.7–3.9.
> Permanecem aqui (marcados ✅) como registro; os demais seguem como backlog.

## P1 — Alto impacto, baixo risco

### 1.1 PRAGMA do SQLite por tier de device  ✅ IMPLEMENTADO (2026-06-15)

**Hoje:** o `tuningCallback` em `LemuroidApplicationModule.retrogradeDb` aplica valores **fixos**
para todos os aparelhos:

```
PRAGMA cache_size = -32000   // 32 MB de page cache COMMITADO por conexão
PRAGMA mmap_size  = 268435456 // 256 MB de address space
PRAGMA temp_store = MEMORY
PRAGMA synchronous = NORMAL
```

**Problemas em device fraco:**
- `cache_size = -32000` reserva **32 MB de RAM** de page cache. Num aparelho de 1 GB isso é
  significativo e some do orçamento da UI/emulador.
- `mmap_size = 256 MB`: o app suporta **minSdk 21** e ABIs **32-bit** (armeabi-v7a, comuns em
  TV box baratos). Em processo 32-bit, 256 MB de mmap consome/fragmenta o address space
  limitado (~2–3 GB) e pode falhar silenciosamente.
- `temp_store = MEMORY`: temporários de query em RAM — ok em device normal, pressão extra em 1 GB.

**Proposta:** escalonar via `HeavySystemFilter.deviceTier`:

| PRAGMA | NORMAL | WEAK | ULTRA_WEAK |
|--------|--------|------|------------|
| `cache_size` | -32000 (32 MB) | -8000 (8 MB) | -4000 (4 MB) |
| `mmap_size` | 256 MB | 64 MB | 0 (desliga mmap) |
| `temp_store` | MEMORY | MEMORY | FILE/DEFAULT |
| `synchronous` | NORMAL | NORMAL | NORMAL |

**Impacto:** libera 24–28 MB de RAM em ULTRA_WEAK; remove risco de mmap em 32-bit.
**Risco:** baixo — page cache menor só significa um pouco mais de I/O de leitura; o DB já é
prebuilt e read-mostly.

### 1.2 Tornar o RGB_565 das covers realmente efetivo (ou assumir hardware bitmaps)

**Achado:** em `CoverUtils.buildImageLoader` setamos `bitmapConfig(RGB_565)` em low-RAM, **mas
não desligamos `allowHardware`**. No Coil 2.x, quando hardware bitmaps estão ativos (default),
o `bitmapConfig` é **ignorado** — ou seja, o RGB_565 atual provavelmente é **no-op** para as
covers exibidas.

**A nuance é favorável:** hardware bitmaps vivem na **memória gráfica nativa, fora do Java
heap** — ou seja, as covers já **não** pressionam o heap gerenciado (que é a principal fonte de
OOM). O risco dos hardware bitmaps (esgotar file descriptors em listas enormes) já é mitigado
pelo `maxSize` da paginação, que limita os bitmaps vivos.

**Duas saídas (escolher uma, idealmente medindo):**
- **(A) Recomendada:** manter hardware bitmaps e **remover a ilusão** — as duas linhas de
  RGB_565 podem sair (ou virar comentário explicando que hardware bitmaps já tiram a cover do
  heap). Net atual já é bom.
- **(B)** Se profiling mostrar pressão de memória gráfica nativa/FD em ULTRA_WEAK:
  `allowHardware(false)` **+** `RGB_565` em low-RAM → covers vão para o Java heap a 2 bytes/px
  (bounded pelo `maxSize`).

**Impacto:** clareza + garantia de que a otimização de fato acontece.
**Risco:** baixo.

---

## P2 — Impacto moderado, risco baixo/moderado

### 2.1 Reduzir o tamanho de decode das covers em low-RAM  ✅ IMPLEMENTADO (2026-06-15)

**Hoje:** `LemuroidGameImage` decodifica com `.size(400)`. O grid usa
`GridCells.Adaptive(140–144.dp)` → em densidade típica de TV (xhdpi) a célula tem ~280–360 px.
400 px já é próximo, mas em ULTRA_WEAK dá para cair para ~256–288 px sem perda perceptível,
economizando memória de bitmap por item.

**Proposta:** `targetSize = if (lowRam) 256 else 400`.
**Impacto:** ~30–40% menos bytes por cover decodificada.
**Risco:** baixo (leve perda de nitidez em telas grandes).

### 2.2 Desligar `crossfade` em low-RAM  ✅ IMPLEMENTADO (2026-06-15)

**Hoje:** `crossfade(true)` no `ImageLoader` e no request. O crossfade mantém **dois** bitmaps
vivos durante a animação e faz alpha-blend (custo de GPU) a cada item que entra na tela —
perceptível ao rolar rápido em TV box.

**Proposta:** `crossfade(!lowRam)`.
**Impacto:** menos picos de memória ao rolar + menos trabalho de GPU.
**Risco:** baixo (puramente estético).

### 2.3 Limitar o paralelismo de decode do Coil em low-RAM

**Hoje:** `interceptorDispatcher(Dispatchers.IO)` — o IO dispatcher tem até 64 threads, então
ao rolar rápido vários decodes podem rodar em paralelo, cada um alocando um bitmap temporário
→ pico de memória.

**Proposta:** em low-RAM usar um dispatcher limitado
(`Dispatchers.IO.limitedParallelism(2)`) para o decode.
**Impacto:** achata o pico de memória durante scroll rápido.
**Risco:** baixo/moderado (decode levemente mais lento sob scroll muito rápido).

---

## P3 — Startup e processo

### 3.1 Pular/atrasar o pre-warm do DB em ULTRA_WEAK

**Hoje:** `retrogradeDb` dispara uma Thread que abre o `writableDatabase` no boot (e com isso
aloca o page cache de 32 MB — ver 1.1). Em ULTRA_WEAK isso compete com a renderização inicial.

**Proposta:** condicionar o pre-warm a `deviceTier != ULTRA_WEAK`, ou atrasá-lo até depois do
primeiro frame. (Combina com 1.1 — cache menor reduz o custo do pre-warm.)
**Impacto:** boot mais leve em 1 GB.
**Risco:** baixo (primeira query foreground paga o open, mas o DB é prebuilt e rápido).

### 3.2 Garantir zero inicialização de Coil/imagem no processo `:game`

**Hoje:** já isolamos pre-warm e trim ao processo principal (correção 2026-06-13). Vale uma
auditoria para garantir que nenhum caminho do `:game` (overlay, menu in-game) construa o
`ImageLoader` do Coil — cada MB conta para o core durante a emulação.
**Impacto:** menos RAM no processo crítico de emulação.
**Risco:** baixo (auditoria).

---

## P4 — Emulação (core) por tier

### 4.1 Defaults de core por tier de device

**Hoje:** `ShaderChooser` já cai para `Sharp` em device fraco (correção 2026-06-13). O passo
seguinte é escalonar **defaults do core** por tier para os sistemas que sobrevivem ao
`HeavySystemFilter` em WEAK (ex.: PSX/N64 em 1–2 GB):
- frameskip automático mais agressivo,
- resolução interna mínima,
- desabilitar threaded renderer onde custa mais do que ganha em CPU fraca.

**Impacto:** alto **para quem joga** os sistemas moderados em device fraco.
**Risco:** moderado — precisa de teste por core; mexe na experiência de jogo. Fazer com cuidado
e por core.

---

## P5 — Medição (pré-requisito para validar tudo acima)

### 5.1 Instrumentar memória nos pontos-chave

Sem medição em campo, as escolhas acima são heurísticas. Propõe-se logar
`Debug.MemoryInfo` / `Runtime` (PSS, Java heap, native, graphics) em: pós-boot, ao abrir o
catálogo, ao rolar um sistema grande, e ao entrar/sair de um jogo — atrás de uma flag de debug.

**Impacto:** transforma o resto da lista de "achismo" em decisões medidas (especialmente 1.2 A vs B).
**Risco:** nulo (só logging em debug).

---

## Resumo priorizado

| # | Oportunidade | Impacto | Risco |
|---|--------------|---------|-------|
| 1.1 | PRAGMA do SQLite por tier (cache/mmap/temp) | Alto | Baixo |
| 1.2 | RGB_565 efetivo vs hardware bitmaps | Médio (clareza) | Baixo |
| 2.1 | Decode de cover menor em low-RAM | Médio | Baixo |
| 2.2 | Desligar crossfade em low-RAM | Médio | Baixo |
| 2.3 | Limitar paralelismo de decode | Médio | Baixo/Médio |
| 3.1 | Pular pre-warm do DB em ULTRA_WEAK | Médio | Baixo |
| 3.2 | Auditar Coil fora do `:game` | Baixo | Baixo |
| 4.1 | Defaults de core por tier | Alto (gameplay) | Médio |
| 5.1 | Instrumentar memória (debug) | Habilitador | Nulo |

**Sugestão de ordem de execução:** 5.1 (medir) → 1.1 + 1.2 + 2.1 + 2.2 (ganhos baratos de RAM)
→ 2.3 + 3.1 → 4.1 (com teste por core).
