#!/usr/bin/env bash
# Draws the Play icon (512 x 512) and the feature graphic (1024 x 500) from the brand files in brand/.
# Needs rsvg-convert and ImageMagick (magick): brew install librsvg imagemagick
#   fastlane/graphics.sh <images directory>
set -euo pipefail

out="${1:?usage: graphics.sh <images directory>}"
repo="$(cd "$(dirname "$0")/../../.." && pwd)"
brand="$repo/brand"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$out"

ink="#14130F"
cream="#F2F0EA"
signal="#FF5A2E"

# the app icon: the small mark with cream brackets and the signal playhead, on an ink tile
sed -e "s/stroke=\"#14130F\"/stroke=\"$cream\"/" -e "s/#F23F12/$signal/g" "$brand/kairos-mark-small.svg" > "$work/mark.svg"

rsvg-convert -w 300 -h 300 "$work/mark.svg" -o "$work/mark-icon.png"
magick -size 512x512 "xc:$ink" "$work/mark-icon.png" -gravity center -composite -alpha off "$out/icon.png"

# the feature graphic: the mark, the wordmark and a line, on ink
rsvg-convert -w 230 -h 230 "$work/mark.svg" -o "$work/mark-banner.png"
magick -size 1024x500 "xc:$ink" \
  "$work/mark-banner.png" -gravity west -geometry +130+0 -composite \
  -font "$brand/fonts/SpaceGrotesk-Variable.ttf" -fill "$cream" -pointsize 124 -kerning -4 \
  -gravity west -annotate +420-30 "kairos" \
  -font "$brand/fonts/SpaceGrotesk-Variable.ttf" -fill "#A8A398" -pointsize 34 -kerning 0 \
  -gravity west -annotate +424+72 "A time-based camera trigger" \
  -alpha off "$out/featureGraphic.png"

echo "wrote $out/icon.png and $out/featureGraphic.png"
