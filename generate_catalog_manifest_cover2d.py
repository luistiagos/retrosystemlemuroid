"""
Generate a text manifest similar to catalog_manifest.txt, but with a second
column carrying cover2d information fetched from romsrepository.

Output format (no header):

    system/rom-file.ext|https://.../cover.jpg

If no cover is available for an entry, the line is still emitted with an empty
right-hand side:

    system/rom-file.ext|
"""

from __future__ import annotations

import argparse
import json
import socket
import time
from pathlib import Path
from typing import Dict, Iterable, List
from urllib import error, request


DEFAULT_ENDPOINT = "https://emuladores.pythonanywhere.com/api/catalog/manifest"
FALLBACK_ENDPOINT = "https://emuladores.pythonanywhere.com/api/roms/fetch_roms_data"
DEFAULT_OUTPUT = "catalog_manifest_cover2d.txt"
DEFAULT_MANIFEST_INPUT = "catalog_manifest.txt"
DEFAULT_MNEMONICO_MAP = "mnemonico_map.json"
REQUEST_TIMEOUT_SECONDS = 600
DEFAULT_BATCH_SIZE = 1
DEFAULT_RETRIES = 2
INFO_COLUMNS = ["cover2d_url", "cover2d", "image_url", "thumbnail_url"]


def load_manifest_paths(manifest_path: Path, max_lines: int = 0) -> List[str]:
    lines: List[str] = []
    with manifest_path.open("r", encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip().lstrip("\ufeff")
            if not line:
                continue
            lines.append(line)
            if max_lines > 0 and len(lines) >= max_lines:
                break
    if not lines:
        raise ValueError(f"No paths found in manifest input: {manifest_path}")
    return lines


def extract_mnemonicos_from_paths(paths: List[str]) -> List[Dict[str, str]]:
    seen = set()
    result: List[Dict[str, str]] = []
    for path in paths:
        if "/" not in path:
            continue
        mnemonic = path.split("/", 1)[0].strip()
        if not mnemonic or mnemonic in seen:
            continue
        seen.add(mnemonic)
        result.append({"name": mnemonic})
    if not result:
        raise ValueError("Could not derive mnemonicos from manifest paths")
    return result


def load_mnemonico_aliases(map_path: Path) -> Dict[str, str]:
    if not map_path.exists():
        return {}
    with map_path.open("r", encoding="utf-8") as handle:
        mapping = json.load(handle)
    if not isinstance(mapping, dict):
        return {}
    return {
        str(src).strip(): str(dst).strip()
        for src, dst in mapping.items()
        if str(src).strip() and str(dst).strip()
    }


def build_query_mnemonicos(base_mnemonicos: List[Dict[str, str]], alias_map: Dict[str, str]) -> List[Dict[str, str]]:
    seen = set()
    result: List[Dict[str, str]] = []
    for entry in base_mnemonicos:
        base_name = entry["name"]
        query_name = alias_map.get(base_name, base_name)
        if query_name in seen:
            continue
        seen.add(query_name)
        result.append({"name": query_name})
    return result


def build_payload(mnemonicos: List[Dict[str, str]]) -> Dict:
    payload = {
        "mnemonicos": [],
        "group_by_mnemonic": False,
        "check_links": False,
    }
    for entry in mnemonicos:
        payload["mnemonicos"].append(
            {
                "name": entry["name"],
                "infos": INFO_COLUMNS,
            }
        )
    return payload


def chunked(items: List[Dict[str, str]], batch_size: int) -> Iterable[List[Dict[str, str]]]:
    for start in range(0, len(items), batch_size):
        yield items[start:start + batch_size]


def post_json(url: str, payload: Dict, timeout_seconds: int, retries: int) -> Dict:
    raw_body = json.dumps(payload).encode("utf-8")
    req = request.Request(
        url,
        data=raw_body,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": "Mozilla/5.0",
        },
        method="POST",
    )

    attempts = retries + 1
    for attempt in range(1, attempts + 1):
        try:
            with request.urlopen(req, timeout=timeout_seconds) as response:
                charset = response.headers.get_content_charset() or "utf-8"
                response_text = response.read().decode(charset)
            break
        except error.HTTPError as exc:
            details = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"HTTP {exc.code} calling {url}: {details}") from exc
        except (error.URLError, TimeoutError, socket.timeout) as exc:
            if attempt == attempts:
                raise RuntimeError(f"Failed to call {url}: {exc}") from exc
            print(f"Retrying {url} (attempt {attempt + 1}/{attempts}) after error: {exc}")
            time.sleep(min(5 * attempt, 15))

    try:
        return json.loads(response_text)
    except json.JSONDecodeError as exc:
        raise RuntimeError("Endpoint did not return valid JSON") from exc


