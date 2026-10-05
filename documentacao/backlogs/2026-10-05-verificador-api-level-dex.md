# [BACKLOG] Verificador de build: API acima do `minSdk` no bytecode das dependências

**Data:** 2026-10-05
**Origem:** lição de `documentacao/bugs/done/2026-10-05-padkit-kotlinx-datetime-java-time-android-7.md`
(pitfall 16 do `CLAUDE.md`)

## Situação

O `NewApi` do lint só lê o código-fonte do projeto. O padkit chamou `java.time` (API 26) no caminho de
montagem do analógico, e nada no build avisou: o crash só apareceu porque alguém abriu um jogo de PSP no
AVD Android 7.1. O *core library desugaring* (ligado na correção) cobre `java.time`, `java.util.stream`,
`java.util.function`, `Optional` etc., mas **não** cobre `java.nio.file`, `java.lang.invoke` nem as APIs
novas de `javax.net.ssl`/`java.security.cert` — a próxima biblioteca que chamar uma delas sem guarda
cai do mesmo jeito.

Hoje a proteção é uma regra no `CLAUDE.md` (pitfall 16, regra 2) e o script manual
[`audit_dex_api_level.py`](../../audit_dex_api_level.py). Instrução em documento não é guard (pitfall 6).

## O que o protótipo (`audit_dex_api_level.py`) já resolve

- Lê os `classes*.dex` do APK: tabelas de string/type/proto/field/method ids e o bytecode de cada método
  (tabela de tamanho de opcode + *payloads* de switch/fill-array). A chamadora é a classe que contém a
  instrução, ou que declara o supertipo.
- Resolve cada referência `java/…`/`javax/…` contra `platforms/android-35/data/api-versions.xml`,
  pegando o **menor** nível por qualquer caminho de herança (o `api-versions.xml` move métodos —
  `Method.getName` aparece em `Executable`, API 26 — e redeclara outros em níveis novos —
  `NavigableSet.iterator`, API 35; sem o mínimo entre caminhos isso vira falso positivo).
- Ignora referência a classe que o próprio APK define: a `desugar_jdk_libs` 2.x embarca o pacote
  `java.util.function` inteiro para minSdk < 24.
- No release o R8 move cada chamada a API nova para `…$$ExternalSyntheticApiModelOutlineN` (ofuscado)
  **e reaproveita o mesmo outline entre classes**, com o nome de uma delas só: o
  `getSystemService(Class)` do padkit saía num outline batizado de
  `androidx.compose.ui.autofill.AndroidAutofill`. Com `--mapping`, o script reconhece os outlines e
  atribui a chamada a quem os invoca — sem isso, filtrar `androidx/` escondia o padkit.
- `--android` inclui as APIs de framework, que o desugaring nunca cobre (foi como o crash do
  Android 5.0–5.1 no haptics do padkit apareceu). Com `--skip Landroidx/ --skip 'Lj$/'` sobram ~60
  classes no release, quase todas guardadas (Material, Coil, código do app que o lint já vê).
- Roda em ~5 s no release (3 dex, ~11 MB).

## Linha de base (release 1.17.24, com desugaring)

Tirando a própria `j$` (wrappers de conversão da biblioteca desugarizada, que só rodam ao trocar tipos
com a plataforma em API nova), sobram só caminhos de plataforma guardados — triagem no bug doc:
`okio.NioSystemFileSystem`/`Path` (`Class.forName("java.nio.file.Files")`), Conscrypt
(`Platform.supportsX509ExtendedTrustManager()` = `SDK_INT > 23`; o app usa o próprio trust manager),
Retrofit (`hasJava8Types` = `SDK_INT >= 24`), okhttp `Jdk9Platform` (nunca escolhida no Android),
emoji2 `…_API24`.

## Proposta

Task `verifyDexApiLevel` no `buildSrc`, no molde de `FlycastCoreVerifier`/`BundledCoresVerifier`:

1. Port do script para Kotlin (o parser cabe em ~200 linhas; `api-versions.xml` do `compileSdk`).
2. Entrada: o APK **de release** da variante (`androidComponents.onVariants` +
   `variant.artifacts.get(SingleArtifact.APK)` e `SingleArtifact.OBFUSCATION_MAPPING_FILE`), task
   pendurada em `assemble<Variant>Release`. No debug, sem R8, a lista inclui todo código morto de
   biblioteca (commons-io inteira, p. ex.) — não serve como gate.
3. Baseline versionado (`lemuroid-app/config/dex-api-baseline.txt`): pares `classe de origem → API`
   já triados, incluindo `android.*`. Classe ou API nova **falha o build** com a lista do que abrir.
   Pacote `j$/` fica fora por regra, não por baseline. O código do próprio app (`com/swordfish/`) pode
   ficar fora também — o lint já o cobre.
4. Validar que o guard pega regressão: desligar o desugaring num build local tem que falhar a task
   apontando `kotlinx.datetime.Instant → java/time/Instant.ofEpochSecond`; e, enquanto o padkit não for
   corrigido, `gg.padkit.haptics.AndroidHapticGenerator → Context.getSystemService(Class)` tem que
   estar no baseline marcado como bug aberto, não como guardado.

## Custo de não fazer

Qualquer atualização de dependência (BOM do Compose, okhttp, coil, padkit) pode trazer uma chamada nova
sem guarda, e o único detector é alguém testar o sistema certo num Android 5–7.
