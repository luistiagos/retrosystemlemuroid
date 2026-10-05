# Prompt: Adicionar um novo sistema (com core Libretro) ao Lemuroid

> **Escopo deste prompt:** adicionar um novo sistema cuja emulação é fornecida por um **core Libretro existente** (arquivos `.so` já compilados para Android). Sistemas sem core Libretro disponível **estão fora do escopo** — não tente portar emuladores nativos.
>
> **Modelo executor:** este prompt foi escrito para ser executado por modelos menores (Haiku, modelos open-source, etc.). Por isso é exaustivo, com exemplos prontos de copiar/adaptar. Siga **na ordem dada** e **não pule passos**. Se algum passo falhar, pare e reporte o erro — não invente soluções.

---

## 0. Entradas que você precisa coletar do usuário antes de começar

### 0.0. Verificação obrigatória — o sistema já existe?

Antes de tudo, **abra `retrograde-app-shared/src/main/java/.../SystemID.kt`** e confirme que o `<dbname>` desejado **NÃO** está na lista. Este projeto já tem **~80+ sistemas** implementados — sugerir um já existente é um erro frequente.

Sistemas já presentes — **lista completa de `SystemID.kt`** (78 sistemas, atualizado jun/2026):

| Categoria | `SystemID` → `dbname` |
|-----------|----------------------|
| **Nintendo** | `NES`→`nes`, `SNES`→`snes`, `GB`→`gb`, `GBC`→`gbc`, `GBA`→`gba`, `N64`→`n64`, `FDS`→`fds`, `VIRTUAL_BOY`→`vb`, `NDS`→`nds`, `NINTENDO_3DS`→`3ds`, `GAMECUBE`→`gc`, `POKEMON_MINI`→`pokemini`, `GAME_WATCH`→`gw` |
| **Sega** | `GENESIS`→`md`, `SMS`→`sms`, `GG`→`gg`, `SEGACD`→`scd`, `SEGA_32X`→`sega32x`, `SATURN`→`saturn`, `DREAMCAST`→`dc`, `SG_1000`→`sg1000`, `SC_3000`→`sc3000` |
| **Sony** | `PSX`→`psx`, `PSP`→`psp` |
| **Atari** | `ATARI2600`→`atari2600`, `ATARI5200`→`atari5200`, `ATARI7800`→`atari7800`, `ATARI800`→`atari800`, `LYNX`→`lynx`, `JAGUAR`→`jaguar` |
| **NEC** | `PC_ENGINE`→`pce`, `PC_ENGINE_CD`→`pcecd`, `PCFX`→`pcfx` |
| **SNK** | `NGP`→`ngp`, `NGC`→`ngc`, `NEOGEO`→`neogeo`, `NEOCD`→`neocd` |
| **Commodore / Amiga** | `COMMODORE_64`→`c64`, `AMIGA`→`amiga`, `AMIGA_1200`→`amiga1200`, `AMIGA_CD32`→`amigacd32`, `AMIGA_CDTV`→`amigacdtv` |
| **Computadores** | `MSX`→`msx`, `MSX2`→`msx2`, `ZX_SPECTRUM`→`zxspectrum`, `AMSTRAD_CPC`→`amstradcpc`, `AMSTRAD_GX4000`→`gx4000`, `DOS`→`dos` |
| **Outros consoles** | `THREE_DO`→`3do`, `ODYSSEY2`→`odyssey2`, `COLECOVISION`→`coleco`, `INTELLIVISION`→`intellivision`, `VECTREX`→`vectrex`, `SUPERVISION`→`supervision`, `MEGADUCK`→`megaduck`, `CHANNEL_F`→`channelf` |
| **Portáteis** | `WS`→`ws`, `WSC`→`wsc` |
| **Arcade** | `FBNEO`→`fbneo`, `MAME2003PLUS`→`mame2003plus`, `CPS1`→`cps1`, `CPS2`→`cps2`, `CPS3`→`cps3`, `DATAEAST`→`dataeast`, `GALAXIAN`→`galaxian`, `TOAPLAN`→`toaplan`, `TAITO`→`taito`, `PSIKYO`→`psikyo`, `PGM`→`pgm`, `KANEKO`→`kaneko`, `CAVE`→`cave`, `TECHNOS`→`technos`, `SETA`→`seta` |
| **Fantasy / Indie** | `PICO_8`→`pico8`, `VIRCON32`→`vircon32`, `LOWRES_NX`→`lowresnx`, `ARDUBOY`→`arduboy`, `UZEBOX`→`uzebox` |

**Só continue depois de confirmar que o sistema NÃO está em `SystemID.kt`.**

---

### 0.1. Variáveis necessárias

Pergunte ao usuário (ou receba via prompt do task) e confirme **antes de tocar em qualquer arquivo**:

