# [BUG] Neo Geo CD: nenhum jogo funciona

- **Data:** 2026-10-25
- **Status:** fechado em 2026-10-25 a pedido do dono, depois da validação ponta a ponta em x86_64 (AVD) e
  ARM64 (Moto G86 real), com o dono jogando no aparelho. Versão publicada: ainda não (entra na próxima).
- **Severidade:** alta (sistema inteiro inutilizável: 98 jogos no catálogo)
- **Complexidade:** baixa (BIOS exigido + default de opção do core)
- **Branch:** redesenho-tv-jogos-salvos-tela
- **Origem:** relato do dono

## Sintoma

"O sistema do Neo Geo CD não está funcionando." O comentário em
`GameSystem.kt` (NEOCD) registra um teste anterior em aparelho: o BIOS carrega e mostra o painel
de CD, o core abre e lê a trilha de dados, mas o jogo nunca começa — igual em todo jogo, em
cue+bin e chd, com Universe BIOS e com CDZ. Como tentativa, os dois speed hacks foram forçados
para `Off` (`neocd_cdspeedhack`, `neocd_loadskip`); o resultado nunca foi registrado.

## Evidência (análise)

- `GameSystem.kt::NEOCD` — core `CoreID.NEOCD` (`libneocd_libretro_android.so`), BIOS exigido
  `neocd/uni-bioscd.rom`, extensões `cue`/`chd`, sem `supportsLibretroVFS` (core recebe o caminho).
- `BiosManager.kt` — `neocd/uni-bioscd.rom`, MD5 `08CA8B2D…`. Baixado do HF
  (`luistiagos/bios/neocd/uni-bioscd.rom`): `md5sum` = `08ca8b2dba6662e8024f9e789711c6fc`, confere.
- Core: as strings do `.so` arm64 não citam `000-lo.lo` (o Y-Zoom é gerado internamente) —
  descartado BIOS auxiliar faltando.
- Catálogo: `grep -c '^neocd/' catalog_manifest.txt` = 98, todos `.zip`.
- `RomOnDemandManager.kt::extractMultiDiscZipIfNeeded` — zip com `.cue` é extraído para a pasta
  irmã, o zip apagado e `fileUri` passa a apontar o `.cue`. Em erro: log e mantém o zip.
- Zip de teste (`Fire Suplex ~ 3 Count Bout (Japan, USA) (En,Ja).zip`, archive.org
  `ef_NeoGeo_CD`, 348 MB): Redump padrão, 1 `.cue` + 27 `.bin`, trilha 1 `MODE1/2352`, resto
  `AUDIO`, nomes do `FILE` batem com os arquivos do zip.

- Reprodução: AVD `lemu_api25_2gb`, debug `-PdevAbi=x86_64` do HEAD `85b4d04`, o zip acima extraído
  e empurrado para `files/roms/neocd/<nome>/`, `fileUri` da linha (id 35970) apontada para o `.cue`
  por `sqlite3`. Sem estado salvo (`files/states/neocd/` vazio). O core roda a 60 fps
  (`EMUFPS 60.31`), o painel "GAME / CD-PLAYER" do CDZ aparece com `TRACK 00` e fica parado —
  mesmo com Start pressionado (toque de 400 ms no pad).
- Matriz de BIOS (mesmo jogo, mesmo build, speed hacks `Off`; cada BIOS copiado para
  `files/system/neocd/`, opção `neocd_bios` = 1º da lista, conferida no logcat):

  | BIOS (HF `luistiagos/bios/neocd/`) | MD5 | Resultado |
  |---|---|---|
  | `uni-bioscd.rom` "CDZ, Universe 3.3" (o que o app baixa) | `08CA8B2D…` | painel CDZ congelado, `TRACK 00` |
  | `neocd_f.rom` Front Loader | `8834880C…` | lê o TOC (27 trilhas, 42:50), para no CD player "PUSH START BUTTON"; com Start o jogo abre |
  | `neocd_t.rom` Top Loader | — | igual ao Front Loader |
  | `neocd_z.rom` CDZ original | `11526D58D4C524DAEF7D5D677DC6B004` / CRC `33697892` | **jogo abre sozinho** (< 30 s), sem tocar em nada |

- `NeoCD` descobre os BIOS em `system/neocd/` e lista todos em `neocd_bios`
  (`neocd_z.rom (CDZ)|uni-bioscd.rom (CDZ, Universe 3.3)`); o default é o 1º.

## Hipóteses descartadas

