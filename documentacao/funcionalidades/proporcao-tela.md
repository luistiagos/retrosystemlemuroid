# Proporção da tela

Preferência que escolhe **com que proporção a imagem do jogo é desenhada**: `Automática (do jogo)`,
`4:3`, `16:9`, `19:9`, `20:9`, `21:9` ou `Esticar para preencher`.

Até esta feature o Lemuroid sempre preservava a proporção do sistema emulado. Num Mega Drive
(4:3) rodando em celular 20:9 isso deixa ~30% da largura em preto — comportamento correto do
renderer, não um bug, mas não havia como escolher outro.

> Isto aqui encaixa a imagem **depois de renderizada**, então toda proporção mais larga que a do
> jogo cobra o preço de distorcer. Quando o core é 3D existe uma saída sem preço — fazer o jogo
> renderizar mais cena: ver [widescreen-cores-3d.md](widescreen-cores-3d.md).

---

## Por que uma lista de proporções, e não modos de encaixe

A primeira versão desta feature expunha três *modos* — original / esticado / zoom-com-corte.
Levantamento dos emuladores de Android estabelecidos mostrou que **ninguém faz assim**:

| Emulador | O que expõe |
|----------|-------------|
| DuckStation | `Auto (Game Native)`, `Stretch To Fill`, `4:3`, `16:9`, **`19:9`, `20:9`, `21:9`**, `16:10`, `PAR 1:1` |
| Dolphin | `Auto`, `Force 16:9`, `Force 4:3`, `Stretch to Window` |
| PCSX2 / ARMSX2 | `Stretch`, `Auto 4:3/3:2`, `4:3`, `16:9`, `10:7` |
| RetroArch | ~20 proporções fixas + `Core Provided` + `Custom` (viewport livre) |

Duas conclusões que mudaram o desenho:

1. **"Original" e "preencher" são casos particulares de uma proporção alvo, não modos.**
   Escolher a proporção do core = original; escolher a da área de jogo = preencher. Uma lista
   cobre os dois e ainda permite alvos intermediários, que os modos não permitiam.

2. **Zoom com corte praticamente não existe no mercado.** O que existe é *corte de overscan*
   (`DisplayCropMode` no DuckStation), que remove borda sabidamente inútil em vez de comer
   imagem útil — coisa diferente e melhor definida. O zoom foi removido.

As proporções de celular (19:9, 20:9, 21:9) estão na lista pelo mesmo motivo que o DuckStation
as tem: combinadas com widescreen no core, o jogo passa a renderizar exatamente na forma da tela.

---

## Onde a decisão acontece

**Não é no app.** O encaixe é feito no renderer nativo do LibretroDroid, em
`VideoLayout::updateForegroundVertices` (`libretrodroid/src/main/cpp/videolayout.cpp`).

> ⚠️ Isso significa que **a feature exige rebuild da AAR** (`libs/libretrodroid-patched.aar`).
> O código-fonte vive no repositório separado `C:\projects\lemuroid\LibretroDroid-patched`.
> Ver "Como reconstruir a AAR" no fim deste arquivo.

### A conta

O encaixe original do LibretroDroid usa a proporção que o core reporta:

```cpp
if (contentAspect > screenAspect) { scaleY *= screenAspect / contentAspect; }
else                              { scaleX *= contentAspect / screenAspect; }
```

A mudança é de uma linha conceitual: **`contentAspect` deixa de ser obrigatoriamente a do core**.

```cpp
float contentAspect = aspectRatio;                                       // AUTO: a do core
if (targetAspect == TargetAspect::FILL)  contentAspect = screenAspect;   // preenche
else if (targetAspect > 0.0F)            contentAspect = targetAspect;   // explícita
```

O encaixe em si fica **intocado**. É exatamente o que o `CalculateDrawDstRect` do PCSX2 faz com
o `targetAr`, e o que o "Force 16:9" do Dolphin faz.

**Contrato do valor** (`TargetAspect`, em `videolayout.h`):

| Valor | Significado |
|-------|-------------|
| `0` (`AUTO`) | usa a proporção reportada pelo core |
| `-1` (`FILL`) | usa a proporção do viewport — preenche distorcendo |
| `> 0` | proporção explícita (`4:3` = 1,3333; `16:9` = 1,7778; …) |

O JNI normaliza qualquer outro negativo para `AUTO`, que é o comportamento histórico e nunca
esconde parte da imagem.

### Por que não há mais scissor

O encaixe é sempre **"contain"**: o quad cabe no viewport em toda configuração, porque a única
coisa que muda é qual proporção alimenta a conta — nunca o sinal da correção. Com nada
transbordando não há excedente para confinar, e o `GL_SCISSOR_TEST` que a versão de modos
precisava foi removido junto com o `getViewportPixels()`.

