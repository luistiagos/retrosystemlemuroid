# [BUG] MAME / Arcade split sets: ausência de BIOS/parent apagava ROM por falso positivo de corrupção

**Data:** 2026-10-16  
**Status:** CORRIGIDO  
**Severidade:** ALTA — Apagava ROMs válidas de arcade (ex: 18 Wheeler de 102 MB) alegando corrupção e impedia a execução de clones/jogos split (Naomi, CPS, ZN-2, etc.).  
**Branch:** version10  

---

## Sintoma

Ao abrir jogos de arcade que utilizam coleções split (como `18wheelr` do MAME 0.289 ou `sfex2`), o emulador falhava logo no boot (`Fatal error: Required files are missing, the machine cannot be run` ou `NOT FOUND (tried in 18wheelr naomi)`).  
O aplicativo exibia a mensagem de erro:
> *"O arquivo ROM parece estar corrompido e foi removido. Toque no jogo novamente para baixá-lo de novo."*

E **apagava o arquivo .zip da ROM do armazenamento**, mesmo o arquivo estando 100% íntegro.

## Causa-Raiz

1. **Sets Split de Arcade:**  
   O acervo de ROMs de arcade do MAME 0.289 e outros emuladores é do tipo *split*: a ROM do jogo (`18wheelr.zip`, 102 MB) traz apenas o conteúdo do título e depende do BIOS da placa mãe (`naomi.zip`, 10.4 MB) e de microcontroladores/dispositivos de hardware (`mie.zip`, 725 B; `jvs13551.zip`, 15 KB) para inicializar a máquina.
2. **Dependências Não Baixadas:**  
   O catálogo de jogos lista apenas os títulos jogáveis. Arquivos de BIOS e dispositivos não constam como jogos jogáveis no catálogo, portanto nunca eram provisionados.
3. **Falso Positivo Destrutivo:**  
   Em [GameLaunchTaskHandler.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/main/GameLaunchTaskHandler.kt), quando o core reportava `isRomLoadFailure`, o código verificava se havia BIOS de sistema tradicional faltando (`GameSystem.requiredBIOSFiles`). Para sistemas de arcade (MAME/FBNeo), essa lista é vazia (pois as dependências variam por placa arcade). O código caía cegamente na exclusão do arquivo (`romOnDemandManager.deleteRom(game)`) sem validar se o arquivo `.zip` estava realmente corrompido.

## Solução Implementada (Opção B - Port RetroBatNew)

Portamos a arquitetura comprovada de resolução de dependências de arcade do RetroBatNew:

### 1. `arcade-clone-parents.json`
- Empacotado em `retrograde-app-shared/src/main/assets/arcade-clone-parents.json` contendo mais de 7.200 mapeamentos de clones, pais, BIOS e chips de hardware para sistemas de arcade (`mame289`, `mame`, `fbneo`, `arcade`, `neogeo`, `zinc`, etc.).
- Inclui declaração `bios_dataset_base` apontando para o dataset dedicado `https://huggingface.co/datasets/luisluis123/mame289bios/resolve/main`.

### 2. `ArcadeCloneParents.kt`
- Localizado em [ArcadeCloneParents.kt](../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/ArcadeCloneParents.kt).
- Implementa `resolveAncestors(context, systemId, romName)` com resolução em profundidade de até 25 níveis (resolvendo toda a cadeia de pais, BIOS e hardware sem loops).
- Totalmente baseado em Kotlin puro (`kotlinx.serialization.json`), sendo 100% testável em JVM e Android.

### 3. Auto-Download On-Demand em `RomOnDemandManager.kt`
- Métodos `ensureArcadeDependencies(game)` e `getMissingArcadeDependencies(game)` em [RomOnDemandManager.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/RomOnDemandManager.kt).
- Integrado tanto no fluxo de download (`downloadRom`) quanto no pré-lançamento do jogo (`prepareGameForLaunch`).
- Prioridade de download:
  1. Base dedicada de BIOS (`luisluis123/mame289bios`) quando configurada para o sistema.
  2. Fallback para o endpoint de catálogo `find_by_file` (`pythonanywhere`).
- Sincroniza automaticamente a presença do arquivo tanto na pasta da ROM (`files/roms/<system>/`) quanto no diretório de sistema (`files/system/`), garantindo que o Libretro core encontre os arquivos em qualquer rompath.

### 4. Blindagem Contra Exclusão em `GameLaunchTaskHandler.kt`
- Verifica `getMissingArcadeDependencies(game)`. Se algum arquivo de arcade estiver ausente, avisa o usuário sem apagar a ROM.
- Validação real de integridade: `isRomFileActuallyCorrupted(game)` tenta abrir a tabela de entradas do arquivo ZIP (`ZipFile(file)`). Se o ZIP abrir e possuir entradas válidas, **o arquivo NUNCA é apagado**.

## Validação

- Testes unitários em [ArcadeCloneParentsTest.kt](../../lemuroid-app/src/test/java/com/swordfish/lemuroid/app/shared/roms/ArcadeCloneParentsTest.kt) cobrindo:
  - Resolução de `18wheelr` -> `["naomi", "mie", "jvs13551"]`.
  - Resolução de `sfex2` -> `["coh3002c", "qsound"]`.
  - Reconhecimento de sistemas arcade vs consoles (SNES, etc.).
- Compilação do app (`assembleFreeBundleDebug`): **BUILD SUCCESSFUL**.
