import io
p = "docs/bugs/open/2026-08-16-tvbox-mxq-crash-toast-badtoken.md"
s = io.open(p, encoding="utf-8").read()

marker = "## Confirma\u00e7\u00e3o pela telemetria (2026-09-02)"
i = s.index(marker)
section = """## Validação possível fora da TV box (2026-09-03)

A MXQ é do cliente, então o que dá para validar aqui é **o que a correção não pode ter
quebrado**: que o `displayToast` continua desenhando um toast de verdade. O `SafeToastContext`
troca o `WindowManager` que o `Toast$TN.handleShow` usa — se o wrapper estivesse errado, o
sintoma no aparelho saudável seria o toast **sumir em silêncio**, que é pior de descobrir do
que um crash.

**Aparelho:** Moto G86 5G, Android 16, app `1.17.12-DEBUG`.
**Gatilho usado:** *Super Mario World* (SNES), estado do slot 1 sobrescrito com lixo por `adb`,
depois carregado pelo menu do jogo → caminho `game_toast_load_state_failed` em
`GameViewModelSaves`.

```
I SurfaceFlinger: onHandleDestroyed: layerId=24644, name=5910e40 Toast#24644
W NotificationService: Toast already killed. pkg=app.retrogamesystem.debug token=android.os.BinderProxy@…
```

- ✅ A janela de toast **é criada** e vive o tempo normal — o wrapper não engole o caminho feliz.
- ✅ Nenhum `BadTokenException` no logcat.
- ⚠️ O toast **não aparece no `screencap`** (a camada é excluída da captura), então a prova aqui
  é o log do SurfaceFlinger, não a imagem. Vale anotar para a próxima vez que alguém tentar
  conferir toast por screenshot e concluir errado que não apareceu.
- Também conferido por varredura estática: **zero** `Toast.makeText(...)` fora do `SafeToast.kt`
  em `lemuroid-app`, `retrograde-app-shared`, `retrograde-util` e `lemuroid-touchinput`.

**Continua faltando** o que só a MXQ (ou o `rockchip YBOX`) responde: que o jogo abre até o fim
sem cair na `GameCrashActivity`. É por isso que esta página segue em `open/`.

"""
s = s[:i] + section + s[i:]
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ok")
