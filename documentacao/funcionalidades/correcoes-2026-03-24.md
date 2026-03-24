# Relatório de Correções — 24 de Março de 2026

## Resumo das Verificações Recursivas

Esta revisão executou múltiplos passos de verificação de bugs até que nenhum erro fosse encontrado:

| Passo | Verificação | Resultado |
|-------|-------------|-----------|
| 1 | Build completo (`assembleFreeDynamicDebug`) | ✅ BUILD SUCCESSFUL |
| 2 | Lint Android (`lintFreeDynamicDebug`) | ❌ 2 erros, 61 avisos |
| 3 | Correção dos erros de lint | Bugs corrigidos |
| 4 | Re-verificação lint | ❌ Ainda havia avisos críticos |
| 5 | Revisão profunda do código (análise estática) | ❌ +19 bugs encontrados |
| 6 | Correção dos bugs de runtime | Bugs corrigidos |
| 7 | Compilação completa + lint final | ✅ 0 erros, BUILD SUCCESSFUL |

---

## Erros Críticos Corrigidos (Lint)

### 1. String de formato inválida — Tradução Turca
- **Arquivo:** `lemuroid-app/src/main/res/values-tr-rTR/strings.xml` (linha 138)
- **Problema:** A string `gamepad_binding_update_title` na localidade turca não continha o marcador `%1$s`, causando crash ao chamar `String.format()`.
- **Antes:** `gamepad bağlama güncelleme başlığı`
- **Depois:** `"RetroPad %1$s" İlişkilendir`

### 2. String de formato inválida — Tradução Húngara
- **Arquivo:** `lemuroid-app/src/main/res/values-hu-rHU/strings.xml` (linha 31)
- **Problema:** A string `settings_retropad_button_name` na localidade húngara não continha o marcador `%1$s`, causando crash ao chamar `String.format()`.
- **Antes:** `RetroPad`
- **Depois:** `RetroPad %1$s`

---

## Avisos de Logging Corrigidos

### 3. Uso de `Log` em vez de `Timber` — RomsDownloadManager
- **Arquivo:** `lemuroid-app/src/main/java/.../roms/RomsDownloadManager.kt`
- **Problema:** O arquivo usava `android.util.Log` em vez da biblioteca `Timber` já presente no projeto. 21 ocorrências.
- **Correção:** Substituídas todas as chamadas `Log.d/e/w(TAG, ...)` por `Timber.d/e/w(...)`, removido `import android.util.Log`, adicionado `import timber.log.Timber`.

### 4. Uso de `Log` em vez de `Timber` — StorageFrameworkPickerLauncher
- **Arquivo:** `lemuroid-app/src/main/java/.../settings/StorageFrameworkPickerLauncher.kt`
- **Problema:** Uma chamada `Log.e(TAG, "Failed to move ROMs", e)` usava a API deprecated em vez de Timber.
- **Correção:** Substituída por `Timber.e(e, "Failed to move ROMs")`.

---

## Bugs de Código Kotlin Corrigidos

### 5. `capitalize()` sem Locale — CoverUtils
- **Arquivo:** `lemuroid-app/src/main/java/.../covers/CoverUtils.kt` (linha 75)
- **Problema:** O método `.capitalize()` foi chamado sem especificar um `Locale`, o que pode causar comportamento incorreto em localidades como o turco (onde 'i' maiúsculo não é 'I').
- **Correção:** Substituído por `.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }`.

### 6. `capitalize()` sem Locale — LemuroidCoreOption
- **Arquivo:** `lemuroid-app/src/main/java/.../coreoptions/LemuroidCoreOption.kt` (linha 21)
- **Problema:** O mesmo problema de `capitalize()` sem `Locale` para strings exibidas ao usuário.
- **Correção:** Substituído por `replaceFirstChar` com `Locale.getDefault()`.

### 7. `@NonNull` desnecessário em Kotlin — TVFolderPickerFolderFragment
- **Arquivo:** `lemuroid-app/src/main/java/.../folderpicker/TVFolderPickerFolderFragment.kt`
- **Problema:** Anotação `@NonNull` redundante em Kotlin, onde a nulidade é inferida pelo próprio tipo.
- **Correção:** Removida a anotação `@NonNull` e o import `androidx.annotation.NonNull`.

### 8. `@NonNull` desnecessário em Kotlin — TVFolderPickerStorageFragment
- **Arquivo:** `lemuroid-app/src/main/java/.../folderpicker/TVFolderPickerStorageFragment.kt`
- **Correção:** Removida a anotação `@NonNull` e o import.

