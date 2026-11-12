# [BACKLOG] 3DS: trocar o core pelo Azahar (OpenGL) e oferecer tela única esticada com controles sobre o jogo

**Data:** 2026-11-08
**Status:** em andamento. **T7 feita e publicada em 2026-11-12** (core Azahar 2126.2, tag `1.23.0`, migração de saves e prefs validada no moto). Faltam as provas em Adreno e de corrida jogada antes de um APK para clientes. **T2 feita em 2026-11-12** ("Tela única" no menu; `large_screen` medido e não exposto). T3–T6, T8 e T9 seguem abertas; as próximas são T4 + T5. Passagem de bastão no bug doc, seção "Próximo passo (passagem de bastão, 2026-11-12, depois da T2)".
**Origem:** chamado #330. Uma cliente com Galaxy S20 FE pediu reembolso porque o 3DS não tem tela única e a imagem sai deformada. Bug doc: [`docs/bugs/open/2026-11-07-3ds-sem-tela-unica-controles-em-coluna-e-imagem-deformada.md`](../bugs/open/2026-11-07-3ds-sem-tela-unica-controles-em-coluna-e-imagem-deformada.md).
**Tipo:** correção + troca de core · **Esforço:** grande (8 tasks; T7 é Alta)

> **O bug doc é a fonte da verdade.** As medições, os scripts de bancada, as hipóteses descartadas e a especificação completa de cada task estão lá, na seção "Tasks abertas (2026-11-08)". Este backlog resume o que foi decidido e por quê, para o item não se perder na fila.

## Contexto

A cliente queria jogar *Mario Kart 7* e *Super Mario 3D Land* como no print de outro emulador:

- só a tela de cima;
- ocupando o celular inteiro;
- controles por cima da imagem.

No nosso app há três bloqueios:

- só existem os layouts "Superior–inferior" e "Esquerda–direita";
- os controles ocupam duas colunas laterais;
- com `Esticar para preencher`, a imagem sai deformada.

## Decisões do dono (2026-11-08)

1. **Tela única abre esticada, sem bordas, como o print da cliente** (Single Screen + Stretch). Não é 5:3 com barras. Numa tela 20:9 não dá para ter ao mesmo tempo "sem bordas" e "sem distorção", e o print da cliente é distorcido (1,34× no S20 FE).
2. **Trocar o core 3DS `citra` pelo libretro do Azahar 2126.2, em OpenGL**, pelo desempenho medido.
3. **Sem Vulkan no frontend.**
4. Tela única é **opção**; o padrão continua `Default Top-Bottom Screen`. Decisão de 2026-11-07.

## O que foi provado (moto g86 5G, Mali-G615, 2026-11-07)

| Fato | Evidência |
|---|---|
| O print da cliente é **Single Screen + Stretch** de um app da família Azahar | Azahar standalone no moto: Stretch dá o logo do MK7 em 6,69; o print 1 dá 6,72 |
| A deformação dos nossos prints é a preferência global `Esticar para preencher` aplicada à coluna entre os pads. O core não deforma nada | Tabela de geometria do bug doc; com `Automática`, o core mostra 5:3 exato |
| O core atual já faz Single Screen (400×240); o app é que não oferece a opção | `cv_3ds_citra_layout_option=Single Screen Only` via prefs Harmony; `GameSystem.kt` expõe só dois valores |
| Os controles em coluna vêm de `ControllerConfigs.NINTENDO_3DS` com `allowTouchOverlay = false` | `ControllerConfigs.kt:383` → `GameScreenLayout.buildConstraintSetLandscapeNoOverlay` (`:123`) |
| **O Azahar GL no nosso app roda o MK7 a 57,2 fps de apresentação e 54,1 de core. O atual roda a 41,7–44,9 e 35,9–40,6**, com 0 crash em 5 min. Quadros > 20 ms: 1,2% contra 5,6–7,8% | `braco.sh`, braços A1/B/A2 intercalados; repetição A1/A2 com ruído de 7% |
| As quedas do Azahar são pontuais (um bloco de ~3 s). As do core atual se prolongam por quase 1 min a 17–37 fps | fps por bloco de 180 quadros (`dist.py`) |
| **Sem `citra_graphics_api=OpenGL`, o Azahar quebra na hora no nosso app.** O padrão em Android é Vulkan, e o LibretroDroid só fala GLES | 1ª rodada do braço B: `Using Vulkan hw renderer` → SIGSEGV no `context_reset` |
| No Mali, Vulkan não ganha: o Azahar standalone em OpenGL deu 62,4 fps e em Vulkan 58,9 | braços C × C2, renderer confirmado no log do Azahar |
| A troca de core **não muda a imagem**: o mesmo quadro sai idêntico pixel a pixel nos dois cores (1x nativo). Os dois reportam a mesma geometria | menu do MK7 no A1 × B, diferença média 0,00 |
| O LibretroDroid não tem nenhum caminho Vulkan: `environment_handle_set_hw_render` sempre entrega `eglGetProcAddress` | `LibretroDroid` `environment.cpp:152` |

