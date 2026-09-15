# 合成テスト用素材

自作の色面と正弦波。外部の映像・音楽は使用していない。MITライセンスのテストfixture。
FFmpegは開発機でこの素材を生成するためだけに使用し、アプリには同梱しない。

- main-blue.mp4: 青、320×180、30fps、4秒、440Hz/48kHz/AAC。
- overlay-red-green.mp4: 前半1秒は赤、後半1秒は緑、160×160、24fps、880Hz/44.1kHz/AAC。

生成コマンド（作業ディレクトリはこのフォルダ）:

```sh
ffmpeg -f lavfi -i 'color=c=blue:s=320x180:r=30:d=4' -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=4' -c:v libx264 -preset ultrafast -crf 18 -pix_fmt yuv420p -c:a aac -shortest main-blue.mp4
ffmpeg -f lavfi -i 'color=c=red:s=160x160:r=24:d=1' -f lavfi -i 'color=c=lime:s=160x160:r=24:d=1' -f lavfi -i 'sine=frequency=880:sample_rate=44100:duration=2' -filter_complex '[0:v][1:v]concat=n=2:v=1:a=0[v]' -map '[v]' -map 2:a -c:v libx264 -preset ultrafast -crf 18 -pix_fmt yuv420p -c:a aac -shortest overlay-red-green.mp4
```
