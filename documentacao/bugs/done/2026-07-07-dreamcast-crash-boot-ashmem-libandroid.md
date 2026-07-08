# [BUG] Dreamcast crasha no boot — vmem EACCES (ashmem) por falta de libandroid no core

**Data:** 2026-07-07
**Status:** Resolvido ✅
**Severidade:** Alta (sistema Dreamcast inteiro inoperante)
**Branch:** version9

---

## Sintoma

Qualquer jogo de Dreamcast (core Flycast do buildbot libretro) crasha com SIGSEGV
(`SEGV_ACCERR`) ~1–2 segundos após o boot, tanto com BIOS real quanto com HLE BIOS.
Investigação anterior (maio/junho, ver backlog `dreamcast-flycast-standalone-embed.md`)
havia concluído que o caminho libretro era "whack-a-mole" e recomendado embutir o
motor standalone do Flycast (~6–8 dias de trabalho).

Logcat do crash (nightly de 07/07/2026, moto g86 5G, Android 16):

```
W Libretro Core: [VMEM] Virtual memory file allocation failed: errno 13
W Libretro Core: [VMEM] Warning! nvmem is DISABLED (due to failure or not being built-in
I Libretro Core: [VMEM] BASE 0x0 RAM(16 MB) 0xb400007b5e601000 ...
I Libretro Core: [BOOT] Game ID is [MK-51049]
E Libretro Core: [COMMON] SIGSEGV @ 0x7b67e63c34 invalid access to 0x7b5e6c1a00
F libc          : Fatal signal 11 (SIGSEGV), code 2 (SEGV_ACCERR) ...
```

## Causa-raiz

Cadeia de 3 fatos, todos necessários:

1. **O `.so` do buildbot não linka `libandroid.so`.** O Flycast
   (`core/linux/posix_vmem.cpp`, upstream flyinghead) aloca o arquivo de memória
   compartilhada do fastmem via `ASharedMemory_create`, declarado como **símbolo
   weak** — resolvido em runtime pelo dynamic linker *somente se* `libandroid.so`
   estiver no DT_NEEDED. O binário do buildbot lista apenas `libm/libdl/libc`
   → o símbolo fica **nulo** → cai no fallback `open("/dev/ashmem")`.

2. **`/dev/ashmem` é bloqueado por SELinux para apps com targetSdk ≥ 29.**
   Este app usa `targetSdk = 35` → `EACCES` (errno 13). No Android não existe
   outro fallback no código (o caminho shm_open/arquivo em disco é `#else`,
   compilado fora). Por isso o RetroArch oficial (que manteve targetSdk 28)
   roda o MESMO core sem problema — a diferença nunca foi o LibretroDroid.

3. **Com fastmem desligado, o caminho fallback do dynarec é incompatível com
   pointer tagging do Android 11+.** A RAM vem de `malloc` com tag `0xb4` no
   byte alto (`0xb400007b5e601000`); o código JIT trunca o tag e acessa
   `0x7b5e6c1a00` → `SEGV_ACCERR`. (O fault addr do logcat é exatamente o
   endereço da RAM sem o tag — foi o que denunciou o problema.)

A investigação de junho nunca viu o item 1–2 (o warning `errno 13` passou
despercebido) e atacou o item 3 recompilando o core com fastmem desligado —
justamente o caminho quebrado. O fastmem real nunca tinha sido exercitado.

## Correção

**Patch de ELF nos `.so` empacotados: adicionar `libandroid.so` ao DT_NEEDED.**
Com isso `ASharedMemory_create` resolve (API pública do NDK, permitida para
qualquer targetSdk), a alocação de vmem funciona, o fastmem liga
(`[VMEM] Info: nvmem is enabled`, BASE ≠ 0) e o jogo roda.

- Script idempotente: [`patch_flycast_libandroid.py`](../../../patch_flycast_libandroid.py)
  (usa `lief`; roda nas 4 ABIs × 2 módulos: `lemuroid_core_flycast` e `bundled-cores`).
- Core atualizado para o nightly do buildbot de 07/07/2026 (md5 arm64 pré-patch
  `0774c3ef84bdba5a2ca5b2caefa47ab9`) — o crash NÃO era regressão de nightly;
  o patch é necessário em qualquer build do buildbot.
- `reicast_hle_bios` revertido para `disabled` em `GameSystem.kt` (resíduo de
  experimento; o BIOS real é baixado pelo `BiosDownloader`).
- `CoreUpdaterImpl` (flavor free) prioriza o core bundled do APK e, quando baixa,
  baixa do GitHub `luistiagos/libretrocores` (mesmo conteúdo do submodule) — commitar
  os `.so` patchados no submodule cobre os dois caminhos.

**Ao atualizar o core Flycast no futuro: rodar `python patch_flycast_libandroid.py`
antes de buildar.** Sem isso o Dreamcast volta a crashar.

## Validação

- Device: moto g86 5G (arm64, Android 16), APK `freeBundleDebug`.
- ROM: `ChuChu Rocket! (USA).chd` em `roms/dc/` + BIOS `dc_boot.bin`/`dc_flash.bin`
  em `system/dc/` (mesmos hashes do `BiosManager`).
- Antes do patch: SIGSEGV ~0,7 s após `retro_load_game` (log acima).
- Depois do patch: `nvmem is enabled`, BASE `0x7b49880000`, boot completo do BIOS
  ("PRESENTED BY SEGA") → intro do jogo ("SONIC TEAM PRESENTS"), minutos rodando,
  zero sinais fatais no logcat.

## Lição

1. **Warnings de inicialização de memória de emulador não são ruído.** O
   `errno 13` no primeiro log do core era a causa-raiz de toda a cadeia; os
   crashes eram só o sintoma distante.
2. **"Funciona no RetroArch" ≠ "o problema é o frontend".** A diferença real
   era o `targetSdk` (28 vs 35) mudando a política de SELinux para `/dev/ashmem`.
3. Símbolos weak em cores do buildbot podem estar permanentemente não-resolvidos
   por falta de DT_NEEDED — verificar com `lief`/`readelf` antes de assumir que
   um recurso "existe" no binário.
4. Endereço de fault igual ao endereço válido sem o byte alto (`0xb4…` → `0x00…`)
   = truncamento de pointer tag (Android 11+ heap tagging), não corrupção aleatória.
