---
resultado: passou
base: fbcd7984a84c824e57842ea7e8d23a628e7f86fa
---
# Aceitacao de 2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio

Sessao de teste, pipeline 20261120-2117, 2026-11-21 ~01:15 UTC. Lane `wt-1` (`bugfix/TASK-0016`) = ramo principal com
TASK-0013 (`54291d11de`) e TASK-0014 (`04b5435742`) integradas (`git merge-base --is-ancestor` das duas = ok;
`git diff 04b5435 HEAD -- build-and-upload.ps1 tests/` vazio: o script testado e o da correcao).

## O que provar (Passo 1)

O sintoma nao tem caminho no app: e da publicacao (`build-and-upload.ps1`, que so o dono roda contra o R2), e o app nao
mudou (Plano de teste da demanda: T2 no aparelho dispensado). O caminho que produziu o incidente e reproduzido pelo
harness da TASK-0013, que roda o `build-and-upload.ps1` real da lane contra um bucket local (rclone falso + servidor
HTTP servindo uma copia velha = borda velha). Itens:

1. **Sintoma (S1 `cache-velho`, o incidente de 2026-11-20):** verificacao publica falha depois do upload -> o anuncio que
   fica no ar tem de bater com o objeto para o qual aponta.
2. **Falha no meio do upload (S2 `falha-upload-armv7`)** e **falha no link fixo depois do anuncio (S4)**: anuncio coerente.
3. **Regressao:** publicacao completa (S3 `sucesso`: anuncio novo nas URLs versionadas, links fixos e `.sha256` novos) e
   `-SkipR2` (S5: bucket intacto, json local).

Sem deploy no aparelho (nada do app a provar); o "binario em execucao" e o `build-and-upload.ps1` da lane
(sha256 `d81c9da3...`), copiado pelo harness para cada raiz temporaria.

## Resultado (Passo 5)

Comando: `powershell -NoProfile -ExecutionPolicy Bypass -File tests/test-publicacao-anuncio.ps1` na lane, payload = APKs
debug da lane (versionCode 269; arm64 `b441a9bf...` 237381562 B, armv7 `f6f4966a...` 194763670 B).
Log completo: `evidencias/harness-aceitacao-fbcd798.log` -> **5/5 PASS, exit 0** (fim 01:15:16 UTC).

- `PASS cache-velho`: script exit 1, anuncio coerente. Rodado de novo com `-Cenario cache-velho -ManterRaiz` e
  inspecionado: `script.out.log` com `PASSO 3/5: Upload para R2 (caminho versionado...)` e
  `VERIFICACAO FALHOU: nao foi possivel baixar a URL publica de arm64-v8a` (o versionado nao existe na borda velha).
  Bucket: `retro-game-system-arm64-269.apk` 237381562 B / `b441a9bf...` e `armv7-269.apk` 194763670 B / `f6f4966a...`
  (o APK novo so no caminho versionado); `retro-game-system-arm64.apk` 4096 B / `3736f1bc...` e `armv7.apk` 5120 B /
  `fc771049...` (semente intacta); `version-harness.json` anuncia 268, `apkUrls[arm64]` = `.../retro-game-system-arm64.apk`,
  sha `3736f1bc...`, 4096 B; armv7 `fc771049...`, 5120 B. **Anuncio = objeto apontado**: o cliente baixaria um arquivo que
  bate. Na base pre-correcao esse mesmo cenario dava anunciado != encontrado (controle positivo das TASK-0013/0014).
- `PASS falha-upload-armv7`: script exit 1, anuncio coerente.
- `PASS sucesso`: script exit 0, anuncio coerente.
- `PASS falha-link-fixo`: script exit 1, anuncio coerente.
- `PASS skip-r2`: script exit 0, anuncio coerente.

Restos: raiz mantida apagada; 0 `lemuroid-harness-*`/`lemuroid-publish-*` em `%TEMP%`; nenhum `http.server`/`rclone`
vivo (`Win32_Process`); `git status` da lane limpo antes deste registro. Testes de unidade/ktlint nao se aplicam (nenhum
Kotlin na demanda; a integracao ja compilou e rodou os testes).

## Falta para done (dono, `fecha_por: dono`)

Na proxima publicacao real: `curl` do `version.json` publico, baixar os dois APKs pelas URLs versionadas dele conferindo
tamanho/sha, e o link fixo entregando a versao nova.
