---
fecha_por: dono
tasks: [TASK-0013, TASK-0014, TASK-0016]
---

# [BUG] Atualização pelo app falha sempre depois de uma publicação interrompida: o `version.json` anuncia um APK que não está mais no ar

**Data:** 2026-11-20
**Severidade:** alta: enquanto durou, todo cliente abaixo da versão anunciada via "atualização disponível" e o download falhava sempre
**Complexidade:** media
**Branch:** version10
**Origem:** investigacao (publicação do pipeline 20261119-2228)

---

## Sintoma

De 2026-11-20 15:59 UTC até a publicação da 1.17.38 (~23:30 UTC do mesmo dia), todo cliente em versão
abaixo da 268, em qualquer aparelho (celular ou TV box), via a atualização para a 1.17.37 e o download
falhava sempre. O app recusa o arquivo quando tamanho ou sha256 não batem com o anúncio.

## Evidência

- A publicação do pipeline 20261119-2228 (`.bugfix-pipeline-Lemuroid/reports/20261119-2228-publicacao.log`) subiu os
  APKs ao R2 às 15:59 UTC, purgou o cache e parou na verificação pela URL pública:
  ```
  VERIFICACAO FALHOU: a URL publica de arm64-v8a entregou bytes DIFERENTES do publicado.
    esperado: 581eaf186d10cfff58a7dda6be929022506b89ce345237ffe928f7c93fe70206 (195323821 bytes)
    recebido: 92c9818bff082eec89ed6c6cbf266c595667237c65f1693bbdb0b127d6519307 (200337932 bytes)
  ```
  O passo 4 (`version.json`) não rodou.
- Horas depois, `curl -sI .../RetroGameSystem/retro-game-system-arm64.apk?v=268` já devolvia `Content-Length: 195323821`,
  `Last-Modified: Fri, 20 Nov 2026 15:59:51 GMT`, `cf-cache-status: DYNAMIC`, e o `.sha256` dizia `581eaf…`. Ou seja,
  a URL passou a entregar o APK novo.
- No mesmo momento, `curl -s .../RetroGameSystem/version.json` (`Last-Modified: Thu, 19 Nov 2026 05:25:07 GMT`)
  anunciava `versionCode 268`, `sha256 92c98…`, `size 200337932`: o binário **anterior**, publicado no mesmo caminho.
- `AppUpdateManager.kt:404` lança `IOException("Incomplete download: … expected …")` quando o tamanho difere, e
  `:411-412` compara o sha256. Com o anúncio velho e o arquivo novo, os dois falham.
- Resolvido de fato pela publicação manual da 1.17.38 (tag `publicado/1.17.38`, commit `bf8b799`). Conferido baixando
  os dois APKs pela URL do `version.json` público: arm64 200382907 B, sha `aa24ca6d…`, e armv7 163572953 B,
  sha `9cf25463…`, os dois **BATE**.

## Causa-raiz

`build-and-upload.ps1` publica sempre no **mesmo caminho** (`RetroGameSystem/retro-game-system-<abi>.apk`; o `?v=<code>`
é só query string e o R2 a ignora). Os passos estão nesta ordem:

1. Passo 3 (`build-and-upload.ps1:495` em diante): `rclone` sobrescreve o APK, verifica pela API S3, purga o cache e
   verifica pela URL pública (`:606`, `throw "VERIFICACAO FALHOU…"`).
2. Passo 4 (`:647` em diante): grava o `version.json` com sha e tamanho novos.

Deixar o anúncio por último só protege se o APK novo for para um caminho **novo**. Como o caminho é o mesmo, no
instante em que o passo 3 sobrescreve o objeto o anúncio antigo passa a apontar para bytes novos. Qualquer
`throw` entre o fim do upload e o fim do passo 4 deixa produção quebrada. Isso inclui a própria verificação pública,
que existe para proteger o cliente, além de queda de rede, credencial ou timeout. Não depende da versão duplicada
registrada em `2026-11-20-publicacao-manual-sem-commit-versao-duplicada`, que só tornou este caso visível.

Por que a URL pública ainda entregou o arquivo antigo logo depois da purga **não está provado**: hoje a resposta é
`cf-cache-status: DYNAMIC` (o Cloudflare não guarda cópia). As hipóteses são propagação do R2 ou purga ainda não
efetivada. Não muda a correção.

## Hipóteses descartadas

- **"Cache do Cloudflare, é só purgar e repetir"** (mensagem do próprio script): explica só por que a verificação
  falhou, não a quebra. A quebra vem de o anúncio antigo sobreviver ao APK que ele descreve. Repetir a verificação
  não conserta o `version.json`.
