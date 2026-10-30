#!/usr/bin/env bash
# Turn a raw screen recording into the README demo (MP4 + GIF).
# Speeds it up, and can optionally blur everything after a timestamp (handy
# for hiding a remote person's face when you do not have their permission).
# Usage: bash scripts/make-demo.sh recording.mp4 [speed=1.75] [blur_from_seconds]
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
need ffmpeg

in="${1:?usage: make-demo.sh <recording.mp4> [speed] [blur_from_seconds]}"
speed="${2:-1.75}"; blur_from="${3:-}"
out="$ROOT/docs/media/demo"; mkdir -p "$out"

vf="setpts=PTS/${speed},fps=30,scale=540:-2"
if [[ -n "$blur_from" ]]; then
  vf+=",gblur=sigma=26:enable='gte(t,${blur_from})'"
fi

info "encoding MP4 (${speed}x)"
ffmpeg -v error -y -i "$in" -an -vf "$vf" -c:v libx264 -preset slow -crf 27 \
  -pix_fmt yuv420p -movflags +faststart "$out/bluechat-demo.mp4"

info "encoding GIF preview"
ffmpeg -v error -y -i "$out/bluechat-demo.mp4" \
  -vf "fps=8,scale=220:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=64[p];[b][p]paletteuse=dither=bayer:bayer_scale=5" \
  "$out/bluechat-demo.gif"
ok "wrote $out/bluechat-demo.{mp4,gif}"
