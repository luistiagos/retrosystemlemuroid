# [BUG] NPE em `isMainProcess()` na TV box — `getRunningAppProcesses()` devolve null e o Kotlin trata como não-nulo

**Data:** 2026-09-02
**Status:** ✅ Corrigido em 2026-09-03
**Severidade:** Média-Alta (crash na TV box rockchip YBOX, o mesmo aparelho de
[[2026-08-16-tvbox-mxq-crash-toast-badtoken]]; `isMainProcess()` está no caminho de startup)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/main` (`SourceFile::K4.g.d`)
**Errors (serviço):** 1347
**Aparelho:** rockchip YBOX, **armeabi-v7a**, anuncia "Android 12.1" mas é **sdk 25 (7.1)**, app 1.17.8
**Observado em:** 2026-08-17 03:34

---

## Sintoma

```
java.lang.NullPointerException: getRunningAppProcesses(...) must not be null
	at K4.g.d(SourceFile:35)                                        <- retrieveProcessName
	at K4.g.b(SourceFile:6)                                         <- isMainProcess
	at com.swordfish.lemuroid.app.LemuroidApplication.onTrimMemory(SourceFile:4)
	at android.app.ActivityThread.handleTrimMemory(ActivityThread.java:5010)
	at android.os.Looper.loop(Looper.java:154)
	at android.app.ActivityThread.main(ActivityThread.java:6121)
```

`Looper.loop(Looper.java:154)` + `ActivityThread.main(6121)` são assinaturas de **Android 7.1**,
confirmando que o "Android 12.1" anunciado pelo aparelho é falso — exatamente o caso descrito
na regra 2 do pitfall 7 do CLAUDE.md.

## Causa-raiz

[ContextUtils.kt:11-25](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/ContextUtils.kt#L11-L25):

```kotlin
fun Context.isMainProcess(): Boolean {
    return retrieveProcessName(this) == this.packageName
}

