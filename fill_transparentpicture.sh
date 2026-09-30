#!/bin/sh
#填充png透明背景脚本, 放到 output/下面, bash命令运行即可,输出 output/output/ 保持原有目录结构
cd "$(dirname "$0")" || exit 1

find . -type f -name "*.png" -not -path "./output/*" -exec sh -c '
    src="$1"
    rel="${src#./}"
    dst="output/$rel"

    mkdir -p "$(dirname "$dst")"
    magick "$src" -background white -flatten -alpha off "$dst"
' _ {} \;