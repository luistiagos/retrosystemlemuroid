# [BUG] Mi Box com HD USB: download de PS1 diz "Espaco insuficiente. Necessario: 355 MB, disponivel: 177 MB" mesmo com o HD escolhido no app

**Data:** 2026-10-20
**Status:** aberto
**Severidade:** alta para TV box com pouca memoria interna — o cliente comprou para usar com HD externo, trocou de HD,
formatou em exFAT, escolheu as tres opcoes de "Diretorio" e desistiu do Android ("Ja escolhi as 3 e mudei de hd"),
migrando para o PC
**Complexidade:** media — o destino e decidido em dois lugares (`SmartStoragePicker` e o `fileUri` da linha do
catalogo), nenhum ligado ao "Diretorio"
**Branch:** version9
**Origem:** triagem do chamado de suporte #206 (sessao `252990820729054@lid`)

## Sintoma

Xiaomi Mi Box (Google TV; tela de armazenamento com marca TCL no print 144098), app instalado pelo APK armv7.
HD externo plugado antes de abrir o app. Todo download de PlayStation falha:
"Baixar ROM. Falha no download: Espaco insuficiente no dispositivo. Necessario: 355 MB, disponivel: 177 MB."

## Evidencia

Wpp_proccess (UTC, 15/10):

- 143953 23:03 — *"Mas ta dizendo que nao tem espaco"*; 143959 — Configuracoes da Mi Box mostram o HD.
- 144024 23:16 — print do erro: Necessario 355 MB, **disponivel 177 MB** (HD WD Blue 1 TB, um volume so).
- 144032 — segundo HD (500 GB) formatado em exFAT. 144098 — o app lista "Armazenamento Principal
  /storage/emulated/0", "Armazenamento secundario 1 /storage/AE40-9992", "Armazenamento secundario 2
  /storage/9230-344C". 144103 — dentro de `/storage/9230-344C` existe `Android/` (o sistema criou a pasta de app).
- 144113 — *"Selecionou e voltou prai"* (tela com "Verificar novamente" e "Diretorio").
- 144125 23:35 — mesmo erro, **disponivel 179 MB**.
- 144131 — armazenamento da Mi Box: interno 5,4 GB, "HD Games" 499 GB, "disk" 367 MB.
- 144138 — *"no HD de 1 tera que nao ta particionado deu o mesmo erro"*; 144163/144165 — *"Ja escolhi as 3 e mudei
  de hd"*.

O "disponivel" ficou em ~177-179 MB com dois HDs diferentes e com as tres escolhas de diretorio. Isso aponta para um
mesmo volume (provavelmente o interno), nao para o HD.

Codigo aberto:
- `RomOnDemandManager.downloadToFile` (~777): `available = destFile.parentFile?.usableSpace`; e dai que sai a
  mensagem.
- `RomOnDemandManager.resolveDestFile`: `destFile` = `Uri.parse(game.fileUri).path`; so cai em
  `DirectoriesManager.getInternalRomsDirectory()/<systemId>` quando a linha nao tem path.
- `DirectoriesManager.cachedRoms` = `by lazy { SmartStoragePicker.getBestRomsDirectory(appContext) }`; desde
  `done/2026-10-02-smartstoragepicker-pasta-roms-alterna-entre-volumes.md` a escolha e fixa entre processos.

## Hipoteses — nenhuma verificada

1. As linhas do catalogo foram criadas com `fileUri` no volume escolhido no primeiro start (interno) e nao mudam
   quando o HD aparece: o download vai sempre para o interno. Medir: `fileUri` das linhas de `psx` num aparelho com
   SD/USB plugado depois do primeiro start.
2. A escolha fixa do `SmartStoragePicker` (fix de 02/10) prendeu o interno porque o HD nao estava montado (ou nao
   era gravavel) no primeiro start; plugar o HD depois nao reescolhe.
3. "Diretorio" (`StorageFrameworkPickerLauncher`) so muda a pasta de **varredura** de ROMs do usuario, nao o destino
   do download — por isso escolher qualquer uma das tres opcoes nao mudou nada. Confirmar abrindo o launcher e o
   `SettingsViewModel` antes de afirmar.

## Correcao

(a definir depois da medicao)

## Validacao

AVD com SD/USB virtual: instalar, abrir sem a midia, plugar a midia, baixar ROM de PSX maior que o espaco interno
livre. Controle positivo: erro "Espaco insuficiente" com o "disponivel" do interno. Com o fix: download no volume
externo. Controle oposto: aparelho so com interno continua baixando no interno.

## Licao

O "disponivel" da mensagem e a pista mais barata: quando ele nao muda trocando de HD, o app nao esta olhando o HD.
A mensagem deveria dizer o caminho do volume que mediu.

Ver tambem (lado do agente): `digitalstoregamesproject/docs/modules/chatbot-whatsapp/areas/prompt-kb/bugs/2026-10-20-agente-afirma-windows-7-8-suportado-e-diagnostica-tls-errado.md`
(secao Escopo, o "Diretorio" repetido seis vezes).
