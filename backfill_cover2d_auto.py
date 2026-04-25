#!/usr/bin/env python3
"""
Automatic cover2d backfill orchestrator.
Uploads missing items CSV to backend endpoint and iterates until coverage is maximized.
"""

import csv
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from urllib.parse import urljoin

import requests


# Configuration
BACKEND_URL = "https://emuladores.pythonanywhere.com"
ENDPOINT = "/api/medias/fill_missing_from_csv"
MAX_ITERATIONS = 10
MIN_MISSING_THRESHOLD = 100  # Stop if fewer than this many items missing
TIMEOUT = 90
INITIAL_CHUNK_SIZE = 100
MIN_CHUNK_SIZE = 25
MAX_UPLOAD_RETRIES = 4
POLL_INTERVAL_SECONDS = 5
POLL_TIMEOUT_SECONDS = 7200
STATUS_404_MAX_RETRIES = 12

# Local paths
SCRIPT_DIR = Path(__file__).parent
MANIFEST_FILE = SCRIPT_DIR / "catalog_manifest.txt"
MANIFEST_OUTPUT = SCRIPT_DIR / "catalog_manifest_cover2d.txt"
MISSING_CSV = SCRIPT_DIR / "missing_cover2d_items.csv"


def _setup_session() -> requests.Session:
    """Create a plain requests session; retries are handled manually per upload attempt."""
    return requests.Session()


def upload_missing_csv(
    csv_path: Path,
    dry_run: bool = False,
    session: Optional[requests.Session] = None,
    limit: Optional[int] = None,
    async_mode: bool = True,
    max_attempts: int = MAX_UPLOAD_RETRIES,
) -> Optional[dict]:
    """
    Upload missing items CSV to backend endpoint.
    Returns the response JSON (backfill summary) or None on failure.
    """
    if session is None:
        session = _setup_session()

    if not csv_path.exists():
        print(f"❌ CSV file not found: {csv_path}")
        return None

    url = f"{BACKEND_URL}{ENDPOINT}"
    file_size = csv_path.stat().st_size
    print(f"\n{'='*70}")
    print(f"📤 UPLOADING: {csv_path.name} ({file_size:,} bytes)")
    print(f"🔗 Endpoint: {url}")
    print(f"🔄 Dry-run: {dry_run}")
    if limit is not None:
        print(f"📏 Limit: {limit}")
    print(f"{'='*70}")

    for attempt in range(1, max_attempts + 1):
        try:
            with open(csv_path, "rb") as f:
                files = {"file": f}
                data = {"dry_run": "true" if dry_run else "false"}
                data["async"] = "true" if async_mode else "false"
                if limit is not None:
                    data["limit"] = str(limit)
                response = session.post(url, files=files, data=data, timeout=TIMEOUT)

            if response.status_code == 200:
                result = response.json()
                print(f"✅ Upload successful!")
                print(f"\n📊 Backfill Summary:")
                print(f"  Total CSV items:       {result.get('total_csv_items', 0):,}")
                print(f"  Processed:             {result.get('processed', 0):,}")
                print(f"  DB not found:          {result.get('db_not_found', 0):,}")
                print(f"  INFO records created:  {result.get('info_created', 0):,}")
                print(f"  Rows updated:          {result.get('updated_rows', 0):,}")
                print(f"  Cover2d populated:     {result.get('cover2d_populated', 0):,}")
                print(f"  Already has cover2d:   {result.get('already_has_cover2d', 0):,}")
                print(f"  No remote data:        {result.get('no_remote_data', 0):,}")
                if result.get('errors'):
                    print(f"  Errors:                {result.get('errors', 0):,}")
                return result

            if response.status_code == 202:
                result = response.json() if response.content else {}
                print("✅ Backfill accepted in async mode.")
                print(f"   Job ID: {result.get('job_id', 'unknown')}")
                print("   Check PythonAnywhere logs for completion.")
                return {
                    "async_accepted": True,
                    "job_id": result.get("job_id"),
                    "status_url": result.get("status_url"),
                    "processed": 0,
                    "db_not_found": 0,
                    "info_created": 0,
                    "updated_rows": 0,
                    "cover2d_populated": 0,
                    "already_has_cover2d": 0,
                    "no_remote_data": 0,
                    "errors": 0,
                }

            if response.status_code in {502, 503, 504} and attempt < max_attempts:
                wait_seconds = min(60, 5 * (2 ** (attempt - 1)))
                print(f"⚠️  Server returned {response.status_code}. Retry {attempt}/{max_attempts} in {wait_seconds}s...")
                time.sleep(wait_seconds)
                continue

            if response.status_code == 429:
                detail = {}
                try:
                    detail = response.json() if response.content else {}
                except Exception:
                    detail = {}
                if isinstance(detail, dict) and detail.get("status") == "busy":
                    print("⚠️  Backend reports another async backfill job is already running.")
                    return {
                        "async_busy": True,
                        "error": detail.get("error") or "Backend busy",
                        "max_concurrent_jobs": detail.get("max_concurrent_jobs"),
                    }

            print(f"❌ Upload failed with status {response.status_code}")
            try:
                error_detail = response.json()
                print(f"   Error: {error_detail}")
            except Exception:
                print(f"   Response: {response.text[:200]}")
            return None

        except requests.RequestException as e:
            if attempt < max_attempts:
                wait_seconds = min(60, 5 * (2 ** (attempt - 1)))
                print(f"⚠️  Request exception: {e}")
                print(f"   Retry {attempt}/{max_attempts} in {wait_seconds}s...")
                time.sleep(wait_seconds)
                continue
            print(f"❌ Exception during upload: {e}")
            return None

    return None


