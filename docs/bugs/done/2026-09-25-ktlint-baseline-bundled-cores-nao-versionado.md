# [BUG] Baseline do ktlint do `:bundled-cores` não versionado: `ktlintCheck` falha em clone limpo

**Data:** 2026-09-25
**Status:** ✅ **Resolvido** em 2026-09-25. Correção no `lemuroid-cores` `54188d6` (publicado
no GitHub). O Lemuroid passa a usá-la quando o gitlink apontar para esse commit, o que vai no
commit do [[2026-09-25-cores-sem-prefixo-lib-nao-extraidos-no-release]].
**Severidade:** Baixa. `./gradlew ktlintCheck`, que o CLAUDE.md afirma que passa, falha em
qualquer checkout que não seja este disco.
**Branch:** `main` (lemuroid-cores) · version9 (Lemuroid)
**Origem:** investigação do working tree em 2026-09-25

---

## Sintoma

`lemuroid-cores/bundled-cores/config/ktlint/baseline.xml` não está versionado no repo de cores.
Ele congela uma violação:

```xml
<file name="build.gradle.kts">
    <error line="31" column="29" source="standard:multiline-expression-wrapping" />
</file>
```

**Reproduzido** tirando o arquivo do lugar por um instante:

```
> Task :bundled-cores:ktlintKotlinScriptCheck FAILED
…\lemuroid-cores\bundled-cores\build.gradle.kts:31:29 A multiline expression should start on a new line
BUILD FAILED
```

Com o arquivo de volta (hash conferido), a task passa.

## Causa-raiz

O `build.gradle.kts` raiz do Lemuroid aplica o ktlint a todo subprojeto Android e aponta cada
um para `config/ktlint/baseline.xml` **relativo ao próprio módulo**. O `:bundled-cores` é
subprojeto do Lemuroid (`settings.gradle.kts`), mas o diretório dele mora **dentro do
submódulo** `lemuroid-cores`. O `ktlintGenerateBaseline` gravou o arquivo lá, num repo que
ninguém commitou, enquanto os baselines dos outros módulos entraram no commit `9190756` do
Lemuroid. É o único baseline dentro do submódulo: os `lemuroid_core_*` não têm violação.

## Correção

Aplicada a opção 2 das três levantadas: **corrigir a violação e apagar o baseline**. É o que o
CLAUDE.md pede ("regenerar só depois de corrigir"). As outras duas eram commitar o baseline no
repo de cores (o estilo do Lemuroid ficaria acoplado a outro repositório) ou desligar o ktlint
para o módulo (o script de build ficaria sem checagem).

- `lemuroid-cores/bundled-cores/build.gradle.kts`: a expressão `tasks.register(...) { … }`
  passou para a linha seguinte ao `val materializeNativeLibs =`, com o bloco reindentado em +4.
  A edição saiu do próprio `:bundled-cores:ktlintKotlinScriptFormat`, e não à mão, para
  coincidir com o que o checker exige. `git diff -w` mostra só a quebra de linha. O arquivo é
  idêntico no `c2e88b9` e no `origin/main`, então não conflita com o rebase de
  [[2026-09-25-lemuroid-cores-submodulo-divergente-commit-nao-publicado]].
- `lemuroid-cores/bundled-cores/config/` apagado (cópia do baseline antigo guardada fora do
  repo durante a validação; é recuperável pelo conteúdo acima).
- CLAUDE.md, seção "Estilo: ktlint com baseline": regra de que módulos dentro do submódulo não
  têm baseline.

**Commits:** `lemuroid-cores` `54188d6` (`style(bundled-cores): …`), commit próprio, separado
dos binários, feito sobre o `0efcc7a` depois do reposicionamento do `main` do submódulo
([[2026-09-25-lemuroid-cores-submodulo-divergente-commit-nao-publicado]]) e enviado por
fast-forward. O ponteiro do submódulo **não** foi commitado junto com este doc: ele também
carrega os renomes `lib*` do `0efcc7a`, que só funcionam com o `CoreID.kt` daquela correção, e
por isso vai no commit dela.

## Validação

- Baseline removido: `./gradlew :bundled-cores:ktlintKotlinScriptCheck` → reproduziu a falha
  `build.gradle.kts:31:29 … (standard:multiline-expression-wrapping)`.
- Depois do format, ainda sem baseline: `:bundled-cores:ktlintKotlinScriptCheck` →
  **BUILD SUCCESSFUL**. É o estado de um clone limpo depois do commit.
- `./gradlew ktlintCheck` (todos os módulos) → **BUILD SUCCESSFUL**, e o `bundled-cores/config/`
  não foi recriado.
- O script continua sendo avaliado pelo Gradle (a própria task de check depende disso). A
  mudança é só de espaço em branco, então o `materializeNativeLibs` não muda de comportamento.

## Lição

Subprojeto cujo diretório está dentro de um submódulo gera arquivos no repo **do submódulo**.
Ao rodar tarefa que gera arquivo por módulo (baseline, lint, schema), conferir `git status` dos
dois repositórios, não só do principal.
