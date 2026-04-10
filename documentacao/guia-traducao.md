# Guia de Tradução — Retro Game System

> **Regra de ouro:** Todo texto visível ao usuário DEVE ser declarado como resource de string. Nunca coloque texto em português ou em qualquer idioma diretamente em arquivos Kotlin/XML de layout.

---

## Estrutura dos Arquivos de Strings

| Arquivo | Idioma | Módulo |
|---|---|---|
| `values/strings.xml` | **Inglês** (padrão — fallback universal) | cada módulo |
| `values-pt-rBR/strings.xml` | Português do Brasil | cada módulo |
| `values-pt-rPT/strings.xml` | Português de Portugal (Europeu) | cada módulo |

### Módulos com arquivos de strings

```
lemuroid-app/src/main/res/
retrograde-app-shared/src/main/res/
lemuroid-touchinput/src/main/res/
lemuroid-app-ext-free/src/main/res/
lemuroid-app-ext-play/src/main/res/
```

---

## Como Adicionar um Novo Texto

### Passo 1 — Adicionar o texto em inglês (fallback)

Abra `values/strings.xml` do módulo correto e adicione a entrada em **inglês**:

```xml
<!-- lemuroid-app/src/main/res/values/strings.xml -->
<string name="minha_chave_nova">My new text in English</string>
```

### Passo 2 — Adicionar a tradução em Português do Brasil

Abra `values-pt-rBR/strings.xml` e adicione a mesma chave traduzida:

```xml
<!-- lemuroid-app/src/main/res/values-pt-rBR/strings.xml -->
<string name="minha_chave_nova">Meu novo texto em português</string>
```

### Passo 3 — Adicionar a tradução em Português de Portugal

Abra `values-pt-rPT/strings.xml` e adicione com phrasing europeu quando aplicável:

```xml
<!-- lemuroid-app/src/main/res/values-pt-rPT/strings.xml -->
<string name="minha_chave_nova">O meu novo texto em português</string>
```

> **Diferenças comuns pt-BR vs pt-PT:**
> | pt-BR | pt-PT |
> |---|---|
> | arquivo | ficheiro |
> | baixar | descarregar |
> | download | descarregamento |
> | diretório | directório |
> | você | si / (omitido) |
> | Clique | Clique / Toque |
> | celular / móvel | telemóvel / móvel |

### Passo 4 — Usar o resource no código Kotlin

```kotlin
// Em Activity / Fragment com contexto
context.getString(R.string.minha_chave_nova)

// Em Composable
stringResource(id = R.string.minha_chave_nova)

// Com argumentos de formato
context.getString(R.string.minha_chave_com_arg, argumento)
```

Para declarar com argumento de formato no XML:

```xml
<!-- values/strings.xml -->
<string name="progresso_download">Downloading… %1$d%%</string>

<!-- values-pt-rBR/strings.xml -->
<string name="progresso_download">Baixando… %1$d%%</string>

<!-- values-pt-rPT/strings.xml -->
<string name="progresso_download">A descarregar… %1$d%%</string>
```

---

## Verificação de Completude (Auditoria)

Execute o script abaixo para detectar qualquer chave inglesa sem tradução:

```bash
python - <<'EOF'
import xml.etree.ElementTree as ET, os

modules = [
    'lemuroid-app',
    'retrograde-app-shared',
    'lemuroid-touchinput',
    'lemuroid-app-ext-free',
    'lemuroid-app-ext-play',
]
base = '.'   # raiz do projeto

for locale in ['pt-rBR', 'pt-rPT']:
    print(f'\n=== {locale} ===')
    total = 0
    for mod in modules:
        en_path  = f'{base}/{mod}/src/main/res/values/strings.xml'
        pt_path  = f'{base}/{mod}/src/main/res/values-{locale}/strings.xml'
        if not os.path.exists(en_path):
            continue
        en_keys = {s.attrib['name'] for s in ET.parse(en_path).findall('.//string')}
        pt_keys = {s.attrib['name'] for s in ET.parse(pt_path).findall('.//string')} if os.path.exists(pt_path) else set()
        missing = en_keys - pt_keys
        total += len(missing)
        status = 'OK' if not missing else f'{len(missing)} FALTANDO: {sorted(missing)}'
        print(f'  {mod}: {status}')
    print(f'  TOTAL FALTANDO: {total}')
EOF
```

---

## Regras de Nomenclatura de Chaves

| Prefixo | Uso |
|---|---|
| `title_` | Títulos de tela / abas principais |
| `settings_title_` | Título de um item de configuração |
| `settings_description_` | Subtítulo/descrição de configuração |
| `home_` | Textos da tela Home |
| `game_` | Textos relacionados a jogos em execução |
| `game_menu_` | Itens do menu in-game |
| `game_context_menu_` | Itens do menu de contexto do jogo |
| `game_loader_error_` | Mensagens de erro ao carregar jogo |
| `rom_download_` | Diálogos de download por demanda (ROM individual) |
| `home_streaming_` | Textos do download streaming (lote de ROMs) |
| `home_download_` | Textos do download do pacote ZIP de ROMs |
| `notification_` | Textos de notificações do sistema |
| `tv_` | Textos exclusivos da interface para TV |
| `dialog_` | Diálogos genéricos |
| `settings_` | Outros textos de configurações |

---

## Erros Comuns a Evitar

### ❌ ERRADO — texto hardcoded em Kotlin

```kotlin
// NUNCA faça isto:
Toast.makeText(context, "Download completo!", Toast.LENGTH_SHORT).show()

// NUNCA faça isto em inglês em código que também serve pt-BR:
binding.button.text = "Cancel"
```

### ✅ CERTO

```kotlin
Toast.makeText(context, getString(R.string.download_completo), Toast.LENGTH_SHORT).show()
binding.button.text = getString(R.string.cancel)
```

### ❌ ERRADO — texto em português no arquivo de fallback inglês

```xml
<!-- values/strings.xml — ERRADO, isto impede a tradução -->
<string name="home_streaming_roms_pause">Pausar</string>
```

### ✅ CERTO

```xml
<!-- values/strings.xml -->
<string name="home_streaming_roms_pause">Pause</string>

<!-- values-pt-rBR/strings.xml -->
<string name="home_streaming_roms_pause">Pausar</string>

<!-- values-pt-rPT/strings.xml -->
<string name="home_streaming_roms_pause">Pausar</string>
```

---

## Histórico de Auditorias

| Data | pt-BR faltando | pt-PT faltando | Ação |
|---|---|---|---|
| 2026-04-07 | 37 | 82 (+2 em retrograde-app-shared) | Corrigidos todos. 20+ strings em PT no arquivo EN foram movidas para os arquivos de tradução corretos. |
| _próxima auditoria_ | 0 | 0 | Manter com o script acima |
