# Foldex 動画編集 — 商用製品との機能比較

調査日: 2026-09-14 / Foldex の基準: 1.9.1、versionCode 97、コミット `988e673`

**現状の Foldex は動画を「再生」できるだけで、編集機能はない。**
この文書は比較と提案であり、記載した機能を実装済みとするものではない。
実装順序・構造・受け入れ条件は [実装設計](VIDEO-EDITOR-ROADMAP.md) に書く。
画像編集の同種の文書: [画像編集の比較](IMAGE-EDITOR-COMPARISON.md)。

## 1. 比較の前提

- 目標は **商用アプリと同等の機能・品質**。商用出荷は目的にしない（2026-09-13 のユーザー指示を動画にも適用）。
- 主な用途を「スマホで撮った動画・画面録画を切って繋ぎ、音と文字を付け、縦横や容量を整えて保存・共有・NAS へ送る」と仮定した。用途の回答に応じて優先度を変える。
- 比較先は各社の公式ページの記述で確認した。同じ端末・同じ素材で操作する比較試験はしていない。速度・画質・使いやすさの優劣は未判定。
- 有料機能を含む。料金・無料枠・地域差は比較しない。表のある欄に書いていない機能を「その製品にない」とは判断しない。
- Adobe Premiere のモバイル版は、公式発表（2025-09-30）の時点で iPhone 版のみ提供、Android 版は開発中とされている。到達点の参考として載せる。

