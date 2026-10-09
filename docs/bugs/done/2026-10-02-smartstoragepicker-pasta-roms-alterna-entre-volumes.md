# [BUG] `SmartStoragePicker` reescolhe a pasta de ROMs a cada início de processo — pode alternar entre volumes

**Data:** 2026-10-02
**Status:** ✅ Resolvido — corrigido e validado em AVD com SD virtual (2026-10-02)
**Severidade:** Média (latente). Afeta aparelho com mais de um volume gravável: TV box com SD/pendrive,
celular com SD.
**Branch:** version9
**Origem:** achado lateral da investigação de
`docs/bugs/open/2026-10-02-tvbox-enduro-atari2600-sumiu-do-catalogo.md`

---

## Sintoma (esperado, não observado)

A pasta de ROMs (`DirectoriesManager.getInternalRomsDirectory()`) pode mudar de volume de uma abertura
do app para outra. Consequências possíveis:

- jogos baixados "somem" (o arquivo ficou no volume antigo, a linha nova aponta para o volume novo e é
  tratada como placeholder → pede download de novo);
- linhas duplicadas no catálogo após uma passada completa do `ManifestQuickLoader`;
- o scan de biblioteca apaga o catálogo inteiro do volume antigo.

## Análise

Símbolos abertos:

- `SmartStoragePicker.getBestRomsDirectory` — cacheia em `@Volatile cachedBestRomsDir`, que dura só o
  processo. `computeBestRomsDirectory`: sem pasta SAF do usuário e com mais de um volume em
  `getExternalFilesDirs(null)`, escolhe `volumes.maxByOrNull { freeBytes(it) }`. Isso é **espaço
  livre do momento**, ou seja, muda conforme o usuário baixa/apaga coisas ou pluga/despluga mídia.
- `SmartStoragePicker.invalidateCache()` — **nenhum chamador** (`grep -rn invalidateCache` só acha a
  definição). Na prática a escolha é refeita a cada início de processo, nunca no meio dele.
- `DirectoriesManager.cachedRoms` — `by lazy { SmartStoragePicker.getBestRomsDirectory(appContext) }`.
- `ManifestQuickLoader.load()`:
  - monta `fileUri = File(File(romsDir, systemId), fileName).toUri()`. Com o volume trocado, toda URI
    muda de prefixo. `games.fileUri` é `unique`, mas o prefixo novo não colide → `insertIfNotExists`
    insere uma **segunda** linha por jogo;
  - limpeza "stale": só considera linhas que começam com o `realPrefix` **atual**. As do volume antigo
    nunca são limpas.
- `LemuroidLibrary.romsUriPrefix` + `GameDao.deleteByLastIndexedAtLessThan` — o guard que protege o
  catálogo é `SUBSTR(fileUri, 1, LENGTH(:romsPrefix)) <> :romsPrefix` com o prefixo **atual**. Linhas
  do volume antigo perdem a proteção: todo placeholder delas é apagado no próximo scan
  (`MediaMountedReceiver` dispara um ao montar mídia, que é justamente o gatilho da troca). Sobram as
  que estão em `downloaded_roms`.
- `RomOnDemandManager.isManagedRom` / `resolveDestFile` também usam o `romsDir` atual: ROM baixada no
  volume antigo deixa de ser "gerenciada".

**Não confirmado em aparelho.** Falta ver se uma TV box real alterna de fato (precisa de dois
volumes graváveis com espaço livre parecido, ou mídia plugada e desplugada).

## O que fazer (proposta)

1. Tornar a escolha **persistente**: decidir o volume uma vez (primeira execução, ou quando o usuário
   escolher em Ajustes) e gravar em SharedPreferences. Só reescolher se o volume gravado deixar de
   existir ou de ser gravável.
2. Ao reescolher, migrar as URIs de `games` (`UPDATE … SET fileUri = novoPrefixo || SUBSTR(…)`, mesmo
   padrão do `rewritePrebuiltUris`) em vez de criar linhas novas. Os arquivos baixados ficam no volume
   antigo ou são movidos; decidir qual.
3. Teste: unitário do picker com dois volumes cujo espaço livre se inverte entre chamadas → mesma pasta
   nas duas; e o caso do volume gravado sumir → cai no primário.
