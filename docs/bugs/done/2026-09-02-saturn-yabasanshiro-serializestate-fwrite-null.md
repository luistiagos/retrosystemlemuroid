# [BUG] Saturn — salvar estado aborta o processo: `FORTIFY: fwrite: null FILE*` dentro do yabasanshiro

**Data:** 2026-09-02
**Status:** Resolvido ✅ (causa-raiz provada no binário e no aparelho; falta o teste ponta-a-ponta com um jogo de Saturn)
**Severidade:** Média-Alta (perde a partida ao salvar; Saturn é sistema pesado, quem joga salva)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/native` (`yabasanshiro_libretro_android.so`, `reason=Native crash status=6`)
**Errors (serviço):** 2 ocorrências — 1178, 1170
**Aparelho:** Samsung SM-G781B (S20 FE), arm64-v8a, Android 13, app 1.17.4
**Observado em:** 2026-08-14 04:15 e 2026-08-15 00:21

---

## Sintoma

```
signal 6 (SIGABRT), code SI_QUEUE
Abort message: FORTIFY: fwrite: null FILE*
  #00  libc.so (abort+168)
  #01  libc.so (__fortify_fatal(char const*, ...)+128)
  #02  libc.so (fwrite+300)
  #03  .../yabasanshiro_libretro_android.so
  #04  .../yabasanshiro_libretro_android.so
  #05  .../yabasanshiro_libretro_android.so
  #06  liblibretrodroid.so (libretrodroid::LibretroDroid::serializeState()+24)
```

Thread `GLThread`. Caminho de salvar estado (quick save / autosave ao sair).

## Causa-raiz

**`tmpfile()` devolve `NULL` para qualquer processo de app no Android, e o core não checa
o retorno.** Não é bug de Saturn nem daquele aparelho: é determinístico, em todo aparelho.

### 1. O que o core faz (desassemblado do `.so` empacotado)

`retro_serialize` (0x14083c) → `YabSaveStateBuffer` (0xc1ebc) → `YabSaveStateStream` (0xc1f6c)
— exatamente os três quadros `#05/#04/#03` do tombstone:

```asm
; 0xc1ebc  YabSaveStateBuffer
  c1ee0: bl  <tmpfile@plt>
  c1ee4: mov x19, x0          ; <-- FILE* guardado, SEM cbz/cbnz: nenhuma checagem
  c1ef0: bl  0xc1f6c          ; YabSaveStateStream(fp)

; 0xc1f6c  YabSaveStateStream
  c1f9c: adrp x0, 0x47000
  c1fa0: add  x0, x0, #0x115  ; "YSS" (magic do save state do Yabause)
  c1fac: mov  x3, x19         ; fp == NULL
  c1fb8: bl   <fwrite@plt>    ; <-- FORTIFY: fwrite: null FILE*  ->  abort()
```

`_FORTIFY_SOURCE` do bionic transforma o `fwrite(…, NULL)` em `abort()` com mensagem, em vez
de um SIGSEGV — daí o processo morrer "limpo" com `status=6`.

`retro_unserialize` (0x1408e0) → `YabLoadStateBuffer` (0xc2538) tem o **mesmo** `tmpfile()`
sem checagem seguido de `fwrite` em 0xc2568: **carregar** um estado de Saturn aborta igual.

### 2. Por que `tmpfile()` falha

`tmpfile()` do bionic cria o arquivo em `$TMPDIR`; com a variável ausente, cai em
`/data/local/tmp`. Medido no aparelho conectado (moto g86, Android 16), com o uid do
próprio app:

| Verificação | Resultado |
|---|---|
| `TMPDIR` no `/proc/<pid>/environ` do processo do app | **ausente** (o Android não exporta a variável) |
| `ls -ldZ /data/local/tmp` | `drwxrwx--x shell:shell u:object_r:shell_data_file:s0` |
| escrever em `/data/local/tmp` com o uid do app | `Permission denied` |
| `tmpfile()` rodando com o uid do app, `TMPDIR` ausente | **`NULL`, `errno=13` (EACCES)** |
| `tmpfile()` rodando com o uid do app, `TMPDIR=<filesDir>/tmp` | **OK** — `/data/user/0/…/files/tmp/#925206 (deleted)` |
| `tmpfile()` com `TMPDIR` apontando para diretório inexistente | `NULL`, `errno=2` (ENOENT) — **não há segundo fallback** |

A última linha é o detalhe que dita a correção: o bionic atual não tenta mais `/tmp` depois
de falhar em `$TMPDIR`. O diretório precisa **existir** antes de exportar a variável.

### 3. Alcance

Não é só o yabasanshiro. Importam `tmpfile`/`tmpnam`/`mkstemp` entre os cores empacotados:

| Core | Símbolos importados |
|---|---|
| `yabasanshiro` | `tmpfile` (nas 4 ABIs) |
| `libppsspp` | `tmpfile`, `tmpnam` |
| `atari800` | `tmpfile`, `mkstemp` |
| `hatari` | `tmpfile` |
| `libfake08` | `tmpfile`, `tmpnam` |

