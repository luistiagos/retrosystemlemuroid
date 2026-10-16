# [BACKLOG] Inclusão do Core MAME 0.289 (mamearcade) Dinâmico On-Demand

**Data:** 2026-10-16  
**Origem:** Demanda de paridade de acervo e emulação com o RetroBatNew (`mame289`)  
**Tipo:** Feature / Novo Core e Sistema  
**Status:** Implementado  
**Prioridade:** Média-Alta  
**Complexidade:** Média-Alta (módulo Gradle dinâmico, binários de 257 MB, catálogo de ROMs e dependências de arcade)

---

## 1. Contexto e Motivação

No ecossistema de acervos compartilhados entre os projetos (**RetroBatNew**, **Lemuroid**, **romsrepository**):
- O **RetroBatNew** padronizou sua emulação de Arcade moderno na versão **MAME 0.289** (`mame289` no EmulationStation, core `mame_libretro.dll` e emulador `mame64.exe`). Esse ambiente conta com mais de **14.339 títulos** catalogados no dataset `luisluis123/mame289` e 372 sets de BIOS/dispositivos em `luisluis123/mame289bios`.
- O **Lemuroid** atualmente possui apenas o core legado **MAME 2003-Plus** (`libmame2003_plus_libretro_android.so`, baseado no MAME 0.78 de 2003) e o **FBNeo** (`libfbneo_libretro_android.so`). O catálogo atual sob o mnemônico `mame2003Plus` possui apenas 942 jogos.
- **Decisão arquitetural:** Adicionar o MAME Current (**0.289**) como um **novo core e sistema dinâmico sob demanda (`on-demand`)**, mantendo o `mame2003_plus` intacto para preservar a compatibilidade com dispositivos mais modestos e TV Boxes fracas.

---

## 2. Identificação Técnica do Core

O buildbot oficial do Libretro compila o MAME atual para Android sob o sub-alvo arcade:
- **Nome no Libretro:** `mamearcade`
- **Versão confirmada no binário:** `0.289 (5f5a0774)`
- **Origem dos binários:** `https://buildbot.libretro.com/nightly/android/latest/<abi>/mamearcade_libretro_android.so.zip`
- **Nome final do arquivo .so no Lemuroid:** `libmamearcade_libretro_android.so` *(obrigatório prefixo `lib` para extração no Android)*.

### Métricas de Peso e Performance

| Métrica | MAME 2003-Plus (Atual) | MAME 0.289 (`mamearcade`) |
|---|---|---|
| **Tamanho descomprimido (`arm64-v8a`)** | 38,9 MB | **256,9 MB** |
| **Tamanho comprimido (zip/apk)** | ~12 MB | **61,1 MB** |
| **Soma de todas as 4 ABIs** | ~150 MB | **~1,02 GB** (descomprimido) |
| **Tier de Hardware (`HeavySystemFilter`)** | `LIGHT` | **`VERY_HEAVY`** |
| **Requisito mínimo de CPU recomendada** | Quad-core A53 (qualquer TV Box) | Octa-core A76/A77+ (Snapdragon 7xx/8xx, Dimensity) |

> ⚠️ **Atenção ao Tamanho:** Por ter 256,9 MB descompactado, este core **NÃO PODE** ser incluído como `bundled-core` padrão em APKs universais sem ABI split. Ele deve ser entregue estritamente via **Dynamic Delivery on-demand** na Play Store e tratado com cautela em builds offline.

---

## 3. Os 3 Pilares Arquiteturais da Feature