def resolve_cover2d(entry: Dict) -> str:
    return (
        entry.get("cover2d_url")
        or entry.get("cover2d")
        or entry.get("image_url")
        or entry.get("thumbnail_url")
        or ""
    )


def build_entries_from_fetch_roms_data(response: Dict, mnemonicos: List[Dict[str, str]]) -> List[Dict]:
    if not isinstance(response, dict):
        raise RuntimeError("Fallback endpoint response must be a JSON object")

    ordered_entries: List[Dict] = []
    for mnemonic_entry in mnemonicos:
        mnemonic_name = mnemonic_entry["name"]
        roms = response.get(mnemonic_name)
        if not isinstance(roms, list):
            continue

        for rom in roms:
            if not isinstance(rom, dict):
                continue
            path = (rom.get("path") or "").strip()
            if not path:
                continue
            ordered_entries.append({
                "manifest_path": f"{mnemonic_name}/{path}",
                **rom,
            })

    return ordered_entries


def fetch_manifest_entries(
    endpoint: str,
    mnemonicos: List[Dict[str, str]],
    batch_size: int,
    timeout_seconds: int,
    retries: int,
) -> List[Dict]:
    payload = build_payload(mnemonicos)

    try:
        response = post_json(endpoint, payload, timeout_seconds, retries)
        entries = response.get("entries")
        if not isinstance(entries, list):
            raise RuntimeError("Endpoint response must contain an 'entries' array")
        return entries
    except RuntimeError as exc:
        if "/api/catalog/manifest" not in endpoint or "HTTP 404" not in str(exc):
            raise

    aggregated_entries: List[Dict] = []
    for index, batch in enumerate(chunked(mnemonicos, batch_size), start=1):
        batch_payload = build_payload(batch)
        fallback_response = post_json(FALLBACK_ENDPOINT, batch_payload, timeout_seconds, retries)
        batch_entries = build_entries_from_fetch_roms_data(fallback_response, batch)
        batch_names = ",".join(item["name"] for item in batch)
        print(f"Fetched batch {index} ({batch_names}): {len(batch_entries)} entries")
        aggregated_entries.extend(batch_entries)

    if not aggregated_entries:
        raise RuntimeError(
            "Fallback endpoint returned no entries. Check mnemonicos, source data, or deployment state."
        )
    return aggregated_entries


def iter_manifest_lines(entries: Iterable[Dict]) -> Iterable[str]:
    for entry in entries:
        manifest_path = (entry.get("manifest_path") or "").strip()
        if not manifest_path:
            continue
        cover2d = resolve_cover2d(entry).strip()
        yield f"{manifest_path}|{cover2d}"


def build_cover_map(entries: Iterable[Dict]) -> Dict[str, str]:
    cover_map: Dict[str, str] = {}
    for entry in entries:
        manifest_path = (entry.get("manifest_path") or "").strip()
        if not manifest_path:
            continue
        cover = resolve_cover2d(entry).strip()
        # Keep first non-empty cover seen for the same manifest path.
        if manifest_path not in cover_map or (not cover_map[manifest_path] and cover):
            cover_map[manifest_path] = cover
    return cover_map


def augment_cover_map_with_aliases(cover_map: Dict[str, str], alias_map: Dict[str, str]) -> Dict[str, str]:
    if not alias_map:
        return cover_map

    reverse_alias_map = {dst: src for src, dst in alias_map.items() if dst}
    augmented = dict(cover_map)

    for manifest_path, cover in cover_map.items():
        if "/" not in manifest_path:
            continue
        prefix, rest = manifest_path.split("/", 1)

        if prefix in reverse_alias_map:
            short_prefix = reverse_alias_map[prefix]
            short_key = f"{short_prefix}/{rest}"
            if short_key not in augmented or (not augmented[short_key] and cover):
                augmented[short_key] = cover

        if prefix in alias_map:
            long_prefix = alias_map[prefix]
            long_key = f"{long_prefix}/{rest}"
            if long_key not in augmented or (not augmented[long_key] and cover):
                augmented[long_key] = cover

    return augmented


