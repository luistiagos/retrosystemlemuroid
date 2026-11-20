---
resultado: passou
base: 1b3defd2ca08f1aae4f2c7f5b947513d241bfd0a
---
# Aceitacao de 2026-09-28-lowmemory-gameplay-ppsspp-foreground-service

TASK-0010, pipeline 20261119-2228, 2026-11-20. Ramo principal com TASK-0007 (`0f50dd1`) e TASK-0008 (`8093fa8`)
integradas; lane `bugfix/TASK-0010` em `1b3defd` (sem `sync`: so docs depois do ultimo codigo, `085f74c`).
Evidencia em `evidencias/aceitacao/`.

## Ambiente e binario

- Aparelho: `emulator-5554` = `lemu_3ds_api34` (Android 14, x86_64), o unico no adb; instalado pelo
  `aparelho-instalar --bug TASK-0010` (telemetria do app de teste desligada no slot).
- `build --bug TASK-0010 --emulador` -> BUILD SUCCESSFUL (8m22s). Testes de unidade: 19 suites, **123 testes, 0 falhas,
  0 erros, 1 pulado** (linha de base do manual 54/0/1, sem falha nova).
- sha256 do APK x86_64 da lane `71048b22…076f02` = `sha256sum` do `base.apk` instalado (`binario-sha256.txt`);
  `lastUpdateTime` 14:59:01 (`pacote-antes.txt`) -> 15:29:18; `versionName 1.17.36-DEBUG`.

## 1. Defeito original (grupo A): sair do jogo pela Home e o LMK matar o `:game` nao perde a sessao

Caminho do usuario, pela UI: Inicio -> "Saved games" -> *Kirbys Adventure* (NES/fceumm, ROM ja baixada, autosave
ligado = default) -> jogo na tela -> **Home** do aparelho -> `run-as ... kill -9 <pid do :game>` (simula o LMK) ->
abrir o app -> "Continue playing" -> **Play** -> modal de versoes -> *(US)*.

| Ciclo | Antes do Home | Log do `:game` apos o Home | Reaberto apos o kill |
|---|---|---|---|
| 1 (PID 21522) | `c1-jogo-antes-home.png` (tutorial "The B button makes him inhale enemies!") | 15:31:54 Home -> 15:31:56.161 `Stored sram file with size: 8192` + 15:31:56.377 `Stored autosave file with size: 13805` (`logcat-game-21522.txt`); `.srm` e `.nes.state` com mtime 15:31:56 (`arquivos-apos-home.txt`) | `c1-reaberto-apos-kill.png`: mesmo cenario, passo seguinte do tutorial |
| 2 (PID 21717) | `c2-jogo-antes-home.png` ("Right or Left makes Kirby walk or dash!") | 15:36:06.437 `Stored sram` + 15:36:06.573 `Stored autosave` (`logcat-game-21717.txt`) | `c2-reaberto-primeiros-quadros.png`: 5 capturas desde o `Core created` (PID 21944): o 1o quadro ja e o mesmo cenario, no passo seguinte ("Press the A Button, and Kirby jumps!"); nenhum logo HAL nem tela-titulo |

- **Sinal do sintoma ausente:** um boot limpo do Kirby passa por logo e tela-titulo (visto ao apertar Start no
  mesmo teste); a reabertura apos o kill volta direto no ponto do Home -> a sessao foi restaurada do autosave
  gravado no `ON_STOP`. Antes da correcao (`2c02594`) o Home nao gravava nada e o kill perdia tudo.
- **Comportamento certo presente:** um par `Stored sram`/`Stored autosave` em cada Home, ~2 s depois.
- **Item 3 pendente da "Validacao" do doc (saida por Back grava uma vez so): confirmado.** PID 21944, Back as
  15:38:06: **um** par (`Stored sram` 15:38:06.397, `Stored autosave` 15:38:06.999), depois `VM exiting with result
  code 0` (`logcat-game-21944.txt`); app volta a Inicio (`c2-apos-back-home.png`).
- Controle positivo: nao ha (a correcao do grupo A ja estava na base desde 2026-09-30, como diz o Planejamento).

## 2. TASK-0007/0008: o processo principal em `FOREGROUND_SERVICE` registra qual trabalho o segura

- **Download pela UI** (Busca -> "spyro" -> *Skylanders - Spyro no Daibouken* (3DS, nao baixado) -> **Save**):
  5 amostras de 15:39:58 a 15:40:30 com `telemetry_fgs_tags=download` e `telemetry_fgs_pid=21392` = `pidof` do
  processo principal, `DownloadForegroundService` com `isForeground=true` (`download-prefs-durante.txt`,
  `download-fila-baixando.png`, fila em 5%).
- **Cancelar na fila (X):** `telemetry_fgs_tags` vazio e nenhum servico do app 4 s depois
  (`download-apos-cancelar.txt`, `download-apos-cancelar.png`).
- **Download ate o fim** (*Spyro - Attack of the Rhynocs*, GBA): `download` + `fgs=1` de 15:42:44 a 15:42:53, vazio
  e `fgs=0` as 15:42:56 (`download-completo-amostras.txt`); dialogo "Download Complete ... Play now?"
  (`download-completo-jogar-agora.png`).
- **Ajustes -> ROMs -> Rescan** (laco de 0,4 s no aparelho, `reescanear-amostras.txt`): 15:41:26 o servico de
  foreground sobe; 15:41:27.184 e 15:41:27.795 `telemetry_fgs_tags=library` com `fgs=1`; 15:41:28.443 vazio e
  `fgs=0`. No logcat: `Library indexing completed in: 1671 ms`, `Worker result SUCCESS ... LibraryIndexWork`,
  `WM-SystemFgDispatcher: Stopping foreground service` (15:41:28).
- O controle positivo do marcador (base sem `telemetry_fgs_*`) ja esta na TASK-0008 (`evidencias/TASK-0008/base-*`).

## 3. Regressao e logs (T3/T4)

- Testes de unidade: acima (123/0/1).
- Jogo abre, sai por Back sem crash; download completa e oferece jogar; cancelar e reescanear terminam.
- `logcat-principal-21392.txt` (PID do processo principal, a janela inteira) e `logcat-game-{21522,21717,21944}.txt`:
  nenhum `AndroidRuntime` de erro, `FATAL`, `ANR` ou `libc`; buffer `crash` vazio. Unicos `E/`: `OpenGLRenderer` e
  `SurfaceSyncGroup` (sistema) e `libretrodroid VIDEOFRAMES/EMUFPS` (contador de quadros, logado em nivel E).

## Fora do alcance desta prova (verificacao do cliente, nao criterio)

- Os proximos `lowmemory` do processo principal em producao chegarem com `fgs=`: o `reportOneExit` so roda para
  exits reais e a telemetria fica desligada no slot; o conteudo da linha e provado pelo `ForegroundWorkForExitTest`.
- PPSSPP num aparelho real de pouca RAM: o emulador x86_64 usou NES; o caminho (`onStop` -> `saveOnBackground`) e o
  mesmo para qualquer core.
