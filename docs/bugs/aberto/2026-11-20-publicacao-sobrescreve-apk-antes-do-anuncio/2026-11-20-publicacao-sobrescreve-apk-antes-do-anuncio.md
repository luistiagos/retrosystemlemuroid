---
fecha_por: dono
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
