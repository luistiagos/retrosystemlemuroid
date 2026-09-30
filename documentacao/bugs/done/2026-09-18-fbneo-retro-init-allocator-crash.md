# [BUG] Crash nativo na inicialização do FBNeo — Corrupção de alocador de memória no `retro_init` (Scudo / je_large_dalloc)

**Data:** 2026-09-18
**Status:** ✅ **Resolvido em 2026-09-30** — a causa nunca foi o binário: é um double free do
FBNeo quando `retro_init` roda pela segunda vez no mesmo processo, que o frontend provocava ao
reaproveitar o `:game`. Ver "Causa-raiz real e correção (2026-09-30)" no fim. A "Causa-raiz" e a
"Correção" abaixo (atualização do core) ficam como histórico: só mudaram os offsets.
**Severidade:** Alta (todo jogo de arcade operado pelo FBNeo crashava na inicialização, em todo aparelho arm64 Android 16 observado)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/native` (`libfbneo_libretro_android.so::reason=Native crash`)
**Errors (serviço):** 7203, 6991, 6294, 5944 (4 ocorrências)
**Reincidência:** primeira vez detectado (aparelhos Samsung S22 Android 16 e Moto G56 Android 16, app 1.17.19)

---

## Sintoma

Ao iniciar um jogo de arcade operado pelo FBNeo (ex.: *Marvel Super Heroes*, CPS2), o processo do jogo (`app.retrogamesystem:game`) aborta ou sofre segfault antes de renderizar o primeiro frame:

**Variante A — Scudo (Android 16 padrão):**
```
signal 6 (SIGABRT), code SI_QUEUE
Abort message: Scudo ERROR: invalid chunk state when deallocating address 0x200007a44e93010
backtrace:
  #00  pc 00000000000705fc  /apex/com.android.runtime/lib64/bionic/libc.so (abort+160)
  #01  pc 000000000005d200  /apex/com.android.runtime/lib64/bionic/libc.so (scudo::die()+12)
  #04  pc 000000000005dfe0  /apex/com.android.runtime/lib64/bionic/libc.so (scudo::reportInvalidChunkState+120)
  #05  pc 000000000005f6fc  /apex/com.android.runtime/lib64/bionic/libc.so (scudo::Allocator::deallocate+296)
  #06  pc 0000000001dc4524  .../libfbneo_libretro_android.so
  #07  pc 0000000001dc4310  .../libfbneo_libretro_android.so
  #08  pc 0000000001dd1d24  .../libfbneo_libretro_android.so (retro_init+196)
  #09  pc 00000000000b8190  .../liblibretrodroid.so (libretrodroid::LibretroDroid::create+728)
```

**Variante B — jemalloc (Motorola):**
```
signal 11 (SIGSEGV), code SEGV_MAPERR, fault addr 0x0
Cause: null pointer dereference
backtrace:
  #00  pc 00000000000822e0  /apex/com.android.runtime/lib64/bionic/libc.so (je_large_dalloc+48)
  #02  pc 0000000000067974  /apex/com.android.runtime/lib64/bionic/libc.so (je_free+708)
  #03  pc 0000000001dc4530  .../libfbneo_libretro_android.so
  #04  pc 0000000001dc4310  .../libfbneo_libretro_android.so
  #05  pc 0000000001dd1d24  .../libfbneo_libretro_android.so (retro_init+196)
  #06  pc 00000000000b8190  .../liblibretrodroid.so (libretrodroid::LibretroDroid::create+728)
