# [BUG] Sem opção de tela cheia — imagem do jogo fica com barras pretas grandes em celular moderno

**Data:** 2026-08-20
**Status:** ✅ Resolvido — 3ª iteração do design validada no device em 2026-09-03 (Moto G86 5G,
os cinco critérios do roteiro conferidos por captura de tela).
**Severidade:** Média (não quebra nada; é ausência de controle que o usuário pediu e o mercado
já resolveu)
**Branch:** version9

---

## Sintoma

Usuário mandou foto de um jogo (Bare Knuckle/Streets of Rage-like, Mega Drive) rodando num
celular encaixado num grip de controle — a imagem do jogo ocupa só a faixa central da tela,
com barras pretas grandes dos dois lados. Pergunta: *"Como faz para deixar tela cheia?"*

Celular de teste (Moto G86 5G): 2712×1220 ≈ 20:9. Mega Drive é 4:3 (≈1,33). A diferença de
proporção é grande — em qualquer tela moderna (19:9 a 21:9) sobra ~30-45% de largura em preto
com qualquer sistema 4:3 (a maioria do catálogo: Mega Drive, SNES, NES, NeoGeo, Saturn 2D...).

Não havia **nenhuma** preferência no app para isso. O comportamento (preservar a proporção do
sistema, "contain fit") sempre foi assim e é o correto por padrão — o problema é a ausência de
alternativa.

## Causa-raiz

**A decisão não é do app.** O encaixe da imagem na tela é feito no renderer nativo do
LibretroDroid, em `VideoLayout::updateForegroundVertices`
(`libretrodroid/src/main/cpp/videolayout.cpp`, no repositório separado
`C:\projects\lemuroid\LibretroDroid-patched`), que calcula os vértices do quad final em NDC.

O único controle de geometria que a AAR expunha ao Kotlin era `GLRetroView.viewport: RectF`, e
ele **não serve** para esticar — provado algebricamente: nos dois ramos do encaixe original,

```cpp
if (contentAspect > screenAspect) { scaleY *= screenAspect / contentAspect; }
else                              { scaleX *= contentAspect / screenAspect; }
```

a razão final é a mesma constante nos dois casos (`scaleX/scaleY == contentAspect · screenH /
screenW`), ou seja, **qualquer** `viewport` produz escala uniforme. Dá para conseguir corte
mandando um rect maior que a tela, mas nunca distorção.

Conclusão: qualquer forma de "tela cheia" real exige **patch no C++ e rebuild da AAR**. Não dá
para resolver só no lado do app.

---

## O que foi feito — histórico das três iterações

### Iteração 1 — três "modos" de encaixe (`FIT` / `STRETCH` / `ZOOM`)

Primeira tentativa: `enum class ScaleMode` no C++ (`videolayout.h/cpp`, `video.h/cpp`,
`libretrodroid.h/cpp`, JNI) + plumbing até `GLRetroView.scaleMode` no Kotlin.

- `FIT` = comportamento histórico (preserva proporção, sobra barra).
- `STRETCH` = preenche o viewport inteiro distorcendo (não faz correção nenhuma).
- `ZOOM` = preserva proporção e **cresce** até encostar na borda, cortando o excedente — o quad
  passa a ultrapassar o viewport de propósito.

Como o `ZOOM` transborda o viewport, foi adicionado `GL_SCISSOR_TEST` no passe final do
`Video::renderFrame`, usando um novo `VideoLayout::getViewportPixels()` para confinar o corte à
área de jogo (senão o excedente vazaria pela tela inteira, incluindo por cima dos controles de
toque em alguns layouts).

Preferência "Proporção da tela" adicionada no app: `ScreenScaleMode` enum, `SettingsManager`,
`SettingsScreen` (mobile), `tv_settings.xml` (TV), strings en + pt-BR.

