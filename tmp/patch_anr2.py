import io
p = "documentacao/bugs/open/2026-08-09-anr-inicializar-jogo-runongl-thread.md"
s = io.open(p, encoding="utf-8").read()

old = """### Nota sobre a tela de crash que apareceu

O disclaimer exibido foi o de **falha de núcleo** ("tente formatação de fábrica"), porque
`isEmulatorFailure` casa o frame `com.swordfish.libretrodroid` e a `GLThreadTimeoutException`
é lançada de dentro de `GLRetroView.runOnGLThread`. A regra do pitfall 8 do `CLAUDE.md` não
está errada — a GLThread estava mesmo presa dentro do core — mas o conselho dado ao usuário
(formatação de fábrica) não resolve um timeout. Fica registrado; com a correção acima esse
crash não acontece mais por este caminho.
"""
new = """### A tela de crash acusava o núcleo — corrigido (2026-09-03)

O disclaimer exibido foi o de **falha de núcleo** ("limpe o cache… execute a formatação de
fábrica"), porque `isEmulatorFailure` casava o frame `com.swordfish.libretrodroid` e a
`GLThreadTimeoutException` é lançada de dentro de `GLRetroView.runOnGLThread`. Além do texto,
`isEmulatorFailure = true` também dispara `tryFallbackCore`: o app relançaria o jogo com o
próximo núcleo e pagaria **outro** timeout de 30 s.

Nenhum dos dois textos existentes servia:

| Texto | Por que não serve para uma GLThread travada |
|---|---|
| `lemuroid_crash_disclamer` | manda limpar dados e resetar de fábrica — nada disso alcança uma thread presa |
| `lemuroid_app_error_disclamer` | afirma "o problema não é do seu jogo, da sua ROM **nem do núcleo de emulação**", e o núcleo é exatamente o que parou |

**Correção:** terceira categoria, própria.

- [BaseGameActivity.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt):
  `causeChain()` unifica a caminhada pelas causas (com o limite de profundidade que já existia);
  `isCoreStall()` procura `GLRetroView.GLThreadTimeoutException` na cadeia; e `isEmulatorFailure`
  ganhou `return false` para essa exceção **antes** do teste por pacote — sem isso ela sempre
  casaria `LIBRETRODROID_PACKAGE`. O resultado vai no extra novo
  `PLAY_GAME_RESULT_IS_CORE_STALL`.
- [GameLaunchTaskHandler.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/main/GameLaunchTaskHandler.kt):
  ramo próprio antes dos outros dois — mostra `lemuroid_core_stalled_disclamer` e **não** chama
  `tryFallbackCore`.
- String nova em `values/` e `values-pt-rBR/` (os dois locais onde o disclaimer de app vive):
  "O núcleo de emulação parou de responder e o jogo precisou ser fechado. O problema não é do
  seu aparelho nem da sua ROM — limpar dados ou resetar de fábrica não vai adiantar. Abra o jogo
  de novo; o progresso desde o último salvamento pode ter se perdido."

**Validado em device (2026-09-03)** com a exceção forçada — o mesmo roteiro que a página do
Toast já prevê para conferir a blindagem, já que a travada real não reproduz sob demanda. Build
temporário com `postDelayed { throw … }` no `onCreate`, duas rodadas, e o `.kt` conferido por
`diff` contra a cópia limpa depois de remover a instrumentação:

| Exceção forçada | Log | Tela |
|---|---|---|
| `GLRetroView.GLThreadTimeoutException` | `W GameLaunchTaskHandler: Core stalled the GL thread:` e **nenhum** `Core fallback:` | texto novo do núcleo travado |
| `IllegalStateException` | `W GameLaunchTaskHandler: Non-emulator failure in game process:` | disclaimer de app, como antes |

Nos dois casos o `text2` seguiu trazendo a mensagem real (`GLThread did not answer in 30000 ms`)
e o rodapé, aparelho + Android + versão — que é o que o pitfall 8 pede que uma foto da tela
resolva. Instrumentação removida e o build limpo reinstalado e conferido rodando um jogo a
60 fps.
"""
assert old in s
s = s.replace(old, new)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ok")
