---
tipo: implementacao
demandas: [2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio]
depende_de: [TASK-0013]
---
# Publicação sobe o APK para um caminho versionado, anuncia esse caminho e só depois atualiza os links fixos

Demanda: `docs/bugs/aberto/2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio/` (leia "Causa-raiz" e "Planejamento").
Depende da task `harness-publicacao-bucket-falso` (o teste que prova esta).

## Objetivo

Hoje o passo 3 do `build-and-upload.ps1` sobrescreve `RetroGameSystem/retro-game-system-<abi>.apk` — o mesmo objeto
para o qual o `version.json` **em produção** aponta (`?v=` é só query, o R2 ignora). Qualquer `throw` entre esse
upload e o fim do passo 4 deixa o anúncio velho descrevendo bytes novos: todo cliente vê a atualização e o download
falha sempre (`AppUpdateManager.verifyApk`, tamanho/sha). Depois desta task, nenhuma falha em nenhum ponto da
publicação deixa o anúncio do bucket apontando para bytes que ele não descreve.

## O que mudar — `build-and-upload.ps1`

1. **URLs** (bloco `:426-432`): nova tabela por ABI com o nome versionado
   `retro-game-system-<arm64|armv7>-<VERSION_CODE>.apk` e a URL pública dela (pode manter `?v=<code>`, inofensivo).
   `$r2BareUrls` (link fixo sem query, o que o suporte manda no WhatsApp — usado em conversas do
   `digitalstoregamesproject`) continua igual.
2. **Payload do anúncio** (`:462-481`): `apkUrl` e `apkUrls[abi]` passam a ser as URLs **versionadas**. Sha e tamanho
   não mudam (são os do arquivo). O parser do app já instalado só lê a string e grava sempre em
   `cacheDir/updates/lemuroid-update.apk` (`AppUpdateManager.fetchVersionInfo :177-199`, `downloadAndInstall :258-259`,
   `downloadApk :351`): **nenhuma mudança no app**.
3. **Passo 3** (`:519-636`): subir cada APK com `copyto <dist> "$dest/<nome versionado>"`; conferir pela API S3
   (`lsjson`) o nome **versionado**; purgar e verificar pela URL pública a URL **versionada** (mesmo critério de hoje:
   sha diferente = `throw`). Nada aqui escreve nos nomes fixos.
4. **Passo 4** (`:641-690`): igual (grava o json, confere por `lsjson` e pela URL pública). Acrescentar à conferência
   pública que `apkUrls` do json público são as URLs versionadas desta publicação.
