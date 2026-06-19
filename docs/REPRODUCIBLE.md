# Reproducible Build — Foldex

最終更新: 2026-06-20 (0.2.38 / versionCode 40 時点)

> このドキュメントは Foldex のビルド再現性 (Reproducible Build) に関する方針と手順を記録する。F-Droid の [Reproducible Builds](https://f-droid.org/en/docs/Reproducible_Builds/) に向けた前提整備。

## 1. 結論 (要約)

- リポジトリ側で **バージョン関連はすべて pin 済み** (Gradle wrapper / AGP / Kotlin / Compose BOM / 依存ライブラリ)。
- 同じソース + 同じツール環境 (JDK 17 / Gradle 8.14.3) で `./gradlew :app:assembleRelease` を流せば、**署名前の APK は決定論的に同じバイナリ**になることを目標とする。
- 完全な Reproducible Build (F-Droid Builds Server の出力との照合) は F-Droid 配布後に検証する。

## 2. 固定済みのビルド構成要素

| 要素 | バージョン | 固定場所 |
|---|---|---|
| Gradle | 8.14.3 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.13.0 | `gradle/libs.versions.toml` (`agp`) |
| Kotlin | 2.2.10 | `gradle/libs.versions.toml` (`kotlin`) |
| Compose BOM | 2026.04.01 | `gradle/libs.versions.toml` (`compose-bom`) |
| KSP | (Kotlin 同梱) | `gradle/libs.versions.toml` |
| compileSdk / targetSdk | 35 | `app/build.gradle.kts` |
| minSdk | 26 | `app/build.gradle.kts` |
| JDK | 17 | CI: `actions/setup-java@v4` (`temurin` / 17), ローカル: 同左推奨 |
| versionCode / versionName | 各リリースタグ時点で固定 | `app/build.gradle.kts` |

依存ライブラリは `gradle/libs.versions.toml` の Version Catalog で **すべて明示固定**。`+` や `latest.release` 等の動的バージョンは使用していない。

## 3. ビルド手順 (検証用)

1. リポジトリを取得しタグをチェックアウト:
   ```bash
   git clone https://github.com/orgsonai/foldex.git
   cd foldex
   git checkout v0.2.38   # 例
   ```

2. JDK 17 (Temurin 推奨) と Android SDK (compileSdk 35) を用意。Gradle は wrapper を使用。

3. `keystore.properties` と `release.keystore` を**用意しない**状態でビルド (debug 鍵フォールバック):
   ```bash
   ./gradlew :app:assembleRelease
   ls -lh app/build/outputs/apk/release/
   ```

   - debug 鍵フォールバックの動作は `app/build.gradle.kts` の `signingConfig = if (keystorePropsFile.exists()) ... else signingConfigs.getByName("debug")` の分岐に依存。

4. 署名前のクラスファイル・リソース・DEX を比較する場合は、APK を `unzip` で展開し、`META-INF/` の署名ファイル (`*.RSA`, `*.SF`, `MANIFEST.MF` の署名差分) を除いて diff を取る:
   ```bash
   unzip -d a app/build/outputs/apk/release/app-release.apk
   # 別環境で同じ操作 → unzip -d b ...
   diff -r a b -x 'META-INF/*'
   ```

## 4. 再現性に影響する既知のノイズ

- **AGP の `version-control-info.textproto`**: AGP 8.13 では APK 内に Git の HEAD コミット情報が埋め込まれる場合がある。同じタグから出すなら同一になる。
- **タイムスタンプ**: ZIP エントリのタイムスタンプは AGP のデフォルトで一定 (固定値) になっている (`androidx.archive.useTimestamp=false` 相当)。
- **R8 / D8**: Kotlin リフレクションの除去や proguard ルールの差分で変動が起きる可能性。`app/proguard-rules.pro` 内容は固定。

## 5. F-Droid 側で必要な作業 (アプリ側スコープ外)

- F-Droid Data リポジトリ ([`fdroiddata`](https://gitlab.com/fdroid/fdroiddata)) に `metadata/com.zerotoship.foldex.yml` を追加。
- そこに `Builds:` セクションで本リポの該当タグ・ビルドコマンド・出力 APK を指定。
- F-Droid Builds Server が自動でビルドし、リリース APK と照合。一致すれば Reproducible Build として記録される。

## 6. 残課題

- 依存ライブラリ単位のハッシュ検証 (`gradle/verification-metadata.xml`) は未導入。CI 安定後に検討。
- F-Droid 配布後の Reproducible Builds の検証結果に応じて、本ドキュメントを更新する。
