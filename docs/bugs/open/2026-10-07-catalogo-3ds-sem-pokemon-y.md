# [BUG] Catálogo de 3DS tem o Pokémon X e não tem o Pokémon Y — cliente procurou, não achou e citou a falta no pedido de reembolso

**Data:** 2026-10-07
**Status:** 🔴 Aberto — passada 1 (sintoma e evidência); nada corrigido
**Severidade:** Média (conteúdo/acervo: um título de primeira linha ausente; entrou num pedido de reembolso)
**Branch:** version9
**Origem:** triagem do chamado de suporte #112 (sessão `43087531405512@lid`), conversa de WhatsApp

---

## Sintoma

Cliente do Sistema Multigames, jogando no celular (Moto G86) pelo app Retro Game System. Abre o
3DS, vê o contador de 1027 jogos, procura o Pokémon Y pela lista e pela busca e não encontra. Três
dias depois pede reembolso dizendo que "não tem todos os jogos do 3DS, GameCube e PSP".

## Evidência

Conversa (`Wpp_proccess`, horários UTC):

- `[126891]` 2026-10-01 12:32 customer: *"Pokémon y 3ds"*
- `[126897]` 12:34 customer: *"3ds aparece com 1027 jogos mais não aparece o pokémon y"*
- `[126899]` 12:34 customer: *"Não aparece nem se pesquisar"*
- `[130236]` 2026-10-04 02:32 customer: *"...além do mais não tem todos os.jogos.do.3ds GameCube e PSP olha eu só quero o reembolso"*

No manifesto (`lemuroid-app/src/main/assets/catalog_manifest.txt`, HEAD `a8f994f`, versionCode 256):

```
grep -c "^3ds/" catalog_manifest.txt                 -> 1027   (o número que o cliente leu)
grep -iE "^3ds/.*pok" catalog_manifest.txt           -> 18 linhas
```

Entre as 18: `Pokemon X (Europe) (En,Ja,Fr,De,Es,It,Ko).3ds`, `Omega Ruby`, `Alpha Sapphire`,
`Sun`, `Moon`, `Ultra Sun`, `Ultra Moon`. **Nenhuma linha de `Pokemon Y`.** O par X/Y saiu junto e
só metade está no catálogo.

Nunca esteve: `git log -S"Pokemon Y (" -- lemuroid-app/src/main/assets/catalog_manifest.txt` → vazio
(medido em 2026-10-04 pela triagem do lado do agente, ver "Relacionados").

### A busca não é a causa

O agente de suporte supôs que a busca falhava pelo acento ("Pokémon") e mandou buscar "pokemon".
Não é isso: o índice é `FTS4(tokenize=unicode61 "remove_diacritics=1")`, tanto no app
(`GameSearchDao.kt`, linha 36–37) quanto no prebuilt (`PrebuiltDbGenerator.kt`, linha 268–269).
"Pokémon" e "Pokemon" caem no mesmo token. O jogo não aparece porque não existe linha para ele.

## Hipóteses — nenhuma verificada

O que não foi medido, e decide o tamanho da correção:

1. **O arquivo existe no repositório de ROMs e só falta a linha no manifesto.** Conferir no bucket
   de onde o `RomOnDemandManager` baixa o 3DS se há um `Pokemon Y (...).3ds`. Se houver, é uma linha
   no manifesto + bump de `MANIFEST_SCHEMA_VERSION`.
2. **O arquivo não está hospedado.** Aí é upload + linha + capa.
3. **Há mais pares incompletos.** O cliente falou em "jogos importantes" faltando também em PSP e
   GameCube, sem citar títulos (`[125872]`, 2026-09-30 07:55: *"consoles como PSP game cube faltam
   jogos importantes"*). Sem nome não dá para conferir; a pergunta "quais?" nunca foi feita a ele.

## Escopo — o que NÃO é este bug

- O agente ter mandado o cliente baixar emulador solto pela planilha: é do lado do agente, já
  registrado e corrigido em
  `C:\projects\digitalstoregamesproject\docs\modules\chatbot-whatsapp\areas\prompt-kb\bugs\2026-10-03-agente-manda-emulador-solto-da-planilha-em-vez-do-app-android-quando-falta-jogo.md`.
- O contador de jogos que dobra e cai na abertura do app (mesmo chamado): causa conhecida, ver a
  seção "Confirmação de campo" em `documentacao/bugs/done/2026-10-02-prebuilt-fileuri-sem-encoding-recria-catalogo.md`.
- O áudio engasgando no mesmo aparelho: `documentacao/bugs/open/2026-10-07-audio-engasgando-gba-nds-moto-g86-sem-diagnostico.md`.

## Próximos passos

1. Conferir a hipótese 1 no bucket de ROMs de 3DS.
2. Incluir o Pokémon Y no manifesto (com capa e `popularityIndex` no patamar do Pokémon X) e bumpar
   `MANIFEST_SCHEMA_VERSION`.
3. Prova: `grep -c "^3ds/Pokemon Y" catalog_manifest.txt` = 1; no aparelho, buscar "pokémon y" no
   3DS, baixar e abrir o jogo.
