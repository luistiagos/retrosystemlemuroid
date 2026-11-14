---
movido_por: Luis Tiago - 2026-11-14 - "corrigido no adaptador (.pipeline/projeto.json, publicacao.cmd povoa o lemuroid-cores) na T24 do pipeline-global; prova local sem upload saiu 0"
---
# [BUG] Publicação do pipeline falha: a lane `wt-planning` não tem o submódulo `lemuroid-cores` (`:bundled-cores` sem variantes)

**Data:** 2026-11-14
**Severidade:** alta
**Complexidade:** baixa
**Branch:** version10
**Origem:** investigacao (finalização da execução 20261114-1244 do pipeline)

## Sintoma

Na finalização da execução 20261114-1244, o `final-publicar` rodou o `build-and-upload.ps1` na lane
`wt-planning` e a publicação parou no passo 1 (build release). Nenhuma versão foi publicada: a v1.17.35 (versionCode
266) não saiu, não há tag `publicado/*` e a demanda `2026-10-25-busca-gamecube-traz-neo-geo-pocket-color` ficou em
`testado/`, sem ir para `no-ar/`. É a primeira execução com o adaptador `.pipeline/projeto.json` (commit `f7310ea`),
então toda publicação pelo pipeline vai falhar do mesmo jeito.

## Evidencia

`C:\projects\lemuroid\.bugfix-pipeline-Lemuroid\reports\20261114-1244-publicacao.log` (2026-11-14T13:27:19):

```
Executando: .\gradlew.bat assembleFreeBundleRelease
...
* What went wrong:
Could not determine the dependencies of task ':lemuroid-app:lintVitalReportFreeBundleRelease'.
> Could not resolve all dependencies for configuration ':lemuroid-app:freeBundleReleaseCompileClasspath'.
   > Could not resolve project :bundled-cores.
      > No matching variant of project :bundled-cores was found. ... - No variants exist.
BUILD FAILED in 32s
release.ps1 falhou com exit code 1
```

Na lane, `git submodule status` → `-478fc9137f08ff7d767f95ca18a925f666d9b83b lemuroid-cores` (o `-` quer dizer
submódulo não inicializado) e `lemuroid-cores/bundled-cores` não existe.

## Causa-raiz

`.pipeline/projeto.json`: o `build.cmd` povoa o submódulo antes do gradle (`git -c protocol.file.allow=always -c
'submodule.lemuroid-cores.url={root}/lemuroid-cores' submodule update --init lemuroid-cores`, com o comentário "numa
worktree nova ele vem vazio"), mas o `publicacao.cmd` chama direto `{wt}\build-and-upload.ps1`, sem esse passo. A
`wt-planning` só recebe docs e nunca passou pelo `build`, então chega à publicação sem o submódulo.

## Hipoteses descartadas

- Credencial (HF_TOKEN/R2) ou `release.jks` ausentes: o script passou da checagem de `hf`/`rclone` e falhou na
  resolução de dependências do Gradle, antes de qualquer upload ou assinatura.
- Código integrado quebrado: o mesmo código compilou nas lanes de dev e de teste (TASK-0001), que passaram pelo
  `build.cmd`, este sim com o submódulo povoado.

## O que fazer

- Em `.pipeline/projeto.json`, `publicacao.cmd`: trocar `-File {wt}\build-and-upload.ps1` por `-Command` com o mesmo
  `submodule update --init lemuroid-cores` do `build.cmd` (do clone `{root}/lemuroid-cores`, sem rede), seguido de
  `& '{wt}\build-and-upload.ps1'; exit $LASTEXITCODE`. Conferir antes que o motor substitui `{root}` também no
  `publicacao.cmd`.
- Republicar o que ficou pendente: a correção do apelido "gamecube" (TASK-0001, `f2a17e8`) está em `origin/version10`
  mas fora de qualquer versão publicada.
- Prova: o próximo `final-publicar` passa do `assembleFreeBundleRelease` e grava a tag `publicado/<versao>`.

## Correção (2026-11-14, T24 do pipeline-global)

`.pipeline/projeto.json`: o `publicacao.cmd` passou a `powershell -Command` que faz o mesmo `submodule update --init
lemuroid-cores` do `build.cmd` (sai com o código dele) e roda o `build-and-upload.ps1` num `try/catch` (`throw` ->
`exit 1`). Prova local: o comando novo com `-NoBump -SkipHF -SkipR2 -SkipPublicVerify` numa worktree descartável de
`origin/version10` (submódulo vazio antes) saiu 0, com `dist/retro-game-system-{arm64,armv7}.apk` e nada publicado. A
prova real é a publicação da próxima execução do pipeline.
