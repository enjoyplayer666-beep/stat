# Разовый инструмент: ищет в базах голов (minecraft-heads.com + CustomHeads) головы, похожие по цвету
# на иконки со скрина (ref_*.png), и рисует лучших кандидатов в таблицы out/<ref>.png + out/index.tsv
import base64, io, json, os, re, urllib.request
from concurrent.futures import ThreadPoolExecutor
from PIL import Image, ImageDraw

UA = {'User-Agent': 'Mozilla/5.0'}

def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30).read()

heads = {}
cats = ['alphabet', 'animals', 'blocks', 'decoration', 'food-drinks', 'humans', 'humanoid', 'miscellaneous', 'monsters', 'plants']
for cat in cats:
    try:
        for h in json.loads(get(f'https://minecraft-heads.com/scripts/api.php?cat={cat}&tags=true')):
            heads.setdefault(h.get('value', ''), h.get('name', ''))
        print(cat, len(heads))
    except Exception as e:
        print('mh fail', cat, e)
for f in os.listdir('ch'):
    for h in json.load(open(os.path.join('ch', f), encoding='utf-8')).get('heads', []):
        heads.setdefault(h.get('texture', ''), re.sub('§.', '', h.get('name', '')))

def url_of(value):
    try:
        return json.loads(base64.b64decode(value + '==='))['textures']['SKIN']['url']
    except Exception:
        return None

items = []
seen = set()
for value, name in heads.items():
    u = url_of(value)
    if u and u not in seen:
        seen.add(u); items.append((name, u))
print('unique heads', len(items))

def q(p):
    return (p[0] // 32, p[1] // 32, p[2] // 32)

def hist(pixels):
    h = {}
    for p in pixels:
        k = q(p); h[k] = h.get(k, 0) + 1
    n = sum(h.values()) or 1
    return {k: v / n for k, v in h.items()}

def shade(p, f):
    return (int(p[0] * f), int(p[1] * f), int(p[2] * f))

def face(skin, x, y):
    base = skin.crop((x, y, x + 8, y + 8))
    over = skin.crop((x + 32, y, x + 40, y + 8))
    base.alpha_composite(over)
    return base

def feat(skin):
    px = []
    for (x, y, f, w) in [(8, 0, 1.0, 1), (8, 8, 0.8, 2), (0, 8, 0.6, 1)]:
        for p in face(skin, x, y).getdata():
            if p[3] > 0:
                px += [shade(p, f)] * w
    return hist(px)

refs = {}
for f in os.listdir('.'):
    if f.startswith('ref_') and f.endswith('.png'):
        im = Image.open(f).convert('RGBA')
        px = [p[:3] for p in im.getdata() if p[3] > 0 and not (max(p[:3]) - min(p[:3]) < 12 and 100 < p[0] < 175)]
        refs[f[4:-4]] = hist(px)
print('refs', list(refs))

def load(it):
    name, u = it
    try:
        skin = Image.open(io.BytesIO(get(u))).convert('RGBA')
        if skin.size[0] < 64:
            return None
        return (name, u, skin)
    except Exception:
        return None

loaded = []
with ThreadPoolExecutor(48) as ex:
    for r in ex.map(load, items):
        if r:
            loaded.append(r)
print('loaded', len(loaded))

def sim(a, b):
    return sum(min(v, b.get(k, 0)) for k, v in a.items())

os.makedirs('out', exist_ok=True)
lines = []
feats = [(n, u, s, feat(s)) for n, u, s in loaded]
for rname, rh in refs.items():
    best = sorted(feats, key=lambda t: -sim(rh, t[3]))[:60]
    cell = 130; cols = 10
    W = Image.new('RGB', (cols * cell, (len(best) // cols + 1) * cell), (70, 70, 80)); d = ImageDraw.Draw(W)
    ref = Image.open(f'ref_{rname}.png').convert('RGBA').resize((100, 108), Image.NEAREST)
    for k, (n, u, s, _) in enumerate(best):
        x = (k % cols) * cell; y = (k // cols) * cell
        top = face(s, 8, 0).resize((40, 40), Image.NEAREST)
        fr = face(s, 8, 8).resize((64, 64), Image.NEAREST)
        W.paste(top, (x + 4, y + 4), top); W.paste(fr, (x + 48, y + 4), fr)
        d.text((x + 4, y + 72), f'{rname[:1]}{k}', fill=(255, 255, 0))
        d.text((x + 4, y + 86), n[:20], fill=(255, 255, 255))
        d.text((x + 4, y + 100), n[20:40], fill=(255, 255, 255))
        lines.append(f'{rname}\t{rname[:1]}{k}\t{n}\t{u.rsplit("/", 1)[-1]}')
    W.save(f'out/{rname}.png')
open('out/index.tsv', 'w', encoding='utf-8').write('\n'.join(lines))
