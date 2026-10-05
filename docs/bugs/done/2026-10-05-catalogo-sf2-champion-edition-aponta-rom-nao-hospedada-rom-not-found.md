# [BUG] Catálogo: o item visível de *Street Fighter II': Champion Edition* aponta para `sf2ceua.zip`, que não está hospedado — download sempre termina em "ROM not found"

- **Detectado em:** 2026-10-03 13:44 (chamado #129 do painel de suporte, sessão WhatsApp `81776613540006@lid`)
- **Origem:** conversa de suporte + leitura de `SaveQueueManager.processQueue`, `RomOnDemandManager.downloadRom` / `resolveDownloadUrl` e sondagem do endpoint `find_by_file` em 2026-10-05
- **Status:** **CORRIGIDO / RESOLVIDO** (2026-10-05)
- **Classe:** conteúdo-acervo (catálogo x hospedagem)
- **Severidade:** média — o jogo fica visível e impossível de baixar; o erro é determinístico, então "tentar de novo", reinstalar o app ou trocar de rede nunca resolvem. Neste chamado o cliente reinstalou e escalou para suporte humano por causa disso
- **Complexidade:** baixa — trocar o representante do grupo no manifest (ou hospedar o arquivo); 3 itens no total
- **Reincidência:** primeira vez. Relacionado: [[2026-07-21-items-duplicados-catalogo]]

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

## Evidência e Diagnóstico

**1. De onde vinha a frase "ROM not found".** O literal em inglês existia hardcoded em
`lemuroid-app/.../shared/roms/SaveQueueManager.kt::processQueue`, no ramo
`RomOnDemandManager.DownloadResult.NotFound`. O `NotFound` sai de `RomOnDemandManager.downloadRom` quando
`resolveDownloadUrl` devolve `null` — que é **HTTP 404 ou corpo vazio** do endpoint
`https://emuladores.pythonanywhere.com/find_by_file`.

**2. O item visível apontava para arquivo não hospedado.** No `catalog_manifest.txt` do app:
`sf2ceua.zip` estava marcado com `isRepresentative=1`, enquanto o set-pai `sf2ce.zip` estava com `isRepresentative=0`.
Sondagem do endpoint com parâmetros do app (`source_id=1&system=fbneo`):
- `sf2ceua.zip`: **404**
- `sf2ce.zip`: **200** (hospedado no dataset Hugging Face `luistiagos/fbneo`, 3.584.769 bytes)

**3. Escala — exatamente 3 itens de `cps1` afetados:**
1. `Street Fighter II': Champion Edition`: clone `sf2ceua.zip` (404) vs pai `sf2ce.zip` (200 OK)
2. `1941: Counter Attack`: clone `1941j.zip` (404) vs pai `1941.zip` (200 OK)
3. `Forgotten Worlds`: clone `forgott1.zip` (404) vs pai `forgottn.zip` (200 OK)

Todos os outros 1.765 representantes de sistemas FBNeo no catálogo já apontavam para ROMs hospedadas.

---

## Solução Implementada

1. **Correção dos 3 representantes no `catalog_manifest.txt`:**
   - Atualizados simultaneamente em `lemuroid-app/src/main/assets/catalog_manifest.txt` e no espelho `catalog_manifest.txt` da raiz do repositório:
     - `cps1/sf2ce.zip|Street Fighter II': Champion Edition (World 920513)|https://adb.arcadeitalia.net/?mame=sf2ce&type=title&resize=0|0|1` (clone `cps1/sf2ceua.zip|||0|0`)
     - `cps1/1941.zip|1941: Counter Attack (World 900227)|https://adb.arcadeitalia.net/?mame=1941&type=title&resize=0|0|1` (clone `cps1/1941j.zip|||0|0`)
     - `cps1/forgottn.zip|Forgotten Worlds (World, newer)|https://adb.arcadeitalia.net/?mame=forgottn&type=title&resize=0|0|1` (clone `cps1/forgott1.zip|||0|0`)
   - Preservados estritamente: encoding UTF-8 sem BOM, terminação LF e ordenação.

2. **Incremento de `MANIFEST_SCHEMA_VERSION` (36 → 37):**
   - Atualizado em `retrograde-app-shared/.../catalog/ManifestQuickLoader.kt`.
   - Dispara a sincronização automática nos clientes existentes no primeiro launch, atualizando os registros no Room DB via `updateManifestFieldsWithTitle`.

3. **Regeneração da base pré-construída (`retrograde-prebuilt.db`):**
   - Executado `.\gradlew.bat :lemuroid-app:generatePrebuiltDb`.
   - Validado: 60.926 jogos, integridade FTS e schema version 24. Novos usuários já recebem o catálogo corrigido no primeiro boot.

4. **UX Aprimorada (Internacionalização da mensagem de erro):**
   - Removido o literal hardcoded em inglês `"ROM not found"` em `SaveQueueManager.kt`.
   - Adicionada chave `save_queue_rom_not_found` nos arquivos de recursos:
     - `values/strings.xml`: *"This game is not available on the server. Try another version."*
     - `values-pt-rBR/strings.xml`: *"Este jogo não está disponível no servidor. Tente outra versão."*
     - `values-pt-rPT/strings.xml`: *"Este jogo não está disponível no servidor. Tente outra versão."*

5. **Ferramenta de Auditoria Preventiva contra Regressões:**
   - Criado `validate_catalog_representatives.py` na raiz do projeto.
   - Audita e compara automaticamente os representantes (`isRepresentative=1`) contra a listagem de arquivos do dataset no Hugging Face e a API `find_by_file`.

---

## Validação e Testes

1. **Auditoria dos Representantes:**
   - `python validate_catalog_representatives.py` executado:
     - Total de representantes auditados: 1.768
     - Representantes quebrados: **0** (100% hospedados e íntegros).

2. **Sondagem de Download e Content-Length:**
   - `cps1/sf2ce.zip` (sistema `fbneo`): HTTP 200, Content-Length: 3.584.769 bytes
   - `cps1/1941.zip` (sistema `fbneo`): HTTP 200, Content-Length: 1.420.267 bytes
   - `cps1/forgottn.zip` (sistema `fbneo`): HTTP 200, Content-Length: 1.952.659 bytes

3. **Testes Automatizados:**
   - `.\gradlew.bat :retrograde-app-shared:testDebugUnitTest :lemuroid-app:compileFreeBundleDebugKotlin` aprovado (`BUILD SUCCESSFUL`).
   - `.\gradlew.bat :lemuroid-app:testFreeBundleDebugUnitTest` aprovado (`BUILD SUCCESSFUL`).