4. Validação em AVD com SD card virtual (`lemu_api25_2gb` + `-sdcard`), alternando o espaço livre.

## Análise complementar (2026-10-02, antes da correção)

Símbolos abertos além dos de cima:

- `Game` — índice único só em `fileUri` (não em `systemId, fileName`). Confirma as linhas duplicadas
  quando o prefixo muda e há passada completa do loader.
- `ManifestQuickLoader.load()` — o `rewritePrebuiltUris` usa o volume escolhido **no primeiro boot**.
  Em boot de fast-path nenhuma linha é reescrita: as linhas ficam no volume antigo enquanto
  `getInternalRomsDirectory()` aponta para o novo.
- `RomOnDemandManager.resolveDestFile` — usa o `fileUri` da linha (não o `romsDir`). O download vai
  para o volume da linha, não para o atual.
- `RomOnDemandManager.deleteRom` / `deleteFromCatalog` — comparam `destFile.parentFile` com
  `File(romsDirAtual, systemId)`. Com a linha em outro volume a comparação falha sempre e o código
  toma o caminho "diretório de extração multi-disco": **`parentDir.deleteRecursively()` apaga a pasta
  do sistema inteira no volume antigo** (todas as ROMs baixadas daquele sistema). Consequência mais
  grave que as listadas no Sintoma.
- `RomOnDemandManager.isManagedRom` — falso para linha em outro volume → `prepareGameForLaunch` não
  extrai ZIP multi-disco.
- `GameSearchDao` — `games_bu`/`games_au` disparam em **qualquer** UPDATE de `games`. Reescrever
  `fileUri` em massa fragmenta o FTS → chamar `optimizeFtsIndex` depois.
- `SettingsViewModel.uiState` — `isUsingRemovableStorage` recalcula pelo espaço livre do momento, sem
  passar pelo cache: a tela pode dizer um volume e o app usar outro.
- Testes: só `lemuroid-app/src/test` existe (JUnit puro, sem Robolectric). `retrograde-app-shared`
  não tem `src/test`. Lógica testável precisa ser função pura, sem `Context`/`StatFs`/`Uri`.

Hipóteses descartadas:

- "Persistir a escolha basta" — não: instalações já afetadas têm linhas no volume antigo, e um volume
  removível pode sumir (pendrive). Toda troca de volume que ainda puder acontecer precisa realinhar as
  linhas.
- "Mover os arquivos baixados para o volume novo no boot" — não: são GB em mídia lenta, no startup.
  Linha com arquivo presente no volume antigo **fica** lá; só placeholders são reapontados.
- "Ao sumir o volume gravado, gravar o novo" — não: em TV box o pendrive pode montar depois do
  processo subir. Fallback para o primário vale **só para o processo**; a escolha gravada continua,
  e o volume volta a ser usado quando reaparecer.

## Plano

1. `SmartStoragePicker`: escolha persistida (prefs próprias). Decisão em função pura
   (`chooseRomsDir`), testável. Upgrade sem escolha gravada: preferir o volume que já tem `roms/`.
   `isUsingRemovableStorage` passa a refletir a pasta realmente usada.
2. `ManifestQuickLoader`: passo de realinhamento a cada boot (consulta barata que quase sempre volta
   vazia), reapontando placeholders de outra raiz `…/Android/data/<pkg>/files/roms` para a atual e
   resolvendo duplicatas. Plano em função pura, testável.
3. `RomOnDemandManager`: `isManagedRom` e a detecção de diretório de extração independentes do
   volume atual. Isso fecha o `deleteRecursively` da pasta do sistema.
4. Guard do scan (`deleteByLastIndexedAtLessThan`): proteger qualquer raiz gerenciada, não só a atual.
5. Testes unitários do picker e do plano de realinhamento.

## Correção

1. **Escolha persistida** — `RomsDirChoice.choose` (novo, função pura) + `SmartStoragePicker`
   (prefs `smart_storage_prefs`, chave `roms_dir`, gravada com `commit()` porque o `:game` lê no
   próprio start). Regras: pasta gravada com volume montado → usa, sem olhar espaço livre; volume
   gravado ausente → primário **só neste processo** (a escolha gravada volta a valer quando o volume
   reaparece); sem escolha gravada (instalação nova ou upgrade) → se exatamente um volume já tem
   `roms/`, é ele; senão a regra antiga (SAF → primário; maior espaço livre). `isUsingRemovableStorage`
   agora reflete a pasta de fato em uso.
