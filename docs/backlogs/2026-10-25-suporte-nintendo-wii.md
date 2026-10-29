# [BACKLOG] Suporte a Nintendo Wii — viabilidade e plano

**Data:** 2026-10-25
**Status:** planejado — aprovado pelo dono em 2026-10-25; nada implementado
**Origem:** pedido do dono ("verifique a possibilidade de adicionarmos Nintendo Wii"), sessão de planejamento
**Tipo:** feature · **Esforço:** grande (4 fases; a Fase 3 depende de fonte de ROMs externa)

## Contexto

O dono pediu para avaliar se dá para adicionar o Wii ao Lemuroid e o que precisaria ser feito, só planejando nesta etapa.
A avaliação anterior (`docs/backlogs/inclusaosystemas.md`, jun/2026, linhas 34 e 36) marcou GameCube e Wii como
"teórico / inviável". Isso mudou: o GameCube entrou em produção com o core Dolphin (1.059 jogos `.rvz` no catálogo,
60 fps em *NFS Underground 2* no Moto G86 5G — `docs/bugs/open/2026-08-09-anr-inicializar-jogo-runongl-thread.md`).

## Veredito

**Dá para fazer, e a emulação já vem no APK.** O `libdolphin_libretro_android.so` empacotado (libretro/dolphin
`0cd3bb8`, de 2026-04-08) serve GameCube e Wii e já traz a emulação completa do Wii: Wii Remote, Nunchuk, Classic
Controller e ponteiro IR. Não precisa de core novo, de módulo `lemuroid_core_*`, de `.so` para copiar nem de BIOS.
O APK só cresce os ~3 MB da pasta Sys (Fase 2).

O trabalho se divide em quatro frentes, da mais arriscada para a menos:
1. **Controles.** É a frente maior. No Wii se joga apontando o Wii Remote para a tela, com Nunchuk e sacudindo o
   controle, e tudo isso precisa virar layout de toque e mapeamento de gamepad.
2. **Catálogo e hospedagem.** Depende de fora do app: cada ROM tem de 0,5 a 4,4 GB e precisa de uma fonte RVZ
   cadastrada no `find_by_file`.
3. **Corte de aparelhos.** O core só existe em 64 bits, e o Wii pesa uns 1,5× o GameCube.
4. **Pasta `Sys` do Dolphin.** O core a procura em `system/dolphin-emu/Sys`, e o app nunca a provisionou. Isso afeta o
   GameCube também.

## Decisões do dono (2026-10-25)

1. **Catálogo:** completo, como o do GameCube (manifesto + download pelo `find_by_file`). O nome MNEMONICO da fonte de
   Wii ainda está em aberto e precisa ser confirmado antes da Fase 3.
2. **Aparelhos:** mesmo corte do GC, mais a exigência de 64 bits. O Wii some com 2 GB de RAM ou menos (`VERY_HEAVY`) e
   em processo 32 bits, **nas duas UIs**. Ele aparece na TV arm64. A TV hoje não usa o `HeavySystemFilter`, então o
   corte de 64 bits precisa entrar também em `TVHomeViewModel`.
3. **Core:** a atualização se decide pelo spike. Começa com o core de abril, e só atualiza se a Fase 0 mostrar um
   defeito de Wii que dependa do core.

## O que a análise provou

| Fato | Evidência |
|---|---|
| O core empacotado já tem Wii. Descritores: `WiiMote`, `WiiMote (sideways)`, `WiiMote + Nunchuk`, `WiiMote + Classic Controller`, `WiiMote + Classic Controller Pro`, `Real WiiMote`. Opções: `dolphin_ir_mode` (`Right Stick controls pointer (relative)` / `(absolute)` / `Mouse controls pointer`), `dolphin_ir_offset/yaw/pitch/deadzone`, `dolphin_alt_gc_ports_on_wii`, `dolphin_hotkey_sideways_toggle`, `dolphin_sensor_bar_position` | `grep -a -o -E "dolphin_[a-z0-9_]+"` e `grep -a -o -E "[ -~]{4,}"` no `.so` de `lemuroid-cores/bundled-cores/src/main/jniLibs/arm64-v8a/` |
| O core só existe em `arm64-v8a` e `x86_64` (não há armeabi-v7a nem x86) | `ls lemuroid-cores/{bundled-cores,lemuroid_core_dolphin}/src/main/jniLibs/*/` |
| Versão: o `.so` embute o hash `0cd3bb8` (2026-04-08) | API do GitHub, `repos/libretro/dolphin/commits/0cd3bb8…` |
| O input do core mudou depois de abril. `49a2f01` "fix wiimote shake" (2026-09-02) move o sacudir de L2 para R2. `87e4b14` renomeia `WiiMote` para `WiiMote (upright)`. `00dbb84` transforma o MotionPlus em device. `4d2cc5b` cria uma opção de device padrão do Wii Remote (2026-10-20) | `api.github.com/repos/libretro/dolphin/commits?path=Source/Core/DolphinLibretro/Input.cpp` |
| Os IDs numéricos dos devices são iguais em abril e no master: em pé 1, deitado 513, Nunchuk 769, Classic 1025, Classic Pro 1281, GameCube-no-Wii 1537 (`(n<<8)\|JOYPAD`) | `Input.cpp` em `0cd3bb8` e no master |
| Se o frontend não pede um device Wii, o jogo começa sem Wii Remote (`ResetControllers` deixa todas as portas em `NONE`) | `Input.cpp` em `0cd3bb8` |
| O app só chama `setControllerType` quando `description == libretroDescriptor` ou `id == libretroId`, e só depois do primeiro frame | `GameViewModelInput.kt:268-279` e `:309-314` |
| A escolha de controle por porta já existe: uma lista com mais de um item em `controllerConfigs` vira seletor no menu do jogo. A escolha é salva por sistema+core+porta, **não por jogo** | `GameMenuCoreOptionsScreen.kt:90-117`, `ControllerConfigsManager.kt:35-39` |
| O LibretroDroid entrega JOYPAD, ANALOG e POINTER (um toque, porta 0). MOUSE e LIGHTGUN devolvem 0, e não há `GET_SENSOR_INTERFACE` | `LibretroDroid-patched/libretrodroid/src/main/cpp/input.cpp:29-132`, `environment.cpp` |
| Um toque na área do jogo já vira POINTER dentro do viewport, mas só quando `GameSystem.hasTouchScreen` está ligado | `GLRetroView.kt:377-395`, `libretrodroid.cpp:237-243`, `GameViewModelRetroGameView.kt:229` |
| O modo de IR "Mouse controls pointer" lê o POINTER (`devPointer`), não o mouse | `Input.cpp` em `0cd3bb8` |
| A pasta Sys fica em `<system>/dolphin-emu/Sys`. A pasta User (NAND do Wii, memory card do GC) fica em `<saves>/User`. Não há SRAM: `retro_get_memory_size` só responde SYSTEM_RAM | `Boot.cpp`, `Main.cpp` em `0cd3bb8` |
| O app não provisiona a Sys (não há referência a `dolphin-emu` no código). O libretro publica `buildbot.libretro.com/assets/system/Dolphin.zip` (3,2 MB) | grep; `curl -sIL` |
| Já existe um precedente de arquivos próprios de um core: `CoreID.getAssetManager` → `PPSSPPAssetsManager` (zip versionado em `system/PPSSPP`). Ele só é chamado pelo `CoreUpdateWork`, que roda 2 min depois do boot | `CoreID.kt:281-286`, `PPSSPPAssetsManager.kt`, `CoreUpdaterImpl.kt:105-111`, `LibraryIndexScheduler.kt:61-87` |
| Já existe corte por arquitetura: `SystemCoreConfig.supportedOnlyArchitectures` (usado pelo 3DS) gera `GameLoaderError.UnsupportedArchitecture`. O GameCube não usa | `GameSystem.kt:1901`, `GameLoader.kt:83-85` e `:188-195` |
| O `HeavySystemFilter` só corta por RAM. A TV não o usa e esconde apenas PSP e 3DS | `HeavySystemFilter.kt`, `TVHomeViewModel.kt:47` |
| No gamepad, o atalho de menu é L3+R3 (ou Select+Start). No Wii, o core usa R3 = Home e L3 = alternar "deitado" | `GameShortcut.kt:19-22`, `Input.cpp` |
| A arte já existe: `logos_extracted/all-systems/047-wii-normal.png` e `-hover.png` | glob |
| O SIGSEGV do Dolphin dentro do driver Adreno continua em aberto, e o Wii o herda | `docs/bugs/open/2026-09-02-crashes-nativos-cores-citra-dolphin.md` |

