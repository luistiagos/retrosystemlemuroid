# [BACKLOG] Suporte a Nintendo Switch — viabilidade

**Data:** 2026-10-27
**Status:** dono escolheu o caminho A. Fase 0, rodadas 1 e 2 feitas em 2026-10-28 (spike na worktree `Lemuroid-switch`, sem commit). Jogo comercial (LIMBO) roda, a ~20 fps (limite na GPU ou na sincronia com ela, não na CPU). Falta provar um jogo de 64 bits (NCE). Ver "Fase 0 — spike do caminho A".
**Origem:** pedido do dono ("Verifique a possibilidade de adicionarmos o console switch no Lemuroid, nesta etapa não
implemente nada, apenas verifique a possibilidade"), sessão de planejamento.
**Tipo:** feature
**Esforço:** muito grande no caminho A (core, distribuição e degrau de aparelho novos); médio no B.

## Contexto

O critério do projeto (`docs/backlogs/inclusaosystemas.md` e `docs/prompts/adicionar-novo-sistema-libretro.md:61-72`) é
ter um core libretro com `.so` Android. Emulador nativo fora do libretro não é portado. O Switch não estava na lista
original de 36 sistemas.

A avaliação respondeu quatro perguntas:
1. Existe um core libretro de Switch para Android?
2. O LibretroDroid consegue rodá-lo?
3. O público do app consegue jogar?
4. Qual é o risco jurídico e operacional?

## Veredito

**Tecnicamente possível pela primeira vez, mas não recomendado agora.**

- **O que mudou.** Desde outubro de 2026 o projeto `suyu-emu/suyu-main` publica um core libretro com build Android
  arm64. Ele roda no LibretroDroid mesmo o LibretroDroid só tendo GL: o core renderiza em Vulkan por conta própria e
  entrega um framebuffer de software pronto.
- **O que pesa contra:**
  1. **Público.** O core só roda em arm64, com Android 8+, GPU com Vulkan 1.1 e uns 8 GB de RAM, ou seja, em aparelho
     topo de linha. As TV box, que são metade do público-alvo, ficam de fora, e os dois aparelhos de teste são fracos.
  2. **Core.** Não tem save state, reset nem pausa: o jogo continua rodando, com som, atrás do menu do Lemuroid. Aceita
     um jogador só. Nasceu em setembro de 2026 e tem regressões relatadas entre versões.
  3. **Chaves.** O core recusa NSP/XCI/NCA sem as `prod.keys` do console, que o usuário precisa fornecer. Não existe o
     "baixar e jogar" dos outros sistemas.
  4. **Jurídico e infraestrutura.** A Nintendo mandou 15 avisos de DMCA ao GitHub em 2026, o último em 23/10, contra
     repositórios que "oferecem, linkam ou dão acesso" a emulador de Switch. Pelos guards de build atuais, todo core vai
     para o repositório **público** `luistiagos/libretrocores`, de onde o app baixa os cores de **todos** os sistemas.
     Um takedown ali quebraria o download de cores do app inteiro.

## O que a análise provou

### O core

Fonte lida na tag `v14.1.0` de `suyu-emu/suyu-main`: `src/libretro_core/retro_core.cpp`, `retro_emu_window.cpp` e
`README.md`. Binário: `suyu-libretro-android-arm64.zip` da mesma release.

| Fato | Evidência |
|---|---|
| O core existe: `suyu-emu/suyu-main`, fork de `suyu-emu/suyu-v0.0.4`, licença GPL-3.0 (os arquivos do core dizem GPL-2.0-or-later). Repositório criado em 2026-09-10 e último push em 2026-10-12. O endereço antigo `dougchansan/suyu-v0.0.4` redireciona para ele | API do GitHub, `repositories/1364719826` |
| As releases v0.0.13 (09/10), v0.0.14 (10/10), v14.0.1 (11/10) e v14.1.0 (12/10) publicam `suyu-libretro-android-arm64.zip` (13.759.170 bytes na v14.1.0). As releases v0.0.5 a v0.0.12 aparecem sem assets | `/releases?per_page=30` |
| O buildbot oficial do libretro não tem core de Switch. Dos 246 cores arm64, a busca casou só falsos positivos (`gearlynx`, `lowresnx`, `mednafen_lynx`, `nxengine`) | comando 3 abaixo |
| O `.so` é só **arm64-v8a** (ELF64 AArch64). Não há v7a, x86 nem x86_64, então nenhum AVD roda | `llvm-readelf -h` |
| Exige **API 26 (Android 8.0)**: a `.note.android.ident` começa com `1a 00 00 00` (= 26), NDK `r28c`; versões de símbolo `LIBC_N` e `LIBC_O` | `llvm-readelf -n` e `-V` |
| O arquivo se chama `suyu_libretro_android.so`, **sem o prefixo `lib`**, e teria de ser renomeado (pitfall 14). Tem 46.313.800 bytes descomprimido | `unzip -l` |
| `NEEDED`: `libm`, `libOpenSLES`, `liblog`, `libmediandk`, `libandroid`, `libdl`, `libc`. A libc++ é estática, então não conflita com a do app. Não importa `tmpfile`/`mkstemp`/`tmpnam` (pitfall 10 ok) e linka `libandroid` (pitfall 6a não se aplica). O `dlclose` importado é do próprio core para o `libvulkan.so`, não descarga do core (pitfall 13) | `llvm-readelf -d`; comando 2 abaixo |
| **Vídeo:** Vulkan "headless" com cópia do quadro para a CPU, entregue como XRGB8888 em `retro_video_refresh`. Não chama `SET_HW_RENDER`. Carrega o `libvulkan.so` do sistema, então **não usa os drivers Turnip customizados** do Eden standalone | `retro_core.cpp:13-15`, `:245-246`, `:600-637`; `retro_emu_window.cpp:18-28`; README: "The current libretro video path supports Vulkan" |
| Quadro repetido vai como `NULL` quando o frontend aceita *dupe*. O LibretroDroid aceita, então não há cópia quando o jogo não desenhou nada novo | `retro_core.cpp:606-609`, `:961-964`; `environment.cpp:233-235` |
| Resolução base 1280×720 (até 4×), 60 fps, 48 kHz | `retro_core.cpp:141-142`, `:411-422` |
| **Chaves:** o core procura `prod.keys`, `title.keys` e `console.keys` em `<system>/suyu/keys` e recusa NSP/XCI/NCA sem chave de cabeçalho utilizável. Só `.nro` (homebrew) carrega sem chaves | `retro_core.cpp:97-104`, `:147-197`, `:712-718` |
| Extensões `nsp\|xci\|nca\|nro`, com `need_fullpath = true`; o arquivo precisa ser um caminho comum (`is_regular_file`) | `retro_core.cpp:404-408`, `:679-684` |
| No Android, o diretório do app suyu é `<system>/suyu`: NAND, saves dos jogos, cache e logs ficam ali. Sem `GET_SYSTEM_DIRECTORY` o `retro_init` aborta. O LibretroDroid responde essa chamada | `retro_core.cpp:301-319`; `environment.cpp:287-288` |
| **Sem save state:** `retro_serialize_size` = 0 e `serialize`/`unserialize` devolvem `false`. Segundo o comentário do autor, nenhum emulador derivado do yuzu tem serialização | `retro_core.cpp:29-34`, `:640-662` |
| **Sem reset:** `retro_reset` só loga um aviso. **Sem SRAM:** `retro_get_memory_*` = 0/`nullptr` | `retro_core.cpp:426-429`, `:1021-1027` |
| **Sem pausa:** o `retro_load_game` chama `g_system->Run()`, que sobe as threads de emulação. O `retro_run` só lê o input, entrega o áudio (se o áudio for pelo frontend) e manda o último quadro. Não há `Pause` em lugar nenhum do arquivo, e `IsShown()` devolve sempre `true` para o renderer não se pausar | `retro_core.cpp:458-638`, `:970`; `retro_emu_window.cpp:86-90` |
| **Áudio:** por padrão o core toca direto no aparelho (`sink_id = Auto`, `libOpenSLES` no `NEEDED`). A opção `suyu_audio_output = Frontend (libretro)` existe, mas o autor diz que sai em rajadas | `retro_core.cpp:333-344`, `:567-598`, `:821-830` |
| **Input:** só a porta 0, com 16 botões e 2 analógicos (+ = Start, − = Select, ZL/ZR = L2/R2). Sem toque nem movimento. O `retro_set_controller_port_device` é vazio | `retro_core.cpp:424`, `:437-454`, `:536-556` |
| **Atualização de jogo:** só pela env `SUYU_LIBRETRO_UPDATE_PATH` ou pela propriedade `debug.suyu.libretro.update` (que um app comum não grava). O core não instala atualização nem DLC na NAND | `retro_core.cpp:82-95`, `:725-735`; README |
| Opções: `suyu_resolution`, `suyu_scaling_filter`, `suyu_anti_aliasing`, `suyu_cpu_accuracy`, `suyu_use_docked` (padrão `Yes` = 1080p docked), `suyu_fastmem`, `suyu_audio_output`, `suyu_online_enable` e `suyu_show_perf` (padrão `On`). Todas, menos `show_perf`, só valem ao recarregar o jogo | `retro_core.cpp:248-271`, `:769-838` |
| **Maturidade:** no fórum do libretro (set/2026), a 0.0.4 não funcionava para ninguém. A 0.0.11 rodou bem em alguns jogos, mas Super Mario Wonder travou. Na 0.0.12 o Tetris Effect caiu de 60 para ~10 fps, e jogos com atualização separada não carregaram. Nenhum relato de Android | forums.libretro.com, tópico 49153, p. 5, posts #86–#97 |

### O Lemuroid

| Fato | Evidência |
|---|---|
| O LibretroDroid é só GL. O `SET_HW_RENDER` liga `eglGetProcAddress` e o framebuffer GL sem olhar o tipo de contexto; a preferência declarada é GLES3; o `GET_HW_RENDER_INTERFACE` (que um core Vulkan pede) cai no `default → false`. Os fontes Vulkan do `libretro-common` vendorizado nem entram no build | `LibretroDroid-patched/libretrodroid/src/main/cpp/environment.cpp:152-164`, `:262-270`, `:363-365`; nenhum "vulkan" em `CMakeLists.txt` |
| O framebuffer de software XRGB8888 é suportado pelos dois renderers | `renderers/es2/imagerendereres2.cpp:33,87`, `renderers/es3/imagerendereres3.cpp:96` |
| No `ON_PAUSE` o `GLRetroView` chama `LibretroDroid.pause()` e `onPause()`, que só param o `retro_run`. **Com este core, o jogo segue rodando e tocando atrás do menu do Lemuroid.** O "Silenciar áudio" do menu também não alcança o áudio próprio do core | `GLRetroView.kt:571-577`; `GameMenuHomeScreen.kt:84` |
| Core sem estado já é suportado: `statesSupported = false` esconde salvar/carregar (mobile e TV) e desliga o autosave. Precedentes: MAME 2003-Plus, MAME 0.289, DOS, C64 e **3DS**, que é também o precedente de core só arm64 (`supportedOnlyArchitectures`) | `SystemCoreConfig.kt:13,20`; `GameMenuHomeScreen.kt:34`; `GameMenuHelper.kt:47-50`; `TVGameMenuFragment.kt:118-119`; `GameViewModelSaves.kt:278`; `GameSystem.kt:1448,1477,1826,1899-1901,2066` |
| O "Reiniciar" aparece sempre, sem flag para esconder. Com este core ele não faria nada | `GameMenuHomeScreen.kt:71-82`; `tv_game_settings.xml:22-24` |
| O `HeavySystemFilter` corta só por RAM, e o degrau mais alto é "NORMAL = mais de 2 GB". Não há degrau para aparelho topo de linha nem checagem de Vulkan | `HeavySystemFilter.kt:22-104` (classificação em `:91-104`) |
| **Os guards de build obrigam todo `CoreID`** a ter o `.so` em `bundled-cores/src/main/jniLibs/arm64-v8a/`, ou seja, dentro do APK (`verifyNames`, roda em todo build). No release, o arquivo também tem de estar na tag `CORES_VERSION` de `luistiagos/libretrocores` (`verifyVersioned`) | `BundledCoresVerifier.kt:21-28`, `:76-88`, `:112-129` |
| O app baixa os cores de `https://raw.githubusercontent.com/luistiagos/libretrocores/<CORES_VERSION>/lemuroid_core_<coreName>/…` | `CoreDownloader.kt:28-29`, `:71` |
| **Os dois repositórios do projeto são públicos no GitHub**: `luistiagos/libretrocores` (cores) e `luistiagos/retrosystemlemuroid` (app) | comando 5 abaixo |

### Jurídico e público

| Fato | Evidência |
|---|---|
| **15 avisos de DMCA da Nintendo no GitHub em 2026:** 3 em fevereiro (12, 20 e 27), 7 em 17/08, 2 em setembro (3 e 30) e 3 em outubro (2 em 01/10 e 1 em **23/10**) | comando 4 abaixo |
| O aviso de 12/02 lista 353 repositórios. O primeiro de 17/08 lista 316, e "suyu" aparece 307 vezes: foi uma derrubada em massa de forks do suyu. O `suyu-main` foi criado depois, em 10/09 | contagem por `grep` (comando 4) |
| A base é a anticircunvenção (DMCA §1201): as `prod.keys` seriam a medida de proteção. O alvo são repositórios que *"offer, link to, or otherwise provide access to Nintendo Switch emulator(s)"* | texto de `2026-08-17-nintendo.md` |
| Precedentes: Yuzu (acordo de US$ 2,4 milhões, mar/2024) e Ryujinx (encerrado em out/2024). O Eden entrou na Play Store em 12/09/2025 e tinha saído em 26/09/2025, sem explicação | Android Authority (Eden); fatos conhecidos (Yuzu, Ryujinx) |
| Segundo um resumo dos requisitos oficiais do Eden, o Android exige GPU com Vulkan 1.1, e a maioria das PowerVR e Xclipse não é suportada. Um guia de 2026 cita Snapdragon 8 Gen 2 como mínimo prático (estimativa de blog, não oficial) | tech-insider.org (fonte secundária) |
| **Público:** a TV box é "metade do público-alvo da distribuição" e usa a build armeabi-v7a; o `minSdk` é 21. Os aparelhos de teste são fracos para Switch: o Moto G86 é MediaTek mt6878 com Mali, de linha intermediária, e o outro é o SM-A127M | `docs/funcionalidades/atualizacao-automatica.md:229`; `docs/backlogs/2026-10-25-suporte-nintendo-wii.md` (sessão 3) |

### Comandos que provaram

```bash
# 1. Repositório e releases (o endereço antigo redireciona: "Moved Permanently" → repositories/1364719826)
curl -sL https://api.github.com/repositories/1364719826           # suyu-emu/suyu-main, GPL-3.0, created 2026-09-10, pushed 2026-10-12
curl -sL "https://api.github.com/repositories/1364719826/releases?per_page=30"
#    v14.1.0: suyu-libretro-android-arm64.zip 13759170 (também v0.0.13, v0.0.14, v14.0.1)

# 2. Binário (zip baixado numa pasta isolada; nada executado). NDK 29 em D:/DevCaches/Android/Sdk/ndk/29.0.14206865
unzip -l core.zip                                                  # suyu_libretro_android.so 46313800
llvm-readelf -h suyu_libretro_android.so                           # ELF64, AArch64
llvm-readelf -d suyu_libretro_android.so | grep NEEDED             # libm libOpenSLES liblog libmediandk libandroid libdl libc
llvm-readelf -n suyu_libretro_android.so                           # .note.android.ident: 1a 00 00 00 ("r28c")
llvm-readelf -V suyu_libretro_android.so | grep -oE "LIBC_[A-Z0-9]+" | sort -u   # LIBC_N LIBC_O
llvm-nm -D --undefined-only suyu_libretro_android.so | awk '{print $NF}' | sed 's/@.*//' \
  | grep -E "^(tmpfile|mkstemp|mkstemps|tmpnam|tempnam|ASharedMemory_create|memfd_create|shm_open|dlclose|dlopen|__system_property_get)$"
#    → __system_property_get dlclose dlopen

# 3. Buildbot oficial
curl -sL https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/ \
  | grep -oiE '[a-z0-9_]+_libretro_android\.so\.zip' | sort -u    # 246 cores
#    grep -iE "suyu|yuzu|eden|citron|sudachi|ryujinx|skyline|strato|switch|nx" → só gearlynx, lowresnx, mednafen_lynx, nxengine

# 4. Avisos de DMCA (repositório público github/dmca)
curl -sL https://api.github.com/repos/github/dmca/contents/2026/02   # (idem 08, 09, 10) → nomes com "nintendo"
curl -sL https://raw.githubusercontent.com/github/dmca/master/2026/08/2026-08-17-nintendo.md -o n.md
grep -oiE 'github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+' n.md | sort -u | wc -l   # 316 (353 no de 2026-02-12)
grep -ci suyu n.md                                                   # 307

# 5. Visibilidade dos repositórios do projeto (sem token: 200 = público)
curl -s -o /dev/null -w "%{http_code}" https://api.github.com/repos/luistiagos/libretrocores          # 200
curl -s -o /dev/null -w "%{http_code}" https://api.github.com/repos/luistiagos/retrosystemlemuroid    # 200
```

## Contrato do core × Lemuroid

| Recurso | Core suyu | Lemuroid hoje | Consequência |
|---|---|---|---|
| Save state / autosave | não tem | `statesSupported = false` já esconde e desliga | ok, padrão do 3DS |
| Reiniciar | só loga | item sempre visível (mobile e TV) | flag nova, ou reinício por relançamento do jogo |
| Pausa | não tem | `ON_PAUSE` só para o `retro_run` | o jogo roda e toca atrás do menu; corrigir exige patch no core |
| Áudio | direto no aparelho (OpenSL) | o "Silenciar" atua no áudio do LibretroDroid | o "Silenciar" não funciona; o som continua no menu |
| Saves dos jogos | NAND em `<system>/suyu/` | saves em `<saves>` | os saves ficam fora da pasta de saves do app |
| Input | porta 0, 16 botões + 2 analógicos | já existe pad com 2 analógicos (`PSX_DUALSHOCK`, usado no spike do Wii) | layout novo com ZL/ZR, L3/R3 e +/− |
| Toque / movimento | não tem | — | jogos que dependem de toque ficam injogáveis |
| Atualização / DLC | env `SUYU_LIBRETRO_UPDATE_PATH` | — | `setenv` no `:game` antes do `retro_load_game` (precedente: `NativeTempDir`, pitfall 10); sem DLC |
| Chaves | `<system>/suyu/keys` | — | tela para o usuário importar as próprias chaves |
| Distribuição | `.so` sem `lib`, 46 MB, só arm64 | os guards exigem o core no APK e na tag pública | mecanismo novo de distribuição (ver Riscos) |
| Aparelho | arm64, API 26, Vulkan 1.1, ~8 GB | filtro só por RAM, máximo "> 2 GB" | degrau novo de aparelho, nas duas UIs |

## Caminhos

### A — core suyu dentro do Lemuroid

É possível, com as limitações da tabela acima. Exigiria:
- **Distribuição nova.** O core não pode ir para `luistiagos/libretrocores` nem para o APK do jeito que os guards exigem
  hoje (ver Riscos 1 e 2). Isso pede exceção nos dois verificadores e um download próprio, fora do GitHub. Nenhum host
  está imune a DMCA.
- **Registro do sistema:** `SystemID`/`MetaSystemID` `SWITCH`, `CoreID` com o arquivo renomeado para
  `libsuyu_libretro_android.so`, e `GameSystem` com:
  - `statesSupported = false`;
  - `supportedOnlyArchitectures = setOf("arm64-v8a")`;
  - `defaultSettings`: `suyu_use_docked = No` (720p) e `suyu_show_perf = Off`.
- **Degrau de aparelho novo:** arm64 + API 26 + Vulkan 1.1 (`FEATURE_VULKAN_HARDWARE_VERSION`) + uns 8 GB de RAM, no
  mobile e no `TVHomeViewModel`. Lembrar o pitfall 12: `SDK_INT` mente nas TV box.
- Esconder o "Reiniciar"; layout de toque; tela de importação das chaves do usuário.
- **GPL:** publicar o fonte correspondente a cada versão distribuída. A release traz `suyu-v14.1.0-source.tar.gz`
  (93,7 MB) e `suyu-v14.1.0-dependency-sources.tar` (324 MB). É preciso guardar uma cópia, porque o upstream pode sumir.

### B — abrir um emulador externo (Eden/suyu standalone) por intent

- **A favor.** O Lemuroid vira só lançador, sem código de emulador. O jogo roda com desempenho melhor (drivers Turnip,
  pausa e saves do próprio emulador).
- **Contra:**
  - Foge da regra "só libretro" e cria um tipo novo de integração, sem nada do pipeline do Lemuroid (menu, pad, saves).
  - O contrato de intent do Eden **não foi verificado**. Só existem configs da comunidade (ES-DE, Daijishō) que abrem
    uma `EmulationActivity` com `ACTION_VIEW`.
  - O usuário instala o emulador fora da Play Store. Relatos secundários dizem que o Eden tirou as releases do GitHub
    depois dos avisos de fevereiro.
  - Exposição menor, mas não zero: o alvo dos avisos inclui *"link to"*.
- Se o dono quiser, isto vira uma avaliação própria.

### C — não fazer agora (recomendado)

Reavaliar em uns 6 meses (por volta de abril de 2027), conferindo:
1. se `suyu-emu/suyu-main` ainda existe;
2. se o core ganhou pausa ou serialização;
3. se algum core de Switch entrou no buildbot;
4. se houve novas ondas de DMCA.

## Catálogo: fora de todos os caminhos

O catálogo com download sob demanda, como nos outros sistemas, não entra no A nem no B:
- o core não abre NSP/XCI/NCA sem `prod.keys`;
- o app não pode distribuir as chaves, que são exatamente a base dos avisos da Nintendo;
- os jogos são títulos comerciais ainda à venda.

Qualquer caminho é "o usuário traz os próprios dumps e as próprias chaves", importados para a pasta de ROMs.

## Hipóteses descartadas

- **"O LibretroDroid só tem GL, então um core de Switch não roda."** Falso para este core: ele faz o readback e entrega um
  framebuffer de software. Só seria verdade para um core que pedisse `RETRO_HW_CONTEXT_VULKAN`.
- **"Não existe core libretro de Switch."** Existe desde setembro de 2026 (Android desde 09/10), mas fora do buildbot.
- **"Dá para testar no AVD."** Não dá: o core é só arm64 e os AVDs do projeto são x86/x86_64.
- **"Pôr o core no `lemuroid-cores` como os outros."** É o que os guards exigem, e é o que expõe todo o download de cores
  a um DMCA (Risco 1).
- **"Instalar atualização na NAND pelo core."** O core não faz isso; ele só aceita base + atualização na mesma sessão,
  pela env.

## Riscos (se o dono seguir pelo A ou pelo B)

1. **Takedown do repositório de cores.** O texto dos avisos cobre qualquer repositório que dê acesso a emulador de
   Switch. Derrubado o `libretrocores`, o flavor `dynamic` e o fallback de download deixam de achar o core de qualquer
   sistema. O repositório do app também é público.
2. **Play Store.** O Eden durou duas semanas lá (set/2025). Um core de Switch dentro do APK põe o app inteiro nessa
   mesma exposição.
3. **Upstream instável.** Os forks do suyu foram derrubados em massa em 17/08, e o `suyu-main` pode ter o mesmo destino
   e levar consigo as atualizações.
4. **Desempenho.** Há cópia do quadro para a CPU a cada frame, nada de Turnip, e o Mali tem desempenho reconhecidamente
   pior em emulação de Switch. O LMK pode matar o `:game` em aparelho com menos de 8 GB.
5. **Maturidade.** As regressões entre versões são documentadas. Cada atualização do core pede reteste.
6. **UX.** Jogo que continua atrás do menu, "Silenciar" que não silencia, "Reiniciar" que não reinicia, um jogador só.

## Esboço da Fase 0, se o dono escolher o A

Antes de qualquer registro definitivo:
- **Aparelho.** Precisa de arm64 com 8 GB ou mais e Snapdragon 8 Gen 2 ou superior. Hoje o projeto não tem um. O Moto
  G86 serve só para validar o contrato, não o desempenho.
- **Build.** Debug, em branch ou worktree própria. O `.so` renomeado entra **só localmente** em
  `lemuroid-cores/bundled-cores/src/main/jniLibs/arm64-v8a/`, porque o `verifyNames` roda em todo build. **Nunca
  commitar no submódulo**: o `verifyCoresPublished` do release acusa arquivo não versionado ali.
- **Primeira rodada: homebrew `.nro`, sem chaves.** Valida o contrato:
  - boot e primeiro quadro;
  - input;
  - o jogo seguindo atrás do menu (confirmar o comportamento lido no fonte);
  - áudio `Host` × `Frontend`;
  - saída (`ShutdownMainProcess` + `exitGameProcess`);
  - `dumpsys meminfo` do `:game`.
- **Segunda rodada:** dumps e chaves do próprio dono em `<system>/suyu/keys`. Medir fps em 720p (`suyu_show_perf`) e o
  custo do readback.
- **Saída da fase:** a pausa ausente é aceitável? Se não for, o caminho exige patch no core (fork próprio), e o custo
  sobe de novo.

## Próximo passo

**Decisão do dono:** A (Fase 0 acima), B (abrir uma avaliação própria do lançador externo) ou C (arquivar e reavaliar por
volta de abril de 2027). A recomendação é C. Nada depende desta decisão no curto prazo.

## Fontes

- Core: <https://github.com/suyu-emu/suyu-main> (releases; `src/libretro_core/` na tag `v14.1.0`)
- Avisos de DMCA: <https://github.com/github/dmca/tree/master/2026>
- Fórum libretro: <https://forums.libretro.com/t/list-of-cores-outside-the-downloader/49153?page=5>
- Eden na Play Store: <https://www.androidauthority.com/play-store-first-nintendo-switch-emulator-3597451/>
- Avisos de fevereiro: <https://www.notebookcheck.net/Nintendo-sends-mass-DMCA-notices-to-Switch-emulators-on-GitHub-including-Yuzu-fork-Citron.1226292.0.html>
- Requisitos do Eden (resumo secundário): <https://tech-insider.org/eden-emulateur-switch-android-2026/>
- ES-DE com emuladores de Switch: <https://retrohandhelds.gg/how-to-improve-es-de-switch-emulation-streaming-and-quick-device-set-up/>

## Fase 0 — spike do caminho A (2026-10-28, sessão 1)

Decisão do dono: executar a Fase 0 do caminho A, testando no Moto G86.

**Preparação, provado antes de qualquer código:**

| Fato | Evidência |
|---|---|
| Worktree própria `C:\projects\lemuroid\Lemuroid-switch`, branch `spike-switch` a partir de `584a3d3`, no molde do spike do Wii (o checkout principal tem trabalho de outras sessões sem commit). Submódulo clonado do clone local, `origin` reapontado para o GitHub; `local.properties` copiado | `git worktree add -b spike-switch ../Lemuroid-switch 584a3d3`; `git -c submodule.lemuroid-cores.url=<checkout>/lemuroid-cores submodule update --init` |
| Moto G86 (`ZY32LMNN9B`): arm64-v8a, **API 36**, `mt6878`, 7,57 GB, **Vulkan 1.3** (`android.hardware.vulkan.version=4206592` = 0x403000), `vulkan.level=1`, 114 GB livres. App de debug `app.retrogamesystem.debug` instalado | `getprop`, `/proc/meminfo`, `pm list features` |
| Core: v14.1.0 continua a última release (12/10). `suyu_libretro_android.so` sha256 `1e77cdcc…c05b`, 46.313.800 bytes | `/releases?per_page=5`; `sha256sum` |
| Homebrew de teste: **Super Haxagon 3.9.1** (`RedTopper/Super-Haxagon`, GPL-3.0), `SuperHaxagon.nro` de 31.144.297 bytes (romfs embutido; tem áudio e usa D-pad/botões) | release `SuperHaxagon-Switch-arm64.zip` |
| No debug só roda o `verifyNames`: todo `.so` em `bundled-cores/jniLibs` tem de ser `lib*.so` e o conjunto de `arm64-v8a` tem de ser igual ao do `CoreID.kt`. As outras ABIs só são conferidas no sentido "não há `.so` sem `CoreID`" — basta pôr o core em `arm64-v8a` | `BundledCoresVerifier.kt::verifyNames` |
| `core_names.xml`, `settings.gradle.kts` e `dynamicFeatures` só servem ao flavor `dynamic`; o `freeBundle` não precisa | `grep core_name_` → só o XML |
| `ControllerConfig` sem `libretroDescriptor`/`libretroId` não chama `setControllerType`, o que casa com o core (que ignora `retro_set_controller_port_device`). O layout de toque `PSX_DUALSHOCK` cobre os 16 botões + 2 analógicos, como no spike do Wii | `ControllerConfig.kt:7-17`; `ControllerConfigs.kt:168-184` |
| `ExposedSetting` sem `values` mostra os valores que o próprio core declara (precedente do spike do Wii, sessão 3) | doc do Wii |

**Desenho do registro mínimo do spike** (não é o da Fase 1):
- `CoreID.SUYU("suyu", "suyu", "libsuyu_libretro_android.so")`; `.so` renomeado copiado **só localmente** para
  `lemuroid-cores/bundled-cores/src/main/jniLibs/arm64-v8a/` (nunca commitar no submódulo).
- `SystemID.SWITCH("switch")` + `MetaSystemID.SWITCH` (arte provisória: a do 3DS), strings, `SystemPalette`,
  `ShaderChooser` (`Default`/`modern`) e `SystemLogoResolver`.
- `GameSystem` com `statesSupported = false`, `supportedOnlyArchitectures = setOf("arm64-v8a")`,
  `uniqueExtensions = nro, nsp, xci, nca`, `defaultSettings` `suyu_use_docked = No` e `suyu_show_perf = On` (para medir),
  e expostas no menu: `suyu_audio_output`, `suyu_resolution`, `suyu_use_docked`, `suyu_show_perf`, `suyu_cpu_accuracy`.
- Controle: `ControllerConfigs.SWITCH` com o toque `PSX_DUALSHOCK`.

**Roteiro da rodada 1 (`.nro`, sem chaves):** boot e 1º quadro; input; jogo seguindo atrás do menu; áudio Host ×
Frontend; saída (`exited cleanly`); `dumpsys meminfo` do `:game`.

**Estado da implementação do spike (worktree `Lemuroid-switch`, branch `spike-switch`, sem commit):** registro conforme o
desenho acima. `./gradlew :lemuroid-app:assembleFreeBundleDebug` → BUILD SUCCESSFUL, instalado no G86 com `install -r`.
`SuperHaxagon.nro` em `files/roms/switch/`, indexado pelo "Verificar novamente" (`games.id` **347261**, `systemId=switch`).

**Rodada 1 — resultados (Moto G86, core v14.1.0, debug):**

| O quê | Resultado |
|---|---|
| **Homebrew `.nro` sem modificação** | ❌ **SIGABRT em ~0,1 s** na GLThread: `core/arm/nce/patcher.cpp:148:PatchText: unreachable`. A linha 148 é `MRS Xn, CNTFRQ_EL0 → UNREACHABLE()`: o patcher do NCE não trata leitura da frequência do timer |
| Por que não há como desviar por opção | Para `.nro`, `nro.cpp:245` chama `Settings::SetNceEnabled(true)`, e o NCE fica ligado com fastmem ativo. `IsFastmemEnabled()` (`settings.cpp:187-199`) só lê `cpuopt_fastmem` com precisão `Debugging`, que o core não expõe. Em `Auto`/`Accurate` ela devolve `getpagesize() == 4096`, e em `Unsafe`, `cpuopt_unsafe_host_mmu` (padrão `true`). O G86 tem página de 4 KB, então **`suyu_fastmem = Disabled` não desliga o NCE** e não há backend Dynarmic alcançável |
| Prova no arquivo | Uma única leitura `mrs x0, cntfrq_el0` no texto do `.nro`, em 0x58880. É a função `armGetSystemTickFreq` do libnx (`mrs x0, cntfrq_el0; ret` + 8 bytes de padding). Todo homebrew libnx que mede tempo passa por ela, então **na prática o caminho "homebrew sem chaves" não existe neste core em aparelho com página de 4 KB** |
| Contorno **só para o teste** | Cópia do `.nro` com a função trocada por `movz x0,#0xf800; movk x0,#0x124,lsl#16; ret` (19,2 MHz, a frequência fixa do Switch). O original ficou intacto (scratchpad da sessão, `nro_patched/`) |
| Boot com o `.nro` de teste | ✅ Sobe. Vulkan headless, quadro 1280×720 XRGB8888 pelo `retro_video_refresh`, `EMUFPS` 59,3–60,0. Do deep link ao primeiro `Headless Composite` levou ~6 s |
| Quadros novos do jogo | ~25/s (`Composite #60` → `#120` em 2,4 s). O `retro_run` repete o último quadro (`data=0x0` = dupe) |
| **Jogo segue atrás** | ✅ **Confirmado e pior que o previsto.** Com o Lemuroid fora de primeiro plano (outro app em foco), o `retro_run` parou (sem `EMUFPS`), mas as threads do suyu **continuam emulando e compondo** (Composite #1320 → #1440 a ~12–15/s, processo `:game` vivo). Não é só "atrás do menu": é **em segundo plano**, gastando bateria |
| Partida a frio pelo deep link | o `ExternalGameLauncherActivity` mostra "Preparando o ambiente…" antes do jogo (carga do catálogo no processo principal). Só depois o `:game` sobe |
| **Input** | ✅ o pad de toque `PSX_DUALSHOCK` chega ao core pela porta 0: o Círculo (A no libretro) sai do título para a seleção de fase |
| **Memória do `:game`** (título do Haxagon, 720p) | **TOTAL PSS 2,06 GB** (575 MB em swap), RSS 1,6 GB, GL mtrack 419 MB, Native Heap 81 MB. Em segundo plano o RSS chegou a 2,4 GB. É o dobro do Mario Kart Wii no Dolphin (PSS 917 MB) para um homebrew 2D |
| **Áudio Host** (padrão do core) | o core abre **a própria saída** (`Auto-selecting the oboe backend` → `Opened Oboe OpenSLES stream … 48000`, em `files/system/suyu/log/suyu_log.txt`). O processo fica com **duas** saídas ativas: a AAudio do LibretroDroid (vazia) e a OpenSL do core |
| **Áudio Frontend** (`suyu_audio_output = Frontend (libretro)`) | ✅ a rota funciona: `audio output = frontend`, só a saída AAudio do LibretroDroid fica ativa. Qualidade (as "rajadas" de que o autor fala) **não medida**: precisa de ouvido |
| **Segundo plano, modo Host** | a AAudio do LibretroDroid vai a `stopped`, mas a OpenSL do core **segue `started`**: **o som do jogo continua com o Lemuroid fora da tela**. CPU 103%, RSS 2,4 GB |
| **Segundo plano, modo Frontend** | silêncio (a AAudio para junto com o `retro_run`), mas a emulação continua: Composite #840 → #1140 em 11 s (~27/s), **CPU 192%** |
| **Saída** | o voltar finaliza o jogo (`wm_finish_activity … app-request`), e o `:game` morre 1,3 s depois com `reason=1 (EXIT_SELF) status=0`, sem tombstone (`dumpsys activity exit-info`) |
| Menu do jogo | não aberto: o botão ≡ não responde a `input tap`/`swipe` (mesmo comportamento anotado no spike do Wii). O que importa já está provado acima, porque o menu é outra Activity, e com ela o `GameActivity` só pausa o `retro_run` |

**Armadilhas desta sessão:**
- **As opções de core não ficam nas preferências padrão.** `CoreVariablesManager` lê o `SharedPreferences` do Harmony
  (`harmony_options`, multiprocesso: `SharedPreferencesHelper.getSharedPreferences`). As padrão
  (`<pkg>_preferences.xml`) são as "legadas": diretório de ROMs/SAF e parte dos ajustes da tela de configurações. Para A/B
  de opção sem o menu, o caminho seguro é o `defaultSettings` temporário + rebuild incremental (~3 min), e foi assim
  que o Frontend foi medido.
- **Incidente:** tentando gravar a opção por `run-as`, um `cat` com caminho relativo falhou e o comando seguinte
  sobrescreveu `shared_prefs/app.retrogamesystem.debug_preferences.xml` do debug no G86, sem cópia. Ficou um `<map />`
  vazio e válido. Perderam-se só as preferências legadas do app de debug desse aparelho (que voltam ao padrão). Banco,
  saves e `harmony_options` não foram tocados. Lição: com `run-as`, sempre caminho absoluto
  (`/data/data/<pkg>/…`) e conferir o que foi lido **antes** de escrever.

**Conclusão da rodada 1:**
1. O contrato libretro **funciona** no LibretroDroid sem mudança: vídeo de software, input na porta 0, rota de áudio
   Frontend e saída limpa.
2. **Não há rodada "sem chaves".** Homebrew libnx morre no patcher do NCE, e não há opção que o desvie (seção acima).
   Validar com homebrew exigiu alterar o binário de teste. Para o produto, isso significa que só jogo comercial com
   `prod.keys` do usuário roda. O NCE ser o padrão do yuzu/suyu Android sugere que jogos comerciais não leem
   `CNTFRQ_EL0`, mas **isso não foi provado**: é a rodada 2.
3. **A falta de pausa é pior que o previsto:** o core segue emulando **em segundo plano** (100–190% de CPU) e, no modo
   Host, **tocando**. Pelo critério de saída da Fase 0, isso não é aceitável para produto. Corrigir exige patch no core
   (pausar as threads do `System` no `ON_PAUSE`), ou seja, fork próprio. Mitigação parcial do lado do app (sem
   fork): forçar `Frontend` (silencia em segundo plano) e encerrar o `:game` ao ir para segundo plano.
4. Memória: 2 GB de PSS já no título de um homebrew 2D. Aparelho de 4 GB está fora, e 6 GB é duvidoso.

**Próximo passo (rodada 2, precisa do dono):** dumps de jogos e `prod.keys`/`title.keys` do próprio dono, copiados para
`files/system/suyu/keys/` do app de debug no G86 (caminho absoluto
`/data/data/app.retrogamesystem.debug/files/system/suyu/keys/`, por `run-as`) e o jogo para `files/roms/switch/`.
Medir: boot, fps em 720p (`suyu_show_perf`), PSS, e confirmar que o NCE não aborta em código comercial. Sem esse
material, a Fase 0 para aqui. A decisão "pausa ausente é aceitável?" já tem resposta: **não, sem patch no core**.

### Fase 0 — rodada 2 (2026-10-28)

**Material:** chaves e jogo do próprio dono, tirados do projeto `C:\projects\retrobatnew`:
- `bios/switch/prod.keys` (14.612 bytes) e `title.keys` (1.292 bytes), copiados para
  `/data/data/app.retrogamesystem.debug/files/system/suyu/keys/`, conferidos com `ls -l`. As cópias em
  `/data/local/tmp` foram apagadas.
- `roms/switch/LIMBO.nsp` (121.709.321 bytes, cabeçalho `PFS0`), o único dump real ali (o resto da pasta são
  placeholders de 0 byte), enviado para `files/roms/switch/`.
- Firmware **não** copiado (há `bios/switch/Firmware.22.5.0.zip` e `dist/retrosystem/bios/switch/registered/*.nca`).
  Só entra se o jogo pedir.

**Incidente de aparelho compartilhado:** na primeira tentativa, o "Verificar novamente" foi tocado com o **ARMSX2 em
primeiro plano no G86** (outro projeto). Os toques cegos podem ter caído nele. Antes de cada toque, conferir
`dumpsys window | grep mCurrentFocus`.

**Resultados (G86, LIMBO id 347262, áudio Host, 1x, modo portátil):**

| Item | Resultado |
|------|-----------|
| Indexação | "Verificar novamente" pegou o `.nsp` como `switch` (habilitado só 2 min após abrir o app). |
| NCE | **Não entra:** o LIMBO é de 32 bits (`DIAG NPDM … is64=0`, `SetNceEnabled: Program does not utilize 39-bit address space`). Roda no JIT (dynarmic), sem o abort do homebrew. **O NCE em código comercial de 64 bits continua sem prova.** |
| Chaves | `PrepareLibretroKeys` achou `files/system/suyu/keys`. Abriu sem firmware. |
| Vulkan | `GetSuitability` reclama de 11 recursos ausentes no Mali (`dualSrcBlend`, `logicOp`, `multiViewport`, `maxClipDistances 0`…) e segue com "Unsuitable driver - continuing anyways". Sem artefato visível no título nem na 1ª cena. |
| Boot | `game loaded and running` a 2,4 s. Tela preta até ~30 s (compilação de pipelines + `Controller Applet`), título visível em ~35 s. |
| Input | Funciona, mas o toque precisa durar ~250 ms (`input tap` não registra; `swipe` de 250 ms sim). Chegou ao menu e ao gameplay. |
| fps | `EMUFPS` 60 (o `retro_run`), mas o jogo compõe **~22 quadros/s** (60 `Composite` a cada 2,6–2,8 s), no título e no gameplay. Cpu do `:game` 140–180%. |
| Memória | PSS 2,26–2,58 GB (+250–580 MB de swap), GL mtrack 540 MB. |
| Saída | BACK → `EXIT_SELF` status 0. |

**Conclusão da rodada 2:**
1. Um jogo comercial **roda de ponta a ponta** no LibretroDroid com as chaves do usuário e sem firmware.
2. **Desempenho insuficiente:** ~20 fps (confirmado com o aparelho livre) num jogo 2D leve, em aparelho de 8 GB com Mali de geração atual. Não foi
   isolado se o gargalo é CPU (JIT de 32 bits) ou o readback Vulkan → software → GL. Jogo 3D pesado deve ficar abaixo.
3. Memória confirma a rodada 1: ~2,3 GB de PSS. 4 GB está fora.
**Remedida com o aparelho livre (hipótese "WhatsApp" descartada):** numa relançada, o `top` mostrou o aparelho a 800% de
CPU. O **WhatsApp Business** (`com.whatsapp.w4b`) consumia 572%, com 1.296 min de CPU acumulados. Com o dono de acordo,
`am force-stop com.whatsapp.w4b` deixou o aparelho 96% ocioso, com 3 GB disponíveis. O LIMBO **continuou a ~20 fps**. O
WhatsApp não era a causa. Ele foi reaberto no fim.

Medida pelo próprio core: `adb shell setprop debug.suyu.libretro.perf 1` liga o log `libretro PERF` (1/s;
`retro_core.cpp:953`, lê `SUYU_LIBRETRO_PERF` ou a propriedade). No gameplay: `average_game_fps` 17–21,
`frametime_ms` 46–64, `frontend_frames` 60, `unique_frames` 16–22. (`emulation_speed` sempre 1.000: com multicore não
significa nada, como diz o próprio código.) Propriedade voltada a 0 no fim.

**Onde vai o tempo:** com o aparelho livre, nenhuma thread do `:game` satura (`GPU` 54%, `CPUCore_2` 31%,
`CPUCore_0` 23%, `GLThread` 12%). Não é a emulação da CPU (JIT A32) que limita. Sobra a GPU Mali (o driver é
"Unsuitable", 11 recursos ausentes) ou a espera síncrona do readback. O readback em si é barato (3,7 MB/quadro a
20 fps). **Não isolado:** a utilização da GPU exige root (`/sys/kernel/ged/hal/gpu_utilization` → Permission denied),
e o experimento do modo dock (1080p, 2,25× pixels) não rodou porque o botão ≡ não abre o menu do Lemuroid neste
layout (na rodada 1, também não). O próximo passo é trocar `cv_switch_suyu_use_docked` pelo menu ou por um build
com o default trocado. Editar `harmony_options` na mão está proibido (incidente da rodada 1).

**Controle proposto pelo dono (2026-10-29): o mesmo jogo num emulador standalone.** Se o LIMBO rodar perto de 60 fps
num app de Switch próprio no G86, o gargalo é o port libretro (readback headless, driver "Unsuitable"). Se ficar perto
de 20, o limite é a GPU Mali. Escolhido o **Eden v0.2.1** (2026-06-01), o mais citado como padrão no Android. O GitHub
dele (`eden-emulator/Releases`) dá **451, DMCA da Nintendo de 2026-03**. A fonte oficial é o Forgejo
`git.eden-emu.dev/eden-emu/eden` (API `/api/v1/repos/eden-emu/eden/releases`), com o APK em
`stable.eden-emu.dev/v0.2.1/Eden-Android-v0.2.1-standard.apk` (24.970.363 bytes, sha256
`befbf70b1a05715811fe092ef9cf71e6f1c1672008a6c5caa31d633e99615668`). Ficou no scratchpad, sem instalar: o G86 saiu do
adb.

4. Pendente para fechar a Fase 0: um jogo **de 64 bits** (a maioria do catálogo) para provar se o NCE aborta em código
   comercial, e uma medida que separe custo de CPU e de readback (ex.: comparar `suyu_cpu_accuracy` Unsafe e
   `suyu_resolution` 1x vs. 2x). Só há o LIMBO entre os dumps do dono.
