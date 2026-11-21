---
fecha_por: dono
---

# [BUG] A telemetria quase não recebe relatos das versões 1.17.29 em diante: não dá para saber se um bug nativo ainda acontece

**Data:** 2026-11-20
**Severidade:** media: não derruba o cliente, mas cega a triagem (um bug nativo "sumido" pode estar só sem relato)
**Complexidade:** media
**Branch:** version10
**Origem:** investigacao (frequência de `2026-10-05-ppsspp-libretrodroid-cannot-create-framebuffer-abort`)

---

## Sintoma

Ao medir a frequência de um crash nativo, a última ocorrência era da 1.17.28. Mas a telemetria de **todas** as
versões depois da 1.17.28 tem tão poucos relatos que a ausência não prova nada.

## Evidência

Comando (skill `triagem-bugs-prod`, `RETROBAT_ROOT=C:/projects/retrobatnew`):
`telemetria.py todos --prefixo retrogamesystem --desde 2026-10-20`, contando por versão do `user_agent` (sem `-DEBUG`).

| Versão | Primeiro relato | Relatos por componente |
|---|---|---|
| 1.17.19 | 2026-10-20 | main 50, anr 8, lowmemory 4 |
| 1.17.27 | 2026-10-20 | native 20, game 11, lowmemory 5, anr 2 |
| 1.17.28 | 2026-10-30 | native 18, game 21, lowmemory 22, anr 3 |
| 1.17.29 a 1.17.32 | — | **nenhum relato** |
| 1.17.33 | 2026-11-13 | game 1 |
| 1.17.34 | 2026-11-13 | game 13, native 1 |
| 1.17.35 | 2026-11-16 | game 1, native 2 |
| 1.17.36 | 2026-11-18 | game 2 |
| 1.17.37 | 2026-11-19 | game 2, lowmemory 1 |

- Desde 2026-11-13, por versão: 1.17.19: 23, 1.17.34: 14, 1.17.28: 7, 1.17.22: 5, 1.17.35: 3, 1.17.37: 3,
  1.17.27: 2, 1.17.36: 2, 1.17.33: 1. Versões antigas continuam relatando, então o serviço recebe. A queda vale
  para as versões novas e também, em parte, no volume total.
- Só `retrogamesystem/native` desde 2026-08-13: 206 relatos, 18–30 por versão até a 1.17.28 e 3 depois dela.
- Não se sabe ainda se isso é pouca gente nas versões novas (a atualização in-app esteve quebrada de 2026-11-20
  15:59 até a 1.17.38, ver `2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio`, mas a queda começa antes) ou
  envio que deixou de funcionar. Para decidir, falta comparar com o número de instalações ativas por versão (fora da
  telemetria de erros) e conferir o envio num aparelho com a versão atual.
