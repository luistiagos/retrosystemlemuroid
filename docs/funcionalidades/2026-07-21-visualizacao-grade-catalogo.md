# Visualização em Grade no Catálogo por Sistema

- **Data**: 2026-07-21
- **Status**: Implementado
- **Autor**: Antigravity

---

## Descrição

Implementação de uma forma de visualização alternativa no formato de **grade (grid)** com 3 colunas para a lista de jogos do catálogo de cada sistema (tela `GamesScreen`). Além disso, foi adicionado um switch na tela de configurações gerais para alternar entre a nova visualização em grade e a visualização em lista tradicional. O padrão (default) foi definido como visualização em grade.

---

## Arquivos Modificados / Criados

- **`keys.xml`** [MODIFY]: Adicionado `pref_key_catalog_layout_grid` como chave de preferência.
- **`strings.xml`** (EN/pt-rBR/pt-rPT) [MODIFY]: Adicionados os títulos e descrições localizados para a nova configuração.
- **`SettingsScreen.kt`** [MODIFY]: Adicionado o toggle de configuração `LemuroidSettingsSwitch` na aba de configurações Gerais.
- **`LemuroidGameGridItem.kt`** [NEW]: Componente Compose que renderiza o jogo em formato de cartão/grade com a imagem (cover) vertical (aspect ratio 0.75f) e o título centralizado abaixo, incluindo suporte a cliques normais e longos, bem como badges para variantes e download concluído.
- **`GamesScreen.kt`** [MODIFY]: Coleta o estado da preferência `catalog_layout_grid` (default = true) e decide se renderiza o catálogo usando `LazyVerticalGrid` (grade de 3 colunas) ou `LazyColumn` (lista).

---

## Detalhes da Implementação

### Componente de Grade (`LemuroidGameGridItem`)

O item de grade é desenhado em formato de coluna sem fundo de card fixo, centralizando o título do jogo abaixo da capa. Possui as seguintes características visuais:
- **Capa**: Usa `LemuroidGameImage` com `aspectRatio(0.75f)` e bordas arredondadas de `8.dp`.
- **Badges**:
  - Se baixado, exibe um ícone de download no canto superior direito (idêntico ao `LemuroidGameCard`).
  - Se tiver variantes, exibe um ícone de cópia no canto superior esquerdo.
- **Texto**: O título usa `labelMedium`, alinhado ao centro e com limite de 2 linhas (`maxLines = 2`).

### Controle na Tela de Catálogo (`GamesScreen`)

- Carrega a preferência via `booleanPreferenceState(R.string.pref_key_catalog_layout_grid, true)`.
- Dependendo do valor (`isGridView`):
  - Se `true` (padrão): Exibe uma `LazyVerticalGrid` com `columns = GridCells.Fixed(3)`.
  - Se `false`: Exibe a `LazyColumn` tradicional de listas.
- Ambos os caminhos mantêm a lógica de scroll automático para o topo ao resumir a tela e o header de ordenação (`GamesSortHeader`) no topo.

---

## Validação

### Compilação do Código
A compilação do aplicativo foi verificada executando `./gradlew assembleDebug` para confirmar que não existem erros de importação ou de sintaxe em Kotlin.

### Validação Manual Recomendada
1. Ir para a tela de um sistema (por exemplo, PlayStation 2).
2. Verificar se os jogos aparecem em formato de grade com 3 colunas e com o título abaixo de cada capa.
3. Ir nas configurações gerais e desmarcar a opção "Visualização em Grade".
4. Voltar à tela do sistema e verificar se o catálogo voltou ao formato de lista clássico.