def iter_manifest_lines_from_base(base_paths: Iterable[str], cover_map: Dict[str, str]) -> Iterable[str]:
    for manifest_path in base_paths:
        cover = cover_map.get(manifest_path, "")
        yield f"{manifest_path}|{cover}"


def write_output(output_path: Path, lines: Iterable[str]) -> int:
    written = 0
    with output_path.open("w", encoding="utf-8", newline="\n") as handle:
        for line in lines:
            handle.write(line)
            handle.write("\n")
            written += 1
    return written


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate catalog_manifest-style text with cover2d URLs."
    )
    parser.add_argument(
        "--endpoint",
        default=DEFAULT_ENDPOINT,
        help=f"Catalog endpoint URL (default: {DEFAULT_ENDPOINT})",
    )
    parser.add_argument(
        "--manifest-input",
        default=DEFAULT_MANIFEST_INPUT,
        help=f"Base manifest file path (default: {DEFAULT_MANIFEST_INPUT})",
    )
    parser.add_argument(
        "--mnemonico-map",
        default=DEFAULT_MNEMONICO_MAP,
        help=f"Mnemonic alias map JSON (default: {DEFAULT_MNEMONICO_MAP})",
    )
    parser.add_argument(
        "--output",
        default=DEFAULT_OUTPUT,
        help=f"Output file path (default: {DEFAULT_OUTPUT})",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=DEFAULT_BATCH_SIZE,
        help=f"Fallback batch size for /api/roms/fetch_roms_data (default: {DEFAULT_BATCH_SIZE})",
    )
    parser.add_argument(
        "--mnemonicos",
        default="",
        help="Comma-separated mnemonic names to limit backend fetch (optional)",
    )
    parser.add_argument(
        "--max-lines",
        type=int,
        default=0,
        help="Limit number of input manifest lines for quick tests (default: 0 = all)",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=REQUEST_TIMEOUT_SECONDS,
        help=f"Per-request timeout in seconds (default: {REQUEST_TIMEOUT_SECONDS})",
    )
    parser.add_argument(
        "--retries",
        type=int,
        default=DEFAULT_RETRIES,
        help=f"Retries per request on network timeout/error (default: {DEFAULT_RETRIES})",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    manifest_input_path = Path(args.manifest_input)
    mnemonico_map_path = Path(args.mnemonico_map)
    output_path = Path(args.output)

    if args.batch_size <= 0:
        raise RuntimeError("--batch-size must be greater than zero")
    if args.timeout <= 0:
        raise RuntimeError("--timeout must be greater than zero")
    if args.retries < 0:
        raise RuntimeError("--retries must be zero or greater")
    if args.max_lines < 0:
        raise RuntimeError("--max-lines must be zero or greater")

    base_paths = load_manifest_paths(manifest_input_path, max_lines=args.max_lines)
    alias_map = load_mnemonico_aliases(mnemonico_map_path)
    base_mnemonicos = extract_mnemonicos_from_paths(base_paths)
    mnemonicos = build_query_mnemonicos(base_mnemonicos, alias_map)
    if args.mnemonicos:
        requested = {item.strip() for item in args.mnemonicos.split(",") if item.strip()}
        base_mnemonicos = [entry for entry in base_mnemonicos if entry["name"] in requested]
        mnemonicos = build_query_mnemonicos(base_mnemonicos, alias_map)
        if not mnemonicos:
            raise RuntimeError("No valid mnemonicos matched --mnemonicos")
        base_paths = [
            path for path in base_paths
            if "/" in path and path.split("/", 1)[0] in requested
        ]

    entries = fetch_manifest_entries(
        args.endpoint,
        mnemonicos,
        args.batch_size,
        args.timeout,
        args.retries,
    )

    cover_map = build_cover_map(entries)
    cover_map = augment_cover_map_with_aliases(cover_map, alias_map)
    written = write_output(output_path, iter_manifest_lines_from_base(base_paths, cover_map))
    print(f"Wrote {written} lines to {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())