| Variável | Exemplo | Onde será usado |
|----------|---------|-----------------|
| `<NomeFriendly>` | "Sega Saturn" | Strings de UI, comentários |
| `<SystemIdEnum>` | `SATURN` | Nome em `SCREAMING_SNAKE_CASE` na enum `SystemID` |
| `<dbname>` | `saturn` | Identificador único, minúsculas, sem espaços, usado no DB e como pasta no catálogo |
| `<CoreIdEnum>` | `BEETLE_SATURN` | Nome do core na enum `CoreID` |
| `<coreName>` | `mednafen_saturn` | Nome curto do core (igual ao `core_option_NAMESPACE` que o core usa) |
| `<coreDisplayName>` | "Beetle Saturn" | Nome legível do core |
| `<libretroFileName>` | `libmednafen_saturn_libretro_android.so` | Arquivo `.so` do core. **Sempre `lib<coreName>_libretro_android.so`**, mesmo quando o buildbot entrega o arquivo sem o prefixo `lib` (Opera, PicoDrive, Dolphin…). Nesse caso, **renomeie** o `.so` ao copiar. Ver o aviso no Passo 1. |
| `<libretroFullName>` | "Sega - Saturn" | Nome longo (como usado no LibretroDB) |
| `<extensoes>` | `["cue", "iso", "chd"]` | Extensões de ROM suportadas |
| `<biosFiles>` | `["sega_101.bin", "mpr-17933.bin"]` | Lista de BIOS obrigatórias (vazio se o core não precisar). Cada item é o **caminho relativo** dentro de `system/` (ex.: `"saturn/sega_101.bin"` se for em subpasta) |
| `<libretroControllerDescriptor>` | "JoyPad" | **CRÍTICO:** o descritor exato que o core registra via `RETRO_ENVIRONMENT_SET_CONTROLLER_INFO`. Veja [Passo 7 — descobrir o descriptor](#passo-7-controllerconfigskt). |
| `<deviceTier>` | `MODERATE` ou `VERY_HEAVY` ou `LIGHT` | Quão pesado o core é. Veja [Passo 4](#passo-4-heavysystemfilterkt). |

Se algum desses valores estiver faltando ou ambíguo, **pergunte ao usuário antes de continuar**.

---

## 1. Pré-requisitos físicos (arquivos `.so`)

Antes de tocar em qualquer código, confirme que você tem o `.so` do core para **as 4 ABIs do Android**:

```
arm64-v8a/<libretroFileName>     <-- ARM 64 bits (TVBox/Smartphone moderno)
armeabi-v7a/<libretroFileName>   <-- ARM 32 bits (TVBox antigo, smartphone barato)
x86_64/<libretroFileName>        <-- emulador Android
x86/<libretroFileName>           <-- emulador Android antigo
```

Onde obter: builds oficiais do Libretro em `https://buildbot.libretro.com/nightly/android/latest/<abi>/` ou compile pelo source do core.

Se faltar alguma ABI, **pare e peça ao usuário** — apps com ABIs faltantes ficam quebrados em devices dessa ABI.

Verifique que cada `.so` é um ELF válido (não um stub vazio):

```bash
file <path>/<libretroFileName>
# Esperado: "ELF 64-bit LSB shared object, ARM aarch64" (ou aarch64/x86_64 conforme ABI)
# Se vier "ASCII text" ou tamanho < 10KB, o arquivo é um stub e PRECISA ser substituído.
```

> ⚠️ **O nome do `.so` TEM que começar com `lib`.** O instalador do Android só extrai de
> `lib/<abi>/` para o `nativeLibraryDir` os arquivos `lib*.so`; o resto fica preso dentro do
> APK, onde o `GameLoader` não procura. O jogo cai no download do core e, **sem rede, não abre**.
> No Android 13+ esse filtro é pulado para APK *debuggable*, então **o build debug funciona e
> esconde o problema**. Só o release mostra.
>
> O buildbot entrega vários cores sem o prefixo (`opera`, `picodrive`, `atari800`, `sameduck`,
> `freechaf`, `uzem`, `lowresnx`, `arduous`, `dolphin`, `yabasanshiro`, `virtualjaguar`, `o2em`,
> `neocd`, `puae`, `mednafen_pcfx`, `gw`, `hatari`). **Renomeie** para `lib<nome>_libretro_android.so`
> ao copiar. Até 2026-09 este guia mandava copiar o nome literal, e 17 cores foram para produção
> sem ser extraídos ([[2026-09-25-cores-sem-prefixo-lib-nao-extraidos-no-release]]).
>
> A task `verifyBundledCores` derruba o build se algum `.so` em `jniLibs` não começar com `lib`,
> ou se `CoreID.kt` e `bundled-cores/src/main/jniLibs/arm64-v8a/` discordarem nos nomes.

---

## 2. Visão geral dos arquivos que serão editados

Você vai editar/criar **estes arquivos** (na ordem). Não pule nenhum:

| # | Arquivo | Ação |
|---|---------|------|
| 1 | `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/CoreID.kt` | Adicionar entrada na enum |
| 2 | `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/SystemID.kt` | Adicionar entrada na enum |
| 3 | `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/MetaSystemID.kt` | Adicionar entrada + branch em `fromSystemID()` |
| 4 | `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/HeavySystemFilter.kt` | (opcional) Adicionar ao tier correto |
| 5 | `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/bios/BiosManager.kt` | (se requer BIOS) Adicionar entradas em `SUPPORTED_BIOS` |
| 6 | `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/ControllerConfigs.kt` | Adicionar `val SYSTEM = ControllerConfig(...)` |
| 7 | `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/GameSystem.kt` | Adicionar `GameSystem(...)` ao `SYSTEMS` |
| 8 | `lemuroid-touchinput/src/main/java/com/swordfish/touchinput/radial/settings/TouchControllerID.kt` | Adicionar enum + entrada no `getConfig()` |
| 9 | `lemuroid-touchinput/src/main/java/com/swordfish/touchinput/radial/layouts/<NomeSistema>.kt` | **Criar** arquivo de layout do touch controller |
| 10 | `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/ShaderChooser.kt` | Adicionar branch em **2 funções** |
| 11 | `retrograde-app-shared/src/main/res/values/strings.xml` | Strings de settings expostos |
| 12 | `retrograde-app-shared/src/main/res/values/strings-game-system.xml` | `game_system_title_X` + `game_system_abbr_X` |
| 13 | `retrograde-app-shared/src/main/res/drawable/game_system_<dbname>.png` e `_hover.png` | **Copiar** logos normal (277x192) e hover (333x230) de `logos_extracted/all-systems/` |
| 13b | `retrograde-app-shared/src/main/java/.../SystemLogoResolver.kt` | Adicionar mapeamento do hover em `resolve()` |
| 14 | `lemuroid-touchinput/src/main/res/drawable/<prefix>_button_<n>.xml` | (se face buttons custom) Criar drawables das letras dos botões |
| 15 | `lemuroid-app/src/main/res/values/core_names.xml` | Nome do core para dynamic feature |
| 16 | `lemuroid-app/src/main/assets/mnemonico_map.json` | Adicionar entrada `"<dbname>": "<dbname>"` |
| 17 | `settings.gradle.kts` | Adicionar `:lemuroid_core_<coreName>` ao include + projectDir |
| 18 | `lemuroid-app/build.gradle.kts` | Adicionar à lista `dynamicFeatures` |
| 19 | `lemuroid-cores/lemuroid_core_<coreName>/build.gradle.kts` | **Criar** módulo Gradle |
| 20 | `lemuroid-cores/lemuroid_core_<coreName>/src/main/AndroidManifest.xml` | **Criar** manifest |
| 21 | `lemuroid-cores/lemuroid_core_<coreName>/src/main/jniLibs/<abi>/<libretroFileName>` | **Copiar** os 4 `.so` |
| 22 | `lemuroid-cores/bundled-cores/src/main/jniLibs/<abi>/<libretroFileName>` | **Copiar** os 4 `.so` (variante bundle) |
| 23 | `retrograde-app-shared/.../catalog/ManifestQuickLoader.kt` | **Bumpar `MANIFEST_SCHEMA_VERSION`** (obrigatório se adicionar entradas no catálogo) |

---

## 3. Passo 1 — `CoreID.kt`

Abra `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/CoreID.kt`.

Adicione uma nova entrada **antes do `;` que fecha a enum**. Use o formato exato dos vizinhos:

```kotlin
<CoreIdEnum>(
    "<coreName>",
    "<coreDisplayName>",
    "<libretroFileName>",
),
```

Exemplo real (Opera: o buildbot entrega `opera_libretro_android.so`, e o arquivo foi renomeado):

```kotlin
OPERA(
    "opera",
    "Opera",
    "libopera_libretro_android.so",   // <-- sempre com "lib", mesmo que o buildbot não tenha
),
```

**Não modifique** a função `getAssetManager` salvo se o core precisar de assets adicionais (caso raro — PPSSPP é a exceção).

---

## 4. Passo 2 — `SystemID.kt`

Abra `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/SystemID.kt`.

Adicione **antes** do `}` final da enum:

```kotlin
<SystemIdEnum>("<dbname>"),
```

Exemplo:

```kotlin
SATURN("saturn"),
```

**Regras para `<dbname>`:**
- Letras minúsculas, números, underscore. Sem espaços, sem hífens, sem maiúsculas.
- Deve ser **único** em relação a todos os outros `dbname`s do enum.
- Será usado como nome de pasta no catálogo (ex.: `saturn/Daytona USA (USA).cue`).

---

## 5. Passo 3 — `MetaSystemID.kt`

Abra `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/MetaSystemID.kt`.

Faça **2 edições**:

### 5.1. Adicione a entrada na enum

Antes do `;` que fecha a enum, no padrão dos vizinhos:

```kotlin
<SystemIdEnum>(
    R.string.game_system_title_<dbname>,
    R.drawable.game_system_<dbname>,
    listOf(SystemID.<SystemIdEnum>),
),
```

Exemplo:

```kotlin
SATURN(
    R.string.game_system_title_saturn,
    R.drawable.game_system_saturn,
    listOf(SystemID.SATURN),
),
```

> ⚠️ `R.string.game_system_title_<dbname>` e `R.drawable.game_system_<dbname>` ainda **não existem**; serão criados nos passos 14 e 15. Adicione a referência agora — o compilador só vai reclamar ao final, e os passos 14/15 resolvem.

### 5.2. Adicione o branch em `fromSystemID()`

No `when` dentro de `companion object { fun fromSystemID(...) }`, adicione (antes do `}` que fecha o `when`):

```kotlin
SystemID.<SystemIdEnum> -> <SystemIdEnum>
```

Exemplo:

```kotlin
SystemID.SATURN -> SATURN
```

**Sem essa linha**, o compilador Kotlin vai falhar com erro "when expression must be exhaustive".

---

## 6. Passo 4 — `HeavySystemFilter.kt`

Abra `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/HeavySystemFilter.kt`.

### 6.1. Decidir o tier

Use esta tabela para classificar o sistema novo:

| Tier do sistema | Critério | Onde adicionar | Visível em |
|-----------------|----------|----------------|------------|
| **LIGHT** | 2D/16 bits ou inferior; cores arcade leves (NES, SNES, Genesis, Atari, GBA, PCE, etc.) | **Não adicionar em lugar nenhum** | Todos os devices |
| **MODERATE** | 32/64 bits; Dreamcast, 3DO, PSX, N64, NDS, DOSBox, Sega CD | Adicionar em `MODERATE_SYSTEMS` | Esconde só em ULTRA_WEAK (≤1 GB RAM) |
| **VERY_HEAVY** | Cores pesados: PSP, 3DS | Adicionar em `VERY_HEAVY_SYSTEMS` | Esconde em WEAK (≤2 GB) e ULTRA_WEAK |

**Como decidir se está em dúvida:**
- Procure o sistema no [Libretro buildbot](https://buildbot.libretro.com/). Se o core tem variantes "lite/fast" (PCSX ReARMed, Mednafen), provavelmente é MODERATE.
- 5ª geração (3D inicial: PSX, N64, Saturn, 3DO, DC) → **MODERATE**.
- 6ª/7ª geração (PSP, GameCube, Wii, 3DS) → **VERY_HEAVY**.
- Tudo abaixo de 32-bit → **LIGHT** (não adicionar).

### 6.2. Editar o arquivo

Se for **MODERATE**, dentro do `private val MODERATE_SYSTEMS: Set<SystemID> = setOf(...)`, adicione:

```kotlin
SystemID.<SystemIdEnum>,   // <CoreDisplayName> – moderate-heavy
```

Se for **VERY_HEAVY**, dentro do `private val VERY_HEAVY_SYSTEMS: Set<SystemID> = setOf(...)`, adicione:

```kotlin
SystemID.<SystemIdEnum>,   // <CoreDisplayName> – very demanding
```

Se for **LIGHT**, **pule este arquivo**.

---

## 7. Passo 5 — `BiosManager.kt` (somente se o core requer BIOS)

Se o core **não requer BIOS** (a maioria não requer — apenas PSX, NDS, Dreamcast, 3DO, etc. requerem), **pule este passo**.

Se requer:

### 7.1. Obter MD5 e CRC32 reais da BIOS

Você **precisa** dos hashes reais. **NÃO INVENTE.** Se o usuário não fornecer:
1. Peça ao usuário um arquivo BIOS de exemplo.
2. Compute MD5 e CRC32 com PowerShell:
   ```powershell
   $bytes = [IO.File]::ReadAllBytes("C:\path\bios.bin")
   # MD5
   [System.BitConverter]::ToString([System.Security.Cryptography.MD5]::Create().ComputeHash($bytes)).Replace("-","")
   # CRC32 (precisa de classe customizada)
   Add-Type @"
   public static class Crc32 {
       public static uint Compute(byte[] bytes) {
           uint[] table = new uint[256];
           for (uint i = 0; i < 256; i++) {
               uint c = i;
               for (int k = 0; k < 8; k++) c = ((c & 1) != 0) ? (0xEDB88320 ^ (c >> 1)) : (c >> 1);
               table[i] = c;
           }
           uint crc = 0xFFFFFFFF;
           foreach (var b in bytes) crc = table[(crc ^ b) & 0xFF] ^ (crc >> 8);
           return crc ^ 0xFFFFFFFF;
       }
   }
   "@
   "{0:X8}" -f [Crc32]::Compute($bytes)
   ```

### 7.2. Upload das BIOS para HuggingFace

O Lemuroid baixa BIOS de `https://huggingface.co/datasets/luistiagos/bios/resolve/main/<filename>`. Faça upload via API commit (não PUT direto — não funciona):

```bash
curl -X POST "https://huggingface.co/api/datasets/luistiagos/bios/commit/main" \
  -H "Authorization: Bearer <TOKEN_HF>" \
  -H "Content-Type: application/json" \
  -d '{ "summary": "Add <NomeFriendly> BIOS", "operations": [...] }'
```

(Se o usuário não tem permissão de escrita no dataset, **pare e peça o arquivo BIOS** para ele subir manualmente. Não invente URLs.)

### 7.3. Adicionar entradas em `BiosManager.kt`

Em `SUPPORTED_BIOS` listOf(...), adicione **antes** do `)` que fecha:

```kotlin
// <NomeFriendly> BIOS (<CoreDisplayName>)
Bios(
    "<caminho_relativo_dentro_de_system>",   // ex.: "saturn/sega_101.bin" ou "panafz1.bin"
    "<MD5_EM_MAIUSCULAS_SEM_HIFENS>",
    "<NomeFriendly> BIOS (descrição)",
    SystemID.<SystemIdEnum>,
    "<CRC32_EM_MAIUSCULAS>",                  // opcional, mas recomendado
    "<nomeArquivoNoHuggingFace>",             // opcional, default = filename do path
),
```

Exemplo real (Dreamcast, BIOS em subpasta):

```kotlin
Bios(
    "dc/dc_boot.bin",                  // caminho dentro de system/
    "E10C53C2F8B90BAB96EAD2D368858623",
    "Dreamcast BIOS (World)",
    SystemID.DREAMCAST,
    "89F2B1A1",
    "dc_boot.bin",                     // nome no HuggingFace (sem o prefixo de pasta)
),
```

> **PITFALL crítico:** alguns cores esperam BIOS em subpasta (`system/dc/dc_boot.bin`), outros em `system/` raiz (`system/panafz1.bin`). Cheque a documentação do core no GitHub do Libretro. Use o caminho que o **core espera** como primeiro argumento — `BiosManager` cria automaticamente os diretórios pais via `biosFile.parentFile?.mkdirs()`.

---

## 8. Passo 6 — `ControllerConfigs.kt` (⚠️ **PITFALL CRÍTICO**)

Abra `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/ControllerConfigs.kt`.

Adicione antes do `}` final do `object ControllerConfigs`:

```kotlin
val <SystemIdEnum> =
    ControllerConfig(
        "default",
        R.string.controller_<dbname>,
        TouchControllerID.<SystemIdEnum>,
        mergeDPADAndLeftStickEvents = true,
        libretroDescriptor = "<libretroControllerDescriptor>",
        tiltConfigurations =
            listOf(
                TILT_CONFIGURATION_DISABLED,
                TILT_CONFIGURATION_CROSS,
                TILT_CONFIGURATION_L_R,
            ),
    )
```

### 8.1. PITFALL — `libretroDescriptor` exato

**Este é o erro mais comum** ao adicionar um sistema novo. Sintoma: os botões do touch controller acendem ao toque mas o jogo não reage.

A causa é que **alguns cores Libretro inicializam todas as portas com `RETRO_DEVICE_NONE`** e só processam input depois que o app chama `setControllerType(port, RETRO_DEVICE_JOYPAD)`. O Lemuroid só faz essa chamada se `libretroDescriptor` ou `libretroId` do `ControllerConfig` **casar exatamente** com um dos controllers que o core registra via `RETRO_ENVIRONMENT_SET_CONTROLLER_INFO`.

**Como descobrir o descriptor exato:**

1. No GitHub do Libretro, abra o source do core (`libretro.c` ou `libretro_core_options.h`).
2. Procure `RETRO_ENVIRONMENT_SET_CONTROLLER_INFO` ou `retro_controller_description`.
3. Você verá algo como:
   ```c
   static const struct retro_controller_description controllers[] = {
       { "3DO Joypad",        RETRO_DEVICE_JOYPAD },
       { "3DO Mouse",         RETRO_DEVICE_MOUSE },
       ...
   };
   ```
4. Use **a primeira string literal** (a do tipo JOYPAD). Para o Opera, é `"3DO Joypad"` — **não** `"Joypad"` nem `"3DO"`.

**Se o core não chamar `SET_CONTROLLER_INFO`**, ele provavelmente usa `RETRO_DEVICE_JOYPAD` como padrão automaticamente — nesse caso pode omitir o campo `libretroDescriptor`. Mas em caso de dúvida, **sempre** inspecione o source.

**Como testar se ficou certo:** depois do build, abra um jogo e veja se os botões respondem. Se acenderem mas não funcionarem, o descriptor está errado. Use `adb logcat | grep -i controller` para ver se aparece `"Controls setting <port> to <id>"` no log — se não aparecer, `setControllerType` não foi chamado.

### 8.2. `mergeDPADAndLeftStickEvents`

Se o sistema **não tem** analog stick (Atari, NES, SNES, PCE, Genesis 3-button, 3DO), use `mergeDPADAndLeftStickEvents = true`. Isso garante que o D-pad físico também emula o analog esquerdo, evitando problemas em jogos que esperam analog.

Se o sistema **tem** analog stick (N64, PSX DualShock, Dreamcast, PSP), use `allowTouchRotation = true` (em vez de `mergeDPADAndLeftStickEvents`) — analógicos rotacionados pelo touch.

### 8.3. `tiltConfigurations`

Lista de configurações de tilt (sensor de inclinação do device) disponíveis para o usuário escolher nos settings. Use o padrão:

- Sempre inclua `TILT_CONFIGURATION_DISABLED` (sem tilt) e `TILT_CONFIGURATION_CROSS` (tilt vira D-pad).
- Inclua `TILT_CONFIGURATION_L_R` se o sistema tem botões shoulder L1/R1.
- Inclua `TILT_CONFIGURATION_L1_R1` ou `TILT_CONFIGURATION_L2_R2` se o sistema tem L1/L2/R1/R2 separados (PSX, DualShock).
- Inclua `TILT_CONFIGURATION_ANALOG_LEFT` ou `TILT_CONFIGURATION_ANALOG_RIGHT` se tem analog stick.

---

## 9. Passo 7 — `GameSystem.kt`

Abra `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/GameSystem.kt`.

Dentro do `private val SYSTEMS = listOf(...)`, adicione **antes** do `)` que fecha a lista:

```kotlin
GameSystem(
    SystemID.<SystemIdEnum>,
    "<libretroFullName>",
    R.string.game_system_title_<dbname>,
    R.string.game_system_abbr_<dbname>,
    listOf(
        SystemCoreConfig(
            CoreID.<CoreIdEnum>,
            requiredBIOSFiles = listOf(<biosFiles>),    // omitir se sem BIOS
            controllerConfigs =
                hashMapOf(
                    0 to arrayListOf(ControllerConfigs.<SystemIdEnum>),
                    1 to arrayListOf(ControllerConfigs.<SystemIdEnum>),
                ),
            exposedSettings = listOf(
                // (opcional) settings que o usuário pode mudar no menu in-game
            ),
            defaultSettings = listOf(
                // (opcional) defaults de variáveis do core
            ),
            statesSupported = true,        // true se o core suporta save states (a maioria suporta)
            rumbleSupported = false,        // true se o core e o sistema suportam vibração
            skipDuplicateFrames = false,    // true para alguns cores ajuda a economizar CPU
        ),
    ),
    scanOptions = ScanOptions(
        scanByFilename = false,
        scanByUniqueExtension = false,
        scanByPathAndSupportedExtensions = true,
    ),
    uniqueExtensions = listOf(),
    supportedExtensions = listOf(<extensoes>),
    hasMultiDiskSupport = false,        // true para sistemas com multi-disco (PSX, DC, Saturn, 3DO)
),
```

### 9.1. ScanOptions — qual usar

- `scanByPathAndSupportedExtensions = true` → arquivos são escaneados se estiverem na pasta `<dbname>/` e tiverem extensão de `supportedExtensions`. **Use isso na maioria dos casos.**
- `scanByUniqueExtension = true` → escaneia qualquer pasta se a extensão for única (use só para sistemas com extensão exclusiva tipo `.a26`, `.nes`).
- `scanByFilename = true` → escaneia também por padrões no nome do arquivo (usado pelo MAME).

### 9.2. `controllerConfigs` ports

Adicione tantas portas quanto o sistema suporta:
- 1 jogador local: porta `0`
- 2 jogadores local: portas `0` e `1`
- 4 jogadores local: portas `0`, `1`, `2`, `3`

Cada porta deve apontar para o mesmo `ControllerConfigs.<SystemIdEnum>`.

### 9.3. `exposedSettings` e `defaultSettings`

Estes são opcionais. Veja como sistemas similares fazem (procure `exposedSettings = listOf(` no arquivo). Se for o seu primeiro sistema, **deixe vazio** — o usuário pode adicionar depois.

### 9.4. Padrão multi-variante (um core, múltiplos modelos de hardware)

Quando um sistema tem modelos de hardware distintos (ex.: Amiga 500, Amiga 1200, Amiga CD32) servidos pelo **mesmo core** e `.so`, crie **um `SystemID` e `GameSystem` por modelo**, mas compartilhe o `CoreID`, módulo e `.so`. A diferença entre eles é apenas `defaultSettings`:

```kotlin
// Sistema base — tem uniqueExtensions; os outros usam só folder-scan para não colidir
GameSystem(
    SystemID.AMIGA,
    ...
    listOf(SystemCoreConfig(CoreID.PUAE,
        defaultSettings = listOf(CoreVariable("puae_model", "auto")), ...)),
    uniqueExtensions = listOf("adf"),
    supportedExtensions = listOf("adf", "hdf"),
),
// Variante — sem uniqueExtensions
GameSystem(
    SystemID.AMIGA_1200,
    ...
    listOf(SystemCoreConfig(CoreID.PUAE,
        defaultSettings = listOf(CoreVariable("puae_model", "A1200")), ...)),
    uniqueExtensions = listOf(),
    supportedExtensions = listOf("adf", "hdf"),
),
```

Vantagens: um único módulo (`:lemuroid_core_puae`) com os `.so` serve todos; não duplicar jniLibs.

---

## 10. Passo 8 — `TouchControllerID.kt`

Abra `lemuroid-touchinput/src/main/java/com/swordfish/touchinput/radial/settings/TouchControllerID.kt`.

### 10.1. Adicione 2 imports no topo

```kotlin
import com.swordfish.touchinput.radial.layouts.<NomeSistema>Left
import com.swordfish.touchinput.radial.layouts.<NomeSistema>Right
```

Onde `<NomeSistema>` é o nome do arquivo `.kt` que você vai criar no Passo 9 (ex.: `Saturn`, `ThreeDO`, `Dreamcast`).

### 10.2. Adicione entrada na enum

Antes do `;` que fecha a enum (a enum começa em `enum class TouchControllerID {`):

```kotlin
<SystemIdEnum>,
```

### 10.3. Adicione branch no `when` de `getConfig()`

Antes do `}` que fecha o `when`:

```kotlin
<SystemIdEnum> ->
    Config(
        { modifier, settings -> <NomeSistema>Left(modifier, settings) },
        { modifier, settings -> <NomeSistema>Right(modifier, settings) },
    )
```

---

## 11. Passo 9 — Criar o layout do touch controller

Crie o arquivo `lemuroid-touchinput/src/main/java/com/swordfish/touchinput/radial/layouts/<NomeSistema>.kt`.

### 11.1. Mapeamento de botões — **estude o core primeiro**

Antes de escrever o layout, **descubra o mapeamento RetroPad → sistema real** consultando o source do core (procure `RETRO_DEVICE_ID_JOYPAD_*` ou input descriptors). Exemplo do Opera (3DO):

```c
{ 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_B,     "A" },        // RetroPad B -> 3DO A
{ 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_A,     "B" },        // RetroPad A -> 3DO B
{ 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_Y,     "C" },        // RetroPad Y -> 3DO C
{ 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_START, "Play/Pause" },
{ 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_L,     "Left Shift" },
{ 0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_R,     "Right Shift" },
```

A correspondência entre Android KeyCode e RetroPad é fixa (definida em `input.cpp` do LibretroDroid):

| Android KeyCode | RetroPad ID |
|-----------------|-------------|
| `KEYCODE_BUTTON_B` (south) | RETRO_DEVICE_ID_JOYPAD_B (0) |
| `KEYCODE_BUTTON_A` (east) | RETRO_DEVICE_ID_JOYPAD_A (8) |
| `KEYCODE_BUTTON_X` (north) | RETRO_DEVICE_ID_JOYPAD_X (9) |
| `KEYCODE_BUTTON_Y` (west) | RETRO_DEVICE_ID_JOYPAD_Y (1) |
| `KEYCODE_BUTTON_START` | RETRO_DEVICE_ID_JOYPAD_START (3) |
| `KEYCODE_BUTTON_SELECT` | RETRO_DEVICE_ID_JOYPAD_SELECT (2) |
| `KEYCODE_BUTTON_L1` | RETRO_DEVICE_ID_JOYPAD_L (10) |
| `KEYCODE_BUTTON_R1` | RETRO_DEVICE_ID_JOYPAD_R (11) |
| `KEYCODE_BUTTON_L2` | RETRO_DEVICE_ID_JOYPAD_L2 (12) |
| `KEYCODE_BUTTON_R2` | RETRO_DEVICE_ID_JOYPAD_R2 (13) |
| `DPAD_UP/DOWN/LEFT/RIGHT` | RETRO_DEVICE_ID_JOYPAD_UP/DOWN/LEFT/RIGHT |

Então no exemplo Opera, o botão "A" do 3DO deve emitir `KEYCODE_BUTTON_B` (porque `KEYCODE_BUTTON_B` → `RETRO_DEVICE_ID_JOYPAD_B` → 3DO A).

### 11.2. Esqueleto do arquivo (3 face buttons + L1/R1 + Start + Select)

Use isso como base e ajuste:

```kotlin
package com.swordfish.touchinput.radial.layouts

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import com.swordfish.touchinput.controller.R
import com.swordfish.touchinput.radial.controls.LemuroidControlCross
import com.swordfish.touchinput.radial.controls.LemuroidControlFaceButtons
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonL1
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonMenu
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonMenuPlaceholder
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonR1
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonSelect
import com.swordfish.touchinput.radial.layouts.shared.SecondaryButtonStart
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import com.swordfish.touchinput.radial.ui.LemuroidButtonForeground
import gg.padkit.PadKitScope
import gg.padkit.ids.Id
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * <NomeFriendly> controller layout (<CoreDisplayName> core).
 * Mapeamento RetroPad → sistema (verificado no source do core):
 *   <documente aqui o mapping para futura referência>
 */
@Composable
fun PadKitScope.<NomeSistema>Left(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutLeft(
        settings = settings,
        modifier = modifier,
        primaryDial = { LemuroidControlCross(id = Id.DiscreteDirection(ComposeTouchLayouts.MOTION_SOURCE_DPAD)) },
        secondaryDials = {
            SecondaryButtonL1()
            SecondaryButtonSelect(position = 2)
            SecondaryButtonMenuPlaceholder(settings)
        },
    )
}

@Composable
fun PadKitScope.<NomeSistema>Right(
    modifier: Modifier = Modifier,
    settings: TouchControllerSettingsManager.Settings,
) {
    BaseLayoutRight(
        settings = settings,
        modifier = modifier,
        primaryDial = {
            LemuroidControlFaceButtons(
                ids =
                    persistentListOf(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B), // adapte aos botões reais do sistema
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A),
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y),
                    ),
                idsForegrounds =
                    persistentMapOf<Id.Key, @Composable (State<Boolean>) -> Unit>(
                        Id.Key(KeyEvent.KEYCODE_BUTTON_B) to {
                            LemuroidButtonForeground(pressed = it, icon = R.drawable.<prefix>_button_a)
                        },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_A) to {
                            LemuroidButtonForeground(pressed = it, icon = R.drawable.<prefix>_button_b)
                        },
                        Id.Key(KeyEvent.KEYCODE_BUTTON_Y) to {
                            LemuroidButtonForeground(pressed = it, icon = R.drawable.<prefix>_button_c)
                        },
                    ),
            )
        },
        secondaryDials = {
            SecondaryButtonR1()
            SecondaryButtonStart(position = 2)
            SecondaryButtonMenu(settings)
        },
    )
}
```

### 11.3. Secundary buttons disponíveis (importe os que usar)

Estão em `lemuroid-touchinput/src/main/java/com/swordfish/touchinput/radial/layouts/shared/Controls.kt`:

| Função | Botão emitido |
|--------|---------------|
| `SecondaryButtonL1()` | `KEYCODE_BUTTON_L1` |
| `SecondaryButtonL2()` | `KEYCODE_BUTTON_L2` |
| `SecondaryButtonR1()` | `KEYCODE_BUTTON_R1` |
| `SecondaryButtonR2()` | `KEYCODE_BUTTON_R2` |
| `SecondaryButtonStart(position = N)` | `KEYCODE_BUTTON_START` |
| `SecondaryButtonSelect(position = N)` | `KEYCODE_BUTTON_SELECT` |
| `SecondaryButtonMenu(settings)` | Botão de menu (`KEYCODE_BUTTON_MODE`) |
| `SecondaryButtonMenuPlaceholder(settings)` | Slot vazio que ocupa o espaço do menu no lado oposto |
| `SecondaryAnalogLeft()` | Analog stick esquerdo |
| `SecondaryAnalogRight()` | Analog stick direito |
| `SecondaryButtonCoin()` | Coin (usa SELECT, usado em arcade) |
| `SecondaryButtonKeyboard(...)` | Botão de teclado virtual (usado em DOS, computadores) |

### 11.4. Para sistemas com analog stick

Veja `Dreamcast.kt` ou `PSXDualShock.kt`. O `ThreeDOLeft` por exemplo **não tem** analog porque o controle original do 3DO não tinha. Para sistemas com analog, adicione `SecondaryAnalogLeft()` em `ThreeDOLeft` e `SecondaryAnalogRight()` em `ThreeDORight` se também tiver analog direito.

### 11.5. Para sistemas com 2 face buttons (Atari 2600/7800, Game & Watch)

Veja `Atari7800.kt` — passa só 2 ids no `LemuroidControlFaceButtons`.

Para botões com rótulos de letras simples (A, B, C, I, II...) não é necessário criar drawables: use o parâmetro `label` diretamente:

```kotlin
Id.Key(KeyEvent.KEYCODE_BUTTON_A) to {
    LemuroidButtonForeground(pressed = it, label = "A")   // texto direto, sem drawable
},
Id.Key(KeyEvent.KEYCODE_BUTTON_B) to {
    LemuroidButtonForeground(pressed = it, label = "B")
},
```

Só crie drawables XML (`<prefix>_button_x.xml`) quando precisar de símbolos especiais (triângulo, círculo, cruz, quadrado do PlayStation, setas coloridas).

Para layouts com 2 botões no dial de face buttons, adicione `includeComposite = false` para desabilitar o botão composto (que seria um terceiro botão implícito):

```kotlin
LemuroidControlFaceButtons(
    ids = persistentListOf(...),
    includeComposite = false,   // omita se tiver 3+ botões
    ...
)
```

### 11.6. Para sistemas com 4 face buttons (SNES, PSX, Dreamcast)

Veja `Dreamcast.kt` — 4 ids, ordem: south, east, west, north.

### 11.7. Para 3 face buttons (Genesis 3-button, 3DO)

3 ids. Para melhor layout visual, talvez adicionar `rotationInDegrees = -30f` no `LemuroidControlFaceButtons` (veja `Genesis3.kt`). Sem rotação, o layout fica triângulo com vértice pra baixo.

---

## 12. Passo 10 — `ShaderChooser.kt`

Abra `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/ShaderChooser.kt`.

Faça **2 edições**:

### 12.1. Branch em `getDefaultShaderForSystem()`

No `when (system.id)` da função `getDefaultShaderForSystem`, adicione antes do `}` final:

```kotlin
SystemID.<SystemIdEnum> -> ShaderConfig.<TipoShader>
```

Onde `<TipoShader>` é:
- `Default` → sistemas 3D modernos (Dreamcast, 3DO, PSP)
- `CRT` → consoles de CRT (NES, SNES, Genesis, N64, PSX, MAME)
- `LCD` → portáteis (GB, GBA, NDS, PSP, Lynx)
- `Sharp` → quando quer sem filtro algum

### 12.2. Branch em `getConfigForSystem()`

No `when (system.id)` da função `getConfigForSystem` (usada por HD Mode), adicione:

```kotlin
SystemID.<SystemIdEnum> -> <variante>
```

Onde `<variante>` é uma das definidas no escopo:
- `upscale8Bits` / `upscale8BitsMobile` → consoles 8-bit (NES, SMS, Atari)
- `upscale16Bits` / `upscale16BitsMobile` → consoles 16-bit (SNES, Genesis, GBA)
- `upscale32Bits` → consoles 32-bit (PSX, N64, arcade complexo)
- `modern` → 3D moderno (Dreamcast, 3DO, PSP, 3DS, VB)

---

## 13. Passo 11 — `strings.xml` (settings do core)

Abra `retrograde-app-shared/src/main/res/values/strings.xml`.

### 13.1. String do nome do controller

Adicione:

```xml
<string name="controller_<dbname>"><NomeFriendly></string>
```

### 13.2. Strings das settings expostas (se você adicionou `exposedSettings` no GameSystem)

Para cada setting:

```xml
<string name="setting_<coreName>_<chave>">Nome legível da setting</string>
<string name="value_<coreName>_<chave>_<valor>">Nome legível do valor</string>
```

Se você deixou `exposedSettings` vazio no Passo 9, pule esta parte.

---

## 14. Passo 12 — `strings-game-system.xml`

Abra `retrograde-app-shared/src/main/res/values/strings-game-system.xml`.

Adicione **antes** do `</resources>`:

```xml
<string name="game_system_abbr_<dbname>"><AbreviacaoCurta></string>
<string name="game_system_title_<dbname>"><NomeFriendly></string>
```

Exemplo:

```xml
<string name="game_system_abbr_saturn">Saturn</string>
<string name="game_system_title_saturn">Sega Saturn</string>
```

A `abbr` é mostrada em listas compactas, o `title` em telas detalhe.

> ⚠️ **XML e o caractere `&`:** se o nome do sistema contém `&`, use `&amp;` — XML literal é inválido e vai travar a compilação:
> ```xml
> <!-- ERRADO -->   <string name="game_system_title_gw">Nintendo Game & Watch</string>
> <!-- CORRETO -->  <string name="game_system_title_gw">Nintendo Game &amp; Watch</string>
> ```

---

## 15. Passo 13 — Logos do sistema (Normal e Hover PNG)

O Lemuroid utiliza logos rasterizados no padrão RetroBat com dois estados (normal e hover quando em foco no Leanback/TV/Compose).

1. Localize os arquivos em `logos_extracted/all-systems/`:
   - `*-<sistema>-normal.png` (277x192 RGBA)
   - `*-<sistema>-hover.png` (333x230 RGBA)
2. Copie para `retrograde-app-shared/src/main/res/drawable/`:
   - `game_system_<dbname>.png`
   - `game_system_<dbname>_hover.png`
3. Em `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/SystemLogoResolver.kt`:
   - Adicione o mapeamento do hover no `when (metaSystem)`:
     ```kotlin
     MetaSystemID.<SYSTEM> -> R.drawable.game_system_<dbname>_hover
     ```

> ⚠️ **Atenção:** Nunca crie arquivos `.xml` vetoriais improvisados no lugar de PNG. Todos os 79 sistemas utilizam o par PNG normal/hover extraído de `logos_extracted/all-systems/`. Ter um `.xml` e um `.png` com o mesmo nome também quebra o build do AAPT (Duplicate resources).

---

## 16. Passo 14 — Drawables dos face buttons (somente se layout customizado)

Se você usou `label = "X"` no `LemuroidButtonForeground` (como Genesis3), **pule este passo**.

Se você usou `icon = R.drawable.<prefix>_button_<n>`, crie um drawable para cada botão em `lemuroid-touchinput/src/main/res/drawable/`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="100dp"
    android:height="100dp"
    android:viewportWidth="100"
    android:viewportHeight="100">
  <!-- Letter X -->
  <path
      android:fillColor="#fff"
      android:pathData="<path_da_letra_X>"/>
</vector>
```

Vetor das letras "A", "B", "C", "X", "Y" já existem em sistemas anteriores — copie de `dc_button_a.xml`, `dc_button_b.xml`, etc. e renomeie.

---

## 17. Passo 15 — `core_names.xml`

Abra `lemuroid-app/src/main/res/values/core_names.xml`.

Adicione antes do `</resources>`:

```xml
<string name="core_name_<coreName>" translatable="false"><coreName></string>
```

Exemplo:

```xml
<string name="core_name_opera" translatable="false">opera</string>
```

Esta string é usada como título do dynamic feature module no Play Store.

---

## 18. Passo 16 — `mnemonico_map.json`

Abra `lemuroid-app/src/main/assets/mnemonico_map.json`.

Adicione **antes** do `}` final, mantendo a vírgula no item anterior:

```json
  "<dbname>": "<dbname>"
```

Exemplo:

```json
{
  ...
  "dc": "dreamcast",
  "3do": "3do"
}
```

A chave é o `<dbname>` do `SystemID`. O valor é o `name` exato na tabela MNEMONICO do endpoint `emuladores.pythonanywhere.com` — **NÃO assuma que é igual ao `<dbname>`**.

> ⚠️ **PITFALL CRÍTICO:** se o valor não existir na tabela MNEMONICO, o catálogo aparece normalmente mas **tocar num jogo não dispara o download** (falha silenciosa sem mensagem de erro visível ao usuário).
>
> Bug histórico: `"amiga": "amiga"` (errado) → `"amiga": "amiga500"` (correto). A tabela divide Amiga em `amiga500`/`amiga1200`/`amigacd32`/`amigacdtv`.
>
> **Como verificar:** confirme o `name` exato com o usuário antes de commitar. Se em dúvida, use o `<dbname>` mesmo (pior caso: download falha, sistema aparece no catálogo).

Exemplos de mapeamentos confirmados:

```json
{
  "dc":        "dreamcast",
  "3do":       "3do",
  "saturn":    "saturn",
  "gc":        "gamecube",
  "amiga":     "amiga500",
  "amiga1200": "amiga1200",
  "amigacd32": "amigacd32",
  "amigacdtv": "amigacdtv",
  "gw":        "gw"
}
```

---

## 19. Passo 17 — `settings.gradle.kts`

Abra `settings.gradle.kts`.

Faça **2 edições** dentro do `if (usePlayDynamicFeatures()) { ... }`:

### 19.1. Adicione ao `include(...)`

Antes do `)` que fecha o include, dentro do bloco do `if`:

```kotlin
":lemuroid_core_<coreName>",
```

### 19.2. Adicione ao projectDir

Depois dos outros `project(":lemuroid_core_*").projectDir = File(...)`:

```kotlin
project(":lemuroid_core_<coreName>").projectDir = File("lemuroid-cores/lemuroid_core_<coreName>")
```

---

## 20. Passo 18 — `lemuroid-app/build.gradle.kts`

Abra `lemuroid-app/build.gradle.kts`.

Dentro do `if (usePlayDynamicFeatures()) { ... }`, no `dynamicFeatures.addAll(setOf(...))`, adicione antes do `)`:

```kotlin
":lemuroid_core_<coreName>",
```

---

## 21. Passo 19 — Criar `lemuroid-cores/lemuroid_core_<coreName>/build.gradle.kts`

**Crie** o arquivo com este conteúdo (substitua só `<coreName>`):

```kotlin
plugins {
    id("com.android.dynamic-feature")
    id("kotlin-android")
    id("kotlin-kapt")
}

