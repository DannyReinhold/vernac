"""Keep the one-file export in sync with the independently readable Markdown pages."""
from pathlib import Path
import re
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
FENCE = re.compile(r"^```[^\n]*\n.*?^```[ \t]*$", re.M | re.S)


class AiReferenceBundleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bundle = subprocess.check_output(
            [sys.executable, ROOT / "tools/bundle-ai-reference.py"], encoding="utf-8"
        )

    def test_index_routes_to_every_task_and_export_preserves_each_example(self):
        index = DOCS / "ai-language-reference.md"
        tasks = sorted((DOCS / "ai").glob("*.md"))
        links = re.findall(r"\]\((ai/[^)#]+\.md)\)", index.read_text(encoding="utf-8"))
        self.assertCountEqual(links, [path.relative_to(DOCS).as_posix() for path in tasks])
        pages = [index, *tasks, DOCS / "ai-language-details.md", DOCS / "ai-tools-reference.md"]
        source_blocks = []
        for page in pages:
            source = page.read_text(encoding="utf-8")
            title = source.splitlines()[0]
            self.assertEqual(self.bundle.splitlines().count(title), 1, title)
            source_blocks.extend(FENCE.findall(source))
        self.assertCountEqual(FENCE.findall(self.bundle), source_blocks)

    def test_links_work_outside_the_source_tree(self):
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        self.assertIn("](#reference-add-query)", self.bundle)
        self.assertIn('<a id="reference-add-query"></a>', self.bundle)
        self.assertIn(f"/blob/{revision}/docs/contracts/repository-queries.md)", self.bundle)
        self.assertIn(f"/blob/{revision}/examples/type-modules/README.md)", self.bundle)


if __name__ == "__main__":
    unittest.main()
