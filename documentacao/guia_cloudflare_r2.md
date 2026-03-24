# Guia de Implementação: Cloudflare R2 para Hospedagem de ROMs

Este guia passo a passo ensinará como mover seus arquivos de grandes tamanhos (como o `romssnesnds.7z`) para a Cloudflare R2. O objetivo é servir as ROMs na velocidade máxima da infraestrutura de borda (edge) da Cloudflare sem gastar com transferência de dados (egress).

## Pré-requisitos
* Uma conta na [Cloudflare](https://dash.cloudflare.com/sign-up).
* Um cartão de crédito ou PayPal (apenas para validação e anti-spam. **Não haverá cobranças** desde que o armazenamento não ultrapasse os 10 GB gratuitos).
* O arquivo `.7z` das ROMs em sua máquina.

---

## Passo 1: Habiltar o R2
1. Vá para o painel de controle da Cloudflare: `dash.cloudflare.com`
2. No menu do lado esquerdo, navegue até a seção chamada **R2**.
3. A Cloudflare pedirá que você confirme um método de pagamento para "destravar" o plano gratuito. Essa etapa é um procedimento anti-abuso. Preencha e confirme. Você terá direito ao plano gratuito: 10 GB/mês de armazenamento e 10 milhões de requisições de leitura (Egress/Tráfego de download é ilimitado e grátis).

## Passo 2: Criar o Bucket
No jargão de nuvem, "Bucket" é a pasta/repositório principal onde os arquivos vivem.
1. Dentro do painel R2, clique no botão azul **Create bucket** (Criar bucket).
2. Dê um nome ao bucket (ex: `lemuroid-assets` ou `lemuroid-roms`). Note que este nome precisa ser único.
3. Para localização (Jurisdiction), pode deixar `Automatic`.
4. Clique em **Create bucket**.

## Passo 3: Fazer Upload do Arquivo
Existem duas formas de subir seu arquivo `romssnesnds.7z`:
* **Pelo próprio painel (Navegador):** Se o arquivo for menor que o limite do upload web (geralmente em torno de 300MB), você pode simplesmente arrastar o arquivo para dentro da página no seu recém-criado Bucket.
* **Via API S3 ou Cyberduck (Para arquivos gigantes):** Como o R2 é 100% compatível com a API da Amazon S3, se o arquivo for gigante (ex: 3GB+), você pode baixar um programa de upload como o **Cyberduck**, usar as credenciais API fornecidas pela Cloudflare ("Manage R2 API Tokens") e fazer o upload do `.7z` por ele sem que o upload "congele" no navegador.

## Passo 4: Habilitar o Acesso Público (Muito Importante!)
Por padrão, os arquivos armazenados no R2 são 100% privados e bloqueados para a internet. Para que o aplicativo Lemuroid consiga baixá-los livremente:
1. Abra o seu Bucket no painel R2.
2. Na parte superior, vá até a aba **Settings** (Configurações).
3. Desça até encontrar a seção **Public Access** (Acesso Público).

Aqui você tem duas opções de distribuição pública:
1. **r2.dev subdomínio (Menos recomendado):** A Cloudflare te dará um domínio feio gerenciado por eles (`https://pub-xxxxxx.r2.dev`). Você pode clicar em "Permitir Acesso" aqui. No entanto, esses domínios de desenvolvimento são estritamente limitados a requisições de baixo volume (rate-limited) e não recebem cache intensivo na borda (edge).
2. **Custom Domains (Recomendado e Profissional):** Se você gerencia um domínio ou subdomínio (ex: `roms.meusite.com`) com a Cloudflare, você clica na opção "Connect Domain". Escolha o seu domínio/subdomínio, e a Cloudflare ligará todo o tráfego cacheado da internet a este bucket. Esta opção libera 100% da velocidade massiva da CDN.

## Passo 5: Atualizar o Lemuroid
Depois de público, você terá um link final para o seu arquivo, que será algo parecido com:  
`https://roms.seusite.com/romssnesnds.7z` ou `https://pub-xyz123.r2.dev/romssnesnds.7z`

Para alterar isso no aplicativo:
1. Abra o arquivo no Android Studio: 
   `e:\projects\lemuroid\Lemuroid\lemuroid-app\src\main\java\com\swordfish\lemuroid\app\shared\roms\RomsDownloadManager.kt`
2. Perto da linha 278, modifique a variável do download apontando para a nova URL hosteada no Cloudflare:

```kotlin
// ANTES (HuggingFace CDN):
val downloadUrl = "https://huggingface.co/datasets/Emuladores/sets/resolve/main/romssnesnds.7z?download=true"

// DEPOIS (Novo link rápido do R2):
val downloadUrl = "https://seu-dominio-publico.com/romssnesnds.7z"
```
3. Compile e rode o app. 

### Finalizando
Quando você iniciar o download da ROM no Lemuroid, a requisição sairá do seu celular direto pro datacenter/edge da Cloudflare mais perto de você fisicamente (Ex: Rio ou São Paulo). Como o Cloudflare abraça o recurso de HTTP Range perfeitamente (`Accept-Ranges: bytes`), o `downloadFileParallel` do Kotlin que está configurado com 4 conexões vai quebrar e puxar os pedaços violentamente rápido da CDN.
