#!/usr/bin/env python3
#  图片转pdf工具，用于整理 (ai生成)
import os, sys, img2pdf, io
from PIL import Image, UnidentifiedImageError
from pathlib import Path

root = Path(sys.argv[1])
exts = {'.jpg', '.jpeg', '.png', '.webp', '.bmp', '.tiff', '.tif'}

def try_load_image(img_path):
    """尝试完整解码图片，返回 RGB bytes 或 None"""
    try:
        with Image.open(img_path) as im:
            im.load()  # 强制解码，截断/损坏图在这里报错
            
            # 尺寸检查
            if im.width < 10 or im.height < 10:
                print(f"  ⚠ 跳过过小图片: {img_path.name} ({im.width}x{im.height})")
                return None
            
            # 统一转 RGB（处理 RGBA、P 调色板、L 灰度等）
            if im.mode not in ('RGB', 'L'):
                im = im.convert('RGB')
            
            buf = io.BytesIO()
            im.save(buf, format='JPEG', quality=95)
            return buf.getvalue()
    except (UnidentifiedImageError, OSError, Exception) as e:
        print(f"  ⚠ 跳过损坏文件: {img_path.name} ({type(e).__name__}: {e})")
        return None

for folder in sorted(p for p in root.iterdir() if p.is_dir()):
    images = sorted(f for f in folder.iterdir() if f.suffix.lower() in exts)
    if not images:
        continue

    print(f"处理: {folder.name} ({len(images)} 张图)")
    
    valid_bufs = []
    for img_path in images:
        data = try_load_image(img_path)
        if data:
            valid_bufs.append(data)

    if not valid_bufs:
        print(f"  跳过 {folder.name}（无有效图片）\n")
        continue

    output = root / f"{folder.name}.pdf"
    try:
        with open(output, 'wb') as f:
            f.write(img2pdf.convert(valid_bufs))
        print(f"  ✓ 输出 {output.name}，有效 {len(valid_bufs)}/{len(images)} 张\n")
    except Exception as e:
        print(f"  ✗ PDF生成失败 {folder.name}: {e}\n")

print("全部完成")

