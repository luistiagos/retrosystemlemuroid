#!/usr/bin/env python3
"""
Validates every ROM entry in catalog_manifest.txt against the download endpoint.

For each entry:
  1. Calls find_by_file endpoint → gets download URL.
  2. Verifies the URL actually serves a non-empty file (Content-Length > 0,
     and falls back to a small streamed GET when the server doesn't report it).
  3. If endpoint returns null/404 → tries HuggingFace fallback URL.
  4. Removes entries unavailable in BOTH sources.

Why HEAD alone is not enough:
  Servers (HuggingFace, archive.org) often answer HEAD with 200 OK even when the
  underlying object is a 0-byte placeholder. The previous version of this script
  trusted HEAD and therefore kept every entry. We now require evidence of bytes.

Usage:
  python check_manifest.py                       # validate + remove bad entries
  python check_manifest.py --dry-run             # validate only, no file change
  python check_manifest.py --system psx          # restrict to one system
  python check_manifest.py --workers 3           # concurrent workers (default: 5)
  python check_manifest.py --test "psx/007.chd"  # debug single entry, verbose
  python check_manifest.py --min-bytes 1024      # min file size to accept (default: 1024)
"""

import argparse
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.parse import quote

import requests

MANIFEST_PATH = "lemuroid-app/src/main/assets/catalog_manifest.txt"
FIND_ENDPOINT = "https://emuladores.pythonanywhere.com/find_by_file"
HF_BASE = "https://huggingface.co/datasets/luisluis123/lemusets/resolve/main/roms"
TIMEOUT = 30

_session = requests.Session()
_session.headers["User-Agent"] = "Mozilla/5.0 (Android) LemuroidApp/1.0"
_lock = threading.Lock()
_done = 0
_total = 0
_verbose = False


def _log(msg: str) -> None:
    if _verbose:
        print(f"  · {msg}")


def _probe_url(url: str, min_bytes: int) -> tuple[bool, str]:
    """
    True iff URL serves a file of >= min_bytes.

    Strategy:
      1. HEAD with redirects → check status + Content-Length.
      2. If Content-Length missing or HEAD blocked, do streaming GET with
         Range: bytes=0-{min_bytes} and verify body bytes received.
      3. Network errors return True (uncertain → keep).
    """
    # Step 1: HEAD
    try:
        r = _session.head(url, timeout=TIMEOUT, allow_redirects=True)
        _log(f"HEAD {r.status_code} {url}")
        if r.status_code in (404, 410):
            return False, f"head-{r.status_code}"
        if r.status_code >= 500:
            return True, f"head-{r.status_code}-uncertain"

        cl = r.headers.get("Content-Length")
        if cl is not None:
            try:
                size = int(cl)
                _log(f"Content-Length: {size}")
                if size == 0:
                    return False, "head-content-length-0"
                if size < min_bytes:
                    return False, f"head-too-small-{size}"
                return True, f"head-ok-{size}"
            except ValueError:
                pass

        if r.status_code in (405, 501):
            _log("HEAD not allowed, falling through to GET")
    except requests.RequestException as exc:
        _log(f"HEAD error: {exc}")

    # Step 2: streaming GET with Range
    headers = {"Range": f"bytes=0-{max(min_bytes, 1024) - 1}"}
    try:
        with _session.get(url, headers=headers, stream=True, timeout=TIMEOUT,
                          allow_redirects=True) as r:
            _log(f"GET {r.status_code} {url}")
            if r.status_code in (404, 410):
                return False, f"get-{r.status_code}"
            if not r.ok and r.status_code != 206:
                return True, f"get-{r.status_code}-uncertain"
            received = 0
            for chunk in r.iter_content(chunk_size=4096):
                received += len(chunk)
                if received >= min_bytes:
                    break
            _log(f"received {received} bytes")
            if received == 0:
                return False, "get-empty-body"
            if received < min_bytes:
                return False, f"get-too-small-{received}"
            return True, f"get-ok-{received}"
    except requests.RequestException as exc:
        _log(f"GET error: {exc}")
        return True, f"net-err:{exc}"


