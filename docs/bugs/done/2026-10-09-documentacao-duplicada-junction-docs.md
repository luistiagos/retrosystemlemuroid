# [BUG] Toda a documentacao versionada em dobro (`docs/` e `documentacao/`) por causa de uma junction

**Data:** 2026-10-09
**Severidade:** Baixa para o app (nada vai no APK); media para o repo (cada doc de bug exige dois commits iguais, e
worktrees/clones divergem da arvore principal)
**Branch:** version9
**Origem:** pedido do dono ("colocar tudo em docs e remover o documentacao"), achado da T4 do pipeline de correcao
(`C:\projects\retrobatnew\source\skills\pipeline-correcao-bugs\DESIGN.md` 22.11.D)

---

## Sintoma

O repo rastreia a mesma documentacao em dois caminhos, `docs/` e `documentacao/`. Toda sessao que cria ou move um doc
de bug precisa commitar os dois (ex.: `9a6461a`, `27320f1`: 2 arquivos cada). Fora da arvore principal (worktree,
clone) os dois caminhos sao pastas independentes e podem divergir.

## Evidencia

- `Get-Item docs` na arvore principal -> `LinkType=Junction`, `Target=c:\projects\lemuroid\Lemuroid\documentacao`
  (criada 2026-09-17 22:07). `documentacao` e pasta real.
- `git ls-tree -r HEAD` (script de comparacao, 2026-10-09 20:20): `docs` = 130 arquivos, `documentacao` = 130, comuns 130,
  conteudo identico em 130 (mesmo blob). Nada so de um lado.
- `docs/` entrou no git em 2026-09-19 (`45ce0f6`, primeiro arquivo adicionado sob `docs/bugs`).
- Referencias a `documentacao` em arquivos rastreados fora das duas arvores (`git grep -n documentacao`): 31 arquivos
  - so comentarios: `buildSrc/src/main/kotlin/*Verifier.kt` (7 linhas), `lemuroid-app/build.gradle.kts` 598/616, 15 `.kt`
    do app/shared/util, `lemuroid-app/src/main/res/layout/activity_crash.xml:33` (comentario XML), docstrings de
    `audit_dex_api_level.py`, `patch_flycast_libandroid.py` e 6 `tmp/patch_*.py`;
  - texto: `CLAUDE.md` (5 linhas: tabela `[FEATURE]`/`[BACKLOG]` e 2 links de backlog);
  - config: `.claude/settings.json:18` (permissao `Read(.../documentacao/bugs/open/**)`) e `:23` (`additionalDirectories`).
- Dentro de `docs/`: 22 arquivos citam `documentacao/...` (bugs 22, backlogs 8, funcionalidades 5, prompts 4).
- Fora do repo: 2 bug docs do `digitalstoregamesproject` citam caminho do Lemuroid em `documentacao/`
  (`docs/modules/chatbot-whatsapp/areas/prompt-kb/bugs/2026-10-05-agente-inventa-causa-...md` e
  `.../2026-09-29-agente-nao-pergunta-o-que-deu-errado-no-pedido-de-reembolso.md`).
- Mudanca sem commit de outra sessao (parada desde 15:23) dentro das arvores: o doc
  `2026-10-09-busca-presa-ao-sistema-sem-aviso-nenhum-item.md` movido de `open/` para `done/` nos dois caminhos
  (`D` em `open/`, `??` em `done/`; o `D` de `documentacao/` ja staged).

## Causa-raiz

A junction `docs -> documentacao` (2026-09-17) fez as duas pastas serem o mesmo arquivo no disco da arvore principal;
o Git for Windows trata a junction como pasta comum, entao `git add docs/...` gravou uma SEGUNDA arvore com os mesmos
blobs a partir de 2026-09-19. Desde entao todo `git add` de doc pega os dois caminhos.

## Hipoteses descartadas

- **Tirar so `documentacao/` do indice e manter a junction:** o disco continuaria com `docs` apontando para uma pasta
  que o git nao rastreia; um `git clean`/checkout novo quebraria a ligacao. O dono quer `documentacao` fora.
- **`git mv documentacao docs`:** `docs/` ja esta rastreada com o mesmo conteudo; basta tirar `documentacao/` do indice.
- **Apagar a junction com `Remove-Item -Recurse`:** num link, isso pode apagar o conteudo do ALVO. A junction sai com
  `cmd /c rmdir docs` (remove so o ponto de reparse).
