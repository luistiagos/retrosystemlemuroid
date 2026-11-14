# [BACKLOG] Atualização automática: pendências 4 e 5 (estado `Installing` sem saída; ignora "só Wi-Fi")

**Status:** pendente — aguarda análise do dono (2026-11-13: "coloque em backlog para analisarmos depois"), que decide se entra como funcionalidade ou é arquivado, verificado em 2026-11-13 (T29)

**Origem:** tabela "Pendências" de [`atualizacao-automatica.md`](../features/concluido/atualizacao-automatica/atualizacao-automatica.md),
itens 4 e 5. Na T29 (saneamento dos docs, 2026-11-13) os dois seguiam abertos no código de `version10` (`3145b78`).

## O que é

1. **Estado `Installing` não tem saída.** Se o usuário cancelar o prompt do sistema (Android 8–11, onde o prompt é
   obrigatório), o estado fica `Installing` para sempre; `checkManually()` passa a retornar cedo e o botão "Verificar
   atualizações" fica mudo até reiniciar o app. Impacto: médio (não testado; o aparelho de teste é Android 15 e
   instalou sem prompt).
2. **A atualização ignora "Baixar somente via WiFi".** A preferência `wifi_only_download` (em `streaming_roms_prefs`)
   vale só para ROMs; o `AppUpdateManager.kt` baixa ~110 MB sem consultá-la. Impacto: médio (usuário com plano
   limitado).

## A analisar

- Se cada item vira demanda (bug ou funcionalidade) ou é arquivado.
- Item 1: reproduzir num aparelho Android 8–11 cancelando o prompt.
- Item 2: se a preferência de ROMs deve valer também para a atualização do app, ou se é preciso uma opção própria.
