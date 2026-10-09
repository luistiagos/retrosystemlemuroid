"""
Adiciona `libandroid.so` ao DT_NEEDED do core Flycast (todas as ABIs, ambos os módulos).

POR QUE: o flycast_libretro_android.so do buildbot libretro é linkado sem -landroid.
O código do Flycast declara `ASharedMemory_create` como símbolo WEAK (resolvido em
runtime pela libandroid.so); sem a lib no DT_NEEDED o símbolo fica nulo e o core cai
no open("/dev/ashmem"), que é bloqueado por SELinux para apps com targetSdk >= 29
(este app usa 35). Resultado: alocação de vmem falha (errno 13 EACCES), fastmem
desliga, e o caminho fallback do dynarec crasha com SIGSEGV (trunca o tag 0xb4 dos
ponteiros do Android 11+). Com libandroid no DT_NEEDED, ASharedMemory_create resolve,
o fastmem liga e o Dreamcast funciona.

Ver: docs/bugs/done/2026-07-07-dreamcast-crash-boot-ashmem-libandroid.md

USO: rodar após atualizar o .so do Flycast a partir do buildbot, ANTES de buildar:
    pip install lief
    python patch_flycast_libandroid.py
Idempotente: pula arquivos que já têm libandroid.so no DT_NEEDED.
"""

from pathlib import Path

import lief

ROOT = Path(__file__).parent
MODULES = [
    ROOT / "lemuroid-cores" / "lemuroid_core_flycast" / "src" / "main" / "jniLibs",
    ROOT / "lemuroid-cores" / "bundled-cores" / "src" / "main" / "jniLibs",
]
ABIS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
SO_NAME = "libflycast_libretro_android.so"


def main() -> None:
    for jni_dir in MODULES:
        for abi in ABIS:
            so_path = jni_dir / abi / SO_NAME
            if not so_path.exists():
                print(f"AUSENTE  {so_path}")
                continue
            elf = lief.parse(str(so_path))
            needed = [
                str(e.name)
                for e in elf.dynamic_entries
                if e.tag == lief.ELF.DynamicEntry.TAG.NEEDED
            ]
            if "libandroid.so" in needed:
                print(f"ok       {so_path}")
                continue
            elf.add_library("libandroid.so")
            elf.write(str(so_path))
            print(f"PATCHADO {so_path}")


if __name__ == "__main__":
    main()
