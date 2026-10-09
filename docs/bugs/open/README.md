# bugs/open

Bugs ativos, sem correcao publicada. Vem de chamados de suporte (skill `triagem-chamados`), da telemetria de
erros (`triagem-bugs-prod`, projeto `retrogamesystem/...`), do Play Console e de investigacao no codigo.

`documentacao/bugs/` e um espelho desta arvore mantido a mao (o `CLAUDE.md` da raiz ainda o cita). O pipeline
`pipeline-correcao-bugs` le e move so `docs/bugs/`; o briefing dele lista o que precisa ser replicado la.

## Nome e template

- Nome do arquivo: `AAAA-MM-DD-slug-curto.md` (ex.: `2026-07-01-catalogo-some-scan-biblioteca.md`).
- Titulo `# [BUG] <sintoma como o usuario ve>`; logo abaixo os blocos **Data**, **Status**, **Severidade**,
  **Branch** (e **Origem**: chamado, telemetria, relato do dono); depois as secoes **Sintoma**, **Evidencia**,
  **Causa-raiz**, **Correcao**, **Validacao** e **Licao**. Modelo: qualquer doc recente de `../done/`.
- Nunca apague um doc de bug: mova com `git mv`.

## Ciclo de pastas

```
open/<arquivo>.md      bug aberto
   |  [fix commitado + testado no aparelho (pelo pipeline ou a mao)]
   v
retest/<arquivo>.md    corrigido e testado aqui; falta versao publicada e/ou prova do cliente
   |  [versao publicada + cliente (ou o dono no aparelho dele) confirmou]
   v
done/<arquivo>.md      encerrado
```

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
