#!/usr/bin/env python3
"""Download Capoo (咖波) packs and merge into stickers-dist for OSS upload.

Source: CST-Cat/capoo-gallery (compressed GIFs mirrored from Capoo Telegram packs)
  https://github.com/CST-Cat/capoo-gallery

Does NOT wipe existing packs (tuzi/doge/lengtu). Re-run safe.
"""

from __future__ import annotations

import json
import re
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "stickers-dist"
TMP = ROOT / ".tmp" / "capoo-src"
CDN_BASE = "https://oss.chaisz.com/im-sticker-pack"
GALLERY_RAW = "https://raw.githubusercontent.com/CST-Cat/capoo-gallery/main/gifs"
GALLERY_API = "https://api.github.com/repos/CST-Cat/capoo-gallery/contents/gifs"

# (gallery folder, pack_id, display_name)
PACKS = [
    ("043-HappyCapoo-HappyCapoo", "capoo", "咖波"),
    ("039-HyperCapoo-HyperCapoo", "capoo_hyper", "咖波 Hyper"),
    ("013-CAPOO-SP-capoo_sp_animated", "capoo_sp", "咖波 SP"),
]

IMG_EXT = {".png", ".jpg", ".jpeg", ".gif", ".webp"}


def slug_id(name: str, index: int) -> str:
    stem = Path(name).stem
    safe = re.sub(r"[^a-zA-Z0-9_-]+", "_", stem).strip("_").lower()
    if not safe:
        safe = f"s{index:03d}"
    return safe[:48]


def http_get(url: str, dest: Path | None = None, retries: int = 3) -> bytes:
    last: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "GO-IM-sticker-builder/1.0"})
            with urllib.request.urlopen(req, timeout=120) as resp:
                data = resp.read()
            if dest is not None:
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(data)
            return data
        except Exception as e:  # noqa: BLE001
            last = e
            print(f"  retry {attempt + 1}/{retries}: {url} ({e})")
    raise RuntimeError(f"GET failed: {url}: {last}")


def download_pack(src_name: str) -> Path:
    dest = TMP / src_name
    dest.mkdir(parents=True, exist_ok=True)
    listing = json.loads(http_get(f"{GALLERY_API}/{src_name}"))
    files = [it for it in listing if it.get("type") == "file"]
    print(f"  {len(files)} files from gallery")
    ok = 0
    for it in files:
        path = dest / it["name"]
        if path.exists() and path.stat().st_size > 0:
            ok += 1
            continue
        url = it.get("download_url") or f"{GALLERY_RAW}/{src_name}/{it['name']}"
        try:
            http_get(url, path)
            ok += 1
        except Exception as e:  # noqa: BLE001
            print(f"  FAIL {it['name']}: {e}")
    print(f"  ready {ok}/{len(files)}")
    return dest


def convert_pack(src_dir: Path, pack_id: str, display_name: str) -> dict:
    images = sorted(
        p
        for p in src_dir.iterdir()
        if p.is_file() and p.suffix.lower() in IMG_EXT and p.stat().st_size > 0
    )
    if not images:
        raise RuntimeError(f"no images in {src_dir}")

    out_dir = OUT / pack_id
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)

    stickers = []
    used: set[str] = set()
    for i, src in enumerate(images, start=1):
        sid = slug_id(src.name, i)
        if sid in used:
            sid = f"{sid}_{i:03d}"
        used.add(sid)
        ext = src.suffix.lower().lstrip(".")
        if ext == "jpeg":
            ext = "jpg"
        file_name = f"{sid}.{ext}"
        shutil.copy2(src, out_dir / file_name)
        stickers.append({"id": sid, "file": file_name, "w": 240, "h": 240})

    base_url = f"{CDN_BASE.rstrip('/')}/{pack_id}/"
    pack = {
        "pack_id": pack_id,
        "name": display_name,
        "version": 1,
        "cover": stickers[0]["file"],
        "base_url": base_url,
        "stickers": stickers,
    }
    (out_dir / "pack.json").write_text(
        json.dumps(pack, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    zip_out = OUT / f"{pack_id}.zip"
    if zip_out.exists():
        zip_out.unlink()
    with zipfile.ZipFile(zip_out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for f in sorted(out_dir.iterdir()):
            zf.write(f, arcname=f.name)

    print(f"  -> {pack_id}: {len(stickers)} stickers, zip={zip_out.stat().st_size // 1024}KB")
    return {
        "pack_id": pack_id,
        "name": display_name,
        "version": 1,
        "base_url": base_url,
        "zip_url": f"{CDN_BASE.rstrip('/')}/{pack_id}.zip",
        "cover": stickers[0]["file"],
        "count": len(stickers),
    }


def merge_catalog(new_entries: list[dict]) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    catalog_path = OUT / "catalog.json"
    if catalog_path.exists():
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    else:
        catalog = {"version": 1, "cdn_base": CDN_BASE.rstrip("/"), "packs": []}

    replace_ids = {e["pack_id"] for e in new_entries}
    keep = [p for p in catalog.get("packs", []) if p.get("pack_id") not in replace_ids]
    catalog["version"] = 1
    catalog["cdn_base"] = CDN_BASE.rstrip("/")
    catalog["packs"] = keep + new_entries
    catalog_path.write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    ios_res = ROOT / "ios" / "App" / "Resources" / "Stickers"
    ios_res.mkdir(parents=True, exist_ok=True)
    shutil.copy2(catalog_path, ios_res / "catalog.json")

    docs = ROOT / "docs" / "stickers"
    docs.mkdir(parents=True, exist_ok=True)
    shutil.copy2(catalog_path, docs / "catalog.json")


def main() -> int:
    TMP.mkdir(parents=True, exist_ok=True)
    entries: list[dict] = []
    for src_name, pack_id, display_name in PACKS:
        print(f"== {display_name} ({src_name}) ==")
        try:
            src_dir = download_pack(src_name)
            entries.append(convert_pack(src_dir, pack_id, display_name))
        except Exception as e:  # noqa: BLE001
            print(f"  SKIP: {e}", file=sys.stderr)

    if not entries:
        print("no Capoo packs built", file=sys.stderr)
        return 1

    merge_catalog(entries)
    print(f"\nDone: {len(entries)} Capoo packs merged into {OUT}")
    print(f"Upload stickers-dist/ to OSS prefix im-sticker-pack")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
