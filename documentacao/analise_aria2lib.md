# Análise de Implementação do Aria2Lib no Lemuroid

## 1. O que é o Aria2Lib
[aria2lib (devgianlu)](https://github.com/devgianlu/aria2lib) é uma biblioteca (wrapper) para Android que permite a execução do **aria2** — um utilitário de download em linha de comando leve e multiprotocolo, incrivelmente rápido e conhecido nativamente por otimizar conexões baixando arquivos em múltiplos segmentos de forma muito agressiva.

## 2. Como Lemuroid faz downloads atualmente vs. Aria2
Atualmente, no arquivo `RomsDownloadManager.kt`, o Lemuroid possui uma implementação **própria e muito robusta** de download paralelo utilizando `OkHttp` e Kotlin Coroutines. Ele já divide o arquivo em 4 blocos (`numSegments = 4`) usando HTTP Range Requests (`Accept-Ranges: bytes`) e baixa as partes paralelamente, costurando no final.
O `aria2c` realiza esse mesmo conceito, mas em nível de C++ nativo e de forma mais autônoma, lidando com protocolos como FTP, BitTorrent ou Metalink (o que não parece ser um requisito atual do app, já que hoje baixa de um link direto HTTP/HTTPS do HuggingFace).

## 3. Como seria implementar no Lemuroid
Para integrá-lo, seria necessário:
1. **Adicionar a dependência:** Incluir o `aria2lib` (ou inseri-lo como submódulo) e garantir a compilação do binário C/C++ via NDK (`libaria2c.so`).
2. **Substituir o Service Helper:** O `RomsDownloadManager.kt` deixaria de usar `OkHttpClient` e passaria a usar a interface do `aria2lib`, iniciando um serviço/daemon do aria.
3. **Monitorar Progresso:** Para informar o progresso ao WorkManager (`RomsDownloadWork`), teríamos que assinar eventos RPC ou ler a saída/logger do binário `aria2c` do Aria2Lib, convertendo isso para `DownloadRomsState.Downloading(progress)`.
4. **Permissões & WorkManager:** O aria2c executaria sob uma thread persistente, o que requeriria ajustes de ciclo de vida com o `androidx.work` para que o processo filho do `aria2c` não fosse encerrado pelo sistema operacional.

## 4. O quanto daria de velocidade? (Ganho real)
**Pouco ou nenhum ganho substancial em links comuns.**
Como o Lemuroid **já faz** download paralelo em múltiplos chunks (`downloadFileParallel`), a "mágica" em que o aria2 é mais forte já está, de certa forma, ocorrendo hoje via `OkHttp`. O gargalo real para a velocidade dos downloads raramente é a sobrecarga de parsing em Kotlin vs C++, mas sim:
- A banda real de internet do usuário móvel.
- O rate-limit de conexões ou limite de banda do CDN hospedando o arquivo (atualmente, huggingface.co).

O *aria2* seria mais vantajoso caso precisássemos baixar via BitTorrent ou se precisássemos usar 10+ conexões longas e contornar restrições pesadas de TCP. Além disso, downloads Mobile frequentemente encaram instabilidades de rádio; a lógica coroutine de _retry backoff_ do Lemuroid já lida otimamente com conexões caindo e retomando partes exatas.

## 5. Viabilidade e Complexidade
- **Viabilidade:** Baixa a Média.
- **Complexidade:** Alta.
Integrar a biblioteca significa rodar um executável binário como subprocesso de outro aplicativo no ambiente contido (sandbox) do Android.

## 6. Possíveis Problemas (Trade-offs e Riscos)
1. **Restrições de Execução (W^X) no Android 10+:** O Android moderno impede rigorosamente executar arquivos binários soltos dentro da `data/data` do app (políticas do SELinux). Para contornar, a biblioteca usa a flag `extractNativeLibs` para extrair isso como uma biblioteca `.so` que é forçosamente executada. O Google Play tem políticas de segurança muito enrijecidas sobre processos paralelos.
2. **Aumento do tamanho do APK (Bloat):** O executável precisaria ser compilado ativamente nas 4 arquiteturas Android (arm64-v8a, armeabi-v7a, x86, x86_64), jogando o tamanho do APK para cima (+5MB a 12MB apenas para o binário de download).
3. **Abandono / Manutenção:** A biblioteca `devgianlu/aria2lib` não é atualizada ativamente pelas vias convencionais, gerando risco tecnológico no longo prazo se o SDK alvo do Android exigir mudanças para compatibilidade de permissões de armazenamento moderno.
4. **Falhas Silenciosas em Background:** Pode ser muito mais imprevisível capturar erros de rede dentro de um executável C de terceiro do que dentro de um escopo try-catch do Kotlin `OkHttp`.

## 7. Overview (Conclusão)
A ideia de usar o aria2 via **aria2lib** parece tentadora pela fama do utilitário em desktops, mas a **relação custo-benefício** no código atual do Lemuroid é muito ruim. O aplicativo já implementa multipart ranges via `OkHttp` de forma brilhante, mantendo 100% de controle nativo na linguagem Kotlin (sem sujar o NDK), podendo gerenciar a UI pelo `WorkManager` e lidando com falhas permanentemente atrelado aos Intents de sistema. A migração traria risco técnico altíssimo, dor de cabeça com SELinux em Androids mais novos, aumento de APK sem quase nenhum ganho perceptível de velocidade (pois o gargalo no app é o tráfego do servidor externo). A melhoria no download viria muito mais fácil aumentando o `numSegments` atual no `RomsDownloadManager` ou encontrando um host/CDN mais robusto do que utilizando um core em C++.
