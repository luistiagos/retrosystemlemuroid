---
movido_por: Luis Tiago - 2026-11-13 - "migracao do layout antigo para o pipeline global (T23); estado decidido na T29"
---
# [BUG] As chaves do R2 vao na linha de comando do `rclone` no `build-and-upload.ps1`

## Status

- **Corrigido e testado em 2026-11-10** (commit desta correcao em `version10`; aplicado tambem em `spike-switch` e
  `spike-wii`, que tem o mesmo arquivo).
- Onde: `build-and-upload.ps1::Invoke-R2Rclone` (nova) e o bloco de upload para o R2 (`$s3Flags` e as 5 chamadas).
- Teste: ver Validacao. Nao ha aparelho envolvido (script de publicacao, nao o app).
- **Falta para `done/`:** a proxima publicacao real passar pelo R2 sem erro.

**Data:** 2026-11-10 · **Severidade:** media (seguranca) · **Branch:** version10

## Sintoma

O `$s3Flags` levava `--s3-access-key-id=<chave>` e `--s3-secret-access-key=<chave>`. A linha de comando de um processo
e legivel por qualquer processo do mesmo usuario (Gerenciador de Tarefas, `Win32_Process.CommandLine`, antivirus,
ferramentas de agente) enquanto o upload roda. Achado no repo `retrobatnew` (bug
`montaversao-chaves-do-r2-na-linha-de-comando-do-rclone`), onde a linha do `rclone` com as chaves acabou numa
transcricao de sessao; uma busca nos projetos que publicam no mesmo R2 achou a mesma forma aqui.

## Causa-raiz

Chave passada como argumento do processo filho.

## Correcao

`Invoke-R2Rclone $rclone $cfg <args>`: poe `RCLONE_S3_ACCESS_KEY_ID` / `RCLONE_S3_SECRET_ACCESS_KEY` (nomes de
`rclone help backend s3`, v1.75.0) no ambiente so durante a chamada, tira as `RCLONE_*` herdadas (em DEBUG o rclone
imprime o valor lido do ambiente) e devolve tudo no `finally`, para a chave nao ficar no PowerShell de quem rodou o
script. As duas flags sairam do `$s3Flags` e todo `& $rclone` virou `Invoke-R2Rclone $rclone $cfg`.

## Validacao

Harness descartavel sobre o script editado, chaves do R2 so no ambiente, `RCLONE_VERBOSE=2` + `RCLONE_LOG_LEVEL=DEBUG`
simulando DEBUG ligado:

- estatico: nenhuma flag de chave, nenhum `& $rclone` direto, funcao definida uma vez; parser do PowerShell sem erro;
- a funcao extraida do script, contra o R2 real (pasta `_teste-montaversao/`): `lsjson -R` rc 0; segredo errado -> 403
  `SignatureDoesNotMatch`; `copyto` de 32 KB com `--header-upload` e `--progress` rc 0, tamanho conferido;
  `deletefile` rc 0; pasta vazia no fim;
- nos 6 `rclone`, lidos pelo SO (psutil): chave fora da linha de comando, as duas no ambiente, nenhuma `RCLONE_*`
  herdada; depois, nenhuma chave no ambiente do PowerShell. Zero vazamentos na saida.

Limite: o build + publicacao inteiro nao foi rodado (publicaria uma versao).

## Licao

Chave nunca vai em argumento de processo filho; vai no ambiente dele, e so dele.