**Build e teste:** AAR reconstruída (4 ABIs), instalada, app compilado, instalado no Moto G86
via `adb`. Testado visualmente com KOF'98 (NeoGeo 320×224, 4:3) em paisagem: os três modos
funcionaram como esperado (capturas de tela comparando os três, incluindo `ZOOM` em retrato
confirmando que o scissor não deixava a imagem vazar sobre os controles).

Documentado em `documentacao/funcionalidades/proporcao-tela.md` (versão 1).

> Nesta iteração também escrevi uma justificativa **errada** para o porquê do scissor (disse que
> o caso de risco era retrato; o usuário perguntou e, ao reabrir a conta, o caso de risco real é
> **paisagem sem overlay**, com o game view espremido entre os dois pads — o eixo que transborda
> depende da proporção do **viewport**, não da tela). Corrigi o texto da doc na hora, mas o
> desenho em si (scissor + zoom-com-corte) seguiu adiante até a iteração 3.

### Feedback do usuário: "não é isso que é necessário"

> *"temos um problema... o scissor está cortando a viewport do jogo, e o zoom esticando ou
> comprimindo ela. porém ambos não fazem o que é necessário, talvez ajustar a resolução seria o
> ideal. Inclui o projeto (C:\projects\ARMSX2)... veja como ele faz o fullscreen"*

### Investigação do ARMSX2 (PCSX2 Android)

`GSRenderer.cpp::CalculateDrawDstRect` faz **a mesma conta** de encaixe uniforme que o
LibretroDroid — mesmo formato de fórmula, com `targetAr` no lugar de `contentAspect`. O
`AspectRatioType::Stretch` do PCSX2 é literalmente o nosso `STRETCH`.

O que realmente faz um jogo de PS2 encher a tela **sem distorcer** são os **widescreen
patches** (`Patch.cpp::ApplyPatchSettingOverrides` / `s_override_aspect_ratio`) — código que
reescreve a **câmera/FOV do jogo na memória**, fazendo-o renderizar mais cena. É um hack por
jogo, não um truque de display. `resolution_scales` (1x-4x) do ARMSX2 só afeta nitidez, não
proporção — mesma conclusão para "resolução" no Lemuroid.

### Iteração 2 — opções de widescreen por core (Dreamcast, N64)

Traduzindo o achado do ARMSX2 para libretro: cores 3D expõem hacks de widescreen como **core
option**. Verificado nos binários reais (nunca por suposição — regra do projeto):

```bash
adb shell run-as app.retrogamesystem.debug cat files/cores/1.19.0/<core>.so > core.so
"$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strings.exe" -n 6 core.so | grep -E "^<prefixo>_..."
```

- **Flycast** (Dreamcast): `reicast_widescreen_hack` / `reicast_widescreen_cheats` — confirmado
  também no fonte local (`C:\projects\lemuroid\flycast_src\core\libretro\libretro_core_options.h:303`).
- **mupen64plus-next** (N64): `mupen64plus-aspect` (`4:3` / `16:9 adjusted` / `16:9`) +
  `mupen64plus-169screensize`. A descrição do próprio core confirma: *"'adjusted' means
  essentially Widescreen hacks."*
- Verificado que **não existe** em `pcsx_rearmed` (PSX), `ppsspp` (PSP já é ~16:9 nativo),
  `opera` (3DO).
- **Dolphin (GameCube) ficou pendente** nesta iteração — o device desconectou no meio da
  extração.

`ExposedSetting` adicionadas em `GameSystem.kt` (N64 e Dreamcast) + strings en/pt-BR. Só
Kotlin, sem rebuild de AAR. `BUILD SUCCESSFUL`. **Não testado visualmente** (sem ROM de DC/N64
no device na hora). Documentado em `documentacao/funcionalidades/widescreen-cores-3d.md`.

### Pedido de pesquisa de mercado

> *"vamos ver como os emuladores de android já do mercado lidam com isso... veja se a nossa
> abordagem é a melhor"*

Levantamento (fontes no fim deste arquivo):

