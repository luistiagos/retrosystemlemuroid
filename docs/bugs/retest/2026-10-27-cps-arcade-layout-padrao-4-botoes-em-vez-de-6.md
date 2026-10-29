# [BUG] Jogos de luta da Capcom (CPS-1, CPS-2 e CPS-3) iniciam com layout touch de 4 botões em vez de 6

- **Data:** 2026-10-27
- **Status:** resolvido (validado end-to-end no emulador Android x86_64)
- **Severidade:** Média (afeta a jogabilidade de todos os jogos de luta de CPS-1, CPS-2 e CPS-3 que exigem 6 botões: Marvel vs Capcom, Street Fighter, Darkstalkers)
- **Complexidade:** Baixa (reordenação de `ControllerConfigs` nas declarações de sistemas Capcom)
- **Branch:** redesenho-tv-jogos-salvos-tela
- **Origem:** chamado de suporte WhatsApp (+55 31 9452-3223 / `197637248663793@lid`, cliente Leandro, produto Sistema Multigames)

## Status

- **Corrigido:** commit `240196f` (2026-10-29 12:14, a correcao foi feita em outra sessao em 27-28/10).
- **Onde:** `GameSystem.kt::SYSTEMS` (`controllerConfigs` de CPS1/2/3, FBNeo, Data East, Genesis e Sega CD): o
  layout de 6 botoes passa a vir antes do de 4 (ou de 3, no Mega Drive), e a primeira entrada e o padrao.
- **T2:** AVD `lemu_api25_2gb` (x86_64), Marvel Super Heroes (CPS-2/FBNeo) abriu em `ARCADE_6` e foi jogado ate a
  partida. Detalhes na secao "Validacao End-to-End". Sem controle positivo registrado na base (build anterior) e sem
  aparelho fisico.
- **T3/T4:** nao registrados no doc.
- **Falta para `done/`:** versao publicada (dono); conferir que o layout salvo pelo usuario nao e sobrescrito
  (a mudanca so afeta o padrao); e a prova do cliente (chamado do Leandro).

---

## Sintoma

Ao abrir jogos de arcade das placas Capcom (como *Marvel vs. Capcom: Clash of Super Heroes*, *Marvel Super Heroes*, *Street Fighter Alpha 1/2/3*, *Super Street Fighter II Turbo*, *Street Fighter III*), os controles na tela exibiam apenas 4 botões de ação (A, B, X, Y), impossibilitando o uso de socos fortes, chutes fortes, assistências e golpes especiais/supers que requerem as combinações dos 6 botões de arcade.

No atendimento de suporte, o cliente relatou:
> *"No Primeiro deu para jogar normal embora o console tenha só 4 botões para jogar e no fliperama tinham 6 botões, e quando vc tenta dá o golpe especial não dá pq o console tinha que ter 3 botões na parte de cima e de baixo."*

## Evidência

1. Em `GameSystem.kt`, nos blocos `SystemID.CPS1`, `SystemID.CPS2` e `SystemID.CPS3`, as configurações de controle estavam declaradas com `ControllerConfigs.FB_NEO_4` antes de `ControllerConfigs.FB_NEO_6`:
   ```kotlin
   controllerConfigs = hashMapOf(
       0 to arrayListOf(ControllerConfigs.FB_NEO_4, ControllerConfigs.FB_NEO_6),
   )
   ```
2. Em `ControllerConfigsManager.kt`, quando o usuário ainda não alterou a preferência manualmente pelo menu em jogo, o emulador faz o fallback pegando o primeiro item da lista:
   ```kotlin
   val currentController = controllers.firstOrNull { it.name == currentName } ?: controllers.first()
   ```
3. Com isso, `ControllerConfigs.FB_NEO_4` (`TouchControllerID.ARCADE_4`) era selecionado por default, renderizando apenas 4 botões de face (`Arcade4Right`) sem L1 e R1.
4. O layout de 6 botões (`TouchControllerID.ARCADE_6` / `Arcade6Right`) já existia na codebase com L1 e R1 mapeados como soco e chute forte, mas ficava oculto até que o usuário descobrisse o menu interno do emulador.