### Pilar A: Dynamic Feature Module On-Demand
Criar o módulo `:lemuroid_core_mamearcade` no projeto [LibretroCores](file:///c:/projects/lemuroid/LibretroCores) e incluí-lo em [Lemuroid](file:///c:/projects/lemuroid/Lemuroid).  
O `AndroidManifest.xml` do módulo deve declarar `<dist:on-demand />` para que o download de ~61 MB seja disparado pela Google Play apenas quando o usuário selecionar um jogo desse sistema pela primeira vez.

### Pilar B: Gestão de Dependências de Arcade (ROMs Split, Clones e BIOS)
Diferente de consoles de cartucho (onde 1 arquivo `.nes` ou `.sfc` roda sozinho), o romset do MAME 0.289 é **split**:
1. **ROMs Clones:** 2.994 dos 14.339 jogos dependem do `.zip` pai (ex: `1944j.zip` precisa de `1944.zip` na mesma pasta).
2. **BIOS e Dispositivos:** Jogos necessitam de arquivos de suporte (ex: `neogeo.zip`, `qsound.zip`, `stvbios.zip`, `coh3002c.zip`).
3. **Solução no Lemuroid:**
   - **Opção 1 (Recomendada inicial):** No catálogo mobile inicial do Lemuroid, selecionar títulos prioritários **non-merged** ou **parents autônomos**, e incluir as BIOS principais no `BiosManager.kt` apontando para o dataset `luisluis123/mame289bios`.
   - **Opção 2 (Evolução futura):** Portar para o `GameLoader.kt` do Lemuroid a tabela `arcade-clone-parents.json` do RetroBatNew, garantindo download transparente do parent antes de abrir a sessão de jogo.

### Pilar C: Coexistência de Sistemas e Catálogo
- Identificador de sistema: `MAME289` (`dbname = "mame289"`).
- O `MAME2003PLUS` continua existindo sem alterações.
- O novo sistema aponta para o acervo MAME 0.289 do Hugging Face (`https://huggingface.co/datasets/luisluis123/mame289`).

---

## 4. Plano Detalhado de Implementação (Checklist de Arquivos)

Seguindo o guia arquitetural de [`docs/prompts/adicionar-novo-sistema-libretro.md`](../prompts/adicionar-novo-sistema-libretro.md):

### Fase 1: LibretroCores e Obtenção dos Binários
- [x] **1.1. Atualizar script de download:**
  - Em `update_cores.ipy`, adicionar `"mamearcade"` à lista de cores.
  - Script baixa de `https://buildbot.libretro.com/nightly/android/latest/<abi>/mamearcade_libretro_android.so.zip`.
  - Renomear para `libmamearcade_libretro_android.so` em todas as ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`).
- [x] **1.2. Criar Módulo Gradle no LibretroCores:**
  - Criar pasta `lemuroid_core_mamearcade/`.
  - `build.gradle.kts`:
    ```kotlin
    plugins {
        id("com.android.dynamic-feature")
        id("kotlin-android")
        id("kotlin-kapt")
    }
    android {
        namespace = "com.swordfish.lemuroid.core.mamearcade"
        defaultConfig {
            missingDimensionStrategy("opensource", "play")
            missingDimensionStrategy("cores", "dynamic")
        }
        packagingOptions {
            doNotStrip("*/*/*_libretro_android.so")
        }
    }
    dependencies {
        implementation(project(":lemuroid-app"))
        implementation(kotlin(deps.libs.kotlin.stdlib))
    }
    ```
  - `src/main/AndroidManifest.xml`:
    ```xml
    <manifest xmlns:dist="http://schemas.android.com/apk/distribution"
        xmlns:android="http://schemas.android.com/apk/res/android">
        <application
            android:hasCode="false"
            android:extractNativeLibs="true" />
        <dist:module dist:title="@string/core_name_mamearcade">
            <dist:delivery>
                <dist:on-demand />
            </dist:delivery>
            <dist:fusing dist:include="true" />
        </dist:module>
    </manifest>
    ```

### Fase 2: Configuração do Core e Sistema no Lemuroid (retrograde-app-shared)
- [x] **2.1. `CoreID.kt`:**
  - Adicionar:
    ```kotlin
    MAMEARCADE(
        "mamearcade",
        "MAME 0.289 (Arcade)",
        "libmamearcade_libretro_android.so",
    ),
    ```
- [x] **2.2. `SystemID.kt`:**
  - Adicionar:
    ```kotlin
    MAME289("mame289"),
    ```
- [x] **2.3. `MetaSystemID.kt`:**
  - Adicionar entrada na enum:
    ```kotlin
    MAME289(
        R.string.game_system_title_arcade_mame289,
        R.drawable.game_system_mame289,
        listOf(SystemID.MAME289),
    ),
    ```
  - Mapear no `fromSystemID`:
    ```kotlin
    SystemID.MAME289 -> MAME289
    ```
- [x] **2.4. `HeavySystemFilter.kt`:**
  - Classificar `SystemID.MAME289` no tier `DeviceTier.VERY_HEAVY` para alertar usuários em dispositivos fracos e desabilitar por padrão em hardware com menos de 3 GB de RAM.
- [x] **2.5. `ControllerConfigs.kt`:**
  - Definir configurações de 4 e 6 botões de arcade (reaproveitando o padrão `MAME_2003_4` e `MAME_2003_6` ou ajustando mapeamento libretro padrão do MAME Current).
- [x] **2.6. `GameSystem.kt`:**
  - Adicionar a definição completa em `SYSTEMS`:
    ```kotlin
    GameSystem(
        SystemID.MAME289,
        "MAME 0.289",
        R.string.game_system_title_arcade_mame289,
        R.string.game_system_abbr_arcade_mame289,
        listOf(
            SystemCoreConfig(
                CoreID.MAMEARCADE,
                statesSupported = false, // MAME atual tem estados instáveis dependendo do driver
                controllerConfigs = hashMapOf(
                    0 to arrayListOf(
                        ControllerConfigs.MAME_2003_4,
                        ControllerConfigs.MAME_2003_6,
                    ),
                ),
            ),
        ),
        uniqueExtensions = listOf(),
        supportedExtensions = listOf("zip", "7z"),
        scanOptions = ScanOptions(
            scanByFilename = false,
            scanByUniqueExtension = false,
            scanByPathAndFilename = true,
            scanByPathAndSupportedExtensions = false,
        ),
    ),
    ```

### Fase 3: Camada Visual, Controles de Toque e Strings
- [x] **3.1. Recursos Visuais (Logos):**
  - Adicionar `game_system_mame289.png` (277x192) e `game_system_mame289_hover.png` (333x230) em `retrograde-app-shared/src/main/res/drawable/`.
  - Mapear em `SystemLogoResolver.kt`:
    ```kotlin
    MetaSystemID.MAME289 -> R.drawable.game_system_mame289_hover
    ```
- [x] **3.2. Strings:**
  - Em `strings-game-system.xml`:
    - `game_system_title_arcade_mame289`: `"MAME 0.289"`
    - `game_system_abbr_arcade_mame289`: `"MAME"`
  - Em `core_names.xml`:
    - `core_name_mamearcade`: `"mamearcade"`
- [x] **3.3. ShaderChooser:**
  - Em `ShaderChooser.kt`, associar `SystemID.MAME289` ao grupo CRT / Arcade.
- [x] **3.4. Touch Controls:**
  - Em `TouchControllerID.kt`, apontar para o layout de arcade padrão de 6 botões (`ControllerConfigs.MAME_2003_6`).

### Fase 4: Integração de Build no Lemuroid App
- [x] **4.1. `settings.gradle.kts`:**
  - Adicionar `:lemuroid_core_mamearcade` e vincular ao diretório do LibretroCores.
- [x] **4.2. `lemuroid-app/build.gradle.kts`:**
  - Adicionar `:lemuroid_core_mamearcade` em `dynamicFeatures`.
- [x] **4.3. `mnemonico_map.json`:**
  - Adicionar: `"mame289": "mame289"`.

### Fase 5: Catálogo de Jogos e Manifestos
- [x] **5.1. Seleção e Curadoria de ROMs:**
  - Importar títulos testados do acervo `mame289` do RetroBatNew.
  - Priorizar títulos stand-alone / parent no catálogo do Lemuroid.
- [x] **5.2. `catalog_manifest.txt`:**
  - Adicionar entradas `mame289/<rom>.zip|<Nome>|<CoverURL>|0|1`.
  - Espelhar o manifesto entre `lemuroid-app/src/main/assets/catalog_manifest.txt` e a raiz do repositório.
- [x] **5.3. Bump de Versão:**
  - Incrementar `MANIFEST_SCHEMA_VERSION` em `ManifestQuickLoader.kt` para invalidar caches antigos no aparelho do usuário.

---

## 5. Critérios de Aceite e Validação em Device

1. **Compilação e Empacotamento:**
   - Projeto compila limpo via `./gradlew assemblePlayDebug` e `./gradlew bundlePlayRelease`.
   - `verifyBundledCores` valida que o binário foi nomeado com prefixo `lib` (`libmamearcade_libretro_android.so`).
2. **Download Dinâmico On-Demand:**
   - Ao iniciar o app do zero, o tamanho do APK base permanece enxuto.
   - Ao clicar em um jogo do MAME 0.289 pela primeira vez, o Play Core dispara o download do módulo de ~61 MB com barra de progresso.
3. **Execução de Jogo:**
   - Jogo clássico (ex: *Pac-Man* / `puckman`, *Street Fighter II* / `sf2`) inicializa normalmente a 60 FPS com áudio sincronizado.
   - Controles de toque na tela (6 botões) e gamepad Bluetooth respondem sem latência.
4. **Preservação de Dados:**
   - O sistema existente `mame2003plus` continua intacto, sem perda de saves ou jogos favoritados.
