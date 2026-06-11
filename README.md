# SalesNow Bridge Android

Android 端末上で HTTP サーバー + WebView ページ取得 + Cloudflare Tunnel を提供する社内ツール。
外部クライアント（n8n / GAS / curl 等）から `POST /fetch` でページ内容を取得する。

社内 3 台（bridge1 / bridge2 / bridge3）が常時稼働し、SalesNow Customer Portal
（[QuickWorkInc/sncs-customer-portal](https://github.com/QuickWorkInc/sncs-customer-portal)）から
ヘルス監視・OTA 配信・リリース管理が行われる。

## 構成

```
SalesNow Bridge Android
├── BridgeHttpServer (NanoHTTPd)    … HTTP API サーバー
├── HeadlessWebViewFetcher          … WebView によるページ取得
├── BridgeForegroundService         … バックグラウンド維持
├── TunnelManager + DnsProxy        … cloudflared プロセス管理
├── UpdateChecker (OTA)             … Portal manifest 取得 → 自己更新
└── UI (Jetpack Compose)            … 設定・ログ表示
```

## API 仕様

### `POST /fetch`

ページを取得してテキストまたは DOM 構造を返す。

```bash
curl -X POST https://<tunnel-domain>/fetch \
  -H "Authorization: Bearer <API_KEY>" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com", "mode":"text", "wait":3}'
```

**リクエストパラメータ:**

| パラメータ | 型 | 必須 | デフォルト | 説明 |
|-----------|-----|------|-----------|------|
| url | string | はい | - | 取得対象の URL |
| mode | string | いいえ | "text" | "text" または "dom" |
| wait | int | いいえ | 3 | ページ読み込み後の待機秒数 |
| max_length | int | いいえ | 50000 | テキストの最大文字数 |
| timeout | int | いいえ | 30 | タイムアウト秒数 |

**レスポンス例（text モード）:**

```json
{
  "ok": true,
  "mode": "text",
  "url": "https://example.com",
  "final_url": "https://example.com/",
  "title": "Example Domain",
  "text": "Example Domain\nThis domain is for use in...",
  "length": 129,
  "elapsed_ms": 1234
}
```

### `GET /status`

サーバーとプール状態を返す（ヘルスチェック用、Bearer 認証）。

```bash
curl https://<tunnel-domain>/status -H "Authorization: Bearer <API_KEY>"
```

### `POST /update-check`

Portal の「全端点に通知」から呼ばれる。Portal manifest の取得 → SHA-256 検証 → 自己更新を
fire-and-forget で起動する（Bearer 認証）。

### `GET /stats?period=daily&limit=30`

日次・月次の集計データを返す（管理 UI 用）。

## 運用フロー（標準）

```
main push
  → GitHub Actions が APK を自動ビルド（cloudflared 公式バイナリ + debug 署名）
  → Portal の bridge_app_releases に is_published=false で投入
  → Portal /admin/bridge-monitor → リリースタブで動作確認後、公開トグル ON
  → 「全端点に『更新を確認』を送信」 or 各端末の自動チェック（1 時間毎）
  → 各端末が manifest 取得 → SHA-256 検証 → PackageInstaller で自己更新
```

`applicationId` と署名 keystore は固定なので、端末の SharedPreferences（Tunnel Token・
ログ・統計）はすべて保持される。**新リリース配布で端末側の再設定は不要**。

詳細手順: [docs/ci-release-setup.md](docs/ci-release-setup.md)

## 新端末の追加手順

新しい Android 端末を bridgeN として運用に加えるときの最小手順。

### 1. Cloudflare Tunnel の作成

[Cloudflare Zero Trust](https://one.dash.cloudflare.com/) → **Networks** → **Tunnels**

1. **Create a tunnel** → **Cloudflared** → トンネル名（例: `bridge4`）→ **Save tunnel**
2. 表示されたトークン（`eyJ...`）をコピー
3. **Public Hostnames** → **Add a public hostname**
   - **Subdomain**: `bridge4`（任意）
   - **Domain**: `salesnow-cs.jp`
   - **Type**: HTTP / **URL**: `localhost:3000`
4. **Save hostname** → 数秒で `https://bridge4.salesnow-cs.jp` が有効になる

DNS の CNAME レコードは自動作成される。CLI（`cloudflared tunnel create`）で
作成したトンネルは Public Hostname を設定できないので、必ずダッシュボード経由で作成すること。

### 2. APK のインストール

CI 最新ビルドの APK を GitHub Actions の artifact から取得して USB install する。

```bash
# 最新の成功 run ID を取得
RUN_ID=$(gh run list -R osamu-araki/sn-bridge-android \
  --workflow release-apk.yml --status success --limit 1 \
  --json databaseId --jq '.[0].databaseId')

# artifact をダウンロード
gh run download "$RUN_ID" -R osamu-araki/sn-bridge-android --dir /tmp/sn-bridge

# install
adb install -r /tmp/sn-bridge/*/app-debug.apk
```

> **重要**: CI ビルドは `debug.keystore` を GitHub Secrets から復元して署名している。
> ローカルで `./gradlew assembleDebug` した APK と同じ署名なので、既存端末の上書きや
> ローカルビルドと CI ビルドの混在運用が可能。

### 3. 端末側の設定

アプリを起動して以下を設定：

| 項目 | 値 | 備考 |
|---|---|---|
| Port | 3000 | デフォルトのまま |
| API Key | 任意の文字列 | n8n などから呼ぶときの Bearer |
| Tunnel Token | 手順 1-2 のトークン | 端末ごとに固有 |
| Tunnel Domain | `bridge4.salesnow-cs.jp` | 表示用 |
| Portal Manifest URL | 空欄でよい | BuildConfig 既定値を使用 |
| Check Token | 空欄でよい | BuildConfig 既定値を使用 |
| 自動チェック | ON のまま | 1 時間毎に Portal を polling |

「設定を保存」→ サーバー・Tunnel が自動で起動する。

### 4. Portal への端点登録

salesnow_admin で `/admin/bridge-monitor` → 設定タブ → **Bridge 端点** → 端点追加：

- **Name**: `bridge4`
- **URL**: `https://bridge4.salesnow-cs.jp`
- **API Key**: 手順 3 で設定したものと同じ
- **Active**: ON

これで監視対象 + OTA 配信対象に加わる。

### 5. 長期稼働のための省電力対策（必須）

[長期稼働のための設定（必須）](#長期稼働のための設定必須) を参照。

## バックグラウンド動作

- Foreground Service により、アプリを閉じてもサーバーと Tunnel は維持される
- 通知バーに「SalesNow Bridge」が表示されている間は稼働中
- 端末再起動後は `BootReceiver` が Service を自動起動する

## 長期稼働のための設定（必須）

Android OS とメーカー独自の省電力機構によって、Foreground Service ですら強制停止される
ことがある。長時間（夜間越し等）安定稼働させるには **3 段階の許可** を行う必要がある。

すべてアプリ内の「設定 → 省電力対策」セクションから誘導できる。

### 1. バッテリー最適化の除外（Android 標準）

設定タブの「除外設定」ボタン → システムダイアログで許可。
端末側の手順: 設定 → 電池 → バッテリー最適化 → SalesNow Bridge を「最適化しない」。

### 2. メーカー独自の自動起動許可（最重要）

**OPPO / Realme / Xiaomi / Redmi / Poco / Huawei / Honor / Vivo / iQOO** 等は、
標準のバッテリー最適化除外だけでは不十分。各メーカーの「自動起動管理」画面で
SalesNow Bridge を**明示的に許可**する必要がある。

設定タブの「自動起動設定を開く（メーカー名）」ボタンから該当画面に直接遷移する。

| メーカー | 設定画面の名称（参考） |
|---------|-----------------------|
| OPPO / Realme | スマートマネージャー → 権限とプライバシー → 自動起動の管理 |
| Xiaomi / Redmi | セキュリティ → 権限 → 自動起動 |
| Huawei / Honor | スマートアシスタント → アプリ起動 → 手動管理 |
| Vivo | i マネージャー → アプリマネージャー → 自動起動 |
| Samsung | 設定 → 電池 → バッテリー使用量 → 制限なし |

### 3. 「不明な提供元のインストール」許可（OTA 必須）

アプリ自体が新バージョンを install できるようにするため必要。
設定タブの「アップデート」セクション → 「許可設定」ボタンから誘導される。

OS の許可がないと OTA は事前チェックで停止し、ユーザー操作を待つ通知が出る。

### 4. 最近使ったアプリでロック（推奨）

タスクリスト（最近使ったアプリ）から SalesNow Bridge を「ロック」状態にする
（メーカー UI により名称異なる、長押し → 鍵アイコン等）。これでユーザー誤操作・
端末メモリ整理時の停止を防げる。

> **重要**: 上記 1, 2, 3 のいずれかが欠けると、夜間にプロセスが殺されて翌朝まで
> 停止し続ける / OTA が止まる事象が発生する。社内 3 台運用では Portal の
> ヘルスチェック + Slack 通知も併用すること（[Portal /admin/bridge-monitor](https://cs.salesnow.jp/admin/bridge-monitor) 設定タブ）。

## 技術詳細

| 項目 | 値 |
|------|-----|
| 最小 API レベル | 26 (Android 8.0) |
| ターゲット API | 35 |
| HTTP サーバー | NanoHTTPd 2.3.1 |
| ページ取得 | Android WebView + evaluateJavascript |
| Tunnel | cloudflared 公式 Linux ARM64 (CI で取得) |
| Tunnel 接続 | QUIC + `--edge` 固定 IP（DNS 不要） |
| UI | Jetpack Compose + Material 3 |
| OTA | PackageInstaller + SHA-256 検証 |
| 署名 | debug keystore（CI が Secrets から復元） |

### Tunnel の DNS 経路

Android では `/etc/resolv.conf` が無く、cloudflared が DNS lookup に
`[::1]:53` へフォールバックする。port 53 は privileged port なので一部 OS
（OPPO Android 9 等）では DnsProxy の bind が EACCES で失敗する。

これを回避するため、起動時に cloudflared へ `--edge IP:7844` を 6 個複数回指定して
edge discovery（DNS lookup）を bypass する経路に統一している。
詳細: [TunnelManager.kt](app/src/main/java/jp/salesnow/chromebridge/tunnel/TunnelManager.kt)
の `DEFAULT_CLOUDFLARED_EDGE_IPS`。

Cloudflare 側で edge IP 帯が変更された場合は `SettingsRepository.cloudflaredEdgeIps`
（ChromeBridge アプリ設定の pref キー）で上書き可能。

## ファイル構成

```
app/src/main/java/jp/salesnow/chromebridge/
├── MainActivity.kt                    # エントリーポイント
├── ChromeBridgeApp.kt                 # Application（クラッシュハンドラ等）
├── BootReceiver.kt                    # 端末再起動後の自動起動
├── ChallengeActivity.kt               # Cloudflare 認証画面手動解除
├── ui/
│   ├── MainScreen.kt                  # Compose UI
│   ├── SettingsTab.kt                 # 設定画面
│   └── theme/Theme.kt                 # SalesNow ブランドカラー
├── server/
│   ├── BridgeHttpServer.kt            # NanoHTTPd サーバー
│   ├── AuthMiddleware.kt              # Bearer Token 認証
│   ├── UrlPolicy.kt                   # SSRF 防御
│   └── RateLimiter.kt                 # レート制限
├── fetcher/
│   ├── WebViewPool.kt                 # WebView プール（並列処理）
│   └── ChallengeManager.kt            # Cloudflare チャレンジ検知
├── service/
│   └── BridgeForegroundService.kt     # Foreground Service
├── tunnel/
│   ├── TunnelManager.kt               # cloudflared プロセス管理
│   └── DnsProxy.kt                    # DNS プロキシ（IPv4/IPv6）
├── update/
│   ├── UpdateChecker.kt               # OTA: manifest → DL → install
│   └── PackageInstallerReceiver.kt    # install 結果コールバック
└── data/
    ├── SettingsRepository.kt          # SharedPreferences 管理
    ├── StatsDatabase.kt               # 統計 DB
    └── LogFileWriter.kt               # ログファイル管理
```

> Kotlin パッケージ名と applicationId は `jp.salesnow.chromebridge` のまま保持している
> （変更すると既存 3 台が別アプリ扱いになり Tunnel Token 含む設定が消えるため）。
> UI 表示名のみ「SalesNow Bridge」に統一済。

## ローカルでのビルド（補足）

通常運用は CI 経由なので不要。手元で APK を作って install したいとき向け。

### 前提

- Android Studio + Android SDK（API 26 以上）
- ローカルの `~/.android/debug.keystore`（CI と同じ署名を保つため、自動生成される
  もので OK。初回 build 時に Android Studio が作る）

### ビルド

```bash
git clone https://github.com/osamu-araki/sn-bridge-android.git
cd sn-bridge-android

# cloudflared バイナリは CI が download している。ローカル build では
# 公式 Linux ARM64 を一度配置すれば以後 .gitignore で追跡されない。
mkdir -p app/src/main/jniLibs/arm64-v8a
curl -fsSL -o app/src/main/jniLibs/arm64-v8a/libcloudflared.so \
  https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### versionCode / versionName / OTA token の上書き

```bash
BRIDGE_APP_CHECK_TOKEN_BUILD="<Portal の bridge_app_check_token と同値>" \
VERSION_CODE_OVERRIDE=701 \
VERSION_NAME_OVERRIDE=1.2.7-dev \
./gradlew assembleDebug
```

env を省略すると `versionCode=3 / versionName=1.2.0 / Check Token=空` でビルドされる。
手元 dev 版を端末に入れた場合、versionCode を CI の連番（`<github.run_number>00`）と
被らない値にしておくこと（被ると OTA が「最新です」と判定して更新を取らない）。

## 参考

- [docs/ci-release-setup.md](docs/ci-release-setup.md) — CI / GitHub Secrets / OTA セットアップ
- [docs/android-overview.html](docs/android-overview.html) — Android 実装の構造図
- [docs/requirements-and-design.html](docs/requirements-and-design.html) — 初版設計ドキュメント
