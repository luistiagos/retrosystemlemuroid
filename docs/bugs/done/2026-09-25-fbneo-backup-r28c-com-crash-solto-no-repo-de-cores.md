# [BUG] Backup do FBNeo r28c (a versão que crasha em `retro_init`) solto no repo de cores, sem versionar

**Data:** 2026-09-25
**Status:** ✅ **Resolvido** em 2026-09-25 (diretório apagado; nada a commitar, era não versionado)
**Severidade:** Baixa. 209 MB fora do git, redundantes com o histórico, e um atalho de rollback
para um binário com crash conhecido.
**Branch:** `main` (lemuroid-cores)
**Origem:** investigação do working tree em 2026-09-25

---

## Sintoma

`lemuroid-cores/_fbneo_backup_20260918_r28c/` não está versionado:

```
60.3 MB  arm64-v8a/libfbneo_libretro_android.so
43.1 MB  armeabi-v7a/libfbneo_libretro_android.so
45.5 MB  x86/libfbneo_libretro_android.so
59.4 MB  x86_64/libfbneo_libretro_android.so
```

## Causa-raiz

Foi criado **de propósito** em [[2026-09-18-fbneo-retro-init-allocator-crash]]: "O core antigo
(r28c) foi preservado em `lemuroid-cores/_fbneo_backup_20260918_r28c/` para rollback rápido
caso o nightly novo regrida algo."

Três fatos mudaram a conta desde então:

1. **É redundante.** `git hash-object` das 4 ABIs bate com o FBNeo do `f41eeeb` (`c2e88b9^`) e
   do `b31f28b` (`origin/main`), nos dois módulos (`lemuroid_core_fbneo` e `bundled-cores`).
2. **É a versão com crash.** O r28c é o que dá SIGABRT (Scudo) ou SIGSEGV em `retro_init` em
   todo jogo FBNeo no Android 16. Deixá-lo ao lado do core bom, com nome de "backup", repete a
   armadilha dos AARs `.known-good` removidos em
   [[2026-09-03-investigacao-sigsegv-glthread-pc-desmapeado]]: parece rede de segurança e
   reintroduz o crash.
3. **O nightly novo já foi validado.** [[2026-09-22-fbneo-retro-run-segv-gameplay]] rodou
   KOF '97, Real Bout Fatal Fury Special e Marvel Super Heroes em aparelho, a 60 FPS.

## Correção

Apagado `lemuroid-cores/_fbneo_backup_20260918_r28c/`. Nada o referenciava em código ou
script, só o doc de 09-18, que ganhou uma nota apontando o rollback pelo git.

**A ordem proposta antes ("só depois de publicar o `c2e88b9`") não era necessária.** A
premissa era que o histórico que torna o backup redundante existia só neste disco. Não é o
caso: o r28c está no `b31f28b`, que é o `main` do GitHub (`git ls-remote origin main` →
`b31f28b`), e também no `f41eeeb`, ancestral dele. O que existe só neste disco é o `c2e88b9`,
ou seja, o nightly **novo**, e esse nunca esteve no backup. Apagar o diretório não mexe no risco
tratado por [[2026-09-25-lemuroid-cores-submodulo-divergente-commit-nao-publicado]].

Rollback, se um dia for preciso:

```
git -C lemuroid-cores restore --source=b31f28b -- "bundled-cores/src/main/jniLibs/*/libfbneo_libretro_android.so" "lemuroid_core_fbneo/src/main/jniLibs/*/libfbneo_libretro_android.so"
```

O comando usa um hash **publicado** (`b31f28b`), não `c2e88b9^`: o `c2e88b9` vai ganhar outro
hash quando for rebaseado sobre o `origin/main`. E usa `restore` em vez de
`git show … > arquivo`, porque no PowerShell 5.1 o `>` re-encoda a saída como texto e corrompe o
`.so`.

## Validação

- Antes de apagar: `git hash-object` dos 4 `.so` do backup == blob do `b31f28b` e do `f41eeeb`
  em `bundled-cores` e em `lemuroid_core_fbneo` (16 comparações, todas iguais). Contra o
  `c2e88b9` todas diferem, como esperado.
- `git -C lemuroid-cores status --short` sem `_fbneo_backup_*`.
- O rollback foi **executado** e desfeito: o `restore` acima deixou os 8 arquivos com o blob do
  `b31f28b` (hash conferido um a um), e `git restore` pelo índice os devolveu ao nightly do
  `c2e88b9` (status limpo).

## Lição

Backup de binário vira passivo no momento em que o novo é validado. Guardar o antigo num
diretório ao lado não acrescenta nada ao que o git já guarda, e o nome "backup" convida a
usá-lo. Antes de condicionar uma limpeza à publicação de um commit, conferir **qual** commit
contém o que se quer preservar: aqui era um que já estava no remoto.
