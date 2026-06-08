# Canais de Catálogo e Atualização

## Objetivo

Permitir gerar APKs com catálogos diferentes, por exemplo um APK com `catalog_manifest.txt` completo e outro com um manifest reduzido ou temático, mantendo um fluxo de atualização próprio para cada edição.

O canal controla três coisas:

| Item | Como é definido | Efeito |
|---|---|---|
| Catálogo embarcado | `-PcatalogManifest=<arquivo>` | Arquivo usado pelo app em runtime e pelo `retrograde-prebuilt.db`. |
| Canal de update | `-PappUpdateChannel=<canal>` ou `-PcatalogChannel=<canal>` | Endpoint consultado pelo auto-update. |
| Identidade do app | `-PcatalogApplicationIdSuffix=.sufixo` | Decide se instala lado a lado ou substitui o app existente. |

---

## Parâmetros de Build

| Propriedade | Padrão | Descrição |
|---|---|---|
| `catalogChannel` | `default` | Nome lógico da edição/catálogo. |
| `catalogManifest` | `lemuroid-app/src/main/assets/catalog_manifest.txt` | Manifest alternativo usado para gerar o DB prebuilt e empacotado como asset. |
| `appUpdateChannel` | valor de `catalogChannel` | Canal consultado pelo update. |
| `appUpdateBaseUrl` | `https://emuladores.pythonanywhere.com/app_version` | Base para montar `base/<channel>`. |
| `appUpdateEndpoint` | base ou `base/<channel>` | Endpoint exato, caso precise sobrescrever. |
| `catalogApplicationIdSuffix` | vazio | Sufixo opcional do `applicationId`, como `.ps2` ou `.arcade`. |

Esses valores viram `BuildConfig`:

| BuildConfig | Exemplo |
|---|---|
| `CATALOG_CHANNEL` | `ps2` |
| `CATALOG_MANIFEST_ASSET` | `catalog_manifest_ps2.txt` |
| `APP_UPDATE_CHANNEL` | `ps2` |
| `APP_UPDATE_ENDPOINT` | `https://emuladores.pythonanywhere.com/app_version/ps2` |

---

## Build com Script

Exemplo para gerar uma edição `ps2`:

```powershell
.\build_apk.ps1 `
  -CatalogChannel ps2 `
  -CatalogManifest E:\fetchimagers\catalog_manifest_ps2.txt
```

Isso gera `dist/retro-game-system-ps2.apk` e configura o app para consultar:

```text
https://emuladores.pythonanywhere.com/app_version/ps2
```

Para instalar lado a lado com a edição principal:

```powershell
.\build_apk.ps1 `
  -CatalogChannel ps2 `
  -CatalogManifest E:\fetchimagers\catalog_manifest_ps2.txt `
  -CatalogApplicationIdSuffix .ps2
```

Nesse caso o `applicationId` vira:

```text
app.retrogamesystem.ps2
```

---

## Build Direto pelo Gradle

```powershell
.\gradlew.bat :lemuroid-app:assembleFreeBundleRelease `
  -PcatalogChannel=ps2 `
  -PcatalogManifest=E:\fetchimagers\catalog_manifest_ps2.txt
```

Com endpoint explícito:

```powershell
.\gradlew.bat :lemuroid-app:assembleFreeBundleRelease `
  -PcatalogChannel=ps2 `
  -PcatalogManifest=E:\fetchimagers\catalog_manifest_ps2.txt `
  -PappUpdateEndpoint=https://cdn.exemplo.com/updates/ps2.json
```

---

## Endpoint de Update

Cada canal deve ter seu próprio JSON de versão.

Exemplo para canal `ps2`:

```http
GET https://emuladores.pythonanywhere.com/app_version/ps2
```

Resposta:

```json
{
  "channel": "ps2",
  "versionCode": 232,
  "versionName": "1.18.0-ps2",
  "apkUrl": "https://emuladores.pythonanywhere.com/static/apks/retro-game-system-ps2-v1.18.0.apk"
}
```

Regras:

