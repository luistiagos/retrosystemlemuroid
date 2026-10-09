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
