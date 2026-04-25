#!/usr/bin/env python3
import requests

url = 'https://emuladores.pythonanywhere.com/api/medias/fill_missing_from_csv'

print('Testing endpoint with SMALL CSV (dry_run=true)...\n')

try:
    with open('test_small.csv', 'rb') as f:
        files = {'file': f}
        data = {'dry_run': 'true'}
        print('Uploading...')
        response = requests.post(url, files=files, data=data, timeout=60)
        print(f'Status: {response.status_code}')
        if response.status_code == 200:
            result = response.json()
            print('✅ SUCCESS!')
            print(f'  CSV items: {result.get("total_csv_items")}')
            print(f'  Would populate: {result.get("cover2d_populated")} URLs')
        else:
            print(f'❌ Error: {response.text[:300]}')
except Exception as e:
    print(f'❌ Error: {e}')
