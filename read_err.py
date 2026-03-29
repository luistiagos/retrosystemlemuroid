import sys

try:
    with open('build_out.txt', 'r', encoding='utf-16') as f:
        content = f.read()
        print(content[-2000:])
except Exception as e:
    try:
        with open('build_out.txt', 'r', encoding='utf-8') as f:
            content = f.read()
            print(content[-2000:])
    except Exception as e2:
        print(e2)
