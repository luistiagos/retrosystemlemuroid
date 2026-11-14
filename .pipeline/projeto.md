# Manual do projeto — Lemuroid (RetroSystem Android)

Lido pelas sessoes do pipeline (planning, dev, teste, Aceitacao, finalizacao) e pelos modos `bug`/`feature`: o motor
injeta o caminho deste arquivo no prompt, e o que os roteiros do motor chamam de "o manual" e o que esta aqui. A config
que o MOTOR le (build, base, gerados, preflight, android, publicacao, telemetria) esta no `projeto.json` ao lado; aqui
fica o que a SESSAO precisa saber. Caminhos relativos a raiz do repo; a arvore principal e a pasta do pipeline vem no
prompt.

## Caminhos

- A raiz do projeto e o proprio repo. Fluxo principal: `origin/version10`. Remoto unico: `origin`.
- Docs: `docs/<tipo>/<estado>/<id>/<id>.md` (tipos `bugs`, `features`, `manutencao`), tasks em `<id>/tasks/`; backlog
  (planos, sem estado) em `docs/backlog/`. A pasta de estado e a unica fonte do estado; quem a muda e o motor.
- Caminhos de arquivo no plano e nos `claim`: relativos a raiz do repo (`lemuroid-app/src/main/java/...`,
  `retrograde-app-shared/src/main/java/...`).
- **Submodulo `lemuroid-cores`** (os `.so` dos cores e o modulo `:bundled-cores`): numa worktree nova ele vem VAZIO. O
  `build` do helper o povoa a partir do clone da arvore principal (sem rede, ~16 s na 1a vez) no commit que o ponteiro
  da base registra. Antes do 1o `build` a pasta da lane esta vazia: para LER o fonte de um core, leia na arvore
  principal (`lemuroid-cores/...` dela), sem escrever la.

## Leituras obrigatorias (planning Passo 1, dev Passo 1)

- `docs/bugs/aberto/README.md` (ciclo de pastas e criterio de complexidade).
- `CLAUDE.md` (raiz): a arquitetura, as "Convencoes de Codigo" (Toast so por `displayToast`, Intent implicita so por
  `startActivitySafely`, rede so por `NetworkCompat`, Activity com extra ausente -> `finish(); return`) e os
  **"Pitfalls de Android / Room"**, todos bugs reais de producao com a regra para nao regredir. Leia o pitfall do modulo
  que a mudanca toca (Room/`createFromAsset`, `minSdk 21` e `SDK_INT` que mente nas TV box, processo `:game`, cores).
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
  :lemuroid-app:testFreeBundleDebugUnitTest` na lane. ~5 min na 1a vez numa lane (medido: 257 s o assemble, 32 s os
  testes, parte do build cache compartilhado), incremental depois. Um por vez (lock `lemuroid-build`; cada daemon do
  Gradle usa ate 5 GB). Rode em background. `build --emulador` acrescenta a ABI x86_64 (`-PdevAbi=x86_64`), que o
  emulador precisa; sem a flag so saem `arm64-v8a` e `armeabi-v7a`.
- O `build` deixa na lane o APK que o teste instala:
  `lemuroid-app/build/outputs/apk/freeBundle/debug/lemuroid-app-free-bundle-<abi>-debug.apk`. Debug = pacote
  `app.retrogamesystem.debug`, versao `<x>-DEBUG`, assinado com o `debug.keystore` do repo: convive com o app de
  cliente (`app.retrogamesystem`) no mesmo aparelho, sem conflito de assinatura.
- **O build nao compila os cores** (`.so` prontos no submodulo; NDK nao entra). Mudanca que exige mexer num core ou no
  `libretrodroid-patched.aar` e `bloqueado` (secao Planning).
- **Nao ha `deploy`**: o teste instala o APK da lane pelo `aparelho-instalar` (secao Teste (E2E)).

## Planning

- **Exemplos de veredito:** `sem-codigo` = item do catalogo remoto/capa, publicacao de versao (`build-and-upload.ps1`),
  orientacao ao cliente (permissao de armazenamento, pasta de ROMs), ROM/BIOS que o cliente trouxe; `bloqueado` = crash
  NATIVO dentro de um core (o fix e no `lemuroid-cores`: commit + push + tag + `CORES_VERSION`, pitfall 15 do
  `CLAUDE.md`; o pipeline nao commita em outro repo), so reproduz num aparelho que nao temos (TV box, Moto G86,
  Android 5-7 real), depende de telemetria/trace que nao existe, ou do Play Console.
- **Exemplo de arquivo na reserva:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainActivity.kt`
  (no plan.json: `"files": ["lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainActivity.kt"]`).
