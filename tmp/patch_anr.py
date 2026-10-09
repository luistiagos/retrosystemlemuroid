import io
p = "docs/bugs/open/2026-08-09-anr-inicializar-jogo-runongl-thread.md"
s = io.open(p, encoding="utf-8").read()

start = s.index("- **N\u00e3o testado em device.**")
end = s.index("(fora do PATH).", start) + len("(fora do PATH).")
s = s[:start] + "- ~~**N\u00e3o testado em device.**~~ \u2192 **testado em 2026-09-03**, ver a se\u00e7\u00e3o abaixo." + s[end:]

marker = "## Recorr\u00eancia em produ\u00e7\u00e3o (telemetria, 2026-09-02)"
i = s.index(marker)
section = """## Validação em device (2026-09-03) — e um terceiro ponto que ainda quebrava

**Aparelho:** Moto G86 5G, Android 16, app `1.17.12-DEBUG`.
**Jogo:** *Need for Speed - Underground 2* (GameCube / dolphin) — **o mesmo jogo e core que a
telemetria nomeia**.

### O que passou

- **Boot sem ANR.** O jogo carrega e roda a 60 fps (`EMUFPS 60.00`). O sintoma do relato
  original — pad de GameCube desenhado sobre tela preta — aparece, mas **sem** o diálogo
  "não está respondendo": é o intro do jogo, não a main thread presa.
- **Saída limpa** em todas as sessões testadas (SNES, 3DS, GameCube):
  `Stored sram file with size: …` → `System.exit called, status: 0` → `Process exited
  cleanly (0)`. Nenhum `GLThreadTimeoutException` escapou do `saveOnExit`.

### O que NÃO passou — bug novo, encontrado aqui

Numa das sessões o Dolphin **parou de renderizar** (zero `EMUFPS`/`VIDEOFRAMES` por 12 s
seguidos, processo `:game` vivo, atividade resumida): a GLThread parada dentro de um callback
do renderer, exatamente a condição de fundo desta página. Com ela nesse estado, **abrir o menu
do jogo matava a sessão**:

```
E BaseGameActivity: com.swordfish.libretrodroid.GLRetroView$GLThreadTimeoutException:
                    GLThread did not answer in 30000 ms
I ActivityTaskManager: START … GameCrashActivity
```

O caminho é o terceiro da tabela "Mesma classe de bug em outros dois pontos", e o que faltava
nele não era o `withContext(IO)` (esse já estava) e sim o **tratamento do timeout**:
`displayOptionsDialog` chamava `getAvailableDisks`/`getCurrentDisk` sem `try/catch`, a exceção
escapava do `collect` de `initializeViewModelsEffectsFlow`, chegava ao
`UncaughtExceptionHandler` e o usuário via a tela de crash — **em vez do menu, que é por onde
ele sairia do jogo travado**. Pior: o crash só chegava 30 s depois do toque, então na prática
o menu "não abria" e depois o app "quebrava sozinho".

### Correção (2026-09-03)

[BaseGameActivity.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt):
`readDiskState()` substitui a leitura crua.

1. **Sonda barata antes** (`queueEvent` + latch de 2 s, o mesmo padrão de `glThreadResponds`
   em `GameViewModelSaves`): GLThread parada custa 2 s, não 30 s.
2. **`try/catch` mesmo assim** — a parada pode começar entre a sonda e a chamada.
3. Degrada para `0 to 0`: o menu abre **sem a linha de discos**, que é informação acessória,
   em vez de não abrir.
4. Reporta à telemetria com `phase=open-menu` (não-terminal), para a recorrência aparecer sem
   custar uma sessão do usuário.

### Validação da correção, no mesmo aparelho

- Menu abre em **1.181 ms** com a GLThread saudável (900 ms dos quais são o *hold* que o
  próprio botão exige) — a sonda não cobra nada no caminho normal.
- A sessão de GameCube sobreviveu a abrir/fechar o menu, tela apagada + PIN e ida e volta para
  o background, voltando sempre a 60 fps.
- **A travada da GLThread não voltou a acontecer sob demanda.** Ocorreu uma vez e não
  reproduziu em ~15 min de tentativa dirigida (screen-off/on, background/foreground, jogo
  parado). Ou seja: o caminho corrigido está exercitado no estado saudável e o crash está
  provado no estado travado **antes** da correção; o "depois" no estado travado depende de a
  travada reaparecer. É por isso que esta página continua em `open/`.

### Nota sobre a tela de crash que apareceu

O disclaimer exibido foi o de **falha de núcleo** ("tente formatação de fábrica"), porque
`isEmulatorFailure` casa o frame `com.swordfish.libretrodroid` e a `GLThreadTimeoutException`
é lançada de dentro de `GLRetroView.runOnGLThread`. A regra do pitfall 8 do `CLAUDE.md` não
está errada — a GLThread estava mesmo presa dentro do core — mas o conselho dado ao usuário
(formatação de fábrica) não resolve um timeout. Fica registrado; com a correção acima esse
crash não acontece mais por este caminho.

"""
s = s[:i] + section + s[i:]
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ok")
