#!/usr/bin/env python3
"""Convert JackEasons/emoticon packs into GO-IM sticker layout + zip.

Output folder (upload this entire directory to OSS):
  stickers-dist/
    catalog.json
    {pack_id}/pack.json + images
    {pack_id}.zip

OSS base (user-hosted):
  https://oss.chaisz.com/im-sticker-pack
"""

from __future__ import annotations

import json
import re
import shutil
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "stickers-dist"
CDN_BASE = "https://oss.chaisz.com/im-sticker-pack"

# Small/medium packs from https://github.com/JackEasons/emoticon
# (source_name, pack_id, display_name)
PACKS = [
    ("兔子", "tuzi", "兔子"),
    ("Doge", "doge", "Doge"),
    ("冷兔大表情", "lengtu", "冷兔"),
    ("ARU", "aru", "ARU"),
    ("三连", "sanlian", "三连"),
    ("Python 中文", "python_cn", "Python"),
]

SOURCE_ZIP = "https://raw.githubusercontent.com/JackEasons/emoticon/master/packages/{name}.zip"
SOURCE_ZIP_ALT = "https://emoticon.kallydev.com/packages/{name}.zip"

IMG_EXT = {".png", ".jpg", ".jpeg", ".gif", ".webp"}


def slug_id(name: str, index: int) -> str:
    stem = Path(name).stem
    safe = re.sub(r"[^a-zA-Z0-9_-]+", "_", stem).strip("_").lower()
    if not safe:
        safe = f"s{index:03d}"
    return safe[:48]


def download(url: str, dest: Path) -> None:
    print(f"  GET {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "GO-IM-sticker-builder/1.0"})
    with urllib.request.urlopen(req, timeout=180) as resp, open(dest, "wb") as f:
        shutil.copyfileobj(resp, f)


def try_download_zip(source_name: str, dest: Path) -> None:
    encoded = urllib.parse.quote(source_name)
    errors = []
    for tmpl in (SOURCE_ZIP, SOURCE_ZIP_ALT):
        url = tmpl.format(name=encoded)
        try:
            download(url, dest)
            return
        except Exception as e:  # noqa: BLE001
            errors.append(f"{url}: {e}")
    raise RuntimeError("download failed:\n  " + "\n  ".join(errors))


def convert_pack(source_name: str, pack_id: str, display_name: str, work: Path) -> dict:
    zip_path = work / f"{pack_id}-src.zip"
    extract_dir = work / f"{pack_id}-src"
    extract_dir.mkdir(parents=True, exist_ok=True)
    try_download_zip(source_name, zip_path)

    with zipfile.ZipFile(zip_path, "r") as zf:
        zf.extractall(extract_dir)

    images: list[Path] = []
    for p in sorted(extract_dir.rglob("*")):
        if p.is_file() and p.suffix.lower() in IMG_EXT and not p.name.startswith("."):
            images.append(p)
    if not images:
        raise RuntimeError(f"no images in {source_name}")

    out_dir = OUT / pack_id
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)

    stickers = []
    used_ids: set[str] = set()
    for i, src in enumerate(images, start=1):
        sid = slug_id(src.name, i)
        if sid in used_ids:
            sid = f"{sid}_{i:03d}"
        used_ids.add(sid)
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


def main() -> int:
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)

    catalog_packs = []
    with tempfile.TemporaryDirectory(prefix="goim-stickers-") as tmp:
        work = Path(tmp)
        for source_name, pack_id, display_name in PACKS:
            print(f"== {display_name} ({source_name}) ==")
            try:
                catalog_packs.append(convert_pack(source_name, pack_id, display_name, work))
            except Exception as e:  # noqa: BLE001
                print(f"  SKIP: {e}", file=sys.stderr)

    if not catalog_packs:
        print("no packs built", file=sys.stderr)
        return 1

    catalog = {
        "version": 1,
        "cdn_base": CDN_BASE.rstrip("/"),
        "packs": catalog_packs,
    }
    (OUT / "catalog.json").write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    # Preset index for the iOS client (small JSON only — assets live on OSS).
    ios_res = ROOT / "ios" / "App" / "Resources" / "Stickers"
    ios_res.mkdir(parents=True, exist_ok=True)
    shutil.copy2(OUT / "catalog.json", ios_res / "catalog.json")

    docs = ROOT / "docs" / "stickers"
    docs.mkdir(parents=True, exist_ok=True)
    shutil.copy2(OUT / "catalog.json", docs / "catalog.json")

    print(f"\nDone: {len(catalog_packs)} packs in {OUT}")
    print(f"Upload the contents of {OUT}/ to OSS prefix im-sticker-pack")
    print(f"  e.g. catalog.json -> {CDN_BASE}/catalog.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