### Mapeamento do core (`0cd3bb8`) — base dos layouts

| Wii | Wii Remote + Nunchuk | Wii Remote deitado | Wii Remote em pé | Classic Controller |
|---|---|---|---|---|
| A / B | A / B | X / Y | A / B | A / B (+ X / Y) |
| 1 / 2 | Start / Select | B / A | X / Y | — |
| − / + | L1 / R1 | Select / Start | Select / Start | Select / Start |
| Home | R3 | R3 | R3 | R3 |
| Nunchuk C / Z, stick | X / Y, analógico esq. | — | — | — |
| Sacudir | R2 (Remote), L2 (Nunchuk)¹ | R2¹ | R2¹ | — |
| Inclinar | analógico dir., só no modo de IR por ponteiro | analógico esq. | analógico esq. | — |
| Mira (IR) | analógico dir. (modos 0/1) ou toque (modo ponteiro) | idem | idem | — |
| ZL / ZR, L / R | — | — | — | L1 / R1, L2 / R2 analógicos |

¹ O fonte lido diz R2, mas o commit posterior `49a2f01` diz que estava em L2. Conferir no aparelho (Fase 0).

## Hipóteses descartadas

- **Core ou módulo novo:** não precisa. É o mesmo `CoreID.DOLPHIN`, no padrão multivariante do §9.4 de `docs/prompts/adicionar-novo-sistema-libretro.md`.
- **BIOS ou NAND do usuário:** não precisa. O Dolphin usa IOS em HLE e cria uma NAND vazia em `<saves>/User/Wii`. A pasta Sys não é BIOS.
- **Mirar com o mouse do LibretroDroid:** o mouse não existe no LibretroDroid. O modo de IR do core lê o POINTER, que o LibretroDroid já entrega.
- **Acelerômetro e giroscópio via libretro:** o LibretroDroid não tem `SENSOR_INTERFACE`. A inclinação sai pelo "Inclinar sensor" do Lemuroid (`TILT_CONFIGURATION_ANALOG_*`), que move o analógico que o core lê como inclinação.
- **Catálogo em ISO:** uma ISO de Wii tem 4,37 GB, acima do limite de 4 GB do FAT32 (pendrive e SD de TV box). RVZ é menor e cabe.
- **Casar o controle pelo texto do descritor:** é frágil, porque o core novo renomeia `WiiMote`. Usar o `libretroId` numérico.
- **Wii Remote real por Bluetooth:** fica fora. Depende do lado Java do Dolphin Android, que não existe no libretro.

## Riscos

1. **Desempenho:** um aparelho de 3 GB com SoC fraco cai no tier NORMAL e vê o Wii (o GameCube já tem esse problema hoje).
2. **Wii Remote desconectado no boot:** acontece se o `ResetControllers` do core rodar depois do `setControllerType` do app, que roda após o primeiro frame.
3. **Controle por jogo:** o controle certo muda de jogo para jogo (NSMB Wii deitado, Zelda TP com Nunchuk, Xenoblade com Classic), mas a escolha salva vale para o sistema inteiro.
4. **Atalho de menu:** o L3+R3 do gamepad também aperta Home e alterna o "deitado" dentro do jogo.
5. **Download de vários GB:** esbarra no bug aberto de HD USB em TV box (`docs/bugs/open/2026-10-20-tvbox-hd-usb-download-continua-no-interno-espaco-insuficiente.md`), no espaço livre e no tempo de download.
6. **Crash no driver Adreno:** herdado do GameCube.
7. **Atualizar o core para corrigir o sacudir:** mexe no GameCube em produção.
8. **Memória:** o Wii tem 64 MB de MEM2 a mais que o GC, e o LMK pode matar o jogo em aparelho de 3 a 4 GB.

## Plano

### Fase 0 — Spike no aparelho (decide o resto)

Em branch própria, build debug, sem catálogo. Fazer o registro mínimo do sistema (Fase 1, só com o controle Nunchuk)
e usar ROMs de teste locais em `wii/`, fornecidas pelo dono. Medir no Moto G86 5G, com o SM-A127M como piso:
- boot: `EMUFPS`, tempo até o primeiro frame e `dumpsys meminfo` do `:game`;
- `Controls setting 0 to 769` no logcat e o aviso "Wii Remote 1 connected" na tela (risco 2);
- cada device (1, 513, 769, 1025, 1537) respondendo no toque e no gamepad;
- se o sacudir cai em R2 ou em L2;
- mira pelo analógico (modo 0) e pelo toque (`hasTouchScreen = true` + modo ponteiro);
- valores exatos das opções, via `printRetroVariables` (só no debug), para os `CoreVariable`;
- comportamento com e sem `system/dolphin-emu/Sys`;
- salvar e carregar estado, e saída limpa.

Jogos de teste: Wii Sports (ponteiro), New Super Mario Bros. Wii (deitado + sacudir), Super Mario Galaxy
(Nunchuk + mira + sacudir), Mario Kart Wii (deitado com inclinação, Classic, GC) e Zelda Twilight Princess.

**Saída do spike:** decidir se fica o core de abril ou se ele é atualizado, qual modo de mira é o padrão e qual é o corte de aparelho.

### Fase 1 — Registrar o sistema (Kotlin + recursos)

Seguir `docs/prompts/adicionar-novo-sistema-libretro.md` pulando os passos de core (1, 15, 17–22):
- `SystemID.kt`: `WII("wii")`. Isso também atende o `PrebuiltDbGenerator`, que lê os dbnames dali.
- `MetaSystemID.kt`: `WII` + branch em `fromSystemID()`.
- `GameSystem.kt` (modelo: o bloco do GC em `:2761-2793`):
  - `"Nintendo - Wii"`, `CoreID.DOLPHIN`;
  - portas 0–3 com `[WII_NUNCHUK, WII_SIDEWAYS, WII_CLASSIC, WII_GAMECUBE]` (+ `WII_REMOTE` em pé, opcional);
  - `rumbleSupported = true`, `statesSupported = true`, `skipDuplicateFrames = false`;
  - `supportedOnlyArchitectures = setOf("arm64-v8a", "x86_64")`;
  - `hasTouchScreen` conforme o spike;
  - extensões `rvz, wbfs, iso, ciso, gcz, wia, dol, elf` (`wad` opcional), com scan por pasta;
  - `defaultSettings`: `dolphin_alt_gc_ports_on_wii` desligado (o LibretroDroid só tem 4 portas, e com a opção ligada o GC iria para as portas 4–7), `dolphin_hotkey_sideways_toggle` desligado (risco 4) e `dolphin_ir_mode` conforme o spike;
  - `exposedSettings`: modo de mira, resolução interna (`dolphin_efb_scale`) e widescreen, com strings em en, pt-BR e pt-PT.
- `ControllerConfigs.kt`:
  - configs com `libretroId` (769, 513, 1025, 1537, 1), com comentário explicando o porquê;
  - `allowTouchRotation`;
  - inclinação no deitado → `TILT_CONFIGURATION_ANALOG_LEFT` (volante do Mario Kart);
  - nomes estáveis (`wii_nunchuk`, …), porque viram chave de preferência.
- `lemuroid-touchinput/.../layouts/Wii.kt` + `TouchControllerID.kt`: layouts novos para Nunchuk, deitado e Classic (e em pé, se entrar). O GameCube-no-Wii reaproveita `TouchControllerID.GAMECUBE`.
  - Nunchuk: à esquerda, stick, C/Z, − e sacudir do Nunchuk; à direita, A/B, 1/2, +, sacudir, Home e analógico de mira.
  - Deitado: D-pad à esquerda; à direita, 1/2 grandes, mais A/B e sacudir.
- `HeavySystemFilter.kt`: `WII` em `VERY_HEAVY_SYSTEMS`, mais um corte novo por ABI (sistemas que só rodam em 64
  bits, por enquanto só o `WII`; o GC fica para o achado lateral 2), aplicado no mobile e no `TVHomeViewModel`.
  A busca não filtra por sistema: um resultado de Wii em 32 bits cai na mensagem de arquitetura, o que é aceitável.
