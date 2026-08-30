# Widescreen nos cores 3D

Expõe, no menu de opções do jogo, as opções que fazem o **core renderizar imagem mais larga** —
em vez de esticar ou cortar a imagem 4:3 depois de pronta.

Complementa [proporcao-tela.md](proporcao-tela.md), que resolve o mesmo sintoma pelo lado do
display. As duas coisas são diferentes e se somam:

| | O que faz | Custo |
|---|---|---|
| [Proporção da tela](proporcao-tela.md) | Encaixa a imagem **já renderizada** na tela | Distorce (esticado) ou esconde (zoom) |
| **Widescreen (este arquivo)** | Faz o jogo **renderizar mais cena** | Nenhum — mas só funciona em core 3D |

---

## Por que só funciona em core 3D

De 4:3 para 20:9 faltam 45% de largura. Existem exatamente três saídas: distorcer, cortar, ou
a fonte gerar imagem mais larga. A terceira é a boa, e depende do jogo desenhar a cena a partir
de uma **câmera** — dá para alargar o frustum e revelar mais mundo dos lados.

Console 2D não tem câmera. Mega Drive, SNES, NeoGeo, Master System e afins têm hardware de
vídeo que emite um framebuffer de tamanho fixo (320×224 e variantes), montado a partir de
planos de tiles. Não existe "mais cena" para revelar, e nenhuma resolução interna muda isso —
resolução interna deixa a imagem **mais nítida**, nunca mais larga. Para esses sistemas, o
único caminho é escolher entre distorcer e cortar, na preferência de proporção.

> É o mesmo desenho do PCSX2/ARMSX2: o `CalculateDrawDstRect` dele faz o mesmo encaixe uniforme
> que o LibretroDroid, e o `AspectRatioType::Stretch` é literalmente o nosso `STRETCH`. O que
> faz jogo de PS2 encher a tela sem distorcer são os **widescreen patches**, que reescrevem
> a câmera do jogo na memória e só então setam `EmuConfig.CurrentCustomAspectRatio`
> (`Patch::ApplyPatchSettingOverrides`). O display não inventa nada.

---

## Opções expostas

Chaves e valores conferidos **no core de verdade**, não de memória: Flycast pelo fonte em
`C:\projects\lemuroid\flycast_src\core\libretro\libretro_core_options.h`, mupen64plus-next
por `llvm-strings` no `.so` baixado do aparelho.

### Dreamcast — Flycast (`CORE_OPTION_NAME` = `reicast`)

| Chave | Onde | Valores | Default |
|-------|------|---------|---------|
| `reicast_widescreen_hack` | opções | `disabled` / `enabled` | `disabled` |
| `reicast_widescreen_cheats` | avançadas | `disabled` / `enabled` | `disabled` |

O `_hack` alarga o frustum e serve para a maioria dos jogos. O `_cheats` ativa cheats por jogo
para títulos que o hack sozinho não cobre — existe para poucos, daí ficar no avançado.

### Nintendo 64 — mupen64plus-next

| Chave | Valores | Observação |
|-------|---------|------------|
| `mupen64plus-aspect` | `4:3`, `16:9 adjusted`, `16:9` | `adjusted` **é** o widescreen hack; `16:9` puro só estica |
| `mupen64plus-169screensize` | do core | tamanho de render usado quando a proporção é 16:9 |

A descrição no próprio core não deixa dúvida: *"Select the aspect ratio. 'adjusted' means
essentially Widescreen hacks."* O `43screensize` (já exposto antes) só vale no modo 4:3 — por
isso o `169screensize` entrou junto, senão escolher 16:9 deixaria a resolução de render órfã.

### Cores sem opção de widescreen (verificado, não é omissão)

| Core | Sistema | Situação |
|------|---------|----------|
| `pcsx_rearmed` | PSX | Não tem. Só `screen_centering*` e calibração de guncon |
| `ppsspp` | PSP | Não precisa — o PSP é 480×272, nativamente ~16:9 |
| `opera` | 3DO | Não tem |
| `dolphin` | GameCube | **Não verificado** — o aparelho desconectou antes de eu extrair o `.so`. Não escrevi chave sem conferir |

---

## Como o valor chega no core

1. O usuário escolhe no menu do jogo (`BaseGameActivity.displayOptionsDialog`).
2. Grava em SharedPreferences como `cv_<systemDbname>_<chave>`
   (`CoreVariablesManager.computeSharedPreferenceKey`).
3. No boot seguinte, `CoreVariablesManager.getOptionsForCore` faz o merge
   `defaultSettings + prefs do usuário`.
4. A lista vira `GLRetroViewData.variables` em `buildRetroViewData` e é passada no
   `LibretroDroid.create(...)` — ou seja, **antes** do `retro_load_game`.

O passo 4 é o que faz as opções marcadas "Restart Required" funcionarem: elas não pegam no jogo
em andamento, mas pegam sozinhas no próximo boot, sem nenhum tratamento especial. Os títulos
levam "(reiniciar)" para o usuário não achar que quebrou.

> **Chave errada não quebra nada — e é justamente por isso que precisa ser conferida.**
> `BaseGameActivity.transformExposedSetting` cruza a `ExposedSetting` com o que o core
> **declara** e devolve `null` quando não bate. Uma chave inventada simplesmente não aparece no
> menu: sem crash, sem log, sem sintoma. O único jeito de saber que está certa é conferir no
> core.

---

## Arquivos

| Arquivo | Mudança |
|---------|---------|
| `lib/library/GameSystem.kt` | `ExposedSetting` de aspecto/widescreen em N64 e Dreamcast |
| `retrograde-app-shared/res/values/strings.xml` | títulos e rótulos dos valores (en) |
| `retrograde-app-shared/res/values-pt-rBR/strings.xml` | tradução |

Nenhuma mudança nativa — não exige rebuild da AAR.

---

## Como conferir as chaves de um core

```bash
# .so do core, do proprio aparelho (cores ficam em files/cores/<versao>/)
adb shell run-as app.retrogamesystem.debug cat files/cores/1.19.0/<core>.so > core.so

# chaves declaradas
"$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strings.exe" -n 6 core.so \
  | grep -E "^<prefixo>_[a-zA-Z0-9_-]+$" | sort -u
```

O `.so` traz as traduções misturadas no `.rodata`, então o contexto ao redor da chave **não** é
confiável para descobrir os valores aceitos. Para os valores, use o fonte do core quando houver
(`libretro_core_options.h`) ou o log `Libretro variable:` que o `printRetroVariables` emite em
build debug com o jogo rodando.
