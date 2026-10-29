# [BUG] Pokemon de DS (Black/White) roda acelerado com "Acelerar" desligado e sem botao de turbo mapeado

**Data:** 2026-10-23
**Status:** corrigido e medido no Moto G86 (2026-10-29); vai a `retest/` com o commit; falta versao publicada + cliente
**Severidade:** media (jogo "injogavel" para o cliente; mesmo cliente ja pediu reembolso por audio e catalogo)
**Complexidade:** alta (JNI/LibretroDroid: rebuild do `.so` do AAR)
**Branch:** version9 (versao publicada: versionCode 258 / 1.17.27, APK de 2026-10-12 06:28 UTC; versao do cliente nao determinada)
**Origem:** triagem do chamado #112 (sessao `43087531405512@lid`), conversa de WhatsApp

## Status

- **Corrigido:** 2026-10-29 ~12:10, commit `954dda3`.
- **Onde:** `../LibretroDroid-patched` `fpssync.cpp::FPSSync::advanceFrames` / `FPSSync::isVSyncTooFast` /
  `FPSSync::reset` (patch em `libs/libretrodroid-patches/fpssync-vsync-real.patch`); `libs/libretrodroid-patched.aar`
  com os quatro `liblibretrodroid.so` novos.
- **T2 (aparelho, caminho do cliente):** Moto G86 `ZY32LMNN9B`, jogo de GB, painel forcado a 120/90 Hz. Controle na
  base: 120,0 fps. Com o fix: 59,73 fps nos dois. Tabela na secao Validacao.
