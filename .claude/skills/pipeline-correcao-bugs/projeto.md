# Manual do projeto — Lemuroid (RetroSystem Android)

O que as `etapas/*.md` e o `SKILL.md` deixam para o projeto. Cada secao diz qual etapa a cita.
Nunca sobrescrito pela sincronizacao das copias (a fonte da skill e
`C:\projects\retrobatnew\source\skills\pipeline-correcao-bugs`; DESIGN secao 22). A config que o MOTOR le
(build, base, gerados, preflight) esta no `projeto.json` ao lado; aqui fica o que a SESSAO precisa saber.
Fatos medidos que sustentam este manual: DESIGN 22.11.D.

## Caminhos

- Arvore principal (repo): `C:\projects\lemuroid\Lemuroid` (a raiz do projeto e o proprio repo).
- Pasta do pipeline: `C:\projects\lemuroid\.bugfix-pipeline-Lemuroid` (`state.json`, `lanes\`, `reports\`, `runs\`), fora
  do repo.
- Fluxo principal: `origin/version9`. Remoto unico: `origin`.
- Helper: `python "C:\projects\lemuroid\Lemuroid\.claude\skills\pipeline-correcao-bugs\scripts\pipeline.py"`.
- Onde a skill mora: versionada em `.claude/skills/pipeline-correcao-bugs/` (aqui `.claude/` e versionado, sem
  junction). E COPIA da fonte no retrobatnew: so `projeto.json` e este arquivo se editam aqui (`SKILL.md`,
  "Fonte e copias").
- Caminhos de arquivo no plano e nos `claim`: relativos a raiz do repo (`lemuroid-app/src/main/java/...`,
  `retrograde-app-shared/src/main/java/...`).
- **Submodulo `lemuroid-cores`** (os `.so` dos cores e o modulo `:bundled-cores`): numa worktree nova ele vem VAZIO. O
  `build` do helper o povoa a partir do clone da arvore principal (sem rede, ~16 s na 1a vez) no commit que o ponteiro
  da base registra. Antes do 1o `build` a pasta da lane esta vazia: para LER o fonte de um core, leia na arvore
  principal (`C:\projects\lemuroid\Lemuroid\lemuroid-cores\...`), sem escrever la.

## Leituras obrigatorias (planning Passo 1, dev Passo 1)

- `docs/bugs/open/README.md` (template, ciclo de pastas, criterio de complexidade) e `docs/bugs/retest/README.md` (a
  secao `## Status`).
- `CLAUDE.md` (raiz): a arquitetura, as "Convencoes de Codigo" (Toast so por `displayToast`, Intent implicita so por
  `startActivitySafely`, rede so por `NetworkCompat`, Activity com extra ausente -> `finish(); return`) e os **17
  "Pitfalls de Android / Room"**, todos bugs reais de producao com a regra para nao regredir. Leia o pitfall do modulo
  que o fix toca (Room/`createFromAsset`, `minSdk 21` e `SDK_INT` que mente nas TV box, processo `:game`, cores).
  **Ignore la o "Workflow de Documentacao"**: o pipeline escreve em `docs/bugs/` (secao Maestro).
- `prj.md` (raiz): especificacao por area (fluxo click -> launch, entrega de ROM, busca, catalogo, Transferir Jogos,
  portas do controle) e o "Key File Index".

## O app e o runtime (todas as etapas)

- O app e o **RetroSystem** (fork do Lemuroid): emulador Android multi-sistema sobre Libretro. Modulos:
  `lemuroid-app` (UI Compose mobile + TV, ViewModels, Workers, DI), `retrograde-app-shared` (Room, DAOs, biblioteca,
  catalogo), `retrograde-util`, `lemuroid-touchinput`, `retrograde-libretro` (JNI/LibretroDroid) e os cores no submodulo.
  O publico real e TV box e aparelho velho (`minSdkVersion 21`).
- O jogo roda num processo separado (`<pacote>:game`); a home/catalogo no processo principal. Catalogo embutido
  (`catalog_manifest.txt` + o banco pre-gerado no build) e ROM baixada sob demanda.
- **Build** (o helper roda; a sessao nunca roda `gradlew` de build nem de teste de unidade direto):
  `build --bug <slug>` = povoa o submodulo + `gradlew :lemuroid-app:assembleFreeBundleDebug
  :lemuroid-app:testFreeBundleDebugUnitTest` na lane. ~5 min na 1a vez numa lane (medido: 257 s o assemble, 32 s os testes,
  parte do build cache compartilhado), incremental depois. Um por vez (lock `lemuroid-build`; cada daemon do Gradle usa
  ate 5 GB). Rode em background. Sem flags.
- O `build` deixa na lane o APK que o teste instala:
  `lemuroid-app\build\outputs\apk\freeBundle\debug\lemuroid-app-free-bundle-arm64-v8a-debug.apk` (e o `armeabi-v7a`).
  Debug = pacote `app.retrogamesystem.debug`, versao `<x>-DEBUG`, assinado com o `debug.keystore` do repo: convive com
  o app de cliente (`app.retrogamesystem`) no mesmo aparelho, sem conflito de assinatura.
- **O build nao compila os cores** (`.so` prontos no submodulo; NDK nao entra). Fix que exige mudar um core ou o
  `libretrodroid-patched.aar` e `bloqueado` (secao Planning).
- **Nao ha deploy**: o `deploy` do helper recusa (saida 2) e o teste instala o APK da lane no aparelho (secao Teste).

## Planning

- **Exemplos de veredito:** `sem-codigo` = item do catalogo remoto/capa, publicacao de versao (`build-and-upload.ps1`,
  do dono), orientacao ao cliente (permissao de armazenamento, pasta de ROMs), ROM/BIOS que o cliente trouxe;
  `bloqueado` = crash NATIVO dentro de um core (o fix e no `lemuroid-cores`: commit + push + tag + `CORES_VERSION`, pitfall
  15 do `CLAUDE.md`; o pipeline nao commita em outro repo), so reproduz num aparelho que nao temos (TV box, Moto G86,
  Android 5-7 real), depende de telemetria/trace que nao existe, ou do Play Console.
- **Exemplo de arquivo na reserva:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainActivity.kt`
  (no plan.json: `"files": ["lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainActivity.kt"]`).
- **Arquivos que a correcao arrasta** (entram na reserva): texto ao usuario -> `lemuroid-app/src/main/res/values/strings.xml`
  E `lemuroid-app/src/main/res/values-pt-rBR/strings.xml` (os dois, sempre); entidade/coluna Room nova ->
  `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/Migrations.kt` + o registro em
  `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/LemuroidApplicationModule.kt` + o schema novo que o kapt gera em
  `retrograde-app-shared/schemas/` (nunca editado a mao); mudanca no catalogo -> `catalog_manifest.txt` da raiz E
  `lemuroid-app/src/main/assets/catalog_manifest.txt` (identicos na base, mudam juntos) e o
  `MANIFEST_SCHEMA_VERSION`/`CATALOG_VERSION` quando o `CLAUDE.md` manda subir; Activity nova ->
  `AndroidManifest.xml`; dependencia nova -> `deps.kt`/`build.gradle.kts` (e a auditoria do pitfall 16).
- **Effort:** `high` para Room/migracao/`createFromAsset`, download sob demanda e fila (`RomOnDemandManager`,
  `SaveQueueManager`, `StreamingRomsManager`), o ciclo do processo `:game` (`BaseGameActivity`, `GameProcessSession`) e
  qualquer API acima do `minSdk`; `xhigh` para ANR/deadlock e crash que so aparece na saida/volta do jogo.
- **T2 (reproducao como o usuario) no Plano de teste:** o caminho que o CLIENTE fez, no Galaxy A12, pela UI (toque ou
  `adb shell input`): estado de partida (sistema, jogo, variante, ROM baixada ou nao, armazenamento), cada toque (Home,
  sistema, busca, jogo, modal de variantes, fila, Configuracoes) e o SINAL objetivo (texto/toast na tela por screenshot,
  linha do `adb logcat` do SEU processo, ANR, crash, arquivo na pasta de ROMs). Deep link
  (`retrogamesystem://app.retrogamesystem.debug/play-game/id/<gameId>`, `CLAUDE.md` "Abrir um jogo...") e complemento
  quando o bug e dentro do jogo, nunca substituto da navegacao que o cliente fez. O que so outro aparelho prova vai para
  "falta para done". Bug sem caminho de usuario (build, empacotamento) dispensa a UI, com o motivo escrito.

## Bug de outro projeto (planning Passo 6, teste Passo 6)

`outro-projeto` aqui = o conserto e do `digitalstoregamesproject` (agente de WhatsApp, guias, KB do suporte, painel,
pagamentos), de outro produto (RGS de PC, RetroSystem PS2, PS2Companion, Xbox 360 Companion) ou do repo de cores
(`lemuroid-cores`: ai o veredito e `bloqueado`, secao Planning). Rota e formato estao no Passo 4 de
`C:\projects\retrobatnew\source\skills\triagem-chamados\SKILL.md` (produto -> repo do produto; agente/plataforma ->
`C:\projects\digitalstoregamesproject\docs\modules\<modulo>\[areas\<area>\]bugs\AAAA-MM-DD-slug.md`). Siga o fluxo de bug
daquele projeto (1a passada: sintoma e evidencia). Escreva o arquivo, **nao commite** (o checkout e compartilhado e
commit local la vai ao ar no deploy de outra sessao; o helper commita com pathspec, sem push). Exemplo de
`external_docs` no plan.json: `"C:\\projects\\digitalstoregamesproject\\docs\\modules\\...\\bugs\\<arquivo>.md"`.

## Dev

- Arrastes: os da secao Planning.
- **Gerados que nunca se commitam** (o helper descarta e recusa commit com eles, saida 5): o ponteiro do submodulo
  `lemuroid-cores` (um ponteiro que anda, sobretudo PARA TRAS, e o pitfall 15 do `CLAUDE.md`) e
  `.claude/settings.local.json`. A saida do Gradle (`build/`, `.gradle/`, `.kotlin/`) e gitignored; nunca a force
  (`git add -f`). Nunca rode `ktlintGenerateBaseline` (grava no submodulo, fora de qualquer commit daqui).
- **Testes de unidade** fazem parte do fix quando a logica e testavel na JVM: `lemuroid-app/src/test/...` (JUnit; o
  `build` os roda). Os que existem hoje: 10 suites, 54 testes, 0 falhas, 1 pulado (linha de base da lane, 2026-10-09).
- **Estilo:** `gradlew ktlintCheck` ja e VERMELHO na base (`Color.kt` de `src/debug`, `lemuroid-app/build.gradle.kts`): o
  criterio e nenhum apontamento novo nos arquivos que o fix tocou (`gradlew :lemuroid-app:ktlintCheck` na lane e procurar
  os seus arquivos no relatorio). Fix que chama API de framework: `gradlew :lemuroid-app:lintFreeBundleDebug` passa
  (`CLAUDE.md`, pitfall 12, item 5) e o achado novo e seu. Estes dois a sessao pode rodar direto na lane, um por vez.
- **Versao:** o fix NAO sobe `versionCode`/`versionName`; isso e do `chore(release)`/`build-and-upload.ps1` do dono.
- **Commit do fix:** `fix(<area>): <resumo> (bug: <slug>)`, com as areas que o repo ja usa (`catalog`, `core`, `padkit`,
  `tv`, `library`, `variants`, `ppsspp`, `telemetria`; area nova so se nenhuma servir); comentario no codigo, quando
  explicar o fix, cita `[[<slug>]]`.
- **Commit de docs** (teste, finalizacao): `docs(bug): ...` (o `commit_docs` do `projeto.json`; e o prefixo que o repo
  usa).

## Teste (E2E)

**Onde o teste roda: no Galaxy A12 de teste (`adb -s RX8R90G1D6E`, SM-A127M, Android 13, arm64), com o APK debug que o
`build` deixou na SUA lane.** Decisao do dono (2026-10-09). Um teste por vez (o slot). `env-wait` nao tem processo local
a vigiar aqui (`e2e` vazio): antes de instalar, `adb -s RX8R90G1D6E get-state` tem que dizer `device`; sem o A12 (ou
`unauthorized`/`offline`) = `test-done --result ambiente`, nao reprovacao.

```
wt-1 (bug A) --build do helper--> APK da lane --adb install (no slot)--> A12: app.retrogamesystem.debug <-- teste de A
wt-2 (bug B) --(espera o slot)--> ...
```

- **O aparelho e compartilhado com o dono e com outras sessoes** (o debug instalado e o mesmo pacote que elas usam).
  Antes de mexer: `adb -s RX8R90G1D6E shell dumpsys package app.retrogamesystem.debug` (guarde `versionName` e
  `lastUpdateTime` na evidencia). Ao instalar, os dados do app ficam (`install -r`): nao limpe dados (`pm clear`) sem o
  Plano de teste exigir, e se exigir, diga no doc. Nunca desinstale nem toque no `app.retrogamesystem` (o de cliente).
- **Telemetria:** o debug reporta erro para a telemetria de PRODUCAO (`TelemetryReporter`, projeto `retrogamesystem/...`).
  Um controle positivo de crash poluiria o painel de triagem. No inicio do slot, com o app parado
  (`am force-stop app.retrogamesystem.debug`): copie `shared_prefs/telemetry_prefs.xml` para a evidencia
  (`adb exec-out run-as app.retrogamesystem.debug cat shared_prefs/telemetry_prefs.xml`) e grave a versao com
  `<boolean name="telemetry_error_reporting" value="false" />` dentro do `<map>` (`run-as ... sh -c 'cat > ...'`). No fim,
  com o app parado, devolva o arquivo copiado. Se nao conseguir desligar, anote no doc a janela de horario do teste e que
  os reportes `app=<x>-DEBUG` do SM-A127M nesse horario sao do pipeline.
- **Controle positivo (Passo 4, sem `deploy`):** depois do `sync`, na lane: `git checkout --detach <base sincronizada>`;
  `python <helper> build --bug <slug>` (background); copie o APK arm64 para a evidencia como `base-<hash>.apk`;
  `git checkout bugfix/<slug>`; `git status` limpo. Instale o da base e rode a T2: o sinal TEM que aparecer. Depois
  `build` de novo (o fix) e copie como `fix-<hash>.apk`.
- **Instalar e provar o binario:** `adb -s RX8R90G1D6E install -r -d -t <apk>` (`-d`: base e fix tem o mesmo versionCode).
  Confira que o instalado e o recem-construido: `adb shell pm path app.retrogamesystem.debug` -> `adb shell sha256sum
  <caminho do base.apk>` igual ao `Get-FileHash -Algorithm SHA256` do APK que voce instalou.
- **Prova:** ponta a ponta no A12, pela UI, com o sinal do sintoma ausente e o comportamento correto presente.
- **Evidencia:** `C:\projects\lemuroid\.bugfix-pipeline-Lemuroid\evidencia\<AAAAMMDD-HHMM>-<slug>\`: screenshots
  (`adb exec-out screencap -p`), `adb logcat` filtrado pelo PID do SEU launch (`adb shell pidof app.retrogamesystem.debug`
  e `...debug:game`; nunca `logcat -c`: outras sessoes leem o mesmo buffer, filtre por hora e PID), ANR/crash do seu
  processo, os APKs e o `telemetry_prefs.xml` original.
- Relatorios do Gradle podem citar caminho da ARVORE PRINCIPAL (resultado vindo do build cache compartilhado): confira o
  arquivo da lane antes de concluir.

| Nivel | O que | Criterio |
|---|---|---|
| T2 | **o cliente de novo**: o mesmo caminho do controle positivo, no A12, pela UI, agora com o APK do fix | sinal do sintoma AUSENTE e comportamento correto PRESENTE |
| T3 | regressao: os testes de unidade do `build` (`lemuroid-app/build/test-results/testFreeBundleDebugUnitTest/*.xml`), o caminho vizinho que o fix tocou no aparelho, e ktlint/lint como na secao Dev | sem `fail` novo; linha de base 54 testes / 0 falhas / 1 pulado |
| T4 | `adb logcat` do seu PID (`AndroidRuntime`, `FATAL`, `ANR`, `libc`, tags do app) na janela do teste | sem erro novo atribuivel ao fix |

## Finalizacao

- `final-sync-main`: o codigo que outras sessoes deixaram sem commit so e publicado se o build do `projeto.json` sair 0
  numa lane. O ponteiro do `lemuroid-cores` e o `.claude/settings.local.json` ficam de fora (`gerados`).
- `final-deploy`: sem deploy neste projeto, sai 0 sem fazer nada. Subir a versao e publicar (`build-and-upload.ps1`) e do
  dono: no briefing, vai em "falta para done".
- Ciclo de pastas: `open/` -> `retest/` (o pipeline) -> `done/` (o dono, com a versao publicada e a prova do cliente).
- **Espelho `documentacao/bugs/`:** na arvore principal `docs` e uma *junction* para `documentacao`: os dois caminhos
  sao o mesmo arquivo no disco e o git rastreia os dois (todo commit de doc de bug do repo leva os dois). Nas lanes nao
  ha junction e o pipeline escreve so `docs/bugs/` (o motor so trata `docs/` como documentacao). Consequencia: depois
  que a arvore principal recebe os commits do pipeline, o caminho `documentacao/bugs/...` de cada doc criado ou movido
  aparece sujo nela (` D` em `open/`, `??` em `retest/`). Decisao do dono (2026-10-09): o pipeline nao o corrige; o
  briefing lista, numa secao "Espelho documentacao/bugs", cada doc criado ou movido, para o dono (ou a proxima sessao)
  commitar os dois caminhos.
- Briefing em `C:\projects\lemuroid\.bugfix-pipeline-Lemuroid\runs\<execucao>-briefing.md`; o link de cada doc e
  relativo a essa pasta: `../../Lemuroid/docs/bugs/retest/<doc>.md`.

## Maestro (SKILL.md)

- Trust: o dono roda o `claude.exe` num terminal em `C:\projects\lemuroid\Lemuroid`, aceita e sai.
- Bugs: `docs/bugs/open/*.md` (sem subpastas). `documentacao/bugs/` fica fora: na arvore principal e o mesmo arquivo
  pela junction `docs -> documentacao` (secao Finalizacao).
- O E2E usa o Galaxy A12: ao iniciar, avise o dono que o A12 tem que ficar conectado, desbloqueado e sem uso (dele ou de
  outra sessao) nas janelas de teste, e que o app debug dele sera reinstalado com o APK de cada bug.
- Commits locais na arvore principal: publicar antes com `git -C C:\projects\lemuroid\Lemuroid push origin version9`,
  que nao mexe na arvore de trabalho.
- Rodizio de contas: desligado (`"rodizio_contas": false`, DESIGN 22.3): no limite de uso, a execucao espera o reset.
