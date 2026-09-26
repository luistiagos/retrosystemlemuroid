# [BUG] Android TV: NullPointerException em `SearchBar.startRecognition()` ao sair do `TVSearchFragment`

**Data:** 2026-09-24 (detectado) · 2026-09-26 (resolvido)
**Status:** ✅ **Resolvido** em 2026-09-26. Reproduzido no AVD Android 7.1 (13 crashes em 15
tentativas) e validado com a correção: 0 em 12 rodadas válidas em debug e 0 em 12 em release com R8.
**Severidade:** Média. Crash do processo principal ao sair da busca da TV logo depois de abri-la.
**Branch:** version9
**Origem:** telemetria `retrogamesystem/main` (`SourceFile::androidx.leanback.app.j$d.run`), error
8491 (1 ocorrência), TCL Smart TV Pro (Android 11, SDK 30, `armeabi-v7a`, app `1.17.22`)

---

## Sintoma

Abrir a busca na Android TV e sair dela (BACK) logo em seguida mata o app na main thread:

```
java.lang.NullPointerException: Attempt to invoke virtual method 'void androidx.leanback.widget.SearchBar.i()' on a null object reference
	at androidx.leanback.app.j$d.run(SourceFile:8)
	at android.os.Handler.handleCallback(Handler.java:967)
	at android.os.Handler.dispatchMessage(Handler.java:104)
	at android.os.Looper.loop(Looper.java:250)
	at android.app.ActivityThread.main(ActivityThread.java:7848)
```

No build ofuscado, `androidx.leanback.app.j` é o `SearchSupportFragment`, `j$d` é a classe anônima
`SearchSupportFragment$4` (o `mStartRecognitionRunnable`) e `SearchBar.i()` é
`SearchBar.startRecognition()`. No build debug o mesmo crash sai sem ofuscação:

```
java.lang.NullPointerException: Attempt to invoke virtual method 'void androidx.leanback.widget.SearchBar.startRecognition()' on a null object reference
	at androidx.leanback.app.SearchSupportFragment$4.run(SearchSupportFragment.java:204)
	at android.os.Handler.handleCallback(Handler.java:751)
```

---

## Causa-raiz

Bug da Leanback (`androidx.leanback:leanback:1.1.0-rc01`), confirmado com `javap -c` no jar do
cache do Gradle:

1. [TVSearchFragment.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/search/TVSearchFragment.kt)
   chama `setSearchResultProvider(this)` no `onViewCreated()`. A Leanback posta
   `mSetSearchResultProvider` (`$3`) no `mHandler`, um `Handler` que o **próprio fragment** cria no
   construtor.
2. Quando `$3` roda e `mAutoStartRecognition` é `true` (o default quando o fragment é criado sem
   estado salvo), ele faz `mHandler.postDelayed(mStartRecognitionRunnable, 300)`.
3. `mStartRecognitionRunnable` (`$4`) é só isto, sem checar nulo:
   ```java
   mAutoStartRecognition = false;
   mSearchBar.startRecognition();
   ```
4. `SearchSupportFragment.onDestroyView()` faz `mSearchBar = null; mRowsSupportFragment = null;` e
   **não** remove o runnable do `mHandler`.

BACK dentro desses 300 ms destrói a view e deixa o runnable pendente, que roda em seguida com
`mSearchBar` nulo. O `$3` não tem esse problema porque checa `mRowsSupportFragment == null` antes de
tudo.

---

## Correção

[SearchSupportFragmentRecognition.kt](../../../lemuroid-app/src/main/java/androidx/leanback/app/SearchSupportFragmentRecognition.kt),
no pacote `androidx.leanback.app` dentro do `lemuroid-app`:

```kotlin
internal fun SearchSupportFragment.cancelPendingAutoStartRecognition() {
    mHandler.removeCallbacks(mStartRecognitionRunnable)
}
```

E no `TVSearchFragment`:

```kotlin
override fun onDestroyView() {
    cancelPendingAutoStartRecognition()
    super.onDestroyView()
}
```

O comportamento não muda: quem fica na tela continua tendo o reconhecimento de voz iniciado
sozinho, como antes. Voltando da back stack com a view recriada, o `onCreateView` da Leanback chama
`onSetSearchResultProvider()` de novo (porque `mProvider != null`) e reagenda o início normalmente.

### Por que o arquivo mora em `androidx.leanback.app`

`mHandler` e `mStartRecognitionRunnable` são package-private. As alternativas foram descartadas:

- **Reflexão:** o R8 renomeia os campos da Leanback no release (o próprio stack de produção mostra
  `androidx.leanback.app.j`). Lookup por nome em string quebraria só no build distribuído.