def check_line(idx: int, line: str, filter_system: str | None,
               min_bytes: int) -> tuple[int, str, bool, str]:
    """Returns (original_index, original_line, keep, reason)."""
    stripped = line.rstrip("\n")
    if not stripped:
        return idx, line, True, "blank"

    parts = stripped.split("|")
    if len(parts) < 2:
        return idx, line, True, "malformed"

    path = parts[0]
    sep = path.find("/")
    if sep < 0:
        return idx, line, True, "no-system-sep"

    system = path[:sep]
    filename = path[sep + 1:]

    if filter_system and system != filter_system:
        return idx, line, True, "skipped"

    # 1. Query pythonanywhere endpoint
    ep_url = (
        f"{FIND_ENDPOINT}"
        f"?path={quote(filename)}&source_id=1&system={quote(system)}"
    )
    _log(f"endpoint: {ep_url}")
    download_url: str | None = None
    try:
        r = _session.get(ep_url, timeout=TIMEOUT)
        if r.status_code == 404:
            download_url = None
            _log("endpoint 404")
        elif r.ok:
            t = r.text.strip()
            download_url = t if t else None
            _log(f"endpoint url: {download_url!r}")
        else:
            return idx, line, True, f"endpoint-err-{r.status_code}"
    except requests.RequestException as exc:
        return idx, line, True, f"net-err:{exc}"

    if download_url:
        ok, reason = _probe_url(download_url, min_bytes)
        if ok:
            return idx, line, True, f"endpoint:{reason}"
        _log(f"endpoint URL failed ({reason}), trying HuggingFace")

    # 2. HuggingFace fallback
    hf_url = f"{HF_BASE}/{quote(system)}/{quote(filename)}"
    _log(f"huggingface: {hf_url}")
    ok, reason = _probe_url(hf_url, min_bytes)
    if ok:
        return idx, line, True, f"hf:{reason}"

    overall = (
        "endpoint-null+hf-dead" if download_url is None
        else "endpoint-url-dead+hf-dead"
    )
    return idx, line, False, overall


def test_single(entry_path: str, min_bytes: int) -> None:
    """Debug mode: probe one entry with verbose logging."""
    global _verbose
    _verbose = True
    print(f"Testing: {entry_path}")
    idx, line, keep, reason = check_line(0, entry_path + "|test|", None, min_bytes)
    print(f"\nResult: keep={keep} reason={reason}")


def main() -> None:
    global _done, _total, _verbose

    parser = argparse.ArgumentParser(description="Validate catalog_manifest.txt ROM entries")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--system", default=None)
    parser.add_argument("--workers", type=int, default=5)
    parser.add_argument("--min-bytes", type=int, default=1024,
                        help="Minimum acceptable file size in bytes (default: 1024)")
    parser.add_argument("--test", default=None,
                        help="Debug a single entry (e.g. 'psx/007 - foo.chd')")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    _verbose = args.verbose

    if args.test:
        test_single(args.test, args.min_bytes)
        return

    with open(MANIFEST_PATH, encoding="utf-8") as f:
        lines = f.readlines()

    filter_sys = args.system
    active_lines = [
        (i, l) for i, l in enumerate(lines)
        if l.strip() and (not filter_sys or l.startswith(f"{filter_sys}/"))
    ]
    _total = len(active_lines)
    print(f"Manifest: {len(lines)} lines | Checking: {_total} | Workers: {args.workers}"
          f" | min-bytes: {args.min_bytes}")
    if filter_sys:
        print(f"Filter: system={filter_sys}")
    if args.dry_run:
        print("DRY RUN — file will NOT be modified\n")
    else:
        print()

    results: dict[int, tuple[str, bool, str]] = {}
    start = time.monotonic()

    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        futures = {
            ex.submit(check_line, i, line, filter_sys, args.min_bytes): i
            for i, line in active_lines
        }
        for fut in as_completed(futures):
            idx, line, keep, reason = fut.result()
            results[idx] = (line, keep, reason)
            with _lock:
                _done += 1
                pct = _done * 100 // _total
                if not keep:
                    entry = line.split("|")[0].strip()
                    print(f"[{_done}/{_total} {pct}%] REMOVE  {entry}  ({reason})")
                elif _done % 200 == 0:
                    elapsed = time.monotonic() - start
                    eta = elapsed / _done * (_total - _done)
                    print(f"[{_done}/{_total} {pct}%] ...  ETA {eta:.0f}s")

    for i, line in enumerate(lines):
        if i not in results:
            results[i] = (line, True, "unchanged")

    removed: list[str] = []
    final_lines: list[str] = []
    for i in sorted(results):
        line, keep, _ = results[i]
        if keep:
            final_lines.append(line)
        else:
            removed.append(line.strip())

    elapsed = time.monotonic() - start
    print(f"\nFinished in {elapsed:.1f}s — removed {len(removed)}, kept {len(final_lines)} lines.")

    if removed:
        log_path = "removed_manifest_entries.txt"
        with open(log_path, "w", encoding="utf-8") as f:
            f.write("\n".join(removed) + "\n")
        print(f"Removed entries saved to: {log_path}")

    if not args.dry_run and removed:
        with open(MANIFEST_PATH, "w", encoding="utf-8") as f:
            f.writelines(final_lines)
        print(f"Manifest updated: {MANIFEST_PATH}")
    elif args.dry_run and removed:
        print("(dry-run: manifest NOT modified)")


if __name__ == "__main__":
    main()
