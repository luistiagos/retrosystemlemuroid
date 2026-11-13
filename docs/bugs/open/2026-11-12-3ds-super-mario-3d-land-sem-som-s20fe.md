# [BUG] 3DS: Super Mario 3D Land sai sem som (Galaxy S20 FE)

- **Data:** 2026-11-12
- **Status:** aberto. Passada 3: **não reproduz no moto g86, que tem som**, com o mesmo núcleo, arquivo e app. É específico do aparelho da cliente. Candidatos: desempenho no S20 FE (D1), a cópia do arquivo dela (D2) e o estado do Citra no aparelho dela (D3). Aguardando a cliente re-baixar o jogo e mandar um vídeo.
- **Severidade:** Alta para 3DS. É a mesma cliente que já pediu reembolso por causa do 3DS (chamado #330).
- **Complexidade:** a definir. Se for configuração do core, Baixa. Se for o caminho de áudio do LibretroDroid, Alta (JNI). Se estiver dentro do core, fica fora deste repo (pitfall 15).
- **Branch:** version10 (investigação; a correção ainda não tem branch)
- **Origem:** relato do dono em 2026-11-12: "No lemuroid o Mario 3d Land está saindo sem som, celular da cliente: S20FE". Provavelmente é a cliente da sessão `45861677588562@lid` / chamado #330, ver [`2026-11-07-3ds-sem-tela-unica-controles-em-coluna-e-imagem-deformada.md`](2026-11-07-3ds-sem-tela-unica-controles-em-coluna-e-imagem-deformada.md). **Isso não foi confirmado na conversa.**

---

## Sintoma

Super Mario 3D Land (3DS) roda sem som no Galaxy S20 FE da cliente: **SM-G780G/DS**, Snapdragon 865 / Adreno 650 (print do "Sobre o telefone" na conversa). Ela diz que **é só esse jogo**. Um segundo jogo de 3DS tem som com defeito.

Conversa (`chamado.py conversa-lid 45861677588562@lid`, 564 mensagens, lida em 2026-11-12):

- `[173170]` 2026-11-09 00:11 customer: *"Super mario 3D land está sem som"*
- `[173809]`/`[173814]`/`[173822]` 2026-11-09 21:56–22:04 customer: *"Sonic Sega all stars racing tá com o som estranho"*, *"Com o som meio chiando"*, *"É 3DS"*
- `[176587]` 2026-11-11 21:04 customer: *"O marido 3d land ele dá pra jogar só que está com tela duplicada e sem som"*
- `[176591]` 21:06 customer: *"**É só esse jogo que tá sem som**"*
- `[176650]` 21:58: *"Ele continua sem som"*
- `[177092]` 2026-11-12 02:57: *"Ainda está mudo"*, depois de o bot pedir para reiniciar o celular e testar **com fone**.

O bot afirmou em `[177089]` que "outros jogos do 3DS estão com som normal". Quem disse isso foi o bot, não a cliente. O que a cliente disse é o `[176591]`. O Mario Kart 7 ela jogou (prints do menu `[1275]`), mas nunca falou do som dele.

Conclusão: o defeito é específico do jogo (H5 cai), não é o fone (H6 cai em parte) e sobreviveu a um reboot.

## Evidência

### Entrada do catálogo

Só existe uma linha no manifest (`lemuroid-app/src/main/assets/catalog_manifest.txt`):
`3ds/Super Mario 3D Land (Europe) (En,Fr,De,Es,It,Nl,Pt,Ru) (Rev 1).3ds`, com `isRepresentative=1`. Não há outra variante para trocar.

### Core empacotado

- `GameSystem.kt:1839-1907`: o 3DS tem um único core, `CoreID.CITRA` → `libcitra_libretro_android.so`, só `arm64-v8a`. Nenhuma opção de áudio vai em `defaultSettings` nem em `exposedSettings`.
- Binário: `lemuroid-cores/bundled-cores/src/main/jniLibs/arm64-v8a/libcitra_libretro_android.so` (32.075.136 bytes). O `llvm-strings` (NDK 29) mostra `g_scm_rev` = **`5263fae3344e5e9af43036e0e38bec2d10fb2407`**, branch `master`, `5263fae33-dirty`, build de `2025-11-12T10:19:27Z`. O commit é do `libretro/citra`: a árvore dele no GitHub tem `src/audio_core/libretro_sink.cpp` e `src/citra_libretro/`. O `-dirty` quer dizer que o fonte lido pode não ser byte a byte o que foi compilado.
- O binário tem o decodificador AAC FAAD2 (`Created FAAD2 AAC decoder.`, `NeAACDecDecode`). Então o "jogo mudo por falta de AAC", conhecido em builds antigos do Citra, não se aplica a este binário. E o SM3DL nem é jogo de AAC.
- Opções que o core declara (`citra_*` no binário): nenhuma é de áudio. `audio_emulation` aparece só como chave interna de `Settings`, sem opção libretro. Fica no padrão (HLE).

### Caminho do áudio no core (fonte no commit `5263fae`)

- `citra_libretro.cpp:234-238`: força `output_type = SinkType::LibRetro`, `volume = 1.0f` e `enable_audio_stretching = false`.
- `audio_core/libretro_sink.cpp`: `OnAudioSubmission(frames)` puxa as amostras do DSP e chama `LibRetro::SubmitAudio` → `audio_batch_cb(data, frames)`. **Usa o callback em lote.**
- `environment.cpp:186`: `retro_set_audio_sample` não guarda nada. O core não usa o callback de amostra única.
- `environment.cpp:210-211`: `fps = 60.0`, `sample_rate = AudioCore::native_sample_rate` (32.728 Hz no 3DS).
- `environment.cpp`: nenhuma chamada a `SET_SYSTEM_AV_INFO`. Então o `LibretroDroid::updateSystemAVTiming` (que recria o `Audio`) não roda para o 3DS.
- `retro_run` (`citra_libretro.cpp:488`): `RunLoop()` até um quadro ser apresentado. O áudio sai de dentro do `RunLoop`, na mesma thread do `retro_run`. Não foi achada nenhuma thread própria de áudio.

### Caminho do áudio no frontend (`LibretroDroid-patched/libretrodroid/src/main/cpp`)

- `libretrodroid.cpp:68-70`: `callback_audio_sample` (amostra única) **só loga e descarta**. Um core que usasse esse callback ficaria mudo. **O Citra não usa**, então isto não explica o bug (ver acima).
- `libretrodroid.cpp:72-74` e `583-588`: o callback em lote escreve no FIFO do `Audio` se `audio && audioEnabled`.
- `libretrodroid.cpp:637-657` (`afterGameLoad`): cria o `Audio` com `sample_rate × timeStretchFactor` e com o fps do core.
- `audio.cpp`: stream Oboe I16 estéreo; resampler a partir de `inputSampleRate / stream->getSampleRate()`; controlador PI sobre o FIFO. Quando falta amostra (core lento), sai som picotado, não silêncio. Se o stream não abre (`Failed to create stream`), `stream = nullptr` e **todo** o app fica mudo, não só o 3DS.
- `environment.cpp` do LibretroDroid: `GET_AUDIO_VIDEO_ENABLE` devolve `false`, que o core lê como "frontend não informa", ou seja, áudio ligado.

### Silenciar no menu do jogo

- `GameMenuHomeScreen.kt:85-94` / `GameMenuHelper.kt:146-150`: o menu do jogo tem "Silenciar" (`RESULT_ENABLE_AUDIO`). `BaseGameActivity.kt:673-677` aplica em `GLRetroView.audioEnabled`.
- **Não foi aberto** se o estado sobrevive a fechar e abrir o jogo. Se não sobrevive, um toque acidental em "Silenciar" explicaria só uma sessão muda, não o jogo sempre mudo.

### Bancada: moto g86 5G (`ZY32LMNN9B`, Mali-G615), 2026-11-12 00:45

- Ligado por adb Wi-Fi (`adb pair` + `adb connect 192.168.18.113:43551`). App `app.retrogamesystem.debug` 1.17.27-DEBUG (versionCode 258), instalado em 2026-11-07 22:24.
- O núcleo instalado é o do repositório: `md5sum` do `lib/arm64/libcitra_libretro_android.so` no aparelho = `41609224f22bc780e99b99cb50a44972`, igual ao do `bundled-cores`. O `.so` do Azahar patchado à mão em 11-07 não ficou instalado.
- ROMs já baixadas em `/sdcard/Android/data/app.retrogamesystem.debug/files/roms/3ds/`: SM3DL (id **1484**, 512 MiB) e MK7 (id **1165**, 1 GiB). Ids lidos da cópia do banco (`run-as … cat databases/retrograde{,-wal,-shm}`).
- Lançado por `am start -d "retrogamesystem://app.retrogamesystem.debug/play-game/id/1484"`. Logcat:
  - `libretrodroid: Audio initialization has been called with input sample rate 32728`, `Using low latency stream: 0`, `AAudioStreamBuilder_openStream() returns 0 = AAUDIO_OK`, `AAudioStream_requestStart(s#1) returned 0`. **O stream abre e inicia.**
  - Núcleo: `Created FAAD2 AAC decoder`; `Service.DSP … LoadComponent … size=0xBE44`, `Firmware hash: 0xd50f271c0a6b6eea`, `RegisterInterruptEvents … interrupt=2, channel=2`. O jogo carrega o componente DSP e o HLE aceita. Nenhuma linha de erro de áudio.
- `dumpsys media.audio_flinger`, trilha do pid do `:game`: `Active yes`, `SRate 48000`, `Usg 1` (media), `FrmRdy 5798`, `Underruns 0`. **A trilha está ativa e consumindo amostras.** Mas o volume de mídia do moto estava zerado (`G db -inf`, `PortVol -inf`, `PortMuted true`). O dump não diz se as amostras são silêncio, e com o volume zerado ninguém ouviria nada.
- Gravar o áudio de saída com `scrcpy --audio-source=output --audio-codec=raw` foi **bloqueado pelo modo automático** (binário baixado da internet). Falta essa medição, ou alguém ouvir com o volume ligado.

### Resultado no moto (dono, 2026-11-12): **tem som**

O dono subiu o volume e ouviu o SM3DL com som no moto g86. Isso vale para o mesmo núcleo (md5 `41609224…`), o mesmo arquivo do catálogo e o mesmo app. **Não reproduz fora do aparelho da cliente.**

O núcleo é o mesmo em toda versão publicada. `git log -- '*citra*'` no `lemuroid-cores` tem só `c2dca27` (2026-04-10) e `96663cc` (2026-05-21), e as duas cópias do `.so` (`bundled-cores` e `lemuroid_core_citra`) têm o mesmo md5. A versão do app da cliente não muda o núcleo.

O download tem uma única fonte: `find_by_file?source_id=1` (`RomOnDemandManager.downloadRom`). Ele grava em `.part`, confere `bytesWritten == contentLength` e só então renomeia (`downloadToFile`, ~linha 808). Um arquivo truncado só passaria se o servidor não mandasse `Content-Length`. Não há hash do conteúdo.

### O que sobra de diferente no aparelho dela

| # | Candidato | Por quê | Como testar |
|---|---|---|---|
| D1 | **Desempenho**: o SM3DL roda bem abaixo de 60 fps no S20 FE (Adreno 650) | O som do HLE sai em tempo emulado. Com o jogo a 20–30 fps, o FIFO do LibretroDroid fica vazio a maior parte do tempo e o `readNow` completa com zeros: sai quase silêncio com estalos. O "chiado" do Sonic & Sega All-Stars Racing no mesmo aparelho é o sintoma leve da mesma coisa. No moto, o mesmo core rodou o MK7 a 36–41 fps de core | Pedir à cliente um vídeo com som do SM3DL e o valor de "Escala da resolução". Em Adreno, medir o fps do SM3DL |
| D2 | **Cópia do arquivo dela** diferente ou corrompida | Uma única fonte, mas sem hash. Um defeito no RomFS (arquivo de som) deixa o jogo jogável e mudo | Excluir o jogo e baixar de novo |
| D3 | **Estado do Citra no aparelho dela** (`files/saves/Citra/`: sysdata/CFG, save e extdata do SM3DL) | É por aparelho e não veio na troca de dispositivo | Só depois de D1 e D2. Apagar arrisca o progresso dela |
| D4 | Pilha de áudio Samsung (Sound Assistant / som separado por app) | Afetaria o app inteiro, mas o Sonic tem som | Praticamente descartado |

## Hipóteses

| # | Hipótese | Situação |
|---|---|---|
| H1 | O core usa o callback de amostra única, que o LibretroDroid descarta | **Descartada**: o `libretro_sink.cpp` do commit do binário usa `audio_batch_cb` |
| H2 | Falta o decodificador AAC no build | **Descartada**: FAAD2 está no binário, e o SM3DL não usa AAC |
| H3 | Recriar o `Audio` por `SET_SYSTEM_AV_INFO` no meio do jogo | **Descartada**: o Citra não chama esse environment |
| H4 | "Silenciar" ligado no menu do jogo | Pouco provável: só valeria para um jogo se ela tivesse ligado ali e o estado persistisse. Não conferido |
| H5 | O áudio do 3DS sai mudo em qualquer jogo | **Descartada pela cliente**: "É só esse jogo que tá sem som" `[176591]` |
| H6 | Aparelho: volume, fone, Bluetooth | **Praticamente descartada**: mudo também com fone e depois de reiniciar `[177092]`, e o defeito é de um jogo só |
| H7 | Defeito do core HLE neste jogo, ou do `-dirty` do build (fonte ≠ binário) | **Principal.** O frontend está fora: o stream abre e a trilha consome amostras. Falta provar que as amostras do SM3DL são zero e as do MK7 não, e comparar com o Azahar |
| H8 | O "chiado" do Sonic & Sega All-Stars Racing (3DS) é o mesmo defeito do DSP HLE | Aberta. Registrar junto, sem afirmar |

## Próximo passo (passagem de bastão)

0. **(2026-11-12, depois do teste do dono) Pela cliente:** (a) excluir o SM3DL e baixar de novo (D2); (b) se continuar mudo, mandar um vídeo de ~20 s com som, de preferência numa fase com movimento, e dizer a "Escala da resolução" das opções do 3DS (D1). Se o vídeo mostrar a imagem lenta e estalos, o caminho é desempenho: o Azahar da T7 mediu +30% de fps.
1. (superado: o moto tem som) **Medir se as amostras do SM3DL são silêncio**, no moto (já pareado e com os dois jogos baixados). Há dois caminhos:
   - captura do áudio de saída, com o `scrcpy` liberado pelo dono, e `--audio-source=output --audio-codec=raw --record=x.wav`. Depois, RMS por janela no `.wav`;
   - **ou** subir o volume de mídia e alguém ouvir.
   Fazer o mesmo no MK7 (id 1165) como controle.
2. Se o SM3DL for mudo e o MK7 tiver som, H7 fica confirmada no nosso core. Rodar o SM3DL no Azahar standalone e no `.so` do Azahar da T7 (backlog [`2026-11-08-3ds-core-azahar-e-tela-unica-esticada.md`](../../backlogs/2026-11-08-3ds-core-azahar-e-tela-unica-esticada.md)). Se o Azahar tiver som, a T7 resolve, e este bug passa a depender dela. Se não tiver, testar o DSP LLE (precisa do `dspfirm.cdc`) antes de culpar o dump.
3. Se o SM3DL tiver som no moto: a diferença é do aparelho (Adreno 650 / Snapdragon 865). Aí vale pedir um vídeo à cliente.

## Correção

Nenhuma ainda.

## Validação

Super Mario 3D Land com som contínuo do título à primeira fase no S20 FE da cliente ou num aparelho equivalente. Mesmo teste com o Mario Kart 7.

## Lição

(a preencher)
