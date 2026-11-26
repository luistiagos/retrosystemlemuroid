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
