# Especificação v2 — Download Sob Demanda de ROMs

**Última atualização:** 06/04/2026  
**Status:** Em validação — ver Seção 8 para decisões pendentes restantes

### Decisões já tomadas

| # | Decisão | Resolução |
|---|---|---|
| 1 | Abordagem do catálogo | **Opção A** — arquivos de 0 bytes no HuggingFace |
| 2 | Mapeamento de sistemas (`mnemonico_map`) | **Embutido no código** como objeto Kotlin `RomSystemMapper` |
| 3 | Formato da resposta do endpoint REST | **Texto puro** — URL de download direta |
| 4 | Rastreamento de ROMs baixadas | **Opção 4A** — tabela Room separada `DownloadedRom` |
| 5 | Comportamento de clique em jogo já baixado | **Opção 2B** — clique direto abre modal (Jogar / Deletar) |
| 6 | ROM não encontrada no endpoint | **Esconder o jogo da lista** |
| 7 | Arquivos HF são realmente 0 bytes? | **Confirmado por teste** — API retorna `size: 0`, OID = SHA1 do blob vazio Git, download chega com 0 bytes exatos. **Não são ponteiros LFS.** |

---

## 1. Contexto e Motivação

Na versão atual, o download de ROMs funciona em pacote completo: o usuário baixa todo o repositório de uma vez. Isso gera dois problemas principais:

- O usuário recebe ROMs que não tem interesse, desperdiçando espaço no dispositivo.
- Não é possível ofertar um catálogo grande, pois é improvável que o usuário tenha espaço disponível para todo o pacote.

**Objetivo da v2:** Exibir todo o catálogo disponível e deixar o usuário baixar somente as ROMs que quiser, quando quiser, sem precisar baixar tudo de uma vez.

---

## 2. Estado Atual do Código (referência)

| Componente | Descrição |
|---|---|
| `StreamingRomsManager` | Baixa arquivo por arquivo do HuggingFace via API tree. Suporta pause/resume, WiFi-only, auto-restart. |
| `RomsDownloadManager` | Download em pacote (fallback). Extrai ZIP, organiza pastas de sistema. |
| `HomeScreen` | Card de download com estados: Idle → Downloading → Paused → Done → Error. |
| `GameInteractor` | Clique num jogo chama diretamente `gameLauncher.launchGameAsync()` sem verificar se a ROM está baixada. |
| `Game` (entity Room) | Campos: `id`, `fileName`, `fileUri`, `title`, `systemId`, `developer`, `coverFrontUrl`, `lastPlayedAt`, `isFavorite`. **Não tem campo de tamanho ou status de download.** |
| `GamesScreen` | Lista jogos sem badge ou indicação de status de download. |
| `MainGameContextActions` | Bottom sheet com: Continuar, Reiniciar, Favoritar, Criar atalho. Sem opção Deletar. |
| `DirectoriesManager` | Retorna diretório de ROMs via `SmartStoragePicker` (escolhe volume com mais espaço livre). |
| `LibraryIndexScheduler` | Trigado após cada arquivo baixado para que jogos apareçam na biblioteca imediatamente. |

---

## 3. Fluxo Proposto

### 3.1 Fase 1 — Download do Catálogo (capas + placeholders)

1. Ao abrir o app pela primeira vez (ou se a pasta de ROMs não existir), iniciar automaticamente o download do catálogo.
2. O download faz streaming arquivo por arquivo, colocando capas e **placeholders de ROM** nas pastas de sistema corretas.
3. Após cada arquivo, a biblioteca é indexada — os jogos aparecem na UI progressivamente.
4. Jogos **não baixados** ficam marcados visualmente (badge ou estilo diferente no card).

### 3.2 Fase 2 — Download sob demanda de ROM individual

**Fluxo ao clicar em jogo NÃO baixado:**

```
Usuário clica no jogo
       ↓
Modal: imagem + título + "Deseja baixar este jogo?"
       ↓ [Sim]
Requisição ao endpoint REST →
  GET https://emuladores.pythonanywhere.com/find_by_file
      ?path=<nomeArquivo.ext>
      &source_id=1
      &system=<mnemonico_remoto>
       ↓
Endpoint retorna URL de download da ROM
       ↓
Download da ROM com barra de progresso
       ↓
Verifica integridade (tamanho esperado)
       ↓
Inicia o jogo automaticamente
```

