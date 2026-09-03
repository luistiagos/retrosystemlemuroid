# [BUG] Crashes nativos dentro dos cores 3DS / GameCube (citra, dolphin)

**Data:** 2026-09-02
**Status:** 🟡 **Citra mitigado** (fallback automático + opção exposta; causa-raiz é do core/driver e
segue aberta) · **Dolphin aberto** (crash dentro do driver Adreno, sem ação nossa identificada)
**Severidade:** Alta para 3DS (o sistema ficava inutilizável nos aparelhos afetados); baixa para GameCube
**Branch:** version9
**Origem:** telemetria `retrogamesystem/native`
**Janela observada:** 2026-08-17 → 2026-09-01

> O terceiro grupo desta investigação — **Flycast / Dreamcast**, 15 eventos — tinha causa
> própria e **foi resolvido**: o `.so` arm64 empacotado era um build local sem o patch de
> `libandroid.so`. Ver [[2026-09-02-flycast-arm64-core-hand-build-sigtrap]].

---

## Resumo dos grupos

| Core | Sinal | N | Errors (serviço) | Aparelhos |
|---|---|---|---|---|
| `libcitra_libretro_android.so` | SIGTRAP `TRAP_BRKPT` na `GLThread` | 14 | 3510, 3407, 3394-3401, 1511, 1489 · 3410, 3399, 3402 | Samsung SM-S731B, Android 16, app 1.17.9/1.17.12 |
| `dolphin_libretro_android.so` | SIGSEGV em `__memcpy_aarch64_simd` via `libGLESv2_adreno` | 3 + 2 | 1816, 1632, 1496 · 1393, 1355 | Samsung SM-S918B (Adreno), OPPO CPH3669, Android 14/16 |

---

## Citra (3DS) — assert na ligação do shader

```
signal 5 (SIGTRAP), code TRAP_BRKPT — tid: GLThread 514
  #01  OpenGL::LoadProgram(bool, std::span<unsigned int const>)+696
  #02  OpenGL::OGLProgram::Create(bool, std::span<unsigned int const>)+320
  #03  OpenGL::ShaderProgramManager::ApplyTo(OpenGL::OpenGLState&)+936
  #04  OpenGL::RasterizerOpenGL::AccelerateDrawBatchInternal(bool)+408
  #05  OpenGL::RasterizerOpenGL::Draw(bool, bool)+804
  #07  Pica::CommandProcessor::ProcessCommandList(unsigned, unsigned)+344
  #09  Service::GSP::GSP_GPU::TriggerCmdReqQueue(Kernel::HLERequestContext&)+4540
```

Jogos observados: *Ocarina of Time 3D*, *LEGO Batman 2*. Todas as 14 ocorrências vêm de
**Android 16**, aparelho `SM-S731B` (Galaxy S25 FE, GPU Xclipse).

### Causa-raiz (lida no fonte do `libretro/citra`, não inferida)

`glLinkProgram` devolve `GL_FALSE` e o citra **aborta de propósito**:

```cpp
// src/video_core/renderer_opengl/gl_shader_util.cpp
GLuint LoadProgram(bool separable_program, std::span<const GLuint> shaders) {
    ...
    glLinkProgram(program_id);
    glGetProgramiv(program_id, GL_LINK_STATUS, &result);
    ...
    ASSERT_MSG(result == GL_TRUE, "Shader not linked");
```

`ASSERT_MSG` em release vira trap → `SIGTRAP`/`TRAP_BRKPT`. Ou seja: **o driver Xclipse
rejeita o GLSL que o citra gera** e o core prefere morrer a degradar. A falha em si é do
par citra × driver — nada nosso.

### O caminho, verificado ponta a ponta

```
citra_use_hw_shaders=enabled
  → Settings::values.use_hw_shader                    (citra_libretro.cpp, UpdateSettings)
  → VideoCore::g_hw_shader_enabled                    (core.cpp, System::ApplySettings)
  → accelerate_draw = g_hw_shader_enabled && ...      (command_processor.cpp)
  → RasterizerOpenGL::AccelerateDrawBatch             (só é chamada se accelerate_draw)
  → AccelerateDrawBatchInternal → ShaderProgramManager::ApplyTo
  → OGLProgram::Create → LoadProgram → ASSERT_MSG → SIGTRAP
```

