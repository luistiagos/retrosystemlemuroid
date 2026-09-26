# [BUG] `.gitmodules` aponta o submódulo `lemuroid-cores` para `E:\`, que não existe

**Data:** 2026-09-25
**Status:** ✅ **Resolvido** em 2026-09-25
**Severidade:** Média. Nenhum clone novo conseguia baixar os cores (`git submodule update --init`
falhava), e nesta máquina qualquer operação que precisasse buscar o submódulo também falhava.
**Branch:** version9
**Origem:** investigação do working tree em 2026-09-25

---

## Sintoma

```
$ git config -f .gitmodules submodule.lemuroid-cores.url
e:\projects\lemuroid\LibretroCores

$ git ls-remote "e:\projects\lemuroid\LibretroCores"
fatal: 'e:\projects\lemuroid\LibretroCores' does not appear to be a git repository
```

O `.git/config` local herdou o mesmo valor (`submodule.lemuroid-cores.url`). O drive `E:` não
existe na máquina atual (ver "Ambiente de build" no CLAUDE.md, que registra a mudança de
`E:` para `C:`/`D:`). Mas o `origin` **do próprio submódulo** aponta para o lugar certo:
`https://github.com/luistiagos/libretrocores.git`.

## Causa-raiz

O `ef13728` (04-12, mensagem `*`) trocou a URL de `https://github.com/Swordfish90/LemuroidCores`
(o upstream) para o caminho local `e:\projects\lemuroid\LibretroCores`, onde o repo de cores do
fork vivia na máquina anterior. Quando o repo foi publicado em `luistiagos/libretrocores`, só o
`origin` do checkout do submódulo foi atualizado. O `.gitmodules`, que é o que um clone usa,
ficou com o caminho local.

Funcionava só enquanto ninguém precisasse buscar o submódulo do zero. Até então ninguém tinha
precisado, porque o checkout em disco já existia.

## Correção

```
git config -f .gitmodules submodule.lemuroid-cores.url https://github.com/luistiagos/libretrocores.git
git submodule sync lemuroid-cores     # propaga para .git/config
```

Resolvido junto com [[2026-09-25-lemuroid-cores-submodulo-divergente-commit-nao-publicado]]: com a
URL certa, um clone ainda falharia enquanto o commit fixado (`c2e88b9`) não estivesse publicado.
Os dois foram corrigidos no mesmo commit do Lemuroid, e o gitlink passou a apontar para um commit
que está no `origin/main` do GitHub.

A regra ficou no Pitfall 15 do [CLAUDE.md](../../../CLAUDE.md): URL de submódulo é sempre a do
remoto.

## Validação

- `git config -f .gitmodules submodule.lemuroid-cores.url` → `https://github.com/luistiagos/libretrocores.git`;
  `git config submodule.lemuroid-cores.url` (o `.git/config`, depois do `sync`) → o mesmo.
- `git ls-remote "$(git config -f .gitmodules submodule.lemuroid-cores.url)"` responde (`refs/heads/main`,
  `refs/tags/1.19.0`, `refs/tags/1.20.0`).
- **Clone limpo**: clone novo do Lemuroid com o `.gitmodules` e o gitlink deste commit +
  `git submodule update --init --depth 1 lemuroid-cores` → `Submodule 'lemuroid-cores'
  (https://github.com/luistiagos/libretrocores.git) registered` … `checked out '54188d6…'`.
  Os `.so` do clone batem byte a byte com os dos APKs de release (ver
  [[2026-09-25-lemuroid-cores-submodulo-divergente-commit-nao-publicado]]).

## Lição

Caminho absoluto de máquina em arquivo versionado quebra na troca de máquina. Aconteceu aqui e
no `gradle.properties` (ver "Ambiente de build" no CLAUDE.md). A URL que vale para todos é a do
remoto, nunca a do disco de quem configurou.
