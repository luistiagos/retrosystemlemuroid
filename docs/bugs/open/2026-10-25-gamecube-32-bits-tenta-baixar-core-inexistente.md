# [BUG] No APK 32 bits (TV box antiga), o GameCube aparece na lista, o jogo fica em "baixando núcleo" e termina em erro de núcleo

- **Data:** 2026-10-25
- **Status:** aberto (deduzido do código e dos binários, ainda não reproduzido no aparelho nem conferido na telemetria)
- **Severidade:** Média. O catálogo inteiro de GameCube (1.059 jogos) fica inutilizável em processo 32 bits, com uma
  mensagem que leva o usuário a achar que é falha de rede ou do app. Não há crash.
- **Complexidade:** Média (filtro de sistemas no mobile e na TV; o guard no `SystemCoreConfig` sozinho é Baixa)
- **Branch:** redesenho-tv-jogos-salvos-tela (onde foi achado)
- **Origem:** achado lateral do planejamento do Wii (`docs/backlogs/2026-10-25-suporte-nintendo-wii.md`)

---

## Sintoma (esperado pelo código)

Num aparelho que roda o APK armeabi-v7a, que é a build distribuída para TV box antiga:
1. o GameCube aparece na lista de sistemas (na TV não há filtro de RAM; no celular, aparece se o aparelho tiver mais de 2 GB);
2. ao abrir um jogo, a tela mostra "baixando núcleo";
3. a tela termina com o erro genérico de núcleo (`game_loader_error_load_core`), e não com a mensagem de
   arquitetura não suportada (`game_loader_error_unsupported_architecture`).

## Evidência

- **O Dolphin é o único core sem build 32 bits.** `comm -23 <(ls bundled-cores/src/main/jniLibs/arm64-v8a)
  <(ls .../armeabi-v7a)` → só `libdolphin_libretro_android.so`. A tag `1.21.0` (`CoreDownloader.CORES_VERSION`)
  também só tem `arm64-v8a` e `x86_64` (`git -C lemuroid-cores ls-tree -r 1.21.0 --name-only | grep dolphin`).
- **O GameCube não declara arquitetura.** O `SystemCoreConfig` do GC (`GameSystem.kt:2761-2793`) não define
  `supportedOnlyArchitectures`; o 3DS define `setOf("arm64-v8a")` (`GameSystem.kt:1901`).
- **O caminho de carga:**
  - `GameLoader.kt:83-85`: sem o campo, o `isArchitectureSupported` devolve `true`.
  - `GameLoader.kt:87-90`: `findLibrary` não acha o `.so` e lança `LoadCore`.
  - `GameViewModelRetroGameView.kt:135-150`: o `LoadCore` mostra `game_loading_download_core` e chama
    `CoreDownloader.downloadCore`.
  - `CoreDownloader.kt:71-72`: a URL montada é `…/1.21.0/lemuroid_core_dolphin/src/main/jniLibs/armeabi-v7a/libdolphin_libretro_android.so`,
    que não existe na tag (404).
  - A falha cai em `requestFailureFinish(getErrorMessage(LoadCore))`.
- **Filtros de lista:** o `HeavySystemFilter` só corta por RAM; o `TVHomeViewModel.kt:47` esconde só PSP e 3DS.

## Causa-raiz

O registro do GameCube não usou o guard de arquitetura que já existia (usado pelo 3DS), e nenhum filtro de lista
considera a ABI do processo.

## Correção (proposta)

1. `supportedOnlyArchitectures = setOf("arm64-v8a", "x86_64")` no `SystemCoreConfig` do GC. Com isso aparece a
   mensagem certa na hora, sem tentar um download inútil.
2. Esconder da lista, em processo 32 bits, os sistemas que só rodam em 64 bits. É o mesmo corte por ABI planejado
   para o Wii (Fase 1 do backlog), no mobile e no `TVHomeViewModel`. Fazer os dois juntos, e decidir se o 3DS entra
   (hoje ele mostra a mensagem certa, mas ainda aparece na lista do celular).

## Validação (a fazer)

- O AVD `lemu_tv_api25` (x86, sem `.so` de Dolphin) segue o mesmo caminho. Build com `-PdevAbi=x86`, abrir um jogo
  de GC pelo deep link, conferir o sintoma antes e a mensagem de arquitetura depois, e conferir que o sistema sumiu
  da lista.
- Telemetria: procurar `LoadCore` com `system=gc` e `abi=armeabi-v7a`.

## Lição

Core que não tem as quatro ABIs precisa de `supportedOnlyArchitectures` no registro do sistema. O guia
`docs/prompts/adicionar-novo-sistema-libretro.md` (§1) manda parar se faltar ABI, mas não diz o que fazer quando a
falta é definitiva.
