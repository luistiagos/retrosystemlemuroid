# bugs/aberto

Bugs registrados, sem correcao integrada (inclui os que tem tasks em execucao). Vem de chamados de suporte (skill
`triagem-chamados`), da telemetria de erros (`triagem-bugs-prod`, projeto `retrogamesystem/...`), do Play Console e de
investigacao no codigo.

Desde 2026-11-14 os docs seguem o layout do pipeline global: cada bug e uma pasta `docs/bugs/<estado>/<id>/<id>.md`
(com `tasks/` dentro, quando houver), e a pasta de estado e a unica fonte do estado. Abrir um bug:
`/pipeline-global bug` (roteiro do motor; o template deste projeto fica em `.pipeline/projeto.md`,
`## Template de bug`). Corrigir: `/pipeline-global iniciar`.

A antiga `documentacao/bugs/` (copia de `docs/` por uma *junction*) foi removida em 2026-10-09; nao a recrie.

## Nome e template

- Id = nome da pasta e do arquivo: `AAAA-MM-DD-slug-curto` (ex.: `2026-07-01-catalogo-some-scan-biblioteca`). O id
  nunca muda depois de criado.
- Titulo `# [BUG] <sintoma como o usuario ve>`; logo abaixo os blocos **Data**, **Severidade**, **Branch** (e
  **Origem**: chamado, telemetria, relato do dono); depois as secoes **Sintoma**, **Evidencia**, **Causa-raiz**,
  **Correcao**, **Validacao** e **Licao**. Modelo: qualquer doc recente de `../concluido/`.
- Nunca apague um doc de bug.

## Ciclo de pastas

```
aberto/<id>/        registrada; inclui as demandas com tasks em execucao
implementado/<id>/  toda task de implementacao concluida e integrada no ramo principal; falta a Aceitacao
testado/<id>/       a Aceitacao passou; falta publicar
no-ar/<id>/         a versao publicada contem a solucao; espera o fechamento (a medida do caso ou o dono)
concluido/<id>/     fechada: pela prova na maquina, pela medida do caso ou pelo dono (frontmatter `fecha_por`)
bloqueado/<id>/     depende de algo externo; o motivo diz o que destrava
descartado/<id>/    duplicada, nao reproduz ou sem codigo
```

Quem muda a pasta e o motor do pipeline, pela evidencia (tasks, integracao, Aceitacao, publicacao, telemetria). A
pedido do dono a pasta pode ser movida a mao (`git mv` da pasta inteira) com o carimbo
`movido_por: <nome> - <AAAA-MM-DD> - "<frase do dono>"` no frontmatter; sem ele, o `pipeline.py validar` reprova a
pasta. Os docs que a migracao de 2026-11-14 pos fora de `aberto/` levam esse carimbo.

## Criterio de complexidade (obrigatorio em todo bug)

- **Baixa:** texto/mensagem na UI (`strings.xml` + `values-pt-rBR/strings.xml`), entrada do catalogo
  (`catalog_manifest.txt` + a copia em `lemuroid-app/src/main/assets/`), flag ou default de preferencia, validacao que ja
  tem a regra pronta em outro lugar e so falta chamar.
- **Media:** logica nova num ViewModel ou tela Compose (mobile ou TV), query nova no `GameDao`, Worker, filtro de
  sistemas (`HeavySystemFilter`), mapeamento de controle/portas.
- **Alta:** Room (migracao, `createFromAsset`, banco pre-gerado, PRAGMA), download sob demanda e fila
  (`RomOnDemandManager`, `SaveQueueManager`, `StreamingRomsManager`), o ciclo do processo `:game` (`BaseGameActivity`,
  saida do jogo, telemetria no caminho de crash), API acima do `minSdk 21`, JNI/LibretroDroid, ou mudanca que atravessa
  catalogo + banco + UI. Fix dentro de um core (`lemuroid-cores`) e fora do alcance deste repo: commit, tag e
  `CORES_VERSION` (pitfall 15 do `CLAUDE.md`).
