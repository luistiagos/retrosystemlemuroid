---
criado_em: 2026-11-25
aprovado_por: Luis Tiago - 2026-11-25 21:50 - "pode seguir"
verificacao: |
  Na versão publicada instalada no moto g86 (e no AVD de TV `lemu_tv_api25` para a navegação por controle):
  abrir um jogo de cada um dos cinco sistemas (PS1, GameCube, Nintendo DS, Saturn, 3DO), menu do jogo ▸
  Opções do core ▸ "Resolução interna" aparece com "1x (nativa)" marcada; escolher 2x, voltar ao jogo
  (reiniciando se o título pedir) e comparar print antes/depois: bordas de polígono visivelmente mais finas.
  No N64, as opções aparecem como "Resolução interna (4:3)" / "(16:9)" com valores "1x (nativa)", "2x"…, e
  quem já tinha escolhido um tamanho antes de atualizar continua com a mesma resolução.
---

# [FEATURE] Resolução interna nos sistemas 3D que ainda não têm, e nome claro no N64

## Problema

Aumentar a resolução interna (o core redesenha a cena 3D com mais pixels, deixando a imagem mais nítida) só
existe hoje no menu do jogo de PSP, 3DS e Dreamcast/Naomi/Atomiswave. No N64 a opção existe, mas se chama
"Tamanho da tela 4:3 / 16:9" e mostra valores como `640x480`, que não deixam claro que é resolução.

Cinco sistemas 3D cujo core empacotado já suporta resolução interna não a oferecem ao usuário:

| Sistema | Hoje | Suporte no core (chave conferida no binário arm64 em 2026-11-25) |
|---|---|---|
| PS1 | opção escondida, sempre nativa | só dobra (2x), e só em CPU ARM — todo aparelho real |
| GameCube | nenhuma opção do core aparece no menu do jogo | escala da resolução interna |
| Nintendo DS | escondida, sempre nativa, no core padrão | resolução interna em múltiplos de 256x192; o core alternativo não tem |
| Saturn | fixada na original por cautela de memória (não por defeito) | original, 2x, 4x, 720p, 1080p, 4k |
| 3DO | fixada em desligada, sem motivo registrado | liga/desliga (dobra para 640x480, calculado na CPU) |

Fora do alcance (o core não suporta; conferido no binário): Jaguar, 32X, PC-FX, DOS. Sistemas 2D não ganham
detalhe com resolução interna; para eles já existe o Modo HD.

## Visão do usuário de TV box

1. **Só com controle remoto/gamepad?** Sim. A opção entra na mesma lista de Opções do core do menu do jogo, que
   já é navegada por D-pad com foco visível na TV (Leanback) e no celular. Nenhum passo novo de toque.
2. **Cabe em TV 720p e celular 720x1280?** Títulos curtos ("Resolução interna", "Resolução interna (4:3)") e
   valores curtos ("1x (nativa)", "2x", "3x"…), do mesmo tamanho dos de Dreamcast e PSP que já cabem.
3. **Android 5 com 1 GB?** O padrão continua a resolução nativa, então nada muda para quem não mexer. Quem
   escolher 2x ou mais num aparelho fraco pode ver o jogo ficar lento ou, no Saturn e no GameCube, faltar
   memória — exatamente como já acontece hoje no PSP e no Dreamcast. Por decisão do dono, nenhum valor é escondido
   por aparelho: o padrão nativo é a proteção.
4. **pt-BR e inglês?** Todo título e valor novo nos dois idiomas.

## Decisões do dono

Tomadas em 2026-11-25 ("1 - Não teremos teto, 2 - Mostrar tudo, 3 - Corrije junto"):

1. **Sem teto.** Cada opção oferece todos os valores que o core declara (Saturn até 4k, N64 até 3840x2880, DS
   até 10x).
2. **Mostrar tudo em todo aparelho**, inclusive no tier `ULTRA_WEAK`. A escolha é do usuário e o padrão é nativo.
3. **Dreamcast em pt-BR corrigido junto:** os 4 valores da resolução do Dreamcast ("Native (640x480)",
   "2x (1280x960)"…) ganham tradução.

## Critérios de aceite

EARS-1. QUANDO o usuário abre Opções do core no menu de um jogo de um dos sistemas abaixo, O SISTEMA DEVE mostrar
a opção "Resolução interna".
*conjunto: sistemas que ganham a opção (5)*
- PS1: teste lemuroid-app/src/test/java/com/swordfish/lemuroid/app/shared/game/GameSystemResolutionSettingTest.kt::psxExpoeResolucao, e2e evidencias/aceitacao.md::psx-opcao-visivel
- GameCube: teste ...GameSystemResolutionSettingTest.kt::gamecubeExpoeResolucao, e2e evidencias/aceitacao.md::gc-opcao-visivel
- Nintendo DS (core padrão): teste ...GameSystemResolutionSettingTest.kt::ndsExpoeResolucao, e2e evidencias/aceitacao.md::nds-opcao-visivel
- Saturn: teste ...GameSystemResolutionSettingTest.kt::saturnExpoeResolucao, e2e evidencias/aceitacao.md::saturn-opcao-visivel
- 3DO: teste ...GameSystemResolutionSettingTest.kt::operaExpoeResolucao, e2e evidencias/aceitacao.md::3do-opcao-visivel

