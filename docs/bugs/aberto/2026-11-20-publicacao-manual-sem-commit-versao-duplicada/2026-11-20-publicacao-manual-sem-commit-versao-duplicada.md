---
fecha_por: dono
tasks: [TASK-0011, TASK-0012, TASK-0015]
---

# [BUG] Duas versões 1.17.37 (versionCode 268) diferentes foram publicadas: quem instalou a primeira nunca receberia a segunda

**Data:** 2026-11-20
**Severidade:** alta: o cliente que instalou a 1.17.37 de 19/11 ficaria sem as correções da 1.17.37 de 20/11, sem aviso, porque o versionCode é o mesmo
**Complexidade:** baixa
**Branch:** version10
**Origem:** investigacao (publicação do pipeline 20261119-2228)

---

## Sintoma

Em 2026-11-19 e 2026-11-20 saíram duas publicações com o mesmo `versionCode 268` / `1.17.37` e binários diferentes.
O app compara só o `versionCode`. Quem instalou a primeira nunca seria avisado da segunda, que tinha as correções da
execução 20261119-2228 (ANR do canal de notificação, marcador de foreground no lowmemory).

## Evidência

- `dist/version.json` da árvore principal, gravado em 2026-11-19 02:23 (-03), tem `versionCode 268`,
  `versionName 1.17.37`, `sha256 92c9818b…`. O `dist/retro-game-system-arm64.apk` (200337932 B) é da mesma hora.
  O `version.json` público tinha `Last-Modified: Thu, 19 Nov 2026 05:25:07 GMT`, com o mesmo conteúdo.
- Nesse momento o git estava em 267: `19cacda` (2026-11-19 02:12 -03) sobe `266 → 267` / `1.17.35 → 1.17.36`, e não há
  tag `publicado/1.17.36` nem `publicado/1.17.37`.
- O `268` só entrou no git em `ec4e2f6` (2026-11-20 13:00 -03), o commit "chore(terceiros): codigo local nao commitado de
  outra sessao" da finalização. O bump ficou sem commit na árvore principal por ~35 h e foi recolhido como "código de
  terceiros".
- A publicação do pipeline (`reports/20261119-2228-publicacao.log`, 12:46 -03) partiu do origin em 267 e o script subiu
  de novo: `versionCode: 267 -> 268`, `versionName: 1.17.36 -> 1.17.37`. O resultado foi outro binário 268
  (`581eaf18…`, 195323821 B).

## Causa-raiz

`build-and-upload.ps1` (bloco "BUMP DE VERSAO", `:106-160`) grava o bump no `build.gradle.kts` e publica, mas **não**
commita nem cria a tag. Também não confere se a versão nova é maior que a que está no ar. Quando o bump não é
commitado, ele se perde para qualquer lane ou máquina que parta do origin, e a próxima publicação repete o número.

## Hipóteses descartadas

- **Rodar o script duas vezes na mesma árvore**: não explica. Na mesma árvore o segundo bump iria para 269. A
  duplicação exige duas árvores com o mesmo ponto de partida (árvore principal com o bump não commitado e lane do
  pipeline a partir do origin).

## O que fazer

1. `build-and-upload.ps1`, antes do bump: ler o `version.json` público do canal (`$versionJsonUrl`) e abortar se o
   `versionCode` que vai ser gerado for `<=` o anunciado, com mensagem dizendo qual versão está no ar e de qual commit
   o script partiu. Sem `version.json` (canal novo), segue.
2. `build-and-upload.ps1`, no fim com sucesso: imprimir em destaque o lembrete com os comandos exatos
   `git add lemuroid-app/build.gradle.kts`, `git commit -m "chore(versao): publicado/<v>"`, `git tag publicado/<v>` e
   `git push origin <branch> publicado/<v>`. Commit automático não: o dono publica da árvore principal compartilhada,
   e o script não deve commitar por cima do trabalho de outras sessões.
3. Desde 2026-11-20 o pipeline não publica mais (`publicacao: null` no `.pipeline/projeto.json`, decisão do dono), o que
   elimina a segunda árvore desta ocorrência. O item 1 continua necessário: vale também para duas máquinas ou
   checkouts do dono.

Prova do item 1: com o `version.json` público em 269, rodar o script num checkout com `versionCode = 268` (bump iria
para 269) e ver que ele aborta antes de compilar.

## Correção

Situação em produção resolvida pela 1.17.38 / 269 (`bf8b799`, tag `publicado/1.17.38`), maior que as duas 268.

## Planejamento (pipeline 20261120-2117, 2026-11-20)