- **T3:** `tests/native/test-fpssync-vsync.ps1` contra o `.so` do AAR. Controle no AAR antigo: falha a 90/120 Hz. AAR
  novo: ALL PASSED no AVD x86_64 e no G86 arm64. Caminho vizinho: painel fixo de 60 Hz continua no vsync, inclusive
  com travadas (KOF '97 no AVD, sem troca falsa).
- **T4:** logcat do `:game` no G86: `Screen is refreshing at 97.667047 ... Switching from vsync to time based sync`
  seguido de `EMUFPS 59.73`.
- **Falta para `done/`:** versao publicada (dono). O cliente do chamado #112 tem de confirmar no Pokemon Black/Platinum
  com o boost de toque real; o toque injetado por adb nao dispara esse boost.

## Sintoma

Mesmo cliente do doc `2026-10-07-audio-engasgando-gba-nds-moto-g86-sem-diagnostico.md` (Moto G86, Pokemon Black DS).
Em 22/10 diz que os jogos Pokemon de DS rodam acelerados ao tocar A/B e ja comecam "com o botao turbo ativado".

## Evidencia (Wpp_proccess, UTC)

- `[150956]` 10-22 13:00 *"Jogos pokemon no ds estao acelerando ao apertar o.botao correspondente"*
- `[150958]` *"Acelera no a e b so pokemon"*; `[150960]` *"Ele dispara a velocidade ficando injogavel"*
- `[150966]` print do Menu do jogo (Salvar, Carregar, Sair, Reiniciar, Silenciar, Acelerar, Editar Controles, Configuracoes, Inclinar sensor)
- `[151063]` *"jogos de pokemon no ds ja comecam com o botao turbo ativado"*
- `[151325]` 19:31 *"estao acelerados com a opcao acelerar desligado e nao tem botao mapeado pra ativar turbo"*; agente escalou em `[151327]`.

## Codigo aberto (o que ja se sabe)

- `BaseGameActivity.onActivityResult` (RESULT_ENABLE_FAST_FORWARD) e `BaseGameScreenViewModel.toggleFastForward`: "Acelerar" so troca `frameSpeed` entre 1 e 2, em memoria; nao e persistido, nasce 1 a cada jogo.
- `GameShortcut.getDefault`: so o atalho MENU tem padrao; nao ha atalho de turbo por padrao.
- `CoresSelection.getDefaultCoreForSystem`: NDS instalado do zero usa melonDS (DeSmuME so com saves pendentes). melonDS usa `skipDuplicateFrames` padrao (true) e `melonds_threaded_renderer`=enabled; DeSmuME usa `skipDuplicateFrames=false`, `desmume_frameskip`=1.
- Logo "Acelerar" desligado + sem atalho nao explica a velocidade; o app nao tem o estado que o cliente descreve.

## Hipoteses - nenhuma verificada

1. Ritmo de quadros/audio do core no aparelho (display provavelmente 120 Hz): mesma familia do doc de audio engasgando no mesmo aparelho.
2. Comportamento do proprio jogo (Gen 5 acelera texto ao segurar A/B): o agente assumiu turbo do emulador sem confirmar.
3. Core efetivo (melonDS x DeSmuME) e versao do app do cliente: desconhecidos.

## Nova evidencia (2026-10-25, outro core no mesmo aparelho)

Durante a validacao de `docs/bugs/done/2026-10-25-neogeo-cd-jogo-nao-inicia.md`, o dono jogou Metal Slug
(Neo Geo CD, core **NeoCD**) num Moto G86 5G (Android 16, debug 1.17.27) e relatou: *"estava meio acelerado, no
mais estava ok"*. Logo nao e exclusivo de NDS/melonDS.

- logcat do `:game`: `EMUFPS 60.00 frames/s` estavel; o Neo Geo nativo e ~59,19 Hz (core ~1,4% acima).
- `MTK_APPList ... GameActivity ... state:PAUSED, fps:90`: o painel estava a **90 Hz** com o jogo aberto
  (no `MainActivity`, `dumpsys display` mostra `renderFrameRate 60`). 60 quadros num painel de 90 Hz = cadencia
  3:2 (trepidacao). O mesmo jogo num AVD a 60 Hz nao teve queixa.
- Reforca a hipotese 1 (ritmo de quadros/audio x taxa do painel). Proximo passo concreto: medir no G86 com a taxa
  do painel travada em 60 Hz (Configuracoes > Tela > Taxa de atualizacao) e comparar a sensacao/fps.

## Nova evidencia (2026-10-25, Dolphin/Wii no spike do Wii) — reproduzido e medido

Achado lateral do spike de `docs/backlogs/2026-10-25-suporte-nintendo-wii.md` (build debug da branch `spike-wii`,
Moto G86 `ZY32LMNN9B`, Mario Kart Wii em corrida):
- **Com o dedo parado na tela (segurando o A do pad de toque), o painel sobe para 120 Hz** e a emulacao acelera junto.
  `dumpsys display`: `mActiveRenderFrameRate=60.000004` sem toque, `=120.00001` com toque.
- `EMUFPS` sem toque: 59–60. Segurando o botao: **100–105** (limite da CPU, nao 120).
- **O cronometro do proprio jogo andou 26,74 s em 15,66 s de relogio** (1:19.554 → 1:46.290 entre duas capturas
  cronometradas no host), ou seja, 1,71×, igual a `102 / 60`.
- Conclusao: o ritmo da emulacao segue o vsync do painel, e o G86 aumenta a taxa enquanto ha toque (boost de toque do
  MTK). Explica "acelerado sem Acelerar" justamente em jogo jogado na tela de toque, como o Pokemon de DS.
  Independe do core.
- Proximo passo: descobrir onde o LibretroDroid amarra o `retro_run` ao `onDrawFrame` (GLSurfaceView,
  `RENDERMODE_CONTINUOUSLY`) e pedir uma taxa fixa ao painel (`Surface.setFrameRate(60f, …)` /
  `preferredDisplayModeId` / `preferredRefreshRate` da janela do `GameActivity`), ou ritmar pelo tempo/audio.

## Nova evidencia (2026-10-29, chamado #112) — acelera tambem SEM toque

Mesmo cliente, outro jogo (Pokemon Platinum, DS), Acelerar desmarcado:
- `[159285]` 10-29 14:22 UTC *"Pokemon Platinum está acelerado com opcao desmarcada ao apertar qualquer botão sem pressionar"*
- `[159287]` 14:23 *"Ele acelera do nada sem tocar em nenhum botão"* (resposta a pergunta do agente se era so
  enquanto encosta no botao ou o tempo todo)
- `[159292]` 14:28 operador humano: *"sou do suporte estou verificando aqui"*

O que muda: o boost de toque medido no Wii (60 → 120 Hz so com dedo na tela) nao explica sozinho o relato de
"sem tocar". Mas a medicao do NeoCD acima ja tinha o painel a **90 Hz com o `GameActivity` aberto** sem depender de
toque — consistente com a hipotese 1 em geral (ritmo da emulacao segue o vsync do painel, e o painel do G86 nao
fica em 60 Hz durante o jogo). Nao refuta nada; amplia o gatilho de "toque" para "qualquer subida da taxa do painel".

Agente: em `[159289]` sugeriu desligar "Inclinar sensor" como causa da aceleracao — palpite sem lastro: o tilt so gera
entrada de controle simulada (`GameViewModelTilt.getSimulatedTiltEvents` → `Flow<InputState>`), nao mexe em
`frameSpeed`. Nao virou doc no dsg: e chute isolado, nao rotulo de tela inventado.

## Proximos passos

Pedir ao cliente versao do app e video curto; reproduzir Pokemon Black em aparelho 120 Hz com melonDS e DeSmuME; medir fps/velocidade com Acelerar desligado.

## Analise (2026-10-29) - causa-raiz no `FPSSync` do LibretroDroid

Simbolos abertos (checkout `../LibretroDroid-patched`, que e a fonte do `libs/libretrodroid-patched.aar`; o `.so`
do AAR tem os mesmos `EMUFPS` / `Applying core AV timing` / `Using vsync` do fonte):

- `GLRetroView.getDefaultRefreshRate` - le `defaultDisplay.refreshRate` **uma vez**, no `onCreate`, e passa ao
  `LibretroDroid.create` -> `LibretroDroid::screenRefreshRate`. Nunca e relido.
- `GLRetroView.Renderer.onDrawFrame` - chama `LibretroDroid.step` a cada quadro do `GLSurfaceView`
  (`RENDERMODE_CONTINUOUSLY` padrao), ou seja, **a cada vsync do painel**.
- `FPSSync::FPSSync` - `useVSync = |contentRefreshRate - screenRefreshRate| < 5`, decidido uma vez. NDS ~59,8 x painel
  60 na abertura -> `useVSync = true`.
- `FPSSync::advanceFrames` - com `useVSync` devolve sempre `1`; `FPSSync::wait` com `useVSync` nao espera.
  Logo, no modo vsync, **quadros emulados/s = taxa real do painel**. Painel a 120 Hz = jogo a 2x (limitado pela CPU:
  os 100-105 EMUFPS do Wii). Sem `useVSync` o caminho por tempo (`sampleInterval` + `sleep_until`) ja ritma certo
  em qualquer painel: roda >= 1 quadro por `step` e dorme ate o prazo.
- `LibretroDroid::step` - `frames = min(advanceFrames(), 2)`, `retro_run` `frames * frameSpeed` vezes, depois
  `fpsSync->wait()`. `frameSpeed` (Acelerar) e independente do bug.
- `LibretroDroid::resume` -> `fpsSync->reset()` (so zera `lastFrame`); `afterGameLoad`/`updateSystemAVTiming`
  recriam o `FPSSync` e o `Audio` com `getTimeStretchFactor()` (content/screen no modo vsync, 1,0 por tempo).
- `Audio::computeDynamicBufferConversionFactor` - controlador PI do buffer (`maxi` = 2%): absorve a diferenca de
  0,3-1,4% de taxa de entrada se o modo mudar com o `Audio` ja criado. Em erro nao ha excecao: so ajusta a taxa.
- Efeito colateral provavel: no modo vsync a 120 Hz o core produz audio em dobro -> fifo cheio -> estalos. Pode ser
  parte do doc `2026-10-07-audio-engasgando-gba-nds-moto-g86-sem-diagnostico.md` (mesmo aparelho); nao verificado.

Prova de medicao ja registrada acima: Wii no G86, `EMUFPS` 59-60 sem toque, 100-105 com toque, cronometro do jogo
1,71x. Bate com "1 `retro_run` por vsync".

Hipoteses descartadas:
- "Acelerar"/`frameSpeed` ou atalho de turbo: `frameSpeed` nasce 1 e nao e persistido; nao ha atalho padrao.
- Inclinar sensor: so gera `InputState`, nao toca em ritmo.
- Pedir 60 Hz ao painel pelo app (`preferredDisplayModeId`, `Surface.setFrameRate`,
  `setFrameRateBoostOnTouchEnabled`): e dica, o MTK pode ignorar (o boost de toque e do fabricante; o
  `MTK_APPList` ja mostrou 90 Hz com o `GameActivity`). Nao garante velocidade; no maximo melhora a cadencia.
  Fica fora desta correcao.
- Desligar o modo vsync sempre: corrige, mas perde a cadencia lisa nos paineis 60 Hz comuns (todo aparelho). Rejeitado.

## Plano de correcao

1. `fpssync.h/.cpp` (LibretroDroid): no modo vsync, medir a taxa real de chamadas de `advanceFrames` em janelas de
   60 chamadas. Se passar de `contentRefreshRate * 1.2`, logar e trocar para o modo por tempo (`useVSync = false`,
   `start()`), que ja existe e ritma pelo relogio. Nao volta para vsync na mesma sessao (voltar no `resume` daria
   rajada de 2x a cada menu fechado). Margem 1,2 e janela 60: rajada de 2-3 quadros da fila do BufferQueue apos um
   travamento da no maximo ~63 Hz num painel de 60; 90 e 120 passam folgado.
2. Rebuild de `:libretrodroid:assembleRelease` numa copia isolada; trocar **so** os `jni/*/liblibretrodroid.so` do
   `libs/libretrodroid-patched.aar` (o `classes.jar` fica igual). Patch preservado em
   `libs/libretrodroid-patches/fpssync-vsync-real.patch`.
3. Teste: `tests/native/fpssync_vsync_test.cpp` + runner `.ps1`, compila `fpssync.cpp` com o NDK e roda no
   aparelho/AVD simulando vsync de 60, 90 e 120 Hz; prova quadros emulados/s ~= conteudo nos tres e modo vsync
   mantido a 60.
4. Validacao em aparelho de painel variavel (G86): `EMUFPS` ~60 segurando o dedo no pad (antes: 100-105).

## Causa-raiz

`FPSSync` (LibretroDroid) decide **uma vez**, na abertura do jogo, que vai ritmar pelo vsync, porque o painel estava a
60 Hz. Nesse modo cada `onDrawFrame` roda um `retro_run`, sem olhar o relogio. O Moto G86 (MediaTek) muda a taxa do
painel durante o jogo, para 90 Hz ou 120 Hz: boost de toque, ou a taxa que o `MTK_APPList` aplica ao app. Com isso a
emulacao vai a 1,5x ou 2x (ate o limite da CPU). Independe do core e do "Acelerar".

## Correcao

`fpssync.h/.cpp` no checkout `../LibretroDroid-patched` (patch preservado em
`libs/libretrodroid-patches/fpssync-vsync-real.patch`, procedimento no `README.md` da pasta):

- `FPSSync::isVSyncTooFast` mede a taxa real de chamadas de `advanceFrames` em janelas de 60 enquanto o modo e vsync.
  Acima de `contentRefreshRate * 1.2`, loga `Screen is refreshing at ... Switching from vsync to time based sync`. Em
  seguida o `FPSSync` passa para o ritmo por relogio, que ja existia (`sampleInterval` + `sleep_until`), e fica nele ate
  o fim da sessao.
- `reset()` (no `resume`) zera a janela, para a pausa nao contar na medicao.
- Painel de 60 Hz nao muda: continua no vsync, com o mesmo `getTimeStretchFactor`.
- O `Audio` nao e recriado na troca. A diferenca de 0,3-1,4% entre o fator de vsync e o ritmo por relogio fica dentro do
  integral do controlador PI (`maxi` = 2%).
- `libs/libretrodroid-patched.aar`: so os quatro `jni/*/liblibretrodroid.so` trocados; `classes.jar`, manifesto e
  recursos byte a byte iguais. Diff de simbolos exportados antigo x novo (arm64 e armv7): so `+FPSSync::isVSyncTooFast`;
  diff de strings: so a mensagem nova. SHA-256 do AAR:
  `64EE4912A450C8FA8FB44A4EEBC0687C5311A708BA2248D1B95C31D9BFAB98CE`.

## Validacao

- `tests/native/test-fpssync-vsync.ps1` (`fpssync_vsync_test.cpp` linkado contra o `.so` **do AAR**, vsync simulado,
  conteudo 59,83 Hz, painel de 60 Hz na criacao), AVD `emulator-5554`, `-Abi x86_64`:
  - **controle, AAR anterior:** 60 Hz 59,99 fps PASS; 90 Hz **89,66 fps FAIL**; 120 Hz **119,97 fps FAIL** (reproduz o bug).
  - **AAR novo:** 60 Hz 60,00 (vsync mantido, stretch 0,9971); 60 Hz com travadas de 100 ms + rajada de 3 quadros:
    vsync mantido; 90 Hz **59,93**; 120 Hz **60,05** -> ALL PASSED. Segunda rodada: 59,99 / 60,03 / 60,03, ALL PASSED.
- `:libretrodroid:assembleRelease`: BUILD SUCCESSFUL. `:lemuroid-app:assembleFreeBundleDebug -PdevAbi=x86_64`:
  BUILD SUCCESSFUL, `verifyLibretroDroidBridge` ok; o `.so` do APK e identico ao do AAR novo.
- APK no AVD (API 25), KOF '97 (FBNeo, 59,18 Hz): roda; ~6 s de carga travada e nenhum `Switching from vsync` (sem
  falso positivo). O AVD so entrega 30 vsync/s com esse core (`EMUFPS` ~30), caso de painel **mais lento**, que a
  mudanca nao toca.
