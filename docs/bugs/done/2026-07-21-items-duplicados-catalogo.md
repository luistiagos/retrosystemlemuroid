# [BUG] Itens Duplicados no Catálogo de Jogos

**Data:** 2026-07-21
**Status:** CORRIGIDO (manifesto limpo e validado; versão do schema incrementada)
**Severidade:** MÉDIA — itens duplicados aparecem no catálogo do usuário para sistemas cujas linhas tinham < 5 campos
**Branch:** main

---

## Sintoma

Conforme as imagens em anexo reportadas pelo usuário, vários itens de catálogo apareciam duplicados na lista (ex.: *The Outer Worlds* no 3DO, *Barbarian II* no GX4000, *Pokemon - Crystal Version* no GBC). Tocar nos itens abria o modal de variantes, mas a lista de catálogo continuava com itens duplicados.

## Causa-raiz

O Room/SQLite do app utiliza o campo `isRepresentative` da tabela `games` para filtrar as consultas agrupadas (`isRepresentative = 1`). No entanto:
1. O arquivo `lemuroid-app/src/main/assets/catalog_manifest.txt` continha 9.440 linhas com apenas 1 campo (caminho do arquivo) e 3.295 linhas com 4 campos (sem a tag `isRepresentative` no 5º campo).
2. Durante o processamento do manifest (`ManifestQuickLoader` / `PrebuiltDbGenerator`), linhas com menos de 5 campos tinham o campo `isRepresentative` assumido como `true` por padrão.
3. Isso fazia com que múltiplas variantes de uma mesma ROM (regiões ou versões diferentes) fossem inseridas com `isRepresentative = 1` no banco de dados, resultando em múltiplos representantes para o mesmo grupo de `(systemId, clean_title)` e, consequentemente, duplicando itens no catálogo.

## Correção

1. Executamos um script de limpeza off-line (`clean_active_manifest.py`) sobre o manifesto ativo `lemuroid-app/src/main/assets/catalog_manifest.txt`.
2. O script:
   - Eliminou 460 linhas duplicadas de mesmo nome de arquivo (filename).
   - Derivou títulos limpos caso estivessem ausentes e removeu as tags regionais/revisões/idiomas para agrupar por `(systemId, cleaned_title)`.
   - Para cada grupo de variantes, escolheu exatamente um representante (com base na popularidade, presença de imagem de capa e menor nome de arquivo lexicográfico) e definiu `isRepresentative = 1` apenas para ele, marcando todos os outros como `isRepresentative = 0`.
   - Escreveu de volta todas as linhas com exatamente 5 campos e ordenadas por sistema e título.
3. Incrementamos a versão de `MANIFEST_SCHEMA_VERSION` para `26` em `ManifestQuickLoader.kt`. Ao inicializar o app atualizado, isso força a reimportação do manifesto e a atualização em lote da coluna `isRepresentative` dos jogos existentes para todos os usuários.

## Validação

- `validate_manifest.py` executado com sucesso:
  - Todas as linhas no manifesto modificado possuem exatamente 5 campos.
  - Zero representantes duplicados para qualquer grupo.
- Build do Gradle roda a task `generatePrebuiltDb` com sucesso gerando a base SQLite embarcada (`retrograde-prebuilt.db`) atualizada.
