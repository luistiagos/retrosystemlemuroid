---
tipo: implementacao
demandas: [2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio]
depende_de: []
---
# Harness offline da publicação: build-and-upload.ps1 contra um bucket R2 falso, provando que o anúncio sempre bate com o APK

Demanda: `docs/bugs/aberto/2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio/` (leia a seção "Planejamento").

## Objetivo

Hoje não há como testar o `build-and-upload.ps1` sem publicar de verdade (e nenhuma sessão do pipeline pode publicar:
manual, seção Finalização). Esta task cria um teste que roda o script **real**, sem rede externa e sem credencial,
contra um "bucket" que é uma pasta local, e confere a invariante que o bug quebrou:

> Em qualquer momento (depois de sucesso **ou** de falha no meio), para cada ABI, o objeto para o qual o
> `version*.json` do bucket aponta tem exatamente o `apkSizes[abi]`/`apkSha256[abi]` anunciados (e o par legado
> `apkUrl`/`size`/`sha256`).

Na base atual o cenário S1 abaixo **tem de falhar** (é o controle positivo do bug); a task 2
(`publicacao-apk-caminho-versionado`) o deixa verde. Esta task NÃO muda o `build-and-upload.ps1`.

## O que criar

`tests/test-publicacao-anuncio.ps1` (ao lado de `tests/native/`, que tem a mesma convenção `test-*.ps1`; pasta nova não entra na reserva da onda) e, ao lado, o rclone falso
(`tests/fake-rclone.ps1` ou equivalente). Parâmetros: `-ApkArm64 <apk>` e `-ApkArmv7 <apk>` (os
APKs debug da lane servem: `lemuroid-app/build/outputs/apk/freeBundle/debug/lemuroid-app-free-bundle-<abi>-debug.apk`).
Saída: uma linha `PASS`/`FAIL` por cenário com o motivo, exit 0 só se todos passarem; opção `-Cenario <nome>` para rodar um só.

### Isolamento (obrigatório; nada pode tocar o R2/Cloudflare de produção)

1. Raiz temporária nova por cenário (em `$env:TEMP`), com cópias de `build-and-upload.ps1`, `build-env.ps1` e
   `lemuroid-app/build.gradle.kts` (este com `versionCode`/`versionName` reescritos para o versionCode que o `aapt2`
   lê dos APKs passados — o script confere isso em `:318-350`), `dist/retro-game-system-arm64.apk` e
   `dist/retro-game-system-armv7.apk` (cópias dos APKs), e um `build.properties` com R2 **falso**:
   `R2_ACCESS_KEY_ID=fake`, `R2_SECRET_ACCESS_KEY=fake`, `R2_ENDPOINT=http://127.0.0.1:9`, `R2_BUCKET=fakebucket`,
   `R2_PUBLIC_URL=http://127.0.0.1:<porta>`; **sem** `CF_API_TOKEN`/`CF_ZONE_ID` (a `Purge-EdgeCache` então só avisa
   e devolve `$false`, `:213-217`) e sem `HF_TOKEN`. Com `R2_*` no `build.properties` o script nunca cai no
   `r2-config.json` local nem no do GODSend (`:395-410`).
2. O script roda num processo filho (`powershell -NoProfile -ExecutionPolicy Bypass -File <raiz>\build-and-upload.ps1
   -SkipBuild -SkipHF`), com `PATH` do filho começando pela pasta do rclone falso e `APP_UPDATE_CHANNEL=harness`
   (grava `version-harness.json`). `-SkipBuild` também pula o bump (`:113-114`): o gradle da raiz temporária não muda.
3. Conferir que quem rodou foi o falso: `Find-Rclone` (`:259-268`) usa `Get-Command rclone` e devolve `.Source`; o
   falso grava um log de cada chamada e o harness falha se o log estiver vazio. Mesmo que o rclone real fosse achado,
   endpoint `127.0.0.1:9` + chave falsa não alcançam o R2.

### rclone falso

