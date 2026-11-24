# [BUG] Celular 32 bits com Android Go e 2 GB (Redmi A1): PSP, NDS, N64, Dreamcast, Saturn e outros somem da lista de sistemas

**Data:** 2026-11-24
**Severidade:** media
**Complexidade:** baixa
**Branch:** version10
**Origem:** relato do dono (cliente com celular 32 bits que recebeu o APK armeabi-v7a; provavelmente o do chamado #417, `docs/bugs/aberto/2026-11-20-redmi-a1-apk-pacote-invalido/`)

---

## Sintoma

Cliente com celular 32 bits instalou o APK `armeabi-v7a` gerado para ele e "não acha o PSP" na lista de sistemas
(UI mobile). Se for o Redmi A1 do chamado #417: Android 12 **Go**, Helio A22, 2 GB de RAM.

## Evidencia

- O APK está correto. `unzip -l lemuroid-app/build/outputs/apk/freeBundle/release/lemuroid-app-free-bundle-armeabi-v7a-release.apk`
  lista `lib/armeabi-v7a/libppsspp_libretro_android.so` (17.640.600 bytes), e
  `unzip -p <apk> assets/catalog_manifest.txt | grep -c "^psp/"` = **1803**.
- `retrograde-app-shared/.../library/HeavySystemFilter.kt::deviceTier`: `totalGb > 2.0` → NORMAL;
  `am.isLowRamDevice || totalGb <= 1.0` → ULTRA_WEAK; senão WEAK.
- Android Go sempre tem `ro.config.low_ram=true` → `isLowRamDevice` → **ULTRA_WEAK** com 2 GB.
- Aparelho vendido como "2 GB" informa ~1,8 GB de `MemoryInfo.totalMem` (o kernel reserva parte) → no mínimo WEAK.

## Causa-raiz

Símbolos abertos:
- `HeavySystemFilter::deviceTier`: um único tier, usado para duas coisas diferentes: (a) **quais sistemas esconder**
  e (b) **quanto de memória o app gasta**.
- (a) esconder sistemas: `MetaSystemsViewModel` (linha 31-32, filtro na 55: lista de sistemas), `HomeViewModel`
  (linha 140: recentes/favoritos/descubra), `StreamingRomsManager::loadCatalogFromAssets` (linha 306: placeholders do
  modo legado).
- (b) memória: `LemuroidApplicationModule::retrogradeDb` (PRAGMA `cache_size`/`mmap_size`/`temp_store` por tier),
  `CoverUtils::isLowRamDevice` (`!= NORMAL`), `ShaderChooser` (`isWeakDevice` = `!= NORMAL`).

O `isLowRamDevice` é um bom sinal para (b) (economizar memória), mas é ruim para (a). Ele leva um aparelho de 2 GB
ao tier de 1 GB e esconde 15 sistemas a mais (NDS, N64, DOS, Sega CD, Dreamcast, NAOMI, Atomiswave, 3DO, Saturn,
Amiga ×4, PC-FX, Atari ST). É o mesmo erro que já tinha escondido o PSX em TV box (nota no KDoc do filtro e no CLAUDE.md).

**Decisão do dono (2026-11-24):** de início foi escolhida a opção 2 (tirar o `low_ram` só do filtro de sistemas, PSP
ainda oculto até 2 GB). No meio da implementação o dono trocou: **"não vamos mais esconder sistemas. Pode mostrar todos
sempre"**. Isso vale também para o filtro de TV (`TV_HIDDEN_SYSTEMS` = PSP e 3DS, em `TVHomeViewModel` e no
`MetaSystemsViewModel`), que não tinha motivo registrado (entrou no commit `b344f5f` `*`).

## Hipoteses descartadas

- **Core PPSSPP ausente no 32 bits**: descartada, o `.so` armeabi-v7a está no APK e no `lemuroid-cores` nas duas ABIs.
- **Catálogo do PSP fora do APK**: descartada, são 1803 linhas `psp/` no manifest empacotado.
- **Mudar o próprio `deviceTier`**: descartada. Também mudaria o tuning do SQLite (mmap 0 → 64 MB, cache 4 → 8 MB)
  e as capas num aparelho Go, onde o `low_ram` é justamente o sinal certo. A correção separa os dois usos.
- **Opções 1 (manter) e 3 (ajuste "mostrar todos os sistemas")**: não escolhidas pelo dono.

## O que fazer

1. Tirar todo filtro de sistema: `MetaSystemsViewModel` (tier de RAM + TV), `TVHomeViewModel::availableSystems` (TV),
   `HomeViewModel` (descubra/recentes/favoritos passam às queries sem `Excluding`),
   `StreamingRomsManager::loadCatalogFromAssets` (placeholders do modo legado).
2. `HeavySystemFilter`: só `DeviceTier`, `deviceTier` e `isWeakDevice`, para o tuning de memória (PRAGMA, capas,
   shaders), que continua igual, inclusive o `low_ram`. As listas de sistemas e os `excluded*` saem.
3. Atualizar a seção "Filtro de Sistemas por RAM" e o algoritmo do "Descubra" no CLAUDE.md.

## Correcao

Feito conforme "O que fazer". As queries `GameDao.select*Excluding` ficaram sem chamador e foram mantidas no DAO
(código morto, inofensivo: não muda schema).

## Validacao

- `./gradlew :lemuroid-app:compileFreeBundleDebugKotlin :lemuroid-app:testFreeBundleDebugUnitTest`: BUILD SUCCESSFUL.
- `./gradlew ktlintCheck` (completo): BUILD SUCCESSFUL, depois de regenerar o baseline de `lemuroid-app` e
  `retrograde-app-shared`. O diff do baseline só realoca passivo deslocado (`StreamingRomsManager.kt`, e as 8 entradas de
  `build.gradle.kts`, que já estavam 7 linhas fora desde um commit anterior e derrubavam o `ktlintKotlinScriptCheck`) e
  apaga as entradas dos trechos removidos.
- **Falta (`verificacao:`):** instalar o armeabi-v7a num aparelho Android Go de 2 GB (ou no `lemu_api21_1gb`, tier
  ULTRA_WEAK) e ver PSP, NDS, N64, Dreamcast e Saturn na lista de Sistemas e na Início. Na TV (`lemu_tv_api25`), ver PSP
  e 3DS na Início.

## Licao

Um mesmo tier servia a duas decisões: quanto de memória o app gasta e o que o usuário pode ver. O `low_ram` é sinal certo
para a primeira e errado para a segunda. Esconder sistema por RAM também tira do usuário uma escolha que é dele.
