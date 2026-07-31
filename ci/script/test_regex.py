import re
from pathlib import Path

NAME_RE = re.compile(r'<string\s+name="([^"]+)"', re.I)
base = Path('app/src/main/res/values')
en = Path('app/src/main/res/values-en')

total_missing = 0
for f in sorted(base.glob('strings_*.xml')):
    base_names = set(NAME_RE.findall(f.read_text(encoding='utf-8')))
    en_file = en / f.name
    if en_file.exists():
        en_names = set(NAME_RE.findall(en_file.read_text(encoding='utf-8')))
    else:
        en_names = set()
    missing = base_names - en_names
    if missing:
        print(f'{f.name}: {len(missing)} missing')
        for k in sorted(missing)[:3]:
            print(f'  {k}')
        total_missing += len(missing)

print(f'\nTotal missing in en: {total_missing}')
