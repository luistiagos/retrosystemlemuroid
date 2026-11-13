# [BACKLOG] 3DS: redesenhar o layout de toque no padrão do emulador da imagem 1 (antiga T9)

**Status:** obsoleto — arquivado pelo dono em 2026-11-13 sem implementação; `Nintendo3DS.kt` segue com o radial padrão, verificado em 2026-11-13 (T29)

- **Data:** 2026-11-13
- **Origem:** T9 do bug `docs/bugs/retest/2026-11-07-3ds-sem-tela-unica-controles-em-coluna-e-imagem-deformada.md`. O dono decidiu fechar o bug sem ela (2026-11-13), porque o que levou ao reembolso já está corrigido: tela única, controles sobre o jogo, imagem preenchendo a tela e troca de telas.
- **Complexidade:** Média.

## O que é

Hoje o pad do 3DS (`lemuroid-touchinput/.../radial/layouts/Nintendo3DS.kt`) é o radial padrão do Lemuroid:

- **esquerda:** direcional no centro; L, Select, o botão de trocar telas (`SecondaryButtonSwapScreens`, da T3) e o analógico em arco ao redor;
- **direita:** ABXY no centro; R, Start e o menu ao redor.

Proposta, como na imagem 1 do bug:

- L e R nos cantos superiores;
- direcional e analógico à esquerda, ABXY à direita;
- Select e Start no centro, embaixo;
- o botão de trocar telas da T3 em algum lugar acessível.

## Ponto a decidir na análise

O 3DS usa a mesma base (`BaseLayoutLeft`/`BaseLayoutRight`) dos outros sistemas. Pôr L/R nos cantos e Select/Start no centro foge do arco radial, então há dois caminhos:

- dar ao 3DS um layout próprio;
- dar posições novas à base, o que mexe em todos os sistemas.

Também falta decidir se o botão de trocar telas some fora da tela única. Ver a "T3: análise" do bug, onde esconder o botão foi descartado por exigir estado de layout dentro do pad.

## Prova

Print lado a lado com a imagem 1, com os controles sem cobrir o centro da pista do MK7. A prova pode ser feita toda no moto g86, porque não depende de GPU.
