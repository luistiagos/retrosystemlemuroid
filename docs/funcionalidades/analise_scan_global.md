# Análise Técnica: Scan Global de ROMs no Lemuroid

Esta funcionalidade propõe que o aplicativo localize automaticamente jogos em qualquer lugar do dispositivo, sem exigir que o usuário selecione uma pasta específica. Abaixo está a análise de viabilidade, complexidade e performance.

## 1. Viabilidade Técnica

### Versões Antigas (Android 10 ou inferior)
É **totalmente viável** e simples, pois o aplicativo já possui a permissão `READ_EXTERNAL_STORAGE`. Basta iniciar a varredura a partir da raiz `/storage/emulated/0`.

### Versões Modernas (Android 11, 12, 13, 14+)
Devido ao **Scoped Storage**, o acesso direto a pastas como `Android/data`, `Download` ou a raiz do sistema é bloqueado. Tentamos duas rotas:
- **Rota B (API do Sistema/MediaStore):** O Android mantém um índice de todos os arquivos no dispositivo. Tentou-se perguntar ao MediaStore: *"Onde estão todos os arquivos com extensão .gba ou .smc?"*. **Resultado:** Falha. O Android ativamente omite qualquer coisa que não seja áudio, vídeo ou imagem da query baseada na permissão tradicional, para prevenir espionagem de documentos.
- **Rota A (Permissão Especial):** A única solução provada viável é solicitar `MANAGE_EXTERNAL_STORAGE` (Permitir gerenciamento de todos os arquivos). O Lemuroid solicitará acesso total ao disco visando percorrer programmaticamente usando `File(Environment.getExternalStorageDirectory())`.

## 2. Complexidade de Implementação

- **Complexidade Média-Alta:** Será necessário implementar um `AllFilesStorageProvider` em Kotlin usando fluxo assíncrono para caminhar o sistema de arquivos recursivamente.
- **Cuidado com UI:** O app precisará lidar com o intent `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` se o usuário tiver `Android 11+`.

## 3. Performance (Tempo de Operação)

| Método de Scan | Tempo Estimado | Impacto na Bateria | Experiência do Usuário |
|---|---|---|---|
| **Manual Force (Recursivo)** | **3 a 10 Segundos** | Médio | Muito boa (Rápido em aparelhos modernos, com ressalvas a discos longos) |
| **MediaStore (Índice)** | **Descartado** | - | Não acessa extensões não-mídia |

## Conclusão

Após os insucessos em tempo de execução das restrições do MediaStore, a funcionalidade é implementável de forma robusta e definitiva via **MANAGE_EXTERNAL_STORAGE** (`AllFilesStorageProvider`).

### Vantagens:
- **Indexação Genuína:** Qualquer .rom do usuário na pasta de `Downloads` e periféricos será descoberta infalivelmente.
- **Zero configuração:** O usuário apenas autoriza caso utilize esta chave especial, não precisa apontar diretórios específicos.

### Desafios:
- **Publicação:** Risco na Play Store.
- **Autorização Complexa do App:** Lidar com a tela de permissão customizada introduzida no Android 11 de forma elegante na parte de `Configurações` sem travar o usuário.

**Veredito:** A estratégia foi redirecionada para a Busca Recursiva em Arquivos usando a Permissão de Geenciamento de Todos os Arquivos do Android. A versão anterior (`MediaStore`) será descartada do fork corrente.
