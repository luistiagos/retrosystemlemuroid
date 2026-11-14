---
resultado: passou
base: d6e60f3e162e3a41ba8480ac46d21911d886f9a2
---
# Aceitacao de 2026-10-25-busca-gamecube-traz-neo-geo-pocket-color

Ramo principal (`origin/version10`) em `d6e60f3`, com a TASK-0001 integrada (`f2a17e8` / `1298414`; conferido
`git merge-base --is-ancestor`). `SystemSearchResolver.kt:55-56` tem `"gamecube"` e `"game cube"` -> `gc`.

**Ambiente:** emulador `lemu_3ds_api34` (`emulator-5554`, Android 14, x86_64; unico aparelho no adb). App
`app.retrogamesystem.debug` 1.17.34-DEBUG, instalado por `aparelho-instalar` (dados mantidos: o jogo de NGPC baixado na
TASK-0001 continua instalado). APK `build --bug TASK-0002 --emulador` da lane, copiado como
`aceitacao/aceitacao-d6e60f3-x86_64.apk` (fora do git: `*.apk`), sha256
`0ec009fa5ee4f95384ac89d25e1dba93be97a2f3310f57a719b562b032fda3b8` = `sha256sum` do `base.apk` instalado
(`pm path`), `lastUpdateTime=2026-11-14 16:19:44`. PID do launch: 10318.

Evidencia em `aceitacao/`.

## 1. Sintoma (T2 da demanda): busca `gamecube` nos jogos salvos

Caminho do usuario: Home ▸ aba **Saved games** ▸ campo "Search installed games…" ▸ digitar o termo. Instalado: so
`Sonic The Hedgehog - Pocket Adventure` (Neo Geo Pocket Color, selo `NGC`).

| Busca | Observado | Esperado | Print |
|---|---|---|---|
| `gamecube` | "No installed games match "gamecube"" | NGPC ausente (na base aparecia: `TASK-0001/base-t2-gamecube.png`) | `t2-gamecube.png` |
| `nintendo gamecube` | lista vazia | NGPC ausente (na base aparecia) | `t2-nintendo-gamecube.png` |
| `game cube` | lista vazia | sem NGPC | `t2-game-cube.png` |
| `ngc` | Sonic Pocket Adventure (NGC) | NGPC presente | `t2-ngc.png` |

Sinal do sintoma **ausente**; comportamento certo do `ngc` **presente**. A metade "o GameCube aparece" nao e visivel na
UI (nenhum jogo de GC instalado; ISO grande demais para o teto de download), e fica provada pelo
`SystemSearchResolverTest` (`gamecube`/`game cube`/`nintendo gamecube` -> `{gc}`), que rodou neste build.

## 2. Regressao direcionada

- Mesma tela: `neo geo` e `sonic` -> Sonic Pocket Adventure aparece; `snes` -> vazio (nenhum SNES instalado). Igual a
  base da TASK-0001 (`t2-neo-geo.png`, `t2-sonic.png`, `t2-snes.png`).
- Testes de unidade do `build` (`testFreeBundleDebugUnitTest` da lane): 96 testes, 0 falhas, 1 pulado;
  `SystemSearchResolverTest` 2/0. BUILD SUCCESSFUL.

## 3. Logs (T4)

`aceitacao/logcat-pid10318.txt` (`logcat -d --pid 10318`, 331 linhas): sem `AndroidRuntime`/`FATAL`/ANR; buffer
`crash` sem entrada do PID. Os unicos `E` sao 7 `OpenGLRenderer: Unable to match the desired swap behavior`, ruido do
emulador que aparece igual na base da TASK-0001. As 4 linhas com "libc" sao `D StrictMode ... libcore.io`, nao erro.

## Nao testado

A tela de TV (`TVSavedGamesFragment`, mesmo resolver pelo construtor com `Context`), sem AVD de TV no slot.
