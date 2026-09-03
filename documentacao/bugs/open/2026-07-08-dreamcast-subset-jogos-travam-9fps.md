# [BUG] Dreamcast: subconjunto de jogos trava cedo no boot a ~9,8fps

**Data:** 2026-07-08
**Status:** Mitigado — títulos quebrados removidos do catálogo (v23); causa-raiz no core segue aberta
**Severidade:** Média (maioria dos jogos roda a 60fps; um subconjunto trava)
**Branch:** version9

---

## Sintoma

Após o fix que fez o Dreamcast funcionar ([[2026-07-07-dreamcast-crash-boot-ashmem-libandroid]]),
a **maioria** dos jogos roda a 60fps, mas um **subconjunto trava cedo no boot** — preto,
ou parado na tela de licença SEGA — sempre marcando **exatamente ~9,8fps** (9,83 / 9,84).

### Bateria de testes no device (moto g86 5G, Android 16), top-populares:

| Jogo | Pop. | Resultado |
|---|---|---|
| Crazy Taxi | 241 | ✅ 60fps |
| Quake III Arena | 417 | ✅ 60fps |
| Sonic Adventure | 209 | ✅ 60fps |
| Unreal Tournament | 280 | ✅ 60fps |
| Spider-Man | 298 | ✅ 60fps |
| ChuChu Rocket | 19 | ✅ 60fps |
| **Grand Theft Auto 2** | 542 | ❌ logo SEGA lavado, trava |
| **Resident Evil 3: Nemesis** | 445 | ❌ trava na licença, 9,8fps |
| **Legacy of Kain: Soul Reaver** | 179 | ❌ preto, 9,8fps |
| **Worms Armageddon** | 358 | ❌ trava, 9,8fps |

(Soulcalibur ficou inconclusivo — falha do automatizador, não do jogo.)

## Investigação — hipóteses testadas e REFUTADAS

O fps idêntico (~9,8) entre jogos diferentes indica um **caminho lento compartilhado**
(não "jogo pesado", que variaria o fps). Cada hipótese foi testada com experimento real:

| Hipótese | Teste | Resultado |
|---|---|---|
| **Não é WinCE** | Cruzado com lista oficial (`0WINCEOS.BIN`, frds.github.io) | GTA2/RE3/Soul Reaver **não são** WinCE; vários WinCE populares funcionam. Rótulo WinCE não prediz nada. |
| **Config de vídeo** | `broadcast` NTSC→Default, `cable_type` TV(RGB) | Sem efeito nos travados. |
| **MMU / força WinCE** | `reicast_force_wince=enabled` nos travados | Sem efeito; o log `Enabling Full MMU support` (existe no binário) **nunca dispara** → o caminho MMU não engata neste core. |
| **Frame pacing / refresh** | Comparado log de jogo OK vs travado | **Idêntico**: ambos mostram `refresh rate 45.000000, vsync 0, fps 59.945`. Não é o discriminador. |
| **CDDA / faixas de áudio** | Parseado metadados dos CHDs | **Soul Reaver (trava) ≡ Spider-Man (OK)**: 2 áudio + 8 dados idênticos; Unreal (OK) tem 20 faixas de áudio. Estrutura de disco não é a causa. |

## Conclusão

O travamento a ~9,8fps é um **hang cedo no boot interno ao core Flycast do buildbot**
(nightly 07/07/2026), específico de certos jogos. **Não é alcançável por nenhuma
configuração exposta** nem pela estrutura do ROM. O core não expõe diagnóstico (log de
CPU/dynarec/interpretador) que revele a causa.

**Confirmar/corrigir exige trabalho no core:**
- Testar um build de core diferente (compilar do upstream `flyinghead/flycast` atual,
  reaplicando o patch `libandroid.so` — ver [[2026-07-07-dreamcast-crash-boot-ashmem-libandroid]]),
  OU
- Buildar o core do fonte com logging de debug para achar o ponto exato do hang.

O teste de referência com o **Flycast standalone 2.6** (mesmo motor, mais novo) não pôde
ser concluído: o app usa SAF (URIs `content://`), o seletor de pasta é imgui (sem árvore
de acessibilidade) e resiste à automação por adb; `ACTION_VIEW` direto e injeção de
`ContentPath` no `emu.cfg` falham. Rodar manualmente na UI do standalone confirmaria se um
core mais novo resolve — próximo passo natural se for investir no caminho do core.

## Estado atual do código (config final validada)

Bloco DREAMCAST em [GameSystem.kt](../../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/GameSystem.kt):

- **Defaults**: `hle_bios=disabled`, `cable_type=TV (RGB)`, `broadcast=Default`,
  `enable_dsp=disabled`, `threaded_rendering=enabled`, `internal_resolution=640x480`,
  `anisotropic_filtering=4`.
- **Advanced toggles** (menu do jogo → Configurações): `reicast_force_wince`,
  `reicast_emulate_framebuffer` — para o usuário experimentar por jogo.