**Veredito:** corrigir — duas tasks de implementação no `build-and-upload.ps1`: `trava-versao-anunciada` (aborta antes
do bump se a versão gerada não passa da anunciada no `version.json` do canal) e `lembrete-commit-tag-publicacao` (no
fim, imprime os comandos de commit/tag/push do bump).
**Complexidade:** baixa — script de publicação, sem código do app; a regra (comparar `versionCode` com o anúncio) já
existe no app (`AppUpdateManager`) e só falta aplicá-la antes do bump.
**Severidade:** alto — quem instala o binário anterior com o mesmo `versionCode` nunca recebe a correção seguinte, sem
aviso e sem contorno do lado do cliente.
**Arquivos a alterar (reserva, por task no plan.json):**
- `build-and-upload.ps1` — resolução da config R2 e da URL do anúncio sobe para antes do bump; chamada da trava;
  lembrete no fim.
- `publish-guard.ps1` (novo, raiz, dot-source como o `build-env.ps1`) — funções testáveis da trava e do lembrete.
- `tests/test-publish-guard.ps1` (novo) — teste offline da tabela de decisão e leitura real do anúncio.

### Causa raiz (símbolos abertos)
- `build-and-upload.ps1` bloco "BUMP DE VERSAO" (`:106-160`) — lê `versionCode`/`versionName` do
  `lemuroid-app/build.gradle.kts`, soma 1 e **grava o arquivo** (`WriteAllText`, `:158`) sem consultar o que está no
  ar. `-SkipBuild`/`-NoBump` pulam o bump e usam o valor do arquivo como está. Em erro: `throw` só se não achar as
  linhas.
- `build-and-upload.ps1` leitura da versão (`:162-171`) — `$VERSION_CODE`/`$VERSION_NAME` saem do `.kts` já bumpado.
- `build-and-upload.ps1` "CONFIG R2" (`:390-408`) e "URLs PUBLICAS" (`:415-418`) — `$cfg` e `$publicBase` só são
  resolvidos **depois** do build e do HF; `$VERSION_JSON_NAME`/`$versionJsonUrl` (`:461-463`) idem. Hoje o script não
  tem como ler o anúncio antes do bump sem mover esses blocos para cima. Eles só leem arquivos locais
  (`build.properties`, `r2-config.json`, `E:\projects\GODSend\r2-config.json`); sem nenhum, `$cfg = $null` e
  `$publicBase` cai em `https://versions.digitalstoregames.com` — não lançam. `$UPDATE_CHANNEL` (`:179`) vem do
  ambiente.
- `build-and-upload.ps1` "GATE" (`:300-337`) — confere só que o APK de `dist\` tem o `versionCode` do `.kts`; não
  compara com o anúncio.
- `build-and-upload.ps1` passo 4 (`:647-696`) e "SUMMARY" (`:704-727`) — publicam e imprimem URLs; **nenhum** comando
  `git` (nem commit, nem tag, nem lembrete).
- `build-env.ps1::Initialize-BuildEnv` — só preenche variáveis de ambiente vazias; `::Find-HfCli` (`:81`) é chamado no
  preflight. Nenhum dos dois depende de arquivo do repo além do próprio `build-env.ps1`.

### Provas (comando literal -> resultado)
- `curl -s https://versions.digitalstoregames.com/RetroGameSystem/version.json` -> `"versionCode": 269`,
  `"versionName": "1.17.38"`, `sha256 aa24ca6d…` (produção hoje).
- `curl -s -o /dev/null -w "%{http_code}" https://versions.digitalstoregames.com/RetroGameSystem/version-canalinexistente.json`
  -> `404` (canal sem anúncio responde 404: é o caso "canal novo, segue").
- `grep -n "versionCode\|versionName" lemuroid-app/build.gradle.kts` -> `:71 versionCode = 269`,
  `:72 versionName = "1.17.38"` na base (`f363183`); `git log -1 bf8b799` = `chore(versao): publicado/1.17.38`.
- `git tag -l 'publicado/*'` -> só `publicado/1.17.35` e `publicado/1.17.38` (faltam 1.17.36 e 1.17.37: bumps
  publicados sem commit nem tag).

### Hipóteses descartadas
- **Commit automático do bump pelo script:** descartado pelo próprio doc (item 2 de "O que fazer"): o dono publica da
  árvore principal, compartilhada com outras sessões; commitar ali pode levar trabalho alheio. Fica o lembrete.
- **Trava só no caminho com bump:** insuficiente. `-NoBump` com o `.kts` igual ao anunciado recompila a mesma versão
  com bytes novos (é exatamente a 268 duplicada). A trava vale nos três modos (tabela na task).
