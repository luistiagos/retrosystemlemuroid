# [BUG] Atualização in-app nunca chega a ninguém — o endereço consultado responde 404

**Data:** 2026-08-13
**Status:** Corrigido ✅
**Severidade:** Crítica (todo usuário em campo fica preso na versão que instalou)
**Branch:** version9

---

## Sintoma

O app nunca avisa que existe versão nova. Nem ao abrir, nem em **Configurações → Verificar atualizações** — ali o resultado era sempre "sem atualizações" ou um erro. Todo usuário ficava congelado na versão que instalou, mesmo com releases publicadas.

## Causa-raiz

Duas falhas em série, e a primeira sozinha já bastava.

**1. O endereço consultado não existe.**

`AppUpdateManager.VERSION_ENDPOINT` vinha de `BuildConfig.APP_UPDATE_ENDPOINT`, cujo padrão em [`lemuroid-app/build.gradle.kts`](../../../lemuroid-app/build.gradle.kts) era:

```
https://emuladores.pythonanywhere.com/app_version
```

```console
$ curl -o /dev/null -w "%{http_code}" https://emuladores.pythonanywhere.com/app_version
404
```

A rota nunca foi criada. Toda checagem lançava `IOException("Version check failed: HTTP 404")`.

**2. A falha era invisível.**

`AppUpdateViewModel.checkOnStartup()` engole a exceção e volta para `State.Idle` — comportamento **correto** (rede instável não pode virar popup a cada abertura do app), mas que transformou uma configuração quebrada em silêncio absoluto por meses. Nada no logcat de produção, nenhuma reclamação óbvia: o app simplesmente nunca falava de atualização.

**Por que o endereço ficou órfão.** O `build-and-upload.ps1` publicava os APKs no R2 e, no fim da execução, **imprimia** um JSON no terminal com o rótulo *"JSON para /app_version"* — para ser colado à mão numa rota do pythonanywhere. Passo manual, sem verificação, feito por ninguém. O APK subia para o R2 (e está lá, servindo 200), mas o anúncio da versão jamais era atualizado em lugar nenhum.

## Correção

Anúncio virou **arquivo estático publicado ao lado do APK**, na mesma pasta do R2 que já serve os binários — a mesma lógica que já roda em produção no ARMSX2 (`rgs/ps2/version.json`). Não há mais passo manual entre publicar e os usuários serem avisados.

**Publicação — [`build-and-upload.ps1`](../../../build-and-upload.ps1):**

- Novo passo 4/4: gera o `version.json` a partir dos APKs recém-publicados (`versionCode`, `versionName`, `channel`, URL/SHA-256/tamanho por ABI) e envia para `RetroGameSystem/version.json`.
- É a **última escrita** da publicação, depois da verificação pela URL pública dos APKs: nenhum cliente vê "versão nova" apontando para bytes que ainda não estão sendo servidos corretamente.
- Purga do cache de borda para o JSON + releitura pela URL pública conferindo `versionCode` e `sha256` — se o anúncio publicado não bate com o que foi buildado, o script falha.
- Sidecars `.sha256` por ABI.
- O trecho que imprimia o JSON para colar à mão saiu.

**App — [`build.gradle.kts`](../../../lemuroid-app/build.gradle.kts):**

- `appUpdateBaseUrl` passa a ser o **diretório** `https://versions.digitalstoregames.com/RetroGameSystem`; o endpoint é `<base>/version.json`, ou `<base>/version-<canal>.json` quando o canal não é `default`.

**App — [`AppUpdateManager.kt`](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/updates/AppUpdateManager.kt):**

- Lê `apkUrls`/`apkSha256`/`apkSizes` por ABI (mapas paralelos, para não quebrar o parser já instalado em campo, que lê `apkUrls[abi]` como string).
- Confere **tamanho e SHA-256** antes de entregar o arquivo ao instalador; hash errado apaga tudo e recomeça.
- Download com resume via header `Range` (`.part`) e até 3 tentativas.
- `canInstallPackages()` — checa "instalar apps desconhecidos" **antes** de baixar ~110 MB que o sistema recusaria instalar.
- Throttle de 12h e "Agora não" por `versionCode` em `app_update_prefs`.
- `Cache-Control: no-cache` na leitura do anúncio.

**UI:**

- Mobile: novo estado `PermissionRequired` com diálogo que leva à tela do sistema; "Agora não" registra o skip daquela versão.
- TV: [`TVAppUpdateDialog.kt`](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/shared/TVAppUpdateDialog.kt) — aviso ao abrir o `MainTVActivity` e item "Verificar atualizações" nas opções. A build **armeabi-v7a é distribuída justamente para Smart TV / TV box antiga**; sem isso esses aparelhos continuariam sem caminho de atualização.

## Validação

Validado ponta a ponta em aparelho real (moto g86 5G, arm64, Android 15) em 2026-08-14:

1. `curl` no endereço antigo → 404 confirmado; APK no R2 → 200 com 111 MB.
2. Publicada a **236** com o script novo: gate do `aapt` conferiu 237/236 dentro dos APKs, verificação pela URL pública bateu o SHA-256 dos dois ABIs, `version.json` publicado (990 bytes) e relido pela URL pública.
3. Instalada a 236 no aparelho → abre normal, **sem** diálogo (236 anunciado = 236 instalado).
4. Publicada a **237** e usado **Configurações → Verificar atualizações**:
   - diálogo **"Atualização 1.17.6 disponível"** ✅
   - **"Permissão necessária"** apareceu ANTES do download (o app não tinha "instalar apps desconhecidos") ✅ — o guard economizou 111 MB que o sistema recusaria instalar
   - permissão concedida → download com progresso até **100%** ✅
   - SHA-256 conferido sem erro → `PackageInstaller` ✅
   - app substituído **in-place**, sem prompt (Android 12+ honrou `USER_ACTION_NOT_REQUIRED` por ser o mesmo assinante) → `versionCode=237 / versionName=1.17.6` ✅
   - reaberto: home normal, sem oferecer atualização de novo ✅

**Gap conhecido, de uma vez só:** usuários com versionCode ≤ 235 têm o endpoint 404 gravado dentro do APK deles e **precisam de uma instalação manual uma única vez**. Da 236 em diante a atualização in-app se sustenta sozinha.

**Sobre o throttle:** a checagem automática de abertura roda no máximo 1x a cada 12h. No teste, o aparelho já tinha checado ao abrir a 236, então o diálogo não apareceu sozinho quando a 237 subiu — foi preciso o caminho manual pelas opções, que ignora o throttle de propósito. É o comportamento desenhado (e o mesmo do ARMSX2), mas vale saber: quem publica duas versões no mesmo dia não vê a segunda pela checagem automática.

## Lição

**Um caminho de atualização que depende de um passo manual não é um caminho de atualização.** O JSON impresso no terminal parecia integração — era um lembrete que ninguém leu, e o custo foi a base inteira presa numa versão antiga.

Duas regras que caem daqui:

1. **Quem publica o binário publica o anúncio, na mesma execução e no mesmo lugar.** Se o anúncio mora em outro serviço, ele diverge.
2. **Silêncio por design precisa de um teste explícito.** Engolir o erro da checagem automática é certo para o usuário e péssimo para o operador: a mesma linha que evita o popup escondeu a configuração quebrada. A verificação pela URL pública no passo 4 é o que substitui esse sinal perdido — agora a publicação falha alto quando o anúncio não está de pé.
