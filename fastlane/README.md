# F-Droid / fastlane metadata

このディレクトリは [F-Droid metadata 仕様](https://f-droid.org/en/docs/All_About_Descriptions_Graphics_and_Screenshots/) に従う。fastlane の [supply (Play Console アップロード)](https://docs.fastlane.tools/actions/supply/) とも互換。

```
fastlane/metadata/android/
├── ja-JP/
│   ├── title.txt
│   ├── short_description.txt   # 80 字以内
│   ├── full_description.txt    # 4000 字以内
│   ├── changelogs/<versionCode>.txt
│   └── images/                 # 任意 (本リポではまだ未配置)
│       ├── icon.png
│       ├── featureGraphic.png
│       └── phoneScreenshots/
└── en-US/
    └── (同上)
```

## 画像について (P8 残)

F-Droid / Play 両方が要求するアセット (アイコン以外) はまだコミットしていない:

- `images/icon.png` — 512x512 PNG (Play 要件)
- `images/featureGraphic.png` — 1024x500 PNG (Play 要件、F-Droid は任意)
- `images/phoneScreenshots/*.png` — 1080x1920 PNG (最低 2 枚推奨)

実機スクリーンショットを撮ったらここに置いて、ロケールごとに用意する。

## バージョンの更新

新しいリリースを切るたびに `changelogs/<versionCode>.txt` を 1 つ追加する。`<versionCode>` は `app/build.gradle.kts` の `versionCode` と一致させる。

最終更新: 2026-06-20 (0.2.38 / versionCode 40 初版)