def poll_backfill_job_status(
    job_id: str,
    session: Optional[requests.Session] = None,
    status_url: Optional[str] = None,
    interval_seconds: int = POLL_INTERVAL_SECONDS,
    timeout_seconds: int = POLL_TIMEOUT_SECONDS,
) -> Optional[Dict]:
    """Poll backend job status until it completes or fails."""
    if session is None:
        session = _setup_session()

    if status_url:
        # Backend may return relative paths like /api/...; convert to absolute URL.
        status_url = urljoin(f"{BACKEND_URL}/", status_url)
    else:
        status_url = f"{BACKEND_URL}{ENDPOINT}/status/{job_id}"
    started_at = time.time()
    last_status = None
    not_found_retries = 0

    print(f"\n🔎 Monitoring async job: {job_id}")
    print(f"🔗 Status URL: {status_url}")
    print(f"⏱️  Poll interval: {interval_seconds}s")

    while True:
        elapsed = int(time.time() - started_at)
        if elapsed > timeout_seconds:
            print(f"❌ Job polling timed out after {timeout_seconds}s")
            return None

        try:
            response = session.get(status_url, timeout=TIMEOUT)
        except requests.RequestException as e:
            print(f"⚠️  Status check failed: {e}. Retrying in {interval_seconds}s...")
            time.sleep(interval_seconds)
            continue

        if response.status_code == 404:
            not_found_retries += 1
            if not_found_retries >= STATUS_404_MAX_RETRIES:
                print("❌ Status endpoint did not find this job after multiple retries.")
                print("   The server may be running an old version without job-tracking endpoints.")
                print("   Please deploy/reload mysite/flask_app.py and try again.")
                return None
            print(f"⚠️  Job {job_id} not found yet. Retrying in {interval_seconds}s...")
            time.sleep(interval_seconds)
            continue
        not_found_retries = 0

        if response.status_code != 200:
            print(f"⚠️  Status check returned {response.status_code}. Retrying in {interval_seconds}s...")
            time.sleep(interval_seconds)
            continue

        try:
            payload = response.json()
        except Exception as e:
            print(f"⚠️  Invalid status response JSON: {e}. Retrying in {interval_seconds}s...")
            time.sleep(interval_seconds)
            continue

        status = (payload.get("status") or "unknown").strip().lower()
        if status != last_status:
            print(f"📌 Job status: {status} (elapsed: {elapsed}s)")
            last_status = status

        if status == "failed":
            print("❌ Async backfill job failed.")
            err = payload.get("error")
            if err:
                print(f"   Error: {err}")
            return payload

        if status == "completed":
            print("✅ Async backfill job completed.")
            result = payload.get("result") or {}
            if isinstance(result, dict):
                print("📊 Backfill Summary (async result):")
                print(f"  Total CSV items:       {result.get('total_csv_items', 0):,}")
                print(f"  Processed:             {result.get('processed', 0):,}")
                print(f"  DB not found:          {result.get('db_not_found', 0):,}")
                print(f"  INFO records created:  {result.get('info_created', 0):,}")
                print(f"  Rows updated:          {result.get('updated_rows', 0):,}")
                print(f"  Cover2d populated:     {result.get('cover2d_populated', 0):,}")
                print(f"  Already has cover2d:   {result.get('already_has_cover2d', 0):,}")
                print(f"  No remote data:        {result.get('no_remote_data', 0):,}")
                print(f"  Errors:                {result.get('errors', 0):,}")
            return payload

        time.sleep(interval_seconds)


