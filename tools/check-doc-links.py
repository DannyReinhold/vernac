#!/usr/bin/env python3
"""Check local documentation links; no network access or third-party packages needed."""
from pathlib import Path
import html
import re
import sys
import unicodedata
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parent.parent
FILES = [ROOT / 'README.md'] + sorted((ROOT / 'docs').rglob('*.md'))
for folder in ('examples', 'templates'):
    FILES += [p for p in (ROOT / folder).rglob('*.md') if 'target' not in p.parts]


def prose(text):
    return re.sub(r'^```[^\n]*\n.*?^```\s*$', '', text, flags=re.M | re.S)


def anchors(path):
    counts = {}
    result = set()
    for heading in re.findall(r'^#{1,6}\s+(.+?)\s*#*\s*$', prose(path.read_text(encoding='utf-8')), re.M):
        heading = re.sub(r'\[([^]]+)\]\([^)]*\)', r'\1', heading)
        slug = ''.join(c for c in html.unescape(heading).lower()
                       if c in ('-', '_', ' ') or unicodedata.category(c)[0] in 'LNM')
        slug = slug.replace(' ', '-')
        index = counts.get(slug, 0)
        counts[slug] = index + 1
        result.add(slug + (f'-{index}' if index else ''))
    return result


failures = []
checked = 0
for source in FILES:
    text = prose(source.read_text(encoding='utf-8'))
    links = re.findall(r'!?\[[^\]\n]*\]\(([^\s)]+)(?:\s+"[^"]*")?\)', text)
    links += re.findall(r'(?:href|src)=["\']([^"\']+)["\']', text)
    links += re.findall(r'^\[[^\]]+\]:\s*(\S+)', text, re.M)
    for link in links:
        url = urlsplit(html.unescape(link.strip('<>')))
        repository_prefixes = ('/DannyReinhold/vernac/blob/main/', '/DannyReinhold/vernac/tree/main/')
        repository_path = next((url.path[len(prefix):] for prefix in repository_prefixes
                                if url.netloc == 'github.com' and url.path.startswith(prefix)), None)
        if (url.scheme or url.netloc) and repository_path is None:
            continue
        checked += 1
        target = ((ROOT / unquote(repository_path)).resolve() if repository_path is not None
                  else (source.parent / unquote(url.path)).resolve() if url.path else source)
        if not target.exists():
            failures.append(f'{source.relative_to(ROOT)}: missing target {link}')
        elif url.fragment and target.suffix == '.md' and unquote(url.fragment) not in anchors(target):
            failures.append(f'{source.relative_to(ROOT)}: missing heading {link}')

for failure in failures:
    print(failure)
print(f'Checked {checked} local links in {len(FILES)} documents; {len(failures)} failures.')
sys.exit(bool(failures))