- **Editar os 2 docs do `digitalstoregamesproject`:** checkout compartilhado, commit local la vai ao ar no deploy de outra
  sessao; ficam para o dono (os nomes dos arquivos continuam validos trocando `documentacao/` por `docs/`).

## Correcao planejada

| # | task | prova |
|---|---|---|
| T1 | disco: `cmd /c rmdir docs` (so a junction), `documentacao` -> `docs` (rename); git: `git rm -r --cached documentacao` e commit SO desse caminho | `Get-Item docs` sem `LinkType`; `Test-Path documentacao` falso; `git ls-files documentacao` = 0; `git ls-files docs` = 130 + os 2 deste trabalho; `git status -- docs` so com a mudanca da outra sessao |
| T2 | `documentacao/` -> `docs/` em todo arquivo rastreado que cita (os 31 + os 22 de `docs/`), exceto os sujos/novos de outra sessao; `.claude/settings.json` com os dois caminhos | `git grep -n documentacao` so acha este doc e citacoes historicas intencionais; `gradlew :lemuroid-app:assembleFreeBundleDebug` verde (so comentario mudou em `.kt`/`.kts`/`.xml`) |
| T3 | textos que explicam a junction: `CLAUDE.md` (Workflow), `docs/bugs/open/README.md`, manual do pipeline (`.claude/skills/pipeline-correcao-bugs/projeto.md`) e o DESIGN 22.11.D da fonte | leitura; preflight do pipeline sem mudanca |

Se a outra sessao commitar depois disto com `git add documentacao/...`, o git recusa o comando inteiro (pathspec
inexistente): ela deve commitar so `docs/...`. O `CLAUDE.md` passa a dizer isso.

## Correcao aplicada

- **T1 `0a7b9cf`:** `cmd /c rmdir docs` (rc 0; `documentacao` seguiu com 131 arquivos), `Rename-Item documentacao docs`
  (ok; `Get-Item docs` sem `LinkType`, `Test-Path documentacao` falso, 131 arquivos), `git rm -r --cached documentacao`
  (130 `D`, nada fora de `documentacao/` staged) e commit so desse caminho. Depois: `git ls-files documentacao` = 0,
  `git ls-files docs` = 131; `git status -- docs` so com o `D`/`??` da outra sessao (doc da busca).
- **T2 `664443f`:** script com troca em bytes (`documentacao/` -> `docs/`, e as formas com `\\` do `settings.json`) em 53
  arquivos rastreados e limpos, 84 trocas; pulados o doc da outra sessao, este doc e a copia da skill do pipeline.
  Conferido: os 53 sao exatamente o `HEAD` com a troca (com CRLF normalizado), `--numstat` 84/84. Os 2 restos (texto sobre
  a junction no `CLAUDE.md` e no README) foram para a T3.
- **T3:** `CLAUDE.md` (Workflow: tudo em `docs/`, nao recriar `documentacao/` nem junction), `docs/bugs/open/README.md`
  e o manual do pipeline (`.claude/skills/pipeline-correcao-bugs/projeto.md`: some a secao do espelho) reescritos.
- Desvio do plano: nenhum. Fica de fora, para o dono: os 2 bug docs do `digitalstoregamesproject` que citam caminhos do
  Lemuroid em `documentacao/` (lista na Evidencia); e o doc da busca da outra sessao (`docs/bugs/done/2026-10-09-busca-...`,
  ainda nao commitado), que ela deve commitar so pelo caminho `docs/`.

- **T3 `f89011a`.**

## Testes executados

- Comparacao das arvores no `HEAD` antes da T1: 130 = 130, blobs identicos (nada perdido ao tirar `documentacao/`).
- `git ls-files documentacao` = 0 e `git ls-files docs` = 131 depois da T1; `Get-Item docs` sem `LinkType`.
- Conferencia da T2: os 53 arquivos = `HEAD` com a troca, nada mais.
- Build do `664443f` (T2) numa worktree temporaria (submodulo do clone local): `gradlew :lemuroid-app:assembleFreeBundleDebug
  :lemuroid-app:testFreeBundleDebugUnitTest` rc 0 em 349 s; na worktree `docs/` e pasta e `documentacao/` nao existe;
  `git status` dela vazio; removida sem sobra.
