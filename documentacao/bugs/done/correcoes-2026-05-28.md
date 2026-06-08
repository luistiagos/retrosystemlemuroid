# Correções — 28 de Maio de 2026

Horário: **28/05/2026**  
Build final: **BUILD SUCCESSFUL** — APK instalado no dispositivo `ZY32LMNN9B`.

---

## Correção 1 — APK release recusado como "pacote inválido" no Motorola Edge 60 Fusion

### Arquivos
`lemuroid-app/build.gradle.kts`  
`build_apk.ps1`

### Severidade
**ALTO** — bloqueava a instalação manual do APK release em aparelho real.

### Sintoma
Ao tentar instalar o APK `Retro Game System` pelo gerenciador de arquivos do Motorola Edge 60 Fusion, o instalador do Android exibia:

> Como o pacote parece ser inválido, o app não foi instalado.

O APK local validava corretamente com `aapt` e `apksigner`, mas a instalação via UI do Android falhava com mensagem genérica.

### Causa raiz
O build release continuava usando o mesmo `applicationId` do Lemuroid upstream:

```kotlin
applicationId = "com.swordfish.lemuroid"
```

Em aparelhos onde o Lemuroid oficial/F-Droid já estava instalado, o Android tratava o `Retro Game System` como uma atualização do mesmo pacote. Como o APK local é assinado com outra chave (`release.jks` do projeto), a instalação era recusada por conflito de assinatura. A UI do Package Installer mascarava o erro técnico e mostrava apenas "pacote inválido".

### Correção

Foi adicionado um sufixo de package id somente para o build release:

```kotlin
getByName("release") {
    applicationIdSuffix = ".retrogamesystem"
    isMinifyEnabled = true
    isShrinkResources = true
    signingConfig = signingConfigs["release"]
    proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
    resValue("string", "lemuroid_name", "Retro Game System")
}
```

Com isso, o APK final passa a instalar como pacote independente:

```text
com.swordfish.lemuroid.retrogamesystem
```

O script `build_apk.ps1` também foi atualizado para orientar a remoção dos pacotes corretos em caso de falha de instalação via ADB:

```text
com.swordfish.lemuroid.retrogamesystem
com.swordfish.lemuroid
com.swordfish.lemuroid.debug
```

### Validação

Build release:

```text
BUILD SUCCESSFUL in 5m 55s
APK final: E:\projects\lemuroid\Lemuroid\dist\retro-game-system.apk
```

Validação do APK:

```text
package: name='com.swordfish.lemuroid.retrogamesystem' versionCode='231' versionName='1.17.0'
application-label:'Retro Game System'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
Verified using v1 scheme: true
Verified using v2 scheme: true
Verified using v3 scheme: true
```

Instalação no Motorola Edge 60 Fusion:

```text
List of devices attached
ZY32LMNN9B      device

Performing Streamed Install
Success
```

Confirmação pós-instalação:

```text
pkg=Package{... com.swordfish.lemuroid.retrogamesystem}
versionCode=231 minSdk=21 targetSdk=35
versionName=1.17.0
pidof com.swordfish.lemuroid.retrogamesystem -> 20975
```

### Resultado

O `Retro Game System` agora instala lado a lado com o Lemuroid oficial, evitando conflito de assinatura e eliminando a mensagem de "pacote inválido" nesse dispositivo.