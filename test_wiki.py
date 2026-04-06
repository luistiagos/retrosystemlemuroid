import urllib.request
import os

url = "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e5/Super_Nintendo_Entertainment_System_Logo.svg/512px-Super_Nintendo_Entertainment_System_Logo.svg.png"
req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'})
try:
    with urllib.request.urlopen(req) as response, open('test_logo.png', 'wb') as out_file:
        data = response.read()
        out_file.write(data)
    print("Success, downloaded " + str(os.path.getsize('test_logo.png')) + " bytes.")
except Exception as e:
    print(f"Error: {e}")
