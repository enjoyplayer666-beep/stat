# Разовый инструмент: ищет головы по словам в базах minecraft-heads.com и CustomHeads,
# рисует лица (лицо + верх) в таблицы, чтобы выбрать нужные по картинке.
import base64, io, json, os, re, sys, urllib.request
from PIL import Image, ImageDraw

KEYS = [k.strip().lower() for k in open('keywords.txt', encoding='utf-8') if k.strip()]
UA = {'User-Agent': 'Mozilla/5.0'}

def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30).read()

heads = []
for cat in ['alphabet', 'animals', 'blocks', 'decoration', 'food-drinks', 'humans', 'humanoid', 'miscellaneous', 'monsters', 'plants']:
    try:
        data = json.loads(get(f'https://minecraft-heads.com/scripts/api.php?cat={cat}&tags=true'))
        for h in data:
            heads.append((h.get('name', ''), h.get('value', ''), cat + ' ' + (h.get('tags') or '')))
        print(cat, len(data))
    except Exception as e:
        print('mh fail', cat, e)
for f in os.listdir('ch'):
    d = json.load(open(os.path.join('ch', f), encoding='utf-8'))
    for h in d.get('heads', []):
        heads.append((re.sub('§.', '', h.get('name', '')), h.get('texture', ''), 'customheads'))
print('total heads', len(heads))

def url_of(value):
    try:
        j = json.loads(base64.b64decode(value + '==='))
        return j['textures']['SKIN']['url']
    except Exception:
        return None

found = []
for name, value, tags in heads:
    low = (name + ' ' + tags).lower()
    if any(k in low for k in KEYS):
        u = url_of(value)
        if u:
            found.append((name, u))
seen = set(); uniq = []
for n, u in found:
    if u not in seen:
        seen.add(u); uniq.append((n, u))
uniq = uniq[:600]
print('matched', len(uniq))

os.makedirs('out', exist_ok=True)
cell = 120
cols = 10
rows_per = 8
out = []
for i, (name, u) in enumerate(uniq):
    try:
        skin = Image.open(io.BytesIO(get(u))).convert('RGBA')
    except Exception:
        continue
    face = skin.crop((8, 8, 16, 16)); face.alpha_composite(skin.crop((40, 8, 48, 16)))
    top = skin.crop((8, 0, 16, 8)); top.alpha_composite(skin.crop((40, 0, 48, 8)))
    out.append((name, u.rsplit('/', 1)[-1], face, top))
lines = []
for p in range(0, len(out), cols * rows_per):
    page = out[p:p + cols * rows_per]
    W = Image.new('RGB', (cols * cell, rows_per * cell), (70, 70, 80)); d = ImageDraw.Draw(W)
    for k, (name, h, face, top) in enumerate(page):
        x = (k % cols) * cell; y = (k // cols) * cell
        W.paste(top.resize((40, 40), Image.NEAREST), (x + 8, y + 4), top.resize((40, 40), Image.NEAREST))
        W.paste(face.resize((56, 56), Image.NEAREST), (x + 56, y + 4), face.resize((56, 56), Image.NEAREST))
        idx = p + k
        d.text((x + 4, y + 64), f'#{idx}', fill=(255, 255, 0))
        d.text((x + 4, y + 78), name[:18], fill=(255, 255, 255))
        d.text((x + 4, y + 92), name[18:36], fill=(255, 255, 255))
        lines.append(f'{idx}\t{name}\t{h}')
    W.save(f'out/sheet_{p // (cols * rows_per):02d}.png')
open('out/index.tsv', 'w', encoding='utf-8').write('\n'.join(lines))
print('rendered', len(out))
