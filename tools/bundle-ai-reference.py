#!/usr/bin/env python3
"""Write the single-file Markdown reference for the current Vernac checkout to stdout."""

from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs"
REPOSITORY = "https://github.com/DannyReinhold/vernac"


def render_page(path, revision, anchors):
    text = path.read_text(encoding="utf-8")
    # Preserve code verbatim. Resolve supplementary links even when the bundle is saved elsewhere.
    pieces = re.split(r"(^```[^\n]*\n.*?^```[ \t]*$)", text, flags=re.M | re.S)

    def resolve(match):
        target = urlsplit(match[1])
        if target.scheme or target.netloc:
            for kind in ("blob", "tree"):
                prefix = f"{REPOSITORY}/{kind}/main/"
                if match[1].startswith(prefix):
                    return f"]({REPOSITORY}/{kind}/{revision}/{match[1][len(prefix):]})"
            return match[0]
        if not target.path:
            return match[0]
        source = (path.parent / target.path).resolve()
        if source in anchors and not target.fragment:
            return f"](#{anchors[source]})"
        fragment = "#" + target.fragment if target.fragment else ""
        return f"]({REPOSITORY}/blob/{revision}/{source.relative_to(ROOT).as_posix()}{fragment})"

    for i in range(0, len(pieces), 2):
        pieces[i] = re.sub(r"\]\(([^\s)]+)\)", resolve, pieces[i])
    return f'<a id="{anchors[path]}"></a>\n\n' + "".join(pieces).rstrip()


def main():
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    index = DOCS / "ai-language-reference.md"
    tasks = re.findall(r"\]\((ai/[^)#]+\.md)\)", index.read_text(encoding="utf-8"))
    pages = [index, *(DOCS / name for name in dict.fromkeys(tasks)),
             DOCS / "ai-language-details.md", DOCS / "ai-tools-reference.md"]
    anchors = {path: "reference-" + path.stem for path in pages}
    output = "\n\n".join(render_page(path, revision, anchors) for path in pages) + "\n"
    sys.stdout.buffer.write(output.encode("utf-8"))


if __name__ == "__main__":
    main()
