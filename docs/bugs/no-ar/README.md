# bugs/no-ar

A versao publicada contem a solucao; a demanda espera o fechamento (a medida do caso ou o dono). Ciclo completo em
[../aberto/README.md](../aberto/README.md).

## Por que separar de concluido

O teste do pipeline roda o APK debug num aparelho ou emulador nosso. O que so o cliente prova (a versao publicada, o
aparelho dele — TV box, Android antigo, outro fabricante — e o jogo dele) ainda nao foi visto. A demanda sai daqui
para `../concluido/` pelo `fecha_por` do frontmatter: `prova` (a prova na maquina basta), `evento` (a medida do caso
chegou pela telemetria) ou `dono` (padrao). Se o sintoma voltar (telemetria pelo `casador`, ou relato), a demanda
volta para [../aberto/](../aberto/) com nota da reincidencia.

## Os bugs que ja estavam aqui antes da migracao

Os docs que a migracao de 2026-11-14 trouxe para esta pasta tem uma secao `## Status` no topo, do ciclo antigo: data da
correcao, hash do(s) commit(s), onde o fix foi aplicado, evidencia de cada nivel de teste e **o que falta para
fechar** (versao publicada, outro aparelho, prova do cliente). Ela continua valendo como historico desses bugs; demanda
nova nao escreve `Status:` a mao (o estado e a pasta).
