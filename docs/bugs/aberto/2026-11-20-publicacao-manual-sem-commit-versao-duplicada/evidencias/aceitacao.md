---
resultado: passou
base: ed30205c3b5fb093ad0eb70b4fb2c1243b48f288
---
# Aceitacao de 2026-11-20-publicacao-manual-sem-commit-versao-duplicada

Pipeline 20261120-2117, task `prova` TASK-0015, lane `wt-1` = `origin/version10` com TASK-0011 (`24e15cb`) e
TASK-0012 (`8aec970`) integradas; HEAD testado `ed30205`, sem `sync`. Windows PowerShell 5.1.26100 (o do dono).
Sem aparelho e sem `deploy` (`deploy: null`): o "binario" e o `build-and-upload.ps1` + `publish-guard.ps1` da lane,
rodados por copia; os sha256 das copias batem com os da lane (`aceitacao/binario-lane.txt`; o harness do item 4
mostra os mesmos sha). No ar durante o teste: `version.json` do canal padrao = 269 / 1.17.38 (`aceitacao/no-ar.txt`).
Nenhum `release.ps1`/Gradle/publicacao real rodou; pastas de rascunho apagadas no fim. Evidencia em `aceitacao/`.

## 1. Sintoma da demanda (T2): uma segunda arvore repetir o numero que ja esta no ar

Receita do Planejamento: pasta de rascunho **sem `release.ps1`** com copia de `build-and-upload.ps1`,
`build-env.ps1`, `publish-guard.ps1` e `lemuroid-app/build.gradle.kts`, sempre `-SkipHF -SkipR2`.

| Caso | Entrada | Esperado | Observado | Arquivo |
|---|---|---|---|---|
| 1 | `.kts` 268/1.17.37 (a arvore que partiu do origin sem o bump), sem flag | aborta antes do bump | exit 1, "TRAVA DE VERSAO: o bump geraria o versionCode 269, que nao passa do 269 que ja esta no ar", no ar 269 (1.17.38) + URL, seria 269, commit "desconhecido", "Nada foi escrito"; sem "PASSO 1/4"; `.kts` continua 268 | `t2-1-bump-268-repete-numero.txt` |
| 6 | repo git temporario: `.kts` 267 commitado + 268 **nao commitado** (o estado exato da arvore do dono em 19/11) | aborta citando commit e `.kts` sujo | exit 1, "commit de partida: 0044290", "build.gradle.kts com mudanca nao commitada: sim", orientacao `chore(versao): publicado/1.17.38`; sem "PASSO 1/4"; `.kts` intacto | `t2-6-git-checkout-kts-sujo.txt` |
| 2 | `.kts` 269 = anunciado, `-NoBump` | aborta (mesmo numero, bytes novos) | exit 1, "-NoBump recompilaria o versionCode 269..."; sem "PASSO 1/4"; `.kts` intacto | `t2-2-nobump-269-igual-anunciado.txt` |
| 3 | `.kts` 269 = anunciado, sem flag | trava passa, bump depois dela | "versionCode: 269 -> 270", `.kts` vira 270/1.17.39, para no PASSO 1/4 por falta do `release.ps1` (de proposito) | `t2-3-bump-269-passa.txt` |
| 4 | `APP_UPDATE_CHANNEL=canalteste-inexistente`, `.kts` 268 | 404 = canal novo, segue | "canal canalteste-inexistente sem anuncio em .../version-canalteste-inexistente.json: seguindo (canal novo)", 268 -> 269, para no PASSO 1/4 | `t2-4-canal-sem-anuncio.txt` |

Sinal do sintoma (gerar/publicar um `versionCode` que ja esta no ar) **ausente**; comportamento certo (abortar antes
de escrever qualquer coisa, dizendo versao no ar, versao gerada e commit de partida; seguir quando o numero passa ou
o canal nao tem anuncio) **presente**.

## 2. Lembrete de commit/tag/push no fim da publicacao (item 2 da demanda)

`build-and-upload.ps1` REAL de ponta a ponta pelo harness da TASK-0013 (`tests/test-publicacao-anuncio.ps1`,
cenario `sucesso`: bucket R2 falso, servidor HTTP local, `-SkipBuild -SkipHF`, raiz temporaria sem git). Rodado de
uma copia do harness com **uma linha a mais** copiando o `publish-guard.ps1` para a raiz (achado lateral ja aberto
pela TASK-0012: `2026-11-20-harness-publicacao-sem-publish-guard`; nao e desta demanda e nao reprova).
PASS, script exit 0, anuncio coerente, `script.err.log` vazio. A saida termina com o bloco vermelho
"FALTA COMMITAR A VERSAO PUBLICADA (1.17.38-DEBUG)", aviso de checkout sem branch, aviso "nunca git add -A",
`git add lemuroid-app/build.gradle.kts`, `git commit -m "chore(versao): publicado/1.17.38-DEBUG"`,
`git tag publicado/1.17.38-DEBUG`, `git push origin <branch> publicado/1.17.38-DEBUG`, **antes** de
"Todos os passos concluidos com sucesso!". A trava rodou no mesmo script (no ar 268 semente, `-SkipBuild` 269: passa).
Arquivos: `t2-7-harness-sucesso-lembrete.txt`, `t2-7-script.out.sucesso.log`. Com `-SkipR2` (casos 1-4) o bloco nao
aparece. A prova no terminal real do dono e a proxima publicacao (`fecha_por: dono`).

## 3. Controle positivo (estatico; a base nao roda: dispararia Gradle release)

`aceitacao/controle-positivo-estatico.txt`: em `713b9cf` (antes das tasks) o `WriteAllText` do bump esta em `:160` e o
"PASSO 1/4" em `:294` sem nenhuma leitura do anuncio antes, e o script tem 0 linhas com `git `. Em `ed30205`:
`Get-AnnouncedVersion` `:165` -> `Assert-PublishVersionGate` `:218` -> `WriteAllText` `:229`; trava NoBump/SkipBuild
`:254` antes do "PASSO 1/4" `:366`; lembrete `:796`. Mais a evidencia historica da demanda (duas 268, `92c9818b…` e
`581eaf18…`).

## 4. Regressao direcionada (T3/T4)

- `tests/test-publish-guard.ps1 -Offline` -> "Todos os 48 casos passaram.", exit 0 (`t1-offline.txt`); sem
  `-Offline` -> 51/51, exit 0, anuncio real 269, canal inexistente `$null`, host invalido lanca (`t1-online.txt`).
- `-SkipBuild` com `.kts` 269 = anunciado e um `dist\retro-game-system-arm64.apk` de outro binario -> exit 1,
  "-SkipBuild republicaria o versionCode 269, que ja esta no ar com OUTRO binario"; sem "PASSO 1/4"
  (`t3-5-skipbuild-269-apk-diferente.txt`). Republicacao com sha igual passa: casos do teste offline.
- Publicacao completa com sucesso (item 2) continua exit 0 e com anuncio coerente: a trava e o lembrete nao quebraram
  o caminho feliz.
- Sem app nem aparelho envolvidos: sem logcat. Saidas sem erro alem dos esperados (o `throw` da trava, que o 5.1
  imprime duas vezes, e o `release.ps1` ausente de proposito).
