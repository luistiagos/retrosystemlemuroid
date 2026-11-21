---
tipo: implementacao
demandas: [2026-11-20-publicacao-manual-sem-commit-versao-duplicada]
depende_de: []
integrado_em: 24e15cbc61f70dd64ced8ed6b8d3ef98440f5faa
---
# Trava no build-and-upload.ps1: abortar antes do bump se a versão gerada não passa da anunciada

Demanda: `docs/bugs/aberto/2026-11-20-publicacao-manual-sem-commit-versao-duplicada/` (leia a seção "Planejamento").

## Objetivo

`build-and-upload.ps1` nunca pode gerar nem publicar um `versionCode` que já está (ou ficou para trás) no
`version.json` público do canal. Foi assim que saíram duas 1.17.37 / 268 com bytes diferentes: o bump de uma árvore não
foi commitado e outra árvore, partindo do origin, gerou o mesmo número.

## O que mudar

1. **`publish-guard.ps1` (novo, raiz do repo)** — dot-source no `build-and-upload.ps1`, como o `build-env.ps1`:
   - `Get-AnnouncedVersion -Url <string>`: GET do anúncio com cache-busting (`?t=<ticks>` na URL) e
     `Cache-Control: no-cache`, timeout ~30 s. Devolve o objeto (`versionCode`, `versionName`, `sha256`) ou `$null`
     **só** em HTTP 404 (canal sem anúncio). Qualquer outra falha (rede, 5xx, 403, JSON sem `versionCode` inteiro)
     lança com mensagem clara. Em PowerShell 5.1 o 404 chega como `System.Net.WebException` com
     `$_.Exception.Response.StatusCode` = `NotFound`; confira o código, não a mensagem.
   - `Test-PublishVersionGate -TargetCode <int> -Mode <Bump|NoBump|SkipBuild> -Announced <obj ou $null>
     [-DistSha <string>]`: função pura, devolve `$null` (pode seguir) ou o texto do motivo (abortar). Tabela:

     | Modo | Anúncio | Regra |
     |---|---|---|
     | qualquer | `$null` (404) | segue (o chamador avisa "canal sem anúncio") |
     | `Bump` | existe | `TargetCode` (o código DEPOIS do +1) `<=` anunciado -> aborta |
     | `NoBump` | existe | `TargetCode <=` anunciado -> aborta (recompilar o mesmo código gera bytes novos com o mesmo número) |
     | `SkipBuild` | existe | `<` anunciado -> aborta; `==` anunciado: segue só se `DistSha` (arm64 de `dist\`) == `sha256` anunciado (republicação do mesmo binário), senão aborta |

   Mensagem do aborto: versão no ar (`versionCode`/`versionName` e URL do anúncio), a versão que seria gerada, o commit
   de partida (`git rev-parse --short HEAD`, ou "desconhecido" se não houver git — a receita de teste roda fora do
   repo) e se `lemuroid-app/build.gradle.kts` tem mudança não commitada; e o que fazer ("o bump da versão no ar não
   está neste checkout: traga o commit `chore(versao): publicado/<v>` ou suba com `-VersionName`/edite o `.kts` acima
   de <anunciado>").

2. **`build-and-upload.ps1`**:
   - Mover para **antes** do bloco "BUMP DE VERSAO" (`:106`) a resolução que a trava precisa: "CONFIG R2" (`:390-408`),
     `$PUBLIC_BASE_DEFAULT`/`$publicBase` (`:415-416`), `$UPDATE_CHANNEL` (`:179`) e `$VERSION_JSON_NAME`/
     `$versionJsonUrl` (`:461-463`; o `$versionJsonFile` pode ficar onde está ou subir junto). Só leem arquivos
     locais e não lançam sem config; os usos posteriores (`$cfg` no passo 3, `$publicBase` nas URLs) continuam
     valendo. Não duplicar: mover.
   - Chamar a trava **antes do `WriteAllText`** do bump (`:158`) e antes de qualquer build/upload:
     - caminho com bump: `TargetCode = $bumpNewCode`, modo `Bump`;
     - `-NoBump`: código lido do `.kts`, modo `NoBump`;
     - `-SkipBuild`: código lido do `.kts`, modo `SkipBuild`, `DistSha` = sha256 de `dist\retro-game-system-arm64.apk`
       se existir (se não existir, o script já falha adiante; a trava pode passar `$null` e tratar como "diferente").
     - Anúncio `$null`: `Write-Host` amarelo "canal <canal> sem anúncio em <url>: seguindo (canal novo)".
     - Motivo != `$null`: `throw` com o motivo. Nada foi escrito ainda.
   - Vale também com `-SkipR2`/`-SkipHF` (a trava protege o número, não o upload).
   - Comentário no código citando o id da demanda.

3. **`tests/test-publish-guard.ps1` (novo)** — no estilo de `tests/native/*.ps1` (`param`,
   `$ErrorActionPreference='Stop'`, sai ≠ 0 na 1a falha), rodável em Windows PowerShell 5.1:
   - todos os casos da tabela acima com objetos injetados (sem rede), incluindo os limites `==` e `+1`;
   - `Get-AnnouncedVersion` contra o anúncio real (`https://versions.digitalstoregames.com/RetroGameSystem/version.json`
     -> objeto com `versionCode` inteiro > 0) e contra um canal inexistente (`.../version-canalinexistente-<guid>.json`
     -> `$null`); e contra um host que não resolve (`https://invalido.invalid/version.json` -> lança). Um switch
     `-Offline` pula esses três.

## Fora do escopo

- Lembrete de commit/tag no fim: task `lembrete-commit-tag-publicacao`.
- Caminho versionado dos APKs no R2: bug `2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio`.
- Não subir `versionCode`/`versionName` no repo; não rodar o `build-and-upload.ps1` de verdade, nem o `release.ps1`.

## Plano de teste da task

- **Offline:** `powershell -NoProfile -File tests/test-publish-guard.ps1` sai 0 (e `-Offline` também).
- **T2 (sem aparelho; o usuário é o dono rodando o script):** pasta de rascunho **sem `release.ps1`** (assim, se a trava
  falhar, o passo 1 morre em "release.ps1 não encontrado" e nada compila nem publica). Copiar da lane
  `build-and-upload.ps1`, `build-env.ps1`, `publish-guard.ps1` e `lemuroid-app/build.gradle.kts` (mesma subpasta).
  1. Cópia do `.kts` com `versionCode = 268` e `versionName = "1.17.37"`; rodar
     `powershell -NoProfile -File <rascunho>\build-and-upload.ps1 -SkipHF -SkipR2`. Esperado: exit ≠ 0, mensagem com
     a versão no ar (a do `version.json` público no dia; hoje 269 / 1.17.38), a que seria gerada (269) e commit
     "desconhecido"; **sem** "PASSO 1/4"; `Select-String versionCode <rascunho>\lemuroid-app\build.gradle.kts` ainda 268.
  2. Mesmo com `-NoBump` e o `.kts` igual ao anunciado: aborta.
  3. `.kts` igual ao anunciado (hoje 269; o bump o leva a 270): a trava passa, o `.kts` vira 270 e o script para no passo 1 por
     falta do `release.ps1`.
  4. `$env:APP_UPDATE_CHANNEL='canalteste-inexistente'` com o `.kts` em 268: aviso "canal sem anúncio", segue até o
     passo 1.
  Guardar a saída de cada rodada na evidência.
- **Controle positivo:** estático — `git show <base>:build-and-upload.ps1 | Select-String "versionJsonUrl|WriteAllText"`
  mostra o `WriteAllText` (`:158`) antes de qualquer leitura do anúncio. Não rodar a base (dispararia Gradle release
  fora do helper).
- **T3:** `git diff <base> -- build-and-upload.ps1`: só a mudança de posição dos blocos de config e a chamada da trava;
  `-SkipBuild` com sha igual ao anunciado passa (teste offline). O `build` do helper não é afetado (script fora do
  Gradle); rodar `ktlintCheck` não é necessário.

## Correcao (pipeline 20261120-2117, rodada 1)

**O que mudou:**

- `publish-guard.ps1` (novo, raiz; ASCII puro, CRLF: o PowerShell 5.1 le `.ps1` sem BOM como ANSI):
  - `ConvertFrom-AnnouncementJson` — texto do `version.json` -> `{versionCode [int], versionName, sha256}`. Lanca em
    conteudo vazio, JSON invalido e `versionCode` que nao seja inteiro > 0 (string `"269"`, `269.5`, `0`, ausente).
    Tira BOM antes do parse.
  - `Get-AnnouncedVersion -Url [-TimeoutSec 30]` — `Invoke-WebRequest -UseBasicParsing` com `?t=<ticks>` (ou `&t=`)
    e `Cache-Control`/`Pragma: no-cache`. `$null` so quando `[int]$_.Exception.Response.StatusCode -eq 404` (vale
    para `WebException` no 5.1 e `HttpResponseException` no 7; nunca pelo texto da mensagem). Qualquer outra falha
    lanca "Nao foi possivel ler o anuncio da versao em <url> (HTTP n | mensagem)...". Conteudo `byte[]` e decodificado
    como UTF-8.
  - `Test-PublishVersionGate -TargetCode -Mode Bump|NoBump|SkipBuild -Announced [-DistSha]` — pura, tabela da task.
    `SkipBuild` com `==`: segue so se `DistSha` e o `sha256` anunciado existem e batem (sem diferenca de caixa);
    `DistSha` ausente ou anuncio sem `sha256` = diferente = aborta.
  - `Get-PublishCheckoutState -RepoRoot` — `git rev-parse --short HEAD` e `git status --porcelain -- lemuroid-app/
    build.gradle.kts`; `desconhecido` sem git, se `RepoRoot` nao for a raiz do repositorio (copia de rascunho dentro
    de outra arvore nao herda o commit dela) ou se o git falhar. EAP local `Continue` (no 5.1 stderr de nativo com
    `Stop` vira excecao). Nunca lanca.
  - `Format-PublishGateMessage` — motivo, versao no ar (code/name + URL), a que seria gerada, commit de partida,
    `.kts` com mudanca nao commitada (sim/nao/desconhecido), "Nada foi escrito" e o que fazer por modo (Bump: trazer
    `chore(versao): publicado/<v>` ou editar o `versionCode` do `.kts` para >= anunciado e passar `-VersionName`;
    NoBump: rodar sem `-NoBump` ou `.kts` acima; SkipBuild: rodar sem `-SkipBuild`).
    *Ajuste ao texto da task:* `-VersionName` so muda o nome, nao o `versionCode`; a mensagem diz isso em vez de
    "suba com `-VersionName`" sozinho.
  - `Assert-PublishVersionGate` — junta os tres e lanca a mensagem.
- `build-and-upload.ps1`:
  - dot-source do `publish-guard.ps1` logo depois do `build-env.ps1`;
  - **movidos** (nao duplicados) para logo depois do `build.properties` e antes do "BUMP DE VERSAO": `$R2_FOLDER`,
    `$UPDATE_CHANNEL`, `$LOCAL_R2_CONFIG`/`$GODSEND_R2_CONFIG`, "CONFIG R2" (`$cfg`, `$usingGodsendFallback`),
    "URLs PUBLICAS" (`$PUBLIC_BASE_DEFAULT`, `$publicBase`) e `$VERSION_JSON_NAME`/`$versionJsonFile`/
    `$versionJsonUrl`. Nos lugares antigos ficou um comentario de uma linha apontando para cima;
  - novo bloco "VERSAO NO AR": `$announcedVersion = Get-AnnouncedVersion -Url $versionJsonUrl`; `$null` -> amarelo
    "canal <c> sem anuncio em <url>: seguindo (canal novo)"; senao imprime a versao no ar. Comentario cita
    `[[2026-11-20-publicacao-manual-sem-commit-versao-duplicada]]`;
  - ramo do bump: `Assert-PublishVersionGate -TargetCode $bumpNewCode -TargetName $bumpNewName -Mode Bump` depois de
    calcular o numero/nome novos e ANTES do "Subindo versao" e do `WriteAllText`;
  - depois da leitura de `$VERSION_CODE`/`$VERSION_NAME`, com `-SkipBuild` ou `-NoBump`: a mesma trava em modo
    `SkipBuild` (precede `-NoBump` se os dois vierem, como no bump) ou `NoBump`; `DistSha` = sha256 de
    `dist\retro-game-system-arm64.apk` se existir, senao `$null`. Antes do preflight e do passo 1;
  - vale com `-SkipR2`/`-SkipHF` (a leitura do anuncio nao depende deles).
- `tests/test-publish-guard.ps1` (novo, ASCII/CRLF, `param([switch]$Offline)`, `exit 1` na 1a falha): 41 casos —
  tabela completa (3 modos x sem anuncio, `<`, `==`, `+1`; SkipBuild com sha igual, igual em maiusculas, diferente,
  sem APK, anuncio sem sha), parse do JSON (6 casos), mensagem de aborto (cita no ar + URL, gerada, commit
  `desconhecido`, `publicado/1.17.38`, "Nada foi escrito"), `Get-PublishCheckoutState` fora do git e no repo, ordem
  estatica no `build-and-upload.ps1` (leitura do anuncio < trava Bump < `WriteAllText`; trava NoBump/SkipBuild antes
  do "PASSO 1/4") e, sem `-Offline`, os 3 casos de rede.

**Provas rodadas na lane (Windows PowerShell 5.1; nao ha `pwsh` nesta maquina):**

- `powershell -NoProfile -ExecutionPolicy Bypass -File tests/test-publish-guard.ps1` -> "Todos os 41 casos passaram.",
  exit 0 (no ar no dia: 269 / 1.17.38; canal `version-canalinexistente-<guid>.json` -> `$null`;
  `https://invalido.invalid/version.json` -> lanca).
- Mesmo com `-Offline` -> "Todos os 38 casos passaram.", exit 0.
- Ensaio da receita T2 numa pasta de rascunho **sem `release.ps1`** (copia de `build-and-upload.ps1`, `build-env.ps1`,
  `publish-guard.ps1` e do `.kts`), sempre com `-SkipHF -SkipR2`, pasta apagada no fim. Feito so para pegar erro de
  parse/ordem antes de mandar para teste; a T2 oficial e da sessao de teste.
  1. `.kts` 268/1.17.37 -> "TRAVA DE VERSAO: o bump geraria o versionCode 269, que nao passa do 269 que ja esta no
     ar.", "commit de partida: desconhecido", exit 1, sem "PASSO 1/4", `.kts` continua 268.
  2. `.kts` 269 com `-NoBump` -> "TRAVA DE VERSAO: -NoBump recompilaria o versionCode 269...", exit 1.
  3. `.kts` 269 sem flag -> passa, "versionCode: 269 -> 270", `.kts` vira 270, morre no passo 1 por falta do
     `release.ps1`.
  4. `APP_UPDATE_CHANNEL=canalteste-inexistente`, `.kts` 268 -> "canal canalteste-inexistente sem anuncio em
     .../version-canalteste-inexistente.json: seguindo (canal novo)", bump 268 -> 269, morre no passo 1.

**Ajustes ao Plano de teste:** nenhum na receita. Observacao para a T2: a mensagem de aborto sai duas vezes no
console do 5.1 (o texto do `throw` e o `FullyQualifiedErrorId` que o PowerShell imprime) — e o comportamento normal do
`throw` com string, igual aos outros `throw` do script.

## Estado

- Feito: as tres partes da task (publish-guard.ps1, build-and-upload.ps1, tests/test-publish-guard.ps1), teste
  offline e online verdes no 5.1, ensaio T2 dos 4 casos.
- Compile-check: `build --bug TASK-0011` -> BUILD SUCCESSFUL in 3m 13s, exit 0 (a mudanca nao toca entrada do Gradle;
  rodado porque o roteiro manda).
- Falta: nada de codigo. Proximo passo: `enqueue-test --bug TASK-0011` (sessao de teste roda a receita T2/T3 da task).

## Teste

### Teste rodada 1 — aprovado (2026-11-20, pipeline 20261120-2117)

Sessao de teste, lane `wt-1` sincronizada sobre `origin/version10` @ `713b9cf` (sem mudanca na base). Windows PowerShell
5.1. Sem aparelho: o "usuario" desta task e o dono rodando o `build-and-upload.ps1`. Evidencia em
`../../evidencias/TASK-0011/` (pasta da demanda).

- **Diff:** `git diff origin/version10...HEAD` toca so `build-and-upload.ps1`, `publish-guard.ps1`,
  `tests/test-publish-guard.ps1` (reservados) e o doc desta task.
- **Offline (T1):** `tests/test-publish-guard.ps1 -Offline` -> "Todos os 38 casos passaram.", exit 0
  (`t1-offline.txt`); sem `-Offline` -> "Todos os 41 casos passaram.", exit 0, no ar 269 / 1.17.38, canal inexistente
  -> `$null`, host invalido lanca (`t1-online.txt`).
- **Controle positivo (estatico, como o Plano manda):** na base `713b9cf` o `WriteAllText` do bump esta em `:160`, o
  "PASSO 1/4" em `:294` e o `$versionJsonUrl` so em `:460` — o script escrevia o `.kts` e compilava sem nunca ler o
  anuncio (`controle-positivo-base.txt`). Base nao executada (dispararia Gradle release fora do helper).
- **T2** (rascunho sem `release.ps1` em `%CLAUDE_JOB_DIR%\tmp\t2-*`, copia de `build-and-upload.ps1`, `build-env.ps1`,
  `publish-guard.ps1` e do `.kts` da lane com o numero trocado; sempre `-SkipHF -SkipR2`; pastas apagadas no fim; no ar
  no momento: 269 / 1.17.38, `no-ar.txt`):
  1. `.kts` 268/1.17.37, sem flag -> "TRAVA DE VERSAO: o bump geraria o versionCode 269, que nao passa do 269 que ja
     esta no ar.", no ar 269 (1.17.38) + URL, seria gerada 269 (1.17.38), commit "desconhecido"; exit 1; **sem** "PASSO
     1/4"; `.kts` continua 268; `dist\` nao criado (`t2-1-bump-repete-numero.txt`).
  2. `.kts` 269/1.17.38 com `-NoBump` -> "TRAVA DE VERSAO: -NoBump recompilaria o versionCode 269..."; exit 1; sem
     "PASSO 1/4"; `.kts` intacto (`t2-2-nobump-igual-anunciado.txt`).
  3. `.kts` 269/1.17.38 sem flag -> trava passa, "versionCode: 269 -> 270", `.kts` vira 270/1.17.39, para no PASSO
     1/4 por falta do `release.ps1` (`t2-3-bump-passa.txt`).
  4. `APP_UPDATE_CHANNEL=canalteste-inexistente`, `.kts` 268 -> "canal canalteste-inexistente sem anuncio em
     .../version-canalteste-inexistente.json: seguindo (canal novo)", bump 268 -> 269, para no PASSO 1/4
     (`t2-4-canal-sem-anuncio.txt`).
  Sinal do sintoma (numero repetido gerado) ausente; comportamento correto (aborta antes de escrever, segue quando o
  numero passa ou o canal nao tem anuncio) presente.
- **T3:** diff do `build-and-upload.ps1` = dot-source do guard, blocos `$R2_FOLDER`/`$UPDATE_CHANNEL`/CONFIG R2/URLs
  PUBLICAS/`$versionJsonUrl` movidos para antes do bump (cada atribuicao aparece 1 vez no arquivo), leitura do anuncio
  e as duas chamadas da trava. `-SkipBuild` com sha igual ao anunciado (inclusive em maiusculas) passa e com sha
  diferente/sem APK/anuncio sem sha aborta: casos do teste offline, verdes. Sem efeito no Gradle (script fora do build).
- **T4:** sem log de app (nada roda no aparelho). Saida das 4 rodadas sem erro alem do esperado (o `throw` da trava e
  o `release.ps1` ausente de proposito). A mensagem da trava aparece duas vezes no console do 5.1 (texto + 
  `FullyQualifiedErrorId`), comportamento normal do `throw` com string, ja registrado pelo dev.