- **Arquivos que a mudanca arrasta** (entram na reserva): texto ao usuario -> `lemuroid-app/src/main/res/values/strings.xml`
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
- **Reproducao como o usuario (Plano de teste):** o caminho que o CLIENTE fez, pela UI (toque ou `adb shell input`):
  estado de partida (sistema, jogo, variante, ROM baixada ou nao, armazenamento), cada toque (Home, sistema, busca,
  jogo, modal de variantes, fila, Configuracoes) e o SINAL objetivo (texto/toast na tela por print, linha do
  `adb logcat` do SEU processo, ANR, crash, arquivo na pasta de ROMs). Deep link
  (`retrogamesystem://app.retrogamesystem.debug/play-game/id/<gameId>`, `CLAUDE.md` "Abrir um jogo...") e complemento
  quando o bug e dentro do jogo, nunca substituto da navegacao que o cliente fez. O que so outro aparelho prova vai para
  "falta para done". Bug sem caminho de usuario (build, empacotamento) dispensa a UI, com o motivo escrito.

## Bug de outro projeto (planning Passo 6, teste Passo 6)

`outro-projeto` aqui = o conserto e do `digitalstoregamesproject` (agente de WhatsApp, guias, KB do suporte, painel,
pagamentos), de outro produto (RGS de PC = `retrobatnew`, RetroSystem PS2, PS2Companion, Xbox 360 Companion) ou do repo
de cores (`lemuroid-cores`: ai o veredito e `bloqueado`, secao Planning). Rota e formato: o Passo 4 da skill global
`triagem-chamados` (produto -> repo do produto; a pasta de bug de cada repo sai do `.pipeline/` dele, ou do
`destino_bug.py` da skill quando ele nao tem). Siga o fluxo de bug daquele projeto (1a passada: sintoma e evidencia).
Escreva o arquivo, **nao commite** (o checkout e compartilhado; o helper commita com pathspec, sem push).

## Dev

- Arrastes: os da secao Planning.
- **Gerados que nunca se commitam** (o helper descarta e recusa commit com eles): o ponteiro do submodulo
  `lemuroid-cores` (um ponteiro que anda, sobretudo PARA TRAS, e o pitfall 15 do `CLAUDE.md`) e
  `.claude/settings.local.json`. A saida do Gradle (`build/`, `.gradle/`, `.kotlin/`) e gitignored; nunca a force
  (`git add -f`). Nunca rode `ktlintGenerateBaseline` (grava no submodulo, fora de qualquer commit daqui).
- **Testes de unidade** fazem parte da mudanca quando a logica e testavel na JVM: `lemuroid-app/src/test/...` (JUnit; o
  `build` os roda). Linha de base (2026-10-09): 10 suites, 54 testes, 0 falhas, 1 pulado.
- **Estilo:** `gradlew ktlintCheck` ja e VERMELHO na base (`Color.kt` de `src/debug`, `lemuroid-app/build.gradle.kts`): o
  criterio e nenhum apontamento novo nos arquivos que a mudanca tocou (`gradlew :lemuroid-app:ktlintCheck` na lane e
  procurar os seus arquivos no relatorio). Mudanca que chama API de framework: `gradlew :lemuroid-app:lintFreeBundleDebug`
  passa (`CLAUDE.md`, pitfall 12, item 5) e o achado novo e seu. Estes dois a sessao pode rodar direto na lane, um por vez.
- **Versao:** a mudanca NAO sobe `versionCode`/`versionName`; isso e da publicacao (`final-publicar`).
- **Commit:** `TASK-NNNN: <tipo>(<area>): <resumo>` (o prefixo do motor e depois a convencao do repo), com as areas que
  o repo ja usa (`catalog`, `core`, `padkit`, `tv`, `library`, `variants`, `ppsspp`, `telemetria`, `cores`,
  `controles`, `publicacao`; area nova so se nenhuma servir); comentario no codigo, quando explicar a mudanca, cita o id
  da demanda.
- **Commit de docs:** `docs: ...` (o `commit_docs` do `projeto.json`).

## Teste (E2E)

**Onde o teste roda:** no aparelho que o `aparelho-instalar` escolher: o Galaxy A12 de teste (`RX8R90G1D6E`,
SM-A127M, Android 13, arm64) quando conectado e autorizado; sem ele, o emulador `lemu_3ds_api34` (Android 14, x86_64),
que o motor sobe. Um teste por vez (o slot). O APK e o debug que o `build` deixou na SUA lane; para o emulador o build
tem de ser `build --emulador` (sem ele o APK x86_64 nao existe e o `aparelho-instalar` recusa dizendo isso).