**Fluxo ao clicar em jogo JÁ baixado:**

```
Usuário clica no jogo (tem badge de "baixado")
       ↓
Modal: imagem + título + [Jogar] [Deletar]
       ↓ [Jogar]          ↓ [Deletar]
Inicia o jogo        Dialog de confirmação
                          ↓ [Sim]
                     Apaga arquivo físico
                          ↓
                     Recria placeholder (0 bytes)
                     com mesmo nome e extensão
```

---

## 4. Catálogo de ROMs — Abordagem (DECISÃO PENDENTE)

Existem duas abordagens viáveis para representar o catálogo. Ambas foram discutidas e validadas tecnicamente.

---

### Opção A — Arquivos de 0 bytes no HuggingFace ✅ (upload já realizado)

**Como funciona:** O repositório HuggingFace `luisluis123/lemusets` contém os arquivos de ROM com 0 bytes. O `StreamingRomsManager` baixa esses placeholders nas pastas corretas. O scanner indexa os arquivos (incluindo os de 0 bytes, após ajuste no código). "Baixado" = tamanho do arquivo > 0.

**Prós:**
- Upload já feito — repositório pronto.
- Reutiliza 100% da infraestrutura do `StreamingRomsManager`.
- A estrutura de pastas por sistema já existe no HF (ex: `roms/nes/`, `roms/psx/`).
- Simples de entender: arquivo de 0 bytes = não baixado; arquivo com bytes = baixado.

**Contras:**
- O `StreamingRomsManager` atual tem um bug para este caso: na condição de skip ele testa `entry.size == 0L` — se o HF retornar tamanho 0, o arquivo seria pulado sem ser criado. Precisa de correção.
- O scanner (`DocumentFileParser`) filtra arquivos com `fileSize <= 0` — precisará ser ajustado para indexar placeholders.
- Capas e metadados (título correto, imagem de capa) vêm do banco LibretroDB já existente no app — OK para a maioria dos jogos, mas pode falhar para títulos não catalogados.
- Milhares de requisições HTTP para baixar arquivos de 0 bytes é ineficiente (embora o HF suporte bem).

**Ajustes necessários no código:**
1. `StreamingRomsManager.doStreamingDownload()` — corrigir condição de skip para não pular entradas com `entry.size == 0L`.
2. `DocumentFileParser` — permitir indexar arquivos com 0 bytes como jogos "não baixados".
3. `Game` entity — adicionar campo computed ou derivado para `isDownloaded` (verificar `fileSize` no filesystem).
4. `GameInteractor` — interceptar clique e checar se jogo está baixado antes de lançar.
5. `LemuroidGameCard` / `LemuroidGameListRow` — exibir badge nos jogos baixados.

---

### Opção B — Catálogo JSON centralizado (recomendada se quiser mais controle)

**Como funciona:** Um arquivo `catalog.json` é hospedado no HuggingFace (ou no endpoint próprio). O app baixa esse JSON na primeira execução e cria localmente os arquivos placeholder de 0 bytes com base na lista. A partir daí, o fluxo é igual ao da Opção A.

**Formato sugerido do `catalog.json`:**

```json
[
  {
    "fileName": "Super Mario World (USA).sfc",
    "system": "snes",
    "title": "Super Mario World",
    "coverUrl": "https://cdn.coverproject.org/...",
    "expectedSize": 524288
  }
]
```

**Prós:**
- Uma única requisição HTTP para obter todo o catálogo.
- Pode incluir metadados ricos (título correto, URL de capa específica, tamanho esperado para validação pós-download).
- Catálogo facilmente atualizável no servidor sem precisar fazer upload de arquivos no HF.
- `expectedSize` permite validar integridade após download sem precisar de hash.
- Independe do HuggingFace para hospedar arquivos dummy.

**Contras:**
- Requer geração e manutenção do `catalog.json` no servidor.
- Adiciona uma nova camada (parsing do JSON + criação local de stubs) que não existe hoje.
- Ligeiramente mais trabalho de implementação inicial.