Com `citra_use_hw_shaders=disabled`, `accelerate_draw` é falso, `AccelerateDrawBatch` nunca
roda e o **vertex/geometry shader PICA gerado** — o programa que não linka — nunca é
construído.

> ⚠️ **Não é garantia, é mitigação.** `RasterizerOpenGL::Draw` também chama
> `shader_manager.ApplyTo(state)` no caminho não-acelerado (depois de
> `UseTrivialVertexShader()` + `UseTrivialGeometryShader()`), então o *fragment program*
> continua sendo ligado e um driver suficientemente quebrado ainda pode falhar ali. O que
> some é o shader gerado a partir do programa PICA do jogo, que é o candidato óbvio para
> um compilador GLSL exótico rejeitar.

### Correção aplicada (2026-09-02)

1. **Opção exposta ao usuário** — `citra_use_hw_shaders` entrou em `exposedSettings` do
   bloco 3DS de [GameSystem.kt](../../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/GameSystem.kt),
   com default explícito `enabled` (igual ao default do core — ninguém perde performance).
   Antes **não havia como desligar**: a opção não estava nem em `defaultSettings` nem em
   `exposedSettings`, então o usuário afetado não tinha saída nenhuma.

2. **Fallback automático pós-crash** —
   [CoreCrashFallback.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/CoreCrashFallback.kt),
   chamado do `MainProcessInitializer`. Se a sessão anterior era 3DS e o processo `:game`
   morreu com `REASON_CRASH_NATIVE` **depois** do início daquela sessão, grava
   `cv_3ds_citra_use_hw_shaders = false` e marca que já fez isso.
   - Usa a mesma preferência que o switch do menu de jogo escreve (Harmony, multi-processo),
     então o usuário pode religar e o app **não** desliga de novo — é one-shot.
   - Discriminação de sessão por timestamp (`TelemetryContext.lastGameSessionStartedAt`,
     acrescentado agora): um crash nativo antigo de outro core não dispara o fallback.

3. **Removida a opção morta `citra_use_acc_geo_shaders`.** Estava em `exposedSettings` e
   aparecia como switch no menu, mas o `citra_libretro.cpp` só a **declara** na tabela de
   `retro_variable` — nunca a lê, nunca atribui a nenhum `Settings::values`. Era um botão
   que não fazia nada. (`citra_use_acc_mul`, ao lado, é lida de verdade →
   `Settings::values.shaders_accurate_mul`.)

### O que já foi verificado e descartado

- O `.so` de citra empacotado é binário de buildbot legítimo — **não** é build local
  (varredura dos 102 arquivos `.so` arm64 empacotados procurando caminho de fonte absoluto
  embutido; só o Flycast era build local, e já foi corrigido).
- Todas as demais chaves `citra_*` usadas em `GameSystem.kt` existem na tabela de
  `retro_variable` do binário — nenhuma outra opção está sendo ignorada por typo.

### Validação em device (2026-09-03) — parcial, e uma lacuna corrigida

**Aparelho:** Moto G86 5G (Mali, Android 16) — **não** é o aparelho que crasha. O que dá para
validar aqui é o lado do app: que a opção existe, é a chave certa e o 3DS continua rodando.
O crash em si depende de GPU Xclipse.

**Jogo:** *Mario Kart 7* (Europe), baixado pelo próprio catálogo do app.

| Item | Resultado |
|---|---|
| 3DS carrega e roda com o core citra | ✅ 60 fps (`EMUFPS 60.03`), tela do jogo renderizando |
| `citra_use_hw_shaders` aparece no menu do jogo → Configurações | ✅ com o switch **ligado**, igual ao default do core |
| `citra_use_acc_geo_shaders` (a chave morta) sumiu do menu | ✅ |
| Saída do jogo | ✅ limpa (`System.exit … status: 0`) |
| Fallback automático após crash nativo | ⏳ não exercitável aqui — exige o crash, que não acontece nesta GPU |