```
lane (task A) --build do helper--> APK da lane --aparelho-instalar (no slot)--> A12 ou emulador: app.retrogamesystem.debug
lane (task B) --(espera o slot)--> ...
```

- **Instalar so pelo helper:** `python <helper> aparelho-instalar --bug <slug> [--apk <arquivo>]` (sem `--apk`: o APK da
  lane na ABI do aparelho). Ele segura o aparelho, recusa APK de pacote de cliente, instala com `-r -d -t` (os dados
  do app ficam) e desliga a telemetria do app de teste ate o fim do slot (o `test-done` devolve). Nunca rode
  `adb install` nem mexa no `telemetry_prefs.xml` a mao. Saida 19 (`AMBIENTE: ...`) = sem aparelho pronto: o item ja
  voltou a fila; termine o turno.
- **O aparelho e compartilhado com o dono e com outras sessoes** (o debug instalado e o mesmo pacote que elas usam).
  Antes de mexer: `adb -s <serial> shell dumpsys package app.retrogamesystem.debug` (guarde `versionName` e
  `lastUpdateTime` na evidencia). Nao limpe dados (`pm clear`) sem o Plano de teste exigir, e se exigir, diga no doc.
  Nunca desinstale nem toque no `app.retrogamesystem` (o de cliente).
- **Controle positivo (sem `deploy`):** depois do `sync`, na lane: `git checkout --detach <base sincronizada>`;
  `python <helper> build --bug <slug>` (background; `--emulador` se o aparelho for o emulador); copie o APK da ABI para
  a evidencia como `base-<hash>.apk`; `git checkout <branch da task>`; `git status` limpo. Instale o da base
  (`aparelho-instalar --apk <base-...apk>`) e rode a reproducao: o sinal TEM que aparecer. Depois `build` de novo (a
  mudanca), copie como `fix-<hash>.apk` e instale.
- **Provar o binario:** `adb -s <serial> shell pm path app.retrogamesystem.debug` -> `adb shell sha256sum <caminho do
  base.apk>` igual ao `Get-FileHash -Algorithm SHA256` do APK que voce instalou.
- **Prova:** ponta a ponta no aparelho, pela UI, com o sinal do sintoma ausente e o comportamento correto presente.
- **Evidencia:** na pasta `evidencias/` da task: prints do aparelho (o unico meio de print deste projeto:
  `adb -s <serial> exec-out screencap -p > <arquivo>.png`; nunca print da tela do PC), `adb logcat` filtrado pelo PID
  do SEU launch (`adb shell pidof app.retrogamesystem.debug` e `...debug:game`; nunca `logcat -c`: outras sessoes leem
  o mesmo buffer, filtre por hora e PID), ANR/crash do seu processo e os APKs.
- **Ferramenta auxiliar** (script de captura, leitor de log) roda da lane ou do scratchpad, nunca de dentro de uma pasta
  do aparelho nem do APK instalado.
- **Teto:** todo comando em background no slot tem teto: build 60 min, boot do emulador 8 min, download de ROM no
  aparelho 10 min. O slot inteiro tem teto de 60 min (`e2e.teto_slot_min`).
- Relatorios do Gradle podem citar caminho da ARVORE PRINCIPAL (resultado vindo do build cache compartilhado): confira o
  arquivo da lane antes de concluir.

| Nivel | O que | Criterio |
|---|---|---|
| T2 | **o cliente de novo**: o mesmo caminho do controle positivo, no aparelho, pela UI, agora com o APK da mudanca | sinal do sintoma AUSENTE e comportamento correto PRESENTE |
| T3 | regressao: os testes de unidade do `build` (`lemuroid-app/build/test-results/testFreeBundleDebugUnitTest/*.xml`), o caminho vizinho que a mudanca tocou no aparelho, e ktlint/lint como na secao Dev | sem `fail` novo; linha de base 54 testes / 0 falhas / 1 pulado |
| T4 | `adb logcat` do seu PID (`AndroidRuntime`, `FATAL`, `ANR`, `libc`, tags do app) na janela do teste | sem erro novo atribuivel a mudanca |

Falha de ambiente (aparelho sai do adb, emulador nao sobe, rede do aparelho fora) e `ambiente`, nao reprovacao.

## Finalizacao