5. **Passo novo, último — links fixos** (depois do passo 4 verificado): copiar cada APK para o nome fixo
   (`retro-game-system-<abi>.apk`; cópia dentro do bucket a partir do versionado ou novo upload do `dist\`), subir os
   `.sha256` fixos (hoje em `:552-566`, que **saem** do passo 3 e vêm para cá), purgar os `$r2BareUrls` e conferir o
   link fixo como hoje (`:616-631`, só AVISO). Falha aqui **não** quebra a atualização in-app: a mensagem tem de dizer
   isso ("anúncio OK; link manual desatualizado") e o que rodar para refazer; o script sai ≠ 0 para o dono ver.
   Renumerar os `Print-Step` (`PASSO x/5`) e atualizar o cabeçalho `.DESCRIPTION` (`:18-34`) e o RESUMO (`:698-727`,
   imprimir também a URL versionada).
6. O ramo `-SkipR2` (`:691-696`) continua gerando o `version*.json` local, agora com as URLs versionadas.
7. Comentário no código citando o id da demanda onde a ordem é decidida.

Fora de escopo (decidido no planning): apagar APKs versionados antigos (retenção) — custo de R2 desprezível
(~360 MB por versão) e apagar objeto em produção é risco que esta correção não precisa; e o guard de "versão já
anunciada" (é do bug `2026-11-20-publicacao-manual-sem-commit-versao-duplicada`, nesta mesma execução, que também
edita este arquivo — em outra região: o bump e o fim).

## O que mudar — `tests/test-publicacao-anuncio.ps1`

Acrescentar (sem afrouxar S1–S3):
- em **S3 `sucesso`**: `apkUrls[abi]`/`apkUrl` terminam em `-<versionCode>.apk[?v=...]`; os links fixos e seus
  `.sha256` têm os bytes novos; o log do rclone falso mostra a ordem: upload versionado < `version-harness.json` <
  nome fixo.
- **S4 `falha-link-fixo`**: `FAKE_RCLONE_FALHAR_EM=retro-game-system-armv7.apk` (casa só o nome fixo; o versionado é
  `...-armv7-<code>.apk`). Esperado: script ≠ 0 com a mensagem de "anúncio OK; link manual desatualizado", invariante
  vale e o json anuncia a versão nova.
- **S5 `skip-r2`**: `-SkipR2` gera `dist\version-harness.json` com URLs versionadas e o bucket não muda.

## Teste que prova

`tests/test-publicacao-anuncio.ps1` com os APKs debug da lane: S1–S5 PASS. Na base (antes desta task) S1 e
S2 falhavam — o mesmo harness é o controle positivo.

## Plano de teste

- **Offline:** parser do PowerShell sem erro
  (`[System.Management.Automation.Language.Parser]::ParseFile('build-and-upload.ps1',[ref]$null,[ref]$e); $e.Count` = 0);
  `build` do helper na lane; harness S1–S5 PASS; a mesma rodada com o `build-and-upload.ps1` da base (cópia de
  `git show <base>:build-and-upload.ps1` na raiz temporária) dá S1/S2 FAIL.
- **T2 (usuário):** dispensado com motivo — o defeito é da publicação (script do dono), não há caminho no app, e o
  app não muda. O pipeline não pode publicar; a prova em produção é do dono (`fecha_por: dono`).
- **Controle positivo:** harness contra o script da base: S1/S2 FAIL.
- **T3:** `git diff` só em `build-and-upload.ps1` e `tests/` (os dois arquivos do harness); o `build` do helper (testes de unidade) sem
  falha nova — nenhum Kotlin mudou; ler o diff conferindo que HF (passo 2), bump, aapt2 e a checagem de canal não
  mudaram.
- **Falta para done (dono):** na próxima publicação real, `curl` do `version.json` público e download dos dois APKs
  pelas URLs dele, conferindo tamanho e sha (como foi feito para a 1.17.38), e o link fixo entregando a versão nova.

## Analise do dev (pipeline 20261120-2117, antes da primeira edicao)

Base da lane: `ed30205` (origin/version10). O script mudou desde o planning (TASK-0011/0012 integradas): as linhas
citadas no "O que mudar" estao deslocadas. Simbolos abertos nesta base:

- `build-and-upload.ps1` (806 linhas, UTF-8 com BOM, CRLF):
  - `:97` dot-source de `publish-guard.ps1`; `:164-170` le o anuncio no ar (`Get-AnnouncedVersion`) ANTES do bump;
    `:247-256` trava em `-SkipBuild`: mesmo versionCode so passa com `dist\` arm64 de sha igual ao anunciado.
  - `:473-479` `$r2PublicUrls`/`$r2BareUrls` (mesmo objeto fixo); `:506-526` payload; `:529-535` sidecars em `dist\`.
  - `:538-683` passo 3: `copy` para o nome fixo (`:566`), `lsjson` (`:576`), sidecars (`:603-610`), purga dos
    `$r2BareUrls` (`:619-621`), verificacao publica `?v=` (`:638-657`, `throw`) e do link fixo (`:660-675`, so AVISO).
  - `:685-734` passo 4: `copyto` do json, `lsjson`, purga, `Invoke-RestMethod` confere `versionCode` e `sha256`; o
    `catch [System.Net.WebException]` so pega erro de rede, o `throw` de texto dentro do `try` propaga.
  - `:735-740` ramo `-SkipR2`; `:742-768` RESUMO; `:770-803` lembrete de commit/tag (TASK-0012) so sem `-SkipR2`, e so
    se nada lancou antes; `:806` fim.
  - `Purge-EdgeCache` `:281-306`: sem `CF_*` devolve `$false`; com, `throw` se a API recusar.
- `tests/test-publicacao-anuncio.ps1` (TASK-0013): `New-HarnessRoot` `:157-230` copia so `build-and-upload.ps1` e
  `build-env.ps1` -> na base atual o script morre no dot-source do `publish-guard.ps1` (demanda achada pela TASK-0012:
  `2026-11-20-harness-publicacao-sem-publish-guard`). Sem essa copia nenhum cenario roda; a linha entra aqui (o arquivo
  e reservado desta task) e o doc daquela demanda ganha a nota.
  `Invoke-Cenario` `:350-426` exige log do rclone falso nao vazio e a linha `rclone : <falso>` -> com `-SkipR2` o
  preflight nao resolve rclone (`:349`), entao S5 precisa de um campo do cenario que inverta essa exigencia.
- `tests/fake-rclone.ps1`: `copyto` aceita origem `:s3:` (copia dentro do bucket); falha injetada casa QUALQUER
  argumento (`Contains`).

Decisoes e desvios do plano (com o motivo):

1. **S4: o texto da injecao nao pode ser `retro-game-system-armv7.apk`.** O upload versionado do passo 3 tem como
   ORIGEM `dist\retro-game-system-armv7.apk`, que contem esse texto: a falha cairia no passo 3, nao no link fixo.
   Usado `RetroGameSystem/retro-game-system-armv7.apk` (com a barra do caminho remoto): casa so o destino fixo e o
   `.sha256` fixo (`.../retro-game-system-armv7.apk.sha256`); o versionado e `.../retro-game-system-armv7-<code>.apk`.
2. **Link fixo = copia dentro do bucket a partir do versionado** (`copyto :s3:<bucket>/RetroGameSystem/<versionado>
   <dest>/<fixo>`): o link fixo recebe exatamente os bytes ja verificados e nao ha 2o upload de ~360 MB.
3. **Falha no passo 5 nao sai na hora:** grava a mensagem, segue para o RESUMO e o lembrete de commit/tag da TASK-0012
   (o anuncio JA esta no ar, entao o bump tem de ser commitado do mesmo jeito) e so no fim sai 1. Comando para refazer:
   `.\build-and-upload.ps1 -SkipBuild -SkipHF` (a trava aceita: mesmo versionCode e mesmo sha do anunciado; o
   versionado recebe os mesmos bytes e o anuncio e regravado igual).
4. `?v=<code>` mantido na URL versionada (inofensivo; a chave de cache fica como hoje).
5. Purga do passo 3 = as URLs versionadas (o que o app e a verificacao buscam); purga dos `$r2BareUrls` vai para o passo 5.
6. Residual coberto pela trava da TASK-0011, nao por esta task: republicar o MESMO versionCode com bytes diferentes
   sobrescreveria o versionado que o anuncio aponta; a trava ja recusa isso (`SkipBuild` exige sha igual; `Bump`/`NoBump`
   exigem code maior que o anunciado).

Tarefas: `build-and-upload.ps1` (passos 3/4/5, URLs, cabecalho, RESUMO); harness: copia do `publish-guard.ps1`, S3 com
URL versionada + links fixos + ordem no log, S4 `falha-link-fixo`, S5 `skip-r2`. Prova: harness S1-S5 PASS na lane; o
mesmo harness com o `build-and-upload.ps1` da base: S1/S2 FAIL.

## Estado

- **2026-11-20 (dev, rodada 1):** feito. `build-and-upload.ps1` (passos 3/4/5) e harness (S4/S5 + conferencias novas)
  escritos; harness 5/5 PASS duas vezes na lane; controle positivo com o script da base 0/5 (S1/S2 com a invariante
  quebrada). Proximo passo: `enqueue-test --bug TASK-0014` (a sessao de teste roda o harness na lane e o controle da
  base, comandos na secao Correcao). Nada pendente nesta task.

## Correcao (pipeline 20261120-2117, rodada 1)

### O que mudou

`build-and-upload.ps1` (base `ed30205`):
- Bloco das URLs (antes `:473-479`): `$r2FixedNames` (`retro-game-system-<abi>.apk`), `$r2VersionedNames`
  (`retro-game-system-<abi>-<VERSION_CODE>.apk`), `$r2PublicUrls` = URL **versionada** com `?v=<code>` (vai no anuncio),
  `$r2BareUrls` = link fixo sem query (igual a antes). Comentario `[[2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio]]`
  explicando por que o anuncio aponta para um nome por versao.
- Payload (`apkUrl`/`apkUrls`): sem mudanca de codigo; passa a levar as URLs versionadas porque usa `$r2PublicUrls`.
  Mesmo efeito no ramo `-SkipR2` (o `dist\version*.json` local).
- Passo 3 (`PASSO 3/5`): `copyto <dist> "$dest/<versionado>"`; `lsjson` confere o nome versionado; purga das URLs
  versionadas; verificacao publica pela URL versionada (mesmo `throw` de sha diferente; a mensagem agora diz que nada foi
  anunciado). Download que falha (404, rede) vira `VERIFICACAO FALHOU: nao foi possivel baixar ...` com a mesma frase,
  em vez do erro cru do `Invoke-WebRequest`. Os sidecars `.sha256` e a conferencia do link fixo **sairam** daqui.
  Nada no passo 3 escreve no nome fixo nem no json.
- Passo 4 (`PASSO 4/5`): igual, mais a conferencia publica de que `apkUrl` e `apkUrls[abi]` do json publico sao as
  URLs versionadas desta publicacao.
- Passo 5 novo (`PASSO 5/5: Links fixos`), so depois do passo 4 verificado: por ABI, `copyto` **dentro do bucket**
  `"$dest/<versionado>" -> "$dest/<fixo>"` (sem 2o upload; o fixo recebe os bytes ja verificados) e depois o `.sha256`
  fixo; `lsjson` confere os tamanhos; purga dos `$r2BareUrls`; conferencia publica do link fixo (so AVISO, como era).
  Qualquer erro vai para `$fixedLinksError` e imprime `PASSO 5 FALHOU: anuncio OK; link manual desatualizado`, o erro,
  os links afetados e o comando para refazer (`.\build-and-upload.ps1 -SkipBuild -SkipHF`: a trava aceita o mesmo
  versionCode com o mesmo sha). O script **segue** para o RESUMO e para o lembrete de commit/tag da TASK-0012 (o anuncio
  ja esta no ar: o bump tem de ser commitado) e so no fim sai 1 (`PUBLICACAO INCOMPLETA: anuncio OK; link manual
  desatualizado.`).
- `.DESCRIPTION` (passos 3, 4 e 5 novo), `Print-Step` renumerados para `/5` (inclusive 1 e 2 e o ramo `-SkipR2`), o
  comentario do preflight ("passos 2-5"), e o RESUMO imprime tambem a URL versionada (e `DESATUALIZADO` no link fixo
  quando o passo 5 falhou).

`tests/test-publicacao-anuncio.ps1`:
- `New-HarnessRoot` copia o `publish-guard.ps1` (sem ele o script da base atual morre no dot-source; demanda
  `2026-11-20-harness-publicacao-sem-publish-guard`, que recebeu nota).
- Tabela `$Cenarios` com campos explicitos: `UsaR2`, `Anuncio` (`novo`/`semente`), `LinksFixos` (`novos`/`semente`),
  `Mensagem`, `ConfereOrdem`, `BucketIntacto`, `DistJson`. Funcoes novas: `Test-AnuncioNovo` (versionCode, URL
  versionada sem query, sha/tamanho dos APKs, inclusive o legado), `Test-OrdemEscritas` (log do rclone falso: os 2
  uploads versionados < o `version-harness.json` < as 4 escritas nos nomes fixos), `Get-DirSnapshot`/`Compare-DirSnapshot`;
  `Test-LinksFixos` ganhou o esperado (`novos`/`semente`).
- S1/S2 (sem afrouxar): a mais, anuncio e links fixos da semente intactos. S3: a mais, anuncio com URL versionada e a
  ordem no log. **S4 `falha-link-fixo`**: `FAKE_RCLONE_FALHAR_EM=RetroGameSystem/retro-game-system-armv7.apk` (desvio do
  plano, motivo na Analise: o texto sem a barra casaria a origem `dist\retro-game-system-armv7.apk` do passo 3); exige
  exit != 0, a mensagem, invariante e anuncio novo. **S5 `skip-r2`**: exit 0, rclone nunca chamado, bucket identico
  byte a byte, `dist\version-harness.json` com as URLs versionadas.
- `tests/fake-rclone.ps1`: sem mudanca (o `copyto` com origem `:s3:` ja existia).

### Provas (offline)

- Parser: `[System.Management.Automation.Language.Parser]::ParseFile(...)` = 0 erros nos dois `.ps1`.
- `build` do helper na lane: `BUILD SUCCESSFUL` (exit 0); testes de unidade 22 suites / 138 testes / 0 falhas / 1 pulado
  (`lemuroid-app/build/test-results/testFreeBundleDebugUnitTest/*.xml`). Nenhum Kotlin mudou (ktlint/lint nao se aplicam).
- APKs: debug da lane, versionCode 269 (1.17.38-DEBUG), arm64 `3a0301df...` 237381577 B, armv7 `a3ed1ce2...` 194763687 B.
- Harness na lane (`powershell -NoProfile -ExecutionPolicy Bypass -File tests/test-publicacao-anuncio.ps1`), duas rodadas
  completas (~34 s cada, a 2a terminou 21:59:02): **5/5 PASS**, exit 0.
  - `cache-velho`: script exit 1 (o objeto versionado nao existe no `cdn-velho` -> 404 -> `VERIFICACAO FALHOU`); bucket
    com os versionados novos, `version-harness.json` e links fixos da semente intactos.
  - `falha-upload-armv7`: exit 1 (`rclone copyto falhou (armeabi-v7a -> retro-game-system-armv7-269.apk)`); anuncio e
    links fixos intactos.
  - `sucesso`: exit 0; json com `.../retro-game-system-arm64-269.apk?v=269` e `...-armv7-269.apk?v=269`; log do rclone:
    `copyto dist->arm64-269`, `copyto dist->armv7-269`, `lsjson`, `copyto version-harness.json`, `lsjson`,
    `copyto :s3:.../arm64-269.apk -> .../arm64.apk`, `.sha256`, idem armv7, `lsjson`; links fixos e `.sha256` com os
    bytes novos.
  - `falha-link-fixo`: exit 1; `FALHA-INJETADA` no `copyto ...armv7-269.apk -> ...armv7.apk`; saida com `PASSO 5 FALHOU:
    anuncio OK; link manual desatualizado`, o comando de refazer, o lembrete `FALTA COMMITAR A VERSAO PUBLICADA` e
    `PUBLICACAO INCOMPLETA`; json anuncia 269 nas URLs versionadas; arm64 fixo novo, armv7 fixo ainda da semente (5120 B).
  - `skip-r2`: exit 0; rclone nunca chamado; bucket identico; `dist\version-harness.json` com as URLs versionadas.
- **Controle positivo** (o mesmo harness novo contra o `build-and-upload.ps1` da base): raiz temporaria com
  `git show ed30205:build-and-upload.ps1`, `build-env.ps1`, `publish-guard.ps1`, `lemuroid-app/build.gradle.kts` e
  `tests/*.ps1`; `-ApkArm64/-ApkArmv7` = os APKs da lane. Resultado **0/5**, exit 1 (21:59:52):
  - S1 FAIL: invariante quebrada nas 3 entradas (anunciado = sha da semente 4096/5120 B, encontrado = APK novo) + links
    fixos sobrescritos. S2 FAIL: invariante quebrada em `apkUrls[arm64-v8a]` e `apkUrl`. Sinal do bug presente na base.
  - S3 FAIL so pelas conferencias novas (URL nao versionada; 0 uploads versionados no log). S4 FAIL (a falha cai no
    sidecar do passo 3 da base e quebra a invariante; sem a mensagem). S5 FAIL (json local com URL fixa).
- Restos: nenhuma `lemuroid-harness-*`/`lemuroid-publish-*` em `%TEMP%`; nenhum `http.server`/`rclone` vivo
  (`Win32_Process`); a raiz do controle (scratchpad da sessao) apagada.
- T3: `git diff --stat origin/version10` so em `build-and-upload.ps1` e `tests/test-publicacao-anuncio.ps1` (+ docs);
  no diff nenhuma linha com HF/bump/aapt2/canal/trava (`grep -iE 'HfCli|HF_|bump|aapt|UPDATE_CHANNEL|Assert-Publish|Get-Announced'`
  vazio); na regiao do HF so os titulos `PASSO 2/5`.

### Ajustes ao Plano de teste

- S4 usa `RetroGameSystem/retro-game-system-armv7.apk` como texto da injecao (motivo acima).
- Para o teste: o harness na lane (comando acima; `-Cenario <nome> -ManterRaiz` guarda a raiz) e o controle da base com
  a raiz montada como descrito. T2 dispensado (Plano de teste); a prova em producao e do dono na proxima publicacao.

### Fora desta task (registrado, sem acao)

- Falha **dentro** do passo 4 depois do upload do json (purga/verificacao publica do json lancam) sai sem o lembrete de
  commit da TASK-0012. O anuncio, ai, ja aponta para objetos versionados e verificados (o app funciona); o que falta e
  so o lembrete. E a regiao da demanda `2026-11-20-publicacao-manual-sem-commit-versao-duplicada`; nao alterado aqui.

## Teste

Rodada 1 (2026-11-20), aprovada. Evidencia em `C:\projects\lemuroid\.bugfix-pipeline-Lemuroid\reports\TASK-0014-evidencia\`.

- **Diff:** `git diff ed30205..HEAD` so em `build-and-upload.ps1`, `tests/test-publicacao-anuncio.ps1` (reservados) e docs
  (esta task + nota na demanda `2026-11-20-harness-publicacao-sem-publish-guard`). Nenhuma linha +/- com
  `HfCli|HF_|bump|aapt|UPDATE_CHANNEL|Assert-Publish|Get-Announced`: HF, bump, trava e canal intactos. Simbolos que o
  trecho novo usa (`$apkSha256`, `$apkSizes`, `$dest`, `Invoke-R2Rclone`, `Purge-EdgeCache`) abertos e existentes no escopo.
- **Offline:** parser = 0 erros em `build-and-upload.ps1`, `tests/test-publicacao-anuncio.ps1`, `tests/fake-rclone.ps1`.
  `build` do helper na lane: exit 0, testes de unidade 22 suites / 138 testes / 0 falhas / 1 pulado (nenhum Kotlin mudou).
- **Controle positivo:** o harness desta task contra `git show ed30205:build-and-upload.ps1` (raiz temporaria com
  `build-env.ps1`, `publish-guard.ps1`, `build.gradle.kts`, `tests/*.ps1`; APKs debug da lane): **0/5**, exit 1;
  `cache-velho`, `falha-upload-armv7` e `falha-link-fixo` com "invariante QUEBRADA: o anuncio nao bate com o objeto
  para o qual aponta" (`controle-base-ed30205.log`). Sinal do bug presente na base.
- **T2 (o caminho do dono, sem app):** o `build-and-upload.ps1` real, rodado pelo harness contra o bucket falso, depois
  do `sync` (lane `513e82f` sobre `8a0215f`, base andou so em docs): **5/5 PASS**, exit 0 (`harness-fix-pos-sync-513e82f.log`;
  antes do sync tambem 5/5, `harness-fix-pre-sync.log`). Cenarios `sucesso` e `falha-link-fixo` rodados de novo com
  `-ManterRaiz` (pastas `sucesso/`, `falha-link-fixo/`: logs, `version-harness.json`, `bucket-ls.txt`):
  - `sucesso`: `rclone.log` na ordem `dist->arm64-269`, `dist->armv7-269`, `lsjson`, `version-harness.json`, `lsjson`,
    `:s3:arm64-269 -> arm64.apk`, `.sha256`, idem armv7, `lsjson`; json com `apkUrl` `.../retro-game-system-arm64-269.apk?v=269`;
    links fixos com os bytes novos (237381577 / 194763687 B); saida `PASSO 1/5`..`PASSO 5/5` e o lembrete de commit.
  - `falha-link-fixo`: `FALHA-INJETADA` no `copyto ...armv7-269.apk -> ...armv7.apk`; saida com `PASSO 5 FALHOU: anuncio OK;
    link manual desatualizado`, `DESATUALIZADO` no resumo, lembrete de commit e `PUBLICACAO INCOMPLETA`, exit 1; json
    anuncia 269 nas URLs versionadas, armv7 fixo ainda da semente (5120 B).
  - Aparelho nao usado: o app nao muda (T2 no app dispensado pelo Plano de teste).
- **T3:** S1-S3 sem afrouxar (anuncio e links fixos da semente intactos nas falhas); `-SkipR2` (S5) nao chama o rclone e
  gera o json local com as URLs versionadas; testes de unidade sem falha nova.
- **T4:** `script.err.log` vazio no `sucesso`; no `falha-link-fixo` so a linha da falha injetada. Sem restos
  (`lemuroid-harness-*`/`lemuroid-publish-*` em `%TEMP%` = 0; nenhum `http.server` vivo).
- **Falta para done (dono, `fecha_por: dono`):** na proxima publicacao real, `curl` do `version.json` publico, download
  dos dois APKs pelas URLs versionadas dele conferindo tamanho/sha, e o link fixo entregando a versao nova.
