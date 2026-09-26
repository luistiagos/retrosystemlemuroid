# [BUG] Submódulo `lemuroid-cores` divergente: commit fixado não publicado e 247 MB de cores do APK fora do git

**Data:** 2026-09-25
**Status:** ✅ **Resolvido** em 2026-09-25. `main` do `lemuroid-cores` reposicionado sobre o
`origin/main`, tudo versionado e enviado ao GitHub, e trava de release contra regressão.
**Severidade:** Alta. Um clone novo do Lemuroid não conseguia obter os cores. Numa perda de disco,
sumiria a única cópia do commit do FBNeo, e o APK era construído a partir de binários que nenhum
commit reproduzia.
**Branch:** version9 (Lemuroid) · `main` (lemuroid-cores)
**Origem:** investigação do working tree em 2026-09-25

---

## Sintoma

`git status` do Lemuroid mostrava ` ? lemuroid-cores` (conteúdo não versionado dentro do
submódulo). Dentro dele:

| O quê | Situação |
|-------|----------|
| Commit fixado pelo Lemuroid (`45ce0f6` → `c2e88b9`, "Atualiza core FBNeo…", 09-18) | **não existia no GitHub** (`git branch -r --contains c2e88b9` vazio; `ls-remote` → `main = b31f28b`) |
| `main` local | 1 commit à frente e **4 atrás** do `origin/main`; base comum `f41eeeb` (05-22) |
| 20 módulos `lemuroid_core_*` (dolphin, flycast, yabasanshiro, puae, opera, picodrive, atari800, hatari, neocd…) | **0 arquivos versionados** no `main` local; existiam só no disco |
| `bundled-cores/src/main/jniLibs` | 77 arquivos não versionados, **247 MB de `.so`** nas 4 ABIs |

Esses `.so` **iam dentro do APK distribuído** (conferido no
`lemuroid-app-free-bundle-arm64-v8a-debug.apk`: `dolphin_…` 13,6 MB, `puae_…` 20 MB,
`libflycast_…` 22,7 MB, `yabasanshiro_…`, `opera_…` e os demais).

Os 4 commits que o `main` local não tinha, todos no GitHub:

```
b31f28b 2026-07-08 fix: update Flycast core to 2026-07-07 nightly + patch libandroid.so into DT_NEEDED
270e48a 2026-06-19 new cores                                    (tag 1.19.0)
13d2c5e 2026-06-04 feat: add 5 new cores + sync 4 missing cores
b13b1af 2026-06-01 feat: add flycast and vircon32 cores (v1.18.0)
```

## Causa-raiz

O ponteiro do submódulo **andou para trás**. O histórico do gitlink `lemuroid-cores` no
Lemuroid:

| Commit Lemuroid | Data | Aponta para |
|-----------------|------|-------------|
| `41bf394` | 05-22 | `f41eeeb` |
| `f7ac9b6` | 06-27 | `270e48a` |
| `0528549` | 07-08 | `b31f28b` (= GitHub `main` até 2026-09-25) |
| **`e1cd92f`** ("`*`") | **07-21** | **`f41eeeb`**, dois meses para trás |
| `45ce0f6` | 09-19 | `c2e88b9` (FBNeo, feito **sobre** `f41eeeb`) |

O `e1cd92f` gravou o submódulo num commit antigo, provavelmente um `git add -A` com o submódulo
em checkout desatualizado. Os arquivos dos 4 commits posteriores ficaram no disco como não
versionados. Em 09-18, a atualização do FBNeo foi commitada sobre essa base velha e nunca
enviada. Por isso o `main` local e o GitHub tinham histórias separadas.

**Conferido arquivo a arquivo** (194 não versionados, `git hash-object` contra `origin/main`):

- 182 idênticos ao `origin/main`.
- **Flycast** (8 = 2 módulos × 4 ABIs): diferente. O do disco tem o **mesmo build-id** (`29be9a00…`)
  do Flycast da tag `1.19.0`, com +8 KB, que é o `libandroid.so` injetado no `DT_NEEDED` pelo
  `lief`. É idêntico ao `_flycast_backup_20260531_131454/` (conteúdo gravado em **07-20**, véspera
  do `e1cd92f`, apesar do nome). O `b31f28b` tem outro build, o nightly de 07/07 (`1d37e546…`). Os
  dois passam na `verifyFlycastCore`. O que ia no APK não era reproduzível a partir de nenhum commit.
- `lemuroid_core_vircon32/{build.gradle.kts,AndroidManifest.xml}` (2): diferença só de uma linha em
  branco no topo. Fica a do remoto.
- `lemuroid_core_a5200/{build.gradle.kts,AndroidManifest.xml}` (2): **não existiam em commit
  nenhum**. Eram a única cópia.

Efeito colateral no remoto: o GitHub ainda distribuía o **FBNeo r28c**, que crasha em
`retro_init` ([[2026-09-18-fbneo-retro-init-allocator-crash]]), porque o `c2e88b9` nunca foi
enviado.

## Correção

No `lemuroid-cores`:

1. Os 194 não versionados foram movidos para fora do repo (backup), liberando o checkout.
   O `c2e88b9` original ficou preservado no branch local `backup/main-pre-rebase-20260925`.
