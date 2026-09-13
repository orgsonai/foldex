# Foldex 画像編集 — 商用製品との機能比較

調査日: 2026-09-13 / Foldex の基準: 1.8.0、versionCode 90、コミット `68f7e4e`

**現状は基本加工の土台ができている段階。日常の編集を商用品質にするには、選択の修正、写真の補正、再編集、保存の信頼性を補う必要がある。**
この文書は比較と提案であり、記載した不足機能を実装済みとするものではない。
実装順序・構造・受け入れ条件は [実装設計](IMAGE-EDITOR-ROADMAP.md) に記載する。
調査後の対応: [1.8.2 の保存基盤](IMAGE-EDITING-1.8.2.md)、[1.8.3 のメモリ管理](IMAGE-EDITING-1.8.3.md)、[1.8.4 の透過・撮影情報・容量指定](IMAGE-EDITING-1.8.4.md)。以下の比較表・コード上の課題は 1.8.0 時点の記録。

## 1. 比較の前提

- 主用途は「写真・スクリーンショットを直す、見せたくない部分を隠す、保存・共有・NAS 転送前に整える」と仮定した。用途の回答に応じて優先順位を変更できる。
- Android 向けの商用アプリを中心に、レイヤー編集の到達点を知るため Web エディタも参照する。
- 比較先は公式ヘルプの機能説明を確認した。各アプリを同一端末・同一画像で操作する比較試験は実施していない。速度・精度・使いやすさの優劣は未判定。
- 有料機能を含む。料金、無料枠、地域別提供、すべての端末での利用可否は比較しない。ある製品の欄を載せていない機能も「その製品にはない」とは判断しない。
- Foldex の「実装あり」はソースコード上の確認。既存の JVM テスト 92 件とデバッグビルドの成功記録はあるが、Android 実機による商用出荷判定は未完了。