Isso é consequência direta do desenho de mercado: preencher por *corte* é o único caso que gera
transbordo, e nenhum dos emuladores levantados oferece esse caso.

---

## Arquivos

### LibretroDroid-patched (nativo — exige rebuild da AAR)

| Arquivo | Mudança |
|---------|---------|
| `cpp/videolayout.h` | `namespace TargetAspect` (AUTO/FILL), campo `targetAspect`, `updateTargetAspectRatio` |
| `cpp/videolayout.cpp` | proporção alvo alimentando o encaixe |
| `cpp/video.h` / `video.cpp` | repasse do valor |
| `cpp/libretrodroid.h` / `.cpp` | campo `targetAspect` + `setTargetAspectRatio` |
| `cpp/libretrodroidjni.cpp` | `Java_..._setTargetAspectRatio(jfloat)` |
| `java/…/LibretroDroid.java` | `TARGET_ASPECT_AUTO` / `TARGET_ASPECT_FILL` + native |
| `java/…/GLRetroViewData.kt` | `var targetAspectRatio: Float` |
| `java/…/GLRetroView.kt` | propriedade + aplicação em `onCreate` |

O campo mora em `LibretroDroid` (não só em `Video`) pelo mesmo motivo do `viewportRect`: o
`Video` é recriado a cada carga de jogo e lê o valor de volta do singleton.

### Lemuroid

| Arquivo | Mudança |
|---------|---------|
| `app/shared/settings/ScreenAspectRatio.kt` | enum das proporções → `Float` do contrato |
| `mobile/feature/settings/SettingsManager.kt` | `screenAspectRatio()` |
| `mobile/feature/settings/general/SettingsScreen.kt` | `LemuroidSettingsList` (mobile) |
| `res/xml/tv_settings.xml` | `ListPreference` (TV) |
| `res/values/keys.xml` | `pref_key_screen_aspect_ratio` + arrays |
| `res/values/strings.xml`, `values-pt-rBR/strings.xml` | títulos e rótulos |
| `shared/game/viewmodel/GameViewModelRetroGameView.kt` | lê a pref e grava em `GLRetroViewData` |

---

## Aplicação do valor (e por que não via `runOnGLThread`)

`GLRetroView.onCreate` chama `LibretroDroid.setTargetAspectRatio(data.targetAspectRatio)` logo
após `create()`, na main thread. Nesse ponto o `Video` nativo ainda é `nullptr` — a chamada só
grava o campo do singleton, sem tocar em estado de GL e sem round-trip para a GLThread.

A propriedade `GLRetroView.targetAspectRatio` (troca em runtime) usa `queueEvent`,
fire-and-forget, pela mesma razão documentada no `viewport`: `runOnGLThread` bloqueia num
`CountDownLatch` e a GLThread só drena a fila entre callbacks do renderer — durante
`retro_load_game()` isso é ANR garantido em ROM grande.

A preferência é lida uma vez, em `GameViewModelRetroGameView.initialize()`. Mudar a configuração
vale a partir do **próximo** jogo aberto, igual ao `screenFilter`.

---

## Validação

⚠️ **Este desenho ainda não foi testado em aparelho.** A validação da versão anterior (três modos)
não vale para ele e foi removida em vez de reaproveitada.

A conferir quando houver aparelho conectado:

- `Automática` idêntica ao comportamento anterior à feature (pillarbox do 4:3);
- `Esticar para preencher` sem barra nenhuma;
- `20:9` num aparelho 20:9 visualmente idêntico a `Esticar para preencher`;
- `16:9` deixando barra fina num aparelho 20:9;
- em retrato, com controles na tela, nenhuma proporção invadindo a área dos controles.

---

## Como reconstruir a AAR

```bash
cd C:/projects/lemuroid/LibretroDroid-patched
JAVA_HOME=D:/DevCaches/jdk-17 ./gradlew :libretrodroid:assembleRelease --no-daemon
cp libretrodroid/build/outputs/aar/libretrodroid-release.aar \
   C:/projects/lemuroid/Lemuroid/libs/libretrodroid-patched.aar
```

- `local.properties` (não versionado) precisa de `sdk.dir` **e** `ndk.dir`. Nesta máquina:
  `D:/DevCaches/Android/Sdk` e `.../ndk/29.0.14206865`.
- Para checagem rápida de compilação existe `-PldAbi=arm64-v8a` (uma ABI só, ~3 min em vez de
  ~12). **Nunca** empacotar uma AAR gerada assim: falta armeabi-v7a, que é o que roda nas TV
  box antigas.
