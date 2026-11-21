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

## Análise (2026-11-20)

### Comandos e números

`telemetria.py todos --prefixo retrogamesystem --desde 2026-10-15` (skill `triagem-bugs-prod`, `RETROBAT_ROOT=C:/projects/retrobatnew`),
sem `-DEBUG`, contando por versão × componente:

| Versão | Primeiro/último relato | Componentes |
|---|---|---|
| 1.17.19 | 10-20 / 11-20 | main 50, anr 8, lowmemory 4 |
| 1.17.22 | 10-18 / 11-17 | lowmemory 12 |
| 1.17.27 | 10-15 / 11-15 | native 30, game 14, lowmemory 7, anr 2 |
| 1.17.28 | 10-30 / 11-21 | native 18, game 21, lowmemory 22, anr 3 |
| 1.17.33 | 11-13 | game 1 |
| 1.17.34 | 11-13 / 11-14 | game 13, native 1 |
| 1.17.35 | 11-16 / 11-17 | game 1, native 2 |
| 1.17.36 | 11-18 | game 2 |
| 1.17.37 | 11-19 / 11-20 | game 2, lowmemory 1 |

- As 13 de `game` da 1.17.34 são uma rajada de `GLThreadTimeoutException` entre 11-13 22:55 e 11-14 23:30 (8 delas em
  35 min): cara de um aparelho só. Tirando a rajada, as cinco versões novas somam ~12 relatos em 7 dias.
- Bumps de versão (`git log -G'versionName = ' -- lemuroid-app/build.gradle.kts`): 1.17.28 em 10-29; 1.17.29 só em
  11-10 (`65ad454`); 1.17.30 em 11-12 (`cbc35de`); 1.17.33/34 em 11-13/14. Ou seja, a 1.17.28 foi a última versão por
  ~12 dias, e da 1.17.29 à 1.17.37 houve nove versões em dez dias, cada uma no ar de 1 a 3 dias. Só há tag de
  publicação para `publicado/1.17.35` e `publicado/1.17.38`; da 1.17.29 à 1.17.32 não há relato nem sinal de publicação.
- A 1.17.19 (bump de 2026-09-19) ainda é a versão que mais relata (23 desde 11-13), e 1.17.22/1.17.27 também seguem
  relatando: a base instalada atualiza devagar. A atualização in-app (`AppUpdateManager`) depende de o usuário abrir o
  app e aceitar, e cada versão nova só pega quem atualizou na janela curta em que foi a última.

### O envio funciona nas versões novas

Os três caminhos de relato aparecem em versões 1.17.33+:

- exceção Java no `:game` (`CrashTelemetry.installUncaughtHandler` → `TelemetryReporter.report`): `game` em todas;
- scan do `ApplicationExitInfo` na sessão seguinte (`CrashTelemetry.reportPastExits` → `reportOneExit`): `native` na
  1.17.34 (id 13838) e na 1.17.35 (14024, 14126), `lowmemory` na 1.17.37 (14340);
- o servidor grava uma linha por relato, sem deduplicar (`flask_app.py::logError` → `errordao.add_error`, um `INSERT`
  por chamada). O único descarte no servidor é `retrogamesystem/lowmemory` com `importance>200` (desde 2026-09-24,
  `1b88280`, anterior à queda).

### Símbolos abertos

- `TelemetryReporter.kt` (diff `2e9e17a..HEAD`): só ganhou acessores para a `MedidaReporter` e reformatação do ktlint.
  `report` / `send` / `postJson` / `getFallback` sem mudança de comportamento. Em erro: tudo engolido, relato perdido.
- `CrashTelemetry.reportPastExits`: inalterado desde a 1.17.28. Em erro dentro de `reportOneExit`, o relato daquela
  saída se perde e o watermark `telemetry_last_exit_ts` avança mesmo assim (risco latente, mas não é a causa aqui: o
  caminho entrega nas versões novas).
- `CrashTelemetry.reportOneExit` ganhou `TelemetryContext.foregroundWorkForExit` (`3299e5d`, 2026-11-20, depois da
  queda); é `try/catch` que devolve `""`, não derruba o relato.
- `LemuroidApplication.onCreate`: `reportPastExitsAsync` continua só no processo principal, como antes.

### Hipóteses descartadas

- **Envio quebrado no cliente:** descartada. Os três caminhos entregam relatos de 1.17.34, 1.17.35 e 1.17.37, e o
  código de envio não mudou desde a 1.17.28.
- **Servidor descartando ou agrupando:** descartada. Um `INSERT` por relato; as versões antigas continuam chegando
  no mesmo período.
- **Scan de `ApplicationExitInfo` parado (o `native`/`lowmemory` some e o `game` não):** descartada. Há `native` e
  `lowmemory` vindos do scan nas versões novas; a proporção menor é compatível com poucos aparelhos (o `game` das
  novas é quase todo uma rajada de um aparelho).

## Causa-raiz

Não há defeito de envio. A queda é de população: a 1.17.28 ficou ~12 dias como última versão e juntou a base que
atualizou nesse período. As versões seguintes saíram quase uma por dia, e cada uma ficou com os poucos aparelhos que
atualizaram naquela janela. A atualização in-app quebrada em 2026-11-20 (`2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio`)
só piora o último dia. A ausência de um crash nas versões novas, portanto, **não prova** que ele foi corrigido.

## Correção

Nenhuma mudança de código. Para a triagem:

- Para medir se um bug nativo sumiu, comparar a **taxa por relato da mesma versão** (ou somar as versões novas e
  comparar com a 1.17.28 normalizando por `game`) e esperar a versão ficar alguns dias no ar; nunca concluir pela
  ausência numa versão que viveu 1 a 3 dias.
- Melhoria possível, fora deste bug (vira backlog se o dono quiser): um pulso de sessão por versão (ex.: uma `medida`
  diária por aparelho) daria o denominador que falta para comparar versões.

## Validação

Sem código, a validação é a própria contagem acima: relatos dos três caminhos chegando de versões 1.17.33+. Conferir
de novo em ~7 dias com a 1.17.38 no ar (a atualização in-app foi restaurada nela): se ela juntar `native`/`lowmemory`
na faixa de 1–2 por dia como a 1.17.27/1.17.28, a conclusão está confirmada; se `game` chegar e o scan continuar
calado, reabrir pela hipótese do scan.

## Lição

Volume de telemetria por versão mede usuários naquela versão, não saúde do envio. Antes de afirmar "o bug sumiu" ou
"o envio quebrou", olhar quanto tempo a versão ficou no ar e se os outros componentes da mesma versão chegam.
