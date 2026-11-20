---
movido_por: Luis Tiago - 2026-11-19 - "após corrigir , testar e validar mova o bug"
---
# [BUG] 3DS (Azahar): jogo preso em "Inputting..." ou sem abrir sem crash não aciona fallback `citra_legacy`

**Data:** 2026-11-18
**Severidade:** Alta (jogos de 3DS que funcionavam no Citra antigo travam após migração para Azahar sem fallback automático)
**Complexidade:** Média (investigação em Adreno/Azahar + mecanismo de fallback/seleção de core)
**Branch:** version10
**Origem:** Triagem do chamado `#330` (`45861677588562@lid`), Samsung Galaxy S20 FE (`SM-G780G`, Snapdragon 865 / Adreno 650, `arm64-v8a`, Android 13 SDK 33).

---

## Sintoma

1. **`Super Street Fighter IV - 3D Edition` (3DS) — regressão no Azahar sem crash:**
   - Em `2026-11-08T14:34Z` (`app=1.17.28`, core Citra antigo), o jogo abriu normalmente até o menu inicial (`[172480]`: *"DEFINIR TÍTULO / DESBLOQUEADO 0.00%"*).
   - Em `2026-11-13T02:36Z`, o operador avisou a cliente que saiu a atualização de tela única no 3DS (`[178921]`, com core `Azahar 2126.2`, versões `1.17.33`/`1.17.34`).
   - Em `2026-11-13T04:29:57Z` (`[179095]`), a cliente enviou print do *Street Fighter IV 3D* preso indefinidamente em tela preta com a caixa **"Inputting..."** e ícone circular de carregamento (*"Olá a tela do street fighter IV 3d fica assim e não entra"*).
   - Não houve nenhum report de crash nativo/Java para `system=3ds` no `SM-G780G` na telemetria após a troca para Azahar (`errors` em `1.17.34`–`1.17.35`).

2. **`Super Mario Maker for Nintendo 3DS` (`[183998]`):**
   - Em `2026-11-16T04:04:05Z` (`[183998]`), ainda na versão **`1.17.35`** (comprovada pela telemetria `errors.id=13991` às `04:02:46Z`, 79 s antes), a cliente relatou: *"Super mario maker for nintendo também não está pegando"*, também sem crash registrado em `errors`.
   - Em `2026-11-17T18:20:34Z`, foi publicada a **`1.17.36`** (`versionCode=267`, contendo o commit `9409cf3` com `CoreID.CITRA_LEGACY` como 2º core do 3DS). O operador respondeu em `[186627]`/`[186629]` (`18:25Z`), ainda sem retorno da cliente (chamado marcado `NAO TOCAR` / `REEMBOLSO`).

---

## Análise do código (confirmada)

- `GameSystem.kt` (`SystemID.NINTENDO_3DS`): define `CoreID.CITRA` (`Azahar`, `libcitra_libretro_android.so`) em primeiro lugar e `CoreID.CITRA_LEGACY` (`libcitra_legacy_libretro_android.so`) como 2º core (adicionado em `9409cf3` / `1.17.36`). Não há seletor manual de core para 3DS na UI.
- `GameLaunchTaskHandler.kt` (`onActivityResult` e `tryFallbackCore`): `tryFallbackCore` só é chamado quando `:game` encerra com `RESULT_UNEXPECTED_ERROR` (e `!isCoreStall && isEmulatorFailure`) ou `RESULT_ERROR`.
- **Consequência estrutural:** Quando o `Azahar` trava em boot/diálogo/tela preta ou `"Inputting..."` sem abortar o processo (ou quando o usuário aperta Back/sai normalmente, ou quando `isCoreStall` é `true`), `tryFallbackCore` **nunca dispara**, impedindo o fallback para `CITRA_LEGACY` introduzido na `1.17.36`.

---

## Hipoteses — nenhuma verificada

- **H1 (Azahar `Inputting...` / `SSF4 3D`):** O diálogo `"Inputting..."` é o `SoftwareKeyboard`/diálogo de nome de perfil/Mii do applet HLE do 3DS no Azahar que pode estar travando sem resposta de entrada/teclado ou aguardando sysdata migrado em `saves/Azahar/`. Não reproduzido localmente.
- **H2 (`Super Mario Maker for Nintendo 3DS` em `1.17.35`):** Pode sofrer crash nativo silencioso no `Azahar` (neste caso `1.17.36` acionaria `CITRA_LEGACY` no reteste) ou congelar/ficar em tela preta na inicialização por falta de Mii/sysdata/incompatibilidade no Adreno 650 (neste caso `1.17.36` também não acionaria o fallback). Aguarda resposta ao `[186629]` ou reprodução local.

---

## Correção