**Ajustes necessários no código:**
1. Novo componente `CatalogManager` — baixa e parseia o `catalog.json`.
2. Cria arquivos stub de 0 bytes localmente com base no catálogo.
3. Mesmos ajustes 2-5 da Opção A.

---

### Recomendação

**Se o objetivo é velocidade de implementação:** use a **Opção A** (upload já feito, menor mudança no código).  
**Se o objetivo é qualidade e controle a longo prazo:** use a **Opção B** (catálogo JSON dá mais flexibilidade para atualizar títulos, capas e adicionar novas ROMs sem subir arquivos vazios no HF).

Uma abordagem híbrida viável: **usar a Opção A** agora, mas incluir o campo `expectedSize` no próprio nome de arquivo ou em um `manifest.json` lightweight por sistema (ex: `roms/nes/manifest.json`) para ter o tamanho esperado disponível sem um catálogo global.

---

## 5. Mapeamento de Sistemas — mnemonico_map

### Propósito

O endpoint REST `emuladores.pythonanywhere.com` usa nomes de sistema diferentes dos que o Lemuroid usa internamente. O mapa resolve essa diferença.

**Exemplo:**
- Lemuroid usa `systemId = "atari2600"`
- A pasta HuggingFace é `a26`
- O endpoint REST espera `system=atari2600`

Portanto: **chave = nome da pasta HF / systemId do Lemuroid**, **valor = nome que o endpoint remoto reconhece**.

### Mapa atual

| Pasta HF / systemId | Endpoint remoto |
|---|---|
| `a26` | `atari2600` |
| `a78` | `atari7800` |
| `lynx` | `lynx` |
| `nes` | `nes` |
| `snes` | `snes` |
| `gb` | `gb` |
| `gbc` | `gbc` |
| `gba` | `gba` |
| `megadrive` | `megadrive` |
| `megacd` | `megacd` |
| `sms` | `mastersystem` |
| `gg` | `gamegear` |
| `n64` | `n64` |
| `psx` | `psx` |
| `psp` | `psp` |
| `arcade` | `mame` |
| `nds` | `nds` |
| `pce` | `pcengine` |
| `ngp` | `ngp` |
| `ngc` | `neogeocd` |
| `ws` | `wswan` |
| `wsc` | `wswanc` |
| `3ds` | `3ds` |

### Decisão técnica: embutir no código

**Recomendação:** embutir diretamente no código Kotlin como constante, **não** como arquivo externo (JSON em `assets/`).

**Motivo:** o mapa tem apenas 23 entradas, raramente muda, e embutí-lo elimina a necessidade de I/O de arquivo em runtime, evita erros de leitura e simplifica o código. 

**Implementação sugerida:**

```kotlin
// Em um objeto companion ou object singleton, ex: RomSystemMapper.kt
object RomSystemMapper {
    /**
     * Maps HuggingFace folder names / Lemuroid systemId values
     * to the system names expected by the remote endpoint.
     */
    val SYSTEM_TO_ENDPOINT: Map<String, String> = mapOf(
        "a26"       to "atari2600",
        "a78"       to "atari7800",
        "lynx"      to "lynx",
        "nes"       to "nes",
        "snes"      to "snes",
        "gb"        to "gb",
        "gbc"       to "gbc",
        "gba"       to "gba",
        "megadrive" to "megadrive",
        "megacd"    to "megacd",
        "sms"       to "mastersystem",
        "gg"        to "gamegear",
        "n64"       to "n64",
        "psx"       to "psx",
        "psp"       to "psp",
        "arcade"    to "mame",
        "nds"       to "nds",
        "pce"       to "pcengine",
        "ngp"       to "ngp",
        "ngc"       to "neogeocd",
        "ws"        to "wswan",
        "wsc"       to "wswanc",
        "3ds"       to "3ds",
    )

    /** Returns the endpoint system name for a given Lemuroid systemId, or null if unknown. */
    fun toEndpointSystem(systemId: String): String? = SYSTEM_TO_ENDPOINT[systemId]
}
```