```

Ambas as variantes ocorrem exatamente no mesmo ponto de execução: `retro_init+196` no
`libfbneo_libretro_android.so`, chamado por `LibretroDroid::create()` — ou seja, o
frontend chama `retro_set_environment`/`retro_set_video_refresh`/etc. e então
`retro_init()` na sequência padrão ([libretrodroid.cpp:284-297](../../../LibretroDroid-patched/libretrodroid/src/main/cpp/libretrodroid.cpp)); o crash acontece
**dentro** da rotina de inicialização global do core, antes de qualquer ROM ser
carregada.

## Causa-raiz

O `.so` do FBNeo empacotado (`lemuroid_core_fbneo` e `bundled-cores`, os dois módulos que
o distribuem — dynamic-feature para a variante Play, estático para a variante free) datava
de **2026-04-10** e tinha sido compilado com **NDK r28c**:

```
lemuroid-cores/bundled-cores/.../arm64-v8a/libfbneo_libretro_android.so:
ELF 64-bit LSB shared object, ARM aarch64, ... built by NDK r28c (13676358), stripped
```

Cinco meses de commits upstream do FBNeo/libretro-super se acumularam sem que o core
fosse re-baixado — não há automação nem CI que puxe o buildbot periodicamente, só o
script manual `lemuroid-cores/update_cores.ipy` (que exige descomentar a entrada e rodar
à mão). `retro_init+196` cai dentro de uma rotina interna sem símbolo (offsets `#06`/`#07`
sem nome de função) que tenta desalocar um bloco de memória em estado inválido — dado que
o binário está stripped e não há fonte do FBNeo neste repositório (só o `.so` final), não
dá para apontar a linha C exata sem fazer engenharia reversa completa de um binário de
~60 MB sem informação de debug. O padrão bate com o já registrado no pitfall 6 do
[CLAUDE.md](../../../CLAUDE.md): "core de buildbot desatualizado/errado" já causou dois
crashes nativos de produção anteriores (Flycast), e o "próximo passo" já cadastrado nesta
mesma issue era justamente atualizar o core para a versão upstream mais recente.

## Correção

Atualizado o `.so` do FBNeo para o nightly atual do buildbot libretro
(`https://buildbot.libretro.com/nightly/android/latest/<abi>/fbneo_libretro_android.so.zip`,
build de 2026-09-18, **NDK r29**), nas 4 ABIs × 2 módulos (8 arquivos, mesmo padrão do
fix de Flycast):

- `lemuroid-cores/lemuroid_core_fbneo/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libfbneo_libretro_android.so`
- `lemuroid-cores/bundled-cores/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libfbneo_libretro_android.so`

Os 8 arquivos foram verificados (`file`) quanto a arquitetura ELF correta por ABI e
batem entre si por par de módulo (mesmo binário nos dois lugares, como antes). O core
antigo (r28c) foi preservado em
`lemuroid-cores/_fbneo_backup_20260918_r28c/<abi>/libfbneo_libretro_android.so` para
rollback rápido caso o nightly novo regrida algo.

> **Atualização 2026-09-25:** esse diretório foi apagado
> ([[2026-09-25-fbneo-backup-r28c-com-crash-solto-no-repo-de-cores]]). O rollback é pelo git:
> o r28c está no `b31f28b` (`main` do GitHub), idêntico byte a byte nos 8 arquivos.
>
> ```
> git -C lemuroid-cores restore --source=b31f28b -- "bundled-cores/src/main/jniLibs/*/libfbneo_libretro_android.so" "lemuroid_core_fbneo/src/main/jniLibs/*/libfbneo_libretro_android.so"
> ```
>
> Use o `restore`, não `git show … > arquivo`: no PowerShell 5.1 o `>` re-encoda a saída
> como texto e corrompe o `.so`. E o r28c é justamente a versão que crasha em `retro_init`.

> ⚠️ **Caminho de download separado não coberto por esta correção.** Assim como no
> [[2026-09-02-flycast-arm64-core-hand-build-sigtrap]], o `CoreUpdaterImpl` (variante free)
> tem um fallback que baixa cores não-bundled do GitHub `luistiagos/libretrocores` na tag
> `CoreDownloader.CORES_VERSION` (`1.19.0`) — repositório externo, fora deste checkout.
> Para o FBNeo esse caminho só é exercitado se o `.so` não estiver em
> `nativeLibraryDir` (não deveria acontecer na variante free, que embute `bundled-cores`
> estaticamente), mas na variante Play com dynamic-feature ainda não instalado o app cai
> nesse download. Esse repositório precisa do binário atualizado publicado separadamente
> por fora deste comando.

## Validação

Feita no artefato, não em aparelho (o defeito é de binário de terceiro, sem fonte local
para reproduzir em teste automatizado):

- `file` nos 4 `.so` novos confirma ELF correto por ABI (aarch64 / arm / i386 / x86-64),
  `for Android 21` (bate com `minSdkVersion 21` do projeto), `built by NDK r29`.
- Os 2 módulos (`lemuroid_core_fbneo` e `bundled-cores`) ficam com binário idêntico
  (`cmp`) nas 4 ABIs — nenhuma divergência entre módulos, ao contrário do que causou o
  bug de Flycast.
- Tamanho quase dobrou (63 MB → 71,7 MB no arm64) — consistente com 5 meses de novos
  drivers/romsets adicionados upstream, não um binário truncado ou corrompido no download.

