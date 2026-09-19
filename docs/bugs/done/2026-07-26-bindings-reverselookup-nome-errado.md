# [BUG] Tela de Controles exibia o nome errado em todos os botões, em qualquer controle

**Data:** 2026-07-26
**Status:** Resolvido ✅
**Severidade:** Média (cosmético, mas atingia os 10 botões customizáveis de todo controle)
**Branch:** version9

---

## Sintoma

Em **Configurações → Controles**, cada botão customizável mostra como vínculo um nome
numérico em vez do nome real do botão do controle:

```
Botão A     ->  BUTTON_2      (deveria ser BUTTON_B)
Botão B     ->  BUTTON_1      (deveria ser BUTTON_A)
Start       ->  BUTTON_10     (deveria ser BUTTON_START)
Select      ->  BUTTON_9      (deveria ser BUTTON_SELECT)
L1/L2/R1/R2 ->  BUTTON_7/5/8/6
```

Atinge os **10** botões customizáveis e **qualquer** controle — Xbox, PS4, genérico —
não só o stick arcade. Mesmo efeito na tela de Controles da TV.

O mapeamento em si funcionava; só a exibição estava errada. Ainda assim confunde o
remapeamento manual, que é justamente o recurso a que o usuário recorre quando o controle
não responde direito.

## Causa-raiz

Introduzido pelo patch de 2026-07-23, que adicionou overrides de stick arcade
(`BUTTON_1..10 -> botões retro`) ao mapa de bindings padrão sem notar que **a ordem do
mapa é significativa**.

`InputDevicesSettingsViewModel` inverte o mapa de bindings para descobrir qual `InputKey`
está vinculada a cada `RetroKey`:

```kotlin
val keys = allBindings(device).reverseLookup()
```

E `reverseLookup` ([MapUtils.kt](../../../retrograde-util/src/main/java/com/swordfish/lemuroid/common/kotlin/MapUtils.kt)) é:

```kotlin
fun <K, V> Map<K, V>.reverseLookup(): Map<V, K> = entries.associateBy({ it.value }, { it.key })
```

`GamePadPreferencesHelper` (TV) faz o equivalente com
`.map { it.value to it.key }.toMap()`.

Várias `InputKey` apontam para a mesma `RetroKey` — `BUTTON_A` e `BUTTON_1` ambos viram
`BUTTON_B`. Dois contratos documentados da stdlib do Kotlin decidem quem vence:

- **`associateBy`**: *"If any two elements would have the same key returned by
  [keySelector] the last one gets added to the map."*
- **`Map.plus`**: *"The returned map preserves the entry iteration order of the original
  map. Those entries of another map that are missing in this map are iterated in the end
  in the order of that map."*

Como `BUTTON_1..10` são chaves **novas**, o `allAvailableInputs + defaultOverride` as
jogava no **fim** do mapa. Sendo as últimas, ganhavam a inversão.

## Correção

Commit `dee8db4`. `getDefaultBindings()` passou a montar o mapa em **três blocos
ordenados**, com os overrides de arcade primeiro:

```kotlin
return genericArcadeOverride + allAvailableInputs + faceButtonSwap
```

Ordem de iteração resultante: `BUTTON_1..10`, depois `A, B, X, Y, START, ...` (o
`faceButtonSwap` atualiza `A/B/X/Y` **na posição original**, por contrato do `Map.plus`).
Na inversão, `BUTTON_A -> BUTTON_B` vem depois de `BUTTON_1 -> BUTTON_B` e vence.

**O mapa direto é idêntico** nas duas ordens — só a ordem de iteração muda. Nada do
comportamento funcional foi alterado.

## Validação

```powershell
./gradlew.bat :lemuroid-app:compileFreeBundleDebugKotlin   # BUILD SUCCESSFUL
```

Equivalência do mapa direto e correção da inversão verificadas simulando a semântica de
`LinkedHashMap`, e depois confirmadas contra os contratos de `Map.plus` e `associateBy`
lidos direto nas fontes da stdlib (`kotlin-stdlib-1.9.23-sources.jar`) — são garantias
documentadas de API, não detalhe de implementação.

Sem validação em device.

## Lição

**A ordem do mapa retornado por `getDefaultBindings()` faz parte do contrato**, porque a
tela de Controles o inverte e a inversão é last-wins. Ao adicionar qualquer override de
binding, colocá-lo **antes** do bloco identidade — senão ele sequestra o nome exibido de
todo `RetroKey` que compartilhar.

Mais geral: `associateBy` para inverter um mapa é silenciosamente destrutivo quando os
valores não são únicos. Um mapa `InputKey -> RetroKey` **não** é injetivo por natureza —
vários botões físicos podem apontar para o mesmo botão retro — então qualquer inversão
dele é ambígua por construção e depende de ordem.
