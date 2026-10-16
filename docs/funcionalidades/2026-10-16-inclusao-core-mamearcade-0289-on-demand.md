# [FEATURE] Inclusão do Core MAME 0.289 (mamearcade) Dinâmico On-Demand

**Data:** 2026-10-16  
**Status:** Implementada e Validada  
**Branch:** version10  
**Backlog:** [`docs/backlogs/2026-10-16-inclusao-core-mamearcade-0289-on-demand.md`](../backlogs/2026-10-16-inclusao-core-mamearcade-0289-on-demand.md)

---

## 1. O que foi feito

Integração do emulador **MAME Current (v0.289, core `mamearcade`)** ao ecossistema do Lemuroid, permitindo paridade com o acervo Arcade do RetroBatNew (`mame289`) e dataset Hugging Face `luisluis123/mame289`.

Devido ao tamanho expressivo do binário (256,9 MB descompactado por ABI, ~1,02 GB no total das 4 arquiteturas), o core foi implementado como **Dynamic Feature Module on-demand** (`:lemuroid_core_mamearcade`), garantindo que o APK base permaneça leve e o download ocorra apenas quando o usuário selecionar um título do sistema. O core legado `mame2003_plus` foi mantido 100% intacto para preservar a compatibilidade com hardware modesto.

---

## 2. Detalhes de Implementação

### 2.1. LibretroCores e Módulo Dinâmico
- **Módulo Gradle:** Criado `:lemuroid_core_mamearcade` com plugin `com.android.dynamic-feature`.
- **AndroidManifest.xml:** Declarado com `<dist:delivery><dist:on-demand /></dist:delivery>` e `<dist:fusing dist:include="true" />`.
- **Binários:** Baixados do Libretro Buildbot oficial para 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) e nomeados com o prefixo obrigatório `libmamearcade_libretro_android.so`.
- **Espelhamento:** Configurado tanto em `Lemuroid/lemuroid-cores` quanto em `LibretroCores`.

### 2.2. Sistema e Lógica de Emulação (`retrograde-app-shared`)
- **`CoreID.kt`:** Adicionado `CoreID.MAMEARCADE("mamearcade", "MAME 0.289 (Arcade)", "libmamearcade_libretro_android.so")`.
- **`SystemID.kt`:** Adicionado `SystemID.MAME289("mame289")`.
- **`MetaSystemID.kt`:** Adicionado enum `MAME289` e mapeamento `SystemID.MAME289 -> MAME289`.
- **`HeavySystemFilter.kt`:** `SystemID.MAME289` classificado como `DeviceTier.VERY_HEAVY` (desabilitado em dispositivos < 3 GB RAM).
- **`GameSystem.kt`:** Configuração do sistema com extensões suportadas `zip` e `7z`, estados desabilitados (`statesSupported = false`), controles Arcade de 4 e 6 botões (`ControllerConfigs.MAME_2003_4` e `MAME_2003_6`).
- **`SystemPalette.kt`:** Paleta visual configurada com a cor terracota `#8B2500`.
- **`ShaderChooser.kt`:** CRT configurado como shader padrão e `upscale32Bits = true`.
- **`SystemLogoResolver.kt`:** Mapeamento para `R.drawable.game_system_mame289_hover`.
- **Logos:** `game_system_mame289.png` e `game_system_mame289_hover.png` adicionados em `res/drawable/`.
- **Strings:** Definidas em `strings-game-system.xml` (`game_system_title_arcade_mame289` = "MAME 0.289", `game_system_abbr_arcade_mame289` = "MAME") e `core_names.xml` (`core_name_mamearcade` = "mamearcade").

### 2.3. Catálogo e Resolução de Downloads
- **Mapeamento de Mnemônico:** Adicionado `"mame289": "mame289"` em `lemuroid-app/src/main/assets/mnemonico_map.json`.
- **Endpoint:** `find_by_file` mapeia perfeitamente para `https://huggingface.co/datasets/luisluis123/mame289/resolve/main/<rom>.zip`.
- **Manifesto de ROMs:** Extraídos 14.357 títulos de ROMs não-CHD do `gamelist.xml` do RetroBatNew (663 jogos baseados em CHD foram excluídos para garantir compatibilidade móvel).
- **Schema Version:** `MANIFEST_SCHEMA_VERSION` incrementado de 39 para 40 em `ManifestQuickLoader.kt`.
- **Total de títulos no catálogo:** 75.229 entradas sincronizadas em `catalog_manifest.txt` e no asset de produção.

---

## 3. Validação

1. **Compilação Kotlin:**
   - `:retrograde-app-shared:compileDebugKotlin` — SUCESSO.
   - `:lemuroid-touchinput:compileDebugKotlin` — SUCESSO.
   - `:lemuroid-app:compileFreeBundleDebugKotlin` — SUCESSO.
2. **Geração do Banco Pré-construído e Montagem do APK:**
   - `:lemuroid-app:assembleFreeBundleDebug` executado com sucesso, validando todas as 75.229 entradas do banco Room sem erros de chave estrangeira ou formatação.
   - Presença do binário `libmamearcade_libretro_android.so` confirmada na pasta `lib/` das ABIs.
