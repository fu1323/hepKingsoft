#!/usr/bin/env python3
# 图片转 PDF 工具，用于整理
# 功能：
# 1. 遍历指定目录下的所有子文件夹
# 2. 按文件名排序图片
# 3. 自动检测并跳过损坏图片
# 4. 透明背景自动补成白色
# 5. 统一转换为 JPEG 后生成 PDF

import os
import sys
import io
import img2pdf

from PIL import Image, UnidentifiedImageError
from pathlib import Path


# =========================
# 参数检查
# =========================

if len(sys.argv) < 2:
    print(f"用法: {sys.argv[0]} <图片根目录>")
    sys.exit(1)

root = Path(sys.argv[1])

if not root.exists():
    print(f"错误：目录不存在: {root}")
    sys.exit(1)

if not root.is_dir():
    print(f"错误：不是目录: {root}")
    sys.exit(1)


# 支持的图片格式
exts = {
    '.jpg',
    '.jpeg',
    '.png',
    '.webp',
    '.bmp',
    '.tiff',
    '.tif'
}


# =========================
# 图片加载
# =========================

def try_load_image(img_path):
    """
    尝试完整解码图片。

    返回：
        JPEG bytes：图片有效
        None：图片损坏或不符合要求
    """

    try:
        with Image.open(img_path) as im:

            # 强制完整解码
            # 截断、损坏的图片通常会在这里报错
            im.load()

            # =========================
            # 尺寸检查
            # =========================

            if im.width < 10 or im.height < 10:
                print(
                    f"  ⚠ 跳过过小图片: "
                    f"{img_path.name} ({im.width}x{im.height})"
                )
                return None

            # =========================
            # 处理透明背景
            # =========================

            if im.mode in ('RGBA', 'LA') or 'transparency' in im.info:

                # 转成 RGBA，确保有 Alpha 通道
                rgba = im.convert('RGBA')

                # 创建纯白背景
                bg = Image.new(
                    'RGB',
                    rgba.size,
                    'white'
                )

                # 将原图按照 Alpha 通道贴到白色背景
                bg.paste(
                    rgba,
                    mask=rgba.getchannel('A')
                )

                im = bg

            # =========================
            # 其他颜色模式转换
            # =========================

            elif im.mode not in ('RGB', 'L'):
                im = im.convert('RGB')

            # =========================
            # 转成 JPEG
            # =========================

            buf = io.BytesIO()

            im.save(
                buf,
                format='JPEG',
                quality=95
            )

            return buf.getvalue()

    except (
        UnidentifiedImageError,
        OSError,
        Exception
    ) as e:

        print(
            f"  ⚠ 跳过损坏文件: "
            f"{img_path.name} "
            f"({type(e).__name__}: {e})"
        )

        return None


# =========================
# 遍历子目录
# =========================

for folder in sorted(
    p for p in root.iterdir()
    if p.is_dir()
):

    # 找出当前目录下的图片
    images = sorted(
        f for f in folder.iterdir()
        if f.is_file()
        and f.suffix.lower() in exts
    )

    if not images:
        continue

    print(
        f"处理: {folder.name} "
        f"({len(images)} 张图)"
    )

    # 保存有效图片的 JPEG 数据
    valid_bufs = []

    for img_path in images:

        data = try_load_image(img_path)

        if data:
            valid_bufs.append(data)

    # =========================
    # 没有有效图片
    # =========================

    if not valid_bufs:

        print(
            f"  跳过 {folder.name}"
            f"（无有效图片）\n"
        )

        continue

    # =========================
    # 输出 PDF
    # =========================

    output = root / f"{folder.name}.pdf"

    try:

        with open(output, 'wb') as f:

            f.write(
                img2pdf.convert(valid_bufs)
            )

        print(
            f"  ✓ 输出 {output.name}，"
            f"有效 {len(valid_bufs)}/{len(images)} 张\n"
        )

    except Exception as e:

        print(
            f"  ✗ PDF生成失败 "
            f"{folder.name}: {e}\n"
        )


print("全部完成")