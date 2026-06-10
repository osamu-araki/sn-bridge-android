# CI による APK 自動配信のセットアップ

`main` への push 毎に GitHub Actions が APK をビルドし、SalesNow AIワークフロー（Portal）の
OTA リリース一覧に `is_published=false` で投入する。公開（端末への配信開始）は Portal の
`/admin/bridge-monitor` → リリースタブで手動切替する。

## 必要な GitHub Secrets

リポジトリの **Settings → Secrets and variables → Actions** に以下の Secret を登録する。

| Name | 値 |
|---|---|
| `PORTAL_BASE_URL` | Portal のベース URL（例: `https://portal.example.com`、末尾スラッシュなし） |
| `BRIDGE_APP_UPLOAD_TOKEN` | Portal の `/admin/bridge-monitor` → リリースタブの **Upload Token** から「Token を発行」して得た値 |
| `ANDROID_DEBUG_KEYSTORE_B64` | ローカルで運用中の `~/.android/debug.keystore` を base64 化したもの（既存端末と同じ署名を保つため） |

### `ANDROID_DEBUG_KEYSTORE_B64` の作り方（macOS）

```sh
base64 -i ~/.android/debug.keystore | pbcopy
```

クリップボードに base64 文字列がコピーされる。GitHub Secret に貼り付ける。

### `BRIDGE_APP_UPLOAD_TOKEN` を再発行した場合

Portal の管理 UI で「Token を再発行」を押すと、`system_settings.bridge_app_upload_token` が
新値に置き換わる。GitHub Secret も同じ値に上書きしないと以降のビルドが 401 で失敗する。

## バージョン採番

| 項目 | 値 |
|---|---|
| `versionCode` | `<github.run_number>00`（例: run #5 → `500`） |
| `versionName` | `1.2.<github.run_number>` |

手元ビルド（`./gradlew assembleDebug`）の `versionCode=3` と被らないように、CI は最低 100 以上を割り当てる。

## 配信フロー

1. `main` への push をトリガーに Actions が起動
2. APK ビルド（`./gradlew assembleDebug`、`debug.keystore` で署名）
3. Portal `/api/admin/bridge-monitor/releases/upload-url` で signed PUT URL を取得
4. Supabase Storage に APK を PUT
5. Portal `/api/admin/bridge-monitor/releases` にメタデータ（SHA-256 / size）を登録
6. Portal UI のリリース一覧に `未公開` として表示される
7. 動作確認したら Portal 上で公開トグル ON → 全端末が次回 OTA チェックで取得

「いま配信したい」場合は公開トグル ON 後に「全端点に『更新を確認』を送信」ボタンで即時通知できる。

## ロールバック

公開後に問題が出たら Portal でトグル OFF にするだけでよい（公開リリースが消えれば、
新規 OTA チェックでは何も返らないため、端末は現バージョンのまま留まる）。
旧バージョンに戻したい場合は、旧バージョンの公開トグルを ON にする。
