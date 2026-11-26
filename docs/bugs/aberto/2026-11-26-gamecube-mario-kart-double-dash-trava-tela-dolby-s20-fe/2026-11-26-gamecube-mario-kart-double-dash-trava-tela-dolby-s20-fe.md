---
tasks: [TASK-0022]
---

# [BUG] GameCube: Mario Kart: Double Dash!! trava na primeira tela (logo Dolby Pro Logic II) no Galaxy S20 FE

**Data:** 2026-11-26
**Severidade:** media — um dos títulos mais procurados do GameCube não passa da primeira tela num aparelho de cliente; o resto do app segue funcionando
**Complexidade:** media (provisória — a passada 2 decide: se for opção do core em `GameSystem.kt`/INI de jogo é baixa; se for defeito dentro do `libdolphin_libretro_android.so` é `bloqueado`, pitfall 15)
**Branch:** version10
**Origem:** relato do dono (cliente com Galaxy S20 FE; provavelmente a mesma cliente do chamado #330 — não confirmado)

---

## Sintoma

Cliente com **Samsung Galaxy S20 FE** reclama que o **Mario Kart: Double Dash!!** (GameCube) **trava na primeira
tela**. O print mostra o jogo parado no logo **"DOLBY SURROUND PRO LOGIC II"** (a primeira tela de boot do MKDD), fundo
preto, controle virtual de GameCube na tela (L, Z, R, Start, cruz, A/B/X/Y, dois analógicos, menu). Controle por toque;
nenhum controle físico aparece no print.

Versão do app e data exata do relato: não informadas (print recebido pelo dono em 2026-11-26).

## Evidência

### Print do cliente

`evidencias/print-cliente-tela-dolby.png` (1581×706, paisagem): logo Dolby Pro Logic II centralizado em cima, sem
OSD de FPS nem mensagem de erro, sem diálogo de crash.

### O jogo no catálogo

Só há uma entrada do MKDD original no GameCube, mais um ROM hack:

```
$ grep -n -i "double dash" catalog_manifest.txt | grep "^.*gc/"
76326:gc/Mario Kart - Double Dash!! (Europe) (En,Fr,De,Es,It).rvz|Mario Kart - Double Dash!!|...|0|1
77843:gc/Mario Kart Double Dash (Retro Track Grand Prix v2.1).iso|Mario Kart Double Dash (Retro Track Grand Prix v2.1)|...|0|1
```

O original é a versão **PAL (Europe)**, em **`.rvz`** (entrou no `MANIFEST_SCHEMA_VERSION` 45).

### Histórico do mesmo aparelho (chamado #330, já registrado)

Em `docs/bugs/aberto/2026-08-09-anr-inicializar-jogo-runongl-thread/…md`, seção
"Ocorrência — chamado #330 (2026-11-13 a 2026-11-16, até `1.17.35`)":

- Aparelho `samsung SM-G780G` (S20 FE, Snapdragon 865 / **Adreno 650**, `arm64-v8a`, Android 13 / SDK 33).
- A cliente mandou prints do **mesmo jogo** travado, naquela vez no **logo da Nintendo** (OSD
  `Video Info: Qualcomm, Adreno (TM) 650, OpenGL ES 3.2`), nas versões `1.17.33`–`1.17.35`.
- Telemetria: **15** `GLRetroView$GLThreadTimeoutException: GLThread did not answer in 30000 ms`,
  `phase=exit-save`/`background-save`, `call=serializeSRAM`, `system=gc` (ids `13776`, `13779`, `13781`,
  `13783..13787`, `13796`, `13806`, `13813`, `13839`, `13856`, `13889`, `13991`), com os dois MKDD (original e
  Retro Track Grand Prix). O timeout no save de saída é a consequência de sair com o core travado; não diz onde ele
  trava.
- O mesmo aparelho já tinha `id=13033` (GC, *Donkey Kong Jungle Beat*, `1.17.28`) e travas de 3DS
  (`docs/bugs/implementado/2026-11-18-3ds-azahar-trava-sem-crash-nao-aciona-fallback-citra-legacy/`), e um
  `SetRenderTarget` do PPSSPP (`id=13093`, `docs/bugs/bloqueado/2026-10-05-ppsspp-libretrodroid-cannot-create-framebuffer-abort/`).

O print de hoje para numa tela **anterior** ao logo da Nintendo dos prints de 11-13..11-16.

### Fatos do core Dolphin já medidos no projeto (contexto para a passada 2 — não é causa)

- O GameCube roda sem `defaultSettings` de CPU/áudio: as opções que o core recebe (lidas pelo `printRetroVariables`,
  `docs/features/aberto/2026-11-25-resolucao-interna-sistemas-3d/evidencias/variaveis-gc.txt`, moto g86) são
  `dolphin_main_cpu_thread=enabled` (dual-core), `dolphin_cpu_core=4` (JIT), `dolphin_dsp_hle=enabled`,
  `dolphin_fastmem=enabled`, `dolphin_shader_compilation_mode=0`, `dolphin_pal60=enabled`,
  `dolphin_fast_disc_speed=disabled`, `dolphin_skip_gc_bios=enabled`. O único `defaultSettings` do GC é
  `dolphin_efb_scale=1` (TASK-0017, commit `68316e8`, 2026-11-25 — **posterior** às ocorrências de 11-13..11-16).
- O app **não provisiona** `system/dolphin-emu/Sys` (sem `GameSettings/`), medido na pesquisa do Wii
  (`docs/backlog/arquivados/2026-10-25-suporte-nintendo-wii.md`, linhas 30, 58-59, 209). Os INIs do MKDD no Dolphin
  upstream (`Data/Sys/GameSettings/GM4.ini`, `GM4P01.ini`, baixados de `raw.githubusercontent.com` em 2026-11-26)
  **não têm** override de `[Core]` nem de `[Video_Hacks]` — só `StereoConvergence` e códigos Action Replay. A falta da
  `Sys` não muda as opções de núcleo deste jogo.
- Na mesma pesquisa do Wii, o Super Mario Galaxy travava em **dual-core** (GLThread, CPU thread e DVD thread em
  `futex`, mesmo objeto) e passava com `dolphin_main_cpu_thread=disabled` (linhas 380-405). Lá o gatilho suspeito era
  `EFBAccessEnable` no INI do jogo, que o MKDD não tem. Não testado em GameCube.

### Teste no moto g86 e relato da cliente (dono, 2026-11-26)

- **Não reproduz no moto g86** (MediaTek, GPU Mali, Android 16): o dono abriu o MKDD e o jogo **funciona**, passa do
  logo Dolby. Com as mesmas opções de core que o S20 FE recebe (nenhuma delas é por aparelho: são as do
  `variaveis-gc.txt` acima, inclusive dual-core ligado).
- **Pelo relato da cliente o jogo trava de vez**, não fica lento (não é queda de FPS). Bate com a telemetria de
  11-13..11-16: a GLThread não responde por 30 s ao sair.
- Os pontos de parada já vistos no S20 FE são dois: logo da Nintendo (prints de 11-13..11-16) e logo Dolby (print de
  hoje), ambos durante o boot.

### O que falta de evidência

- Versão do app que a cliente usa hoje e se é a mesma cliente do #330 (telemetria por conta/aparelho: a sessão não
  tinha `TRIAGEM_TOKEN`; rodar `telemetria.py conta …` da skill `triagem-bugs-prod`).
