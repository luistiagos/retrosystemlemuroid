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
