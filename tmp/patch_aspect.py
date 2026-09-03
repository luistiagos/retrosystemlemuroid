import io, re
p = "documentacao/bugs/open/2026-08-20-tela-cheia-proporcao-aspecto.md"
s = io.open(p, encoding="utf-8").read()

old_status = """**Status:** \U0001f7e1 Em andamento — 3ª iteração do design implementada e compilando, **validação visual
zero** (interrompida por reinstalação limpa do device no meio do teste). Fica em `open/` até
alguém rodar um jogo com a nova preferência e conferir as proporções."""
new_status = """**Status:** ✅ Resolvido — 3ª iteração do design validada no device em 2026-09-03 (Moto G86 5G,
os cinco critérios do roteiro conferidos por captura de tela)."""
assert old_status in s, "status block not found"
s = s.replace(old_status, new_status)

start = s.index("## Validação — o que falta (motivo de continuar `open`)")
end = s.index("## Pendências que não são bug, são escopo cortado")
new_section = """## Validação no device (2026-09-03) — os cinco critérios, conferidos

**Aparelho:** Moto G86 5G, 2712×1220 (≈ 20:9), Android 16, app `1.17.12-DEBUG` (versionCode 243),
AAR `libretrodroid-patched.aar` com `setTargetAspectRatio` presente no `.so` de arm64-v8a e o
`TARGET_ASPECT_AUTO`/`_FILL` no `LibretroDroid.class`.

**Jogo de teste:** *Super Mario World* (SNES / snes9x) — 4:3, a mesma classe de proporção do
Mega Drive da foto do usuário.

A preferência aparece em Configurações → **Proporção da tela** com as sete opções, grava
`screen_aspect_ratio` no Harmony (`files/harmony_prefs/harmony_options/`) e vale a partir do
próximo jogo aberto, como projetado.

| Critério do roteiro | Medido na captura (landscape, área de jogo 2000×900 px na escala da imagem) | Resultado |
|---|---|---|
| `Automática` reproduz o pillarbox original | quad de 1200×900 = **1,333** — barras pretas largas dos dois lados, idêntico à foto do usuário | ✅ |
| `Esticar para preencher` sem barra nenhuma | quad de 2000×900, imagem visivelmente esticada na horizontal | ✅ |
| `20:9` ≡ `Esticar` num aparelho 20:9 | as duas capturas são visualmente indistinguíveis (diferem só no gradiente animado de fundo) | ✅ |
| `16:9` deixa barra fina num 20:9 | quad de 1600×900 = **1,778** — barra fina simétrica | ✅ |
| Em retrato, nenhuma proporção invade os controles | com `Esticar`, o quad ocupa toda a área de jogo e **para exatamente na borda do pad**; `Automática` fica letterboxed dentro da mesma área | ✅ |

Capturas em `tmp/v-smw-*.png` (`auto`, `169`, `209`, `fill` × `portrait`/`land`).

**Achado extra, não previsto no roteiro:** o valor **sobrevive à rotação em jogo**. Todos os
testes foram feitos abrindo o jogo em retrato e girando para landscape com o jogo rodando — o
encaixe se refaz na nova viewport mantendo a proporção alvo. Era o esperado (o alvo mora no
singleton nativo `LibretroDroid`, não no `Video`, que é recriado), mas agora está observado.

**Saída do jogo:** limpa em todas as sessões (`System.exit ... status: 0`, `Process exited
cleanly (0)`), sem `GLThreadTimeoutException` — ver [[2026-08-09-anr-inicializar-jogo-runongl-thread]].

"""
s = s[:start] + new_section + s[end:]

s = s.replace("""Este arquivo é o registro da investigação e das decisões — quando a validação acima for feita,
mover este arquivo para `bugs/done/` atualizando o Status.""",
"""Este arquivo é o registro da investigação e das decisões.""")

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ok")