- `final-publicar` roda o `build-and-upload.ps1` da lane (sobe `versionCode`/`versionName` em
  `lemuroid-app/build.gradle.kts`, compila o release assinado com o `release.jks`, publica no HF e no R2); o motor
  commita o bump e grava a tag `publicado/<versao>`. O `build.properties` (HF_TOKEN, R2_*) e o `release.jks` vem da
  arvore principal (`entradas_ignoradas`). Falha de credencial = pendencia do dono no relatorio.
- O codigo que outras sessoes deixaram sem commit so e publicado se o build do `projeto.json` sair 0 numa lane. O
  ponteiro do `lemuroid-cores` e o `.claude/settings.local.json` ficam de fora (`gerados`).
- O que so o cliente prova (TV box, aparelho que nao temos) vai no `verificacao:` da demanda e fecha pela medida ou
  pelo dono (`fecha_por`), nunca pela ausencia do erro.

## Maestro

- Trust: o dono roda o `claude.exe` num terminal na raiz do repo, aceita e sai (as lanes e a sonda do preflight sao
  worktrees dele).
- O teste usa o A12 ou o emulador: ao iniciar, avise o dono que o A12, se conectado, tem de ficar desbloqueado e sem uso
  (dele ou de outra sessao) nas janelas de teste, e que o app debug dele sera reinstalado com o APK de cada task.
- Rodizio de contas: desligado (`"rodizio_contas": false`): no limite de uso, a execucao espera o reset.

## Template de bug

- **Id** (nome da pasta e do arquivo): `AAAA-MM-DD-slug-curto`, data do registro, slug em minusculas com hifen (ex.:
  `2026-07-01-catalogo-some-scan-biblioteca`).
- **Titulo:** `# [BUG] <sintoma como o usuario ve>`.
- **Blocos** logo abaixo do titulo, um por linha: `**Data:** AAAA-MM-DD`, `**Severidade:** critica | alta | media |
  baixa`, `**Complexidade:** baixa | media | alta` (pelo criterio de `docs/bugs/aberto/README.md`), `**Branch:**
  version10`, `**Origem:** chamado #N | telemetria <id> | Play Console | relato do dono | investigacao`. Sem bloco
  `Status` (o estado e a pasta).
- **Secoes, nesta ordem:** `## Sintoma` (na linguagem de quem relatou: aparelho, Android, versao do app, sistema/jogo)
  - `## Evidencia` (log, id de telemetria ou de chamado, print, o comando e o numero) - `## Causa-raiz` - `## Hipoteses
  descartadas` - `## O que fazer` - `## Correcao` - `## Validacao` - `## Licao`. A 1a passada escreve so Sintoma e
  Evidencia; as outras nascem nas passadas seguintes.
- **Regra do produto:** o publico e TV box e aparelho velho (`minSdk 21`, controle remoto, sem toque): o Sintoma diz em
  que aparelho e com que controle; a Validacao diz o que so esse aparelho prova (vai para `verificacao:`).

## Template de feature

- **Id** (nome da pasta): `AAAA-MM-DD-slug-curto`, como no bug.
- **Secoes da spec, nesta ordem:** Problema - Visao do usuario de TV box - Decisoes do dono pendentes (se houver) -
  Criterios de aceite (EARS) - Superficies - Fora de escopo.
- **Visao do usuario de TV box** (regra do produto, secao obrigatoria): responda (1) da para fazer so com o controle
  remoto/gamepad, sem toque, com foco visivel em cada passo? (2) cabe numa TV 720p e num celular 720x1280 sem cortar
  texto? (3) roda em Android 5 (API 21) com 1 GB de RAM, ou diz o que acontece la? (4) o texto esta em pt-BR e em
  ingles (`values-pt-rBR` e `values`)? Resposta ruim em qualquer uma = a spec nao esta pronta.
- **Superficies** (cada uma com o que muda ou `n/a — motivo`): UI mobile (Compose) - UI TV (Leanback/foco) - processo
  `:game` (jogo em execucao, saves, controles) - catalogo e banco (Room, `catalog_manifest.txt`, banco pre-gerado) -
  download de ROM/BIOS - Configuracoes e preferencias - telemetria - cores/LibretroDroid (fora deste repo: `bloqueado`)
  - publicacao (`build-and-upload.ps1`).
- **Tokens de prova:** teste de unidade = `lemuroid-app/src/test/...` (JUnit, rodado pelo `build`); e2e = o caminho
  do usuario no aparelho pelo `aparelho-instalar` (secao Teste (E2E)), com print e `logcat` do PID; no publicado =
  a versao da Play/R2 instalada no aparelho do cliente ou pela medida da telemetria (`retrogamesystem/<comp>`).
