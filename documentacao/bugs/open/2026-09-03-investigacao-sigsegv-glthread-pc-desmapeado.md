# [BUG] (investigação) SIGSEGV na GLThread com o **PC** apontando para memória desmapeada — backtrace vazio

**Data:** 2026-09-03
**Status:** 🔵 Investigação — 2 ocorrências, informação insuficiente para causa-raiz
**Severidade:** Baixa-Média (o processo `:game` morre; 2 eventos, mesmo aparelho, build antigo)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/native` (`<unknown>::reason=Native crash status=11`)
**Errors (serviço):** 2 ocorrências — 1177, 1432
**Aparelho:** samsung **SM-G781B** (Galaxy S20 FE 5G, `r8q`), arm64-v8a, **Android 13**, app **1.17.4**
**Observado em:** 2026-08-14 21:07 e 2026-08-18 19:29

---

## Sintoma

Tombstone decodificado, curtíssimo — o `pc` do frame #00 **é igual ao endereço da falha**:

```
pid: 6040, tid: 6110, name: GLThread 171
signal 11 (SIGSEGV), code SEGV_MAPERR, fault addr 0x71deb34cd8
backtrace:
  #00  pc 00000071deb34cd8  <unknown>
```

```
pid: 1698, tid: 1830, name: GLThread 94
signal 11 (SIGSEGV), code SEGV_MAPERR, fault addr 0x7243b08458
backtrace:
  #00  pc 0000007243b08458  <unknown>
  #01  pc 0000007243b41cd8  <unknown>
```

`fault addr == pc` significa que a falha foi ao **buscar a instrução**, não ao ler um dado: a
execução saltou para um endereço que não está mapeado como executável. O unwinder não consegue
atribuir nenhum dos frames a um `.so` carregado — daí o `<unknown>` e o `file=<unknown>` no
report.

## O que se sabe

- Sempre na **GLThread**, ou seja, no caminho do `retro_run`/render.
- Mesmo aparelho nas duas amostras, com quatro dias de intervalo, na mesma versão (1.17.4).
- 1432 traz `system=snes; core=snes9x; game=Super Mario World`. **Isso é o que torna o caso
  estranho:** o snes9x não tem dynarec — a hipótese óbvia ("saltou para código JIT liberado")
  não se sustenta para essa amostra.
- 1177 não traz `system`/`core` (a sessão não foi registrada).
- Os dois endereços (`0x71deb34cd8`, `0x7243b08458`) estão na faixa típica de mapeamentos
  anônimos/bibliotecas do processo, não em lixo tipo `0x0` ou `0xdeadbeef`.

## Hipóteses, nenhuma testada

1. **Ponteiro de função corrompido** no core ou no `liblibretrodroid` — salto indireto para um
   endereço que já foi `munmap`ado.
2. **`dlclose` de core anterior** deixando um callback registrado apontando para a região
   descarregada. Há precedente exato disso neste projeto —
   [[2026-09-02-libretrodroid-dlclose-core-anterior-sigabrt]] — mas lá o sintoma é SIGABRT em
   `__cxa_finalize`, não SIGSEGV com PC solto. Vale conferir se o aparelho trocou de core na
   sessão.
3. **Defeito de hardware/driver do aparelho** — duas ocorrências no mesmo `SM-G781B` e em
   nenhum outro, num parque de milhares de eventos, sustenta essa leitura tanto quanto as
   outras.

## Por que não dá para ir além agora

O reporter só recebeu `#00`/`#01` sem `build_id` de nenhuma biblioteca — não há como
`llvm-symbolizer` resolver. O caminho que resolveria (`llvm-objdump -d --start-address=…` sobre
o `.so` da versão 1.17.4, como foi feito em
[[2026-09-02-saturn-yabasanshiro-serializestate-fwrite-null]]) exige saber **qual** `.so` contém
o endereço, e é exatamente isso que falta.

## Próximos passos

- [ ] Se reaparecer, capturar o tombstone **completo** (com o mapa de memória — a seção
      `memory map` do tombstone diria qual região contém o `pc`). Hoje o decoder envia só o
      backtrace.
- [ ] Considerar incluir o `maps` do tombstone no report nativo quando o backtrace vier com
      `<unknown>` — sem isso, todo crash desta forma é indiagnosticável por construção.
- [ ] Reabrir/escalar se aparecer em aparelho diferente **ou** em versão ≥ 1.17.12. Enquanto for
      um único modelo numa build antiga, não justifica investimento.

## Lição

Um backtrace com um único frame `<unknown>` e `fault addr == pc` não é "crash sem informação" —
é uma informação específica: **salto para endereço não executável**. O que falta para agir não é
mais um evento, é o **mapa de memória** do tombstone. Vale mais gastar a próxima rodada
melhorando o que o reporter envia do que esperar a terceira ocorrência.
