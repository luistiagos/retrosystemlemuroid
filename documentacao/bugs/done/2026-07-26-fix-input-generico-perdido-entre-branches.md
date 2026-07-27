# [BUG] Controles genéricos voltaram a não funcionar — fix de input perdido entre branches

**Data:** 2026-07-26
**Status:** Resolvido ✅
**Severidade:** Alta (todo controle genérico inoperante no branch de desenvolvimento)
**Branch:** version9

---

## Sintoma

Controles genéricos (TV box, encoders arcade USB, clones de PS2 com dongle 2.4G) não
funcionam no Lemuroid. O sintoma clássico é o controle navegar nos menus do Android mas
ficar morto dentro do jogo — ou aparecer desativado na tela de Controles.

O agravante é que **isso já tinha sido corrigido**. O bug foi reportado como recorrência,
o que levou a uma segunda investigação do zero em 2026-07-23 (ver
`2026-07-23-controle-arcade-njp308-stick.md`).

## Causa-raiz

Não era regressão de código. O fix estava correto e completo — **só não estava neste
branch**.

O commit `b424cda` (2026-07-20) aplicou os 5 edits do plano
`documentacao/backlogs/fix-controles-genericos-tvbox-haskeys.md` e ficou **exclusivamente
no branch `version8`**. O `version9` divergiu de `version8` no commit `095f1dc`, antes
dele.

```
095f1dc (base comum)
  |-- version8 -> 0fbe5fc, f8ff215, 8d19200, b424cda   <- fix de input aqui
  \-- version9 -> ... 9be8ecf, 27accb3, e1cd92f, fc6e94c  <- branch de desenvolvimento
```

O detalhe que fecha o caso: **dois dos quatro** commits do `version8` foram cherry-picked
para o `version9` — `f8ff215` (splits por ABI) virou `9be8ecf` e `8d19200` (app_version
com `-SkipR2`) virou `27accb3`. O `b424cda`, o fix de input, ficou para trás. Os commits
foram escolhidos individualmente em vez de conferidos como conjunto.

Confirmação:

```powershell
git merge-base --is-ancestor b424cda HEAD   # falso
```

O arquivo de backlog com o plano também nunca existiu no `version9` —
`git log --diff-filter=D` não acha deleção, ele simplesmente não está na história deste
branch. Por isso a memória do projeto apontava para um caminho inexistente.

Os quatro pontos que voltaram a ficar quebrados no `version9`:

| # | Local | Efeito |
|---|-------|--------|
| 1 | `LemuroidInputDeviceGamePad.getDefaultBindingForKey()` | Toda tecla com `hasKeys == false` recebia binding `KEYCODE_UNKNOWN` |
| 2 | `LemuroidInputDeviceGamePad.isSupported()` | Exigia `hasKeys(A,B,X,Y)` completo |
| 3 | `LemuroidInputDeviceGamePad.isEnabledByDefault()` | Exigia também `BUTTON_START` |
| 4 | `BaseGameActivity.dispatchKeyEvent()` | Roteava só por `event.source` |

O ponto 1 é o mais letal e o mais fácil de passar batido. `InputDeviceManager.OUTPUT_KEYS`
inclui `KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT`. Se `hasKeys(KEYCODE_DPAD_UP)` retorna `false`
— caso típico de encoder genérico sem `.kl` próprio — o binding vira
`RetroKey(KEYCODE_UNKNOWN)`. Depois, em `GameViewModelInput.initializeGamePadKeysFlow`:

```kotlin
val bindKeyCode = bindings(device)[InputKey(keyCode)]?.keyCode ?: keyCode
```

O `?: keyCode` **não protege**, porque o mapa *contém* a chave — com valor
`KEYCODE_UNKNOWN` (0). O evento chega, tem porta atribuída, e é enviado ao core como
keycode 0. Morre em silêncio.

## Correção

`git cherry-pick b424cda` no `version9` (commit `b91cc7b`). Dos 4 arquivos do commit, 3
aplicaram limpo; o único conflito foi em `LemuroidInputDeviceGamePad.kt`, resolvido
mantendo o lado do `b424cda` em `isSupported`/`isEnabledByDefault` e preservando os
overrides `BUTTON_1..10` do patch de 07-23, que são aditivos.

Traz junto o log `INPUT_DIAG` de devices **rejeitados** (com `sources` e `isVirtual`, para
dizer qual filtro barrou) e restaura o arquivo de backlog no branch.

## Validação

```powershell
./gradlew.bat :lemuroid-app:compileFreeBundleDebugKotlin   # BUILD SUCCESSFUL
```

Sem validação física — nenhum controle genérico foi exercitado neste branch após o
cherry-pick.

## Lição

Fix validado em um branch de release não acompanha o próximo branch automaticamente.

**Ao cherry-pickar entre branches de versão, conferir a lista completa com
`git log --oneline versaoNova..versaoAntiga`** em vez de escolher commits individualmente.
Foi exatamente assim que o `b424cda` se perdeu enquanto os dois commits de build pipeline
vizinhos foram trazidos.

**Corolário, mais importante:** quando um bug "recorre" logo depois de ter sido dado como
corrigido, checar primeiro se a correção está no branch atual
(`git merge-base --is-ancestor <commit> HEAD`) **antes** de rediagnosticar. Diagnosticar
de novo sobre um baseline sem o fix produz um patch que redescobre metade do problema e
erra a outra metade — foi o que aconteceu com o patch de 2026-07-23, e os três bugs
documentados em `2026-07-26-*` saíram todos dele.