**Confirmado em aparelho real** (Motorola moto g86 5G, Android 16, arm64-v8a — mesma
combinação de SO/arquitetura dos relatos de telemetria):

1. Build `freeBundleDebug` (variante que embute `bundled-cores` estaticamente) gerada e
   instalada via `adb install -r`. Extraído o `.so` de dentro do APK final e conferido
   (`file`) que é exatamente o binário novo (`BuildID` bate com o baixado do buildbot).
2. Jogo *Marvel Super Heroes* (CPS2) baixado on-demand e lançado pela UI real do app
   (busca → toque no resultado → download → "Jogar").
3. Logcat do processo `:game` mostra `Core created on GLThread 1 in 120 ms` (passou de
   `retro_init` sem abortar) e depois renderização estável — `EMUFPS ~59.5 frames/s`
   sustentado por toda a sessão, sem nenhuma linha de `Fatal signal`, `SIGABRT`,
   `SIGSEGV` ou `Scudo` no log. Screenshot confirma a tela de atração do jogo
   renderizada corretamente.
4. Saída do jogo (`KEYCODE_BACK` → fluxo normal de `finishAndExitProcess`) também limpa,
   processo `:game` encerrado sem crash.

Antes desta correção o crash ocorria *sempre*, imediatamente em `retro_init`, antes de
qualquer frame — a sessão completa acima (boot + ~15s de gameplay renderizando + saída
limpa) não seria possível com o binário antigo.

## Lição

1. **Core de buildbot sem automação de atualização é dívida técnica silenciosa.** O FBNeo
   ficou 5 meses parado porque `update_cores.ipy` exige alguém lembrar de descomentar a
   linha e rodar manualmente. Um core desatualizado não aparece em nenhum teste local —
   só em telemetria de produção, semanas depois, como "crash nativo misterioso".
2. **Sem o código-fonte do core, root-cause binário exato não é viável.** `retro_init+196`
   aponta pra dentro de duas frames sem símbolo num binário stripped de ~60-70 MB. A ação
   correta quando isso acontece (e já tinha sido cadastrada nesta mesma issue como
   "próximos passos") é atualizar para a build upstream mais recente, não tentar
   desmontar o binário à mão.
3. Mesma lição do Flycast se aplica aqui: **o caminho de download externo
   (`luistiagos/libretrocores`) é uma segunda cópia do binário que este commit não
   alcança.** Precisa de atualização manual separada.

## Recorrência (Triagem 2026-09-22)

- **Novos IDs associados:** 7509, 7380 (2 ocorrências)
- **Diagnóstico:** As ocorrências 7509 (MAME2003+ / Scudo abort) e 7380 (FBNeo / Scudo abort) em aparelhos Samsung SM-A546E rodando versão 1.17.19 são anteriores à distribuição da atualização do core e binários. Fechados na telemetria.

## Recorrência (Triagem 2026-09-28) — build já contém o fix

- **Novos IDs associados:** 8804, 8741, 8740 (todos Samsung SM-A546E, `app=1.17.22`), 8539
  (Samsung SM-A127M, `app=1.17.22-DEBUG`).
