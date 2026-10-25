# [BUG] Pokemon de DS (Black/White) roda acelerado com "Acelerar" desligado e sem botao de turbo mapeado

**Data:** 2026-10-23
**Status:** aberto - passada 1 (sintoma e evidencia); causa desconhecida
**Severidade:** media (jogo "injogavel" para o cliente; mesmo cliente ja pediu reembolso por audio e catalogo)
**Complexidade:** a definir (possivel ritmo de quadros/audio do core no aparelho)
**Branch:** version9 (versao publicada: versionCode 258 / 1.17.27, APK de 2026-10-12 06:28 UTC; versao do cliente nao determinada)
**Origem:** triagem do chamado #112 (sessao `43087531405512@lid`), conversa de WhatsApp

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

## Proximos passos

Pedir ao cliente versao do app e video curto; reproduzir Pokemon Black em aparelho 120 Hz com melonDS e DeSmuME; medir fps/velocidade com Acelerar desligado.