**Uso ao chamar o endpoint:**
```kotlin
val endpointSystem = RomSystemMapper.toEndpointSystem(game.systemId)
    ?: throw IllegalArgumentException("Sistema não mapeado: ${game.systemId}")
val url = "https://emuladores.pythonanywhere.com/find_by_file" +
    "?path=${Uri.encode(game.fileName)}&source_id=1&system=$endpointSystem"
```

---

## 6. Endpoint REST — Contrato

**URL:** `https://emuladores.pythonanywhere.com/find_by_file`

**Parâmetros:**

| Parâmetro | Valor | Exemplo |
|---|---|---|
| `path` | Nome do arquivo da ROM (URL-encoded) | `All%20Kamen%20Rider.3ds` |
| `source_id` | Sempre `1` | `1` |
| `system` | Nome do sistema no banco remoto (via `RomSystemMapper`) | `3ds` |

**Exemplo completo:**
```
GET https://emuladores.pythonanywhere.com/find_by_file
    ?path=All%20Kamen%20Rider%20-%20Rider%20Revolution%20(Japan).3ds
    &source_id=1
    &system=3ds
```

**Resposta:** Texto puro — URL de download direta. Exemplo:
```
https://archive.org/download/...
```

**Pontos ainda a validar com o servidor:**
- Comportamento quando a ROM não é encontrada — qual HTTP status? Corpo vazio? String especial?
- Rate limiting — quantas requisições por minuto são permitidas?

---

## 7. Mudanças Necessárias no Código (visão geral)

> Nenhuma mudança foi implementada ainda. Esta seção serve de guia para a fase de implementação.

### 7.1 Novo componente: `RomSystemMapper`
- Objeto Kotlin com o mapa de sistemas (ver Seção 5).

### 7.2 Ajustes no `StreamingRomsManager` (Opção A)
- Corrigir condição de skip: não pular entradas onde `entry.size == 0L`, para que os arquivos placeholder sejam criados no dispositivo.
- Criar arquivo de 0 bytes via `File.createNewFile()` quando o `entry.size == 0L` (não fazer requisição HTTP para arquivos vazios).

### 7.3 Ajustes no scanner de biblioteca
- `DocumentFileParser` — remover ou condicionar o filtro `fileSize <= 0` para que arquivos placeholder sejam indexados como jogos "não baixados".
- Possivelmente adicionar campo `fileSize: Long` ao `StorageFile` para propagar o tamanho até o banco.

### 7.4 Rastreamento de status de download (sem alterar a entity `Game`)

Decisão: **não** modificar a entity `Game` nem fazer migration do banco principal. Usar uma solução auxiliar.

Duas opções disponíveis (decisão pendente — ver Seção 8):

**Opção 4A — Tabela Room separada `DownloadedRom`:**
```kotlin
@Entity(tableName = "downloaded_roms")
data class DownloadedRom(
    @PrimaryKey val fileName: String,  // chave = fileName da ROM
    val fileSize: Long,                // tamanho real após download
    val downloadedAt: Long,            // timestamp
)
```
- Integra com o banco Room existente (`RetrogradeDatabase`).
- Totalmente reativo via Flow do DAO.
- Requer migration apenas no banco secundário/auxiliar, não no banco principal de Games.

**Opção 4B — SharedPreferences com Set de fileNames:**
```kotlin
// "downloaded_roms" prefs → Set<String> de fileNames baixados
prefs.getStringSet("downloaded_file_names", emptySet())
```
- Zero infraestrutura nova.
- Não é reativo nativamente (precisa de `MutableStateFlow` manual).
- Menos robusto para grandes catálogos.

**Recomendação:** Opção 4A — mais robusto, reativo e consistente com o padrão já usado no app.

### 7.5 Novo fluxo de clique no jogo: `GameInteractor`
- **Jogo não baixado (`fileSize == 0`):** exibir `RomDownloadDialog` (modal com capa + título + Sim/Não).
- **Jogo baixado:** lançar direto (mantém UX atual) — ou exibir `RomOptionsDialog` (Jogar / Deletar) no long press ou clique direto conforme decidido.

