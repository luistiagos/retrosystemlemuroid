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
| `dolphin_libretro_android.so` | SIGSEGV em `__memcpy_aarch64_simd` via `libGLESv2_adreno` | 5 + 5 | 1816, 1632, 1496, 1393, 1355 · **6799, 6727, 5898, 5727, 5723** | Samsung SM-S918B (Adreno), OPPO CPH3669, Samsung SM-A057M (A05s), SM-S901E, Android 14/15/16, app até 1.17.19 |

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
`step()` → `retro_run`. Segue aberto de propósito.

> **Atualização triagem 2026-09-18:** Mais 5 ocorrências registradas na telemetria (IDs **6799, 6727, 5898, 5727, 5723**) em aparelhos com GPU Adreno (Samsung Galaxy A05s `SM-A057M` no Android 15 e Galaxy S22 `SM-S901E` no Android 16), rodando versão 1.17.19. O crash segue idêntico dentro de `libGLESv2_adreno.so` via `__memcpy_aarch64_simd`.

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
- [x] Genérico (vale para os três cores): avisar o usuário quando a sessão anterior morreu
      em código nativo, em vez de o app fechar sem explicação — implementado **e validado em
      device** em 2026-09-03, ver "Aviso ao usuário" abaixo.

## Aviso ao usuário quando o core mata a sessão (2026-09-03)

Um core que aborta sozinho leva o processo `:game` junto: não há exceção Java, não há tela de
crash, não há nada em que o usuário possa agir. O app **some** no meio da partida e volta como
se nada tivesse acontecido — e a leitura natural disso é "o app é bugado", não "este núcleo não
roda nesta GPU". O `CoreCrashFallback` já detectava a morte nativa para virar a opção do citra;
o que faltava era contar.

**O que mudou em [CoreCrashFallback.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/CoreCrashFallback.kt):**

1. A detecção deixou de ser exclusiva do 3DS. Antes, `apply()` saía cedo com
   `if (!session.contains("system=3ds")) return` — um crash nativo de dolphin ou flycast não
   produzia efeito nenhum. Agora a detecção é genérica e o flip da opção do citra virou
   `applyCitraFallback()`, chamado de dentro dela.
2. `nativeCrashDuringLastSession()` devolve o **timestamp** do exit (era `Boolean`), que vai
   para `PREF_LAST_NOTIFIED_EXIT_AT`. Sem isso o mesmo crash seria anunciado em toda abertura
   do app: o breadcrumb do `TelemetryContext` só é limpo numa saída limpa, então ele continua
   lá depois de uma morte nativa.
3. `pendingNotice: StateFlow<Notice?>` com o jogo, o núcleo e se alguma opção foi desligada.
   É `StateFlow` e não uma leitura de preferência porque `applyAsync` roda numa thread de
   fundo do `MainProcessInitializer` e pode terminar **depois** da home já composta — uma tela
   que lesse a preferência uma vez, na entrada, simplesmente perderia o aviso. Também é `val`
   e não `fun`: `collectAsState()` chaveia pela instância do flow, e uma função devolveria um
   wrapper novo a cada recomposição, reiniciando a coleta.
4. O `Notice` é persistido em `PREF_PENDING_NOTICE` e só sai no `consumeNotice()`, para
   sobreviver ao app ser morto antes de o usuário ver.

**Onde aparece:** diálogo na home — [MainActivity.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainActivity.kt)
(Compose) e [MainTVActivity.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/main/MainTVActivity.kt)
(`AlertDialog` comum, o mesmo padrão do `TVAppUpdateDialog`). **Na home, nunca durante o boot
do jogo** — enfileirar UI com a main thread carregando o core é exatamente o que derruba as TV
box de Android 7.1 (pitfall 7). Duas strings, en + pt-BR: uma genérica ("o núcleo X travou; se
persistir, troque de núcleo nas Configurações") e outra para quando o fallback do citra agiu
("os shaders de hardware foram desligados… você pode religá-los").

**Validado em device (2026-09-03).** Moto G86 5G, Android 16, app `1.17.12-DEBUG`.

O crash real do citra depende de GPU Xclipse, que não está em mãos — mas o que o
`CoreCrashFallback` lê não é o sinal e sim `ApplicationExitInfo.REASON_CRASH_NATIVE` do
processo `:game`, e um SIGSEGV entregue por sinal produz exatamente isso. Roteiro em
`test_core_crash_notice.py`: abre *Super Mario World* (snes/snes9x), mata o `:game`, e só então
reabre o app.

| Passo | Resultado |
|---|---|
| breadcrumb gravado no início da sessão | `system=snes; core=snes9x; game=Super Mario World` |
| `run-as … kill -11 <pid do :game>` | `dumpsys activity exit-info` → `process=…:game reason=5 (APP CRASH(NATIVE)) status=11` |
| reabrir o app | diálogo **"O jogo fechou inesperadamente"** / *"Super Mario World parou porque o núcleo snes9x travou. Se continuar acontecendo, tente outro núcleo para este sistema nas Configurações."* |
| tocar em OK | some, e `core_crash_pending_notice` sai das preferências |
| reabrir de novo | **não** volta — `core_crash_last_notified_exit_at` segura, e o breadcrumb continua lá (só some numa saída limpa) |
| `core_fallback_3ds_hw_shaders_applied` | **ausente** — a sessão era SNES, então o aviso genérico disparou e o fallback específico do citra corretamente não |

A última linha é a prova de que a generalização funcionou: antes desta mudança um crash nativo
fora do 3DS não produzia efeito nenhum, nem aviso nem flag.

> ⚠️ **`adb shell kill` não serve** para isto: roda com o uid `shell`, que não pode sinalizar um
> processo do app — o kill falha **em silêncio** e o teste passa a não medir nada (foi o que
> aconteceu nas duas primeiras tentativas, com o `exit-info` mostrando só `FORCE STOP`). Tem que
> ser `adb shell run-as <pkg> kill -11 <pid>`. E o `dumpsys` imprime `APP CRASH(NATIVE)`, não
> `CRASH_NATIVE` — um assert procurando a constante do SDK falha mesmo com o teste correto.

## Lição

**Opção de core não se escolhe por nome.** A mitigação óbvia aqui — "desliga hardware
shaders" — só é defensável porque o gate (`accelerate_draw = g_hw_shader_enabled && ...`)
foi lido no `command_processor.cpp`. A leitura do fonte também mostrou o oposto do
esperado em dois pontos: `Draw` chama `ApplyTo` **também** no caminho não-acelerado (logo a
mitigação é parcial, não uma cura), e `citra_use_acc_geo_shaders` — que estava exposta no
nosso menu há tempos — nunca foi lida pelo core.
