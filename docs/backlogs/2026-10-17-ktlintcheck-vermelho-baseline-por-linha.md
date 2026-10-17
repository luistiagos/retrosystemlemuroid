# [BACKLOG] `./gradlew ktlintCheck` vermelho: baseline por posição + violações novas desde 2026-10-07

**Data:** 2026-10-17
**Origem:** verificação 1 da passagem de bastão da Fase 3 do redesenho
(`docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md`, entrada "2026-10-16 (passagem de bastão: Fase 3 fechada)")

## Situação

O `CLAUDE.md` diz que o `ktlintCheck` "passa e volta a falhar em violação nova". **Hoje ele não passa** no checkout
principal (`version10`, HEAD `9e9651d`, com o trabalho de arcade/NAOMI de outra sessão ainda não commitado na árvore).
Comando e resultado:

```
./gradlew ktlintCheck --continue   → BUILD FAILED, 4 tasks:
  :lemuroid-app:ktlintKotlinScriptCheck        8  (build.gradle.kts)
  :lemuroid-app:ktlintTestSourceSetCheck      15  (ArcadeCloneParentsTest.kt)
  :lemuroid-app:ktlintMainSourceSetCheck      31  (GameLaunchTaskHandler.kt 18, RomOnDemandManager.kt 13)
  :retrograde-app-shared:ktlintMainSourceSetCheck 170 (GameDao 81, HeavySystemFilter 28, ManifestQuickLoader 15,
                                                   DownloadedRomDao 15, LemuroidLibrary 10, GameSearchDao 6,
                                                   ArcadeCloneParents 5, BiosManager 5, GameSystem 5)
```

Nenhum arquivo do redesenho (Fases 1–3) aparece.

## Causa: duas, misturadas

1. **O baseline casa por `linha:coluna:regra`.** Qualquer edição que desloque linhas num arquivo com passivo faz o
   passivo "reaparecer" sem nenhuma violação nova. Os baselines são de `082011d` (2026-10-07). Prova:
   o `lemuroid-app/build.gradle.kts` acusa 8 violações que são exatamente as do baseline uma linha abaixo
   (baseline 181/387/405/410/413/418/480/490 → relatório 182/388/406/…) — o `b631880` (MAME, 2026-10-16) inseriu
   uma linha. `ManifestQuickLoader`, `DownloadedRomDao`, `BiosManager` e `GameSystem`: 100% deslocamento.
2. **Violações realmente novas** (sem par de mesma regra+coluna no baseline; atribuídas por `git blame`):
   - `78b7ff2` (2026-10-08, aba de jogos instalados): `GameDao.kt` 365–489 (function-signature, trailing comma,
     linha em branco dupla), `GameSearchDao.kt` 106–139 (wrapping), `LemuroidLibrary.kt:90`.
   - `9e9651d` (2026-10-16, arcade split sets): `GameLaunchTaskHandler.kt` 172–201, `RomOnDemandManager.kt:651`,
     `ArcadeCloneParents.kt:128`, `GameDao.kt:255`, `ArcadeCloneParentsTest.kt` 14–25.
   - Não commitado (trabalho de arcade/NAOMI em andamento): `RomOnDemandManager.kt` 446/874, `ArcadeCloneParents.kt:116`,
     `GameDao.kt:317`, `ArcadeCloneParentsTest.kt` 99/102, `HeavySystemFilter.kt` 48/59/81 (no-multi-spaces).
   - `GameLaunchTaskHandler.kt:3` (import-ordering) — linha antiga; a ordem quebrou por import novo do `9e9651d`.

O classificador (script descartável) comparou, por arquivo, cada `(regra, coluna)` do relatório com o multiconjunto
do baseline; o que sobrou é "novo". Falso negativo possível (violação nova coincidindo coluna+regra de uma antiga),
falso positivo não.

## Hipótese descartada

- **"O baseline não casa o caminho de `.claude/worktrees/`"** (suspeita da passagem de bastão): falso. O mesmo
  `ktlintKotlinScriptCheck` falha no checkout principal; o motivo é o deslocamento de linha do item 1.

## O que fazer

**Esperar** a sessão de arcade/NAOMI commitar (metade das violações novas está na árvore dela). Depois, num commit só:

1. Corrigir as violações novas dos arquivos que o commit tocou: `./gradlew ktlintFormat` restrito não existe por
   arquivo, então rodar `ktlintFormat` e reverter com `git checkout -p` tudo que não seja das linhas listadas acima
   (o format mexe no passivo inteiro — ~900 apontamentos só no `lemuroid-app`).
2. `./gradlew ktlintGenerateBaseline` para recongelar o deslocamento (item 1), **só depois** do passo 1.
3. Conferir `git -C lemuroid-cores status` (CLAUDE.md: a task grava baseline no submódulo).
4. Prova: `./gradlew ktlintCheck` → BUILD SUCCESSFUL.

**Lição para o `CLAUDE.md` (seção "Estilo: ktlint com baseline"):** o baseline é por posição — editar um arquivo com
passivo exige regenerar o baseline no mesmo commit, senão o check fica vermelho para o próximo. O
`ktlintMainSourceSetCheck` por módulo (que é o que as entregas rodam) não pega o `ktlintKotlinScriptCheck` nem o
`ktlintTestSourceSetCheck`; a verificação de fechamento é o `ktlintCheck` completo.