private fun retrieveProcessName(context: Context): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return DaggerApplication.getProcessName()
    }
    val currentPID = Process.myPid()
    val manager = context.getSystemService(DaggerApplication.ACTIVITY_SERVICE) as ActivityManager
    return manager.runningAppProcesses          // <- tipo de plataforma; o Kotlin insere
        .firstOrNull { it.pid == currentPID }   //    checkNotNull ao encadear com `.`
        ?.processName
}
```

`ActivityManager.getRunningAppProcesses()` é declarado `@NonNull` no framework, então o Kotlin
o vê como tipo de plataforma e, ao encadear `.firstOrNull`, injeta um
`Intrinsics.checkNotNullExpressionValue` — a mensagem *"getRunningAppProcesses(...) must not be
null"* é literalmente esse intrinsic. Na prática, várias ROMs de TV box **devolvem null** (o
`ActivityManagerService` retorna null em vez de lista vazia quando o app não tem permissão ou
quando o serviço ainda não subiu). Resultado: NPE.

O ramo é alcançado só em `SDK_INT < 28`; em aparelho moderno o `getProcessName()` cobre. Ou
seja, **só quebra exatamente no público que o app quer atender** (TV box velha, armeabi-v7a,
`minSdkVersion` 21).

### Por que isso é pior do que "um crash no onTrimMemory"

`isMainProcess()` é chamado em **seis** pontos, cinco deles no
[`LemuroidApplication`](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/LemuroidApplication.kt):
linhas 45, 46, 68, 73 (dentro de `onCreate`) e 107/126 (`onTrimMemory`/`onLowMemory`). Os de
`onCreate` rodam **antes de qualquer Activity**. Se o `getRunningAppProcesses()` devolver null
nesse instante, o app nem sobe — e a telemetria não captura, porque
`CrashTelemetry.installUncaughtHandler` está na linha 45, no meio do mesmo bloco.

O report que chegou é o do `onTrimMemory` justamente porque o caminho de `onCreate`, quando
falha, falha silenciosamente para nós.

## Como reproduzir

TV box rockchip com Android 7.1 real. Sem o aparelho, dá para forçar o caminho num emulador
API 25 injetando `runningAppProcesses == null` (o comportamento não é reproduzível em emulador
padrão, que sempre devolve lista).

## Correção

Três arquivos.

### 1. `ContextUtils.kt` — resolução de processo que não lança

[ContextUtils.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/ContextUtils.kt)

- `getSystemService(...) as? ActivityManager` e `manager?.runningAppProcesses?…` — nullable
  de verdade, sem o `checkNotNullExpressionValue` que produzia a NPE. Tudo dentro de
  `runCatching`, como já fazia o `TelemetryContext.processName`.
- O ramo `SDK_INT >= P` usa `try/catch` explícito em vez de `runCatching`: mantém a chamada
  guardada **lexicamente** dentro do `if`, onde a análise `NewApi` do lint não tem dúvida.
- **Fallback novo: `/proc/self/cmdline`.** O Android grava ali o nome do processo no fork.
  Sem binder IPC e sem permissão, então responde exatamente onde o `ActivityManager` não
  responde. Lido por `FileInputStream(...).readBytes()` — `/proc` reporta tamanho 0, então o
  stream tem que ser lido até EOF em vez de dimensionar buffer por `File.length()`.
- O valor do cmdline só é aceito se for `packageName` ou `packageName:`+sufixo. Um cmdline
  truncado ou estranho vira "não resolvido" em vez de virar um `false` — um `false` errado no
  processo main pularia o `MainProcessInitializer` e deixaria o app sem catálogo.
- Só quando **nenhuma** das duas fontes responde é que vale o default: `isMainProcess()`
  devolve `true`, porque abaixo da API 28 o `:game` é a minoria e assumir "main" mantém
  telemetria, initializer e trim de cache vivos em vez de desligá-los em silêncio.

### 2. `CrashTelemetry.installUncaughtHandler` — tag do processo resolvida depois do install

[CrashTelemetry.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/telemetry/CrashTelemetry.kt)

O parâmetro virou `componentResolver: () -> String`, e o nome do processo é resolvido
**depois** de `Thread.setDefaultUncaughtExceptionHandler`, guardado num `@Volatile var
component` que o handler lê no momento do crash:

```kotlin
fun installUncaughtHandler(context: Context, componentResolver: () -> String) {
    ...
    installed = true
    ...
    component = runCatching { componentResolver() }.getOrDefault(component)
}
```

Antes, `installUncaughtHandler(this, if (isMainProcess()) "main" else "game")` avaliava o
argumento **antes** da chamada: a falha ao nomear o processo derrubava o app no exato instante
em que o handler que reportaria essa falha ainda não existia. Resolver depois também tira o
binder IPC do caminho do crash — no momento do crash o handler já tem a tag em memória.

### 3. `LemuroidApplication` — uma resolução por processo

[LemuroidApplication.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/LemuroidApplication.kt)

Os seis `isMainProcess()` viraram um campo:

```kotlin
private val runningInMainProcess: Boolean by lazy { isMainProcess() }
```

Abaixo da API 28 cada chamada era um round-trip de binder, e `onTrimMemory` — o frame do
report — dispara repetidamente. O processo de um `Application` vivo não muda, então uma
resolução basta. A primeira acontece dentro da lambda passada ao `installUncaughtHandler`,
ou seja, já com o handler instalado.

## Validação

- `./gradlew :lemuroid-app:compileFreeBundleDebugKotlin` — **BUILD SUCCESSFUL**, sem warning
  novo (os que saem são pré-existentes: `TRIM_MEMORY_RUNNING_LOW` deprecado etc.).
- `ktlintMainSourceSetCheck`: **zero** ocorrências em `ContextUtils.kt` e
  `LemuroidApplication.kt`. Em `CrashTelemetry.kt` saem as três de `standard:function-signature`
  na assinatura de 2 parâmetros — a mesma regra já acusa o `reportOneExit(context, info)`
  intocado no mesmo arquivo, então não é regressão (o projeto tem ~870 violações herdadas).
- **Não validado em aparelho.** A condição (`getRunningAppProcesses()` devolvendo null) não é
  reproduzível em emulador padrão, que sempre devolve lista. O fechamento é por análise: o
  único caminho que lançava era o `checkNotNull` do encadeamento, e ele não existe mais.

## Lição

1. **Tipo de plataforma some da revisão.** `manager.runningAppProcesses.firstOrNull { }` não
   tem nada de suspeito na leitura — nenhum `!!`, nenhum cast. O `checkNotNullExpressionValue`
   é o compilador que injeta, porque a assinatura do framework diz `@NonNull` e o Kotlin
   acredita. Sempre que uma chamada de framework é encadeada com `.`, a pergunta é "e se a ROM
   mentir?", não "e se o SDK mentir?".
2. **O `@NonNull` do framework é uma promessa da AOSP, não da ROM.** O público-alvo deste app
   é justamente quem roda 7.1 de verdade anunciando "Android 12.1" (regra 2 do pitfall 7).
   Em aparelho desses, `as?` + `?.` custa nada e é o que mantém o app de pé.
3. **O que instala telemetria não pode depender de nada que possa falhar.** O argumento
   avaliado antes da chamada anulou a telemetria no exato caso que ela existia para capturar —
   por isso só o report do `onTrimMemory` chegou, e nenhum do `onCreate`. Chamada que prepara
   o registro de erros vai primeiro, sozinha; o resto é resolvido depois.
4. **Já havia a versão certa no repositório.** `TelemetryContext.processName` faz a mesma
   consulta, defensiva, desde sempre. Duas implementações da mesma pergunta e só uma corrigida
   — vale procurar o gêmeo antes de escrever a segunda.