def read_missing_rows(csv_path: Path) -> List[dict]:
    """Read CSV rows to allow adaptive chunk uploads."""
    rows: List[dict] = []
    with open(csv_path, "r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            manifest_path = (row.get("manifest_path") or "").strip()
            mnemonic = (row.get("mnemonic") or "").strip()
            rom_path = (row.get("rom_path") or "").strip()
            if manifest_path and mnemonic and rom_path:
                rows.append(
                    {
                        "manifest_path": manifest_path,
                        "mnemonic": mnemonic,
                        "rom_path": rom_path,
                    }
                )
    return rows


def upload_missing_csv_adaptive(
    csv_path: Path,
    dry_run: bool = False,
    session: Optional[requests.Session] = None,
    initial_chunk_size: int = INITIAL_CHUNK_SIZE,
) -> Optional[dict]:
    """Upload CSV in adaptive chunks, shrinking chunk size when requests fail."""
    if session is None:
        session = _setup_session()

    rows = read_missing_rows(csv_path)
    total_rows = len(rows)
    if total_rows == 0:
        print("✅ No rows to upload.")
        return {
            "total_csv_items": 0,
            "processed": 0,
            "db_not_found": 0,
            "info_created": 0,
            "updated_rows": 0,
            "cover2d_populated": 0,
            "already_has_cover2d": 0,
            "no_remote_data": 0,
            "errors": 0,
        }

    # Prefer a single async upload for the full CSV to avoid creating many
    # background jobs when the backend supports async processing.
    single_upload = upload_missing_csv(
        csv_path,
        dry_run=dry_run,
        session=session,
        limit=None,
        async_mode=True,
        max_attempts=MAX_UPLOAD_RETRIES,
    )
    if single_upload is not None:
        if single_upload.get("async_accepted"):
            single_upload["total_csv_items"] = total_rows
            return single_upload
        if single_upload.get("async_busy"):
            return single_upload
        return single_upload
    print("⚠️  Single upload failed, falling back to adaptive sync chunks.")

    print(f"\n🧩 Adaptive upload mode enabled for {total_rows:,} rows.")
    aggregated = {
        "total_csv_items": total_rows,
        "processed": 0,
        "db_not_found": 0,
        "info_created": 0,
        "updated_rows": 0,
        "cover2d_populated": 0,
        "already_has_cover2d": 0,
        "no_remote_data": 0,
        "errors": 0,
    }

    chunk_size = max(MIN_CHUNK_SIZE, initial_chunk_size)
    index = 0
    chunk_number = 0

    while index < total_rows:
        chunk_number += 1
        current_rows = rows[index:index + chunk_size]
        actual_chunk_size = len(current_rows)
        print(f"\n➡️  Chunk {chunk_number}: rows {index + 1}-{index + actual_chunk_size} of {total_rows}")
        print(f"📏 Current chunk size: {actual_chunk_size}")

        with tempfile.NamedTemporaryFile(mode="w", suffix=".csv", prefix="cover2d_chunk_", delete=False, encoding="utf-8", newline="") as tmp:
            temp_csv = Path(tmp.name)
            writer = csv.DictWriter(tmp, fieldnames=["manifest_path", "mnemonic", "rom_path"])
            writer.writeheader()
            writer.writerows(current_rows)

        try:
            result = upload_missing_csv(
                temp_csv,
                dry_run=dry_run,
                session=session,
                limit=actual_chunk_size,
                async_mode=False,
                max_attempts=MAX_UPLOAD_RETRIES,
            )
        finally:
            try:
                temp_csv.unlink(missing_ok=True)
            except Exception:
                pass

        if result is None:
            if chunk_size > MIN_CHUNK_SIZE:
                new_size = max(MIN_CHUNK_SIZE, chunk_size // 2)
                if new_size == chunk_size:
                    print("❌ Upload keeps failing at minimum viable chunk size.")
                    return None
                print(f"⚠️  Chunk failed, reducing chunk size from {chunk_size} to {new_size} and retrying same range.")
                chunk_size = new_size
                chunk_number -= 1
                continue
            print("❌ Upload failed even at minimum chunk size.")
            return None

        for key in [
            "processed",
            "db_not_found",
            "info_created",
            "updated_rows",
            "cover2d_populated",
            "already_has_cover2d",
            "no_remote_data",
            "errors",
        ]:
            aggregated[key] += int(result.get(key, 0) or 0)

        index += actual_chunk_size

        if chunk_size < initial_chunk_size:
            chunk_size = min(initial_chunk_size, chunk_size * 2)

    print("\n✅ Adaptive upload complete.")
    print("📊 Aggregated Backfill Summary:")
    print(f"  Total CSV items:       {aggregated.get('total_csv_items', 0):,}")
    print(f"  Processed:             {aggregated.get('processed', 0):,}")
    print(f"  DB not found:          {aggregated.get('db_not_found', 0):,}")
    print(f"  INFO records created:  {aggregated.get('info_created', 0):,}")
    print(f"  Rows updated:          {aggregated.get('updated_rows', 0):,}")
    print(f"  Cover2d populated:     {aggregated.get('cover2d_populated', 0):,}")
    print(f"  Already has cover2d:   {aggregated.get('already_has_cover2d', 0):,}")
    print(f"  No remote data:        {aggregated.get('no_remote_data', 0):,}")
    print(f"  Errors:                {aggregated.get('errors', 0):,}")
    return aggregated


def regenerate_manifest() -> Tuple[bool, int]:
    """
    Run generate_catalog_manifest_cover2d.py to refresh manifest.
    Returns (success: bool, missing_count: int)
    """
    print(f"\n{'='*70}")
    print(f"🔄 REGENERATING MANIFEST...")
    print(f"{'='*70}")

    try:
        script = SCRIPT_DIR / "generate_catalog_manifest_cover2d.py"
        if not script.exists():
            print(f"❌ Script not found: {script}")
            return False, 0

        result = subprocess.run(
            [sys.executable, str(script), "--batch-size", "1", "--timeout", "300", "--retries", "2", "--output", str(MANIFEST_OUTPUT)],
            cwd=SCRIPT_DIR,
            capture_output=True,
            text=True,
            timeout=3600,
        )

        if result.returncode != 0:
            print(f"❌ Manifest generation failed")
            print(f"STDERR: {result.stderr[-500:]}")
            return False, 0

        print(f"✅ Manifest regenerated: {MANIFEST_OUTPUT}")

        # Count missing items
        missing_count = 0
        if MANIFEST_OUTPUT.exists():
            with open(MANIFEST_OUTPUT, 'r', encoding='utf-8-sig') as f:
                for line in f:
                    if line.strip().endswith('|') or '|' not in line:
                        missing_count += 1

        print(f"📊 Missing cover2d items: {missing_count:,}")
        return True, missing_count

    except subprocess.TimeoutExpired:
        print(f"❌ Manifest generation timed out after 3600s")
        return False, 0
    except Exception as e:
        print(f"❌ Exception during regeneration: {e}")
        return False, 0


def extract_missing_items(manifest_path: Path, output_csv: Path) -> int:
    """
    Extract items without cover2d from manifest and write to CSV.
    Returns the count of missing items.
    """
    print(f"\n{'='*70}")
    print(f"📋 EXTRACTING MISSING ITEMS...")
    print(f"{'='*70}")

    missing_rows = []
    if manifest_path.exists():
        with open(manifest_path, 'r', encoding='utf-8-sig') as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                # Check if line has empty cover2d (ends with | or no pipe at all)
                if line.endswith('|') or '|' not in line:
                    # Extract manifest_path
                    if '|' in line:
                        manifest = line.split('|')[0].strip()
                    else:
                        manifest = line.strip()

                    if manifest and '/' in manifest:
                        parts = manifest.split('/', 1)
                        mnemonic = parts[0]
                        rom_path = parts[1]
                        missing_rows.append({
                            'manifest_path': manifest,
                            'mnemonic': mnemonic,
                            'rom_path': rom_path
                        })

    if not missing_rows:
        print(f"✅ No missing items found!")
        return 0

    # Write CSV
    with open(output_csv, 'w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=['manifest_path', 'mnemonic', 'rom_path'])
        writer.writeheader()
        writer.writerows(missing_rows)

    print(f"✅ Extracted {len(missing_rows):,} missing items to {output_csv.name}")
    return len(missing_rows)


def main():
    """Main orchestration loop."""
    print(f"\n{'#'*70}")
    print(f"# AUTOMATIC COVER2D BACKFILL ORCHESTRATOR")
    print(f"# Backend: {BACKEND_URL}")
    print(f"# Max iterations: {MAX_ITERATIONS}")
    print(f"# Stop threshold: {MIN_MISSING_THRESHOLD:,} items")
    print(f"{'#'*70}\n")

    session = _setup_session()
    iteration = 1
    iterations_ran = 0
    total_covered = 0

    while iteration <= MAX_ITERATIONS:
        iterations_ran += 1
        print(f"\n\n{'#'*70}")
        print(f"# ITERATION {iteration}/{MAX_ITERATIONS}")
        print(f"{'#'*70}")

        # Step 1: Upload current missing CSV
        if not MISSING_CSV.exists():
            print(f"❌ Missing CSV not found: {MISSING_CSV}")
            print(f"   Run generate_catalog_manifest_cover2d.py first.")
            break

        backfill_result = upload_missing_csv_adaptive(MISSING_CSV, dry_run=False, session=session)
        if backfill_result is None:
            print(f"⚠️  Upload failed. Stopping.")
            break

        if backfill_result.get("async_accepted"):
            print("\nℹ️  Endpoint is running in async mode.")
            print("   Waiting for backend job completion before continuing...")
            job_id = backfill_result.get("job_id")
            if not job_id:
                print("❌ Async response did not include job_id. Stopping.")
                break
            status_url = backfill_result.get("status_url")

            job_state = poll_backfill_job_status(job_id, session=session, status_url=status_url)
            if not job_state:
                print("⚠️  Could not resolve async job status. Stopping.")
                break

            final_status = (job_state.get("status") or "").strip().lower()
            if final_status == "failed":
                print("⚠️  Async job failed. Stopping.")
                break
            if final_status != "completed":
                print(f"⚠️  Async job ended with unexpected status '{final_status}'. Stopping.")
                break

            result_payload = job_state.get("result")
            if not isinstance(result_payload, dict):
                print("⚠️  Async job completed without result payload. Stopping.")
                break
            backfill_result = result_payload

        if backfill_result.get("async_busy"):
            print("\n⚠️  Backend is busy with another async backfill job.")
            if backfill_result.get("error"):
                print(f"   Error: {backfill_result.get('error')}")
            max_jobs = backfill_result.get("max_concurrent_jobs")
            if max_jobs is not None:
                print(f"   Max concurrent jobs: {max_jobs}")
            print("   Wait for the running job to finish and execute again.")
            break

        # Record coverage progress
        covered = backfill_result.get('cover2d_populated', 0)
        total_covered += covered
        print(f"\n💾 This iteration populated: {covered:,} additional cover2d URLs")
        print(f"📈 Total populated so far: {total_covered:,}")

        # Wait a moment before regenerating
        print(f"\n⏳ Waiting 5 seconds before regenerating manifest...")
        time.sleep(5)

        # Step 2: Regenerate manifest
        success, missing_count = regenerate_manifest()
        if not success:
            print(f"⚠️  Manifest regeneration failed. Stopping.")
            break

        # Step 3: Check if we're done
        if missing_count <= MIN_MISSING_THRESHOLD:
            print(f"\n✅ TARGET REACHED! Missing items: {missing_count:,} (threshold: {MIN_MISSING_THRESHOLD:,})")
            print(f"🎉 BACKFILL COMPLETE!")
            break

        # Step 4: Extract new missing items
        missing_count_extracted = extract_missing_items(MANIFEST_OUTPUT, MISSING_CSV)
        if missing_count_extracted == 0:
            print(f"\n✅ All items covered!")
            break

        iteration += 1
        print(f"\n📌 Will continue with {missing_count_extracted:,} items in next iteration...")
        print(f"⏳ Waiting 10 seconds before next iteration...")
        time.sleep(10)

    print(f"\n\n{'#'*70}")
    print(f"# BACKFILL ORCHESTRATION COMPLETE")
    print(f"# Total iterations: {iterations_ran}")
    print(f"# Total cover2d URLs populated: {total_covered:,}")
    print(f"{'#'*70}\n")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print(f"\n\n⏹️  Interrupted by user")
        sys.exit(1)
    except Exception as e:
        print(f"\n\n❌ Fatal error: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)
