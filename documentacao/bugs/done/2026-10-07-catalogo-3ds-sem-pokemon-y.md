# [BUG] Catálogo de 3DS tem o Pokémon X e não tem o Pokémon Y — cliente procurou, não achou e citou a falta no pedido de reembolso

- **Data:** 2026-10-07
- **Status:** 🟢 **CORRIGIDO / RESOLVIDO** (2026-10-07)
- **Severidade:** Média (conteúdo/acervo: um título de primeira linha ausente; entrou num pedido de reembolso)
- **Branch:** version9
- **Origem:** triagem do chamado de suporte #112 (sessão `43087531405512@lid`), conversa de WhatsApp

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
só metade estava no catálogo.

---

## Diagnóstico e Investigação das Hipóteses

1. **Hipótese 2 confirmada (O arquivo não estava hospedado nem no banco):**
   - No dataset `Emuladores/3ds` no Hugging Face (1.030 arquivos), existia apenas `Pokemon X`. `Pokemon Y` não constava.
   - Na tabela `ROM` do MySQL de produção (`console_id=148845`), existia apenas a entrada de `Pokemon X` (`rom_id=364296`). Nenhuma linha para `Pokemon Y`.
   - Na tabela `INFO`, existia o registro `384699` para `Pokemon Y (Europe) (En,Ja,Fr,De,Es,It,Ko)`.
   - No arquivo `3ds_rom_upload.csv`, faltava a linha correspondente.

2. **Localização e Validação da ROM Limpa:**
   - No Internet Archive (`3ds-decrypted-roms321com`), foi localizada a ROM decrypted europeia original em formato `.zip`.
   - Foi baixada e extraída a ROM `Pokemon Y (Europe) (En,Ja,Fr,De,Es,It,Ko).3ds`:
     - Tamanho exato: `2.147.483.648 bytes` (2.15 GB, idêntico a Pokémon X).
     - Cabeçalho NCSD em `0x100`: `NCSD`, flags `0001000201020000` (descriptografado padrão para Citra).
     - SHA256: `44e661a351da2bab29016097d5c34873e427b92bea0a48812c8607ad28aa52ce`.

---

## Solução Implementada

1. **Hospedagem da ROM:**
   - Realizado upload da ROM para o dataset Hugging Face `luisluis123/3ds`:
     - URL direta: `https://huggingface.co/datasets/luisluis123/3ds/resolve/main/Pokemon%20Y%20%28Europe%29%20%28En%2CJa%2CFr%2CDe%2CEs%2CIt%2CKo%29.3ds?download=true`
     - Testada requisição HTTP HEAD com retorno `200 OK`, `Content-Length: 2147483648`, `Content-Type: image/x-3ds`.

2. **Cadastro no Banco de Dados Central (`romsrepository`):**
   - Inserido registro na tabela `ROM` no MySQL de produção:
     - `console_id`: `148845` (3ds)
     - `source_id`: `1`
     - `path`: `Pokemon Y (Europe) (En,Ja,Fr,De,Es,It,Ko).3ds`
     - `link`: `https://huggingface.co/datasets/luisluis123/3ds/resolve/main/Pokemon%20Y%20%28Europe%29%20%28En%2CJa%2CFr%2CDe%2CEs%2CIt%2CKo%29.3ds?download=true`
     - `size`: `2.15 GB`
     - `alias`: `pokemony`
   - Atualizado `d:\projects\romsrepository\3ds_rom_upload.csv`.
   - Validação da rota `/find_by_file`:
     `curl "https://emuladores.pythonanywhere.com/find_by_file?path=Pokemon%20Y%20(Europe)%20(En,Ja,Fr,De,Es,It,Ko).3ds&source_id=1&system=3ds"`
     Retorna HTTP 200 com a URL direta do Hugging Face.

3. **Inclusão nos Manifestos do Lemuroid:**
   - Inserida linha no `lemuroid-app/src/main/assets/catalog_manifest.txt` e no espelho da raiz `catalog_manifest.txt`:
     `3ds/Pokemon Y (Europe) (En,Ja,Fr,De,Es,It,Ko).3ds|Pokemon Y|https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Nintendo_3DS/master/Named_Boxarts/Pokemon%20Y%20%28Europe%29%20%28En%2CJa%2CFr%2CDe%2CEs%2CIt%2CKo%29.png|313|1`
   - Capa verificada e ativa no `libretro-thumbnails` (417.576 bytes).
   - Popularidade configurada em `313` (mesmo patamar de Pokémon X).

4. **Incremento de `MANIFEST_SCHEMA_VERSION` (37 → 38):**
   - Atualizado em `retrograde-app-shared/.../catalog/ManifestQuickLoader.kt` com histórico inline.
   - Atualizada tabela de versões no `CLAUDE.md`.
   - Força o recarregamento do catálogo em instalações existentes sem perder estado do usuário.

5. **Regeneração do Prebuilt Database (`retrograde-prebuilt.db`):**
   - Executada a task `.\gradlew.bat :lemuroid-app:generatePrebuiltDb`.
   - Validação: `games=60927`, `fts=60927`, schema version 24, triggers e hash íntegros. Novos usuários recebem Pokémon Y imediatamente no primeiro boot.

6. **Validação de Testes e Compilação:**
   - Executado `.\gradlew.bat :retrograde-app-shared:testDebugUnitTest :lemuroid-app:compileFreeBundleDebugKotlin`.
   - Resultado: `BUILD SUCCESSFUL`.