**Lacuna encontrada e corrigida:** a opção aparecia em **inglês** num app em português —
`setting_citra_use_hw_shaders` só existia em `values/strings.xml`. Traduzida para
`values-pt-rBR` e `values-pt-rPT`. Vale como lembrete: uma `ExposedSetting` nova precisa da
string nos locais do projeto, não só no default; e isso **só aparece abrindo o menu no
aparelho** — compilar e conferir a chave contra o `.so` não pega.

O que continua faltando confirmar num Galaxy S25:

1. Rodar *Ocarina of Time 3D* → crash (baseline).
2. Reabrir o app → log `3DS died in native code last session; disabled citra_use_hw_shaders`.
3. Rodar o mesmo jogo → roda (mais devagar), e o switch "Shaders por hardware" aparece
   desligado no menu do jogo.

---

## Dolphin (GameCube) — SIGSEGV dentro do driver Adreno

```
signal 11 (SIGSEGV), code SI_QUEUE — tid: GLThread 1332
  #00  libc.so (__memcpy_aarch64_simd+232)
  #01  /vendor/lib64/egl/libGLESv2_adreno.so (+4748)
  #02  dolphin_libretro_android.so
  #06  dolphin_libretro_android.so (retro_run+2908)
  #07  liblibretrodroid.so (libretrodroid::LibretroDroid::step()+100)
```

O crash acontece **dentro do driver da GPU**, num `memcpy` — assinatura clássica de
buffer/tamanho inválido passado pelo core numa chamada GL. Nosso único quadro é
`step()` → `retro_run`. Volume baixo (5 eventos, 2 aparelhos) e, ao contrário do citra,
**não há opção de core identificada** que desvie do caminho: o tombstone não nomeia a
chamada GL, e sem isso qualquer troca de opção seria chute. Segue aberto de propósito.

---

## Como reproduzir

Não reproduzido localmente — depende de GPU/driver específicos (Xclipse no citra, Adreno no
dolphin). Para o citra, *Ocarina of Time 3D* num Galaxy da linha S25 é o caso mais repetido
(errors 3394-3402, todos no mesmo aparelho e sessão).

## Próximos passos

- [x] Validar o lado do app em device (2026-09-03, Moto G86 5G): 3DS roda, a opção certa
      aparece no menu, tradução pt-BR/pt-PT adicionada. Ver "Validação em device".
- [ ] Confirmar o **fallback** num Galaxy S25 (roteiro de 3 passos na seção Validação) —
      exige o crash, que não acontece fora da GPU Xclipse.
- [ ] Citra: reportar upstream com o tombstone — o stack simbolizado e o
      `ASSERT_MSG(result == GL_TRUE)` são bons o bastante para um issue. O pedido certo é
      que o link falho **degrade** em vez de abortar o processo.
- [ ] Citra: testar um build mais recente do core no mesmo aparelho.
- [ ] Dolphin: tentar forçar backend/driver alternativo nas opções do core em aparelhos
      Adreno — antes, achar qual chamada GL recebe o buffer inválido.
- [ ] Genérico (vale para os três cores): avisar o usuário quando a sessão anterior morreu
      em código nativo, em vez de o app fechar sem explicação. O `CoreCrashFallback` já
      **detecta** exatamente isso e só falta a UI. Mas **sem `Toast` durante o boot do
      jogo** (pitfall 7).

## Lição

**Opção de core não se escolhe por nome.** A mitigação óbvia aqui — "desliga hardware
shaders" — só é defensável porque o gate (`accelerate_draw = g_hw_shader_enabled && ...`)
foi lido no `command_processor.cpp`. A leitura do fonte também mostrou o oposto do
esperado em dois pontos: `Draw` chama `ApplyTo` **também** no caminho não-acelerado (logo a
mitigação é parcial, não uma cura), e `citra_use_acc_geo_shaders` — que estava exposta no
nosso menu há tempos — nunca foi lida pelo core.