Emula só o que o script usa, sobre `<raiz>\bucket\` (`:s3:fakebucket/RetroGameSystem/x` -> `<raiz>\bucket\RetroGameSystem\x`):
`copy <arquivo> <pasta-destino> ...` (grava com o nome do arquivo), `copyto <origem> <destino> ...` (origem local **ou**
`:s3:` — cópia dentro do bucket, que a task 2 pode usar), `lsjson <pasta>` (JSON com `Name`, `Size`, `IsDir`, como o
script lê em `:532-536` e `:664-665`), `deletefile` (por via das dúvidas). Ignora flags `--...`. Injeção de falha:
se `FAKE_RCLONE_FALHAR_EM` estiver definida e algum argumento a contiver, sai com código 1 **sem escrever**
(simula rede/credencial caindo naquela chamada).

### Servidor HTTP local (o "cache de borda")

`python -m http.server <porta> --bind 127.0.0.1 --directory <pasta>` (ou um `HttpListener` do PowerShell), parado no
fim de cada cenário mesmo em falha. A pasta servida é escolha do cenário: o próprio `bucket\` (cache coerente) ou um
`cdn-velho\` = cópia do bucket tirada **antes** de rodar o script (cache de borda servindo bytes antigos — foi o que
aconteceu em 2026-11-20 15:59 UTC).

### Estado inicial do bucket (semente)

Reproduz produção como está hoje: versionCode anterior = `<code do APK> - 1`; `RetroGameSystem/retro-game-system-arm64.apk`
e `-armv7.apk` com bytes aleatórios distintos dos APKs novos (alguns KB bastam), os `.sha256` correspondentes, e
`version-harness.json` no formato que o script gera (`:462-481`) apontando para
`http://127.0.0.1:<porta>/RetroGameSystem/retro-game-system-<abi>.apk?v=<code anterior>` com o sha/tamanho desses bytes.

### Verificação de coerência (a invariante)

Ler `bucket\RetroGameSystem\version-harness.json`; para cada ABI de `apkUrls` (e para o legado `apkUrl`): tirar o
`R2_PUBLIC_URL` e a query string, achar o arquivo no `bucket\`, comparar tamanho e sha256 com `apkSizes`/`apkSha256`
(legado: `size`/`sha256`). Arquivo ausente ou divergente = FAIL, imprimindo anunciado x encontrado.

### Cenários

- **S1 `cache-velho`** (o incidente): servidor servindo `cdn-velho\`. Esperado: o script sai ≠ 0 (a verificação
  pública vê bytes velhos ou 404) **e** a invariante vale no bucket. Na base: invariante QUEBRA (o APK fixo foi
  sobrescrito e o json anterior aponta para ele) -> FAIL. É o controle positivo.
- **S2 `falha-upload-armv7`**: servidor no `bucket\`, `FAKE_RCLONE_FALHAR_EM=armv7`. Esperado: script ≠ 0 e
  invariante vale. Na base: FAIL (arm64 já sobrescrito).
- **S3 `sucesso`**: servidor no `bucket\`, sem injeção. Esperado: exit 0, invariante vale, o json anuncia o
  versionCode dos APKs, e `retro-game-system-<abi>.apk` (link fixo do suporte) + `.sha256` têm os bytes novos.
  Na base: PASS.

## Teste que prova esta task

Na base (lane desta task, sem a task 2): `S3` PASS, `S1` e `S2` FAIL com a divergência impressa (anunciado = sha da
semente, encontrado = sha do APK novo). O harness rodou sem tocar a rede externa (log do rclone falso preenchido,
nenhum processo `rclone.exe` real).

## Plano de teste

- **Offline:** o `build` do helper na lane (produz os APKs debug); rodar o harness com eles; esperado acima
  (S3 PASS, S1/S2 FAIL). Rodar duas vezes seguidas: mesmo resultado, nenhum resto (servidor parado, raízes temporárias
  limpas ou só em `$env:TEMP`). `git status` da lane limpo além dos arquivos novos em `tests/` (os dois arquivos do harness).
- **T2 (usuário):** dispensado — é ferramenta de teste da publicação, não há caminho no app.
- **Controle positivo:** é o próprio resultado na base (S1/S2 FAIL).
- **T3:** nenhum arquivo do app ou do script muda; `git diff --stat` só em `tests/` (os dois arquivos do harness).