| 比較先 | 参照した資料 | 参照する観点 |
|---|---|---|
| KineMaster | [機能一覧](https://www.kinemaster.com/features)、[クロマキー](https://www.kinemaster.com/features/chroma-key) | 多層タイムライン、キーフレーム、音声、書き出し設定 |
| CapCut | [キーフレーム](https://www.capcut.com/tools/keyframe-animation)、[速度カーブ](https://www.capcut.com/tools/speed-ramp) | キーフレームの対象、速度カーブ |
| LumaFusion（Android 版） | [LumaFusion for Android](https://luma-touch.com/lumafusion-for-android/) | トラック数、フレーム精度、LUT、音声ミックス、プロジェクト保管 |
| Adobe Premiere（モバイル、iPhone） | [公式発表](https://news.adobe.com/news/2025/09/adobe-premiere-now-delivers-fast-pro-quality-video-editing-mobile) | フレーム精度、4K HDR、字幕、声の強調 |

## 2. 機能の差

優先度は Foldex の用途からの設計判断であり、比較先の評価点ではない。
「最優先」は、それが無いと動画編集として成立しないもの。

### カット編集・タイムライン

| 機能 | 商用製品で確認できる例 | Foldex 1.9.1 | 補う内容 | 優先度 |
|---|---|---|---|---|
| 複数クリップの並べ替え・トリム・分割・削除 | 全比較先の基本機能 | なし | メイントラックの基本操作、元に戻す／やり直す | 最優先 |
| 複数トラック（重ねる映像・文字・音声） | LumaFusion「映像/音声/グラフィック 6 本＋音声 6 本」、KineMaster の多層、Premiere の無制限の多トラック | なし | 重ねる段と音声段。同時に再生できる数は端末の実測で決める | 高 |
| フレーム単位の精度 | Premiere・LumaFusion の frame-accurate editing | なし（内蔵プレーヤーはシークと ±10 秒のみ） | 1 コマ送り、時刻の数値入力、素材の実際のフレーム時刻への吸着 | 最優先 |
| 前詰め・挿入／上書き・トラックのロック／非表示／ミュート | LumaFusion の magnetic timeline、insert/overwrite、locking/hiding/muting | なし | 前詰めのメイントラック、段ごとのロック・非表示・ミュート | 高 |
| 再エンコードしない切り出し | 今回の比較先の資料では未確認。ファイラーの用途からの追加候補 | なし | キーフレーム位置での無劣化切り出し、先頭だけ再エンコードする高速トリム | 高 |
| 編集の保存・再開・保管 | LumaFusion のプロジェクト保管、Premiere のデスクトップ版への引き継ぎ | なし | 自動保存と二世代の復元、後の段階でプロジェクトの書き出し | 最優先 |

### 映像表現

| 機能 | 商用製品で確認できる例 | Foldex 1.9.1 | 補う内容 | 優先度 |
|---|---|---|---|---|
| キーフレーム | CapCut「位置・拡大・回転・形・不透明度・色」と音声、LumaFusion「無制限のキーフレーム」、KineMaster | なし | 位置・拡大・回転・不透明度・音量・色補正、イージング | 高 |
| 速度変更・速度カーブ | KineMaster 0.1〜16 倍、CapCut の速度カーブ、Premiere の speed and motion effects | なし | 一定速度（音程維持の選択）、次に速度カーブ | 高 |
| 逆再生・なめらかなスロー | LumaFusion の逆再生、120/240fps 素材のなめらかなスロー | なし | 高 fps 素材のスロー。逆再生は中間ファイルを作る方式 | 中 |
| 切り替え効果（トランジション） | LumaFusion の数十種 | なし | クロスフェード・暗転・スライドから | 高 |
| 文字・タイトル・字幕 | KineMaster の単語単位の書式、LumaFusion の多層タイトル、Premiere の animated captions | なし（画像編集の文字機能は静止画のみ） | タイトル、字幕トラック、SRT の読み書き | 高 |
| 色補正・LUT | LumaFusion の色補正と .cube / .3dl の読み込み、KineMaster の color grading | なし | 基本補正、.cube の読み込み | 中 |
| クロマキー（グリーンバック） | KineMaster（色の抽出と境界の調整）、LumaFusion の luma / chroma key | なし | 色の指定、許容範囲、境界、色かぶり除去 | 中 |
| ブレンドモード・コーナーピン | KineMaster の 16 種のブレンドモード、コーナーピン | なし | 通常合成で始め、専門機能の段階で検討 | 後続 |
| 背景除去・被写体追跡 | KineMaster の Magic Remover・AI Tracking、Premiere の背景除去 | なし（画像編集の人物・物の選択は静止画 1 枚ずつ） | まず手動の範囲指定とキーフレームで追従。自動化は個別に評価 | 後続 |
| 手ブレ補正 | 今回参照した公式資料では未確認 | なし | 専門機能の段階で個別調査 | 後続 |

### 音声

| 機能 | 商用製品で確認できる例 | Foldex 1.9.1 | 補う内容 | 優先度 |
|---|---|---|---|---|
| 音量の調整・フェード・音量の変化 | KineMaster の audio mixer と volume envelope、LumaFusion の音量・パンのキーフレーム | なし | クリップ音量、フェード、音量キーフレーム、波形表示 | 最優先／高 |
| BGM・効果音・映像からの音声分離 | 全比較先の基本機能 | なし | 音声トラック、映像の音声を別クリップへ分離 | 高 |
| 声の間だけ BGM を下げる（ダッキング） | LumaFusion の Auto-ducking | なし | 声の段から音量の変化を自動生成して、後から直せる形で保持 | 中 |
| ナレーション録音 | KineMaster の Voiceover | なし | 録音して音声トラックへ置く（マイク権限が必要） | 中 |
| EQ・リバーブ・声の加工 | KineMaster の EQ / Reverb / Voice Changer、LumaFusion の Graphic EQ | なし | EQ から検討 | 後続 |
| ノイズ除去・声の強調・音声分離 | KineMaster の Noise Remover / Vocal Separation、Premiere の Enhance Speech | なし | 端末内で動く部品の性能・ライセンスを個別に評価 | 後続 |
| 自動字幕 | KineMaster の 36 言語 | なし | 端末内の音声認識を個別に評価。まず SRT の読み込み | 後続 |

### 書き出し・作業効率

| 機能 | 商用製品で確認できる例 | Foldex 1.9.1 | 補う内容 | 優先度 |
|---|---|---|---|---|
| 解像度・fps・コーデック・ビットレート | KineMaster の SD〜4K、60fps、H.264 / HEVC、ビットレート指定、GIF | なし | 端末の符号化能力から選べる設定だけを出す | 最優先 |
| 縦横比の変更 | KineMaster の 16:9 / 9:16 / 1:1 / 4:5 など、Premiere の SNS 別サイズ変更 | なし | キャンバス比率、はめ込み／切り抜き | 高 |
| HDR | Premiere の 4K HDR 編集 | なし | まず SDR に統一（HDR 素材は変換）。HDR の保持は対応端末で後続 | 中 |
| 容量を指定して書き出す | 今回の比較先の資料では未確認。NAS・メッセージ送信の用途からの追加候補 | なし | 目標容量からビットレートを決め、書き出し後に実容量を確認 | 高 |
| 複数ファイルの一括変換・圧縮 | 今回の比較先の資料では未確認。ファイラーの用途からの追加候補 | なし | ファイル一覧の複数選択から同じ設定で順に処理 | 中 |
| 素材ライブラリ・生成 AI | KineMaster の 75,000 点以上の素材、Premiere の Firefly | なし | 対象外（通信・素材ライセンス・端末内完結の方針と合わない） | 対象外 |

## 3. Foldex の現状（コード確認）

以下はソースコードを読んだ所見。実機で不具合を再現した結果とは区別する。

| ID | 現状の根拠 | 起こり得る問題・不足 | 設計上の扱い |
|---|---|---|---|
| V1 | [VideoViewer.kt](../app/src/main/kotlin/com/zerotoship/foldex/ui/viewer/VideoViewer.kt) は ExoPlayer + PlayerView の再生のみ。WMV / ASF は外部アプリへ案内 | 編集の入口・データ・処理系が存在しない | 編集は別画面として新設。再生の既存経路は壊さない |
| V2 | リモート動画は [RemoteStreamProvider.kt](../app/src/main/kotlin/com/zerotoship/foldex/streaming/RemoteStreamProvider.kt) で範囲読みしながら再生 | 再生には十分でも、編集の細かいシーク・複数の同時読み・長い書き出しでは遅延と切断の影響が大きい | 編集前に端末内へ取り込む。元の場所は書き出し先の候補として保持 |
| V3 | `gradle/libs.versions.toml` の Media3 は 1.4.1。exoplayer / ui / session のみで、transformer / effect / muxer は未導入。全 9 モジュールが compileSdk 35 | 最新の Media3 1.11.1 は compileSdk 36 を要求する（AAR のメタデータ `minCompileSdk=36` を確認）。compileSdk 35 のまま使えるのは 1.9.3 まで | compileSdk と Media3 の更新を独立した変更単位にする |
| V4 | [VideoViewer.kt:89](../app/src/main/kotlin/com/zerotoship/foldex/ui/viewer/VideoViewer.kt) と [AudioPlayer.kt:54](../app/src/main/kotlin/com/zerotoship/foldex/ui/viewer/AudioPlayer.kt) は `ExoPlayer.Builder(context).build()`。Media3 1.4.1 の `ExoPlayer.Builder` は `usePlatformDiagnostics = true` が既定 | Android 12 以降で再生の事象・性能が OS の MediaMetricsManager へ渡る。Media3 の説明では、端末の利用者が「使用状況と診断情報の共有」を有効にしている場合に Google が収集することがある。[PRIVACY.md](PRIVACY.md) の解析 SDK なしの記述とは直接は矛盾しないが、画像編集で統計送信を避けた方針と揃っていない | 動画編集の書き出しでも同じ既定値のため、設計で扱う（実装設計 §9）。既存プレーヤーは別の小修正として提案 |
| V5 | [FileDetails.kt](../app/src/main/kotlin/com/zerotoship/foldex/ui/common/FileDetails.kt) は MediaMetadataRetriever で長さ・寸法・ビットレート等を表示 | 編集に必要な実際のフレーム時刻、キーフレーム位置、可変フレームレートか、HDR かは取得していない | 取り込み時に素材を調べる処理を新設 |
| V6 | [FileOpService](../app/src/main/kotlin/com/zerotoship/foldex/fileop/FileOpService.kt) は `dataSync` 種別の前景サービス | Android 15 で追加された `mediaProcessing` 種別（1 日 6 時間まで）を使っていない | 書き出し専用の前景サービスを新設し、OS の版で種別を切り替える |
| V7 | 画像編集の [FileFingerprint.kt](../app/src/main/kotlin/com/zerotoship/foldex/ui/imageedit/engine/FileFingerprint.kt) はファイル全体の SHA-256。下書きは元画像をアプリ内へコピー | 数 GB の動画で全体のハッシュ計算とコピーを毎回行うと、待ち時間と容量の負担が大きい | 端末内の素材は参照し、軽い照合値で変更を検出する。コピーは SAF・リモートの素材に限る |
| V8 | 画像編集には原本保護の保存（CE0-A）、メモリ予算と実行枠（CE0-B）、世代付き下書き（CE0-D）がある | 動画にそのまま流用できる型ではないが、保存先ごとの確定方法・結果未確認の扱い・世代管理の考え方は同じ | 契約を踏襲し、動画用の実装を別に持つ |

## 4. Foldex で先に目指す到達点

1. 撮った動画を切って繋ぎ、不要な部分を消し、容量を落として保存・NAS 送信できる。失敗しても原本と編集データが残る。
2. BGM・ナレーション・タイトル・字幕を付け、音量を整えられる。
3. 縦横比を変え、映像・画像・文字を重ねてキーフレームで動かせる。
4. 色を整え、LUT・クロマキー・範囲の目隠しで見た目を作れる。
5. 長い作業を中断して再開でき、書き出しの設定と結果（要求どおりか）が分かる。

この範囲を「スマホで完結する動画編集としての商用品質」の目標にする。
生成 AI、クラウドの素材ライブラリ・テンプレート配信、共同編集、マルチカメラ、8K、デスクトップ編集ソフトとの完全な互換は初期の必須機能に含めない。

現状の強みは、ファイル管理から直接編集に入れること、NAS 等への入出力経路、端末内完結の方針、MIT の本体と部品の告知の整理ができていること。この導線を保ったまま上記を補う。
