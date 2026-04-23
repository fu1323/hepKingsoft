#!/bin/bash
#(ai生成) 用来填充爬取word时下载的透明背景的图片为白色背景
find folder -name "*.png" -type f -exec sh -c 'mkdir -p "output/$(dirname "${1#folder/}")"; magick "$1" -background white -flatten -alpha off "output/${1#folder/}"' _ {} \;