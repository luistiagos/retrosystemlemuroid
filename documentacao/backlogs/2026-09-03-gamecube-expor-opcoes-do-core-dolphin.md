# [BACKLOG] GameCube: expor opções do core Dolphin no menu do jogo

**Data:** 2026-09-03
**Origem:** observado durante a validação em device dos bugs abertos (Moto G86 5G, *Need for
Speed - Underground 2*)
**Tipo:** feature (não é bug — nada quebra, é ausência de controle)
**Esforço:** pequeno — só Kotlin + strings, **sem rebuild de AAR**

---

## O que acontece hoje

O menu do jogo em GameCube **não tem o item "Configurações"**. Comparação feita no aparelho,
mesma sessão:

| Sistema | Itens do menu do jogo |
|---|---|
| Dreamcast (flycast) | Salvar, Carregar, Sair, Reiniciar, Silenciar, Acelerar, Editar Controles, **Configurações**, Inclinar sensor |
| GameCube (dolphin) | Salvar, Carregar, Sair, Reiniciar, Silenciar, Acelerar, Editar Controles, Inclinar sensor |

Ou seja: quem joga GameCube não tem **nenhuma** opção de núcleo — nem resolução interna, nem
widescreen, nem proporção.

## Por quê

[GameSystem.kt:2606-2620](../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/GameSystem.kt#L2606-L2620)
— o `SystemCoreConfig` do Dolphin passa só `coreID`, `controllerConfigs`, `rumbleSupported`,
`statesSupported` e `skipDuplicateFrames`:

```kotlin
SystemCoreConfig(
    CoreID.DOLPHIN,
    // Dolphin boots GameCube titles with an HLE IPL, so no BIOS file
    // is required (unlike Dreamcast/3DO).
    controllerConfigs = hashMapOf( /* … */ ),
    rumbleSupported = true,
    statesSupported = true,
    skipDuplicateFrames = false,
),
```

`exposedSettings` e `exposedAdvancedSettings` não são passados e caem no default
`listOf()` de [SystemCoreConfig.kt:10-11](../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/SystemCoreConfig.kt#L10-L11).
Lista vazia ⇒ `displayOptionsDialog` monta um menu sem opções ⇒ o item some.

## O que o core realmente declara

Conferido com `grep -a -o -E "dolphin_[a-z0-9_]+"` direto no
`lemuroid-cores/bundled-cores/src/main/jniLibs/arm64-v8a/dolphin_libretro_android.so`
empacotado — **não** copiado de documentação. Chaves presentes e relevantes:

| Chave | Para quê |
|---|---|
| `dolphin_efb_scale` | resolução interna (o análogo do `reicast_internal_resolution`) |
| `dolphin_widescreen` | 16:9 nativo em jogos que suportam |
| `dolphin_widescreen_hack` | força 16:9 abrindo o frustum, em jogos que não suportam |
| `dolphin_aspect_ratio` | proporção reportada pelo core |
| `dolphin_crop_overscan` | corte de overscan |
| `dolphin_cpu_core` | interpretador × JIT |
| `dolphin_shader_compilation_mode` | atenua o engasgo de compilação de shader |
| `dolphin_progressive_scan`, `dolphin_language` | menores, provavelmente avançadas |

`dolphin_texture_filtering` **não existe** neste binário — exemplo do porquê da conferência.

## O que fazer

1. Preencher `exposedSettings` (resolução, widescreen) e `exposedAdvancedSettings` (o resto)
   no bloco GAMECUBE de `GameSystem.kt`.
2. Adicionar as strings de rótulo em
   `retrograde-app-shared/src/main/res/values/strings.xml` **e** nos locais do projeto
   (`values-pt-rBR`, `values-pt-rPT`) — ver a lição abaixo.
3. Validar abrindo um jogo de GameCube no aparelho e conferindo que cada opção aparece
   **e** que trocar o valor faz efeito.

## Duas armadilhas que já custaram caro neste projeto

**Chave errada não dá erro — a opção simplesmente some.** `transformExposedSetting` faz
`coreOptions.firstOrNull { it.variable.key == exposedSetting.key }` e devolve `null` quando não
casa. Nada é logado. O mesmo vale para os **valores**: uma `ExposedSetting.Value` cujo texto não
bate exatamente com o que o core declara não seleciona nada. Os valores não saem do `grep` no
`.so` de forma confiável (a tabela de chaves fica separada dos rótulos) — o jeito seguro é
logar `retroGameView.getVariables()` em `BaseGameActivity.getCoreOptions()` com um jogo de GC
aberto e copiar dali.

**Rótulo sem tradução aparece em inglês num app em português.** Aconteceu em 2026-09-03 com
`setting_citra_use_hw_shaders`, que só existia em `values/strings.xml`; só apareceu abrindo o
menu no aparelho — compilar e conferir a chave contra o `.so` não pega isso.

## Relação com outras páginas

- [bugs/done/2026-08-20-tela-cheia-proporcao-aspecto.md](../bugs/done/2026-08-20-tela-cheia-proporcao-aspecto.md)
  — este item estava lá como "escopo cortado". A feature de proporção de tela encaixa a imagem
  **depois** de renderizada, então alargar sem distorcer só é possível fazendo o core renderizar
  mais cena: é exatamente o que `dolphin_widescreen_hack` faz.
- [funcionalidades/widescreen-cores-3d.md](../funcionalidades/widescreen-cores-3d.md) — o mesmo
  desenho já aplicado ao Flycast (`reicast_widescreen_hack` + `reicast_widescreen_cheats`),
  serve de modelo direto.
