# [BACKLOG] Estudo: Pegasus Frontend como interface do Lemuroid

**Data:** 2026-10-13
**Status:** estudo concluído. O dono escolheu o caminho C (redesenho nativo), planejado em
[`2026-10-13-redesenho-visual-inspirado-pegasus.md`](2026-10-13-redesenho-visual-inspirado-pegasus.md).
Os caminhos A e B não serão feitos agora.
**Origem:** pedido do dono para avaliar a troca do frontend do Lemuroid pelo
[Pegasus Frontend](https://github.com/mmatyas/pegasus-frontend), de visual mais moderno, sem alterar
nenhuma funcionalidade nem o core. Nenhum código foi alterado por este estudo.

## Veredito

**Não dá para trocar a interface do Lemuroid pelo Pegasus e manter todas as funcionalidades.** O Pegasus não
é uma biblioteca de UI nem um "skin". É um app separado (C++ / Qt 5.15 / QML) que só faz duas coisas: listar
jogos descritos em arquivos de texto e abrir outro app com `am start`. Há quatro bloqueios:

1. **O tema não tem como chamar o Lemuroid.** A API de tema só lê jogos e coleções. O único campo gravável é
   `favorite` e o único verbo é `game.launch()`. Download sob demanda, fila, variantes, excluir/restaurar
   catálogo, configurações, opções de core, controles e save sync não cabem no Pegasus. Todos continuariam
   em telas do Lemuroid.
2. **O catálogo do Lemuroid não existe em disco.** São 61.040 jogos, e os não baixados existem só no banco.
   O Pegasus esconde jogo sem arquivo por padrão. No Android 11+ ele também não enxerga
   `Android/data/<pkg>/files/roms/`, a pasta onde ficam os jogos baixados.
3. **O desempenho no aparelho-alvo é um risco.** Há issues abertas sobre lentidão no Android a partir de
   ~200–300 jogos por coleção (#937) e sobre mais de 5 min para abrir 31 mil ROMs (#839, relato de 2022). O
   Lemuroid tem 10.400 jogos de ZX Spectrum, 5.492 de Atari 800 e 3.490 de SNES, e roda em TV box de 1–2 GB.
4. **O estado ficaria duplicado.** Favoritos, recentes e tempo de jogo do Pegasus ficam no banco dele
   (`stats.db`), separados do Room do Lemuroid.

**Recomendação:** para o objetivo declarado (visual moderno), redesenhar as telas do Lemuroid inspirado nos
temas do Pegasus (caminho C). Usar o Pegasus como lançador externo opcional (caminho A) é possível, mas exige
mudanças no Lemuroid e não substitui a UI dele. Embutir o Pegasus no APK (caminho B) não compensa.

## 1. Pegasus: fatos verificados em 13/10/2026

| Item | Fato | Fonte |
|---|---|---|
| Tecnologia | C++ e Qt **5.15**. O build Android usa Qt 5.15.10, NDK r21e, `android-30` e ABIs armeabi-v7a/arm64-v8a. Não há nenhum build com Qt 6. | `.github/workflows/android.yml`, `x11.yml` |
| Estado do projeto | Versão ainda "alpha 16", de 28/03/2022. O build contínuo mais recente é de 15/09/2026. Houve 15 commits entre mar. e out./2026 e há 150 issues/PRs abertas. | API do GitHub |
| Licença | GPLv3+, compatível com o `COPYING` (GPLv3) do Lemuroid. Pela seção 7, uma versão modificada **não pode usar o nome nem o logo "Pegasus"**. | `LICENSE.md` |
| APK | 15,9 MB (armv7) e 18,2 MB (arm64). Distribuído só como APK (sideload). | release `continuous` |
| Android | Exige Android 5.0 ou superior. O manifesto pede `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES` e as categorias HOME e LEANBACK_LAUNCHER. No Android 11+ exige acesso a todos os arquivos. | `src/app/platform/android/AndroidManifest.xml`, `src/backend/platform/AndroidHelpers.cpp` |
| Fonte dos jogos | `metadata.pegasus.txt` nas pastas de jogos ou em `<config>/metafiles`. Aceita campos `x-`. Quando um jogo tem vários arquivos, mostra um seletor ao lançar. | doc "Metadata files" |
| Jogo sem arquivo | Com `verify_files=true` e `show_missing_games=false` (os padrões), o arquivo inexistente é descartado e o jogo some. | `PegasusMetadata.cpp::apply_game_entry`, `AppSettings.h` |
| Capas | Aceita URLs `http://`/`https://` nos assets. | `PegasusMetadata.cpp::assetline_to_url` |
| Lançamento | Só por comando de lançamento. O `am start …` vira uma Intent via JNI (`MainActivity.launchAmCommand`). Sem comando, o lançamento dá erro. Placeholders: `{file.path}`, `{file.uri}` (content URI), `{file.name}`, `{file.basename}`, `{file.dir}`. | `src/backend/ProcessLauncher.cpp`, `AndroidHelpers.cpp` |
| API de tema | `api.collections`, `api.allGames`, `api.keys`, `api.memory`, `api.device`. Só `favorite` é gravável e só existe `launch()`. Ao voltar do jogo, o tema é recarregado. | doc "API reference" |
| Desempenho | #937 (aberta): lentidão a partir de ~200–300 itens por coleção; o tema GameOS foi descrito como "inutilizável" num RK3399 de 4 GB com Android 7.1. #839 (aberta): a carga é limitada por I/O (verifica cada arquivo e varre `media/`), com 31 mil ROMs levando mais de 5 min no Android. Desde jul./2026 há cache de metadados entre varreduras (846c2d5), sem medição no Android. | issues #937 e #839 |

## 2. Lemuroid: o que a UI faz hoje

- **Catálogo** (`lemuroid-app/src/main/assets/catalog_manifest.txt`): 61.040 linhas, das quais 45.780 são
  representantes e 37.587 têm capa. São 73 sistemas. Os maiores: zxspectrum (10.400), atari800 (5.492),
  amstradcpc (4.231) e snes (3.490).
- **Origem das capas:** 15.062 do IGDB, 13.807 do libretro-thumbnails (`Named_Boxarts`), 5.565 do HfsPlay e
  3.153 do ArcadeItalia.
- **Camada de UI:**
  - mobile em Compose: 75 arquivos `.kt`, 10,9 mil linhas. Telas Home, Games, MetaSystem, Favorites,
    Search, Installed, 13 telas de Settings, `GameVariantsModal` e `SaveQueueModal`.
  - TV em Leanback: 43 arquivos, 3,7 mil linhas. TVHome, TVGames, TVFavorites, TVSearch, TVSettings,
    seletor de pasta e canais da Android TV.
- **Fica no Lemuroid em qualquer cenário** (o "core" não muda): `GameActivity`/`TVGameActivity` (processo
  `:game`), o menu do jogo, o pad virtual e os saves.
- **Entrada externa existente:** `retrogamesystem://${applicationId}/play-game/id/<gameId>`, tratada pela
  activity exportada `ExternalGameLauncherActivity` (`lemuroid-app/src/main/AndroidManifest.xml:70-85`).
  - `ExternalGameLauncherActivity.kt:63` aceita só o id numérico, e o `gameId` muda entre instalações.
  - `loadGame` (`:93-108`) chama `GameLauncher.launchGameAsync`. Quando o arquivo não existe,
    `GameLauncher.kt:37-40` mostra **"ROM não encontrada" com um botão "Reescanear"** (`:69-83`).
  - O fluxo de download (`GameInteractor.onGamePlay` → `onPlaceholderGame`, `GameInteractor.kt:41-48`) só é
    ligado pela `MainActivity` e pela `MainTVActivity`. **Um jogo não baixado aberto de fora nunca baixa.**

## 3. Lacunas: Pegasus × Lemuroid

| Funcionalidade do Lemuroid | Como fica no Pegasus |
|---|---|
| Catálogo não baixado | Some por padrão. Só aparece se o usuário desligar `verify_files` ou ligar `show_missing_games`. |
| Saber o que já está baixado | Não é possível: no Android 11+ o Pegasus não vê `Android/data`. Só com um campo `x-` e um tema customizado. |
| Download sob demanda, fila, progresso, pausa | Não existe. O deep link atual cai em "ROM não encontrada". |
| Variantes (modal) | Parcial: `files:` múltiplos com o seletor nativo. Como `{file.path}` não carrega o `gameId`, exige um deep link novo. |
| Ordenação por popularidade e A-Z | Parcial: `sort_by` e `x-popularity`, mas depende do tema. |
| Descubra, recentes, favoritos | Ficam os do Pegasus, sem sincronizar com o Room. |
| Busca FTS | Depende do tema (filtro em memória). |
| Filtro por RAM (`HeavySystemFilter`) | Não existe. Teria de ser aplicado na exportação. |
| Excluir ROM, excluir do catálogo, restaurar | Não existe. |
| Configurações (cores, BIOS, controles, save sync, transferência, romset, atualização) | Não existe. Continuam no Lemuroid. |
| Toque no celular | Depende do tema. A maioria é feita para controle. |
| Canais da Android TV, atalhos fixados | Continuam no Lemuroid. |

## 4. Caminhos avaliados

### A. Pegasus como lançador externo opcional (dois apps)
O que mudaria no Lemuroid. As mudanças são aditivas, mas mexem em código:
1. **Exportador** de `metadata.pegasus.txt` por sistema, gerado a partir do Room. Cada jogo leva título, URL
   da capa, `sort_by`, `x-popularity`, `x-downloaded` e um `launch: am start -a android.intent.action.VIEW -d
   retrogamesystem://<pkg>/play-game/id/<id>`. O arquivo é regravado quando o catálogo muda (quick-load,
   download, exclusão ou restauração) e fica em pasta compartilhada, porque o Pegasus não lê `Android/data`.
2. **`ExternalGameLauncherActivity`**: quando o jogo é placeholder, passar a confirmar e baixar, em vez de
   mostrar "ROM não encontrada". **Isso muda também os atalhos fixados**, que usam o mesmo deep link.
3. Para as variantes, um deep link por `systemId/fileName`.

O que o usuário teria de fazer: instalar o APK do Pegasus fora da Play, conceder acesso a todos os arquivos,
apontar a pasta exportada e ajustar `verify_files`.
Riscos: o desempenho da seção 1, o estado duplicado e a experiência dividida entre dois apps.
Esforço médio. Serve como opção para entusiastas, não como nova UI.

### B. Embutir o Pegasus no APK do Lemuroid (não recomendado)
- **Toolchain:** o Qt 5.15 saiu do suporte padrão em 26/05/2025, e o build usa NDK r21e com o projeto
  Gradle próprio do `androiddeployqt`. O Lemuroid usa AGP 8.7.1, compileSdk 35 e targetSdk 35.
- **Exigência da Play:** apps com targetSdk 35+ precisam suportar páginas de 16 KB. A página oficial bloqueia
  updates sem esse suporte a partir de 01/02/2027. As `.so` do Qt 5.15 feitas com NDK r21e não estão
  preparadas, então seria preciso recompilar o Qt inteiro.
- **Tamanho:** +16–18 MB por ABI.
- **Marca:** a versão modificada não poderia se chamar "Pegasus".
- **Resultado:** continuaria precisando de A.1–A.3, mais uma ponte JNI Qt↔Kotlin. A UI ficaria híbrida
  (Pegasus para navegar, Compose/Leanback para o resto), com dois motores de UI e um runtime C++ novo
  testado contra os pitfalls de Android 5–7 e TV box.
- Esforço muito alto.

### C. Redesenho nativo inspirado nos temas do Pegasus (recomendado e escolhido)
Reproduzir a linguagem visual dos temas (fundo com arte do jogo em foco, fileiras horizontais, identidade
por sistema) trocando só a camada de apresentação. ViewModels, DAOs, download, fila e variantes ficam
intactos. O plano completo está em
[`2026-10-13-redesenho-visual-inspirado-pegasus.md`](2026-10-13-redesenho-visual-inspirado-pegasus.md).

## 5. Hipóteses descartadas
- **"Um URI `retrogamesystem://…` no campo `file:` dispensa o arquivo."** Descartada: `{file.path}` vem de
  `QFileInfo::absoluteFilePath()` em `ProcessLauncher.cpp`, então o URI vira caminho relativo (PR #1177).
- **"O tema pode chamar o app hospedeiro."** Descartada: a API não tem verbo além de `launch()`.
- **"As capas precisariam estar em disco."** Descartada: o Pegasus aceita `http(s)`.
- **"O Pegasus sempre esconde jogo sem arquivo."** Corrigida: há `show_missing_games` e `verify_files`.
- **"O deep link atual já lança um jogo não baixado."** Descartada: cai em "ROM não encontrada".

## 6. Como os fatos foram obtidos

**Pegasus.** Sem clonar o repositório: arquivos lidos por `raw.githubusercontent.com`, metadados pela API
pública do GitHub (`/repos/mmatyas/pegasus-frontend`, `/commits`, `/releases`) e a documentação em
`pegasus-frontend.org/docs`.

**Lemuroid.** Arquivos abertos: `ExternalGameLauncherActivity.kt`, `GameLauncher.kt`, `GameInteractor.kt` e
`AndroidManifest.xml`. Contagens, a partir da raiz do repo:

| Comando | Resultado |
|---|---|
| `wc -l < lemuroid-app/src/main/assets/catalog_manifest.txt` | 61.040 |
| `awk -F'\|' '$5=="1"' <manifest> \| wc -l` | 45.780 representantes |
| `awk -F'\|' '$3!=""' <manifest> \| wc -l` | 37.587 com capa |
| `cut -d'/' -f1 <manifest> \| sort -u \| wc -l` | 73 sistemas |
| `cut -d'/' -f1 <manifest> \| sort \| uniq -c \| sort -rn \| head` | zxspectrum 10.400, atari800 5.492, amstradcpc 4.231, snes 3.490, nds 2.670, psx 2.571, nes 2.457, gba 2.440 |
| `awk -F'\|' '$3!=""{split($3,a,"/"); print a[3]}' <manifest> \| sort \| uniq -c` | images.igdb.com 15.062, raw.githubusercontent.com 13.807, db.hfsplay.fr 5.565, adb.arcadeitalia.net 3.153 |
| `find mobile -name '*.kt' \| wc -l` e `… -exec cat {} + \| wc -l` (em `…/lemuroid/app`) | mobile 75 arquivos / 10.924 linhas; tv 43 / 3.691; shared 83 / 12.496 |

## 7. Se o caminho A voltar à mesa: PoC sem alterar o app

Serve para medir o desempenho real no hardware-alvo antes de escrever qualquer código:
1. Instalar o APK contínuo do Pegasus no AVD `lemu_tv_api25` e/ou no SM-A127M.
2. Gerar, fora do repo, um `metadata.pegasus.txt` a partir do banco do aparelho, com os jogos baixados mais
   5–10 mil placeholders e `launch:` pelo deep link atual.
3. Medir o tempo até o tema aparecer, a rolagem com capas remotas, a abertura de um jogo baixado e o
   "ROM não encontrada" num placeholder.

## Fontes
- Repositório: https://github.com/mmatyas/pegasus-frontend. Arquivos: `LICENSE.md`,
  `.github/workflows/android.yml`, `src/backend/providers/pegasus_metadata/PegasusMetadata.cpp`,
  `src/backend/ProcessLauncher.cpp`, `src/backend/platform/AndroidHelpers.cpp`, `src/backend/AppSettings.h`,
  `src/app/platform/android/AndroidManifest.xml`
- Docs: https://pegasus-frontend.org/docs/user-guide/meta-files/ ·
  https://pegasus-frontend.org/docs/user-guide/platform-android/ · https://pegasus-frontend.org/docs/themes/api/
- Issues/PRs: https://github.com/mmatyas/pegasus-frontend/issues/937 ·
  https://github.com/mmatyas/pegasus-frontend/issues/839 · https://github.com/mmatyas/pegasus-frontend/pull/1177
- Lista de temas: https://github.com/mmatyas/pegasus-theme-gallery-db (`repos.txt`)
- Qt 5.15 fora de suporte: https://qt.io/blog/extended-security-maintenance-for-qt-5.15-begins-may-2025
- Páginas de 16 KB: https://developer.android.com/guide/practices/page-sizes
