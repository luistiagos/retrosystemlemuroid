# Selecionar Diretório de ROMs

## Descrição

Ao selecionar um novo diretório de ROMs (via SAF — Storage Access Framework), caso já exista um diretório anteriormente configurado com arquivos, o app pergunta ao usuário se deseja mover todos os arquivos para o novo local — incluindo saves, caso estejam presentes.

## Arquivo principal

`lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/settings/StorageFrameworkPickerLauncher.kt`

## Fluxo de execução

1. O usuário abre o seletor de diretório (`ACTION_OPEN_DOCUMENT_TREE`).
2. Ao confirmar, verifica se o novo URI é diferente do atual.
3. Se for igual ou não houver valor anterior, apenas agenda o rescan da biblioteca e finaliza.
4. Caso contrário, atualiza as permissões persistíveis (revoga as antigas, concede a nova).
5. Lista os arquivos de ROMs em `getInternalRomsDirectory()`.
6. Se houver arquivos:
   - Exibe um `AlertDialog` perguntando se deseja mover os arquivos.
   - **Sim:** chama `moveRomsAndFinish()`.
   - **Não:** salva o novo URI nas preferências, agenda rescan e finaliza.
7. Se não houver arquivos: salva o URI e finaliza diretamente.

## Método `moveRomsAndFinish()`

- Exibe um diálogo de progresso (percentual atualizado a cada arquivo copiado).
- Para cada arquivo em `romsFiles`:
  - Chama `copyFileToSaf(file, sourceDir, destTree)` no dispatcher `IO`.
  - Atualiza o percentual no diálogo.
- Após copiar todos, apaga `sourceDir` recursivamente.
- Salva o novo URI nas preferências.
- Agenda o rescan da biblioteca (`LibraryIndexScheduler`).
- Fecha a Activity.

## Método `copyFileToSaf()`

- Recria a estrutura de pastas relativa ao `sourceDir` dentro do `destTree` (SAF `DocumentFile`).
- Cria o arquivo de destino com MIME `application/octet-stream`.
- Copia o conteúdo via streams (`InputStream` → `OutputStream`).

## Diretórios envolvidos

| Tipo   | Caminho interno (`DirectoriesManager`)                         |
|--------|----------------------------------------------------------------|
| ROMs   | `getExternalFilesDir(null)/roms`  → `getInternalRomsDirectory()` |
| Saves  | `getExternalFilesDir(null)/saves` → `getSavesDirectory()`        |
| States | `getExternalFilesDir(null)/states`→ `getStatesDirectory()`       |

> **Nota:** a implementação atual move apenas ROMs (`getInternalRomsDirectory()`). Saves e states ficam no diretório de arquivos externos do app e **não são movidos** neste fluxo.

## Strings relacionadas

| Chave                                    | Uso                                         |
|------------------------------------------|---------------------------------------------|
| `settings_move_roms_title`               | Título do diálogo de confirmação            |
| `settings_move_roms_message`             | Mensagem com quantidade de arquivos         |
| `settings_move_roms_yes`                 | Botão confirmar                             |
| `settings_move_roms_no`                  | Botão cancelar                              |
| `settings_transferring_roms_title`       | Título do diálogo de progresso              |
| `settings_transferring_roms_progress`    | Mensagem de progresso (percentual)          |
