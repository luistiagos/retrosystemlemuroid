# Proteção do ciclo de vida do LibretroDroid

Correção de 2026-09-23 para a corrida entre `GLRetroView.onDestroy()` e chamadas JNI,
incluindo `retro_run` do FBNeo. `GLSurfaceView.onPause()` tem timeout de 500 ms;
retornar dele não garante que o frame terminou.

- `core-lifecycle.patch`: alteração de `GLRetroView.kt` sobre o checkout local
  `../LibretroDroid-patched` anterior à correção.
- `CoreWorkGuard.kt`: conta chamadas em andamento e adia a destruição até a última
  terminar. Não mantém o monitor preso durante execução nativa nem espera pela GLThread.
- `../../tests/native/CoreWorkGuardTest.kt`: sete testes JUnit, incluindo um frame
  bloqueado durante o encerramento, chamadas sobrepostas e destruição concorrente.
- `../../tests/native/test-core-work-guard.ps1`: compila e executa esses testes
  diretamente contra `classes.jar` extraído do AAR consumido pelo app. Não precisa
  do checkout externo nem de ROMs; usa JDK e dependências do cache Gradle local.
- `update-lifecycle-aar.py`: atualiza somente as classes `GLRetroView` e
  `CoreWorkGuard` no AAR. Verifica que recursos, manifesto, outras classes e todas
  as bibliotecas nativas continuam idênticos. Requer Python 3.

O checkout-fonte local também recebeu a correção. Estes arquivos a preservam neste
repositório, que consome `libs/libretrodroid-patched.aar`, sem depender de um commit
no checkout externo. O core FBNeo não foi substituído.

## Validar o AAR empacotado

Na raiz do Lemuroid:

```powershell
./tests/native/test-core-work-guard.ps1
```

O runner não recompila `CoreWorkGuard.kt`: os sete testes exercitam a classe que
será distribuída no AAR. Os logs JUnit e o disassembly do renderer ficam em
`tmp/core-work-guard-test`. O disassembly permite conferir também a integração
`begin` / `step` / `end` (normal e excepcional), além do contador isolado.

## Reproduzir a compilação

Na raiz do Lemuroid, com Java/Android SDK e dependências Gradle configurados:

```powershell
$sourceRoot = '../LibretroDroid-patched'
# Apenas em um checkout que ainda não recebeu esta correção:
git -C $sourceRoot apply --check "$PWD/libs/libretrodroid-patches/core-lifecycle.patch"
git -C $sourceRoot apply "$PWD/libs/libretrodroid-patches/core-lifecycle.patch"
Copy-Item libs/libretrodroid-patches/CoreWorkGuard.kt "$sourceRoot/libretrodroid/src/main/java/com/swordfish/libretrodroid/"

$testPath = "$sourceRoot/libretrodroid/src/test/java/com/swordfish/libretrodroid"
New-Item -ItemType Directory -Force $testPath
Copy-Item tests/native/CoreWorkGuardTest.kt $testPath
& "$sourceRoot/gradlew.bat" -p $sourceRoot :libretrodroid:testReleaseUnitTest --tests com.swordfish.libretrodroid.CoreWorkGuardTest --console=plain

python libs/libretrodroid-patches/update-lifecycle-aar.py --aar libs/libretrodroid-patched.aar --classes-directory "$sourceRoot/libretrodroid/build/tmp/kotlin-classes/release" --output "$sourceRoot/libretrodroid/build/lifecycle-patched.aar"
# Só substituir após o script terminar com sucesso:
Copy-Item "$sourceRoot/libretrodroid/build/lifecycle-patched.aar" libs/libretrodroid-patched.aar
./gradlew.bat :lemuroid-app:assembleFreeBundleDebug --console=plain
```

Os testes são de JVM e não precisam de ROMs. Ainda é necessário testar a jogabilidade
de KOF '97 e Real Bout Fatal Fury Special, incluindo pausa/retomada e saída durante
frames lentos, em aparelho arm64. Os testes de concorrência não substituem essa validação.

# Ritmo de quadros com painel de taxa variável (`fpssync-vsync-real.patch`)

Correção de 2026-10-29 para `docs/bugs/open/2026-10-23-pokemon-ds-roda-acelerado-com-acelerar-desligado-moto-g86.md`.
O `FPSSync` escolhia o modo vsync (um `retro_run` por `onDrawFrame`) pela taxa do painel lida uma vez na criação.
Painel que sobe para 90/120 Hz depois (boost de toque do MediaTek no Moto G86) acelerava o jogo junto. Agora o modo
vsync mede a taxa real de chamadas em janelas de 60 e, acima de 1,2x a taxa do conteúdo, passa para o ritmo por
relógio, que já existia, até o fim da sessão.

Somente os `jni/*/liblibretrodroid.so` do AAR foram trocados (`classes.jar` e o resto idênticos). Os campos novos
ficam no fim da classe, para o teste também rodar contra o `.so` antigo (controle negativo):

```powershell
git -C ../LibretroDroid-patched apply "$PWD/libs/libretrodroid-patches/fpssync-vsync-real.patch"
./tests/native/test-fpssync-vsync.ps1 -Serial <aparelho arm64>      # ou -Abi x86_64 no AVD
```
