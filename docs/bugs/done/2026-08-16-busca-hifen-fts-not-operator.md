# [BUG] Busca não encontra títulos com hífen — o '-' virava operador NOT no FTS4

**Data:** 2026-08-16
**Status:** CORRIGIDO
**Severidade:** ALTA — busca devolve zero resultados para uma fatia grande do catálogo
**Branch:** version9

---

## Sintoma

Digitar `x-men` na busca não retorna **nenhum** dos 54 jogos de X-Men presentes no
`catalog_manifest.txt`. Digitar `xmen` ou `men` funciona normalmente.

O mesmo vale para qualquer título hifenizado — `spider-man`, `f-zero`, `r-type`,
`double-dragon` — ou seja, centenas de linhas do catálogo.

## Causa-raiz

`GameSearchDao.sanitizeFtsQuery` limpava aspas, parênteses, `:` e `*`, mas **deixava o hífen
passar** para dentro da expressão `MATCH`. O comentário dizia "hyphens/colons as operators",
mas só o `:` era de fato removido.

O Android compila o SQLite **sem** `SQLITE_ENABLE_FTS3_PARENTHESIS`, então o FTS usa a
*standard query syntax*, na qual um termo colado a um `-` anterior é uma **negação**. Então:

```
entrada do usuário : x-men
expressão gerada   : x-men*
como o FTS lê      : x NOT men*     ←  "contém x e NÃO contém men"
```

Ou seja, a busca por "x-men" produzia exatamente a única query capaz de **esconder** todo
X-Men do catálogo. Reproduzido fora do app (SQLite 3.50.4, FTS4 + unicode61):

```
'x-men*'  -> ['Mega Man X']                     ← a única linha com "x" e sem "men"
'x* men*' -> ['X-Men', 'X-Men: Children of the Atom', "Spider-Man - X-Men - Arcade's Revenge"]
'f-zero*' -> []
'f* zero*'-> ['F-Zero']
```

Repare que não é "nenhum resultado" por acaso: `x-men` devolvia *Mega Man X*. O sintoma de
"busca vazia" só acontece porque quase nada no catálogo tem um token `x` isolado.

Vale notar que o índice **sempre esteve correto** — o tokenizer `unicode61` quebra em qualquer
caractere que não seja letra ou dígito, então "X-Men" está gravado como os tokens `x` + `men`.
O erro era só do lado da query.

## Correção

[GameSearchDao.kt](../../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/GameSearchDao.kt):
trocada a blocklist de caracteres por um split na **mesma regra do tokenizer** — sequências de
tudo que não é letra nem dígito (`[^\p{L}\p{N}]+`) viram separador de termos:

```kotlin
private val NON_TOKEN_CHARS = "[^\\p{L}\\p{N}]+".toRegex()

private fun sanitizeFtsQuery(query: String): String {
    val terms = query.split(NON_TOKEN_CHARS).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return "\"\""
    return terms.joinToString(" ") { "$it*" }
}
```

Assim a query passa a espelhar exatamente o que está no índice (`x* men*`, AND implícito), e de
quebra cobre de uma vez todos os outros metacaracteres — incluindo o `^` do FTS4 ("precisa ser
o primeiro token"), que a blocklist antiga também não tratava.

Sem mudança de schema, de migração ou de `MANIFEST_SCHEMA_VERSION`: o índice não muda, só a
expressão de busca. Corrige retroativamente todas as instalações já existentes.

## Validação

- Reprodução e verificação do fix em SQLite 3.50.4 com o mesmo `CREATE VIRTUAL TABLE`
  (FTS4 + `unicode61 "remove_diacritics=1"`) — tabela de resultados acima.
- Casos de borda conferidos contra o FTS real:

  | entrada | expressão | resultado |
  |---|---|---|
  | `wolverine's rage` | `wolverine* s* rage*` | acha "X-Men - Wolverine's Rage" |
  | `pokémon` | `pokémon*` | acha "Pokemon Rojo" (diacrítico dobrado pelo tokenizer) |
  | `chip and dale` | `chip* and* dale*` | acha "Chip and Dale" — o `*` neutraliza o keyword AND |
  | `Samurai Sho` | `Samurai* Sho*` | acha "Samurai Shodown" (prefixo preservado) |
  | `sonic 3 & knuckles` | `sonic* 3* knuckles*` | acha "Sonic 3 & Knuckles" |
  | `---` / `:::(*)` / vazio | `""` | zero resultados, sem exception |

- `:retrograde-app-shared:compileDebugKotlin` — **BUILD SUCCESSFUL**.
- Pendente no dispositivo: digitar `x-men` e conferir a lista.

## Lição

- **Entrada de `MATCH` é sintaxe, não texto.** Sanitizar por blocklist de caracteres é frágil:
  basta um metacaractere esquecido (aqui o `-`, e também o `^`) para a query mudar de sentido.
  A regra certa é a allowlist do tokenizer — se o índice quebra em tudo que não é letra/dígito,
  a query tem que quebrar igual.
- O modo de falha foi **silencioso**: nenhuma exception, nenhum log, resultados plausíveis
  (`x-men` devolvia *Mega Man X*). Bug de sintaxe de query não estoura — ele responde outra
  pergunta.
- Os dois caminhos de busca (mobile `SearchViewModel` e `TVSearchViewModel`) passam pelo mesmo
  `GameSearchDao.search`, então o fix vale para os dois.
- O comentário do código *dizia* que tratava hífen ("hyphens/colons as operators") enquanto o
  código só tratava `:` — comentário desatualizado escondeu o bug de leituras anteriores.