- `versionCode` precisa ser maior que o `BuildConfig.VERSION_CODE` instalado.
- `apkUrl` precisa ser URL direta para o `.apk`, sem página HTML intermediária.
- O APK precisa ter o mesmo `applicationId` do app instalado.
- O APK precisa estar assinado com a mesma chave.
- Se o JSON tiver `channel`, ele precisa bater com `BuildConfig.APP_UPDATE_CHANNEL`; caso contrário, o app recusa o update.

O campo `channel` é opcional para compatibilidade, mas recomendado para evitar que uma edição baixe APK de outra edição por erro de servidor.

---

## Sem `CatalogApplicationIdSuffix`

Quando `catalogApplicationIdSuffix` não é informado, todas as edições usam o mesmo `applicationId`:

```text
app.retrogamesystem
```

Consequências:

- Uma edição substitui a outra no Android.
- Não é possível instalar lado a lado.
- O update automático funciona entre edições, desde que assinatura e `applicationId` sejam iguais.
- Dados locais são preservados, incluindo banco, saves, states, preferências e ROMs baixadas.

Esse modo é adequado para trocar a linha oficial do app para outro catálogo ou manter apenas uma edição instalada por vez.

Cuidados:

- O banco local não é apagado automaticamente quando o canal muda.
- O `ManifestQuickLoader` insere e atualiza entradas do novo manifest, mas não remove jogos que só existiam no catálogo anterior.
- Se a intenção é trocar completamente de catálogo, avalie implementar uma limpeza controlada por `CATALOG_CHANNEL` salvo em SharedPreferences ou usar `CatalogApplicationIdSuffix`.

Exemplo:

```powershell
.\build_apk.ps1 `
  -CatalogChannel arcade `
  -CatalogManifest E:\catalogos\catalog_manifest_arcade.txt
```

Esse APK substitui qualquer instalação anterior de `app.retrogamesystem` e passa a consultar:

```text
https://emuladores.pythonanywhere.com/app_version/arcade
```

---

## Com `CatalogApplicationIdSuffix`

Quando o suffix é informado, cada edição vira um aplicativo separado:

```powershell
.\build_apk.ps1 `
  -CatalogChannel arcade `
  -CatalogManifest E:\catalogos\catalog_manifest_arcade.txt `
  -CatalogApplicationIdSuffix .arcade
```

Resultado:

```text
app.retrogamesystem.arcade
```

Consequências:

- Instala lado a lado com a edição principal.
- Banco, saves, states, ROMs e preferências ficam separados por `applicationId`.
- O update dessa edição só aceita APKs assinados iguais e com o mesmo `applicationId` com suffix.
- O endpoint do canal continua separado, por exemplo `/app_version/arcade`.

Esse modo é recomendado para edições independentes, testes públicos, builds temáticos ou catálogos que não devem misturar banco/dados.

---

## Como o Catálogo é Empacotado

Quando `catalogManifest` é informado:

1. O Gradle copia o arquivo para um asset gerado, por exemplo `catalog_manifest_ps2.txt`.
2. `CATALOG_MANIFEST_ASSET` aponta para esse asset.
3. `CatalogCoverProvider` lê esse asset em runtime.
4. `StreamingRomsManager` usa esse asset para placeholders.
5. `generatePrebuiltDb` usa o mesmo manifest para gerar `retrograde-prebuilt.db`.

Isso evita drift entre o catálogo lido pelo app e o banco prebuilt empacotado no APK.

---

## Checklist de Publicação por Canal

1. Gere o manifest do canal, por exemplo `catalog_manifest_ps2.txt`.
2. Build o APK com `-CatalogChannel` e `-CatalogManifest`.
3. Publique o APK em uma URL HTTPS direta.
4. Atualize o JSON do canal com `channel`, `versionCode`, `versionName` e `apkUrl`.
5. Confirme que o `versionCode` é maior que o instalado naquele canal.
6. Confirme que a assinatura é a mesma da instalação anterior daquele `applicationId`.
7. Teste o botão `Verificar atualizações` no app instalado.

---

## Exemplos de Layout no Servidor

```text
/app_version
/app_version/ps2
/app_version/arcade
/static/apks/retro-game-system-v1.18.0.apk
/static/apks/retro-game-system-ps2-v1.18.0.apk
/static/apks/retro-game-system-arcade-v1.18.0.apk
```

Cada endpoint pode apontar para um APK diferente, desde que respeite o `applicationId` esperado por aquela instalação.