- Demais pontos de registro:
  - `ShaderChooser.kt` (os 2 `when`) e `SystemPalette.kt` (o `when` é exaustivo);
  - `SystemLogoResolver.kt` + `game_system_wii(_hover).png`;
  - `strings-game-system.xml`, `strings.xml` (`controller_wii_*`);
  - `SystemSearchResolver.kt` ("wii", "nintendo wii");
  - `mnemonico_map.json` (`"wii": <nome MNEMONICO>`);
  - `TVHomeViewModel.kt`: só o corte de 64 bits (o Wii continua visível em TV arm64);
  - `CLAUDE.md` (tabela do filtro).

### Fase 2 — Pasta Sys do Dolphin (vale para GC e Wii)

- Empacotar o `Data/Sys` do mesmo commit do core (`GC/`, `GameSettings/`, `Wii/`, `codehandler.bin`, `wiitdb-*.txt`, sem `Themes/`).
  Duas opções: um zip em assets ou um zip na tag do `lemuroid-cores`. Extrair para `<system>/dolphin-emu/Sys` com marcador de versão.
- Criar um `DolphinAssetsManager` no molde do `PPSSPPAssetsManager` e registrá-lo em `CoreID.getAssetManager`.
  Como o `CoreUpdateWork` só roda 2 min depois do boot, garantir a extração também antes do `retro_load_game`, no
  `GameLoader`, junto do `systemDirectory`.
- Validação: os GameSettings são aplicados (log do core) e a fonte do IPL aparece no GC.

### Fase 3 — Catálogo

- Fonte de ROMs: coleção Wii em RVZ → CSV → cadastro no romsrepository / `find_by_file`, com o nome MNEMONICO. Essa parte é externa e fica com o dono.
- Gerar as linhas `wii/<arquivo>.rvz|título|capa|pop|rep` com um `generate_wii_manifest.py`, no molde do `generate_dreamcast_manifest.py` (repositório `Nintendo_-_Wii` no libretro-thumbnails).
  - 5º campo: `clean_titles.py`.
  - Capas: primeiro HEAD por nome exato, depois `super_scrapper`, descartando URL compartilhada por 3 ou mais títulos.
- `MANIFEST_SCHEMA_VERSION` 42 → 43 no mesmo commit das linhas, com histórico no `ManifestQuickLoader.kt` e no `CLAUDE.md`.
- Conferir com `validate_catalog_representatives.py` e `check_manifest.py`.

### Fase 4 — Depois (fora do MVP)

- Controle padrão por jogo (tabela de game ID → device).
- Atualizar o core, se o spike pedir: fluxo do pitfall 15 (commit → push → tag → `CORES_VERSION`) e reteste do GC.
- `exposedSettings` do GC (`docs/backlogs/2026-09-03-gamecube-expor-opcoes-do-core-dolphin.md`), que é o mesmo trabalho.

## Verificação

- Build:
  - `./gradlew :lemuroid-app:assembleFreeBundleDebug`;
  - `ktlintCheck` completo (regenerar o baseline dos arquivos com passivo);
  - `:lemuroid-app:lintFreeBundleDebug`;
  - `generatePrebuiltDb` passando (valida o dbname).
- Aparelho:
  - abrir cada jogo de teste por `retrogamesystem://<pkg>/play-game/id/<id>`;
  - no logcat, conferir `Controls setting` e `EMUFPS`;
  - trocar o controle pelo menu, testar mira, sacudir e inclinação, salvar e carregar estado;
  - ver `Process exited cleanly` na saída.
- 32 bits: o Wii não aparece na lista e, se for aberto por deep link, mostra a mensagem de arquitetura (AVD `lemu_tv_api25` x86).
- Catálogo:
  - update sobre uma instalação existente (o bump do schema recarrega);
  - instalação nova (prebuilt);
  - download de um jogo pelo `find_by_file`.
- Release (pitfall 14) num aparelho ou AVD com Android 12 ou anterior.

## Achados laterais (fora deste escopo)

1. `SystemSearchResolver.kt:52` tem `"gamecube" to listOf("ngc")`, mas `ngc` é o Neo Geo Pocket Color. Na busca de
   jogos instalados/salvos, digitar "gamecube" traz os jogos de NGPC; o certo é `gc`. Registro:
   `docs/bugs/open/2026-10-25-busca-gamecube-traz-neo-geo-pocket-color.md`.
2. O GameCube não declara `supportedOnlyArchitectures`. No APK armeabi-v7a ele aparece na lista; abrir um jogo mostra
   "baixando núcleo", o download dá 404 (não há `.so` armeabi-v7a na tag) e termina em erro de core, em vez da mensagem
   de arquitetura. Registro: `docs/bugs/open/2026-10-25-gamecube-32-bits-tenta-baixar-core-inexistente.md`.
3. O GameCube roda sem `dolphin-emu/Sys` (sem GameSettings nem fontes do IPL). A Fase 2 corrige junto e não ganhou
   registro próprio.
4. As linhas 34 e 36 de `docs/backlogs/inclusaosystemas.md` davam GC e Wii como inviáveis. Atualizadas junto com este
   doc, apontando para cá.
5. O `GameSystem` do GC aceita `wbfs`, que é formato só de Wii. Quando o Wii existir, decidir se o `wbfs` sai do GC
   (quem tem `.wbfs` em `gc/` precisaria movê-lo para `wii/`).

## Próximo passo

Fase 0 (spike), numa sessão nova que lê só este doc. Antes dela, o dono precisa fornecer as ROMs de teste (os cinco
jogos da Fase 0, em `.rvz`) e deixar o Moto G86 5G conectado. A partir daí: criar uma branch a partir de `main`, fazer
o registro mínimo da Fase 1 com o controle Nunchuk (`libretroId = 769`) e seguir a lista de medições da Fase 0. O
resultado entra neste doc na seção "Andamento", antes de qualquer outra fase.

## Andamento

### Fase 0 — spike (2026-10-25, sessão 1)

**Preparação, provado antes de qualquer código:**

| Fato | Evidência |
|---|---|
| **O nome MNEMONICO do Wii é `wii`, e a fonte já existe.** Destrava a Fase 3: não depende mais de cadastro externo | `curl "https://emuladores.pythonanywhere.com/find_by_file?path=Mario%20Kart%20Wii%20(USA)%20(En%2CFr%2CEs).rvz&source_id=1&system=wii"` → 200 com `https://huggingface.co/datasets/Emuladores/wii/resolve/main/…rvz?download=true`. Com `system=nintendowii` ou `nintendo_wii` → 404 |
| O dataset HF `Emuladores/wii` tem **1.463 arquivos `.rvz`** na raiz | API `huggingface.co/api/datasets/Emuladores/wii/tree/main`, paginada pelo header `Link` (o `?limit=` dá erro) |
| **Catálogo, por decisão do dono:** replicar as linhas de Wii do `C:\projects\retrobatnew\source\catalogs\catalog_manifest_full.txt`. São **1.363 linhas `wii/*.rvz`**, quase todas só com o caminho, sem título, capa nem popularidade (1 linha com `\|`). Título, capa e 5º campo ficam com a Fase 3 (`clean_titles.py`, HEAD no `Nintendo_-_Wii`) | `grep -c '^wii/'` → 1363; `grep -c '^wii/.*\|'` → 1 |
| O HF **não tem** Wii Sports, New Super Mario Bros. Wii nem Zelda TP (USA). Os substitutos são Wii Play (ponteiro), Donkey Kong Country Returns e Kirby's Return to Dream Land (deitado + sacudir) e *Zelda no Densetsu - Twilight Princess (Japan)* (Nunchuk). Mario Kart Wii e Super Mario Galaxy existem | listagem do HF |
| **O `main` está 167 commits atrás** da linha viva e nem tem o submódulo `lemuroid-cores`. A branch do spike, `spike-wii`, sai de `redesenho-tv-jogos-salvos-tela` (`85b4d04`), numa **worktree** em `C:\projects\lemuroid\Lemuroid-wii`, porque o checkout principal tem trabalho de outra sessão sem commit (NeoCD). O submódulo da worktree foi clonado do clone local (`-c submodule.lemuroid-cores.url=<checkout>/lemuroid-cores`), com o `origin` reapontado para o GitHub | `git rev-list --left-right --count main...HEAD` → `0 167`; `git ls-tree main` sem entrada `160000` |
| O Moto G86 (`ZY32LMNN9B`) é arm64-v8a com 7,5 GB de RAM e 125 GB livres. O app de debug é `app.retrogamesystem.debug` | `getprop`, `/proc/meminfo`, `df` |
| ROMs de teste baixadas (8,8 GB) e enviadas para `/sdcard/Download/wii_spike/` no aparelho | `curl` direto do HF |

