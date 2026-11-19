# [BUG] Marvel vs. Capcom 2 (Dreamcast): apenas a versão japonesa abre, mas roda sem áudio e sem responder ao Start; outras 3 versões não rodam

**Data:** 2026-11-18
**Severidade:** media — título principal de luta (Dreamcast/Arcade) fica injogável no app Android sem controle físico nem áudio
**Complexidade:** media — depende de reprodução para separar bug no core Flycast (`dc/`), ROMs no acervo (`catalog_manifest.txt`), ou mapeamento/áudio DSP no `GameSystem.kt` / `Dreamcast.kt`
**Branch:** version10
**Origem:** triagem do chamado #284 (sessão `197637248663793@lid`, app `1.17.27`, celular Xiaomi Android)

---

## Sintoma

Cliente instalou `retro-game-system-arm64.apk` (`v1.17.27`) num celular Xiaomi Android (`2026-10-27`) e baixou as 4 versões de **Marvel vs. Capcom 2** encontradas no catálogo:

1. Três das quatro versões não iniciaram/não funcionaram.
2. Apenas a versão **Japão** (`dc/Marvel vs. Capcom 2 - New Age of Heroes (Japan).chd`, tela com o cenário da água e `"PRESS START BUTTON"`) abriu, porém **sem áudio** e **sem responder a nenhum botão da tela** (inclusive Start).

*(Nota: no mesmo chamado `#284`, o cliente também relatou `Marvel vs. Capcom 1` com apenas 4 botões na tela em vez de 6 — esse defeito já foi corrigido em `240196f` / `1.17.28` e está em `docs/bugs/no-ar/2026-10-27-cps-arcade-layout-padrao-4-botoes-em-vez-de-6/2026-10-27-cps-arcade-layout-padrao-4-botoes-em-vez-de-6.md`.)*

---

## Evidência

### Conversa (`Wpp_proccess`, UTC)

- `[156918]` 2026-10-27 20:16 customer: print `"Verificação de segurança Xiaomi"`, app `"Retro Game System" (versão 1.17.27, tamanho 117,6 MB)`.
- `[156964]` 20:30 customer: *"O 2 é que não tem como iniciar, rodar ele roda mas não quer dá start"*
- `[156974]` 20:33 customer: `[IMAGEM] Ele fica rodando sem áudio e pedindo start` — tela do jogo mostrando cenário com água e `"PRESS START BUTTON"` com controles virtuais na parte inferior (tela título de *Marvel vs. Capcom 2* de Dreamcast).
- `[156978]` 20:36 customer: *"Eu aperto tudo e não funciona nada"*
- `[156989]` 20:41 customer: *"Tentei aqui nada"* (após fechar e reabrir o app e testar cada botão da tela).
- `[156991]` 20:43 customer: *"Eu baixei as 4 versões que tem e só uma do Japão que funcionou sem áudio e sem como dá o start"*
- `[156997]` 20:56 customer: *"Agora no Marvel 2 o único que rodou ficou sem áudio e sem como dá start"*

### Código e catálogo verificados

- **Catálogo (`lemuroid-app/src/main/assets/catalog_manifest.txt`):** contém entradas para `dc/Marvel vs. Capcom 2 (USA).chd`, `dc/Marvel vs. Capcom 2 - New Age of Heroes (Europe).chd`, `dc/Marvel vs. Capcom 2 - New Age of Heroes (Japan).chd`, `dc/Marvel vs. Capcom 2 v1.001 (2000)(Capcom)(NTSC)(US)[!].chd` e `mame289/mvsc2.zip` (além da versão `dc/` que abre a tela com água e `"PRESS START BUTTON"`).
- **Layout touch do Dreamcast (`lemuroid-touchinput/.../layouts/Dreamcast.kt`):**
  - `DreamcastRight` inclui `SecondaryButtonStart(position = 2)` (`Controls.kt:87-93`, envia `KeyEvent.KEYCODE_BUTTON_START`).
  - `DreamcastLeft` declara `SecondaryButtonSelect(position = 2)` (`KeyEvent.KEYCODE_BUTTON_SELECT`), com comentário `// Start here instead of Select — DC has no Select button` (`Dreamcast.kt:50-51`).
- **Configuração Flycast (`GameSystem.kt`, bloco `SystemID.DREAMCAST`):** `reicast_hle_bios = "disabled"`, `reicast_cable_type = "TV (RGB)"`, `reicast_enable_dsp = "disabled"`, `reicast_threaded_rendering = "enabled"`.
- **Telemetria (`errors`):** nenhuma entrada de erro para `Marvel vs. Capcom 2` / `mvsc2` em `retrogamesystem%`.
- **Diferença de `docs/bugs/aberto/2026-07-08-dreamcast-subset-jogos-travam-9fps/2026-07-08-dreamcast-subset-jogos-travam-9fps.md`:** lá os jogos travavam cedo no boot (preto ou licença SEGA) a ~9,8 fps; aqui a versão Japan entra na tela de título animada (`PRESS START BUTTON`), porém sem áudio e sem aceitar Start/toque.

---

## Hipóteses — nenhuma verificada

Sem reprodução no aparelho/emulador com os 4 arquivos baixados do catálogo:

1. **Flycast / MvC2 (Dreamcast):** `reicast_enable_dsp = "disabled"` ou problema específico do core Flycast / porta de controle / CHD na variante Japan explica áudio ausente ou entrada ignorada; e as versões USA/Europe de `dc/` podem estar sofrendo o hang de boot do Flycast (como em `2026-07-08-dreamcast-subset-jogos-travam-9fps`) ou falha de arquivo no servidor.
2. **`mame289/mvsc2.zip` (Naomi no MAME 0.289):** pode falhar por dependência de BIOS/CHD (`naomi.zip` / `mvsc2` CHD) ausente no catálogo ou incompatibilidade no `mamearcade` no celular.
3. **Toque rápido (`input tap`) / sobreposição no botão Start:** conferir se `SecondaryButtonStart` em `DreamcastRight` entrega `KEYCODE_BUTTON_START` ao Flycast na porta 0 e se `DreamcastLeft` estar com `SecondaryButtonSelect` em vez de `Start` confunde o usuário.

---

## O que fazer

1. Baixar pelo catálogo atual (`1.17.36`) as entradas de `Marvel vs. Capcom 2` (`dc/` USA, Europe, Japan e `mame289/mvsc2.zip`) em aparelho arm64 e registrar o comportamento de cada uma com `adb logcat` (boot, áudio, FPS, resposta do botão Start no toque).
2. Se `dc/Marvel vs. Capcom 2 (Japan)` reproduzir sem áudio / sem Start, testar `reicast_enable_dsp = "enabled"` e verificar mapeamento de porta 0 / `KEYCODE_BUTTON_START` no Flycast.