1. **Política de fallback e seleção de core por jogo:**
   - `CoreID.kt`: adicionado `isDeprecated: Boolean = false` (marcado como `true` em `DESMUME`) para distinguir cores legados válidos para fallback (como `CITRA_LEGACY`) de cores obsoletos.
   - `CoresSelection.kt`: adicionada persistência e consulta de core por jogo (`getCoreConfigForGame`, `updateCoreConfigForGame`, `clearCoreConfigForGame`) e controle de descarte do prompt de fallback por jogo/core (`isFallbackPromptDismissed`, `dismissFallbackPrompt`, `clearFallbackPromptDismissed`, `clearAllFallbackPromptsDismissed`).
   - `CoreFallbackPolicy.kt`: criada política pura (`findNextFallbackCore`, `evaluateExitPrompt`, `shouldPersistFallbackCore`, `FallbackPromptReason`) cobrindo três cenários suspeitos em saída normal (`RESULT_OK`): core travado na saída (`CORE_STALLED_ON_EXIT`), nenhum frame renderizado após janela mínima (`NO_FRAME_RENDERED`) e sessão muito curta (`SHORT_SESSION`).
   - `CoreCrashFallback.kt`: adicionados `findNativeCrashSince` e `markExitHandled` para detectar crash nativo via `ApplicationExitInfo` no fluxo de saída `RESULT_CANCELED` quando `:game` é encerrado abruptamente.

2. **Rastreamento de execução e troca de core no processo `:game`:**
   - `GameViewModelRetroGameView.kt`, `GameViewModelSaves.kt`, `BaseGameScreenViewModel.kt`, `GameViewModelSideEffects.kt`, `GameMenuContract.kt` e `BaseGameActivity.kt`: rastreamento do primeiro frame (`firstFrameRendered` / `PLAY_GAME_RESULT_FIRST_FRAME_RENDERED`), `fastFailIfStalled` no save de saída ao trocar de core quando o core está travado, e resultado `RESULT_SWITCH_CORE` com `PLAY_GAME_RESULT_TARGET_CORE_ID`.
   - `GameLauncher.kt`, `ExternalGameLauncherActivity.kt` e `GameLaunchTaskHandler.kt`: registro do lançamento ativo (`recordActiveLaunch` / `ActiveLaunch`), tratamento de `RESULT_SWITCH_CORE`, fallback automático em `RESULT_CANCELED` por crash nativo e em `isCoreStall` (quando o próximo core não é deprecated), persistência automática do core de fallback após sucesso com renderização, e exibição de diálogo interativo (`showFallbackCoreDialog` / `LemuroidTVDialog` na TV) em saídas `RESULT_OK` suspeitas perguntando se o usuário deseja testar o próximo core (ex.: `Citra`).

3. **Seletores manuais na UI (Mobile e TV):**
   - Menu in-game (`GameMenuHomeScreen.kt`, `tv_game_settings.xml`, `GameMenuHelper.kt`, `TVGameMenuFragment.kt`): opção de topo para alternar core (`game_menu_switch_core`) em sistemas com múltiplos cores.
   - Menu de contexto da biblioteca (`MainGameContextActions.kt`, `MainActivity.kt`, `GameInteractor.kt`, `GameContextMenuListener.kt`, `MainTVActivity.kt`): ação por jogo para escolher/alterar o core sem precisar mudar o core global do sistema.
   - `CoresSelectionScreen.kt`: sincronização de estado por `key(system.id)` e `LaunchedEffect(core.coreID)` quando um fallback altera o core selecionado.

4. **Observação sobre as hipóteses:**
   - Para H2 (falta de Mii `CFL_DB.dat` em `Super Mario Maker for Nintendo 3DS`), o provisionamento automático via `ThreeDsMiiProvisioner` já havia sido introduzido no commit `19cacda`.
   - A causa interna do diálogo `"Inputting..."` no Azahar em Adreno 650 (H1) não foi reproduzida sem dispositivo físico equivalente; esta correção resolve a falha estrutural permitindo fallback automático e troca manual para `CITRA_LEGACY` em travamentos silenciosos, crashes nativos ou saídas sem renderização.

---

## Validação

- **Testes unitários e análise estática (após as alterações finais):**
  - `CoreFallbackPolicyTest.kt`: 8 novos testes cobrindo `findNextFallbackCore`, `evaluateExitPrompt`, `shouldPersistFallbackCore`, escopo por jogo em `CoresSelection` e escopo de descarte do prompt de fallback (8/8 passando).
  - `.\gradlew.bat ktlintCheck :lemuroid-app:compileFreeBundleDebugKotlin :lemuroid-app:testFreeBundleDebugUnitTest :lemuroid-app:lintFreeBundleDebug`: aprovado (`BUILD SUCCESSFUL`), com `110` testes no `:lemuroid-app:testFreeBundleDebugUnitTest` (`0` falhas, `0` erros, `1` ignorado preexistente em `WriteAtomicallyTest` no Windows).
- **Pendência para `testado/`:** validação E2E em dispositivo Android / emulador (`adb devices` vazio no ambiente atual), incluindo reteste de fluxo de fallback e seletor de core em runtime.