2. **Realinhamento** — `RomsRootRealigner.plan` (novo, puro) executado por
   `ManifestQuickLoader.realignManagedRoots()` a cada boot, antes dos fast-paths, e de novo no fim da
   passada completa. Detecção barata: `GameDao.selectForeignManagedRoots` (raízes
   `…/Android/data/<pkg>/files/roms` ≠ atual que ainda têm linhas; quase sempre vazio). Placeholder →
   reapontado; arquivo no disco → fica no volume onde está; duplicata → sobrevive a linha com arquivo
   (senão a da raiz atual), com favorito/último-jogado mesclados. FTS desfragmentado depois.
3. **`RomOnDemandManager`** — `isManagedRom` aceita a pasta gerenciada de qualquer volume;
   `deleteRom`/`deleteFromCatalog` calculam o diretório do sistema a partir da raiz do **próprio
   arquivo** (`systemDirOf`). Fecha o `deleteRecursively` da pasta do sistema inteira.
4. **Guard do scan** — `deleteByLastIndexedAtLessThan` ganhou `managedMarker`
   (`DirectoriesManager.getManagedRomsMarker()`): linha sob a pasta gerenciada de qualquer volume não
   é apagada.

Baseline do ktlint regenerado nos dois módulos (só re-indexação de linha: nenhuma contagem por
arquivo/regra subiu; 609→598 e 967→952).

## Revisão antes da validação (2026-10-02, segunda sessão)

Diff relido inteiro (`SmartStoragePicker`, `RomsDirChoice`, `RomsRootRealigner`,
`ManifestQuickLoader.realignManagedRoots`, `GameDao`, `LemuroidLibrary.removeDeletedGames`,
`RomOnDemandManager`). Achado:

- **`SmartStoragePicker.computeBestRomsDirectory` — o argumento `fallback = defaultRomsDir(appContext)`
  é avaliado antes de `RomsDirChoice.choose`, e `defaultRomsDir` fazia `mkdirs()` de
  `<primário>/roms`.** Em instalação nova com SD, a regra "volume que já tem `roms/`" passava a ver
  exatamente um volume com biblioteca — o primário, criado um instante antes pelo próprio picker — e
  o SD nunca era escolhido, por mais espaço livre que tivesse. Em upgrade com a biblioteca no SD, o
  mesmo `mkdirs` fazia `withLibrary.size == 2` e a escolha voltava a ser por espaço livre. O teste
  unitário não pega: ele chama a função pura, que não tem esse efeito colateral.
  - Correção: `defaultRomsDir` sem `mkdirs` (o `mkdirs()` da pasta escolhida já acontece no
    retorno de `computeBestRomsDirectory`).

Conferido e **sem** defeito:

- `downloaded_roms` tem chave `(systemId, fileName)`, não `fileUri` — reapontar a URI não
  desassocia o download.
- `selectForeignManagedRoots`: `INSTR + LENGTH - 2` corta exatamente antes da `/` final do marker.
- `isOnDisk` compara `Uri.parse(uri).path` (decodificado) com `File.absolutePath` — mesma forma.
- `RomOnDemandManager.systemDirOf`: arquivo em `<raiz>/<sys>/x` → só o arquivo; em
  `<raiz>/<sys>/<pasta>/x` → a pasta de extração. Certo em qualquer volume.

Custo conhecido (aceito, não é bug): com o volume gravado ausente (pendrive que monta depois do
processo), o realinhamento reaponta o catálogo inteiro para o primário e, quando o volume volta,
reaponta de volta — ~30 mil `UPDATE`s que disparam os triggers do FTS, mais o `optimizeFtsIndex`.
É em background no startup e só acontece quando o volume de fato some; sem isso as linhas apontariam
para um volume inexistente e o download falharia.

## Validação

