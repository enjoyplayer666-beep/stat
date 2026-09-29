# Достаёт текстуры голов со страниц minecraft-heads.com (список ссылок в urls.txt) -> out/index.tsv
import re, urllib.request, os
UA = {'User-Agent': 'Mozilla/5.0'}
os.makedirs('out', exist_ok=True)
lines = []
for url in [u.strip() for u in open('urls.txt') if u.strip()]:
    try:
        html = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30).read().decode('utf-8', 'replace')
        vals = re.findall(r'eyJ0ZXh0dXJlcy[A-Za-z0-9+/=]{20,}', html)
        hashes = re.findall(r'textures\.minecraft\.net/texture/([0-9a-f]{40,})', html)
        lines.append(f'{url}\t{vals[0] if vals else "-"}\t{hashes[0] if hashes else "-"}')
    except Exception as e:
        lines.append(f'{url}\tERROR {e}\t-')
open('out/index.tsv', 'w').write('\n'.join(lines))
print('\n'.join(lines))