2. `git reset --keep origin/main` + `git cherry-pick c2e88b9` → **`1a88db7`** (FBNeo). O `--keep`
   preservou uma alteração não commitada de outra sessão em `bundled-cores/build.gradle.kts`.
   Depois do reset, os 182 arquivos idênticos voltaram pelo próprio checkout, conferidos byte a
   byte contra o backup.
3. **Flycast**: decidido manter o do disco, o que os usuários rodam hoje; trocar pelo do
   `b31f28b` mudaria o core de Dreamcast sem validação em aparelho. Commitado nas 4 ABIs dos
   dois módulos → **`de7f306`**.
4. `lemuroid_core_a5200/{build.gradle.kts,AndroidManifest.xml}` versionados → **`40462e4`**.
5. (Rename `lib*` do [[2026-09-25-cores-sem-prefixo-lib-nao-extraidos-no-release]] → `0efcc7a`, tag
   `1.20.0`.)
6. **`git push origin main`** (fast-forward `b31f28b..0efcc7a`, sem force) e **`git push origin 1.20.0`**,
   com confirmação do usuário. Isso também tira o FBNeo r28c de circulação no `main` remoto; a tag
   `1.19.0` fica como estava para as versões antigas do app.

No Lemuroid:

7. Novo ponteiro do submódulo commitado: `54188d6`, commit de estilo da outra sessão
   (`bundled-cores/build.gradle.kts`, ktlint) sobre o `0efcc7a`, já no `origin/main`. Os
   `jniLibs` são os mesmos da tag `1.20.0`.
8. URL do `.gitmodules` corrigida ([[2026-09-25-gitmodules-url-drive-e-inexistente]]).
9. **Trava `verifyCoresPublished`** ([BundledCoresVerifier.kt](../../../buildSrc/src/main/kotlin/BundledCoresVerifier.kt),
   registrada em [lemuroid-app/build.gradle.kts](../../../lemuroid-app/build.gradle.kts)), só em
   variante **release** (`merge*Release*NativeLibs`, `package*Release`, `bundle*Release`). Falha se:
   - `git -C lemuroid-cores status --porcelain --ignored -- bundled-cores/src/main/jniLibs` listar
     qualquer coisa (não versionado, modificado ou ignorado): o APK não seria reproduzível;
   - o `HEAD` do submódulo não estiver em nenhum branch remoto (`git branch -r --contains HEAD`,
     conforme o último fetch). Isso pega exatamente o `c2e88b9` local que nunca subiu;
   - a tag `CoreDownloader.CORES_VERSION` não tiver `lemuroid_core_<coreName>/…/<libretroFileName>`
     de todo `CoreID`.
   Não roda em debug: um core experimental solto no disco continua buildando para teste.
10. Pitfall 15 do [CLAUDE.md](../../../CLAUDE.md) com o fluxo: commit → push → tag → bumpar
    `CORES_VERSION` → ponteiro.

Fica fora do escopo, registrado aqui: os commits remotos `b13b1af..b31f28b` versionaram
diretórios `.gradle/` (cache de build) dentro dos `lemuroid_core_*`. É lixo inofensivo, mas deveria
sair do repo e ir para o `.gitignore`.

## Validação

- `git -C lemuroid-cores status --short`: vazio.
- `git ls-remote https://github.com/luistiagos/libretrocores.git`: `refs/heads/main` =
  `54188d6…`, `refs/tags/1.20.0^{}` = `0efcc7a…`; `git branch -r --contains <gitlink>` lista
  `origin/main`.
- `verifyCoresPublished`: **antes do push** falhou com "HEAD 0efcc7a is on no remote branch", o
  mesmo defeito deste bug; **depois do push**, passa. Com um `.so` solto em
  `bundled-cores/…/arm64-v8a`, falha listando o `?? …`.
- `assembleFreeBundleRelease` passa, com a `verifyCoresPublished` rodando dentro do build.
- **Clone limpo**: clone novo do Lemuroid com o `.gitmodules` e o gitlink deste commit +
  `git submodule update --init --depth 1 lemuroid-cores` → baixou do GitHub e fez checkout do
  `54188d6` (72 s). Os `.so` de core **dentro dos APKs de release** comparados por SHA-256 com os
  do `bundled-cores` do clone: **arm64-v8a 51/51 e armeabi-v7a 50/50 idênticos**. Um clone novo
  reproduz byte a byte o que vai para o usuário. No x86_64 (só de dev), 50/51: o `libfake08` de
  x86/x86_64 vem de `lemuroid-app/src/main/jniLibs/`, versionado no próprio Lemuroid, não do
  submódulo. A `verifyBundledCores` passou a checar o prefixo `lib` nesse diretório também.

## Lição

Commit com mensagem `*` feito com `git add -A` num repo com submódulo pode mover o ponteiro
para qualquer lugar, e o diff não mostra nada legível ("Subproject commit" trocado). Ponteiro de
submódulo que vai para uma data **anterior** à do commit que ele substitui é sinal de erro.

E o build empacota o que está **no disco**, não o que está no git. Enquanto nada comparar os dois,
um APK pode sair de binários que não existem em commit nenhum. É a segunda vez que isso acontece
com binário (a primeira foram os AARs `.known-good`,
[[2026-09-03-investigacao-sigsegv-glthread-pc-desmapeado]]), por isso a comparação agora é trava
de build.