- **Seguir quando o anúncio não pode ser lido:** descartado. Só o `404` significa canal novo; qualquer outra falha
  (rede, 5xx, JSON sem `versionCode`) aborta. Sem rede a publicação falharia adiante de qualquer jeito, e seguir às
  cegas reabre o bug.
- **Mudar o caminho dos APKs no R2:** é o bug irmão `2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio` (mesmo
  arquivo, outra região: passos 3 e 4). Não entra aqui; a reserva de `build-and-upload.ps1` serializa os dois na onda.

### Tarefas
- [ ] `trava-versao-anunciada` (implementacao) — `publish-guard.ps1::Get-AnnouncedVersion` e
  `::Test-PublishVersionGate`; `build-and-upload.ps1` move config R2 e URL do anúncio para antes do bump e chama a
  trava antes do `WriteAllText` — prova: `tests/test-publish-guard.ps1` e execução numa cópia de rascunho com o
  `.kts` em 268 abortando antes do bump.
- [ ] `lembrete-commit-tag-publicacao` (implementacao, depende da anterior) —
  `publish-guard.ps1::Get-PublishCommitReminder` chamado no fim do `build-and-upload.ps1` — prova: o mesmo teste,
  casos do lembrete.

### Plano de teste
- **Offline (sem slot):** `powershell -NoProfile -File tests/test-publish-guard.ps1` na lane: tabela de decisão
  da trava (todos os casos da task) e o texto do lembrete; sai 0. Sem aparelho e sem build.
- **T2 reprodução como o usuário (E2E):** o "usuário" é o dono rodando `.\build-and-upload.ps1`; não há caminho de UI
  no app (bug de publicação), por isso dispensa aparelho. Receita segura, numa pasta de rascunho **sem `release.ps1`**
  (se a trava falhar, o passo 1 morre em "release.ps1 não encontrado": nada compila nem publica): copiar da lane
  `build-and-upload.ps1`, `build-env.ps1`, `publish-guard.ps1` e `lemuroid-app/build.gradle.kts` (mantendo a
  subpasta), editar a cópia do `.kts` para `versionCode = 268` e `"1.17.37"` e rodar
  `powershell -NoProfile -File <rascunho>\build-and-upload.ps1 -SkipHF -SkipR2`. Sinal: exit diferente de 0, mensagem
  citando a versão no ar (269 / 1.17.38, ou a do dia), a versão que seria gerada (269) e o commit de partida (ou
  "desconhecido" fora do git); **não** aparece "PASSO 1/4"; a cópia do `.kts` continua em 268. Segundo caso: `.kts` em
  269 (o atual) -> a trava passa, o `.kts` vira 270 e o script para no passo 1 por falta do `release.ps1` (a trava não
  bloqueia publicação legítima e o bump vem depois dela).
- **Controle positivo:** sem rodar a base (rodá-la dispararia o `release.ps1`/Gradle release fora do helper e, sem
  `-Skip*`, uma publicação real): leitura estática — `git show <base>:build-and-upload.ps1 | grep -n
  "versionJsonUrl\|WriteAllText"` mostra o `WriteAllText` do bump (`:158`) antes de qualquer `versionJsonUrl`
  (`:463`); mais a evidência histórica do doc (duas 268, sha `92c9818b…` e `581eaf18…`).
- **T3 regressão direcionada:** (1) `-SkipBuild` com o APK de `dist\` igual ao anunciado (republicação idempotente)
  passa a trava — coberto no teste offline com sha injetado; (2) canal novo
  (`$env:APP_UPDATE_CHANNEL='canalteste-inexistente'`) na cópia de rascunho com o `.kts` em 268: a trava avisa "canal
  sem anúncio" e segue (para no passo 1); (3) `git diff <base> -- build-and-upload.ps1` só mexe na posição dos blocos
  de config, na chamada da trava e no fim; (4) roda em Windows PowerShell 5.1 (`powershell.exe`, a do dono), não só
  no `pwsh`.
- **Falta para done (dono):** a próxima publicação real do dono prova o lembrete no terminal dele (`fecha_por: dono`).

### Sessões seguintes
- `trava-versao-anunciada` — dev: effort high, ultrathink não (três modos × 404/erro/JSON e ordem dos blocos no
  script); teste: effort medium, ultrathink não (receita fechada, sem aparelho).
- `lembrete-commit-tag-publicacao` — dev: effort low, ultrathink não (texto e duas consultas git); teste: effort low,
  ultrathink não.