| Emulador | Proporção | Corte de borda | Widescreen real |
|---|---|---|---|
| DuckStation | Auto (Game Native), **Stretch To Fill**, 4:3, 16:9, **19:9, 20:9, 21:9**, 16:10, PAR 1:1 | `DisplayCropMode` (None / Overscan / All Borders) | hack (só 3D) |
| Dolphin | Auto, Force 16:9, Force 4:3, Stretch to Window | — | Widescreen Hack (doc oficial **desaconselha**: "rarely produces good results") |
| RetroArch | ~20 razões fixas + Core Provided + Custom (viewport livre) | via core option | via core option |
| PCSX2/ARMSX2 | Stretch, Auto 4:3/3:2, 4:3, 16:9, 10:7 | via `src_rect` | patches (câmera) |

Achados que mudaram o desenho:

1. **Nenhum emulador de mercado usa "modos" de encaixe.** Todos usam uma **lista de proporções
   alvo**, onde "original" (proporção do core) e "preencher" (proporção da tela) são só dois
   itens da lista, não conceitos separados.
2. **"Zoom com corte arbitrário" praticamente não existe no mercado.** O que existe é *corte de
   overscan* — remove borda **sabidamente inútil** definida pelo core, não corta imagem útil.
   É um recurso diferente e mais restrito do que o nosso `ZOOM`.
3. Conferido nos próprios cores 2D do Lemuroid que esse recurso já existe no libretro e não é
   exposto: `genesis_plus_gx_overscan`, `genesis_plus_gx_aspect_ratio` (aspecto reportado pelo
   core — ex. "Uncorrected" dá 10:7 em vez de 4:3, sem distorcer nem cortar, de graça),
   `fceumm_overscan_h`/`_v` (+ por borda), `snes9x_overscan`. **Não implementado ainda** — ver
   pendências no fim.

Veredicto entregue ao usuário: a base (STRETCH = "Stretch To Fill" do mercado, widescreen via
core option = "Widescreen Hack" do Dolphin) estava alinhada, mas o `ZOOM` era o item errado do
conjunto e a modelagem em "modos" era pior que a modelagem em "lista de proporções" que todo o
mercado convergiu.

### Iteração 3 — redesenho para lista de proporções-alvo (estado atual)

> *"sim refaça o desenho, seguindo o padrão adotado nos demais do mercado"*

`ScaleMode` (enum de 3 modos) foi **removido inteiramente** do C++ e do Kotlin. No lugar,
`namespace TargetAspect` com contrato de `float`:

| Valor | Significado |
|---|---|
| `0` (`AUTO`) | usa a proporção que o core reporta — comportamento histórico |
| `-1` (`FILL`) | usa a proporção do viewport — preenche distorcendo |
| `> 0` | proporção explícita (`4:3` = 1,3333 ; `16:9` = 1,7778 ; ...) |

A conta do encaixe (`videolayout.cpp`) ficou **intocada** — só `contentAspect` deixou de ser
obrigatoriamente a do core:

```cpp
float contentAspect = aspectRatio;
if (targetAspect == TargetAspect::FILL)  contentAspect = screenAspect;
else if (targetAspect > 0.0F)            contentAspect = targetAspect;
```

**Consequência direta: o encaixe volta a ser sempre "contain"** — o quad nunca ultrapassa o
viewport em nenhuma configuração, porque a única coisa que muda é qual proporção alimenta a
conta, nunca o sinal da correção. Sem transbordo, não há o que confinar: `GL_SCISSOR_TEST` e
`getViewportPixels()` foram **removidos por completo**. É a resposta direta à reclamação
original do usuário sobre o scissor.

`Java_..._setScaleMode(int)` virou `Java_..._setTargetAspectRatio(jfloat)`; `GLRetroView.kt`,
`GLRetroViewData.kt`, `LibretroDroid.java` atualizados na mesma linha.

