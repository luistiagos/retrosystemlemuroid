# [BACKLOG] App de Switch próprio, fork do Eden (molde do ARMSX2-forkv2)

**Status:** obsoleto — para o Lemuroid: virou o projeto RGS Switch, fora deste repo, verificado em 2026-11-13 (T29)

**Data:** 2026-10-29
**Status anterior (escrito antes da T29, não verificado):** etapa analítica **concluída em 2026-10-30**. O projeto agora é o **RGS Switch**, em
`D:\projects\switch\rgsswitch`. A documentação viva está em `D:\projects\switch\rgsswitch\docs\rgsswitch\`
(`arquitetura.md`, `riscos.md`), e a passagem de bastão atual é o §17 do `arquitetura.md`. Este doc fica como
registro da origem.
**Origem:** fecha a Fase 0 de `docs/backlogs/arquivados/2026-10-27-suporte-nintendo-switch.md`. Leia lá a seção
"Resultado do controle (2026-10-29)".
**Tipo:** projeto novo, **fora do Lemuroid**.

## Decisão do dono

> "faremos com o eden semelhante ao que fizemos com armsx2 na criação do ARMSX2-forkv2, faremos um clone da versão mais
> atual do eden e a partir dele faremos o nosso projeto, separado do Lemuroid, colocaremos o catalogo de roms, roms
> salvas, etc. [...] O app será o Eden com as funcionalidades de catalogo e roms baixadas"

O mesmo caminho do PS2: tentar o core libretro dentro do Lemuroid falhou, e o produto passa a ser **a árvore do
emulador upstream com o nosso delta por cima**, não o Lemuroid com o core enxertado.

## Por que o Lemuroid não serve (medido, não suposto)

Com o mesmo jogo (LIMBO, `.nsp` do dono), o mesmo aparelho (Moto G86, MT6878, Mali-G615 MC2, 8 GB) e 720p portátil:

| | Core suyu libretro no Lemuroid | Eden v0.2.1 standalone |
|---|---|---|
| fps do jogo (gameplay) | 17–21 | **59–60** |
| Thread `GPU` | 54% | 18% |
| PSS | 2,3–2,7 GB | 1,7 GB |

Os avisos de Vulkan da Mali (11 recursos ausentes) aparecem **nos dois**, então não explicam a diferença. O custo está
no caminho de vídeo do core libretro: renderer *headless* com readback para buffer de CPU a cada quadro, porque o
LibretroDroid só fala GL. Além disso, o core não pausa em segundo plano (continua emulando e tocando) e o homebrew
libnx aborta no patcher NCE. Detalhes nas rodadas 1 e 2 do doc da Fase 0.

## O molde: ARMSX2-forkv2

Repositório: `D:\projects\play2\ARMSX2-forkv2` (branch `armsx2forkv2`). Ler **antes de planejar**:

| Arquivo | Para quê |
|---|---|
| `CLAUDE.md` | "O que é esta branch" (árvore deles + delta nosso, `git merge upstream/master`), "A regra que sustenta o fork" (correção de motor vira contribuição ao upstream, não edição local), restrições de identidade, rastreabilidade por task |
| `docs/plano-fork-sobre-upstream.md` | o plano do fork. §3 é o que **não** reimplementar, §4 é a lista do que é nosso: §4.1 catálogo + fila de download (1.697 linhas), §4.2 telemetria `/logErr`, §4.3 atualização in-app pelo nosso canal. §5 é identidade e "marca do upstream que sai em todo merge" |
| `docs/ROM_CATALOG_DOWNLOAD.md` | o desenho do catálogo e do download no app do PS2 (manifest, parser, download manager, tela, diálogo, diretórios no aparelho) |
| `docs/download-queue.md` | fila de download |
| `AGENTS.md` | divisão de trabalho entre sessão mestre, subsessões e auditora |

No Lemuroid, as mesmas funcionalidades existem em Kotlin e servem de referência de comportamento: `RomOnDemandManager`
(lookup `find_by_file` no pythonanywhere com fallback HuggingFace, archive.org com sessão, retry, ENOSPC),
`SaveQueueManager` (fila persistente) e o formato do `catalog_manifest.txt`. Ver o `CLAUDE.md` do Lemuroid.

## Upstream: Eden

- Repositório oficial: **`https://git.eden-emu.dev/eden-emu/eden.git`** (Forgejo), branch `master`. Última tag
  estável vista: `v0.2.1` (2026-06-01). O `master` tinha commit de 2026-10-29.
- **O GitHub `eden-emulator/*` dá HTTP 451: foi derrubado por DMCA da Nintendo (2026-03).** O upstream é o Forgejo.
  Não buscar fonte nem APK em espelho de terceiros.
- App Android em `src/android/` (Gradle Kotlin DSL, `app/`). O código Kotlin ainda usa o pacote herdado
  `org.yuzu.yuzu_emu` (`activities/`, `features/`, `fragments/`, `model/`, `ui/`, `utils/`, `NativeLibrary.kt`,
  `YuzuApplication.kt`). O `applicationId` do APK oficial é `dev.eden.eden_emulator`.
- Licença GPLv3, igual ao PCSX2/ARMSX2: o fork também é GPLv3 e publica o fonte.
- O APK oficial já tem três variantes (`standard`, `optimized`, `legacy` para Adreno 6xx); a `chromeos` é x86_64.
  Verificar no `build.gradle.kts` de onde vêm e qual delas o produto distribui.