- **Código do app**: o `AppUpdateManager` faz o certo ao recusar bytes que não batem com o anúncio.

## O que fazer

1. `build-and-upload.ps1`, passo 3: subir cada APK para um caminho **versionado**
   (`RetroGameSystem/retro-game-system-<abi>-<versionCode>.apk`) e verificar esse caminho pela URL pública. O
   `version.json` do passo 4 aponta `apkUrls`/`apkUrl` para ele. Os caminhos fixos antigos só são atualizados
   **depois** do `version.json`: são o link que o suporte manda aos clientes, então continuam existindo, mas como
   último passo.
2. Com isso, falha em qualquer ponto antes do passo 4 deixa o anúncio antigo apontando para o objeto antigo, que
   não foi tocado.
3. Conferir que o parser do app instalado em campo aceita URL com outro nome de arquivo (`AppUpdateManager.kt`,
   leitura de `apkUrls[abi]`/`apkUrl`). Ele só lê a string, mas confirmar antes de publicar.
4. Opcional: retenção, ou seja, apagar do R2 os APKs versionados mais antigos que as N últimas versões.

Prova: rodar a publicação com uma falha forçada depois do upload (ex.: `throw` temporário antes do passo 4, num canal
de teste `APP_UPDATE_CHANNEL=teste`, que grava `version-teste.json`) e conferir que o `version-teste.json` público
continua batendo com o APK para o qual aponta.

## Planejamento (pipeline 20261120-2117, 2026-11-20)

**Veredito:** corrigir — duas tasks: `harness-publicacao-bucket-falso` (implementacao, teste novo: roda o `build-and-upload.ps1` real contra um bucket local e prova a invariante; vermelho na base) e `publicacao-apk-caminho-versionado` (a correção no script, verde no harness).
**Complexidade:** media — script de publicação, sem código do app; a parte delicada é a ordem das escritas e os caminhos de falha.
**Severidade:** alto — quando acontece, a atualização in-app fica inutilizável para todo cliente atrasado (contorno: link manual pelo suporte).
**Arquivos a alterar (reserva da onda, por task no plan.json):**
- `tests/test-publicacao-anuncio.ps1` e `tests/fake-rclone.ps1` — novos (task 1; task 2 acrescenta cenários).
- `build-and-upload.ps1` — passos 3/4 + passo novo dos links fixos (task 2). O bug `2026-11-20-publicacao-manual-sem-commit-versao-duplicada`, desta execução, reserva o mesmo arquivo (bump e fim): a onda serializa; sem `depende_de`, as regiões são diferentes.