- **Confirmado que é o binário atualizado, não eco de build antigo.** O offset mudou de
  `retro_init+196` (r28c, doc original) para **`retro_init+252`** nos quatro reports novos —
  consistente com o `.so` maior (63 MB → 71,7 MB) da atualização de 2026-09-18. Frame `#07`
  idêntico byte a byte entre 8804/8740/8741 (`0x21e70f4`, `0x21e6ee8`), e 8539 (jemalloc,
  variante B) só diverge em 24 bytes num frame interno — mesmo build, mesma rotina.
  - ⚠️ **`app=` do relatório não prova a versão do crash.** Para exits via
    `ApplicationExitInfo` (native/anr/lowmemory), `CrashTelemetry.reportOneExit` monta o
    contexto com `TelemetryReporter.deviceContext()`, que lê a versão **instalada no momento do
    scan** ([TelemetryReporter.kt:229](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/telemetry/TelemetryReporter.kt#L229),
    `safeVersion()` → `PackageManager.getPackageInfo(...).versionName`), não a versão que
    rodava quando o processo `:game` morreu. Por isso a comparação de causa-raiz acima foi feita
    pelo **offset binário no tombstone**, não pelo campo `app=`.
- **Duas variantes de alocador, mesmo ponto de chamada** (`retro_init` → `LibretroDroid::create`):
  - **Scudo** (8804, 8740, 8741): `Scudo ERROR: corrupted chunk header ... chunk header is zero
    and might indicate memory corruption or a double free` — mensagem diferente da variante A
    original (`invalid chunk state`), mesmo offset.
  - **jemalloc** (8539): `je_large_dalloc`/`je_free`, `SIGSEGV SEGV_MAPERR fault addr 0x0` —
    igual à "Variante B" original, mesmo device-class (Samsung).
- **Diagnóstico:** a atualização de core de 2026-09-18 foi uma **mitigação por binário mais
  novo**, não uma correção da causa. A corrupção de heap em `retro_init` é interna ao FBNeo
  (upstream, sem fonte neste repositório) e sobrevive a uma nightly diferente — só o offset
  exato onde o alocador detecta o dano mudou. Reaberto em vez de anotado como recorrência
  porque os quatro IDs batem no binário **atual** (confirmado por offset, não por `app=`).
- **Próximo passo real:** não há mais "atualizar o core" como ação — já é o nightly mais
  recente disponível no momento da 2026-09-18. Ou reportar upstream ao FBNeo/libretro-super com
  os dois tombstones (Scudo + jemalloc, mesmo `retro_init`), ou aceitar como limitação conhecida
  do core e não repetir a tentativa de mitigação por atualização sem uma build nova rio acima.


## Causa-raiz real e correção (2026-09-30)

Investigada junto com o `strcmp(NULL)` do 8780 em
[[2026-09-28-investigacoes-baixa-confianca-triagem]], onde está o detalhe completo.

**Os `free` que crasham dentro de `retro_init` só executam se já houve um `retro_init` antes no
mesmo processo.** `retro_init` → `BurnLibInit()` → `BurnLibExit()` → `BurnGameListExit()` libera
`pszShortName`/`pszFullNameA`/`pszFullNameW`
([burn.cpp](https://github.com/libretro/FBNeo/blob/c2c52376cfb49b431200f2b2c986423abd7463e7/src/burn/burn.cpp)).
Num processo novo os três são `NULL` e os `free` são pulados; o `retro_deinit` da sessão anterior
libera os três **sem zerar**, e o `retro_init` seguinte libera de novo. Como o LibretroDroid nunca
faz `dlclose` (pitfall 13), o segundo `dlopen` devolve a mesma imagem com esses ponteiros pendentes.

Confirmado por disassembly nos dois binários:

| Binário | Cadeia do tombstone |
| --- | --- |
| r28c (relatos originais) | `retro_init+196` → `bl BurnLibInit` (`0x1dc42f4`) → `0x1dc4310` `bl BurnLibExit` → `0x1dc4524` `free(pszShortName)` (Scudo) / `0x1dc4530` `free(pszFullNameA)` (jemalloc) |
| atual, Build ID `99e0cc61…` (8539, 8740, 8741, 8804) | `retro_init+252` → `bl BurnLibInit` (`0x21e6ecc`) → `0x21e6ee8` `bl BurnLibExit` → `0x21e70f4` `free(pszShortName)` / `0x21e710c` `free(pszFullNameW)` |

O 8539 roda em `GLThread 2` — o contador de `GLThread` é estático, então o processo já tinha
criado outra `GLRetroView`. Por isso a validação de 2026-09-18 (processo novo, uma sessão) passava
e a atualização de core só mudou os offsets.

**Correção:** um processo `:game` hospeda uma sessão de core, nunca duas (`GameProcessSession`;
sessão recusada num processo já usado é relançada num processo novo pelo processo principal).
Reproduzido antes (SIGABRT em `retro_init`, `GLThread 2`, no AVD `lemu_api25_2gb`) e validado
depois (`GLThread 1` num processo novo, 60 FPS) no AVD e no Samsung SM-A127M arm64. A reprodução
nativa sem ROM ([test-fbneo-reinit.ps1](../../../tests/native/test-fbneo-reinit.ps1)) dá, nesse
Samsung, o tombstone do 8539 com os mesmos offsets (`je_large_dalloc+52`, `je_free+2116`,
`retro_init+252`).

**Lição que substitui a 2 acima:** "sem o código-fonte do core, root-cause binário não é viável"
estava errado — o fonte do FBNeo é público, e o disassembly de três instruções em volta de cada
frame bastou. O que faltava era comparar o nome da thread entre as famílias.
