"""Keep curated site navigation connected to actual documentation sources."""
from pathlib import Path
import re
import unittest
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"


class DocumentationNavigationTests(unittest.TestCase):
    def test_curated_menu_links_resolve_to_documentation(self):
        text = (DOCS / "_data/navigation.yml").read_text()
        urls = re.findall(r"^\s+url:\s+(\S+)\s*$", text, re.M)
        self.assertTrue(urls, "The documentation menu needs entry points")
        self.assertEqual(len(urls), len(set(urls)), "Avoid duplicate destinations in the curated menu")
        for url in urls:
            with self.subTest(url=url):
                self.assert_documentation_target(url)

    def test_layout_links_resolve_with_a_github_pages_source_mapping(self):
        layout = (DOCS / "_layouts/default.html").read_text()
        urls = re.findall(r"'(/[^']*)'\s*\|\s*relative_url", layout)
        self.assertTrue(urls)
        for url in urls:
            with self.subTest(url=url):
                self.assert_documentation_target(url)

    def assert_documentation_target(self, url):
        parts = urlsplit(url)
        self.assertFalse(parts.scheme or parts.netloc, "The curated menu uses local routes")
        target = DOCS / parts.path.lstrip("/")
        if parts.path.endswith("/"):
            target /= "index.md"
        elif target.suffix == ".html":
            target = target.with_suffix(".md")
        self.assertTrue(target.is_file(), f"Missing site source for {url}: {target}")


if __name__ == "__main__":
    unittest.main()