Todos falhavam pelo mesmo motivo, em qualquer caminho que use essas funções.

## Correção

[NativeTempDir.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/startup/NativeTempDir.kt)
— cria `filesDir/tmp` e exporta `TMPDIR` para o processo via `Os.setenv` (API 21, igual ao
`minSdk`), chamado em
[LemuroidApplication.onCreate](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/LemuroidApplication.kt).

Três decisões que valem registrar:

1. **`mkdirs()` antes do `setenv`**, e desiste sem exportar nada se falhar — `TMPDIR`
   apontando para diretório inexistente é *pior* que `TMPDIR` ausente (ENOENT direto).
2. **Sem `if (runningInMainProcess)`**, pelo mesmo motivo já documentado no
   `installUncaughtHandler` logo acima: `isMainProcess()` devolve `true` quando não consegue
   resolver o nome do processo, e nessas ROMs o `:game` — justamente onde os cores rodam —
   ficaria de fora. O custo é um `stat` e um `mkdir` em diretório já existente.
3. **Antes de qualquer thread.** `setenv` não é seguro contra um `getenv` concorrente, e tanto
   o SQLite quanto os cores leem o ambiente em código nativo. Por isso a chamada fica no topo
   do `onCreate`, antes da thread do Conscrypt, da do Coil e do `AppInitializer` — e por isso
   loga por `android.util.Log` e não por Timber: nesse ponto o `DebugInitializer` ainda não
   plantou árvore nenhuma (e em release nunca planta), então um `Timber.e` no caminho de falha
   seria descartado exatamente quando importa.

Limpeza dos restos: `tmpfile()` faz `unlink` na hora, mas um core que use `mkstemp` deixa o
arquivo para trás se o processo for morto no meio do jogo, e nada sob `filesDir` é reclamado
pelo sistema. O diretório é esvaziado no start do processo, quando nenhum core está carregado.

**Fora do alcance:** `tmpnam` (usado por `libppsspp` e `libfake08`) monta o caminho a partir de
`P_tmpdir` = `/tmp` e **ignora `TMPDIR`** — continua quebrado, e não há o que fazer do lado do app.

## Validação

Feita com o `.so` empacotado e com o aparelho conectado (moto g86 5G, Android 16, arm64):

- Desassemblado dos 3 quadros do tombstone até o `fwrite` — a cadeia `tmpfile` sem checagem →
  `fwrite("YSS", 3, 1, NULL)` está no binário, não é hipótese. A string em 0x47115 é `"YSS"`.
- Sonda nativa (`aarch64-linux-android21-clang`) executada **com o uid do app** provando as duas
  pontas: `tmpfile()` → `NULL/EACCES` sem `TMPDIR`, e sucesso com `TMPDIR=<filesDir>/tmp`
  (tabela acima).
- `:lemuroid-app:compileFreeDynamicDebugKotlin` OK; `ktlintMainSourceSetCheck` sem nenhuma
  violação nos dois arquivos tocados (as 840 do repo são todas pré-existentes).
- APK `freeBundleDebug` instalado e aberto: `files/tmp` criado (modo 700) e logcat com
  `I NativeTempDir: Native TMPDIR set to /data/user/0/app.retrogamesystem.debug/files/tmp`.

**Falta:** rodar um jogo de Saturn e salvar/carregar estado. O aparelho conectado está com
keyguard seguro (não dá para destravar por adb) e não tem ROM nem BIOS de Saturn, então o
processo `:game` não chegou a subir. A cobertura do `:game` decorre do `Application.onCreate`
rodar em todo processo e de não haver condicional no caminho — mas isso é dedução, não medição.

## Lição

1. **`tmpfile()` não funciona em app Android.** Sem `TMPDIR` o bionic tenta `/data/local/tmp`,
   que é `shell:shell` e nenhum app pode escrever. Qualquer core que chame `tmpfile`, `mkstemp`
   ou similar depende do app exportar `TMPDIR` — é responsabilidade do frontend, não do core.
2. **Frequência baixa na telemetria não é sinal de bug raro.** Duas ocorrências, um aparelho,
   e o defeito era 100% determinístico em toda a base — só que pouca gente joga Saturn e salva.
   Ler "2 ocorrências" como "caso de borda" teria mandado a investigação para o lado errado.
3. **Com o `.so` no repo, `llvm-objdump` responde o que a documentação não responde.** O bug
   dizia "sem fontes do core neste checkout, a linha exata não é conhecível"; três quadros de
   desassemblado deram a linha exata. As duas hipóteses de trabalho anteriores (diretório de
   sistema sem `mkdirs`, bug genérico do core) estavam ambas erradas — `getSystemDirectory()` e
   `getSavesDirectory()` já fazem `mkdirs()` no `DirectoriesManager`.
4. **Sonda nativa compilada com o NDK e executada por `run-as` mede o que `adb shell` não
   mede.** O shell escreve em `/data/local/tmp`; o app não. Testar como shell teria "provado"
   que estava tudo bem.
