# [BUG] build_and_install_connected.ps1 falhando ("JAVA_HOME is not set")

**Data:** 2026-06-19
**Status:** CORRIGIDO
**Severidade:** MÉDIA — bloqueava build+install via script

---

## Sintoma

Rodar `.\build_and_install_connected.ps1` falhava com:
```
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
```
E, quando `JAVA_HOME` era forçado, o Gradle ainda falhava com:
```
Value 'C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot' given for org.gradle.java.home
Gradle property is invalid (Java home supplied is invalid)
```

## Causa-raiz

Dois problemas somados:

1. **`gradle.properties`** tinha `org.gradle.java.home` apontando para um JDK **inexistente**
   nesta máquina (`C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot`).
2. O **`gradlew.bat` precisa de `JAVA_HOME` (ou `java` no PATH) para dar bootstrap** *antes* de
   ler o `gradle.properties` — e o script não definia `JAVA_HOME`. Então falhava cedo, antes
   mesmo de chegar no Gradle.

## Correção

**Arquivos:** [gradle.properties](../../../gradle.properties), [build_and_install_connected.ps1](../../../build_and_install_connected.ps1)

1. Corrigido `org.gradle.java.home` para o JDK real da máquina:
   `C:\Android\Android Studio\jbr` (JBR que vem com o Android Studio).
2. Adicionada a função **`Resolve-JavaHome`** no script, que define `JAVA_HOME` antes de chamar
   o `gradlew.bat`. Ordem de resolução:
   1. `JAVA_HOME` já definido e válido;
   2. `org.gradle.java.home` do `gradle.properties`;
   3. JBR do Android Studio (locais comuns);
   4. `java` no PATH.

Assim o script funciona sem precisar configurar nada manualmente.

## Validação
- `:lemuroid-app:assembleFreeBundleDebug` — **BUILD SUCCESSFUL**.
- Mesmo padrão reaplicado depois ao `build_apk.ps1` (ver
  [2026-06-21-build-apk-release-assinatura.md](2026-06-21-build-apk-release-assinatura.md)).