- `git grep -n documentacao` depois da T3: nenhum caminho vivo. Sobram so (a) os avisos de proposito "a antiga
  `documentacao/` foi removida" no `CLAUDE.md`, no README de `open/` e no manual do pipeline, (b) este doc, (c) a palavra
  "documentacao" (= documentacao, nao a pasta) no motor da skill (`SKILL.md:46`, `pipeline.py:2314`,
  `git_pipeline.py:209`) e (d) o registro historico na copia do DESIGN da skill. Nenhum arquivo nao rastreado cita.

## Passagem de bastao — links no digitalstoregamesproject (pedido do dono, 2026-10-09 ~21:00)

Ultimo resto: dois bug docs do `C:\projects\digitalstoregamesproject` citam caminho do Lemuroid em `documentacao\`.
Nao foi feito nesta sessao (checkout compartilhado; regras do repo de la). Medido as ~21:00:

| doc (em `docs/modules/chatbot-whatsapp/areas/prompt-kb/bugs/`) | linha | hoje | trocar por |
|---|---|---|---|
| `2026-10-05-agente-inventa-causa-e-manda-reinstalar-o-app-android-para-erro-rom-not-found.md` | 37 | `C:\projects\lemuroid\Lemuroid\documentacao\bugs\open\2026-10-05-catalogo-sf2-champion-edition-aponta-rom-nao-hospedada-rom-not-found.md` | `C:\projects\lemuroid\Lemuroid\docs\bugs\done\2026-10-05-catalogo-sf2-champion-edition-aponta-rom-nao-hospedada-rom-not-found.md` (**o doc mudou de `open/` para `done/`**: `git -C C:\projects\lemuroid\Lemuroid ls-files "docs/bugs/*catalogo-sf2*"`) |
| `2026-09-29-agente-nao-pergunta-o-que-deu-errado-no-pedido-de-reembolso.md` | 68 | `C:\projects\lemuroid\Lemuroid\documentacao\bugs\done\2026-10-02-prebuilt-fileuri-sem-encoding-recria-catalogo.md` | o mesmo com `docs\` no lugar de `documentacao\` (existe em `docs/bugs/done/`) |

**Cuidados (medidos):**
1. Os DOIS docs ja estao `M` sem commit, junto com ~140 docs da mesma pasta: e a carimbagem do gerador
   (`tools/gen_status.py`: front-matter `status`, `commits`, `gerado_em`; `--numstat` 3/3 e 5/5) de outra sessao.
   `git commit -- <doc>` levaria esse hunk junto. Antes de editar: `git -C C:\projects\digitalstoregamesproject status --short --
   docs/modules/chatbot-whatsapp/areas/prompt-kb/bugs/<doc>`. Se ainda `M`: ou espere o commit de quem rodou o gerador, ou
   grave so a sua linha no indice (`git diff` -> patch com o hunk da linha 37/68 -> `git apply --cached`) e commite sem pathspec
   de arquivo inteiro. Nunca `git checkout`/`restore`/`stash` no hunk alheio.
2. Regras de commit de la (`AGENTS.md` do repo, "Commits, tasks e autoria"): um commit por doc, a mensagem cita o caminho do
   doc, nunca dois docs no mesmo commit; nao digitar `Commits:`/`Status:` (o gerador escreve).
3. Commit local la vai ao ar no deploy de outra sessao (`deploy-all.ps1`): aqui e so texto de doc, sem efeito em producao;
   nunca rodar o `deploy-all.ps1`.
4. A busca desta sessao la foi so em `*.{md,py,json,ps1,txt,html}`: repita sem filtro
   (`git -C C:\projects\digitalstoregamesproject grep -n -i "lemuroid.\{0,3\}.documentacao"`) antes de dar por encerrado.

**Prova de pronto:** o `grep` do item 4 sem linha; os dois caminhos novos existem (`Test-Path`); `git show --stat` de cada
commit com so o doc dele e so a linha do link.

## Licao

Junction dentro de um repo git no Windows nao e transparente: o Git for Windows a trata como pasta e versiona o conteudo
duas vezes. Pasta de docs com outro nome se resolve com rename + `git rm --cached`, nunca com link.
