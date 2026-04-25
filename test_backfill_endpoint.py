#!/usr/bin/env python3
"""
Test the backfill endpoint to verify deployment success.
"""

import requests
from pathlib import Path


BACKEND_URL = "https://emuladores.pythonanywhere.com"
ENDPOINT = "/api/medias/fill_missing_from_csv"
TEST_CSV = Path(__file__).parent / "missing_cover2d_items.csv"


def test_endpoint():
    """Test if the backfill endpoint is available and working."""
    url = f"{BACKEND_URL}{ENDPOINT}"
    
    print(f"\n{'='*70}")
    print(f"🧪 TESTING BACKFILL ENDPOINT")
    print(f"{'='*70}\n")
    print(f"URL: {url}")
    print(f"CSV: {TEST_CSV.name}\n")

    # First: Check if endpoint exists (HEAD request)
    try:
        print(f"1️⃣  Checking if endpoint exists...")
        response = requests.head(url, timeout=10)
        if response.status_code in [200, 404]:  # 404 is ok for HEAD on POST endpoint
            print(f"   ✅ Endpoint is reachable\n")
        else:
            print(f"   ⚠️  Unexpected status: {response.status_code}\n")
    except requests.exceptions.ConnectionError:
        print(f"   ❌ Cannot connect to {BACKEND_URL}")
        print(f"   Make sure PythonAnywhere web app is running.\n")
        return False
    except Exception as e:
        print(f"   ⚠️  Error: {e}\n")

    # Second: Test with CSV file (dry_run=true first)
    if not TEST_CSV.exists():
        print(f"❌ Test CSV not found: {TEST_CSV}")
        print(f"   Run: python generate_catalog_manifest_cover2d.py first\n")
        return False

    print(f"2️⃣  Testing with dry_run=true (no database changes)...")
    try:
        with open(TEST_CSV, 'rb') as f:
            files = {'file': f}
            data = {'dry_run': 'true'}
            response = requests.post(url, files=files, data=data, timeout=60)

        if response.status_code == 200:
            result = response.json()
            print(f"   ✅ DRY RUN SUCCESSFUL!\n")
            print(f"   📊 Summary:")
            print(f"      CSV items: {result.get('total_csv_items', '?'):,}")
            print(f"      Processed: {result.get('processed', '?'):,}")
            print(f"      Would populate: {result.get('cover2d_populated', '?'):,} cover2d URLs")
            print(f"      Would update: {result.get('updated_rows', '?'):,} rows\n")
            return True
        elif response.status_code == 404:
            print(f"   ❌ ENDPOINT NOT FOUND (404)")
            print(f"      The endpoint hasn't been deployed yet.")
            print(f"      Follow deployment instructions in DEPLOYMENT_GUIDE.md\n")
            return False
        else:
            print(f"   ❌ Error status: {response.status_code}")
            try:
                print(f"      {response.json()}\n")
            except:
                print(f"      {response.text[:200]}\n")
            return False

    except requests.exceptions.Timeout:
        print(f"   ⏱️  Request timed out. Server might be slow.\n")
        return False
    except Exception as e:
        print(f"   ❌ Error: {e}\n")
        return False


if __name__ == "__main__":
    print(f"\n{'#'*70}")
    print(f"# BACKFILL ENDPOINT TEST")
    print(f"{'#'*70}")
    
    success = test_endpoint()
    
    if success:
        print(f"\n✅ ENDPOINT IS DEPLOYED AND WORKING!")
        print(f"\n🎯 Next step: Run backfill_cover2d_auto.py to populate cover2d URLs\n")
    else:
        print(f"\n❌ DEPLOYMENT ISSUE DETECTED")
        print(f"\n📋 Please follow the steps in DEPLOYMENT_GUIDE.md\n")