| 比較先 | 対象 | 参照する用途 |
|---|---|---|
| Photoshop Express | Android の公式ガイド | 写真補正、傾き・遠近、文字、ぼかし、保存。[公式ガイド](https://helpx.adobe.com/photoshop-express/mobile/introduction/get-started-android.html) |
| Lightroom | Mobile。Android を含む公式ヘルプ | 明暗の補正、部分補正、修復、RAW。[明暗の補正](https://helpx.adobe.com/lightroom/mobile/adjust-light-and-color/adjust-light-in-photos.html)、[マスク](https://helpx.adobe.com/lightroom/mobile/masks-and-selective-adjustments/masking-tools.html) |
| Photoroom | Android の操作説明、全プラットフォーム共通の切り抜き説明 | 背景削除後の修正、商品画像の一括加工。[切り抜き](https://help.photoroom.com/en/articles/13743688-how-to-use-edit-cutout)、[Android のバッチ編集](https://help.photoroom.com/en/articles/14287616-edit-multiple-images-at-once-in-batch-workflow-android) |
| Photopea | Web。Android ネイティブアプリとの同条件比較ではない | レイヤー、マスク、境界調整、編集可能なファイル保存。[レイヤー](https://www.photopea.com/learn/layers)、[保存](https://www.photopea.com/learn/opening-saving) |

## 2. 機能の差

「一部あり」は、モデルの器だけある場合と、操作できるが用途を完結できない場合を含む。どこまでできるかを併記する。
優先度は Foldex の用途からの設計判断であり、比較先の評価点ではない。

### 選択・目隠し・切り抜き

| 機能 | 商用製品で確認できる例 | Foldex 1.8.0 | 補う内容 | 優先度 |
|---|---|---|---|---|
| 矩形・楕円・投げ縄、追加・除外・反転 | Photopea の手動選択と組み合わせ。[選択](https://www.photopea.com/learn/creating-selections) | 実装あり | 選択の移動、数値指定、拡大時の細かな調整 | 中 |
| 人物・物の自動選択 | Lightroom の被写体選択、Photoroom の補助付き選択。[マスク](https://helpx.adobe.com/lightroom/mobile/masks-and-selective-adjustments/masking-tools.html)、[切り抜き](https://help.photoroom.com/en/articles/13743688-how-to-use-edit-cutout) | 実装あり。タップした対象を端末内で推論 | 複雑な背景・細い物の評価、追加タップと手動修正の操作改善 | 高 |
| 選択ブラシ、消す／戻す | Photoroom の手動 Erase / Restore。[切り抜き](https://help.photoroom.com/en/articles/13743688-how-to-use-edit-cutout) | 未実装。手動の矩形・楕円・投げ縄修正は可能 | 塗って追加・除外、ブラシ径、境界を見やすくする表示 | 高 |
| なめらかな境界、髪・毛・半透明 | Photopea の Refine Edge とマスクの Feather。[境界調整](https://www.photopea.com/learn/refine-edge)、[マスク](https://www.photopea.com/learn/masks) | 一部あり。AI の 512×512 出力を二値の輪郭へ変換。半透明マスクなし | 境界の拡張・縮小・ぼかし、8bit マスク。髪の高精度処理は別の評価工程 | 高 |
| 色・明るさによる選択 | Lightroom の Color Range / Luminance Range。[マスク](https://helpx.adobe.com/lightroom/mobile/masks-and-selective-adjustments/masking-tools.html) | 未実装。タップ AI は色領域選択とは別機能 | 最初は近い色が連続する範囲を選ぶマジックワンド、許容差 | 中 |
| モザイク・塗りつぶし・ぼかし | Photoshop Express の全体／部分ぼかし。[Android ガイド](https://helpx.adobe.com/photoshop-express/mobile/introduction/get-started-android.html) | モザイクと選択範囲の塗りつぶしあり。画像のぼかしなし | モザイクの適用後再調整、なぞって目隠し、不透明な目隠し、ぼかし | 高 |
| 顔・文字の一括検出と目隠し | 今回は対応製品・精度を未比較。Foldex の利用場面からの追加候補 | 未実装。人物タップ選択は顔検出・OCR ではない | 候補一覧と確認操作。見逃し評価とモデル選定が必要 | 後続 |
| 背景削除・復元・置き換え | Photoroom の背景削除後の Erase / Restore。[切り抜き](https://help.photoroom.com/en/articles/13743688-how-to-use-edit-cutout) | 一部あり。被写体選択→反転→透明化→PNG 保存は可能 | 専用の切り抜き操作、残す／戻すマスク、単色／別画像の背景 | 高 |

### 写真補正・注釈・合成

| 機能 | 商用製品で確認できる例 | Foldex 1.8.0 | 補う内容 | 優先度 |
|---|---|---|---|---|
| 明るさ・コントラスト・彩度・色温度 | Photoshop Express の Adjustments。[Android ガイド](https://helpx.adobe.com/photoshop-express/mobile/introduction/get-started-android.html) | 未実装 | まず基本補正、リセット、適用前後の比較 | 高 |
| 露出・ハイライト・シャドウ | Lightroom の Light パネル。[明暗の補正](https://helpx.adobe.com/lightroom/mobile/adjust-light-and-color/adjust-light-in-photos.html) | 未実装 | 階調を保つ補正、ヒストグラム。基本補正の後に追加 | 中 |
| 部分的な色調整 | Lightroom の個別マスクによる補正。[マスク](https://helpx.adobe.com/lightroom/mobile/masks-and-selective-adjustments/masking-tools.html) | 未実装 | 確定したマスクを補正に結び付け、後から強度を変える | 中 |
| 傾き・遠近の補正 | Photoshop Express の任意角回転・遠近補正。[Android ガイド](https://helpx.adobe.com/photoshop-express/mobile/introduction/get-started-android.html) | 縦横比固定・グリッド付き切り抜き、90°回転、反転あり。写真の任意角・遠近 UI なし | 水平補正と既存の切り抜き操作の連動。四隅の遠近補正は後続 | 高／後続 |
| 手描き・色選択 | 今回はブラシ性能の同条件比較なし | ブラシ幅・硬さ・不透明度、固定 12 色、自分の線を消す消しゴムあり | 自由色、スポイト、線の補間。筆圧は利用端末を見て判断 | 高 |
| 文字の編集 | Photoshop Express の書体・配置・不透明度など。[Android ガイド](https://helpx.adobe.com/photoshop-express/mobile/introduction/get-started-android.html) | 文字の再入力、標準 3 書体、サイズ・太字・縁取り・移動・回転あり | 揃え、背景、透明度、キャンバス上で文字を選ぶ操作 | 高 |
| 矢印・枠・番号の注釈 | 今回は専用注釈アプリを未比較。スクショ用途からの追加候補 | 未実装。フリーハンドで代用可能 | 後で動かせる矢印・矩形・楕円・連番 | 高 |
| レイヤー操作 | Photopea の並び替え、表示、不透明度、複製、ロック。[レイヤー](https://www.photopea.com/learn/layers) | 一部あり。内部の Layer と一覧の器はあるが汎用レイヤーパネルなし | 選択、移動、表示、複製、削除、前後関係、ロック | 高 |
| 複数画像・ロゴの合成 | Photopea の画像を別レイヤーに挿入する操作。[保存と読み込み](https://www.photopea.com/learn/opening-saving) | 未実装。元画像を解決する処理は 1 枚用 | 画像追加、変形、切り抜きとの組み合わせ | 高 |
| レイヤーマスク | Photopea の非破壊マスク、濃度、Feather。[マスク](https://www.photopea.com/learn/masks) | 未実装。透明化の Fill は下の合成全体を抜く操作 | 画像単位の隠す／戻す、画像の移動に追従するマスク | 高 |
| 修復・クローン・不要物除去 | Lightroom の Heal と Clone。[修復](https://helpx.adobe.com/lightroom/mobile/heal-clone-and-remove/replace-areas-using-the-heal-tool.html) | 未実装。透明化は周囲で穴を埋める修復とは異なる | 最初はクローンと小さな傷の修復。大きな物体除去は別途品質評価 | 後続 |

### 再編集・出力・作業効率

| 機能 | 商用製品で確認できる例 | Foldex 1.8.0 | 補う内容 | 優先度 |
|---|---|---|---|---|
| 元に戻す／やり直す | 比較先の上限・保持期間は未比較 | 編集中の Undo / Redo は 50 手 | 編集単位の統一、効果の再選択、変更前後の比較 | 高 |
| 編集可能なファイルを保存 | Photopea の PSD 保存と画像エクスポートの分離。[保存](https://www.photopea.com/learn/opening-saving) | 未実装。JPEG 等の保存後は編集内容が画素に統合される | 下書き復元と Foldex のプロジェクト保存。PSD 互換は別課題 | 最優先／高 |
| サイズ・形式・品質・容量指定 | Photoshop Express のリサイズ・JPEG 品質。[Android ガイド](https://helpx.adobe.com/photoshop-express/mobile/introduction/get-started-android.html) | 長辺／割合、JPEG・PNG・WebP、品質、目標容量あり | 幅高さ入力、容量未達の通知、用途プリセット、透過の正確な案内 | 高 |
| メタデータ・保存先 | この比較では他製品の原本保護・メタデータ処理の内部保証は未確認 | GPS 削除の指定あり。SAF 元画像の別名保存は無効 | 保存先選択、撮影情報の保持結果、共有用出力、失敗からの復旧 | 最優先 |
| バッチ編集・設定の再利用 | Photoroom Android のサイズ・配置・背景などの一括処理。[バッチ編集](https://help.photoroom.com/en/articles/14287616-edit-multiple-images-at-once-in-batch-workflow-android) | 未実装 | ファイル一覧で複数選択→縮小・変換・メタデータ設定を一括適用 | 中 |
| RAW などの写真向け形式 | Lightroom Mobile の Camera RAW / DNG 対応。[対応形式](https://helpx.adobe.com/lightroom/mobile/get-started/supported-file-formats.html) | RAW 現像なし。色管理・高ビット深度の編集契約も未整備 | まず sRGB の入出力を明確にする。RAW / HDR は専用段階で検討 | 後続 |

Photoroom のバッチには有料条件がある。一般説明は無料プランでも開始できる旨、Android の個別説明は有料プランでの提供を記載しており、開始可能範囲の表現に差がある。比較上は「有料条件を含む一括編集機能がある」までとする。[一般説明](https://help.photoroom.com/en/articles/11784338-what-is-the-batch-feature)、[Android 説明](https://help.photoroom.com/en/articles/14287616-edit-multiple-images-at-once-in-batch-workflow-android)

## 3. 機能数より先に解決する品質上の差

以下は Foldex のコードを読んだ所見。実際に障害を再現した結果とは区別する。比較先すべてが以下を保証している、という意味でもない。

| ID | 現状の根拠 | 起こり得る問題・不足 | 設計上の扱い |
|---|---|---|---|
| Q1 | `deliver()` は一時出力を最終ファイルの `outputStream()` へコピー | 同名上書きで容量不足・書き込み中断が起きると原本が途中まで書き換わる。エディタ側に置換トランザクションなし | 最優先。出力先別に原本保護の方式を決める |
| Q2 | `drawImageLayer()` は source 不在で return。`withBitmap()` は decode 失敗を呼び出し元へ返さない | 素材を描けなかった画像が、描画成功として出力され得る | 最優先。素材欠落を描画失敗として返す |
| Q3 | `ImageSource.load()` は要求以上のキャッシュを維持 | 保存／容量見積もり後の `load(2048)` では、大きなキャッシュを縮小できない | 最優先。貸し出し終了後の縮小・解放を明示 |
| Q4 | `DocumentRenderer` は出力全体を Bitmap に確保。出力制限は長辺 8192 | 8192×8192 の ARGB_8888 は 1 枚で 256 MiB。元画像、レイヤー中間画像、AI 領域等を加えると長辺制限だけでは足りない | 最優先。画素数と同時確保量で処理前に判定 |
| Q5 | `requestEstimate()` は出力寸法で描画し、`encodedSize()` は ByteArrayOutputStream を利用 | 保存ダイアログでも大きな画像と圧縮データを確保。例外処理・古い結果の排除も統一されていない | 最優先。メモリを増やさない計測、処理の直列化、世代管理 |
| Q6 | `performSave()` は `QualityResult.quality` のみ利用し、EXIF を後で付加 | 目標容量に入らなくても理由が出ない。最終容量と見積もりも異なり得る | 最優先。メタデータ込みの最終バイト数で判定 |
| Q7 | 画像編集の状態は ViewModel とメモリ内履歴に保持 | プロセス終了後の再開なし。選択・文字・モザイクの編集状態を再利用できない | 最優先。素材の保存と世代付き下書き |
| Q8 | `hasTransparentArea` は背景が Transparent なら true。通常の読み込みもこの背景 | 不透明 JPEG にも透明部分の警告が出る保守的な判定。画像単位の透過性が未管理 | 高。実際の素材・合成状態に沿う案内 |
| Q9 | 保存イベントを受けて Activity 終了。リモート上書き後に入力キャッシュも更新 | 現状は保存して編集終了。今後「書き出して編集を続ける」を追加するには不変の素材が必要 | 高。書き出し先と編集用素材を分離 |
| Q10 | `addStroke()` は最後の Drawing に追記。画像用 resolver は単一素材 | 新しい線が後から追加した文字／効果の下になる。Layer の器だけでは合成機能は完成しない | 高。対象レイヤーと効果の適用順を定義 |

根拠ファイル（調査時点）:

- [ImageEditViewModel.kt](/home/orgson/tmp_folder/3/13_Zero-to-Ship/app_project/04_foldex/app/src/main/kotlin/com/zerotoship/foldex/ui/imageedit/ImageEditViewModel.kt): `requestEstimate` / `performSave` / `deliver` / `sourceResolver` / `addStroke`
- [DocumentRenderer.kt](/home/orgson/tmp_folder/3/13_Zero-to-Ship/app_project/04_foldex/app/src/main/kotlin/com/zerotoship/foldex/ui/imageedit/engine/DocumentRenderer.kt): `render` / `drawImageLayer`
- [ImageSource.kt](/home/orgson/tmp_folder/3/13_Zero-to-Ship/app_project/04_foldex/app/src/main/kotlin/com/zerotoship/foldex/ui/imageedit/engine/ImageSource.kt): `load` / `withBitmap`
- [ImageEncoder.kt](/home/orgson/tmp_folder/3/13_Zero-to-Ship/app_project/04_foldex/app/src/main/kotlin/com/zerotoship/foldex/ui/imageedit/engine/ImageEncoder.kt): `encodedSize` / `findQualityForTarget`
- [EditDocument.kt](/home/orgson/tmp_folder/3/13_Zero-to-Ship/app_project/04_foldex/app/src/main/kotlin/com/zerotoship/foldex/ui/imageedit/model/EditDocument.kt): `hasTransparentArea` / `ofSingleImage`
- [ImageEditActivity.kt](/home/orgson/tmp_folder/3/13_Zero-to-Ship/app_project/04_foldex/app/src/main/kotlin/com/zerotoship/foldex/ui/imageedit/ImageEditActivity.kt): 保存成功後の終了

## 4. Foldex で先に目指す到達点

1. 書き込みに失敗しても原本を残し、編集中の状態を復元できる。
2. 選択して隠すまでを短い操作で済ませ、拡大して境界を修正できる。
3. 明暗・色・傾きを直し、文字・矢印・枠で説明を加えられる。
4. 切り抜いた人物・商品を別背景に置き、後日文字や位置を直せる。
5. 同じ出力設定を複数画像に適用し、保存先へ確実に届けられる。

この範囲をまず「日常の画像編集としての商用品質」の目標にする。RAW 現像、CMYK・印刷向け処理、PSD の完全互換、生成 AI、複雑なベクター編集までを初回の必須機能には含めない。これらは用途が異なり、画像処理基盤と検証の規模も大きくなる。

現状の強みは、ファイル管理から編集に入れること、NAS 等への保存経路、モザイクとタップ選択の端末内処理、MIT の本体と依存部品の告知が整理されていること。この導線を保ちながら上記を補う。