EARS-2. ENQUANTO o usuário não escolheu outra resolução, O SISTEMA DEVE rodar os cinco sistemas na resolução
nativa, a mesma de antes da feature.
*prova: teste ...GameSystemResolutionSettingTest.kt::padraoNativoNosCinco*

EARS-3. QUANDO o usuário escolhe 2x num dos cinco sistemas e volta ao jogo, O SISTEMA DEVE desenhar o jogo na
resolução escolhida.
*conjunto: sistemas (5)*
- PS1: e2e evidencias/aceitacao.md::psx-2x-print-antes-depois
- GameCube: e2e evidencias/aceitacao.md::gc-2x-print-antes-depois
- Nintendo DS: e2e evidencias/aceitacao.md::nds-2x-print-antes-depois
- Saturn: e2e evidencias/aceitacao.md::saturn-2x-print-antes-depois
- 3DO: e2e evidencias/aceitacao.md::3do-2x-print-antes-depois

EARS-4. O SISTEMA DEVE oferecer em cada opção de resolução todos os valores que o core daquele sistema declara, e
somente eles, de modo que nenhum valor oferecido seja descartado em silêncio e a opção nunca suma do menu.
*prova: teste ...GameSystemResolutionSettingTest.kt::valoresContidosNosDeclaradosPeloCore (lista declarada
capturada do core em execução e congelada no teste), e2e evidencias/aceitacao.md::contagem-de-valores-por-sistema*

EARS-5. SE o core de um dos cinco sistemas só aplica a nova resolução ao reiniciar o jogo, O SISTEMA DEVE indicar
"(reiniciar)" no título da opção, como as opções do N64 que já fazem isso.
*prova: e2e evidencias/aceitacao.md::aplica-na-hora-ou-reiniciar (por sistema, o que o core fez ao trocar o valor)*

EARS-6. QUANDO o usuário abre Opções do core num jogo de N64, O SISTEMA DEVE mostrar "Resolução interna (4:3)" e
"Resolução interna (16:9)" no lugar de "Tamanho da tela 4:3" e "Tamanho da tela 16:9".
*prova: e2e evidencias/aceitacao.md::n64-titulos*

EARS-7. QUANDO o usuário abre a lista de valores da resolução do N64, O SISTEMA DEVE rotular cada valor como
múltiplo da nativa ("1x (nativa)", "2x", "3x"…).
*prova: teste ...GameSystemResolutionSettingTest.kt::n64ValoresRotulados, e2e evidencias/aceitacao.md::n64-valores*

EARS-8. SE o usuário escolheu um tamanho de tela no N64 antes de atualizar, O SISTEMA DEVE manter a mesma
resolução depois da atualização, sem pedir nova escolha.
*prova: teste ...GameSystemResolutionSettingTest.kt::n64ChavesGravadasInalteradas, e2e evidencias/aceitacao.md::n64-escolha-sobrevive-update*

EARS-9. QUANDO o usuário de TV abre Opções do core pelo controle, O SISTEMA DEVE permitir escolher a resolução
interna só com D-pad e OK, com foco visível em cada item.
*prova: e2e evidencias/aceitacao.md::tv-dpad-resolucao (AVD `lemu_tv_api25`)*

EARS-10. ENQUANTO o app está em português, O SISTEMA DEVE mostrar em português os valores da resolução interna do
Dreamcast, Naomi e Atomiswave ("Nativa (640x480)", "2x (1280x960)"…).
*prova: teste ...GameSystemResolutionSettingTest.kt::rotulosDeResolucaoTraduzidosPtBr, e2e evidencias/aceitacao.md::dreamcast-valores-pt-br*

## Superfícies

| superfície | decisão | onde |
|---|---|---|
| UI mobile (Compose) | muda: opção nova na lista de Opções do core do menu do jogo; nenhum componente novo | EARS-1, EARS-6, EARS-7 |
| UI TV (Leanback/foco) | muda: a mesma lista, mesmo mecanismo de foco existente | EARS-9 |
| processo `:game` | muda: o core recebe a variável de resolução escolhida; padrão nativo | EARS-2, EARS-3, EARS-5 |
| catálogo e banco | n/a — nenhuma linha, coluna ou manifest muda | — |
| download de ROM/BIOS | n/a — não toca em download | — |
| Configurações e preferências | muda: valor escolhido guardado como as demais opções de core; chaves do N64 preservadas | EARS-8 |
| telemetria | n/a — nenhum evento novo; crash de core segue o caminho existente | — |
| cores/LibretroDroid | n/a — só se usa chave que o core empacotado já declara; nenhum `.so` muda | EARS-4 |
| publicação | muda: chega na próxima versão publicada pelo dono (`build-and-upload.ps1`) | verificação |

## Fora de escopo

- Jaguar, 32X, PC-FX e DOS (o core não tem a opção) e todo sistema 2D.
- melonDS (core alternativo do DS): não tem a opção.
- Mudar o padrão de qualquer sistema para acima de nativo.
- Opção global de resolução em Ajustes: a escolha continua por sistema, no menu do jogo.
- Teto de resolução e esconder valores ou sistemas por tier de RAM (decisões 1 e 2 do dono).
