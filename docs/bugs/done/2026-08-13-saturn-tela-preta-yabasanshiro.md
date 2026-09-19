# [BUG] Sega Saturn — tela preta em todos os jogos (FBO recriado a cada troca de modo de vídeo)

**Data:** 2026-08-13
**Status:** Corrigido ✅ — validado no aparelho (moto g86, Mali-G615) em Quake, Mortal Kombat II e Tomb Raider
**Severidade:** Crítica (sistema inteiro inutilizável)
**Branch:** version9

---

## Sintoma

Nenhum jogo de Saturn dava imagem jogável:

- **Motorola G58 / moto g86**: a logo do jogo aparece, às vezes a logo da SEGA (BIOS) também, e
  **em seguida a tela fica completamente preta** — impossível jogar.
- **Outros celulares**: o app **crasha** ao iniciar o jogo.

A logo aparecer provava que BIOS, `retro_load_game`, contexto GL e os primeiros frames estavam
inteiros. A quebra vinha depois do boot.

## Causa-raiz

`FramebufferRenderer` (LibretroDroid) **destruía e recriava o objeto de framebuffer** — nome novo do
`glGenFramebuffers` incluso — toda vez que o core mudava a geometria. Cores com renderização por
hardware têm o direito de **cachear** o id devolvido por `get_current_framebuffer()`, e o
YabaSanshiro faz exatamente isso: `retro_set_resolution()` chama `VIDCore->Resize(...)` (onde o core
lê e guarda o FBO atual) **antes** de `retro_reinit_av_info()` (que avisa o frontend da nova
geometria). Sequência real:

1. Jogo troca de modo de vídeo (ex.: 320×224 → 320×240).
2. `YuiSwapBuffers()` detecta e chama `retro_set_resolution()`.
3. `VIDCore->Resize()` → o core cacheia o **FBO 1**.
4. `retro_reinit_av_info()` → `SET_GEOMETRY` → o LibretroDroid marca `isDirty`.
5. `initializeBuffers()` → `glDeleteFramebuffers` + `glGenFramebuffers` → **FBO 2**.
6. O core segue desenhando no FBO 1; o frontend apresenta o FBO 2, que ninguém escreve.
7. **Preto para sempre**, a partir da primeira troca de modo.

Havia um segundo defeito no mesmo ponto: a reinicialização acontecia em `onNewFrame()`, ou seja
**depois** de o core já ter pintado o frame — o buffer recém-desenhado era jogado fora e apresentava-se
a textura vazia no lugar dele.

### Prova (log instrumentado, Quake)

```
DIAG fbrenderer initializeBuffers 320 x 224
DIAG present fbo=1 status=0x8cd5 size=320x224 centerMax=255   <- core desenhando
DIAG fbrenderer initializeBuffers 320 x 240                   <- troca de modo
DIAG present fbo=2 status=0x8cd5 size=320x240 centerMax=0     <- preto, e nunca mais volta
DIAG present fbo=2 ...                       centerMax=0
```

`centerMax` = maior componente RGBA lido de volta do centro do render target do core.
`status=0x8cd5` = `GL_FRAMEBUFFER_COMPLETE`, e `glErr=0x0` o tempo todo — **nenhum erro de GL era
levantado**, por isso o bug era invisível.

Por que o Tomb Raider parecia curado só com core options: ele fica em 352×224 e a mudança de
`sh2coretype` alterou o timing o suficiente para ele não bater na troca de modo. Coincidência, não
correção.

## Correção

Em `LibretroDroid-patched` (AAR local, `libs/libretrodroid-patched.aar`):

**1. O nome do framebuffer nunca muda.** `ES3Utils::createFramebuffer` ganhou o parâmetro
`reuseFramebufferName`, e `ES3Utils::deleteFramebuffer` o `keepFramebufferName`. Em cada resize só
os **anexos** (textura de cor + renderbuffer de depth/stencil) são realocados; o objeto de
framebuffer é o mesmo da criação até a destruição do renderer. Assim o id cacheado pelo core
continua válido para sempre.

**2. O resize acontece antes de o core desenhar.** Saiu de `onNewFrame()` (que roda *depois* do
core pintar) para `getFramebuffer()` — o próprio ponto em que o core pede o alvo via
`get_current_framebuffer()`. Nenhum frame é mais descartado numa troca de modo.

Arquivos: `renderers/es3/es3utils.{h,cpp}`, `renderers/es3/framebufferrenderer.{h,cpp}`.

No app, o `SystemCoreConfig` do Saturn passou a fixar explicitamente a configuração validada
(`sh2coretype=dynarec`, `resolution_mode=original`, `polygon_mode=perspective_correction`,
`rbg_use_compute_shader=disabled`) e a expor `sh2coretype`, `frameskip` e `polygon_mode` como
opções — o interpretador é a primeira coisa a testar se algum aparelho ainda crashar.

## Validação

moto g86 5G (Mali-G615 MC2, GL ES 3.2), APK `freeBundle` debug arm64:

| Jogo | Antes | Depois |
|------|-------|--------|
| Quake | preto após a intro | roda — tela de título e intro, 60 fps |
| Mortal Kombat II | preto | roda — cutscene de abertura, 60 fps |
| Tomb Raider | preto (curado por acaso via core options) | roda — gameplay, 60 fps |

Nenhum `SIGSEGV` nem `FATAL EXCEPTION` no logcat nas três execuções. AAR final regerado com as
**4 ABIs** (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), objetos recompilados em todas confirmados
por mtime. SHA-1 do AAR: `304ec039c37aa75625f6f79648c39e9622ad5fb4` (anterior:
`16a6c3f8c9907c49331e6b5f0d4c7bc4a06fcbda`).

## O que foi descartado no caminho

| Hipótese | Veredito |
|----------|----------|
| `libandroid.so` faltando no `DT_NEEDED` (caso Flycast) | `DT_NEEDED = [libGLESv3, libc, libm, libdl]` — o core não usa ashmem |
| BIOS ausente/errada | sem BIOS o `retro_load_game` retorna `false`; e a logo da SEGA aparecia |
| Core exige GLES 3.1 | os únicos shaders `#version 310 es` são o compute do RBG e a tessellation, **ambos desligados por default** |
| Vazamento de VAO/`GL_ARRAY_BUFFER` (caso Vircon32) | `renderFrame()` já restaura essas bindings |
| Dynarec SH2 | **falso positivo** — os 3 jogos rodam a 60 fps com o dynarec depois da correção do FBO |

## Lição

**Cores com renderização por hardware podem cachear o id de `get_current_framebuffer()`.** A libretro
diz que o frontend pode trocar o FBO a cada frame, mas na prática vários cores (YabaSanshiro entre
eles) só releem o id quando *eles* decidem redimensionar. Um frontend que gera um nome novo a cada
troca de geometria quebra esses cores **em silêncio** — framebuffer completo, `glGetError()` limpo,
tela preta. Regra: o nome do FBO entregue ao core é parte do contrato e deve ser **estável**;
realoque só os anexos. E realoque **antes** do core desenhar, nunca no callback de vídeo.

Corolário de diagnóstico: quando não há erro de GL, `glReadPixels` no render target do core separa em
um passo "o core não desenhou" de "o frontend não apresentou". Foi o que fechou este caso.
