---
fecha_por: dono
casador: {texto: "Cannot create framebuffer", componente: "retrogamesystem/native"}
---

# [BUG] Jogo fecha ao abrir no Pixel 5 (APK 32-bit): SIGABRT "Cannot create framebuffer" na GLThread, ao criar a tela do jogo

**Data:** 2026-11-20
**Severidade:** media: o jogo não abre (o processo `:game` morre ao criar a superfície); visto num aparelho só, duas vezes
**Complexidade:** alta
**Branch:** version10
**Origem:** telemetria 10421, 10435

---

## Sintoma

Num Google Pixel 5 (`redfin`), Android 12, com o app 1.17.27 instalado pelo APK **32-bit** (bibliotecas em
`lib/arm/`), o jogo fechou ao abrir, duas vezes no mesmo dia (2026-10-16 17:14 e 17:47 UTC). O sistema e o jogo não
aparecem no tombstone.

## Evidência

- Telemetria `retrogamesystem/native`, erros **10421** e **10435** (`user_agent RetroGameSystem/native 1.17.27`).
  Achados ao procurar `Cannot create framebuffer` nos 53 SIGABRT nativos de 2026-08-13 a 2026-11-19 (comandos em
  `docs/bugs/bloqueado/2026-10-05-ppsspp-libretrodroid-cannot-create-framebuffer-abort/`, seção "Frequência na
  telemetria").
- Log (10421; 10435 tem a mesma pilha):
  ```
  Build fingerprint: google/redfin/redfin:12/SP1A.210812.015/7679548:user/release-keys__2021-10-05
  pid: 9998, tid: 10074, name: GLThread 1
  signal 6 (SIGABRT), code SI_QUEUE
  Abort message: terminating due to uncaught exception of type std::runtime_error: Cannot create framebuffer
    #05  liblibretrodroid.so (__cxa_throw+50)
    #06  liblibretrodroid.so (libretrodroid::ES3Utils::create…
    #07  liblibretrodroid.so (libretrodroid::FramebufferRende…
    #08  liblibretrodroid.so (libretrodroid::FramebufferRende…
    #09  liblibretrodroid.so (libretrodroid::Video::initializ…
    #10  liblibretrodroid.so (libretrodroid::Video::Video(lib…
    #11  liblibretrodroid.so (libretrodroid::LibretroDroid::o…
    #13  base.odex (com.swordfish.libretrodroid.GLRetroView$i…
    #20  base.odex (com.swordfish.libretrodroid.GLSurfaceView…
  ```
  (nomes truncados pela telemetria em ~170 colunas). Nenhum `.so` de core na pilha.
- **Diferença para `2026-10-05-ppsspp-libretrodroid-cannot-create-framebuffer-abort`:** lá o throw vem da `EmuThread`
  do PPSSPP via `SetRenderTarget` → `getFramebuffer`. Aqui vem da **GLThread** do próprio LibretroDroid, na construção
  do `Video` (criação da superfície), fora de qualquer core. É a mesma mensagem com outro caminho: não é duplicata.