- Revalidado: Crazy Taxi/Quake III/Sonic/Unreal/Spider-Man seguem a 60fps → sem regressão.

## Mitigação aplicada (2026-07-08)

Decisão do usuário: **esconder os títulos confirmados quebrados** (GTA2, RE3: Nemesis,
Legacy of Kain: Soul Reaver, Worms Armageddon — 16 entradas `dc/`, incluindo o
"Escapee, The (Unl)" que compartilhava o título do RE3 por enriquecimento incorreto).

Implementação (a busca FTS mostra qualquer linha em `games`, então esconder exige
**deleção real**, não `isRepresentative=0`):

1. **Manifest**: 16 linhas removidas do `catalog_manifest.txt` (dc: 1425 → 1409).
   Installs novos ficam limpos via prebuilt DB regenerado (59.313 → 59.297 games,
   validado pelo `PrebuiltDbGenerator`).
2. **Installs existentes**: `MANIFEST_SCHEMA_VERSION` 22 → 23 + cleanup one-time no
   `ManifestQuickLoader.load()` chamando `GameDao.deleteBySystemAndTitles("dc",
   DC_BROKEN_TITLES)` quando `loadedSchema < 23`. FTS limpa via trigger `games_bd`.
3. **Validado no device** (caminho de upgrade real): log `v23 cleanup removed 16 broken
   dc games`; catálogo e busca sem os títulos; `Worms World Party` (dc) e os GTA2 de
   PSX/GBC intactos; total dc = 1409.

**Edge conhecido**: se o usuário já tinha baixado um desses jogos, o arquivo permanece
em `roms/dc/` e um rescan manual (settings) o re-indexaria como jogo avulso. Não afeta
produção (o catálogo DC nunca foi lançado com esses títulos); no device de teste os
arquivos órfãos foram removidos manualmente.

**Para re-adicionar no futuro** (se um core novo corrigir o hang): restaurar as 16 linhas
no manifest (recuperáveis do git) e bumpar o schema — o load INSERT OR IGNORE os recoloca.

## Lição

- Ao triar "tela preta" no Dreamcast, medir o **fps** cedo: 60fps = renderiza/roda;
  ~9,8fps constante = hang de boot (idle spin), não performance.
- fps **idêntico** entre jogos distintos ⇒ caminho compartilhado/cap, não demanda do jogo.
- "WinCE" não é sinônimo de "quebrado" neste core — testar empiricamente foi o certo.

## Nota (2026-09-02) — o core empacotado mudou embaixo deste bug

Entre esta investigação e 09/2026, o `.so` de Flycast de **arm64-v8a** foi trocado por um
build local sem o patch de `libandroid.so`, o que fez **todo** jogo de Dreamcast crashar
com SIGTRAP em aparelho arm64 — ver [[2026-09-02-flycast-arm64-core-hand-build-sigtrap]].
O core do buildbot já foi restaurado.

Consequências para este bug:

- As medições acima continuam válidas: foram feitas em 07-08/07 **com o core do buildbot**
  (nightly de 07/07), antes da troca.
- Qualquer relato de usuário sobre Dreamcast na janela do app 1.17.x é suspeito — o
  sintoma dominante ali era o crash, não o hang a 9,8 fps.
- O "próximo passo" desta página (testar um build de core diferente reaplicando o patch)
  foi provavelmente o que originou a troca. Ao tentar de novo: trocar o binário **nas 4
  ABIs**, sempre a partir do buildbot, e rodar `patch_flycast_libandroid.py`. A task
  `verifyFlycastCore` agora quebra o build se isso não for feito.

## Tentativa e resultado negativo: `reicast_hle_bios` (2026-09-03)

O default do bloco DREAMCAST tinha sido virado para `reicast_hle_bios=enabled` numa sessão
anterior, sem registro nem validação. Testado e **revertido**:

| Teste | Config | Resultado |
|---|---|---|
| *Grand Theft Auto 2* (USA) | `hle_bios=enabled` | ❌ trava na licença SEGA, **9,68 fps** — a mesma assinatura de sempre |
| *ChuChu Rocket!* (USA) | `hle_bios=disabled` (revertido) | ✅ 60 fps, jogo jogável ("PRESS START BUTTON!") |

Leitura: ligar o HLE **não mexe no travamento** e cobraria o preço de trocar a BIOS real por
uma HLE incompleta na biblioteca inteira — ainda mais porque `dc_boot.bin`/`dc_flash.bin` são
`requiredBIOSFiles` deste core e estavam presentes no aparelho de teste. Default restaurado
para `disabled`, que é a config registrada como validada em 07/2026, com o motivo escrito no
próprio [GameSystem.kt](../../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/GameSystem.kt)
para ninguém repetir o experimento às cegas.

**Saída do jogo de Dreamcast validada de passagem:** `Stored autosave file with size: 35908309`
→ `System.exit called, status: 0` → `Process exited cleanly (0)`.

A causa-raiz continua onde estava: **dentro do core**. Nada do lado do app alcança.

