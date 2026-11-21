---
tipo: implementacao
demandas: [2026-11-20-publicacao-manual-sem-commit-versao-duplicada]
depende_de: []
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
