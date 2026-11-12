#!/usr/bin/env python3
"""Deterministic, offline curation of the pinned identity password corpus. No data values in output."""
import argparse
import hashlib
import json
from pathlib import Path
import unicodedata

UPSTREAM_COMMIT = "dbfb830d95e4f3da4fb7d7d277ed597de0ed7a18"
UPSTREAM_PATH = "Passwords/Common-Credentials/xato-net-10-million-passwords-1000000.txt"
UPSTREAM_BYTES = 8_557_632
UPSTREAM_SHA256 = "424a3e03a17df0a2bc2b3ca749d81b04e79d59cb7aeec8876a5a3f308d0caf51"
LICENSE_SHA256 = "3dbdc93d5f8829de0941744841730a09c106d0732e5ae0e98ca1d77be7ded66c"
SOURCE = f"https://github.com/danielmiessler/SecLists/blob/{UPSTREAM_COMMIT}/{UPSTREAM_PATH}"
VERSION = "seclists-xato-1m-20261112-v1"
RESOURCE = Path("saas/identity-spring-security/src/main/resources/org/vernac/saas/identity/security/credentials/passwords")
LICENSE = Path("saas/identity-spring-security/src/main/resources/META-INF/licenses/seclists-MIT.txt")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def curate(data):
    """Mirror the accepted scalar/NFC/line-format bounds; keep no username associations."""
    rows = data.split(b"\n")
    if rows and not rows[-1]:
        rows.pop()
    stats = {"sourceLines": len(rows), "tooShort": 0, "tooLong": 0,
             "unrepresentable": 0, "duplicates": 0, "retained": 0}
    values = set()
    for row in rows:
        try:
            raw = row.removesuffix(b"\r").decode("utf-8", errors="strict")
        except UnicodeError:
            raise ValueError("Upstream corpus is not strict UTF-8") from None
        if "\r" in raw:
            stats["unrepresentable"] += 1
            continue
        if len(raw) > 1_024:
            stats["tooLong"] += 1
            continue
        value = unicodedata.normalize("NFC", raw)
        if len(value) < 15:
            stats["tooShort"] += 1
        elif len(value) > 128:
            stats["tooLong"] += 1
        elif value in values:
            stats["duplicates"] += 1
        else:
            values.add(value)
    stats["retained"] = len(values)
    # UTF-8 byte order is explicit; emit canonical LF and one final separator.
    result = b"\n".join(sorted(v.encode("utf-8") for v in values)) + (b"\n" if values else b"")
    return result, stats


def outputs(upstream):
    if len(upstream) != UPSTREAM_BYTES or digest(upstream) != UPSTREAM_SHA256:
        raise ValueError("Upstream digest differs from the reviewed pin")
    corpus, stats = curate(upstream)
    if not 1_000 <= stats["retained"] <= 100_000 or len(corpus) > 8 * 1_024 * 1_024:
        raise ValueError("Curated corpus violates loader bounds")
    manifest = (f"format=vernac-password-corpus-1\nversion={VERSION}\nsource={SOURCE}\n"
                f"license=MIT\nsha256={digest(corpus)}\nentries={stats['retained']}\n"
                f"upstream.commit={UPSTREAM_COMMIT}\nupstream.sha256={UPSTREAM_SHA256}\n"
                "curation=NFC;15-128-code-points;unique;UTF-8-byte-order;LF\n")
    review = dict(stats, upstreamCommit=UPSTREAM_COMMIT, upstreamSha256=UPSTREAM_SHA256,
                  curatedSha256=digest(corpus), curatedBytes=len(corpus), policyMinimum=15,
                  policyMaximum=128, license="MIT", licenseSha256=LICENSE_SHA256)
    return {RESOURCE / "blocked-v1.txt": corpus,
            RESOURCE / "manifest.properties": manifest.encode("utf-8"),
            RESOURCE / "curation.json": (json.dumps(review, indent=2, sort_keys=True) + "\n").encode("utf-8")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True, help="Already downloaded pinned source; never fetched here")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--check", action="store_true", help="Compare without modifying tracked files")
    args = parser.parse_args()
    if digest((args.root / LICENSE).read_bytes()) != LICENSE_SHA256:
        raise ValueError("Bundled upstream license differs from the reviewed pin")
    if args.upstream.stat().st_size != UPSTREAM_BYTES:
        raise ValueError("Upstream size differs from the reviewed pin")
    generated = outputs(args.upstream.read_bytes())
    for relative, expected in generated.items():
        path = args.root / relative
        if args.check:
            if not path.is_file() or path.read_bytes() != expected:
                raise ValueError(f"Corpus output differs: {relative}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(expected)
    print("Pinned corpus reproduced; resource digests, provenance and license verified.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError):
        raise SystemExit("Corpus curation failed; verify the pinned inputs and tracked outputs.") from None
