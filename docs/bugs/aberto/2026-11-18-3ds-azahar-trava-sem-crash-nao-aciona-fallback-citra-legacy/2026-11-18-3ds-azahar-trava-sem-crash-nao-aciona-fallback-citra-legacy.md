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