- BIOS errado/corrompido no HF — MD5 confere com o declarado em `BiosManager`.
- `000-lo.lo` ausente — o core não procura o arquivo (strings do `.so`).
- Zip em formato estranho — é Redump padrão.
- Caminho com `~`, `,`, parênteses, espaços — cópia com nomes `t01.bin`…/`test.cue` falha igual,
  e o `t01.bin` aparece aberto em `/proc/<pid>/fd`: o core abre o disco.
- `.cue` com CRLF quebrando o regex do parser — a cópia simples era LF e falhou igual.
- VFS do LibretroDroid — `GameSystem` do NeoCD não liga `supportsLibretroVFS`, então
  `Environment::environment_handle_get_vfs_interface` devolve `false`; o core usa stdio.
- Start que não chega ao core — com Front Loader o mesmo toque longo inicia o jogo.
- Speed hacks — o teste anterior (comentário no `GameSystem`) falhou com eles `On`; aqui falhou
  com `Off`. Não são a causa.

## Causa-raiz

O app baixa e exige só o **Universe BIOS CD** (`neocd/uni-bioscd.rom`), e com ele este build do
NeoCD não sai do painel do CDZ: o TOC nunca é lido (`TRACK 00`) e nenhum jogo inicia. Com o BIOS
CDZ original (`neocd_z.rom`), que já está no mesmo dataset do HF, o mesmo disco inicia direto.
Por que o Universe falha não foi investigado no core (fora do alcance do repo); a suspeita é o
patch que o core aplica ao Universe 3.3 (`WARNING: BIOS Universe 3.3 checksum patch failed.` está
nas strings do `.so`). O comentário anterior dizia que o CDZ original também falhava — aquele
teste não foi reproduzido: com ele no lugar, aqui o jogo abre.

## O que fazer

1. `BiosManager.kt` — trocar a entrada `neocd/uni-bioscd.rom` por `neocd/neocd_z.rom`
   (MD5 `11526D58D4C524DAEF7D5D677DC6B004`, CRC32 `33697892`, `externalName` igual).
2. `GameSystem.kt::NEOCD` — `requiredBIOSFiles = listOf("neocd/neocd_z.rom")` e fixar
   `CoreVariable("neocd_bios", "neocd_z.rom (CDZ)")`: quem já tem o `uni-bioscd.rom` baixado
   fica com os dois em `system/neocd/`, e o default do core é o 1º da lista — sem fixar, a escolha
   dependeria da ordem do diretório. Reescrever o comentário (o atual descreve um teste errado).
3. Prova: build debug x86_64, apagar `files/system/neocd/`, abrir o jogo pela UI — o BIOS
   `neocd_z.rom` é baixado sozinho e o jogo inicia sem toque. Repetir com `uni-bioscd.rom`
   presente junto (usuário antigo) — `neocd_bios` no logcat tem de ser `neocd_z.rom (CDZ)`.

## Correção

- `BiosManager.kt` — entrada `neocd/uni-bioscd.rom` trocada por `neocd/neocd_z.rom`
  ("Neo Geo CD BIOS (CDZ)", MD5 `11526D58D4C524DAEF7D5D677DC6B004`, CRC32 `33697892`).
- `GameSystem.kt::NEOCD` — `requiredBIOSFiles = listOf("neocd/neocd_z.rom")` e
  `CoreVariable("neocd_bios", "neocd_z.rom (CDZ)")` nos `defaultSettings`; comentário reescrito.
  Speed hacks continuam `Off` (default do libretro).
- O `uni-bioscd.rom` de quem já o baixou fica no disco, sem efeito (a opção está fixada).

## Validação

Builds debug `freeBundle` com a correção (APKs de 2026-10-25 01:11, HEAD `85b4d04` + este diff).

**A. Cenários de BIOS** (AVD `lemu_api25_2gb`, Android 7.1 x86_64, `Fire Suplex ~ 3 Count Bout`):

1. **Sem BIOS** (`rm -rf files/system/neocd`): logcat `BiosDownloader: neocd/neocd_z.rom MD5 OK`,
   `neocd_bios = neocd_z.rom (CDZ)`; tela-título do jogo em < 30 s, sem toque.
2. **Usuário antigo** (`uni-bioscd.rom` + `neocd_z.rom` em `system/neocd/`): opção listada como
   `neocd_z.rom (CDZ)|uni-bioscd.rom (CDZ, Universe 3.3)`, valor `neocd_z.rom (CDZ)`; jogo inicia.

