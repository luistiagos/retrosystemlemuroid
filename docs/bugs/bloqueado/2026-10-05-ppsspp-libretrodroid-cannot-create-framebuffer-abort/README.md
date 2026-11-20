<!-- gerado por `pipeline.py gerar` (pipeline-global): não edite, o gerador reescreve este arquivo -->
# 2026-10-05-ppsspp-libretrodroid-cannot-create-framebuffer-abort

Bug em `bloqueado/`. Doc: [2026-10-05-ppsspp-libretrodroid-cannot-create-framebuffer-abort.md](2026-10-05-ppsspp-libretrodroid-cannot-create-framebuffer-abort.md).

**0 tasks**: Causa no nativo do libretrodroid-patched.aar: FramebufferRenderer::getFramebuffer (patch do Saturn) realoca FBOs na EmuThread do PPSSPP, sem contexto EGL, e createFramebuffer lanca std::runtime_error -> SIGABRT; destrava com o dono aplicando o fix nativo descrito no doc (realocar so com contexto GL corrente / no step, sem throw) e rebuildando o AAR..

Fecha por: prova.