- Reprodução: não há S20 FE nem Adreno 650 disponível. O que diferencia os dois aparelhos (GPU/driver Adreno × Mali,
  Android 13 × 16, Snapdragon × MediaTek) só se separa no aparelho da cliente.

## Causa-raiz

**Não provada.** Não reproduz em nenhum aparelho disponível (seção acima), então esta passada não fecha a causa: ela
planeja um teste que **a cliente** roda no aparelho dela para separar as duas hipóteses que sobraram.

1. **Deadlock CPU↔GPU do modo dual-core** (`dolphin_main_cpu_thread=enabled`, padrão do core, sem
   `defaultSettings` no GC). Precedente medido no Wii (SMG: GLThread, CPU thread e DVD thread em `futex` no mesmo
   objeto; passa com dual-core desligado). Coerente com "trava de vez" e com o ponto de parada variar entre dois boots
   (logo Nintendo × logo Dolby): uma corrida de threads não para sempre no mesmo lugar. Num aparelho mais rápido
   (g86) a janela da corrida pode simplesmente não abrir.
2. **Driver GLES do Adreno 650** travando dentro de uma chamada GL do Dolphin (compilação de shader, sync). A
   telemetria já disse que a `GLThread` fica `RUNNABLE` dentro de `LibretroDroid.step` (não em monitor Java), o que
   serve para as duas hipóteses. Fix aqui seria dentro do core: fora do alcance deste repo (`bloqueado`).

## Hipóteses descartadas

- **Mudança de resolução interna (`68316e8`, TASK-0017):** posterior às ocorrências de 11-13..11-16.
- **Falta da pasta `Sys`/`GameSettings` do Dolphin:** os INIs do MKDD (`GM4.ini`, `GM4P01.ini`) não mudam opção de
  núcleo nem de vídeo (seção Evidência).
- **Lentidão (FPS baixo) confundida com trava:** a cliente diz que trava de vez; a telemetria mostra a GLThread sem
  responder por 30 s.
- **Defeito do jogo/ROM ou das opções do app:** a mesma ROM (Europe `.rvz`), com as mesmas opções, passa da abertura
  no moto g86.

## O que fazer

Uma task, complexidade **baixa** (pelo critério do README: opção do core exposta; nada de Room, `:game`, JNI):

