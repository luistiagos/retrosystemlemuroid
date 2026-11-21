---
tipo: implementacao
demandas: [2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio]
depende_de: []
integrado_em: 54291d11de32a6e3eacc5f00f3c359a8c46c06a9
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

## Estado

- **2026-11-20 (dev, pipeline 20261120-2117):** feito. Harness e rclone falso escritos, rodados duas vezes com os APKs
  debug da lane (resultado na secao Correcao). Proximo passo: `enqueue-test --bug TASK-0013` (sessao de teste roda o
  harness e confere S3 PASS, S1/S2 FAIL). Nada pendente nesta task.

## Correcao (pipeline 20261120-2117, rodada 1)

Arquivos novos (nenhum outro arquivo do repo mudou; `build-and-upload.ps1` intacto):

- `tests/fake-rclone.ps1` -- rclone falso. O harness o copia para `<raiz>\bin\rclone.ps1`; `Find-Rclone`
  (`build-and-upload.ps1:259-268`, `Get-Command rclone`) o acha antes do `rclone.exe` real (`D:\DevCaches\rclone`
  esta no PATH desta maquina; conferido: `Get-Command` devolve 1 resultado, `ExternalScript`, o da 1a pasta do PATH).
  Roda dentro do processo do script via `& $exe @args` e devolve por `exit N` -> `$LASTEXITCODE` (conferido: os
  `--flags` chegam intactos em `$args`, inclusive `--header-upload=Content-Type: text/plain; charset=utf-8`).
  Emula `copy`, `copyto` (origem local ou `:s3:`), `lsjson` (`Path/Name/Size/ModTime/IsDir`, prefixo inexistente = `[]`),
  `deletefile`; bucket com nome != `fakebucket` sai 2; `FAKE_RCLONE_FALHAR_EM` sai 1 sem escrever. Cada chamada vai
  para `FAKE_RCLONE_LOG` (`OK`/`FALHA-INJETADA`/`ERRO`).
- `tests/test-publicacao-anuncio.ps1` -- o harness, como especificado acima (raiz nova em `$env:TEMP` por cenario,
  gradle com o versionCode do `aapt2`, semente = versao `code-1` nos caminhos fixos, `cdn-velho` = copia do bucket antes
  do script, `python -I -m http.server` em porta livre de 127.0.0.1 parado no `finally`, filho
  `powershell -NoProfile -ExecutionPolicy Bypass -File <raiz>\build-and-upload.ps1 -SkipBuild -SkipHF` com
  `APP_UPDATE_CHANNEL=harness`, `TEMP/TMP` da raiz e `HF_TOKEN` removido). Prova de que rodou o falso: log do rclone
  falso nao vazio **e** a linha `rclone : <raiz>\bin\rclone.ps1` do preflight do script; cenario com injecao exige
  a linha `FALHA-INJETADA` no log (senao o cenario nao testou o que diz). Tabela `$Cenarios` com `Servir`, `FalharEm`,
  `Saida`, `ArgsExtras`, `ConfereLinksFixos`: a TASK-0014 acrescenta S4/S5 como novas entradas.

Duas armadilhas achadas ao rodar (ja corrigidas no harness):
1. `$PSScriptRoot` fica vazio nos defaults do `param()` no Windows PowerShell 5.1 -> o caminho padrao dos APKs virou
   `/../lemuroid-app/...`. Os padroes agora sao resolvidos depois do `param()`.
2. A raiz temporaria levava o nome do cenario (`...-falha-upload-armv7-...`): como a injecao casa com **qualquer**
   argumento, a falha caiu ja no upload do arm64 e S2 dava PASS falso. A raiz agora e `lemuroid-harness-publicacao-<guid8>`,
   com trava (raiz contendo o texto da injecao = erro do harness) e a exigencia da linha `FALHA-INJETADA`.

Resultado na base (`713b9cf` + esta task), APKs debug do `build` da lane (versionCode 269; arm64 `3a0301df...`
237381577 B, armv7 `a3ed1ce2...` 194763687 B), comando
`powershell -NoProfile -ExecutionPolicy Bypass -File tests/test-publicacao-anuncio.ps1`, duas rodadas seguidas
(21:36:11 e 21:36:49, ~38 s cada), resultado identico:

