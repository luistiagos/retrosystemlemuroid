# [BUG] Telemetria afoga o painel: 1.297 reports de reciclagem **normal** de processo em background + 59 ecos de crash sem stack

**Data:** 2026-09-02
**Status:** ✅ Resolvido — filtro aplicado em `CrashTelemetry`
**Severidade:** Média (não quebra o app, mas **84 % de todos os erros abertos do projeto** eram
ruído, e o ruído escondia os bugs reais)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/lowmemory` e `retrogamesystem/crash`
**Errors (serviço):** 1.374 em `lowmemory` (dos quais **1.297** com `importance=400`) + 59 em
`crash` com log = a string `crash`
**Janela observada:** 2026-08-15 13:40 → 2026-09-02 14:27

---

## Sintoma

Dos **1.593** erros com status Aberto do projeto `retrogamesystem`, **1.374 (86 %)** eram um
único grupo: `Killed by low memory`. A distribuição por `importance` do processo morto:

| `importance` | Significado | Ocorrências |
|---|---|---|
| **400** | `IMPORTANCE_CACHED` — processo **em cache**, sem nada visível | **1.297** |
| 230 | `IMPORTANCE_PERCEPTIBLE` | 53 |
| 300 | `IMPORTANCE_SERVICE` | 11 |
| 125 | `IMPORTANCE_FOREGROUND_SERVICE` — jogo rodando | 11 |
| 100 | `IMPORTANCE_FOREGROUND` — na frente do usuário | 2 |

E por processo: 1.332 eram `app.retrogamesystem` (o processo de UI), não `:game`. Em 979 deles
não havia sequer sessão de jogo registrada.

O segundo grupo, `retrogamesystem/crash` (59 ocorrências), tinha sempre a **mesma** mensagem
`Java crash: crash` e o log anexo era literalmente a palavra `crash` — nenhum stack.

## Causa-raiz

### 1. `REASON_LOW_MEMORY` era reportado sem filtrar `importance`

`isInteresting` recebia só o `reason`:

```kotlin
private fun isInteresting(reason: Int): Boolean =
    reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
        reason == ApplicationExitInfo.REASON_CRASH ||
        reason == ApplicationExitInfo.REASON_ANR ||
        reason == ApplicationExitInfo.REASON_LOW_MEMORY
```

O `importance` era lido e gravado no contexto do report, mas **nunca usado para decidir se
valia reportar**.

O Android mata processos em cache por pressão de memória **o tempo todo** — é o funcionamento
normal do LMK, não um defeito do app. Um `REASON_LOW_MEMORY` com `importance=400` significa
apenas "o usuário saiu do app e mais tarde o sistema reciclou o processo". Reportar isso é o
equivalente a reportar que o app foi fechado.

O sinal que **importa** existia e estava afogado: **13 ocorrências** com `importance` 100/125 —
o app morto **em primeiro plano, durante a partida** (errors 3457 mgba/gba, 3408 citra/3ds,
2410/2245/2191 realme RMX3834, …). Esses são bugs de consumo de memória de verdade, e estavam
a 1 em 100 no meio do ruído.

### 2. `REASON_CRASH` duplicava o que o handler ao vivo já reportava

O `UncaughtExceptionHandler` é instalado em **ambos** os processos (`main` e `:game`), como
primeira coisa do `LemuroidApplication.onCreate`
([LemuroidApplication.kt:45](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/LemuroidApplication.kt#L45)),
e reporta a exceção **com stack** no instante do crash. Na sessão seguinte, `reportPastExits`
encontrava o mesmo evento como `REASON_CRASH` e reportava **de novo** — agora com
`info.description == "crash"` e sem trace, porque o `ApplicationExitInfo` de um crash Java não
guarda o stack.

O dedup do `TelemetryReporter` não pegava: o `seen` é um `HashSet` **em memória**
([TelemetryReporter.kt:115](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/telemetry/TelemetryReporter.kt#L115)),
portanto só vale dentro de uma sessão — e o eco, por definição, chega na sessão seguinte.

A prova está nos IDs: saíam **adjacentes** aos do bug real de LazyGrid
([[2026-09-02-home-lazygrid-chave-duplicada]]) — 3259/3258, 2973/2972, 2938/2937, 2933/2932,
2922/2921, 2912/2911, 2849/2848, 2412/2411. Mesmo evento, contado duas vezes, sendo que a
segunda contagem não carregava informação nenhuma.

## Correção

Arquivo único: [CrashTelemetry.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/telemetry/CrashTelemetry.kt).

`isInteresting` passou a receber o `ApplicationExitInfo` inteiro (linha 121), e o corte de
low-memory virou uma função própria e documentada (linha 140):

```kotlin
private fun isInteresting(info: ApplicationExitInfo): Boolean =
    when (info.reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR -> true
        ApplicationExitInfo.REASON_LOW_MEMORY -> isLowMemoryWorthReporting(info)
        else -> false
    }

private fun isLowMemoryWorthReporting(info: ApplicationExitInfo): Boolean =
    info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
