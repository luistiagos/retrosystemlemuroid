# [BUG] TV: Crash ao abrir submenu nas configurações (TCL Smart TV) — `Null reference used for synchronization (monitor-enter)` em `Bundle(fragment.arguments)`

- **Detectado em:** 2026-09-15 05:03 (telemetria de produção)
- **Status:** Corrigido
- **Severidade:** Alta (crash imediato ao abrir qualquer submenu de configurações em Android TV)
- **Branch:** version9
- **Origem:** telemetria `retrogamesystem/main` (`BaseBundle.java::android.os.BaseBundle.<init>`)
- **Errors (serviço):** 6698, 6622, 6621, 6619, 6618, 6260, 6122 (7 ocorrências)
- **Classe:** crash
- **Reincidência:** primeira vez detectado (aparelhos Android TV / TCL Smart TV, armeabi-v7a, versão 1.17.19)

---

## Sintoma

Usuário em Android TV (TCL Smart TV) navega nas configurações do app (Leanback Settings) e clica em qualquer item de submenu que seja um `PreferenceScreen`. O app encerra imediatamente com crash:

```
java.lang.NullPointerException: Null reference used for synchronization (monitor-enter)
	at android.os.BaseBundle.<init>(BaseBundle.java:219)
	at android.os.BaseBundle.<init>(BaseBundle.java:207)
	at android.os.Bundle.<init>(Bundle.java:169)
	at com.swordfish.lemuroid.app.tv.shared.TVBaseSettingsActivity$BaseSettingsFragmentWrapper.onPreferenceStartScreen(TVBaseSettingsActivity.kt:53)
	at androidx.preference.PreferenceFragmentCompat.onPreferenceTreeClick(PreferenceFragmentCompat.java:...)
	at androidx.preference.PreferenceScreen.performClick(PreferenceScreen.java:...)
	at androidx.preference.Preference.performClick(Preference.java:...)
	at android.view.View.performClick(View.java:7659)
```

## Causa raiz

Confirmada no código-fonte em [TVBaseSettingsActivity.kt:51-58](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/shared/TVBaseSettingsActivity.kt#L51-L58):

```kotlin
override fun onPreferenceStartScreen(
    caller: PreferenceFragmentCompat,
    pref: PreferenceScreen,
): Boolean {
    val fragment = createFragment()
    val args = Bundle(fragment.arguments)
    args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.key)
    fragment.arguments = args
    startPreferenceFragment(fragment)
    return true
}
```

O método `createFragment()` retorna uma nova instância limpa do fragmento de configurações, cuja propriedade `fragment.arguments` é **`null`**.
Ao chamar `Bundle(fragment.arguments)`, o compilador resolve para o construtor de cópia `Bundle(Bundle from)`.
No Android (implementação de `BaseBundle`), o construtor de cópia faz:
```java
BaseBundle(BaseBundle from) {
    synchronized (from) { // <--- Null reference used for synchronization
        mMap = from.mMap;
    }
}
```
Como `from` é `null`, o runtime tenta sincronizar no ponteiro nulo (`monitor-enter`) e lança `NullPointerException`.

## Como reproduzir

1. Em um emulador Android TV ou aparelho físico (ex.: TCL Smart TV com Android 9/11).
2. Abrir o app e navegar até "Configurações".
3. Clicar em qualquer preferência que expanda uma nova tela (`PreferenceScreen`).
4. O app crasha imediatamente.

## Correção

Aplicada em [TVBaseSettingsActivity.kt:53](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/shared/TVBaseSettingsActivity.kt#L53):

```kotlin
- val args = Bundle(fragment.arguments)
+ val args = fragment.arguments?.let { Bundle(it) } ?: Bundle()
```

`createFragment()` sempre devolve uma instância nova do fragmento, cujo `arguments` ainda não foi setado (`null`). O construtor de cópia `Bundle(Bundle from)` faz `synchronized(from)` sem checar nulidade — passar `null` derruba o app com NPE de monitor-enter. A troca evita o construtor de cópia quando não há Bundle de origem, criando um `Bundle()` vazio nesse caso.

## Validação

Correção revisada contra o código-fonte atual do arquivo (linhas 48-58) — o trecho batia exatamente com o relatado. Build Kotlin não introduz mudança de tipo (continua `Bundle`). Não foi possível rodar o app em um device/emulador Android TV nesta sessão; recomenda-se validar manualmente a navegação por submenus de configurações em um teste manual antes do próximo release.

## Lição

`Bundle(possivelmenteNull)` resolve silenciosamente para o construtor de cópia `Bundle(Bundle from)`, que sincroniza em `from` sem checar nulidade — isso é um pitfall geral do Android, não específico deste fragmento. Qualquer `Bundle(x)` onde `x` é nullable deve usar `x?.let { Bundle(it) } ?: Bundle()`.

## Recorrência (Triagem 2026-09-22)

- **Novos IDs associados:** 8206, 7401, 7326, 7324, 7323, 7322, 7321, 7318, 7315 (9 ocorrências)
- **Diagnóstico:** Todos os 9 eventos apresentaram a exata mesma assinatura (`NullPointerException: Null reference used for synchronization (monitor-enter)` em `BaseBundle.copyInternal`). Trata-se de aparelhos que ainda operavam na versão anterior (1.17.19) ou instâncias prévias à distribuição do patch. Fechados na telemetria.

