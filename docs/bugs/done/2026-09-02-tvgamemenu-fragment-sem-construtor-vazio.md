# [BUG] Menu do jogo na TV crasha ao relançar a Activity — `TVGameMenuFragmentWrapper` não tem construtor vazio

**Data:** 2026-09-02
**Status:** Resolvido ✅
**Severidade:** Média (o jogo cai ao abrir/reabrir o menu na TV, tipicamente após mudança de
configuração ou restauração da Activity)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/game`
(`ActivityThread.java::android.app.ActivityThread.performLaunchActivity`)
**Errors (serviço):** 2 ocorrências — 2548, 2363
**Aparelho:** Google Chromecast (Google TV), **armeabi-v7a**, Android 14, app 1.17.10 —
`system=neogeo; core=fbneo; game=Metal Slug 3`
**Observado em:** 2026-08-26 00:23 e 2026-08-26 22:18

---

## Sintoma

```
java.lang.RuntimeException: Unable to start activity
ComponentInfo{app.retrogamesystem/com.swordfish.lemuroid.app.tv.gamemenu.TVGameMenuActivity}:
androidx.fragment.app.i$i: Unable to instantiate fragment
com.swordfish.lemuroid.app.tv.gamemenu.TVGameMenuActivity$a: could not find Fragment constructor
	at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:3782)
	at android.app.ActivityThread.handleRelaunchActivityInner(ActivityThread.java:5946)   <- RELAUNCH
	at android.app.ActivityThread.handleRelaunchActivity(ActivityThread.java:5842)
	at android.app.servertransaction.ActivityRelaunchItem.execute(ActivityRelaunchItem.java:76)
Caused by: androidx.fragment.app.i$i: Unable to instantiate fragment … could not find Fragment constructor
	at androidx.fragment.app.i.q0(SourceFile:98)
	at androidx.fragment.app.q.o1(SourceFile:253)
```

O frame decisivo é `handleRelaunchActivity` — **não** é a primeira abertura do menu, é o
**relançamento** da Activity (mudança de configuração, retomada após o sistema descartá-la).

## Causa-raiz

[TVGameMenuActivity.kt:96-109](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/gamemenu/TVGameMenuActivity.kt#L96-L109):

```kotlin
class TVGameMenuFragmentWrapper(
    private val statesManager: StatesManager,
    private val statesPreviewManager: StatesPreviewManager,
    private val inputDeviceManager: InputDeviceManager,
    private val game: Game,
    private val systemCoreConfig: SystemCoreConfig,
    private val coreOptions: Array<LemuroidCoreOption>,
    private val advancedCoreOptions: Array<LemuroidCoreOption>,
    private val numDisks: Int,
    private val currentDisk: Int,
    private val audioEnabled: Boolean,
    private val fastForwardEnabled: Boolean,
    private val fastForwardSupported: Boolean,
) : BaseSettingsFragmentWrapper() { … }
```

É um `Fragment` com **apenas** esse construtor de 12 argumentos — não há construtor sem
argumentos. Ele é adicionado por código
([TVGameMenuActivity.kt:86-87](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/gamemenu/TVGameMenuActivity.kt#L86-L87)):

```kotlin
supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commit()
```

O `FragmentManager` **persiste** o fragment no `savedInstanceState` e, no relançamento,
**reinstancia por reflexão** — o que exige um construtor público sem argumentos. Como não
existe, o `FragmentFactory` padrão lança `InstantiationException` antes mesmo de o `onCreate`
da Activity ter chance de recriar o fragment com os dados da Intent.

O crash é o *segundo* golpe: repare que os `throw InvalidParameterException("Missing EXTRA_…")`
de [TVGameMenuActivity.kt:59-69](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/gamemenu/TVGameMenuActivity.kt#L59-L69)
mostram que a Activity **depende** dos extras da Intent para montar o fragment — a dependência
por construtor é deliberada, só não sobrevive à restauração.

## Como reproduzir

Chromecast/Android TV: abrir um jogo, abrir o menu do jogo, e forçar um relançamento da
Activity — mudar idioma/tamanho de fonte do sistema, ou ativar "Não manter atividades" nas
opções de desenvolvedor e sair/voltar.

## Correção aplicada

Foi aplicada a segunda alternativa, cobrindo tanto o wrapper quanto o fragment filho:

1. `TVGameMenuFragmentWrapper` e `TVGameMenuFragment` agora têm construtor público vazio, de
   modo que os respectivos `FragmentManager`s conseguem recriá-los por reflexão.
2. Os parâmetros funcionais foram agrupados no `GameMenuRequest`, que é `Serializable`, e
   persistidos em `arguments`. O Android restaura esses dados junto com cada fragment.
3. `StatesManager`, `StatesPreviewManager` e `InputDeviceManager`, que não são estado
   serializável, passaram a ser reinjetados no `TVGameMenuFragment.onAttach` pelo Dagger.
4. A navegação entre telas do `LeanbackSettingsFragmentCompat` agora preserva os argumentos
   existentes ao acrescentar `ARG_PREFERENCE_ROOT`; sem isso, entrar numa subseção apagaria o
   `GameMenuRequest`.

O wrapper irmão `TVSettingsFragmentWrapper` também foi auditado: ele já possuía construtor
vazio e seu fragment filho não recebe dependências pelo construtor.

## Validação

- [x] `:lemuroid-app:compileFreeBundleDebugKotlin` — **BUILD SUCCESSFUL**, incluindo geração
      dos subcomponentes Dagger.
- [x] Inspeção do bytecode com `javap` confirma construtores públicos sem argumentos em
      `TVGameMenuFragmentWrapper` e `TVGameMenuFragment`.
- [x] `ktlintMainSourceSetCheck` não apontou violações nos arquivos alterados; a tarefa global
      continua falhando por violações preexistentes em outros arquivos do módulo.
- [ ] Validar com "Não manter atividades" ligado no Chromecast.

## Próximos passos

- [x] Aplicar a correção com estado em `arguments` e auditar os wrappers irmãos.
- [ ] Validar com "Não manter atividades" ligado, no Chromecast.