## Causa-raiz

A lista de `controllerConfigs` para as plataformas Capcom Play System (`CPS1`, `CPS2`, `CPS3`) foi declarada com o layout de 4 botões como primeira opção (`.first()`), ignorando que os principais e mais populares jogos dessas plataformas são jogos de luta clássicos de 6 botões (3 socos e 3 chutes).

## Correção

Inverter a ordem de `controllerConfigs` em `GameSystem.kt` nos seguintes sistemas, priorizando os layouts de 6 botões:
1. **Capcom CPS (`CPS1`, `CPS2`, `CPS3`):** `ControllerConfigs.FB_NEO_6` antes de `FB_NEO_4`.
2. **FBNeo Genérico (`FBNEO`):** `ControllerConfigs.FB_NEO_6` antes de `FB_NEO_4`.
3. **Data East Arcade (`DATAEAST`):** `ControllerConfigs.FB_NEO_6` antes de `FB_NEO_4` (jogos como *Fighter's History* e *Karnov's Revenge*).
4. **MAME (`MAME2003PLUS`, `MAME289`):** `ControllerConfigs.MAME_2003_6` antes de `MAME_2003_4` (Mortal Kombat, Street Fighter, Killer Instinct, etc.).
5. **Mega Drive / Sega CD (`GENESIS`, `SEGACD`):** `ControllerConfigs.GENESIS_6` antes de `GENESIS_3` para todas as portas 0 a 3 (*Street Fighter II SCE*, *Mortal Kombat 1/2/3/UMK3*, *Eternal Champions*, *Comix Zone*).

Dessa forma, jogos de luta e jogos de 6 botões iniciam imediatamente com todos os botões ativos na tela, enquanto jogos de 2 ou 3 botões continuam funcionando sem qualquer perda ou conflito de botões.

## Validação End-to-End no Emulador Android

1. **Compilação e Instalação:**
   - APK compilado com sucesso (`./gradlew assembleFreeBundleDebug -PdevAbi=x86_64`).
   - APK instalado no emulador Android (`lemu_api25_2gb`, Android 7.1.1 x86_64) via `adb install -r`.
2. **Execução de Jogo CPS-2:**
   - ROM `msh.zip` (Marvel Super Heroes - CPS-2) e bios `qsound.zip` carregadas.
   - Jogo iniciado via deep link `retrogamesystem://app.retrogamesystem.debug/play-game/id/15582` rodando com core `FBNeo`.
3. **Verificação do Layout Touch:**
   - O layout touch padrão carregado automaticamente foi o `Arcade6` (`TouchControllerID.ARCADE_6`):
     - Esquerda: D-pad analógico/direcional de 8 direções + botão Coin (`$`) + botão Start (`Play`).
     - Direita: 4 botões de face (A, B, X, Y) + 2 botões superiores (L1 e R1) correspondentes a Soco Forte e Chute Forte + botão Menu inferior direito.
4. **Interação com Controles:**
   - Inserção de fichas via touch no botão Coin (`$`) -> exibido `CREDIT 1`.
   - Inicialização via touch no botão Start (`Play`) -> abertura do `PLAYER SELECT`.
   - Seleção de lutador (Spider-Man) e jóia do infinito via botões de ataque touch.
   - Partida iniciada normalmente com movimentação e ataques responsivos.

## Lição

Placas de arcade que possuem um gênero ou layout marcante com 6 botões (como a linha Capcom CPS) devem ter o layout de 6 botões como default principal, pois um layout de 6 botões cobre perfeitamente jogos de 2, 3, 4 e 6 botões (os botões extras ficam disponíveis sem prejudicar os básicos), enquanto um layout de 4 botões quebra a jogabilidade de jogos de luta.
