#!/usr/bin/env python3
"""Render the editable SaaS Mermaid sources as static SVGs; needs Mermaid CLI 11.12.0."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import shutil

ROOT = Path(__file__).resolve().parents[1]
DIAGRAMS = ROOT / "docs/saas/diagrams"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cli", default="mmdc")
    parser.add_argument("--puppeteer-config", type=Path)
    args = parser.parse_args()
    sources = sorted(DIAGRAMS.glob("*.mmd"))
    if not sources:
        raise SystemExit("No SaaS diagram sources found")
    with tempfile.TemporaryDirectory(prefix="vernac-diagrams-") as work:
        work = Path(work)
        combined = work / "sources.md"
        combined.write_text("\n\n".join("# " + path.stem + "\n\n```mermaid\n" + path.read_text() + "\n```" for path in sources))
        command = [args.cli, "-i", str(combined), "-o", str(work / "rendered.md"),
                   "-c", str(DIAGRAMS / "mermaid-config.json"), "-b", "white"]
        if args.puppeteer_config:
            command += ["-p", str(args.puppeteer_config.resolve())]
        subprocess.run(command, check=True)
        # Prepare/validate every output before updating any retained SVG.
        results = [(path.with_suffix(".svg"), work / f"rendered-{index}.svg") for index, path in enumerate(sources, 1)]
        if any(not rendered.is_file() for _, rendered in results):
            raise SystemExit("Missing rendered diagram; retained SVGs were not replaced")
        for target, rendered in results:
            shutil.copyfile(rendered, target)
    print(f"Rendered {len(sources)} static SaaS diagrams")

if __name__ == "__main__":
    main()
