"""
Convert RetroBat hardware system images to Lemuroid format.
Resizes to 720x332, centers with transparent background, saves as RGBA PNG.
"""
import os
from pathlib import Path
from PIL import Image

# Target dimensions
TARGET_W = 720
TARGET_H = 332

# Paths
RETROBAT_HW = Path(r"E:\projects\retrobatnew\dist\retrosystem\emulationstation\.emulationstation\themes\one4all4one-fanartstar-bobmorane\_systemmedia\hardware")
LEMUROID_DRAWABLE = Path(r"E:\projects\lemuroid\Lemuroid\retrograde-app-shared\src\main\res\drawable")

# Mapping: lemuroid filename -> retrobat filename
MAPPING = {
    "game_system_3ds.png": "3ds.png",
    "game_system_amstradcpc.png": "amstradcpc.png",
    "game_system_arcade.png": "arcade.png",
    "game_system_atari2600.png": "atari2600.png",
    "game_system_atari5200.png": "atari5200.png",
    "game_system_atari7800.png": "atari7800.png",
    "game_system_c64.png": "c64.png",
    "game_system_cave.png": "cave.png",
    "game_system_coleco.png": "colecovision.png",
    "game_system_cps1.png": "cps1.png",
    "game_system_cps2.png": "cps2.png",
    "game_system_cps3.png": "cps3.png",
    "game_system_dataeast.png": "dataeast.png",
    "game_system_dos.png": "dos.png",
    "game_system_ds.png": "nds.png",
    "game_system_fbneo.png": "fbneo.png",
    "game_system_galaxian.png": "galaxy.png",
    "game_system_gb.png": "gb.png",
    "game_system_gba.png": "gba.png",
    "game_system_gbc.png": "gbc.png",
    "game_system_genesis.png": "megadrive.png",
    "game_system_gg.png": "gamegear.png",
    "game_system_intellivision.png": "intellivision.png",
    "game_system_kaneko.png": "kaneko.png",
    "game_system_lynx.png": "atarilynx.png",
    "game_system_msx.png": "msx.png",
    "game_system_msx2.png": "msx2.png",
    "game_system_n64.png": "n64.png",
    "game_system_neogeo.png": "neogeo.png",
    "game_system_nes.png": "nes.png",
    "game_system_ngp.png": "ngp.png",
    "game_system_ngpc.png": "ngpc.png",
    "game_system_pce.png": "pcengine.png",
    "game_system_pcecd.png": "pcenginecd.png",
    "game_system_pgm.png": "pgm.png",
    "game_system_pokemini.png": "pokemini.png",
    "game_system_psikyo.png": "psikyo.png",
    "game_system_psp.png": "psp.png",
    "game_system_psx.png": "psx.png",
    "game_system_sc3000.png": "sc-3000.png",
    "game_system_scd.png": "megacd.png",
    "game_system_seta.png": "seta.png",
    "game_system_sg1000.png": "sg-1000.png",
    "game_system_sms.png": "mastersystem.png",
    "game_system_snes.png": "snes.png",
    "game_system_supervision.png": "supervision.png",
    "game_system_taito.png": "taito.png",
    "game_system_technos.png": "technos.png",
    "game_system_teoplan.png": "toaplan.png",
    "game_system_vb.png": "virtualboy.png",
    "game_system_vectrex.png": "vectrex.png",
    "game_system_ws.png": "wonderswan.png",
    "game_system_wsc.png": "wonderswancolor.png",
    "game_system_zxspectrum.png": "zxspectrum.png",
}

# Also convert the XML-only systems that have hardware images in RetroBat
XML_TO_PNG = {
    "game_system_3do.png": "3do.png",
    "game_system_dc.png": "dreamcast.png",
}


def convert_image(src_path: Path, dst_path: Path):
    """Open source image, fit into TARGET_W x TARGET_H with transparent padding."""
    img = Image.open(src_path).convert("RGBA")

    # Calculate scale to fit within target while preserving aspect ratio
    scale = min(TARGET_W / img.width, TARGET_H / img.height)
    new_w = int(img.width * scale)
    new_h = int(img.height * scale)

    # Resize with high quality
    resized = img.resize((new_w, new_h), Image.LANCZOS)

    # Create transparent canvas and paste centered
    canvas = Image.new("RGBA", (TARGET_W, TARGET_H), (0, 0, 0, 0))
    offset_x = (TARGET_W - new_w) // 2
    offset_y = (TARGET_H - new_h) // 2
    canvas.paste(resized, (offset_x, offset_y), resized)

    canvas.save(dst_path, "PNG", optimize=True)
    return new_w, new_h


def main():
    all_mappings = {**MAPPING, **XML_TO_PNG}
    converted = 0
    skipped = 0
    errors = []

    for lem_name, rb_name in sorted(all_mappings.items()):
        src = RETROBAT_HW / rb_name
        dst = LEMUROID_DRAWABLE / lem_name

        if not src.exists():
            errors.append(f"  MISSING: {rb_name}")
            continue

        try:
            new_w, new_h = convert_image(src, dst)
            converted += 1
            print(f"  OK: {lem_name} <- {rb_name} ({new_w}x{new_h} in {TARGET_W}x{TARGET_H})")
        except Exception as e:
            errors.append(f"  ERROR: {lem_name}: {e}")

    print(f"\n{'='*50}")
    print(f"Converted: {converted}")
    print(f"Errors: {len(errors)}")
    if errors:
        print("\n".join(errors))


if __name__ == "__main__":
    main()