- **`setSearchQuery("", false)`**, a única API pública que remove o runnable. Ela também chama
  `SearchBar.setSearchQuery("")`, que faz `setText` e dispara `onSearchQueryChange` →
  `TVSearchFragment.onQueryTextChange("")`. No `onDestroyView` isso zeraria o `searchDebounce` de um
  fragment que sobrevive na back stack. No `onViewCreated` (o que o relatório original sugeria),
  desligaria de vez o início automático da busca por voz, uma mudança de UX.
- **`view?.handler?.removeCallbacksAndMessages(null)`**, a outra sugestão do relatório original, não
  corrige nada: `View.getHandler()` é o handler do `ViewRootImpl`, não o `mHandler` do fragment, e
  limpá-lo com `null` descartaria **todas** as mensagens pendentes da janela.

O acesso é checado em tempo de compilação: se uma versão nova da Leanback renomear um dos dois
campos, o build quebra em vez de o crash voltar em silêncio.

---

## Validação

AVD `lemu_api25_2gb` (Android 7.1, x86_64), `MainTVActivity` aberta direto por `am start`. A corrida
é disparada lançando o BACK em paralelo com o toque no orb de busca, com atraso variável, porque cada
`input` leva centenas de ms só para subir:

```sh
adb shell am force-stop $P
adb shell am start -f 0x10008000 -n $P/com.swordfish.lemuroid.app.tv.main.MainTVActivity
adb shell "(sleep $d; input keyevent 4) & input tap 215 150; wait"
```

Uma rodada só conta se a `MainTVActivity` estava em primeiro plano antes do toque **e** continua em
primeiro plano depois do BACK. Se a busca não tivesse aberto, o BACK na home (com a barra lateral
aberta) sairia da activity. No build antigo, cada crash já prova sozinho que a busca abriu.

| Build | BACK em 0,1–0,3 s | BACK em 0,4–0,5 s |
|-------|-------------------|-------------------|
| Antes, debug (APK de 2026-09-25, mesmo código de busca desde 2026-09-03) | **13/15 crash** | 0/5 |
| Depois, debug | **0/12 crash** (rodadas válidas) · 0/18 numa primeira passada sem o critério | — |
| Depois, release com R8 | **0/12 crash** (rodadas válidas) | — |

Conferido também, no build corrigido (debug e release):

- A busca abre (`lb_search_bar` presente na hierarquia) e o BACK volta para a home.
- Ficando na tela, o reconhecimento de voz ainda inicia sozinho: `GRecognitionServiceImpl:
  #startListening` no logcat.
- No release, o `mapping.txt` mostra `mHandler -> r0` e `mStartRecognitionRunnable -> u0` (uma
  reflexão por nome falharia) e o acessor mantido no pacote da Leanback
  (`SearchSupportFragmentRecognitionKt -> androidx.leanback.app.k`), sem inline no
  `TVSearchFragment`. O `onDestroyView` chama o acessor em toda saída da busca, e nenhuma das rodadas
  deu `IllegalAccessError`.
- `:lemuroid-app:ktlintMainSourceSetCheck` passa com o arquivo novo.

> ⚠️ **Armadilha do teste:** um diálogo de permissão (`GrantPermissionsActivity`) aberto pelo início
> automático da voz fica órfão no topo da task. O `am force-stop` não o remove, e o `am start`
> simples só traz a task de volta, com o diálogo por cima. A primeira passada no release deu "0/12"
> com os toques caindo no diálogo, não no orb. Por isso: `RECORD_AUDIO` concedido por `pm grant`
> antes de começar, `am start -f 0x10008000` (`NEW_TASK | CLEAR_TASK`) e o critério de validade acima.

---

## Lição

- **Relatório de bug com "correção recomendada" é hipótese, não especificação.** As duas sugestões
  daqui estavam erradas: uma apagava a busca do usuário, a outra limpava o handler errado (e todas
  as mensagens da janela). Só a desmontagem do `onDestroyView`, do `setSearchQuery` e do construtor
  do fragment mostrou isso.
- **`View.getHandler()` não é o handler de quem postou.** Cancelar um runnable exige o mesmo
  `Handler` em que ele foi postado. Em biblioteca, conferir no bytecode qual é.
- **Para chegar a membro package-private de biblioteca, arquivo no mesmo pacote, nunca reflexão.**
  O R8 renomeia a biblioteca junto com o app. O acesso direto é compilado e mantido correto por ele;
  a string de reflexão, não.
- **Fragment da Leanback que agenda trabalho no próprio `Handler`**: conferir se o `onDestroyView`
  cancela o trabalho antes de anular as views que ele usa.
