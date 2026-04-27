#!/usr/bin/env python3
"""
Filter a catalog_manifest_cover2d-style file and export only entries with missing image links.

Input line format:
    mnemonic/rom_path|image_url

A line is considered "missing" when:
- there is no pipe at all, OR
- the text after the first pipe is empty/whitespace.

Output CSV columns:
- mnemonic
- rom_path
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Extract items missing image links from catalog_manifest_cover2d.txt"
    )
    parser.add_argument(
        "--input",
        default="catalog_manifest_cover2d.txt",
        help="Path to input manifest file (default: catalog_manifest_cover2d.txt)",
    )
    parser.add_argument(
        "--output",
        default="missing_cover2d_for_upload.csv",
        help="Path to output CSV file (default: missing_cover2d_for_upload.csv)",
    )
    parser.add_argument(
        "--encoding",
        default="utf-8-sig",
        help="File encoding for input/output (default: utf-8-sig)",
    )
    return parser.parse_args()


def extract_manifest_path(line: str) -> tuple[str, bool]:
    """Return (manifest_path, has_image_link)."""
    if "|" in line:
        left, right = line.split("|", 1)
        return left.strip(), bool(right.strip())
    return line.strip(), False


def main() -> int:
    args = parse_args()
    input_path = Path(args.input)
    output_path = Path(args.output)

    if not input_path.exists():
        raise FileNotFoundError(f"Input file not found: {input_path}")

    total_lines = 0
    valid_manifest_lines = 0
    missing_rows: list[dict[str, str]] = []
    skipped_invalid = 0

    with input_path.open("r", encoding=args.encoding) as f:
        for raw in f:
            total_lines += 1
            line = raw.strip()
            if not line:
                continue

            manifest_path, has_link = extract_manifest_path(line)
            if not manifest_path:
                continue

            valid_manifest_lines += 1
            if has_link:
                continue

            if "/" not in manifest_path:
                skipped_invalid += 1
                continue

            mnemonic, rom_path = manifest_path.split("/", 1)
            mnemonic = mnemonic.strip()
            rom_path = rom_path.strip()
            if not mnemonic or not rom_path:
                skipped_invalid += 1
                continue

            missing_rows.append(
                {
                    "manifest_path": manifest_path,
                    "mnemonic": mnemonic,
                    "rom_path": rom_path,
                }
            )

    # Output only the required fields and force no double quotes in any line.
    # Use ';' as delimiter to reduce escaping for ROM names containing commas.
    with output_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(
            f,
            delimiter=";",
            quoting=csv.QUOTE_NONE,
            escapechar="\\",
            lineterminator="\n",
        )
        writer.writerow(["mnemonic", "rom_path"])
        for row in missing_rows:
            writer.writerow([row["mnemonic"], row["rom_path"]])

    print(f"Input file: {input_path}")
    print(f"Output CSV: {output_path}")
    print(f"Total lines read: {total_lines}")
    print(f"Valid manifest lines: {valid_manifest_lines}")
    print(f"Missing-link rows exported: {len(missing_rows)}")
    print(f"Skipped invalid rows: {skipped_invalid}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
