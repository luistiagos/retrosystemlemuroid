# [BUG] Catálogo: o item visível de *Street Fighter II': Champion Edition* aponta para `sf2ceua.zip`, que não está hospedado — download sempre termina em "ROM not found"

- **Detectado em:** 2026-10-03 13:44 (chamado #129 do painel de suporte, sessão WhatsApp `81776613540006@lid`)
- **Origem:** conversa de suporte + leitura de `SaveQueueManager.processQueue`, `RomOnDemandManager.downloadRom` / `resolveDownloadUrl` e sondagem do endpoint `find_by_file` em 2026-10-05
- **Classe:** conteúdo-acervo (catálogo x hospedagem)
- **Severidade:** média — o jogo fica visível e impossível de baixar; o erro é determinístico, então "tentar de novo", reinstalar o app ou trocar de rede nunca resolvem. Neste chamado o cliente reinstalou e escalou para suporte humano por causa disso
- **Complexidade:** baixa — trocar o representante do grupo no manifest (ou hospedar o arquivo); 3 itens no total
- **Reincidência:** primeira vez. Relacionado, mas não duplicado: [[2026-07-21-items-duplicados-catalogo]] (definiu a regra de escolha do representante, que não leva em conta se o arquivo está hospedado)

---

## Sintoma, na ordem que o cliente viveu

Cliente do *Sistema Multigames*, Android, app Retro Game System. Comprou em 08/09; em 03/10 tentou baixar um jogo de arcade CPS1.

- [129524] 13:44:05 (UTC) — *"Estou tentando baixar um jogo, mais esta dando erro"*
- [129526] 13:44:23 — print da tela. Descrição gerada da imagem: lista com *Ghouls'n Ghosts*, *Mega Man: The Power Battle*, *Street Fighter II*; na seção **"Salvos"**, *"Street Fighter II': Champion Edition"* com a mensagem **"ROM not found"**
- [129529] — *"Antes funcionava normal"*
- [129533] — *"Já fiz tudo isso..."* (fechar/abrir o app, tentar de novo)
- [129538] — *"Desistalei E estalei o jogo de novo"*
- [129541] — *"Só no street fighter"* (outros jogos baixam)
- [129544] — *"Aparece o mesmo erro"* (resposta a "tenta outra versão de Street Fighter")

## Evidência

**1. De onde vem a frase "ROM not found".** O literal em inglês só existe em
`lemuroid-app/.../shared/roms/SaveQueueManager.kt::processQueue`, no ramo
`RomOnDemandManager.DownloadResult.NotFound`. O `NotFound` sai de `RomOnDemandManager.downloadRom` em
dois casos: sistema sem mapeamento em `mnemonico_map.json` (não é o caso: `cps1 → fbneo`), ou
`resolveDownloadUrl` devolver `null` — que é **HTTP 404 ou corpo vazio** do endpoint
`https://emuladores.pythonanywhere.com/find_by_file`. Falha de rede, timeout e 5xx viram
`DownloadResult.Failure` com outra mensagem, não "ROM not found". Portanto, **"ROM not found" =
o servidor disse que esse arquivo não existe**. É determinístico por arquivo.

**2. O item visível aponta para um arquivo que não existe.** No `catalog_manifest.txt` do app
(o mesmo antes e depois do `d0c76aa`, de 04/10):

```
cps1/sf2ceua.zip|Street Fighter II': Champion Edition (USA 920513)|...|0|1   <- isRepresentative=1
```

Sondagem do endpoint em 2026-10-05, com os mesmos parâmetros do app (`source_id=1&system=fbneo`):

| arquivo | `find_by_file` | no HF `luistiagos/fbneo` |
|---|---|---|
| `sf2ceua.zip` (o visível) | **404** | **404** |
| `sf2ce.zip` (set-pai) | 200 | existe |
| `sf2hf.zip`, `sf2.zip` | 200 | existe |
| controles: `ghouls`, `megaman`, `dino`, `ffight` | 200 | — |

Antes do `d0c76aa` o `sf2ce.zip` nem estava no manifest; desde então está, mas como
`isRepresentative=0` (`cps1/sf2ce.zip|||0|0`), então o cliente continua vendo só o `sf2ceua`.

**3. Escala — são 3 itens, todos de `cps1`.** Comparando todas as linhas com `isRepresentative=1` dos
sistemas mapeados para `fbneo` com a listagem do dataset HF (1825 arquivos), 3 de 1768 faltam:

| item visível | arquivo | pai hospedado? |
|---|---|---|
| Street Fighter II': Champion Edition (USA 920513) | `sf2ceua.zip` | `sf2ce.zip` — sim |
| 1941: Counter Attack (Japan) | `1941j.zip` | `1941.zip` — sim |
| Forgotten Worlds (USA 880715) | `forgott1.zip` | `forgottn.zip` — sim (`forgott.zip` não) |

Os três 404 foram confirmados no endpoint. Script: comparar `catalog_manifest.txt` (5º campo = 1,
sistema com `fbneo` no `mnemonico_map.json`) com `https://huggingface.co/api/datasets/luistiagos/fbneo/tree/main`.

## Causa raiz

Provado: o representante visível dos 3 grupos é um clone regional que não está hospedado, e o
download dele sempre acaba em 404 → `NotFound` → "ROM not found".

Não provado (hipótese): por que o gerador escolheu o clone. A regra de [[2026-07-21-items-duplicados-catalogo]]
(popularidade, capa, menor nome) não consulta a hospedagem; nos três casos o set-pai está sem título e
sem capa no manifest (`|||0|0`), o que explicaria o clone ter ganhado. O script gerador desse trecho não
foi aberto.

## Escopo — o que NÃO é este bug

- **"Aparece o mesmo erro" nas outras versões** ([129544]) **não reproduz**: `sf2hf.zip` e `sf2.zip`
  respondem 200 e existem no HF. Hipótese, não verificada: a linha de erro de antes continua na seção
  "Salvos" — `SaveQueueManager` mantém a entrada `ERROR` na lista em memória até `dismissError`/`clearErrors`
  ou o app reiniciar — e o cliente leu a linha antiga como erro novo. Também pode ser que ele não tenha
  tentado de fato.
- Não é rede, servidor fora do ar nem app corrompido — reinstalar não muda nada (ver evidência 1).
- O agente de WhatsApp ter inventado uma causa ("geralmente temporário") é bug do agente, registrado
  à parte em `digitalstoregamesproject`:
  `docs/modules/chatbot-whatsapp/areas/prompt-kb/bugs/2026-10-05-agente-inventa-causa-e-manda-reinstalar-o-app-android-para-erro-rom-not-found.md`.
  Cada um continua valendo sem o outro.

## Tarefas propostas / Próximos passos

1. **Corrigir os 3 itens do catálogo**: tornar `sf2ce.zip`, `1941.zip` e `forgottn.zip` os
   representantes (com título e capa) e rebaixar os clones — ou hospedar os 3 clones no dataset. Prova:
   `find_by_file` 200 para o arquivo de cada linha visível, e o script de comparação acima devolvendo 0.
2. **Fazer o gerador do catálogo recusar representante não hospedado**, comparando com a listagem de
   cada dataset antes de marcar `isRepresentative=1`, para a próxima expansão (`expand catalogs`) não
   reintroduzir isso. Estender a checagem aos outros sistemas, não só `fbneo`.
3. (UX, menor) A mensagem "ROM not found" é crua e em inglês, num app em português, e não diz o que
   fazer. Uma mensagem traduzida ("Este jogo não está disponível no servidor. Tente outra versão.")
   evitaria a reinstalação inútil.