**Desenho do registro mínimo do spike** (não é o da Fase 1):
- `SystemID.WII("wii")` + `MetaSystemID.WII`, strings, logo (`047-wii-*.png` → `game_system_wii(_hover).png`), `SystemPalette`, `ShaderChooser`, `SystemLogoResolver` e `mnemonico_map.json` (`"wii": "wii"`).
- `GameSystem` no molde do GC (`CoreID.DOLPHIN`, `supportedOnlyArchitectures`). A porta 0 recebe **todos** os devices (769, 513, 1, 1025, 1537), para o seletor do menu do jogo trocar o device sem rebuild. As portas 1–3 ficam só com o Nunchuk.
- Os configs casam pelo `libretroId` (o `findControllerId` de `GameViewModelInput.kt` aceita `controller.id == libretroId`). Por enquanto todos usam o layout de toque `PSX_DUALSHOCK` (D-pad, A/B/X/Y, L1/L2/R1/R2, Select/Start e dois analógicos; falta só o R3 = Home). Os layouts próprios são trabalho da Fase 1.

**Estado da implementação do spike (worktree `Lemuroid-wii`, branch `spike-wii`, sem commit):**
- Registro feito conforme o desenho acima. Os valores das opções foram lidos no fonte do core em `0cd3bb8`
  (`Source/Core/DolphinLibretro/Common/Options.cpp`):
  - `dolphin_ir_mode`: `0` = analógico direito relativo, `1` = absoluto (**default**), `2` = "Mouse controls pointer";
  - `dolphin_alt_gc_ports_on_wii`: `disabled` (default) ou `enabled`;
  - `dolphin_hotkey_sideways_toggle`: default `L3`, aceita "Disabled" (valor `MODIFIER_DISABLED_CONTROL`), `R3`, `L1`…;
  - `dolphin_sensor_bar_position`: `0` embaixo (default) ou `1` em cima.
  O `dolphin_ir_mode` ficou exposto no menu do jogo para o teste.
- **Risco 2 provavelmente falso:** o core chama `SET_CONTROLLER_INFO` dentro do `retro_load_game`
  (`Boot.cpp:581` → `Input::InitStage2`), antes do primeiro frame. O `input_types[]` guarda o device pedido pelo
  frontend, e o `ResetControllers` o reaplica. Falta confirmar no aparelho.
- `./gradlew :lemuroid-app:assembleFreeBundleDebug` → BUILD SUCCESSFUL (11m57s), instalado no Moto G86 com
  `install -r` sobre o `app.retrogamesystem.debug` (dados mantidos).
- As 6 ROMs estão em `/sdcard/Android/data/app.retrogamesystem.debug/files/roms/wii/` e foram indexadas
  (`games.id` 347254–347259; Mario Kart Wii = 347256).
- **Armadilha de teste (não é do Wii):** o "Verificar novamente" fica desabilitado nos 2 primeiros minutos depois de
  cada abertura do app. O `MainProcessInitializer` enfileira o `CoreUpdateWork` com `setInitialDelay(2 min)`, e o
  `PendingOperationsMonitor.anyLibraryOperationInProgress` conta `ENQUEUED` como "em andamento". Os primeiros toques
  caíram dentro dessa janela e não fizeram nada. Hipótese descartada: core update reagendado em loop por
  `Result.retry()` (o job que o `cmd jobscheduler run -f` forçou nem era o core update). Prova: o banco
  `no_backup/androidx.work.workdb` tem um `CoreUpdateWork` em `state=0` recém-enfileirado e nenhum `LibraryIndexWork`;
  esperando o botão habilitar, o índice rodou em segundos.

**Medições (Moto G86, core `0cd3bb8`, debug):**

| O quê | Resultado |
|---|---|
| Boot do Mario Kart Wii (deep link → `Controls setting`, que roda depois do 1º frame) | ~4,8 s (`am start` 13:23:59,3 → 13:24:04,1) |
| `Controls setting 0..3 to 769` | aparece; **risco 2 descartado**: o D-pad e o A do toque mexeram no menu do jogo na primeira sessão |
| `dumpsys meminfo` do `:game` nos menus do MKW | TOTAL PSS 917 MB, RSS 1,12 GB (Native Heap 176 MB, GL mtrack 230 MB) |
| `EMUFPS` nos menus do MKW | 58,4–61,6 |
| Valores reais das opções (`printRetroVariables`) | iguais ao fonte: `dolphin_ir_mode` 1 (`1\|0\|2`), `dolphin_hotkey_sideways_toggle` `L3` (`L3\|Disabled\|R3\|…` → o valor de desligar é **`Disabled`**), `dolphin_alt_gc_ports_on_wii` `disabled`, `dolphin_sensor_bar_position` 0, `dolphin_ir_modifier` `None`, `dolphin_swing_modifier` `Disabled`, `dolphin_efb_scale` 1 |
| **Sem `system/dolphin-emu/Sys`** (1º boot, NAND criada do zero) | o MKW abre com **"Could not write to/read from Wii system memory"**. A NAND foi criada em `<saves>/User/Wii` (`SYSCONF`, `title/00010004/524d4345/data/rksys.dat` de 2,8 MB), mas o jogo não a aceitou |
| **Com a `Sys`** (`Dolphin.zip` do buildbot, 3,2 MB, extraído em `files/system/dolphin-emu/Sys`) | o mesmo save aparece como "corrompido, apagar e criar outro?"; com OK o jogo recria o save e chega à tela de título e à escolha de Mii |

**Isolamento da `Sys` (MKW, autosave apagado antes de cada rodada):**

| NAND (`<saves>/User/Wii`) | `Sys` | Resultado |
|---|---|---|
| criada do zero | não | ❌ "Could not write to/read from Wii system memory" (reproduzido 2×) |
| criada do zero | **sim** | ✅ "Select License", sem erro |
| já existente, só o save do MKW apagado | não | ✅ título sem erro, **mas** essa NAND tinha sido povoada numa rodada com a `Sys` (não conta como "sem `Sys`") |

