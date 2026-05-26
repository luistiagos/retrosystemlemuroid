"""
Gera entradas do Dreamcast para catalog_manifest.txt a partir do CSV do archive.org.

Formato de saída (pipe-delimited, sem header):
    dc/filename.zip|Título Limpo|https://libretro-thumbnails/.../filename.png|0|1

Uso:
    python generate_dreamcast_manifest.py               # appenda ao catalog_manifest.txt
    python generate_dreamcast_manifest.py --dry-run     # imprime sem escrever
    python generate_dreamcast_manifest.py --output out.txt  # arquivo alternativo
"""

import argparse
import csv
import re
import urllib.parse
from pathlib import Path

CSV_INPUT = Path(__file__).parent.parent.parent / "projects" / "romsrepository" / "dreamcast_archiveorg.csv"
# fallback: tenta no mesmo diretório do script
if not CSV_INPUT.exists():
    CSV_INPUT = Path(r"e:\projects\romsrepository\dreamcast_archiveorg.csv")

MANIFEST_FILE = Path(__file__).parent / "lemuroid-app" / "src" / "main" / "assets" / "catalog_manifest.txt"

LIBRETRO_BASE = "https://raw.githubusercontent.com/libretro-thumbnails/Sega_-_Dreamcast/master/Named_Boxarts"

# Patterns to strip from the end of a filename stem to produce the clean title.
# Order matters: more specific patterns first.
REGION_RE = re.compile(
    r"\s*\("
    r"(?:USA|Europe|Japan|World|Asia|Brazil|Australia|Korea|Germany|France|Spain|Italy|"
    r"Netherlands|Sweden|Russia|China|Taiwan|Hong Kong|"
    r"En(?:,[A-Za-z][a-z])*|Fr|De|Es|It|Nl|Pt|Ru|Ja|Zh|Ko|"
    r"Rev\s*[\w.]+|v\d[\w.]*|Proto(?:type)?|Beta|Demo|Sample|"
    r"Disc\s*\d+|Disk\s*\d+|CD\s*\d+|Side\s*[AB]|"
    r"En,Fr,De|En,Fr|En,De|En,Es|En,Fr,De,Es,It|"
    r"NTSC|PAL|NTSC-J|NTSC-U|"
    r"[A-Z][a-z]+(?:,[A-Z][a-z]+)+"
    r")\)",
    re.IGNORECASE,
)

BRACKET_RE = re.compile(r"\s*\[.*?\]")


def clean_title(stem: str) -> str:
    """Remove region/version/bracket tags from a ROM stem to get a displayable title."""
    title = BRACKET_RE.sub("", stem)
    # Iteratively strip trailing parenthetical groups until stable
    prev = None
    while prev != title:
        prev = title
        title = REGION_RE.sub("", title).strip()
    return title.strip(" -_")


def libretro_cover_url(stem: str) -> str:
    """Build the libretro-thumbnails boxart URL for a Dreamcast ROM stem."""
    encoded = urllib.parse.quote(stem, safe="")
    return f"{LIBRETRO_BASE}/{encoded}.png"


def generate_entries(csv_path: Path) -> list[str]:
    """Read the archive.org CSV and return manifest lines."""
    entries = []
    with csv_path.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            filename = row["name"].strip()           # e.g. "Sonic Adventure (USA).zip"
            if not filename:
                continue
            stem = Path(filename).stem               # "Sonic Adventure (USA)"
            title = clean_title(stem)
            cover = libretro_cover_url(stem)
            # dc/<filename>|title|coverUrl|popularityIndex|isRepresentative
            entries.append(f"dc/{filename}|{title}|{cover}|0|1")
    return entries


def main():
    parser = argparse.ArgumentParser(description="Generate Dreamcast catalog_manifest entries.")
    parser.add_argument("--dry-run", action="store_true", help="Print entries without writing.")
    parser.add_argument("--output", "-o", default=None, help="Output file (default: catalog_manifest.txt).")
    parser.add_argument("--csv", default=str(CSV_INPUT), help="Input CSV path.")
    args = parser.parse_args()

    csv_path = Path(args.csv)
    if not csv_path.exists():
        print(f"ERROR: CSV not found: {csv_path}")
        return

    entries = generate_entries(csv_path)
    print(f"Generated {len(entries)} Dreamcast entries.")

    if args.dry_run:
        for line in entries[:10]:
            print(line)
        if len(entries) > 10:
            print(f"... ({len(entries) - 10} more)")
        return

    output_path = Path(args.output) if args.output else MANIFEST_FILE
    # Append after the last existing entry
    with output_path.open("a", encoding="utf-8", newline="\n") as f:
        for line in entries:
            f.write(line + "\n")
    print(f"Appended {len(entries)} entries to: {output_path}")


if __name__ == "__main__":
    main()
