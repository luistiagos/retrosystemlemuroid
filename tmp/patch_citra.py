import io
p = "documentacao/bugs/open/2026-09-02-crashes-nativos-cores-citra-dolphin.md"
s = io.open(p, encoding="utf-8").read()

start = s.index("### Valida\u00e7\u00e3o\n\nCompila (`:lemuroid-app:compileFreeBundleDebugKotlin`)")
end = s.index("---\n\n## Dolphin (GameCube)", start)
new = """### Validação em device (2026-09-03) — parcial, e uma lacuna corrigida

**Aparelho:** Moto G86 5G (Mali, Android 16) — **não** é o aparelho que crasha. O que dá para
validar aqui é o lado do app: que a opção existe, é a chave certa e o 3DS continua rodando.
O crash em si depende de GPU Xclipse.

**Jogo:** *Mario Kart 7* (Europe), baixado pelo próprio catálogo do app.

| Item | Resultado |
|---|---|
| 3DS carrega e roda com o core citra | ✅ 60 fps (`EMUFPS 60.03`), tela do jogo renderizando |
| `citra_use_hw_shaders` aparece no menu do jogo → Configurações | ✅ com o switch **ligado**, igual ao default do core |
| `citra_use_acc_geo_shaders` (a chave morta) sumiu do menu | ✅ |
| Saída do jogo | ✅ limpa (`System.exit … status: 0`) |
| Fallback automático após crash nativo | ⏳ não exercitável aqui — exige o crash, que não acontece nesta GPU |

**Lacuna encontrada e corrigida:** a opção aparecia em **inglês** num app em português —
`setting_citra_use_hw_shaders` só existia em `values/strings.xml`. Traduzida para
`values-pt-rBR` e `values-pt-rPT`. Vale como lembrete: uma `ExposedSetting` nova precisa da
string nos locais do projeto, não só no default; e isso **só aparece abrindo o menu no
aparelho** — compilar e conferir a chave contra o `.so` não pega.

O que continua faltando confirmar num Galaxy S25:

1. Rodar *Ocarina of Time 3D* → crash (baseline).
2. Reabrir o app → log `3DS died in native code last session; disabled citra_use_hw_shaders`.
3. Rodar o mesmo jogo → roda (mais devagar), e o switch "Shaders por hardware" aparece
   desligado no menu do jogo.

"""
s = s[:start] + new + s[end:]
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ok")