- `./gradlew :lemuroid-app:testFreeBundleDebugUnitTest` → 46/46 verdes, 13 novos:
  `RomsDirChoiceTest` (6: espaço livre invertido entre chamadas → mesma pasta; volume gravado some →
  primário sem regravar e volta quando reaparece; upgrade fica no volume com `roms/`; SAF; sem volume;
  raiz gerenciada) e `RomsRootRealignerTest` (7: reaponta placeholder; download fica; duplicata com
  merge; download vence placeholder; download nos dois volumes; mesmo jogo em dois volumes antigos;
  `roms2` não é `roms`).
- `ktlintCheck` dos dois módulos verde; kapt do Room validou as queries novas.
- Após a correção da revisão (`defaultRomsDir` sem `mkdirs`): mesma suíte verde e `ktlintCheck`
  verde. O `ktlintCheck` acusou `freeBytes`/`totalBytes` — violações antigas, congeladas no baseline
  por número de linha, que o comentário novo deslocou. Foram **corrigidas** (corpo de expressão em
  linha nova) e as 4 entradas removidas do baseline, sem regenerá-lo.
- **AVD `lemu_api25_2gb` com SD de 1 GB** (APK `-PdevAbi=x86_64`, instalação limpa). Roteiro e
  resultado, cada passo conferido no logcat e por `sqlite3` no banco do app:
  1. Primário enchido com `fallocate` até 481 MB livres (SD: 1019 MB) → instalação nova escolhe o SD
     (`ROMs dir chosen and saved — /storage/0000-0000/…`) e **não** cria `roms/` no primário.
     Prova a correção da revisão: com o `mkdirs` ansioso o primário ganharia.
  2. "Download" simulado no SD (arquivo de 256 KB + linha em `downloaded_roms` + favorito). Primário
     liberado (6,7 GB livres contra 1 GB no SD) → reabrir mantém o SD (`ROMs dir /storage/0000-0000/…`,
     sem regravar). **É o cenário do bug.**
  3. `sm unmount` do SD → reabrir usa o primário só no processo (`smart_storage_prefs` continua com o
     SD); `realigned … -> …/emulated/0/… (repointed=58156)`; favorito preservado. Custou ~13 s no
     emulador.
  4. `sm mount` → `realigned … -> …/0000-0000/… (repointed=58156)`; 58.156 linhas = 58.156
     `(systemId, fileName)` distintos; a linha baixada volta a apontar para o arquivo com conteúdo.
  5. Download mantido no **outro** volume (primário, com o atual no SD) + `force_full_reload` → a
     passada completa insere o placeholder no SD (`inserted=1`) e o realinhamento pós-carga funde
     (`merged=1 deleted=1`): sobra a linha com arquivo, favorita, sem duplicata.
  6. Pela UI, ▸ **Excluir ROM baixada** desse jogo (arquivo no primário, pasta atual no SD): o arquivo
     vira 0 byte e um `sentinela.nes` na mesma pasta `nes/` **sobrevive**. Antes da correção, esse
     caminho fazia `deleteRecursively` da pasta do sistema.
- O AVD tinha `hw.sdCard=yes` sem `sdcard.img` — nenhum SD aparecia. O SD vem de
  `mksdcard -l LEMUSD 1024M sd.img` + `emulator … -sdcard sd.img`; monta como volume público
  `public:253,64` (`0000-0000`), e `sm unmount`/`sm mount` simulam tirar e recolocar a mídia.
- Achado lateral, fora do escopo e registrado à parte: na primeira abertura, a passada completa
  apagou 53.735 linhas e inseriu 52.099, porque o prebuilt guarda a `fileUri` sem codificar e o loader
  monta com `%20` (`docs/bugs/open/2026-10-02-prebuilt-fileuri-sem-encoding-recria-catalogo.md`).

## Lição

- Tudo o que o app grava como caminho absoluto (aqui, `games.fileUri`) amarra a decisão que gerou o
  caminho: uma heurística que pode mudar de resposta entre processos (espaço livre) precisa ser
  decidida uma vez e gravada.
- Comparar um arquivo com "a pasta atual" em vez de com a pasta onde ele está transforma uma troca
  de volume em `deleteRecursively` no lugar errado. Derivar a raiz do próprio caminho.