## O que o produto precisa ter (primeira entrega)

1. **A árvore do Eden compilando** como está, com `applicationId`, nome e ícone nossos, e a marca do Eden fora da UI
   (o mesmo levantamento do §5 do plano do PS2: o que volta em todo merge).
2. **Catálogo de ROMs de Switch** (lista, capa, busca), lido de um manifest empacotado no APK, como no PS2.
3. **Download sob demanda + fila**, e a lista de **jogos baixados** ligada à biblioteca do Eden: o jogo baixado
   aparece e abre direto no emulador.
4. Depois: telemetria `/logErr` e atualização in-app pelo nosso canal (§4.2 e §4.3 do plano do PS2).

## Fatos que a sessão nova vai precisar

- **Manifest de Switch já existe:** 162 linhas `switch/…` em `C:\projects\catalog_manifests\catalog_manifest_full.txt`
  (e em `catalog_manifest.txt` da mesma pasta). Formato do Lemuroid: `system/arquivo|título|capa|popularidade|…`.
  **Os arquivos são `.nsp.rar`** (ex.: `switch/ARMS [01009B500007C000][v0][US].nsp.rar`). O app tem de extrair RAR
  depois do download, ou o catálogo tem de apontar para `.nsp`/`.xci` sem compressão. É decisão de produto.
  O Lemuroid **não** tem linhas de Switch no seu manifest.
- **Chaves:** jogo comercial não abre sem `prod.keys` do usuário. Medido no G86: o importador do Eden
  (Configurações ▸ Administrar Dados do Eden ▸ Instalar a chave prod.keys) **só funciona navegando até a pasta** no
  seletor, não pela busca, e só com a pasta `files/keys` criada pelo próprio Eden. Uma pasta criada por `adb push` fica
  com dono `shell`, e a importação falha com "Uma ou mais chaves falharam ao serem copiadas". Se apagar a pasta, o
  Eden só a recria ao reiniciar. Como o produto entrega ou orienta as chaves é **decisão do dono** e tem implicação
  legal: não decidir sozinho.
- **Firmware:** o LIMBO abriu sem firmware, nos dois emuladores. Outros jogos podem pedir.
- **Aparelho de teste:** Moto G86 (`ZY32LMNN9B`), compartilhado com o uso pessoal do dono e com outros projetos.
  Antes de cada toque, conferir `dumpsys window | grep mCurrentFocus`. O auto-rotate está ligado, então as
  coordenadas mudam no meio. Com `uiautomator dump`, ler só com o app de teste em primeiro plano e apagar o dump no
  aparelho logo depois (um dump já capturou uma conversa de WhatsApp do dono). O Eden v0.2.1 oficial está instalado
  no G86 (`dev.eden.eden_emulator`), com as chaves, e o LIMBO está em `/sdcard/Download/eden_teste`.
- **Ainda não provado:** jogo de **64 bits**. O LIMBO é de 32 bits (JIT A32). Com jogo de 64 bits, o Eden usa NCE
  (execução nativa), e o core suyu abortava no patcher NCE com homebrew. Testar um 64 bits no Eden logo no começo.
- **4 GB de RAM provavelmente fica de fora:** o Eden usou 1,7 GB de PSS num jogo 2D leve.

## Decisões que são do dono (perguntar antes de codificar)

1. Nome do app, `applicationId` e ícone. Diferente do Eden (`dev.eden.eden_emulator`), para os dois coexistirem.
2. Onde mora o repositório (o PS2 está em `D:\projects\play2\`) e qual remote GitHub. O fork é GPLv3, e o upstream
   foi derrubado do GitHub por DMCA: hospedar lá tem o mesmo risco.
3. Seguir o `master` do Eden ou a última tag (`v0.2.1`). O PS2 partiu do `upstream/master` de uma data.
4. Chaves e firmware: como o usuário os obtém.
5. `.nsp.rar` (extrair no aparelho) ou republicar os arquivos sem compressão.

## Limpeza no Lemuroid (independente do projeto novo)

- A worktree `C:\projects\lemuroid\Lemuroid-switch` (branch `spike-switch`, **sem commit**) e o
  `libsuyu_libretro_android.so` não versionado em `lemuroid-cores/bundled-cores/src/main/jniLibs/arm64-v8a/`
  dessa worktree são só do spike. Apagar quando o dono confirmar que o Switch não volta para o Lemuroid.
- No G86, o app de debug do Lemuroid tem as chaves em `files/system/suyu/keys/` e o `LIMBO.nsp` em
  `files/roms/switch/`.

## Frase de abertura da sessão nova

> Leia `C:\projects\lemuroid\Lemuroid\docs\backlogs\arquivados\2026-10-29-app-switch-fork-eden.md` inteiro e, em seguida, o
> `CLAUDE.md` e o `docs/plano-fork-sobre-upstream.md` do `D:\projects\play2\ARMSX2-forkv2`. Faça só a etapa analítica:
> clone do Eden (`git.eden-emu.dev`), build do APK sem mudanças, levantamento do que reimplementar (catálogo, download,
> jogos baixados) e da marca a remover. Grave o plano no repositório novo e me traga as 5 decisões da seção
> "Decisões que são do dono" antes de escrever código.
