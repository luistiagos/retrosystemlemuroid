# bugs/retest

Bugs com correcao **ja commitada e testada** (no aparelho de teste, pelo pipeline ou a mao), mas ainda sem versao
publicada para o cliente e/ou sem a prova do cliente. Ciclo completo em [../open/README.md](../open/README.md):
`open/` -> `retest/` -> `done/`.

## Por que separar de done

O teste daqui roda o APK debug num aparelho nosso (hoje o Galaxy A12). O que so o cliente prova (a versao publicada,
o aparelho dele — TV box, Android antigo, outro fabricante — e o jogo dele) ainda nao foi visto. Se o cliente ou a
proxima versao mostrar o sintoma de novo, o arquivo volta para [../open/](../open/) com nota da reincidencia.

## Status

Ao mover de open para retest, acrescente uma secao `## Status` no TOPO do arquivo, com:

- data e hora da correcao;
- hash do(s) commit(s) do fix;
- onde no codigo o fix foi aplicado (`arquivo::simbolo`);
- evidencia de cada nivel de teste (T2 caminho do cliente no aparelho, com o controle positivo na base; T3 testes de
  unidade e o caminho vizinho; T4 `adb logcat` do processo do teste), com o caminho da pasta de evidencia e o
  aparelho usado;
- **o que falta para `done/`**: versao publicada (do dono), o que depende de outro aparelho, e a prova do cliente.