**Não medido ainda:** aparelho Adreno, corrida jogada (só o demo), sessão longa (aquecimento), resolução 2x e compatibilidade dos saves do core atual com o Azahar.

## Tasks (ordem sugerida)

Especificação completa (o que entra, o que não entra, símbolos e prova) no bug doc, seção "Tasks abertas (2026-11-08)".

| # | Task | Complexidade | Depende de |
|---|---|---|---|
| T7 ✅ (2026-11-12) | Trocar o core pelo Azahar 2126.2. `citra_graphics_api=OpenGL` em `defaultSettings`, migrar as chaves e o `CoreCrashFallback`, publicar no `lemuroid-cores` (pitfall 15: commit, push, tag e `CORES_VERSION`) | Alta | — |
| T2 ✅ (2026-11-12) | Expor `single_screen` em `citra_layout_option`, com pt-BR (`large_screen` medido e não exposto) | Baixa | T7 (mesmo bloco de `GameSystem.kt`) |
| T4 | Controles sobre o jogo na tela única; os outros layouts mantêm as colunas | Média | — |
| T5 | Tela única esticada sem bordas (FILL), sem mudar a proporção dos outros sistemas | Média | T2, T4 |
| T6 | Top-Bottom e Side by Side sem deformação com `Esticar` | Média | T5 |
| T3 | Botão de troca de tela, para jogos que exigem toque na tela de baixo. O Azahar usa L3 (`Toggle`); alternativa: `citra_swap_screen` no menu | Média | T7 |
| T9 | Layout de toque como o do print: L/R nos cantos, direcional e analógico à esquerda, ABXY à direita, Select/Start no centro | Média | T4 |
| T8 | Resolução 2x por padrão, se o Azahar sustentar ~55 fps | Baixa / Média | T7 |

## Riscos que bloqueiam publicar a T7

- **Saves de quem já joga 3DS.** O Azahar usa `<files>/save` como pasta de usuário. Se save e extdata do core atual não abrirem nele, a troca apaga o progresso. Testar com um save de antes da troca.
- **Driver.** Só foi medido em Mali. Repetir o A/B num Adreno.
- **Crash de shader** já registrado em `docs/bugs/open/2026-09-02-crashes-nativos-cores-citra-dolphin.md`: conferir com o Azahar.
- O `.so` usado na medição foi **patchado à mão** só para forçar OpenGL sem editar o app. **Nunca publicar esse binário**; publicar o da release.

## Fora do escopo

- Vulkan no LibretroDroid (decisão do dono). Uma ponte Vulkan → `AHardwareBuffer` → textura GL é viável, mas custa semanas, e no Mali não houve ganho. Reavaliar só se um Adreno mostrar diferença grande.
- Mudar a proporção dos outros sistemas.
- O gatilho do print 3 (split screen e janela flutuante não testados), que fica no bug doc.
