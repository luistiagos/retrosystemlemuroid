import io
p = "documentacao/bugs/open/2026-07-08-dreamcast-subset-jogos-travam-9fps.md"
s = io.open(p, encoding="utf-8").read()

s = s.rstrip() + """

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
"""
io.open(p, "w", encoding="utf-8", newline="\n").write(s + "\n")
print("ok")