android {
    namespace = "com.swordfish.lemuroid.core.<coreName>"
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

---

## 22. Passo 20 — Criar `lemuroid-cores/lemuroid_core_<coreName>/src/main/AndroidManifest.xml`

**Crie** o arquivo:

```xml
<manifest xmlns:dist="http://schemas.android.com/apk/distribution"
    xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:hasCode="false"
        android:extractNativeLibs="true" />

    <dist:module dist:title="@string/core_name_<coreName>">
        <dist:delivery>

<dist:on-demand />
<dist:install-time>
    <dist:conditions>
        <dist:device-feature dist:name="android.software.leanback"/>
    </dist:conditions>
</dist:install-time>

        </dist:delivery>
        <dist:fusing dist:include="true" />
    </dist:module>
</manifest>
```

A condição `android.software.leanback` faz com que TVs (que rodam Android TV / Leanback) baixem o core **na instalação**, enquanto smartphones baixam **on-demand** (só quando o usuário tentar abrir um jogo do sistema). Isso garante compatibilidade com smartphone, TV box e Smart TV.

---

## 23. Passo 21 — Copiar os `.so` para cada ABI

Crie a estrutura e copie os 4 binários:

```
lemuroid-cores/lemuroid_core_<coreName>/src/main/jniLibs/
├── arm64-v8a/<libretroFileName>
├── armeabi-v7a/<libretroFileName>
├── x86/<libretroFileName>
└── x86_64/<libretroFileName>
```

**Comando para verificar que copiou tudo certo:**

```bash
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  f="lemuroid-cores/lemuroid_core_<coreName>/src/main/jniLibs/$abi/<libretroFileName>"
  if [ ! -f "$f" ]; then echo "FALTANDO: $f"; else echo "OK: $f ($(stat -c%s $f) bytes)"; fi
done
```

Cada arquivo deve ter pelo menos algumas centenas de KB. Se algum estiver abaixo de 10 KB, é um stub e precisa ser substituído (cores symlinks no Git podem virar stubs no Windows).

---

## 24. Passo 22 — Copiar para `bundled-cores`

A variant `bundle` (sem dynamic features, usado em builds para devices sem Play Store) precisa dos mesmos `.so` em `lemuroid-cores/bundled-cores/src/main/jniLibs/<abi>/`.

```bash
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  src="lemuroid-cores/lemuroid_core_<coreName>/src/main/jniLibs/$abi/<libretroFileName>"
  dst="lemuroid-cores/bundled-cores/src/main/jniLibs/$abi/<libretroFileName>"
  mkdir -p "$(dirname $dst)"
  cp "$src" "$dst"
done
```

O `bundled-cores/build.gradle.kts` tem uma task `materializeNativeLibs` que faz isso automaticamente no build, mas copiar manualmente garante que o arquivo está lá imediatamente.

---

## 24.5. Passo 23 — Bumpar `MANIFEST_SCHEMA_VERSION` *(obrigatório se adicionar entradas no catálogo)*

> **Por que é obrigatório?** Em instalações existentes o `ManifestQuickLoader` pula a carga inteira quando o schema já foi processado. Sem o bump, as novas ROMs do manifest **nunca são inseridas** — o sistema não aparece para usuários com o app já instalado. Foi exatamente o bug "GameCube não aparecia".

Abra `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/catalog/ManifestQuickLoader.kt`.

Incremente `MANIFEST_SCHEMA_VERSION` em +1 e registre no histórico inline:

```kotlin
// Bump when new catalog entries are added for new systems.
// v12  gc           v13  saturn      v14  jaguar
// v15  odyssey2     v16  neocd       v17  amiga
// v18  amiga vars   v19  pcfx        v20  gw
// v<N+1>  <dbname> (<NomeFriendly>)
const val MANIFEST_SCHEMA_VERSION = <N+1>
```

O bump deve acontecer no **mesmo commit** em que as linhas `<dbname>/...` são adicionadas ao `catalog_manifest.txt`.

**Instalações novas** (app recém-instalado) usam o prebuilt DB asset e não dependem do bump — o `PrebuiltDbGenerator` reage automaticamente à mudança no `catalog_manifest.txt`.

---

## 25. Build e validação

Execute em ordem (no Windows use PowerShell ou bash via Git Bash). Use `--no-daemon --max-workers=1` para evitar OOM em máquinas com pouca RAM:

### 25.1. Compilar o shared

```bash
GRADLE_OPTS="-Xmx1536m -XX:MaxMetaspaceSize=512m" ./gradlew --no-daemon --max-workers=1 :retrograde-app-shared:compileDebugKotlin
```

Espere `BUILD SUCCESSFUL`. Se falhar:
- "Unresolved reference: <SystemIdEnum>" → faltou passo 4 (SystemID.kt) ou 5 (MetaSystemID.kt)
- "Unresolved reference: game_system_title_<dbname>" → faltou passo 14 (strings-game-system.xml)
- "Unresolved reference: game_system_<dbname>" → faltou passo 15 (drawable)
- "when expression must be exhaustive" → faltou branch em algum `when` (revisar passos 5.2, 12.1, 12.2)

### 25.2. Compilar o touch input e app

```bash
GRADLE_OPTS="-Xmx1536m -XX:MaxMetaspaceSize=512m" ./gradlew --no-daemon --max-workers=1 :lemuroid-touchinput:compileDebugKotlin :lemuroid-app:compileFreeBundleDebugKotlin
```

Se falhar:
- "Unresolved reference: <NomeSistema>Left" → faltou passo 11 (criar layout)
- "Unresolved reference: TouchControllerID.<SystemIdEnum>" → faltou passo 10
- "Unresolved reference: ControllerConfigs.<SystemIdEnum>" → faltou passo 8

### 25.3. Build completo

```bash
GRADLE_OPTS="-Xmx1536m -XX:MaxMetaspaceSize=512m" ./gradlew --no-daemon --max-workers=1 :lemuroid-app:assembleFreeBundleDebug
```

Espere `BUILD SUCCESSFUL`. Se falhar com erro de dynamic feature, revisar passos 17, 18, 19, 20.

> **Nota:** as variantes `play*` (`assemblePlayDynamicDebug`) estão **desabilitadas** neste checkout — a task não existe e o Gradle retorna "Task not found". Use sempre `:lemuroid-app:assembleFreeBundleDebug` ou `build_apk.ps1 -Debug`.

Após o build, verifique que o `.so` do core está no APK:

```bash
unzip -l app/build/outputs/apk/freeBundle/debug/*.apk | grep <coreName>
# Espera 4 linhas (uma por ABI)
```

---

## 26. Checklist final (não pule)

Confirme item por item antes de declarar pronto:

- [ ] `CoreID.kt` tem a entrada com nome `lib<coreName>_libretro_android.so` (com `lib`, sempre), igual ao nome dos `.so` copiados
- [ ] `SystemID.kt` tem a entrada com `dbname` minúsculo
- [ ] `MetaSystemID.kt` tem **enum** e **branch em fromSystemID()**
- [ ] `HeavySystemFilter.kt` classificado no tier correto (ou omitido se LIGHT)
- [ ] BIOS necessária? Se sim, hashes reais (não placeholders) em `BiosManager.kt` e arquivos no HuggingFace
- [ ] `ControllerConfigs.kt` com `libretroDescriptor` **literal e exato** do source do core
- [ ] `GameSystem.kt` com `SystemCoreConfig`, `controllerConfigs` para cada porta suportada, `supportedExtensions` correto
- [ ] `TouchControllerID.kt` com imports + enum + `getConfig()` branch
- [ ] Arquivo `<NomeSistema>.kt` criado em `layouts/`, com `<NomeSistema>Left` e `<NomeSistema>Right`
- [ ] Mapeamento de keycodes documentado no header do layout file
- [ ] `ShaderChooser.kt` com branches em **2 funções** (`getDefaultShaderForSystem` + `getConfigForSystem`)
- [ ] `strings.xml` com `controller_<dbname>` (e settings se aplicável)
- [ ] `strings-game-system.xml` com `game_system_title_<dbname>` e `game_system_abbr_<dbname>`
- [ ] Drawable `game_system_<dbname>.xml` criado
- [ ] (Se face buttons custom) drawables dos botões criados
- [ ] `core_names.xml` com `core_name_<coreName>`
- [ ] `mnemonico_map.json` com `<dbname>` adicionado
- [ ] `settings.gradle.kts` com include + projectDir do módulo novo
- [ ] `lemuroid-app/build.gradle.kts` com `:lemuroid_core_<coreName>` em `dynamicFeatures`
- [ ] `lemuroid-cores/lemuroid_core_<coreName>/build.gradle.kts` criado
- [ ] `lemuroid-cores/lemuroid_core_<coreName>/src/main/AndroidManifest.xml` criado
- [ ] **4 `.so` files** (arm64-v8a, armeabi-v7a, x86, x86_64) em `lemuroid_core_<coreName>/src/main/jniLibs/`
- [ ] **Mesmos 4 `.so` files** também em `bundled-cores/src/main/jniLibs/`
- [ ] `.so` commitados **e enviados** (`git push`) no `lemuroid-cores`, numa **tag nova**, com `CoreDownloader.CORES_VERSION` apontando para ela: o fallback de download busca `lemuroid_core_<coreName>/…` nessa tag. Sem isso o build de release falha na `verifyCoresPublished`.
- [ ] `MANIFEST_SCHEMA_VERSION` incrementado em `ManifestQuickLoader.kt` (se adicionou entradas no catálogo)
- [ ] `mnemonico_map.json` — valor confirmado contra tabela MNEMONICO do endpoint (não assumido igual ao dbname)
- [ ] Build `:retrograde-app-shared:compileDebugKotlin` passa
- [ ] Build `:lemuroid-app:assembleFreeBundleDebug` passa (usar esta task — `assemblePlayDynamicDebug` **não existe** neste checkout)
- [ ] APK contém o `.so` do core: `unzip -l <apk> | grep <coreName>` retorna 4 linhas
- [ ] Testar no device (instalar APK, abrir jogo do sistema, verificar que touch responde, verificar joystick físico responde)

---

## 27. Pitfalls conhecidos (revise antes de declarar pronto)

### 27.1. Touch acende mas jogo não reage

**Causa mais provável:** `libretroDescriptor` não casa com o registrado pelo core. Reveja Passo 8.1.

Cores que **já se sabe** ter esse comportamento:
- **Opera (3DO)** → descriptor é `"3DO Joypad"` (não `"Joypad"`)
- Cores Mednafen costumam usar `"JoyPad"` (com J e P maiúsculos)

### 27.2. Joystick físico Bluetooth não responde, mas touch sim

**Causa:** o joystick não casa o filtro de `MINIMAL_SUPPORTED_KEYS` em `LemuroidInputDeviceGamePad.kt`. Cheque se o controle reporta `KEYCODE_BUTTON_A/B/X/Y`. Para devices Bluetooth atípicos, o problema é do device, não do código — não tente "consertar" no Lemuroid.

### 27.3. BIOS aparece como ausente mesmo com arquivo presente

**Causa:** path incorreto. O `Bios(libretroFileName=...)` deve ser **exatamente** onde o core espera. Verifique se é em subpasta (`dc/dc_boot.bin`) ou raiz (`panafz1.bin`).

### 27.4. Sistema não aparece na lista do app

**Causas possíveis:**
- O sistema está em `HeavySystemFilter.MODERATE_SYSTEMS` e o device é WEAK/ULTRA_WEAK → comportamento esperado.
- A pasta `<dbname>/` não tem ROMs no diretório de ROMs do usuário → sem ROMs, sem entrada na biblioteca.
- O `dbname` no `SystemID` está diferente do nome da pasta usada no manifest/catalog. Para fins de catálogo built-in, **é importante** que sejam iguais.

### 27.5. App crasha ao abrir o jogo com `UnsatisfiedLinkError: ...so not found`

**Causa:** o `.so` está faltando para a ABI do device, OU o nome está errado. Cheque:
1. Os 4 arquivos `.so` estão em `lemuroid_core_<coreName>/src/main/jniLibs/<abi>/<filename>`.
2. O nome do arquivo bate **exatamente** com `libretroFileName` em `CoreID.kt` e começa com `lib` (a `verifyBundledCores` confere as duas coisas).
3. Se for variant `bundle`, os mesmos `.so` também em `bundled-cores/src/main/jniLibs/<abi>/`.

### 27.6. Variant `freeBundle` não inclui o core

A variant `freeBundle` usa só os `.so` em `bundled-cores`. Se você esqueceu o passo 22, o core simplesmente não estará no APK. Faça `:lemuroid-app:assembleFreeBundleDebug` para testar essa variant.

### 27.7. Sistema não aparece para usuários com app já instalado

**Causa:** `MANIFEST_SCHEMA_VERSION` não foi incrementado. O loader detecta que o schema já foi processado e pula a carga inteira — os novos jogos nunca entram no banco em updates.  
**Solução:** incremente `MANIFEST_SCHEMA_VERSION` em `ManifestQuickLoader.kt` (Passo 23). O bump força um reload one-time em todos os usuários existentes.

### 27.8. Download on-demand falha silenciosamente (toque num jogo não dispara download)

**Causa:** o valor em `mnemonico_map.json` não existe como `name` na tabela MNEMONICO do endpoint. O catálogo aparece normalmente, mas ao tocar num placeholder o `RomOnDemandManager` não consegue localizar o arquivo e falha sem mensagem visível.  
**Diagnóstico:** cheque os logs do `RomSystemMapper` / `RomOnDemandManager` e confirme o `name` exato contra a tabela MNEMONICO. Ver Passo 16.

### 27.9. Build falha com "There is not enough space on the disk"

**Causa:** C: do Windows está cheio; o Gradle escreve temp/cache no C: por padrão.  
**Fix:** use `build_apk.ps1 -Debug` (já configura `-Djava.io.tmpdir=E:/gradle_tmp`). Ou manualmente no PowerShell:
```powershell
$env:GRADLE_OPTS = "-Xmx1536m -XX:MaxMetaspaceSize=512m -Djava.io.tmpdir=E:/gradle_tmp"
```

### 27.10. Build falha com "invalid character" ou erro de resource em XML

**Causa:** `&` literal em strings XML. Sistemas com `&` no nome (ex.: "Game & Watch") precisam de `&amp;` em `strings-game-system.xml`:

```xml
<!-- ERRADO: causa erro de compilação -->
<string name="game_system_title_gw">Nintendo Game & Watch</string>
<!-- CORRETO: -->
<string name="game_system_title_gw">Nintendo Game &amp; Watch</string>
```

A abreviação também: `G&amp;W` (não `G&W`).

---

## 28. O que **não** fazer

- ❌ **Não** invente MD5 ou CRC32 de BIOS. Hashes errados fazem o `BiosDownloader` rejeitar arquivos legítimos.
- ❌ **Não** copie um descriptor de controller "achando que é parecido". Inspecione o source do core — descriptors são case-sensitive e literais.
- ❌ **Não** edite `manifest_alias.json` se não tem certeza do que faz. Esse arquivo mapeia **folder names do catálogo built-in** para `dbnames`. Só edite se o folder do catálogo for diferente do `dbname` (ex.: `megadrive` → `md`, `colecovision` → `coleco`).
- ❌ **Não** modifique `Migrations.kt`. Adicionar sistemas **não requer** migração do banco — `SystemID` é só uma enum, jogos são identificados por `systemId` (string) no DB.
- ❌ **Não** crie PNG para ícones de sistema. Use vector drawable XML.
- ❌ **Não** edite `catalog_manifest.txt`. Entradas no catálogo são geradas offline pelo script Python e só fazem sentido se você tiver covers + popularity para o sistema.
- ❌ **Não** use a task `assemblePlayDynamicDebug` — ela não existe neste checkout (variantes `play` estão desabilitadas via `beforeVariants`). Use `:lemuroid-app:assembleFreeBundleDebug`.
- ❌ **Não** use sintaxe bash (`tail -n X`, `&&`, `head -n X`) em PowerShell — use os equivalentes: `Select-Object -Last X`, `; if ($?) { ... }`, `Select-Object -First X`.

---

## 29. Quando reportar de volta ao usuário

Após terminar todos os passos, reporte:

1. **Build status:** sucesso ou erros (com a linha exata).
2. **Lista de arquivos alterados** (saída de `git status --short`).
3. **Aviso explícito** se algum passo foi pulado e por quê.
4. **Próximo passo manual do usuário:** instalar APK e testar no device. Não há como você testar input real sem device físico.

Se algo não bater (ex.: `.so` faltando, descriptor desconhecido, BIOS sem hash), **pare e peça** ao usuário — não improvise.