**Causa:** na criação da NAND o Dolphin copia `Sys/Wii/shared2/wc24/*` (`mbox/wc24{recv,send}.{ctl,mbx}`, `misc.bin`,
`nwc24dl.bin`, `nwc24fl.bin`, `nwc24fls.bin`). O MKW usa WiiConnect24 e, sem esses arquivos, falha ao criar o
`wc24dl.vff`/`wc24scr.vff`, que só aparecem na NAND feita com a `Sys`. Comparação por `find Wii -type f` nos dois casos.
**Decisão: a Fase 2 (provisionar a `Sys`) é pré-requisito do Wii, não melhoria.** E tem de valer **antes do primeiro
boot de qualquer jogo de Wii**: uma NAND criada sem a `Sys` fica sem esses arquivos para sempre, porque a cópia só
acontece na criação. Quem abrir um jogo antes da extração precisa de um reparo (copiar o que falta de `Sys/Wii` para
a NAND, sem sobrescrever) ou da recriação da NAND. O `CoreUpdateWork` roda 2 min depois do boot, então extrair só
nele não basta: tem de extrair no `GameLoader`.
- Sintoma lateral do 1º boot com erro: o jogo grava um `rksys.dat` que o boot seguinte declara corrompido ("Delete the
  file and create a new one?"). Com OK o save é recriado.
- Autosave + NAND alterada: carregar o `.state` do autosave depois de apagar o save da NAND deixou **tela roxa a 100%
  de CPU, sem `EMUFPS`** (core travado). É um caso artificial, mas mostra que o estado salvo carrega a NAND junto.
  Reteste junto com "salvar/carregar estado".

**Desempenho em corrida (MKW, Luigi Circuit, 50cc):** `EMUFPS` 48–99 nos primeiros 30 s, segurando o acelerador; o
PSS do `:game` subiu para **1,07 GB** (RSS 1,25 GB). **A medida de FPS do G86 está contaminada**: com o dedo na tela o
painel vai a 120 Hz e a emulação acelera junto (o cronômetro do jogo andou 26,74 s em 15,66 s reais = 1,71×). Isso não
é do Wii; está registrado em `docs/bugs/open/2026-10-23-pokemon-ds-roda-acelerado-com-acelerar-desligado-moto-g86.md`.
Leitura útil: o G86 **sustenta ~100 fps no MKW** (sobra ~1,7× acima do tempo real) e roda estável a 60 sem toque.
- Saída do jogo: `Process … :game exited cleanly (0)`.
- O pad de toque e o D-pad mandam no Wii Remote (o menu e o kart responderam); o A segurado acelera.

**Próximas medições (sessão seguinte):**
1. Sacudir: R2 ou L2 (ex.: Super Mario Galaxy, giro). Para fugir do boost de 120 Hz do G86, travar o painel em 60 Hz
   (Configurações > Tela) antes de medir FPS.
2. Mira: modo `1` (default, absoluto pelo analógico direito), modo `0` e modo `2` com toque na área do jogo
   (`hasTouchScreen = true` já está ligado). Wii Play (tiro ao alvo) é o teste.
3. Cada device pelo seletor do menu do jogo: 513 (DKCR/Kirby), 1 (em pé), 1025 (Classic, MKW), 1537 (GC no Wii,
   MKW). Ver se a troca no meio do jogo reconecta o controle.
4. Salvar e carregar estado (com a NAND estável) e o caso do autosave acima.
5. Zelda TP (JP) com Nunchuk e o SM-A127M como piso.
6. O botão de menu do pad (`SecondaryButtonMenu`) não abriu o menu com `input touchscreen swipe` de 120 ms; o voltar
   abriu (ou fechou o jogo). Conferir com toque real antes de culpar o layout.

### Fase 0 — spike (2026-10-25, sessão 2)

**Lido no fonte do core (`0cd3bb8`, `Source/Core/DolphinLibretro/Input.cpp`, cópia em
`…/4ed476f8-…/scratchpad/dolphin_src/`), `ResetControllers` → bloco `device != RETRO_DEVICE_REAL_WIIMOTE` (l. 1355–1480):**

| Botão libretro | Wii Remote em pé (1) | Deitado (513) | Wii Remote + Nunchuk (769) |
|---|---|---|---|
| A / B | A / B | 2 / 1 | A / B |
| X / Y | 1 / 2 | A / B | **C / Z do Nunchuk** |
| Start / Select | + / − | + / − | **1 / 2** |
| L1 / R1 | — | — | **− / +** |
| R2 | sacudir o Wii Remote | idem | idem |
| L2 | — | — | **sacudir o Nunchuk** |
| R3 | Home | Home | Home |
| L3 | alterna deitado/em pé (`dolphin_hotkey_sideways_toggle`) | idem | idem |
| Analógico esquerdo | inclinação (tilt) | inclinação | analógico do Nunchuk |
| Analógico direito | mira (modos 0/1) e swing | idem | mira (modos 0/1); no modo 2 vira **inclinação** (l. 894–903) |
| D-pad | D-pad | D-pad | D-pad |

- **Sacudir (medição 1) respondido pelo fonte:** o Wii Remote sacode no **R2**, o Nunchuk no **L2**. Super Mario
  Galaxy (giro = sacudir o Wii Remote) usa R2. Falta só ver no aparelho o giro acontecer pelo R2 do toque.
- **Classic (1025):** A/B/X/Y diretos, Select/Start = −/+, ZL/ZR = L2/R2, Home = R3 (l. 1313–1338).
- **No Nunchuk o "+" (pausa na maioria dos jogos) é o R1**, não o Start; o Start é o botão 1. O layout da Fase 1
  tem de rotular isso, ou o jogador não acha o pause.
- **O LibretroDroid não implementa `RETRO_ENVIRONMENT_GET_SENSOR_INTERFACE`** (`environment.cpp` só trata o rumble).
  O `sensor_enabled[port]` do core fica falso e o ramo de acelerômetro/giroscópio (l. 1413–1452) nunca roda:
  inclinação vem **só** do analógico esquerdo (em pé/deitado). O `tiltConfigurations` do app (girar o aparelho →
  analógico) é o único caminho para "inclinar o Wii Remote" — o Mario Kart Wii deitado depende disso.
- **Ponteiro (modo 2):** `Input::getInputState` do LibretroDroid só responde `index == 0` (toque único) e marca
  `PRESSED` enquanto o dedo está na tela. A mira pelo toque, portanto, funciona com **um dedo**: segurar um botão do
  pad e tocar no alvo ao mesmo tempo depende de o toque do pad não virar o ponteiro — a medir.

**Interrompida:** o Moto G86 saiu do `adb devices` no início da sessão e não voltou em ~35 min de espera. Nenhuma
medição de aparelho foi feita nesta sessão; a lista "Próximas medições" da sessão 1 continua valendo, com a medição 1
reduzida a confirmar o giro pelo R2. Estado do aparelho na última leitura: as 6 ROMs em
`files/roms/wii/`, a `Sys` extraída em `files/system/dolphin-emu/Sys`, painel em 120 Hz
(`peak_refresh_rate`/`min_refresh_rate` = `null`, modos 0 = 120, 1 = 90, 2 = 60 Hz). A worktree `Lemuroid-wii` segue
sem commit, com o registro do spike.

### Fase 0 — spike (2026-10-26, sessão 3)

**Aparelho:** o G86 voltou ao `adb devices` depois que o dono religou a depuração (o mDNS anunciava
`_adb-tls-connect` em `192.168.18.112:35281` com a porta fechada — anúncio velho, não serve de prova de conexão).
Painel travado em 60 Hz para medir: `settings put system peak_refresh_rate 60.0` e `min_refresh_rate 60.0`
(`mActiveModeId=3`, `vsyncRate=60.00 Hz`). **Restaurar no fim:** os dois valores eram `null`
(`settings delete system peak_refresh_rate` / `min_refresh_rate`). O APK instalado é o build da worktree
(`lastUpdateTime` 2026-10-25 02:57:53 = horário do `.apk` em `build/outputs`). O G86 é **MediaTek com GPU Mali**
(`/vendor/lib64/egl/mt6878/libGLES_mali.so`), não Adreno: o risco 6 (SIGSEGV no driver Adreno) não é testado aqui.

**Lateral (não é do Wii):** abrir um jogo por deep link com o app frio fica **~30 s em "Preparando o ambiente…"**
antes de o `:game` subir (`Start proc …debug` 00:14:39,5 → `Start proc …debug:game` 00:15:10,4; igual no 1º
lançamento, 00:10:30 → 00:11:01). Não investigado.

**Super Mario Galaxy trava ~22 s depois do 1º frame, sem nenhum input — reproduzido 2×:**

| Rodada | `:game` sobe | `Controls setting 0 to 769` | último `EMUFPS` |
|---|---|---|---|
| 1 | 00:11:01,1 | 00:11:03,2 | 00:11:27,5 (o toque no A foi depois, às 00:11:38) |
| 2 (`force-stop` + relançar, sem tocar) | 00:15:10,4 | 00:15:12,4 | 00:15:33,8 |

- Até travar: `EMUFPS` 60,00 cravado. A tela passa pelo aviso da alça do Wii Remote e congela no preto do fade
  seguinte. Não havia autosave do SMG (`files/states/dolphin/` vazio): foi boot limpo.
- Estado travado: `GameActivity` em primeiro plano, tela acesa; nenhum `queueBuffer` da `SurfaceView` depois do
  último frame. `GLThread 1`, `CPU thread`, `DVD thread` e `Savestate Worker` **todas em `futex` (syscall 98)**
  com o `utime` parado entre duas leituras (`/proc/<pid>/task/*/syscall` via `run-as`). Os endereços de futex da
  GLThread, da CPU thread e da DVD thread ficam a < 1 KB um do outro (mesmo objeto). `debuggerd -b` pede root.
- No `0cd3bb8`, em dual-core o `retro_run` faz `Core::DoFrameStep` + `Fifo::RunGpuLoop` na GLThread
  (`Main.cpp:416-419`): GLThread e CPU thread esperando uma à outra é a cara de um deadlock de sincronia CPU↔GPU.
  Hipótese, ainda não provada.
- Opções que o core recebeu (as mesmas do GC — o GC também não tem `defaultSettings`): `dolphin_main_cpu_thread`
  `enabled` (dual-core), `dolphin_cpu_core` 4 (JIT), `dolphin_call_back_audio_method` 0, `dolphin_dsp_hle`
  `enabled`, `dolphin_efb_access_enable` `disabled`, `dolphin_shader_compilation_mode` 0, `dolphin_fastmem` `enabled`.
- **A/B feito — é o dual-core.** Spike expõe `dolphin_main_cpu_thread` no menu (`ExposedSetting` sem valores →
  vira switch com os valores do core). Com ele desligado (`Updating core variable: dolphin_main_cpu_thread
  disabled`, `CPU Thread separate = No`), mesmo painel a 60 Hz, o SMG passou dos 22 s e chegou à tela de título
  ("Press both A and B") a 60 fps. Suspeita do gatilho: o `Sys/GameSettings/RMG.ini` do SMG liga
  `EFBAccessEnable = True` (o CPU lê o EFB e espera a GPU), e no libretro a GPU só anda dentro do `retro_run`.
  Não confirmado no fonte.
- **O Wii Play roda sempre em single-core:** o `RHA.ini` força `CPUThread = False` (a GLThread vira
  "CPU-GPU thread" — o Dolphin renomeia a thread que chama `Core::EmuThread`). Numa rodada (painel a 60 Hz) ele
  também parou de dar frame ~25 s depois de criar o save, mas com **outra assinatura**: a "CPU-GPU thread" a
  100% de CPU (`utime` +95 ticks/s), sem futex — igual à "tela roxa a 100% de CPU" do autosave do MKW na
  sessão 1. Na rodada seguinte (painel no padrão, save já existente) o mesmo trecho passou: o preto era uma tela
  esperando A. **Não reproduzido; não sei se é o mesmo defeito.**
- **Decisão provável para a Fase 1:** Wii com `dolphin_main_cpu_thread=disabled` em `defaultSettings`. Custo:
  desempenho (medir no MKW). O GameCube também roda em dual-core hoje — conferir se algum jogo de GC com
  `EFBAccessEnable` no INI trava igual (não testado).

**Mira (Wii Play, `dolphin_ir_mode`):**
- **Modos 0/1 (analógico direito):** o core liga **"Auto hide"** no cursor (`Input.cpp:850-851`); com o analógico
  parado o Dolphin esconde o ponteiro e o jogo mostra "Aim the Wii Remote at the screen." Para mirar é preciso
  manter o analógico em movimento. Ruim para toque.
- **Modo 2 (toque):** auto-hide desligado. Sem dedo na tela o cursor fica no **canto superior esquerdo**:
  o `ACTION_UP` manda `TOUCH_EVENT_OUTSIDE`, o LibretroDroid devolve `2·(-1-0,5)·0x7FFF` em `int16`
  (`input.cpp`, `RETRO_DEVICE_ID_POINTER_X/Y`), que estoura para ≈ −1. Com dedo, o cursor segue o dedo, mas
  **não 1:1**: com yaw 25 / pitch 25 / offset 0 (defaults do core) ele anda ~1,8× o deslocamento do dedo a partir
  do centro, nos dois eixos, e em Y fica ~58% da altura **abaixo** (dedo no centro → cursor preso na borda
  inferior). Medido com 4 toques (centro, acima, à direita, abaixo-esquerda) e o cursor de mão do menu "Play
  again / Play a different game". Calibrar `dolphin_ir_yaw/pitch/offset` (spike expõe os três).
- Multitoque no pad funciona (A+B juntos aceitos na tela de título do Wii Play, por multitoque injetado).
  Mirar com um dedo e apertar com outro: ver abaixo.
- **Cenário 3D preto no estande de tiro do Wii Play** (só o Mii, o placar e os textos aparecem). Não investigado.
- **Lateral (genérico do app):** a lista do menu do jogo mostra como selecionado o **primeiro valor exposto**
  (`GameMenuCoreOptionsScreen.kt:74-78`, `indexPreferenceState(…, entriesValues.first(), …)`), não o valor que
  o core está usando — o menu dizia "Right stick (relative)" com o core em `1` (absoluto). Na Fase 1, o primeiro
  valor da lista tem de ser o padrão real.

**Item 6 (botão de menu do pad) respondido pelo código:** o ≡ abre o menu com o botão **segurado por 500 ms**
(`GameViewModelTouchControls.kt`, `MENU_LOADING_ANIMATION_MILLIS = 500`); um toque curto não abre por desenho.
Segurando 800 ms abriu. Não é defeito do layout.

**SMG com dual-core desligado (mira, desempenho, sacudir, estado):**
- **Mirar com um dedo e apertar com outro funciona** (SMG, modo 2): dedo na área do jogo move o cursor-estrela,
  ✕/○ com outro dedo clica. Com a escala ajustada ao SMG (cursor ≈ 1,33 × toque nos dois eixos, coordenadas de
  tela inteira em paisagem: `x_c ≈ 1,33·x − 446`, `y_c ≈ 1,35·y + 55`) a mira acertou ícone e botões de primeira.
  **A escala muda de jogo para jogo** (Wii Play ~1,8×, SMG ~1,33×) — o cursor do jogo não é o mesmo nos dois —,
  então a calibração tem de sair de `dolphin_ir_yaw/pitch/offset`, não de um fator fixo no app.
- **Desempenho do SMG em single-core:** 58–60 fps nos menus, **40–55 fps** no 3D (seleção de arquivo, Toad Town).
- **Portas 1–3 com Wii Remote ligam o "co-star" do SMG** (aparece um cursor "P2"). Na Fase 1 só a porta 0
  recebe Wii Remote por padrão.
- **Sacudir:** no SMG, R2 vs L2 ficou **inconclusivo** (no prólogo em Toad Town o giro talvez nem esteja
  liberado; um quadro com R2 pareceu o giro, a repetição não). Refazer no DKCR (deitado, 513: sacudir = tapa no chão
  desde a 1ª fase), que também cobre o item 3.
- **Salvar estado (autosave na saída), single-core:** "Sair" do menu → `…rvz.state` de 34 MB em
  `files/states/dolphin/` e `Process … :game exited cleanly (0)` ~5 s depois. **Carregar não foi testado**: no
  relançamento o dono pegou o aparelho durante o "Preparando o ambiente" e o `:game` não subiu.

**Estado no fim da sessão 3:** worktree `Lemuroid-wii`, branch `spike-wii`, sem commit. O spike agora expõe no menu
do jogo, além do `dolphin_ir_mode`: `dolphin_main_cpu_thread` ("Dual core"), `dolphin_ir_yaw`, `dolphin_ir_pitch`,
`dolphin_ir_offset` (strings `setting_dolphin_*` em `retrograde-app-shared/.../values/strings.xml`). Esse build
(`assembleFreeBundleDebug` em 1m43s; `lastUpdateTime` 2026-10-26 00:47) está **instalado** no G86. Preferências do sistema Wii gravadas no aparelho:
`dolphin_ir_mode = 2` (toque) e `dolphin_main_cpu_thread = disabled`. O save do SMG tem um arquivo (planeta 1,
Mario) e o autosave em Toad Town; o Wii Play tem save e o Mii convidado. Aparelho restaurado: `peak/min_refresh_rate`
apagados (`null`) e rotação automática (`accelerometer_rotation=1`).

**Próximas medições (sessão 4), em ordem:**
1. Carregar o autosave do SMG (single-core) e jogar 1 min; repetir salvar/carregar pelo menu ("Salvar"/"Carregar").
2. Calibrar o toque: no menu "Play again / Play a different game" do Wii Play (ou na seleção de arquivo do SMG),
   variar `dolphin_ir_yaw/pitch` (começar em 15) e `dolphin_ir_offset` até o cursor cair sob o dedo; anotar os
   valores dos dois jogos.
3. DKCR com "Wii Remote (sideways)" (513): andar, pular, **R2 = sacudir** (tapa no chão), e L2 para comparar.
   Depois 1 (em pé), 1025 (Classic, MKW) e 1537 (GC no Wii, MKW), trocando pelo seletor no meio do jogo.
4. MKW em single-core: `EMUFPS` numa corrida, painel travado em 60 Hz — decide se dá para impor
   `dolphin_main_cpu_thread=disabled` a todo o Wii ou se precisa de lista por jogo.
5. Cenário 3D preto no estande de tiro do Wii Play. Não é o dual-core (o `RHA.ini` já força single-core);
   começar pelos hacks de EFB/XFB do core.
6. Zelda TP (JP) com Nunchuk; SM-A127M como piso.
- Antes de medir FPS: `settings put system peak_refresh_rate 60.0` e `min_refresh_rate 60.0`; travar rotação
  (`accelerometer_rotation 0`, `user_rotation 1`) para os toques injetados caírem no lugar. Restaurar no fim.
- O deep link com o app frio passa ~30 s em "Preparando o ambiente" (lateral acima); esperar `Start proc …:game`.

**Ferramentas do spike** (scratchpad da sessão 3,
`C:\Users\luist\AppData\Local\Temp\claude\c--projects-lemuroid-Lemuroid\b9bed721-3b56-458e-b98a-5e70e114863c\scratchpad\tools\`;
o `mt.dex` também está em `/data/local/tmp/` no G86): `mt` injeta multitoque por `app_process` +
`InputManagerGlobal.injectInputEvent` (o `/dev/input` dá *Permission denied* no G86 de produção, e o
`input` do shell não faz dois dedos); `ui.sh` lê textos/coordenadas do `uiautomator dump`.

### Fase 0 — spike (2026-10-26, sessão 4)

**Aparelho:** G86 em USB, mesmo build da sessão 3 (`lastUpdateTime` 2026-10-26 00:47:16). Painel travado em 60 Hz
e rotação travada. O `GameActivity` fica em **retrato** mesmo com `user_rotation 1`: todas as coordenadas abaixo são
de retrato (tela 1220×2712; imagem do jogo em x 0–1220, y 550–1236, 16:9). Ids no banco: DKCR 347254, Kirby 347255,
MKW 347256, SMG 347257, Wii Play 347258, Zelda TP 347259.

**Lateral resolvido pelo código — os ~30 s em "Preparando o ambiente":** `ExternalGameLauncherActivity.waitPendingOperations()`
espera `getLoadingLiveData()` ficar falso com `withTimeoutOrNull(30_000L)`. É a mesma armadilha da sessão 1: o
`CoreUpdateWork` enfileirado com `setInitialDelay(2 min)` conta como operação em andamento (`ENQUEUED`), então todo
deep link nos 2 primeiros minutos depois de abrir o app paga o timeout inteiro. Medido de novo: `Start proc …debug`
00:56:15,2 → `Start proc …debug:game` 00:56:46,8 (31,6 s). Não é do Wii; afeta todo lançamento externo (atalho,
deep link) com o app frio. Não corrigido.

**1. Autosave e salvar/carregar (SMG, single-core) — funciona.**
- O deep link chama `launchGameAsync(…, loadSave = true, …)`; o `.state` de 34 MB da sessão 3 carregou e o SMG voltou
  em Toad Town, na posição salva. `EMUFPS` 41–42 ali (3D), 53 depois do load seguinte.
- Menu ▸ Salvar ▸ Estado 1 → `…rvz.slot1` de 30 MB. Andei para longe, Menu ▸ Carregar ▸ Estado 1 → Mario de volta à
  torre, jogo seguindo a 53 fps por mais de 40 s. Saída por "Sair" → `.state` regravado (32 MB, 01:08).
- O 1º `EMUFPS` depois do load mede 8,58 fps (180 frames em 20,97 s): é o intervalo do menu aberto somado, não queda.
- **Giro do SMG não testável em Toad Town:** o giro só é liberado depois do encontro com a Luma (fim do prólogo).
  R2 em Toad Town só acende o HUD. Fica para o DKCR/Kirby.

**2. Calibração do toque (`dolphin_ir_mode = 2`) — não existe um trio que sirva aos dois jogos.**
- O LibretroDroid normaliza o toque sobre o **retângulo do vídeo** (`VideoLayout::getRelativePosition`; fora dele vira
  "outside"), então a medida em coordenadas relativas à imagem vale em retrato e em paisagem.
- No core (`Input.cpp:815-834`), `dolphin_ir_offset/yaw/pitch` vão para os `numeric_settings[1..3]` do grupo `Point`
  do Wii Remote: *Vertical Offset* (cm), *Total Yaw* e *Total Pitch* (graus de ponta a ponta). Ganho do cursor ∝ 1/ângulo.
- Método: dedo parado 1,5 s em 4–6 pontos (0,25/0,5/0,75 e 0,15/0,85 da imagem), screencap com o dedo ainda na tela,
  cruz vermelha no ponto do toque (`aim.sh` nas ferramentas). Opções trocadas pelo menu do jogo (`Updating core variable`).

| Jogo (cursor) | yaw/pitch/offset | Ganho H / V | Desvio vertical no centro | Veredito |
|---|---|---|---|---|
| SMG (estrela) | 25/25/0 (padrão) | 1,34 / 1,32 | +0,22·altura (abaixo) | — |
| SMG | 19/19/0 | 1,0 / 1,0 | +0,22 | — |
| SMG | 19/19/10 | 1,0 / 1,0 | +0,07 | — |
| SMG | **19/19/15** | 1,0 / 1,0 | 0 | ✅ sob o dedo nos 4 pontos, inclusive 0,15 e 0,85 |
| Wii Play (mão, ponta do dedo) | 19/19/15 | 1,41 / 1,40 | +0,22 | ❌ |
| Wii Play | 14/14/15 | 1,04 / 1,0 | +0,21 | — |
| Wii Play | **14/14/25** | 1,0 / 1,0 | 0 | ✅ sob o dedo nos 6 pontos |

- **O mapeamento IR→tela é de cada jogo** (Wii Play precisa de ~1,36× menos ângulo que o SMG). Um padrão único deixa
  o cursor a até ~0,2·altura do dedo em algum jogo. Opções para a Fase 1: (a) padrão intermediário (ex.: 16/16/20) e
  aceitar o desvio, já que o cursor visível deixa o jogador compensar; (b) manter yaw/pitch/offset no menu do jogo
  (por sistema, hoje — por jogo exigiria código novo); (c) tabela por jogo só para os títulos de ponteiro mais
  populares. **Decisão do dono.** Corrige a sessão 3: a escala "~1,8×" do Wii Play era com yaw 25; o fator entre os
  jogos é constante (1,8/1,33 ≈ 1,41/1,0).
- Efeito do offset ≈ 0,015·altura por cm com pitch 19 (0,152 em 10 cm), e cresce quando o pitch cai.
- **Lateral (spike):** as listas de valores do menu do jogo mostram o padrão do core primeiro (`25`, `0`) e os valores
  0–14 no fim, depois do 100. Confirma o achado da sessão 3 sobre o primeiro valor exposto.
- O cursor "2" (co-star) aparece também no título do Wii Play: portas 1–3 com Wii Remote (já anotado na sessão 3).
- **Preferências que ficaram no aparelho:** `cv_wii_dolphin_ir_yaw = 14`, `cv_wii_dolphin_ir_pitch = 14`,
  `cv_wii_dolphin_ir_offset = 25` (as do Wii Play), em `files/harmony_prefs/harmony_options/prefs.data`.

**5. Wii Play — o preto não é só o estande de tiro, e não é hack de EFB/XFB.**
- Já na escolha de Mii e na seleção de jogos **só os Miis aparecem**; fundo, molduras e textos ficam pretos. A caixa
  "You can't save My Record data with this Mii" aparece normal. `EMUFPS` 60.
- **O core grava as opções na camada Base** (`Config::SetBase` em `Boot.cpp:231-456`, inclusive todos os
  `GFX_HACK_*`), então um INI em `<saves>/User/GameSettings/<ID>.ini` (camada de jogo local) as sobrescreve. **Provado:**
  `RHAE01.ini` com `[Core] CPUThread = True` → `CPU Thread separate = Yes` no boot (o `Sys/GameSettings/RHA.ini` força
  `False`). Esse é o caminho mais barato para A/B de opções que o app não expõe — nenhum rebuild.
- Com `RHAE01.ini` desligando `EFBToTextureEnable`, `XFBToTextureEnable`, `DeferEFBCopies`, `EFBScaledCopy` e
  `FastDepthCalc`, e ligando `EFBEmulateFormatChanges` e `ImmediateXFBEnable` → **continua preto**. Hipótese descartada.
  (`InternalResolution` no INI não muda o `VIDEOFRAMES` — esse log mostra a geometria base, não serve de controle.)
- Sem erro de GL/shader no log em nível Info. O driver avisa `Missing OGL Extensions: DualSourceBlend PinnedMemory
  GSInstancing ClipControl DepthClamp` (Mali-G615, GLES 3.2). **Próximo passo:** o mesmo trecho num aparelho de outra
  GPU (o SM-A127M é PowerVR) separa "defeito do Mali/GLES" de "defeito do core"; depois `dolphin_log_level` mais
  verboso.
- **Depois de carregar o autosave, o rosto do Mii perde os olhos** (antes do save estavam lá). Textura gerada em
  tempo de execução que não volta com o estado. Não investigado.
- INIs de teste apagados do aparelho no fim (`User/GameSettings/` vazio).

**3. DKCR — o ROM da fonte está truncado.**
- Boot falha: `"…Donkey Kong Country Returns (USA) (En,Fr,Es) (Rev 1).rvz" is an invalid GCM/ISO file` →
  `Cannot load game`, e o `:game` sai com 0.
- O arquivo no HF tem **77.840.066 bytes** (`paths-info` e `X-Linked-Size`), igual ao do aparelho: o download estava
  certo. O cabeçalho RVZ é válido (`RVZ\1`, jogo `SF8E01`), mas o campo de tamanho do arquivo RVZ (offset 44, u64 BE)
  diz **3.492.868.500 bytes**: o upload para o dataset foi cortado em 2,2%.
- **Impacto na Fase 3:** o dataset tem arquivos truncados. Checagem barata e exata: `Range: bytes=0-51` de cada `.rvz`
  e comparar o u64 do offset 44 com o `size` do `paths-info`. Arquivo truncado vira 404 no catálogo, nunca download.
  **Validada nos outros 5 ROMs:** nos 5 o campo bate byte a byte com o tamanho real (Kirby 1.308.625.404, MKW
  2.785.150.188, SMG 3.425.535.460, Wii Play 235.554.716, Zelda TP 1.004.307.956).

**3. Deitado (513) e sacudir — no Kirby's Return to Dream Land (no lugar do DKCR).**
- Troca 769 → 513 pelo seletor no meio do jogo (tela da alça): `Controls setting 0 to 513`; as portas 1–3 seguem 769.
- Andar (D-pad), pular (2 = ○, libretro A) e aspirar (1 = ✕, libretro B) funcionam; no deitado o **A do Wii é o △**
  (libretro X) — o título do MKW em 513 só avança com △.
- **Sacudir = R2, confirmado:** aspirando (✕ segurado) + **R2 segurado ~1 s** → super aspiração (vento vermelho, clarão),
  reproduzido. **L2 segurado 1 s → só a aspiração normal.** R2 de 150 ms falhou 1 de 2 vezes: o Shake do Dolphin é
  uma oscilação enquanto o botão está apertado, e um toque curto mal completa um ciclo. **Fase 1:** o botão de
  sacudir do layout tem de ser "segurar" (ou o app manda um pulso de ≥ 0,5 s), senão o toque rápido falha.
- O dispositivo escolhido **persiste por sistema** (`pref_key_controller_type_wii_dolphin_0`): o MKW subiu em 513.

**Troca de dispositivo no meio do jogo (MKW):**
- 513 → 1025 (Classic) na **seleção de licença**: o jogo mostra "The controller has changed. Please press the A
  Button" com o ícone do Classic; A (○) aceita e o rodapé vira "Ⓑ Back".
- 1025 → 1537 (GameCube no Wii): "Communication with the controller has been interrupted. Please press the A Button";
  A aceita, rodapé com o B vermelho do GC. O **pad de toque troca sozinho para o layout de GameCube** (A/B/X/Y, L/R/Z).
- **Na tela de título** a troca 513 → 1025 deixou o título surdo a todos os botões, mesmo depois de voltar a 513, até
  relançar o jogo (`EMUFPS` 60, foco no `GameActivity`). Com 513 desde o boot, △ no título avança normalmente
  (controle feito). Hipótese descartada no caminho: retorno antecipado do `load_saved_controller_config` antes do
  `SetSelectedAttachment` — ele devolve `nullptr` com `dolphin_save_load_settings = disabled` (o valor em uso).
- **Não testado:** Wii Remote em pé (1). O MKW não aceita; o jogo certo é o Wii Play.

**4. MKW em corrida, painel travado em 60 Hz (`mActiveModeId=3`), controle GameCube — dual-core faz falta.**
- Método: Estado 2 salvo na introdução da Luigi Circuit (50cc, Mushroom Cup); carregar pelo menu e segurar A 40 s
  (`mkrun.sh`), mesma janela nos dois modos. `CPUThread` só vale no boot: troca pelo menu + relançar
  (`CPU Thread separate = Yes/No` no log).

| Modo | `EMUFPS` (blocos de 180 frames) | Relógio da prova ao fim dos ~40 s |
|---|---|---|
| single-core | 48,2 48,7 44,2 44,4 46,6 45,4 50,6 60,5 58,6 60,3 59,5 | 25,9 s |
| dual-core | 58,6 60,1 59,7 60,0 59,3 58,7 60,0 59,3 59,9 60,5 60,0 59,4 59,9 | 32,1 s |

  (O 1º valor de cada rodada, 18,9 / 52,4, inclui o menu.) Em single-core a largada, com os 12 karts na tela, cai
  para **44–50 fps** e o jogo fica lento de verdade (o relógio da prova anda menos). O 60 do fim é o kart parado na
  grama (sem esterço), olhando para pouca coisa. Em dual-core não houve trava em ~1 min de corrida.
- **Decisão para a Fase 1:** impor `dolphin_main_cpu_thread=disabled` a todo o Wii custa ~20% no MKW e o deixa abaixo
  do tempo real no G86 (aparelho bom). Então **lista por jogo**: single-core só para quem trava (SMG, `RMG*`, cujo
  `RMG.ini` liga `EFBAccessEnable`), dual-core no resto. Caminho barato para isso já existe: o core respeita
  `[Core] CPUThread` dos INIs de jogo (provado no item 5), então basta provisionar um `User/GameSettings/RMG.ini`
  com `CPUThread = False` em vez de código no app. Falta medir quais outros jogos com `EFBAccessEnable` travam.
- Observação não investigada: o kart do jogador e as prévias de personagem/kart na escolha de câmbio aparecem
  escuros/como silhueta preta — pode ser o mesmo defeito de render do Wii Play (Mali).

**Estado no fim da sessão 4:** worktree `Lemuroid-wii`, branch `spike-wii`, sem commit, sem mudança de código nesta
sessão. Aparelho restaurado: `peak/min_refresh_rate` apagados (`null`), `accelerometer_rotation=1`, `User/GameSettings/`
vazio. **Preferências do Wii que ficaram no G86:** `dolphin_ir_mode=2`, yaw/pitch/offset `14/14/25` (as do Wii Play),
**`dolphin_main_cpu_thread` = ligado** (estava desligado na sessão 3; o SMG trava assim — desligar antes de abrir o
SMG), Controle 1 = GameCube (`wii_gamecube`). Estados: SMG `.state` + `slot1`; Wii Play `.state` (na seleção de jogos);
MKW `slot2` (introdução da Luigi Circuit), licença nova com Mii convidado; Kirby com arquivo 1 iniciado.
Ferramentas (`aim.sh`, `burst.sh`, `pick.sh`, `mkrun.sh`, `lg.sh`, `strip.py`, `aimgrid.py` + as da sessão 3) em
`C:\Users\luist\AppData\Local\Temp\claude\c--projects-lemuroid-Lemuroid\7b29f45a-becf-4e3b-bb66-2a0cd485cf0b\scratchpad\tools\`
(caminhos do scratchpad gravados dentro dos scripts; o `mt.dex` segue em `/data/local/tmp/` no G86).

**Próximas medições (sessão 5), em ordem:**
1. Zelda TP (JP) com Nunchuk (769) no G86: andar, câmera, mira (modo 2), sacudir o Nunchuk (L2 segurado).
2. SM-A127M (piso, GPU PowerVR): instalar o mesmo build, rodar o Wii Play até a seleção de jogos e o MKW na escolha
   de câmbio — **se o preto/silhueta sumir, é defeito do Mali/GLES**; FPS do MKW com `mkrun.sh` (trocar o serial
   `ZY32LMNN9B` nos scripts).
3. Wii Remote em pé (1) no Wii Play.
4. Outros jogos com `EFBAccessEnable` no `Sys/GameSettings` em dual-core, para fechar a lista por jogo.

## Estimativa

- Fase 0: 1 sessão, mais as ROMs de teste.
- Fase 1: 1 a 2 sessões; os layouts são o grosso.
- Fase 2: 1 sessão.
- Fase 3: 1 a 2 sessões de script e scraping, depois que a fonte de ROMs existir.