```

- **`REASON_LOW_MEMORY`**: corte em `IMPORTANCE_VISIBLE` (200). `importance` conta **para
  baixo**, então `<=` preserva os 13 casos reais — `IMPORTANCE_FOREGROUND` (100) e
  `IMPORTANCE_FOREGROUND_SERVICE` (125, o `:game` com a partida rodando) — e descarta
  `PERCEPTIBLE` (230), `SERVICE` (300) e o `CACHED` (400) que era 94 % do volume.
- **`REASON_CRASH`**: removido do scan (opção 1 do plano). O handler ao vivo já cobre, e com
  stack. O único caso que o handler perde é o crash que mata o processo antes de o report sair —
  e nesse caso o eco também não traz stack, então não se perde nada.

Escolhida a opção 1 em vez da opção 2 (reportar só quando há trace) justamente porque a opção 2
exigiria um dedup persistido entre sessões, que não existe hoje.

O `componentFor`/`reasonName` mantêm os ramos de `REASON_CRASH`: são tabelas de tradução puras,
inofensivas, e deixam o caminho pronto caso o scan volte a incluir esse reason.

**Sem mudança de comportamento do app** — o contrato da telemetria ("nunca alterar o
comportamento do app") continua valendo: a correção só reduz o que é enviado.

## Validação

- `./gradlew :lemuroid-app:kaptFreeBundleDebugKotlin :lemuroid-app:compileFreeBundleDebugKotlin`
  → **BUILD SUCCESSFUL**, zero erros.
- Bytecode conferido com `javap` para garantir que o artefato reflete a mudança (e não um hit de
  cache do Gradle):

  ```
  private final boolean isInteresting(android.app.ApplicationExitInfo);
  private final boolean isLowMemoryWorthReporting(android.app.ApplicationExitInfo);
  ```

  A assinatura de `isInteresting` deixou de ser `(int)`, confirmando a recompilação.
- `isInteresting` tem **um único chamador** (linha 98), verificado por grep no repositório
  inteiro — não há outro caminho que contorne o filtro.
- O `maxTs` do watermark é avançado **antes** do filtro, para todo exit; filtrar mais não faz o
  scan reprocessar exits antigos na sessão seguinte.
- Não há source set de teste em `lemuroid-app` (`src/` só tem variantes de build), então não
  havia onde acrescentar um teste unitário sem criar a infraestrutura do zero.

> ⚠️ Durante a validação apareceu um `Unresolved reference 'DaggerLemuroidApplicationComponent'`
> e um `Could not delete ...kotlin-classes`. Ambos eram estado obsoleto de kapt / lock de arquivo
> no Windows, **não** a mudança: o baseline com o arquivo revertido (`git stash`) falhava igual.
> Resolvido com `./gradlew --stop` + limpeza de `build/tmp/kotlin-classes/freeBundleDebug` e
> rodando a task de kapt explicitamente.

## Impacto esperado

Os erros abertos do projeto caem de **1.593 para ~160**, e os 13 low-memory de primeiro plano
deixam de estar escondidos.

## Lição

**Um campo que entra no report também precisa entrar na decisão de reportar.** O `importance` já
era coletado e gravado no contexto — a informação para separar "LMK funcionando" de "bug nosso"
estava lá desde o início, e ainda assim 94 % do volume era ruído, porque ela nunca foi
consultada no filtro. Coletar um sinal não é usá-lo.

**Dedup em memória não vê eco entre sessões.** Todo mecanismo que relê o passado
(`ApplicationExitInfo` e afins) tem que ser cruzado com o que o caminho ao vivo já reportou — e
essa comparação é, por construção, entre sessões. Um `HashSet` de processo nunca vai pegá-la.
Quando as duas fontes cobrem o mesmo evento, preferir a que tem mais informação (o handler, que
tem stack) e desligar a outra é mais barato do que persistir dedup.

## Próximos passos

- [x] Aplicar o filtro por `importance` e desligar `REASON_CRASH`.
- [x] **(fora do código, no painel)** fechar em massa os 1.297 `lowmemory` com `importance=400`
      e os 59 ecos — não são bugs.
- [x] Reabrir a análise dos low-memory de primeiro plano como bug próprio de consumo de
      memória — ver [[2026-09-28-lowmemory-gameplay-ppsspp-foreground-service]] (triagem
      2026-09-28, PPSSPP entra na lista ao lado de 3ds/citra e gc/dolphin).

## Recorrência (Triagem 2026-09-28)

- **`retrogamesystem/crash` (eco sem stack):** 8825 (`app=1.17.10`), 8604 (`app=1.17.9`) — as
  duas versões predatam o filtro (garantido a partir de `1.17.12`/`1.17.13`, commit
  `36aa389`). Mesmo padrão: `message="Java crash: crash"`, log = a string `crash`. Fechados.
- **`retrogamesystem/lowmemory` com `importance=400`:** 8534 (`app=1.17.12`, na janela ambígua
  do mesmo commit — "pode ou não conter o fix" conforme a data exata do build). Fechado; se
  `importance=400` voltar a aparecer em `app` inequivocamente ≥ 1.17.13, reabrir.