No app: `ScreenScaleMode.kt` deletado, `ScreenAspectRatio.kt` criado com 7 valores —
`AUTO`, `4:3`, `16:9`, `19:9`, `20:9`, `21:9`, `FILL` — mapeando pro `Float` do contrato nativo.
Preferência, strings (en + pt-BR) e telas (mobile + TV) atualizadas.

**Build:**
- AAR: `BUILD SUCCESSFUL in 1m50s` (4 ABIs). Verificado por `llvm-nm` que
  `Java_..._setTargetAspectRatio` está exportado e `setScaleMode` **sumiu** do `.so`.
- App: `BUILD SUCCESSFUL in 5m9s`. Verificado que o `.so` novo (com o símbolo novo) está
  **dentro do APK** empacotado (extraído do zip do APK e conferido com `llvm-nm`).
- Sem resíduo de `ScaleMode`/`scale_mode`/`SCALE_MODE` em nenhum dos dois repositórios
  (`grep` limpo).

`documentacao/funcionalidades/proporcao-tela.md` **reescrita do zero** para este desenho — a
seção de validação da versão anterior foi apagada (não reaproveitada) porque medir três modos
não tem correspondência com medir uma lista de proporções. `index.md` atualizado.

---

## Validação no device (2026-09-03) — os cinco critérios, conferidos

**Aparelho:** Moto G86 5G, 2712×1220 (≈ 20:9), Android 16, app `1.17.12-DEBUG` (versionCode 243),
AAR `libretrodroid-patched.aar` com `setTargetAspectRatio` presente no `.so` de arm64-v8a e o
`TARGET_ASPECT_AUTO`/`_FILL` no `LibretroDroid.class`.

**Jogo de teste:** *Super Mario World* (SNES / snes9x) — 4:3, a mesma classe de proporção do
Mega Drive da foto do usuário.

A preferência aparece em Configurações → **Proporção da tela** com as sete opções, grava
`screen_aspect_ratio` no Harmony (`files/harmony_prefs/harmony_options/`) e vale a partir do
próximo jogo aberto, como projetado.

| Critério do roteiro | Medido na captura (landscape, área de jogo 2000×900 px na escala da imagem) | Resultado |
|---|---|---|
| `Automática` reproduz o pillarbox original | quad de 1200×900 = **1,333** — barras pretas largas dos dois lados, idêntico à foto do usuário | ✅ |
| `Esticar para preencher` sem barra nenhuma | quad de 2000×900, imagem visivelmente esticada na horizontal | ✅ |
| `20:9` ≡ `Esticar` num aparelho 20:9 | as duas capturas são visualmente indistinguíveis (diferem só no gradiente animado de fundo) | ✅ |
| `16:9` deixa barra fina num 20:9 | quad de 1600×900 = **1,778** — barra fina simétrica | ✅ |
| Em retrato, nenhuma proporção invade os controles | com `Esticar`, o quad ocupa toda a área de jogo e **para exatamente na borda do pad**; `Automática` fica letterboxed dentro da mesma área | ✅ |

Capturas em `tmp/v-smw-*.png` (`auto`, `169`, `209`, `fill` × `portrait`/`land`).

**Achado extra, não previsto no roteiro:** o valor **sobrevive à rotação em jogo**. Todos os
testes foram feitos abrindo o jogo em retrato e girando para landscape com o jogo rodando — o
encaixe se refaz na nova viewport mantendo a proporção alvo. Era o esperado (o alvo mora no
singleton nativo `LibretroDroid`, não no `Video`, que é recriado), mas agora está observado.

**Saída do jogo:** limpa em todas as sessões (`System.exit ... status: 0`, `Process exited
cleanly (0)`), sem `GLThreadTimeoutException` — ver [[2026-08-09-anr-inicializar-jogo-runongl-thread]].

## Pendências que não são bug, são escopo cortado

- **Dolphin (GameCube) tem as opções e não estão ligadas.** Verificado nesta sessão via
  `llvm-strings` no `.so` extraído do APK: `dolphin_widescreen_hack`, `dolphin_widescreen`,
  `dolphin_aspect_ratio`, `dolphin_crop_overscan` existem no core. Não entraram em
  `GameSystem.kt` — a sessão virou foco para o redesenho antes de eu voltar nisso.
