# [BUG] Áudio engasgando em jogos leves (GBA e DS) num Moto G86 — queixa repetida três vezes, sem dado técnico

**Data:** 2026-10-07
**Status:** 🔴 Aberto — passada 1 (sintoma e evidência); causa desconhecida
**Severidade:** Alta pelo custo (é o primeiro motivo do pedido de reembolso do cliente), baixa em evidência (um aparelho, relato só por texto)
**Branch:** version9
**Origem:** triagem do chamado de suporte #112 (sessão `43087531405512@lid`), conversa de WhatsApp

---

## Sintoma

Cliente com Moto G86 relata que o áudio dos jogos trava. Testou em Pokémon Emerald (GBA) e Pokémon
Black (DS), dois sistemas leves para o aparelho. Na primeira vez, fechar os apps em segundo plano
resolveu. No dia seguinte a queixa voltou como "muitos jogos" e, quatro dias depois, como "está
travando muito, já tentei de tudo".

## Evidência

Conversa (`Wpp_proccess`, horários UTC). A compra foi aprovada em 2026-09-29 21:48 (`[125661]`).

- `[125689]` 2026-09-29 22:18 customer: *"Os áudios dos jogos estão travando"* — 30 min depois da compra
- `[125691]` 22:19 customer: *"No celular e um moto g86"*
- `[125693]` 22:20 customer: *"Eu testei no pokémon emerald e no blac version ds"*
- `[125695]` 22:22 customer: *"Era excesso em segundo plano obg"*
- `[125872]` 2026-09-30 07:55 customer, pedindo reembolso: *"Muitos jogos ficam travando no áudio..."*
- `[130236]` 2026-10-04 02:32 customer: *"Esta travando muito.no celular já tentei de tudo e desisti..."*
- `[130243]` 02:38 customer: *"...achei que ia dá certo no celular mais acabou não dando"*

Telemetria: nenhuma linha deste aparelho.

```
SELECT ... FROM errors WHERE time >= '2026-09-29'
  AND (user_agent LIKE '%g86%' OR message LIKE '%g86%' OR platform LIKE '%g86%' OR screen LIKE '%g86%')
-> 0 linhas
```

O que isso prova: não houve crash, ANR nem `lowmemory` reportado por ele. Áudio engasgando com o
jogo rodando não gera report nenhum hoje, então a ausência não diz se o problema existe.

## Hipóteses — nenhuma verificada

Nenhum símbolo foi aberto para este bug. O que falta medir:

1. **Disputa de CPU/IO com trabalho em segundo plano do próprio app.** A primeira queixa veio 30 min
   depois da compra e passou ao fechar apps. Conferir se havia download na fila ou carga de catálogo
   rodando enquanto ele jogava.
2. **Configuração de áudio do núcleo neste SoC** (latência/buffer). Precisa do aparelho ou de um
   equivalente.
3. **Núcleo usado para DS e GBA** e se a queixa de "muitos jogos" é dos mesmos dois sistemas ou de
   outros. O cliente nunca disse quais.
4. **Versão instalada.** Não há tabela de versão do app no banco. Em 2026-10-04 o operador mandou o
   link do APK publicado; o cliente respondeu *"não tem"* e não disse se reinstalou.

## Escopo — o que NÃO é este bug

- Jogo ausente do catálogo (mesmo chamado): `docs/bugs/open/2026-10-07-catalogo-3ds-sem-pokemon-y.md`.
- Sistemas pesados travando por desempenho (Dreamcast, PSP): outros docs em `open/`.

## Próximos passos

1. Reproduzir em aparelho com o mesmo SoC, ou num de faixa parecida: Pokémon Emerald (GBA) e
   Pokémon Black (DS), 10 min cada, com e sem download na fila.
2. Se o cliente voltar a falar com o suporte: pedir a versão do app (Ajustes) e um vídeo curto do
   engasgo, e perguntar em quais jogos acontece.
3. Decidir se vale reportar underrun de áudio na telemetria. Hoje esse sintoma é invisível.