- **Moto G86 `ZY32LMNN9B` (Android 16), Super Mario World 7 (GB, gambatte, 59,73 Hz), 2026-10-29.** O painel foi
  forcado por `settings put system min_refresh_rate` (e `peak_refresh_rate` no caso de 90 Hz), com o jogo aberto. O
  toque injetado por `adb shell input swipe` **nao** dispara o boost do MediaTek: o painel ficou em 60 Hz. Os valores
  originais (60/60) foram restaurados.

  | Build | painel 60 Hz | painel 120 Hz | painel 90 Hz |
  |---|---|---|---|
  | anterior (spike Switch, AAR antigo) | 60,0 fps | **120,0 fps** (2x, reproduz) | - |
  | com a correcao | 60,0 fps (vsync) | `Switching ... 97,67` em ~1 s, depois **59,73 fps** | `Switching ... 78,14`, depois **59,73 fps** |

  O valor medido no log (97,7 / 78,1) e da janela que pegou a troca de taxa no meio. 59,73 e a taxa exata do GB.
  Sem crash. O teste nativo tambem passou no arm64 do G86: 60,00 / 60,03 / 60,07.
- Depois do teste o APK que estava no celular (build do spike Switch, 28/10) foi reinstalado a partir do `base.apk`
  puxado do aparelho.
- **Falta (retest):** versao publicada e o cliente do chamado #112 confirmar o Pokemon de DS. Conferir tambem se o
  audio do doc `2026-10-07-audio-engasgando...` melhora, porque a 2x o core gerava audio em dobro.

## Licao

Vsync so serve de relogio se a taxa do painel for fixa, e no Android ela nao e: boost de toque, 90/120 Hz adaptativo e
politica por app do fabricante mudam a taxa com o jogo aberto. Ler `Display.refreshRate` uma vez e confiar nisso para
sempre acelera o jogo em todo aparelho com painel variavel. Medir a cadencia real e cair para o relogio quando ela
diverge.