- `FAIL cache-velho`: script exit 1 (`VERIFICACAO FALHOU: a URL publica de arm64-v8a entregou bytes DIFERENTES`);
  invariante quebrada nas 3 entradas (`apkUrls[arm64-v8a]`, `apkUrls[armeabi-v7a]`, `apkUrl`): anunciado = sha da
  semente (4096/5120 B), encontrado = sha do APK novo. E o incidente de 2026-11-20.
- `FAIL falha-upload-armv7`: script exit 1 (`rclone copy falhou (armeabi-v7a)`); invariante quebrada em
  `apkUrls[arm64-v8a]` e `apkUrl` (o arm64 ja foi sobrescrito); armv7 intacto, coerente.
- `PASS sucesso`: exit 0, anuncio coerente, versionCode 269 anunciado, links fixos e `.sha256` com os bytes novos.
- Harness sai 1 (`1/3 cenario(s) passaram`), como esperado na base: S1/S2 sao o controle positivo do bug.
- Restos: nenhuma `lemuroid-harness-*` nem `lemuroid-publish-*` em `%TEMP%`; nenhum `python -m http.server` nem
  `rclone.exe` vivo (`Win32_Process` so lista os python do proprio pipeline); `git status` so com os dois arquivos de `tests/`.

Ajuste ao Plano de teste: nenhum. Para o teste: rodar o comando acima na lane (precisa dos APKs do `build`);
`-Cenario <nome>` roda um so, `-ManterRaiz` guarda a raiz (bucket, `rclone.log`, `script.out/err.log`) para inspecao.

## Teste

**Rodada 1 (sessao de teste, pipeline 20261120-2117): aprovado.** So fase offline: a task e ferramenta de teste da
publicacao, sem caminho no app (T2 dispensado pelo Plano de teste), entao nao houve slot nem aparelho.

- **Diff** (`git diff --stat origin/version10...HEAD`): `tests/fake-rclone.ps1` (+138), `tests/test-publicacao-anuncio.ps1`
  (+441) e este doc. `build-and-upload.ps1` e o app intactos; so arquivos reservados.
- **Offline / controle positivo** (`powershell -NoProfile -ExecutionPolicy Bypass -File tests/test-publicacao-anuncio.ps1`,
  APKs debug da lane, versionCode 269, arm64 `3a0301df...` 237381577 B, armv7 `a3ed1ce2...` 194763687 B), duas rodadas
  completas (21:39:44-21:40:20 e 21:40:28-21:41:02), resultado identico, harness exit 1 (`1/3 cenario(s) passaram`):
  - `FAIL cache-velho`: script exit 1 (`VERIFICACAO FALHOU: a URL publica de arm64-v8a entregou bytes DIFERENTES`);
    invariante quebrada em `apkUrls[arm64-v8a]`, `apkUrls[armeabi-v7a]` e `apkUrl`: anunciado = sha da semente
    (4096/5120 B), encontrado = sha do APK novo. Sinal do bug presente na base.
  - `FAIL falha-upload-armv7`: script exit 1 (`rclone copy falhou (armeabi-v7a)`); invariante quebrada em
    `apkUrls[arm64-v8a]` e `apkUrl` (arm64 ja sobrescrito).
  - `PASS sucesso`: exit 0, anuncio coerente, versionCode 269, links fixos e `.sha256` com os bytes novos.
- **Isolamento** (3a rodada, `-Cenario falha-upload-armv7 -ManterRaiz`, raiz inspecionada e apagada): `rclone.log` com
  `OK copy ... arm64.apk | :s3:fakebucket/RetroGameSystem | --s3-endpoint=http://127.0.0.1:9` e
  `FALHA-INJETADA copy ... armv7.apk`; `script.out.log` com `rclone : <raiz>\bin\rclone.ps1` (o falso);
  `build.properties` so com R2 falso e sem `CF_*`/`HF_TOKEN` (purga `PULADA`, HF `SKIPPED`).
- **Restos:** nenhuma `lemuroid-harness-*`/`lemuroid-publish-*` em `%TEMP%`; nenhum `rclone.exe` nem `http.server`
  vivo (`Win32_Process`); `git status` da lane limpo.
- **T3:** nenhum arquivo do app ou do script mudou (diff acima). **T4:** nao se aplica (sem app em execucao).