- **Corte de overscan nos cores 2D não foi implementado.** `genesis_plus_gx_overscan`,
  `genesis_plus_gx_aspect_ratio`, `fceumm_overscan_h`/`_v` (+ por borda), `snes9x_overscan` —
  identificados na pesquisa de mercado como o análogo real do que os emuladores de mercado
  chamam de "crop", e ainda não expostos. Só Kotlin, não exige rebuild de AAR.
- Nenhum commit foi feito — a instrução do projeto é nunca commitar sem pedido explícito, e não
  houve pedido.

## Lição

- `viewport: RectF` sozinho **nunca** produz distorção — provado por álgebra, não por teste. Boa
  lição para não gastar ciclo de implementação tentando resolver no lado do app algo que só pode
  ser resolvido no renderer nativo.
- A matemática de encaixe "contain" é praticamente idêntica em bases de código não-relacionadas
  (LibretroDroid, PCSX2/ARMSX2) — é problema convergido na indústria. **Pesquisar prior art antes
  de desenhar UI nativa nova custa muito menos que implementar, levar feedback de "não é isso" e
  redesenhar.** Esta sessão pagou o preço de um ciclo completo de rebuild de AAR (iteração 1) que
  a pesquisa de mercado (feita só depois, por pedido do usuário) teria evitado.
- "Zoom com corte arbitrário" não é o que produtos de mercado implementam; *overscan crop*
  (limitado, definido pelo core) é uma coisa diferente e mais estreita. Confundir os dois gerou
  um desenho (e um scissor) que estava na direção errada — a comparação com mercado pegou isso
  antes de virar validação.
- Regra do projeto (`CLAUDE.md`, "Não escrever código sobre símbolo que não foi aberto") se
  aplica direto a core options: uma `ExposedSetting.key` que não bate com o que o core declara
  **não gera erro nenhum** — a opção só some do menu, em silêncio
  (`BaseGameActivity.transformExposedSetting` retorna `null`). Toda chave escrita nesta sessão
  foi conferida contra o `.so` real ou o fonte upstream antes de entrar em `GameSystem.kt`.
- Larguras 4:3 → tela de celular moderna têm teto físico: sistema 2D (framebuffer de tamanho
  fixo, sem câmera) só pode distorcer ou ficar como está — "mais largo sem distorcer" só existe
  onde há câmera para abrir (core 3D com hack de widescreen). Não é lacuna de implementação, é
  limite do hardware original sendo emulado.

## Fontes da pesquisa de mercado

- [DuckStation `settings.cpp`](https://raw.githubusercontent.com/stenzek/duckstation/master/src/core/settings.cpp)
- [Genesis-Plus-GX `libretro_core_options.h`](https://raw.githubusercontent.com/libretro/Genesis-Plus-GX/master/libretro/libretro_core_options.h)
- [Aspect ratio hacks — Emulation General Wiki](https://emulation.gametechwiki.com/index.php/Aspect_ratio_hacks)
- [Android: Support WideScreen Hack — dolphin-emu commit 95662fa](https://github.com/dolphin-emu/dolphin/commit/95662fa235540eebdd82c10052ec528978f45e96)
- [RetroArch configuration](https://www.retroarch.com/configuration.php)
- [PPSSPP issue #18693 — preserve fullscreen aspect ratio](https://github.com/hrydgard/ppsspp/issues/18693)

## Referência técnica

O "como funciona" completo do desenho atual (contrato do valor, arquivos tocados, por que não
há mais scissor, como reconstruir a AAR) está em
[`documentacao/funcionalidades/proporcao-tela.md`](../../funcionalidades/proporcao-tela.md) e
[`documentacao/funcionalidades/widescreen-cores-3d.md`](../../funcionalidades/widescreen-cores-3d.md).
Este arquivo é o registro da investigação e das decisões.