### 7.6 Novo componente: `RomDownloadService` (ou similar)
- Recebe `Game` como parâmetro.
- Chama endpoint REST para obter URL.
- Baixa ROM usando infraestrutura existente (`downloadFileWithResume`).
- Exibe barra de progresso ao usuário (Dialog ou Notification).
- Verifica integridade do arquivo após conclusão.
- Lança o jogo automaticamente ao terminar.

### 7.7 UI — Badge nos cards
- `LemuroidGameCard` e `LemuroidGameListRow` — adicionar parâmetro `isDownloaded: Boolean`.
- Exibir badge (ex: ícone de check verde ou borda colorida) quando `isDownloaded = true`.

### 7.8 UI — Modal de opções para jogo baixado
- Expandir `MainGameContextActions` com opção **Deletar**.
- Ao confirmar delete: apagar arquivo físico + recriar com 0 bytes + atualizar estado no banco.

---

## 8. Validação de Integridade da ROM

### Estratégia adotada: dupla camada

**Camada 1 — pós-download (simples):** arquivo > 0 bytes = download concluído. Suficiente para detectar downloads interrompidos.

**Camada 2 — detecção de corrupção em runtime:** se o emulador falhar ao carregar a ROM, o sistema tenta diagnosticar se a causa foi corrupção e oferece re-download automático ao usuário.

### Como funciona a detecção de corrupção

O `GLRetroView` (motor do emulador) já emite um código específico quando falha ao abrir o arquivo de jogo:

```
GLRetroView.ERROR_LOAD_GAME  →  GameLoaderError.LoadGame
```

O ponto de interceptação é o `GameLaunchTaskHandler`, que recebe o resultado quando a `GameActivity` fecha. O diagnóstico de corrupção é considerado provável quando **todas** as condições abaixo forem verdadeiras:

1. O erro retornado é `GameLoaderError.LoadGame`
2. O jogo está na tabela `DownloadedRom` (foi baixado por nós — não é uma ROM externa do usuário)
3. O BIOS necessário para o sistema está presente (descarta falso positivo por BIOS faltando)

### Fluxo

```
Emulador falha ao ler ROM (ERROR_LOAD_GAME) → GameActivity encerra com RESULT_ERROR
        ↓
GameLaunchTaskHandler: erro é LoadGame + jogo está em DownloadedRom + BIOS OK?
        ↓ Sim                                    ↓ Não
Dialog: "O arquivo pode estar              Exibe erro genérico normal
corrompido. Deseja refazer o download?"
        ↓ [Sim]               ↓ [Não]
Deleta ROM física         Exibe erro normal
Recria placeholder (0 bytes)
Inicia re-download via endpoint REST
Lança o jogo ao terminar
```

### Ponto de implementação no código

- **`GameLaunchTaskHandler`** — interceptar `RESULT_ERROR` com erro `LoadGame` e verificar `DownloadedRom`
- **Não** modificar o `GameLoader` nem o `handleRetroViewError` — a lógica fica na camada de apresentação, fora do processo do emulador

---

## 9. Pontos Ainda em Aberto

Nenhum ponto crítico pendente. Todas as decisões de arquitetura foram tomadas.

---

## 9. Análise de Riscos

| Risco | Probabilidade | Impacto | Mitigação |
|---|---|---|---|
| Endpoint `pythonanywhere.com` fora do ar | Média | Alto — usuário não consegue baixar nenhuma ROM | Mensagem de erro clara + retry automático |
| Rate limiting no endpoint | Baixa | Médio | Cache local do link de download por sessão |
| HuggingFace API fora do ar | Baixa | Alto — catálogo não carrega | Usar catálogo em cache local se já baixado anteriormente |
| HF retornar LFS pointer em vez de 0 bytes | **Descartado** — teste confirmou OID = blob vazio, download = 0 bytes reais | — | — |
| Arquivo placeholder deletado sem ser recriado | Baixa | Médio — jogo some do catálogo | Recriar placeholder ao detectar fileName faltando na verificação |
| Scanner não indexar arquivos de 0 bytes | Alta (bug conhecido) | Alto — nenhum jogo aparece na UI | Ajuste no `DocumentFileParser` (prioridade alta) |
