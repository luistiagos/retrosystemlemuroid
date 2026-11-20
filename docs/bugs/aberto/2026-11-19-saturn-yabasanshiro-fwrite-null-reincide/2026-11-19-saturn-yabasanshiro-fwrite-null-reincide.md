# Saturn: salvar estado volta a abortar o `:game` com `FORTIFY: fwrite: null FILE*` no yabasanshiro (1.17.27 e 1.17.28)

- **Origem:** achado lateral do pipeline em 2026-09-18-ppsspp-retro-run-terminate-abort (execução 20261119-2228)

**Data:** 2026-11-19
**Severidade:** media (perde a partida de Saturn ao salvar; só quem joga Saturn e salva)
**Branch:** version10
**Origem:** telemetria `retrogamesystem/native`, achado ao triar os SIGABRT nativos do bug
`2026-09-18-ppsspp-retro-run-terminate-abort` (pipeline 20261119-2228)

## Sintoma

O processo `:game` aborta ao salvar estado num jogo de Saturn, com a mesma assinatura do bug fechado
`docs/bugs/concluido/2026-09-02-saturn-yabasanshiro-serializestate-fwrite-null/` (que foi corrigido
exportando `TMPDIR` em `NativeTempDir.install`, chamado em `LemuroidApplication.onCreate:64`, e que
continua no código desta base).

## Evidencia

Telemetria (`python ~/.claude/skills/triagem-bugs-prod/scripts/telemetria.py todos --prefixo retrogamesystem --desde 2026-10-05`
e `... logs <ids>`), 7 ocorrências, todas abertas no painel:

| id | data | versão | ABI |
|----|------|--------|-----|
| 10694 | 2026-10-19 | 1.17.27 | armeabi-v7a |
| 12058 | 2026-10-29 | 1.17.27 | arm64 |
| 12398 | 2026-11-01 | 1.17.28 | arm64 (Samsung r8q, Android 13) |
| 12401 | 2026-11-01 | 1.17.28 | arm64 |
| 12876 | 2026-11-07 | 1.17.28 | armeabi-v7a |
| 13032 | 2026-11-09 | 1.17.28 | armeabi-v7a |
| 13638 | 2026-11-11 | 1.17.28 | armeabi-v7a |

Tombstone de 12398 (`C:\Windows\Temp\triagem\err12398_seq0.log`):

```
pid: 11260, tid: 11330, name: GLThread 1
signal 6 (SIGABRT), code SI_QUEUE
Abort message: FORTIFY: fwrite: null FILE*
  #02  libc.so (fwrite+300)
  #03  pc 00000000000c1fb8  .../lib/arm64/libyabasanshiro_libretro_android.so
  #04  pc 00000000000c1ef0  .../lib/arm64/libyabasanshiro_libretro_android.so
  #05  pc 00000000001407f8  .../libyabasanshiro_libretro_android.so (retro_serialize_size+60)
  #06  liblibretrodroid.so (libretrodroid::LibretroDroid::serializeState()+24)
  #10  com.swordfish.libretrodroid.GLRetroView$serializeState$1.invoke
```

Nome do `.so` já é o com prefixo `lib` (pós-pitfall 14), logo é build recente, com o `NativeTempDir`.
Ou o `tmpfile()` ainda falha com o `TMPDIR` exportado (diretório apagado depois do boot? processo
`:game` sem o `setenv`?), ou há outro `fopen` nulo no mesmo caminho. Não investigado além disso.
