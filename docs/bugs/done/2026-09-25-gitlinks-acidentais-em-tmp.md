# [BUG] Cinco repositórios git embutidos em `tmp/` foram commitados como gitlink sem `.gitmodules`

**Data:** 2026-09-25
**Status:** ✅ **Resolvido** em 2026-09-26
**Severidade:** Baixa-Média. Quebra `git submodule status`/`update` para o repositório inteiro,
um clone recebe pastas vazias e o `git status` fica com ruído permanente.
**Branch:** version9
**Origem:** investigação do working tree em 2026-09-25

---

## Sintoma

```
$ git submodule status
 c2e88b9… lemuroid-cores (1.17.0-8-gc2e88b9)
fatal: no submodule mapping found in .gitmodules for path 'tmp/flycast-pre2396-build'
```

`git ls-files -s | awk '$1=="160000"'` lista seis gitlinks. Só um deles tem entrada no
`.gitmodules`:

| Caminho | Commit que adicionou | Remoto do repo embutido | No disco | Estado |
|---------|---------------------|-------------------------|----------|--------|
| `lemuroid-cores` | `ef13728` (04-12) | GitHub | 14 GB | submódulo legítimo (ver bugs próprios) |
| `tmp/snes9x-source` | `5743485` (08-29) | `snes9xgit/snes9x` | 32 MB | limpo |
| `tmp/flycast-source` | `36aa389` (09-03) | `flyinghead/flycast` | 192 MB | limpo |
| `tmp/libretro-flycast-build` | `36aa389` (09-03) | `libretro/flycast` | 73 MB | limpo |
| `tmp/libretro-flycast-source` | `36aa389` (09-03) | `libretro/flycast` | 3,9 MB | **3.627 arquivos apagados** no working tree |
| `tmp/flycast-pre2396-build` | `36aa389` (09-03) | `C:/…/tmp/flycast-source` (local) | **2,1 GB** | 2 pastas de build não versionadas |

## Causa-raiz

Os clones em `tmp/` foram feitos para as investigações do Flycast
([[2026-09-02-flycast-arm64-core-hand-build-sigtrap]]) e do snes9x. Depois, commits com
`git add -A` (mensagem `*`) os pegaram. Como têm `.git` próprio, o git os grava como **gitlink**
(ponteiro para commit, modo `160000`) e só avisa `adding embedded git repository`, sem bloquear.

O comentário do `.gitignore` já prevê que "clones de build" em `tmp/` não entram
("Scratch de investigação sob tmp/ — imagens de disco, dumps de RAM e clones de build"), mas a
lista não inclui esses cinco.

## Correção

```
git rm --cached tmp/snes9x-source tmp/flycast-source tmp/libretro-flycast-build \
                tmp/libretro-flycast-source tmp/flycast-pre2396-build
```

O `--cached` tira os gitlinks do índice e **mantém as pastas no disco** (os cinco `.git`
continuam lá). Os cinco caminhos entraram no bloco "Scratch de investigação sob tmp/" do
`.gitignore`, com um comentário dizendo por que clone com `.git` próprio precisa estar ali.
O `tmp/fbneo-source/`, o único outro clone em `tmp/`, já era ignorado.

Nenhum código ou script de build referencia esses caminhos pelo git; um clone novo já recebia
pastas vazias, então a remoção não tira nada de quem clona.

**Não feito, de propósito:** `tmp/libretro-flycast-source` segue no disco com o working tree
quase todo apagado, apontando para o mesmo commit (`45bd2f4`) que `tmp/libretro-flycast-build`.
É candidato a ser apagado, mas isso é decisão de quem usa esses clones, e agora que está
ignorado não afeta o repositório.

## Validação

Rodado em 2026-09-26, depois da correção (remoções staged, antes do commit):

- `git submodule status` lista só `lemuroid-cores (1.20.0-1-g54188d6)`, sem `fatal`.
- `git ls-files -s | awk '$1=="160000"'` retorna uma linha só (`lemuroid-cores`).
- `git status --short tmp/` sem as linhas ` ?`/` m`; os cinco aparecem como `D ` (remoção do
  índice), que some no commit.
- `git check-ignore` confirma os cinco como ignorados, e `tmp/<clone>/.git` existe nos cinco.

## Lição

`git add -A` num repo com clones de terceiros dentro não falha: grava gitlink em silêncio.
Todo `git clone` feito dentro da árvore do projeto precisa ir junto com a entrada no
`.gitignore`, no mesmo passo.