**T1 — expor "Modo dual-core" no menu do jogo do GameCube.**
- `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/GameSystem.kt`, bloco
  `SystemID.GAMECUBE` / `CoreID.DOLPHIN`: acrescentar a `exposedSettings` um
  `ExposedSetting("dolphin_main_cpu_thread", R.string.setting_dolphin_dual_core)` **sem `values`**.
  - Símbolos abertos: `ExposedSetting` (`values` default vazio); `LemuroidCoreOption.getEntriesValues` (sem `values`
    usa os valores do core, `enabled|disabled`); `CoreOptionsPreferenceHelper.convertToPreference` (conjunto igual a
    `BOOLEAN_SET = {enabled, disabled}` → `SwitchPreference`). Vale para o menu mobile e o da TV (mesmo helper).
  - **Não** mudar o padrão: continua `enabled` (o core já manda assim). Desligar para todos custa ~20% de FPS
    (medido no MKW do Wii, seção "Decisão para a Fase 1" do backlog do Wii).
- Strings: `setting_dolphin_dual_core` em `retrograde-app-shared/src/main/res/values/strings.xml` ("Dual-core mode")
  e `values-pt-rBR/strings.xml` ("Modo dual-core"), ao lado de `setting_dolphin_internal_resolution`.
- Teste de unidade: em `lemuroid-app/src/test/java/com/swordfish/lemuroid/app/shared/game/GameSystemResolutionSettingTest.kt`
  (ou arquivo irmão), `gamecubeExpoeDualCore`: `dolphin_main_cpu_thread` está em `exposedSettings` do
  GAMECUBE/DOLPHIN **e não** em `defaultSettings`.
- E2E no g86: abrir o MKDD, menu ▸ opções: o switch aparece ligado; desligar, sair, reabrir; no logcat do `:game`
  o boot do Dolphin traz `CPU Thread separate = No` (a mesma linha usada no Wii) e o jogo passa da abertura.
- **Conflito de reserva:** a feature `docs/features/aberto/2026-11-25-resolucao-interna-sistemas-3d/` (TASK-0018)
  mexe no mesmo bloco do `GameSystem.kt` e nas mesmas `strings.xml`.

**Por que o menu abre com o core travado** (verificado, senão o teste da cliente não existe):
`BaseGameActivity.displayOptionsDialog` só retorna cedo com `loadingState` (ligado apenas durante save/load,
`BaseGameScreenViewModel.withLoading`); `getCoreOptions` → `GLRetroView.getVariables` roda na thread que chama (não na
GLThread) e `Environment::getVariables` (`LibretroDroid-patched/.../environment.cpp:467`) só copia um mapa, sem lock
compartilhado com o `retro_run`. A troca grava a preferência; o `updateVariables` vai para a fila da GLThread
(travada) e não chega ao core nesta sessão, mas o Dolphin só lê o dual-core no boot: vale ao reabrir.

**Depois da publicação — teste da cliente (o dono conduz):** abrir o MKDD; travou → menu (≡) ▸ desligar "Modo
dual-core" ▸ sair do jogo (a saída pode demorar ~30 s: é o save de saída esperando o core travado, já conhecido) ▸
abrir de novo.
- **Passa da abertura:** hipótese 1. Próximo passo: dual-core desligado só onde trava (INI de jogo
  `User/GameSettings/GM4.ini` com `[Core] CPUThread = False`, caminho provado no Wii, ou `defaultSettings` por
  sistema), medido no g86 o custo de FPS no MKDD.
- **Continua travando:** hipótese 2 (driver/core) → demanda vai para `bloqueado/` com esse motivo.

## Passagem de bastão (2026-11-26)

**Estado:** análise fechada (seções acima), sem código. Decisão do dono: corrigir **à mão, fora do pipeline**
(caminho B do modo `bug` do motor). Task criada: `tasks/aberto/TASK-0022-gamecube-expor-dual-core.md`, com os
arquivos, as strings e os testes.

**Próximo passo exato:**
1. Ler a seção "O que fazer" acima e a TASK-0022. Abrir o bloco `SystemID.GAMECUBE` de `GameSystem.kt` antes de editar
   (a feature `2026-11-25-resolucao-interna-sistemas-3d`, TASK-0018, pode ter mexido nele e nas `strings.xml` em
   paralelo: `git log -3 -- <arquivo>` e `git status`).
2. Implementar T1 e o teste; build `./gradlew :lemuroid-app:assembleFreeBundleDebug :lemuroid-app:testFreeBundleDebugUnitTest`
   e `./gradlew ktlintCheck`. Commit com assunto `TASK-0022: feat(cores): ...`.
3. E2E no g86 (TASK-0022, "Teste que prova"); preencher o `#### Execucao` da task; commit `chore: concluir TASK-0022`;
   `pipeline.py --projeto <repo> gerar` e `validar`; commit do que o gerar mudou, arquivo por arquivo.
4. Passada 3 nesta demanda (Correção, Validação). Ela fica em `implementado/`: o que fecha a causa é o teste da
   **cliente** depois que o dono publicar (roteiro em "O que fazer", "Depois da publicação").