### 9. `@NonNull` desnecessário em Kotlin — TVGamePadBindingFragment (input)
- **Arquivo:** `lemuroid-app/src/main/java/.../input/TVGamePadBindingFragment.kt`
- **Correção:** Removida a anotação `@NonNull` e o import.

### 10. `@NonNull` desnecessário em Kotlin — TVGamePadBindingFragment (settings)
- **Arquivo:** `lemuroid-app/src/main/java/.../settings/TVGamePadBindingFragment.kt`
- **Correção:** Removida a anotação `@NonNull` e o import.

---

## Melhorias de Layout XML

### 11. Tag `<fragment>` substituída por `FragmentContainerView` — activity_empty_navigation.xml
- **Arquivo:** `lemuroid-app/src/main/res/layout/activity_empty_navigation.xml`
- **Problema:** A tag `<fragment>` é depreciada. `FragmentContainerView` é a alternativa recomendada que usa `FragmentTransaction` internamente.
- **Correção:** Substituída `<fragment>` por `<androidx.fragment.app.FragmentContainerView>`.

### 12. Tag `<fragment>` substituída — activity_empty_navigation_overlay.xml
- **Arquivo:** `lemuroid-app/src/main/res/layout/activity_empty_navigation_overlay.xml`
- **Correção:** Mesma substituição.

### 13. Tag `<fragment>` substituída — activity_tv_main.xml
- **Arquivo:** `lemuroid-app/src/main/res/layout/activity_tv_main.xml`
- **Correção:** Mesma substituição.

### 14. `getDrawable()` substituído por `AppCompatResources.getDrawable()` — ChannelHandler
- **Arquivo:** `lemuroid-app/src/main/java/.../channel/ChannelHandler.kt`
- **Problema:** `context.getDrawable()` não usa o processamento de compat para drawables vetoriais em APIs < 21.
- **Correção:** Substituído por `AppCompatResources.getDrawable(context, resourceId)`.

---

## Bugs de Runtime / Segurança Corrigidos

### 15. NullPointerException em `Uri.path!!` — AllFilesStorageProvider
- **Arquivo:** `retrograde-app-shared/src/main/java/.../storage/local/AllFilesStorageProvider.kt` (linhas 78, 83, 110)
- **Problema:** `Uri.parse(...).path` pode retornar `null` para URIs do tipo `content://`. O uso de `!!` causaria `NullPointerException`.
- **Correção:** Substituído `file!!` por verificação explícita:
  ```kotlin
  val path = uri.path ?: throw IOException("Cannot resolve path for URI: $uri")
  ```

### 16. NullPointerException em `Uri.path` — LocalStorageProvider
- **Arquivo:** `retrograde-app-shared/src/main/java/.../storage/local/LocalStorageProvider.kt` (linhas 92, 97, 125)
- **Problema:** `Uri.path` poderia ser `null` e era passado para `File()` sem verificação.
- **Correção:** Adicionadas verificações explícitas com `?: throw IOException(...)`.

### 17. Force-unwrap `message!!` — BaseGameScreen
- **Arquivo:** `lemuroid-app/src/main/java/.../game/BaseGameScreen.kt` (linha 49)
- **Problema:** `message!!` dentro de `AnimatedVisibility`. Embora a condição `message != null` fosse verificada, Kotlin não faz smart-cast dentro de lambdas composable, tornando o `!!` um risco.
- **Correção:** Substituído por `message ?: ""` para eliminar o risco de NPE.

---

## Melhorias de Código

### 18. Verificação de SDK obsoleta removida — MainActivity
- **Arquivo:** `lemuroid-app/src/main/java/.../main/MainActivity.kt` (linha 137)
- **Problema:** `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)` é sempre verdadeiro pois o `minSdkVersion` é 23 (Android M).
- **Correção:** Removida a verificação desnecessária e o import `android.os.Build`.

### 19. Supressão de `StaticFieldLeak` com documentação — HomeViewModel
- **Arquivo:** `lemuroid-app/src/main/java/.../home/HomeViewModel.kt` (linha 81)
- **Problema:** O lint alertava sobre possível vazamento de contexto em um ViewModel. O contexto armazenado (`appCtx`) é sempre o `applicationContext`, não um contexto de Activity.
- **Correção:** Adicionada anotação `@SuppressLint("StaticFieldLeak")` com comentário explicativo.

---

## Resultado Final

```
BUILD SUCCESSFUL
Lint: 0 erros, avisos restantes apenas informativos
Compilação: SUCCESS (todos os módulos)
```

O APK debug foi gerado em:
`lemuroid-app/build/outputs/apk/freeDynamic/debug/`