### Causa raiz (símbolos abertos)
Confirma a seção "Causa-raiz" acima, conferida na worktree (base `f363183`):
- `build-and-upload.ps1` `:426-432` — `$r2PublicUrls[abi]` = `<base>/RetroGameSystem/retro-game-system-<abi>.apk?v=<code>`; `$r2BareUrls` = o mesmo sem query. Os dois apontam para o **mesmo objeto**.
- `:462-481` — `version.json` com `apkUrl`/`apkUrls` = `$r2PublicUrls`, sha/tamanho do `dist\`.
- `:519-526` — `rclone copy <dist> $dest` sobrescreve o objeto fixo. A partir daqui, até `:659` (upload do json), todo `throw` deixa o anúncio de produção descrevendo o binário anterior no objeto já sobrescrito: `:524`, `:542`, `:546`, `:563` (sidecar), `:230` (purga), `:599` (`Invoke-WebRequest` lança), `:605` (a verificação pública — o caso de 2026-11-20).
- `Purge-EdgeCache` `:209-234` — sem `CF_API_TOKEN`/`CF_ZONE_ID` só avisa e devolve `$false`; com eles, `throw` se a API recusar.
- `Find-Rclone` `:259-268` — `Get-Command rclone` -> `.Source`; `Invoke-R2Rclone` `:242-257` põe as chaves no ambiente e chama `& $exe @args` (o harness usa isso: rclone falso primeiro no `PATH`).
- `AppUpdateManager.fetchVersionInfo` (`lemuroid-app/.../updates/AppUpdateManager.kt:152-202`) — `apkUrls[abi]` lido como string (`optString`), fallback `apkUrl`; `downloadAndInstall` `:253-280` grava sempre em `cacheDir/updates/lemuroid-update.apk`; `downloadApk` `:351` usa a URL crua no OkHttp; `verifyApk` `:400-416` compara tamanho e sha. **URL com outro nome de arquivo funciona no app já instalado** (item 3 do "O que fazer": conferido, sem mudança no app).

### Provas
- `git grep -n "retro-game-system-\|RetroGameSystem/" -- ':!docs'` -> só `build-and-upload.ps1` publica no R2 (`release.ps1:65` e `build_apk.ps1:203-205` só nomeiam arquivos em `dist\`).
- `grep -rn "retro-game-system-arm\|retro-game-system-armv7" C:/projects/digitalstoregamesproject` -> o link fixo (`.../RetroGameSystem/retro-game-system-arm64.apk` / `-armv7.apk`) aparece em conversas de chamados (#106, #112, #123, #127, #129): o suporte o manda aos clientes, então ele continua existindo, atualizado por último.
- `git check-ignore -v dist/x.apk build.properties r2-config.json` -> os três ignorados: o harness pode criá-los numa raiz temporária sem sujar o repo.
- `python pipeline.py status` -> o irmão `2026-11-20-publicacao-manual-sem-commit-versao-duplicada` está nesta execução e mexe no mesmo script (bump `:106-160` e fim).

### Hipóteses descartadas
- **Só reordenar (json antes do APK)**: inverte o defeito — o anúncio novo aponta para o binário velho até o upload terminar, e uma falha no upload deixa o mesmo estado quebrado. Só caminho novo por versão separa os dois objetos.
- **Mudar o app** (aceitar sha divergente, reconsultar): o app está certo ao recusar; e o app em campo não muda.
- **Retenção de APKs versionados** (item 4, opcional): fora. ~360 MB por versão no R2 custa centavos por mês; apagar objeto em produção é risco que a correção não precisa. Se um dia pesar, vira backlog.
- **Prova pela publicação real num canal de teste** (proposta original no "Prova"): nenhuma sessão do pipeline roda o `build-and-upload.ps1` contra o R2 (manual, Finalização). Trocado pelo harness com bucket local, que roda o script real e reproduz o incidente (servidor HTTP servindo uma cópia velha = cache de borda velho).
- `.part` do `AppUpdateManager` retomado entre versões diferentes: o `verifyApk` pega e o retry recomeça do zero; não é este bug e não muda com a correção.

### Tarefas
- [ ] `harness-publicacao-bucket-falso` (implementacao) — `tests/test-publicacao-anuncio.ps1` + rclone falso; cenários S1 `cache-velho`, S2 `falha-upload-armv7`, S3 `sucesso` — prova: na base S3 PASS, S1/S2 FAIL com a divergência impressa.
- [ ] `publicacao-apk-caminho-versionado` (implementacao, depende da anterior) — `build-and-upload.ps1`: upload versionado `retro-game-system-<abi>-<code>.apk`, anúncio aponta para ele, links fixos + `.sha256` só depois do anúncio verificado; harness ganha S4 `falha-link-fixo` e S5 `skip-r2` — prova: S1–S5 PASS.

### Plano de teste
- **Offline (sem slot):** tudo. `build` do helper na lane para ter os APKs debug (arm64 e armv7, versionCode da base); harness com eles. Parser do PowerShell sem erro no script.
- **T2 reprodução como o usuário:** dispensada — o defeito é da publicação (script que só o dono roda), não há caminho no app e o app não muda. O harness reproduz o caminho que produziu o incidente (upload -> verificação pública vê bytes velhos -> `throw` antes do anúncio) e confere o que o cliente veria: o anúncio do bucket contra o objeto para o qual aponta.
- **Controle positivo:** harness contra o `build-and-upload.ps1` da base: S1 e S2 FAIL (anunciado = sha anterior, encontrado = sha novo no mesmo objeto).
- **T3 regressão:** S3 `sucesso` (links fixos com os bytes novos, anúncio coerente), S5 `-SkipR2`; diff sem mudança em HF/bump/aapt2/canal; testes de unidade do `build` sem falha nova.
- **Falta para done (dono, `fecha_por: dono`):** na próxima publicação real, ler o `version.json` público e baixar os dois APKs pelas URLs dele conferindo tamanho e sha, e o link fixo entregando a versão nova.

### Sessões seguintes
- dev `harness-publicacao-bucket-falso`: effort high, ultrathink não — PowerShell com processo filho, `PATH`, servidor local e emulação do rclone; trabalhoso, mas sem decisão de desenho aberta.
- dev `publicacao-apk-caminho-versionado`: effort high, ultrathink sim — ordem de escritas e o que cada falha deixa no ar.
- teste (as duas): effort medium, ultrathink não — rodar o harness e ler PASS/FAIL.