**B. Ponta a ponta pela UI** (catálogo → Neo Geo CD → jogo → "Salvar" → download → extração →
"Download concluído / Jogar" → BIOS → jogo), Metal Slug (580 MB, archive.org `ef_NeoGeo_CD`):

| Etapa | x86_64 — AVD `lemu_api25_2gb` (`pm clear` antes) | ARM64 — Moto G86 5G, Android 16 (aparelho real) |
|---|---|---|
| Download + extração | HTTP 200 `contentLength=579658889`; `extractMultiDiscZip … -> .cue (21 entries)` | idem |
| BIOS automático | `neocd/neocd_z.rom MD5 OK`, `neocd_bios = neocd_z.rom (CDZ)` | idem |
| Boot sem toque | "NOW LOADING" do jogo → menu principal | idem |
| Direcional | ↓ ↓ ↑ ↑ move o cursor do menu | ↓ ↓ ↑ |
| A (botão de baixo) | abre "OK? YES/NO"; na fase, **atira** | abre "OK? YES/NO", entra na missão |
| B (direita) | cancela o diálogo; na fase, **pula** | cancela o diálogo |
| C (esquerda) | na fase, **granada: BOMB 10 → 09** | — (o dono jogou; ver abaixo) |
| Start | na fase, **pausa** (cronômetro parado em 47 por > 3 s) e retoma | — |
| Saída (Voltar) | `Stored sram 8192`, `Stored autosave 8232116`, `System.exit 0` | idem |
| Reabrir | — | volta na mesma cena (2.200 pts, BOMB 07) pelo autosave |

- **Segundo jogo + seletor de versões** (AVD): KOF '94 tem 2 versões; o seletor abriu e a variante
  não-representante `(Japan, USA) (Rev 1)` baixou (574 MB), extraiu e abriu sozinha a ~60 fps. Luta
  jogada, e a tela "DATA SAVE OK?" (cartão de memória) gravou com YES.
- **Botão D**: o Metal Slug não usa D e o AVD estava lento demais (captura de vários segundos) para
  flagrar o chute forte no KOF. Provado pela cadeia, cujas 3 outras teclas foram medidas: o pad
  (`Arcade4Right`) envia A/B/Y/X em volta do círculo — medido A=baixo, B=direita, Y=esquerda, logo X=cima;
  `input.cpp` do LibretroDroid: `AKEYCODE_BUTTON_X → RETRO_DEVICE_ID_JOYPAD_X`; o NeoCD mapeia
  RetroPad B/A/Y/X → Neo Geo A/B/C/D (as três medidas batem com essa tabela). A tela **PAD SET** do KOF
  '94 desenha o mesmo losango: D em cima, C à esquerda, B à direita, A embaixo.
- **Coin/Select** (`$`): sem função no Metal Slug (o CD não tem fichas), sem efeito visível — esperado.
- **Teste do dono**: o dono jogou o Metal Slug no Moto G86 durante a validação — *"no mais estava ok"*.
  Observou *"estava meio acelerado"*: registrado como evidência nova no bug do mesmo aparelho
  (`2026-10-23-pokemon-ds-roda-acelerado-com-acelerar-desligado-moto-g86.md`): painel a 90 Hz com o
  core a `EMUFPS 60.00` (Neo Geo nativo 59,19). Não é do NeoCD nem desta correção.
- `./gradlew ktlintCheck` completo: passa (2 itens do passivo deslocados no `baseline.xml` de
  `retrograde-app-shared`: `BiosManager.kt` 492→495, `GameSystem.kt` 3202→3200).

**Não coberto:** armeabi-v7a (o Moto é só arm64, sem imagem ARM de emulador instalada; o `.so` v7a é do
mesmo build do buildbot que o arm64); gamepad físico (só o pad de toque).

## Lição

- "BIOS carrega e mostra o painel" não é "o BIOS funciona": o painel do Neo Geo CD é justamente o
  que aparece quando o disco não é reconhecido. Antes de mexer em opção do core, trocar o BIOS —
  a matriz de 4 BIOS custou 5 minutos e isolou a causa.
- Registrar o resultado de um teste de hipótese no código/doc, não só a hipótese: o comentário
  anterior forçou os speed hacks `Off` "para descartar" e o resultado nunca foi anotado.

## Fora do escopo (achado durante a investigação)

O deep link `play-game/id/<id>` (`ExternalGameLauncherActivity`) fica em "Preparing environment"
indefinidamente no AVD: `waitPendingOperations` espera algum trabalho único `ENQUEUED` acabar.
Não investigado.
