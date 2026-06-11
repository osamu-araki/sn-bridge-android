# SalesNow Bridge Android

Android 端末上で HTTP サーバー + WebView ページ取得 + Cloudflare Tunnel を提供するアプリ。
外部クライアント（n8n / GAS / curl 等）から `POST /fetch` でページ内容を取得できる。

## 構成

```
SalesNow Bridge Android
├── BridgeHttpServer (NanoHTTPd)    … HTTP API サーバー
├── HeadlessWebViewFetcher          … WebView によるページ取得
├── BridgeForegroundService         … バックグラウンド維持
├── TunnelManager + DnsProxy        … cloudflared プロセス管理
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
  "elapsed_ms": 3076
}
```

**レスポンス例（dom モード）:**

```json
{
  "ok": true,
  "mode": "dom",
  "url": "https://ja.wikipedia.org/wiki/東京都",
  "title": "東京都 - Wikipedia",
  "text": "...",
  "length": 1503,
  "elapsed_ms": 7263,
  "dom": {
    "meta": {...},
    "headings": [{"level": 1, "text": "東京都"}, ...],
    "links": [...],
    "tables": [...],
    "images": [...]
  }
}
```

**エラーレスポンス:**

エラー時は以下の形式で返す。`retryable` と `category` を見て再試行可否を判定できる。

```json
{
  "ok": false,
  "error": "pool_busy",
  "message": "WebView プールが空きません（5秒待機）",
  "retryable": true,
  "category": "bridge"
}
```

| フィールド | 説明 |
|-----------|------|
| retryable | 再試行で回復しうるか（true: 再試行可 / false: 再試行不可） |
| category | `bridge`（Bridge 自身の一時障害）/ `target`（対象 URL 個別の失敗）/ `client`（リクエスト不正） |

**ステータスコードとエラー種別:**

原則として **HTTP 500 は返さない**。一時障害は 503、対象 URL 個別の失敗は 200 + `ok:false`、
クライアント起因は 4xx で返す。

| error | HTTP | retryable | category | 説明 |
|-------|------|-----------|----------|------|
| server_busy | 503 | true | bridge | 処理中リクエストが上限超過（過負荷） |
| pool_busy | 503 | true | bridge | WebView プールが枯渇 |
| renderer_gone | 503 | true | bridge | WebView レンダラが異常終了（自動復旧する） |
| internal_error | 503 | true | bridge | 想定外の内部エラー |
| timeout | 200 | true | target | ページ取得がタイムアウト |
| fetch_failed | 200 | true | target | WebView のページ読み込みエラー |
| extract_failed | 200 | false | target | ページからのテキスト/DOM 抽出に失敗 |
| parse_failed | 200 | false | target | 抽出結果のパースに失敗 |
| bad_request | 400 | false | client | url 未指定・JSON 不正など |
| unauthorized / forbidden | 401 / 403 | false | client | API キー認証エラー |

- **503** には `Retry-After`（秒）ヘッダが付与される。クライアントはこの間隔を空けて再試行する。
- **200 + `ok:false`** は「Bridge は正常だが対象 URL の取得に失敗した」ことを意味する。
  クライアントは HTTP ステータスだけでなく必ずレスポンスボディの `ok` を確認すること。

### `GET /status`

サーバーの状態を返す。

```json
{
  "webview_ready": true,
  "pending_requests": 0,
  "queue_depth": 0,
  "pool_size": 4,
  "pool_available": 4,
  "max_timeout": 60,
  "max_wait": 10,
  "uptime_seconds": 3593,
  "total_requests": 1284,
  "total_errors": 17,
  "total_timeouts": 9,
  "avg_elapsed_ms": 4231
}
```

| フィールド | 説明 |
|-----------|------|
| pending_requests / queue_depth | 処理中＋プール待ちのリクエスト数 |
| pool_size / pool_available | WebView プールの総数 / 空き数 |
| total_requests / total_errors / total_timeouts | 起動来の累計（fetch を実行した回数・失敗数・タイムアウト数。認証エラーや bad_request、過負荷拒否は含まない） |
| avg_elapsed_ms | fetch を実行したリクエストの平均処理時間（ミリ秒、起動来） |

## セットアップ手順

### 前提条件

- macOS（ビルド環境）
- Android Studio + Android SDK（API 26 以上）
- Go 1.21+（cloudflared ビルド用）
- Android NDK r27+（cloudflared クロスコンパイル用）
- 実機またはエミュレータ（ARM64）
- Cloudflare アカウント（無料プランで可）

### 1. リポジトリのクローン

```bash
git clone https://github.com/salesnow-cs/sn-bridge-android.git
cd sn-bridge-android
```

### 2. Android SDK / NDK のインストール

Android Studio がインストール済みであれば SDK は自動的に配置される。
NDK は以下で追加インストール:

```bash
# SDK Manager で NDK をインストール
sdkmanager "ndk;27.0.12077973"
```

### 3. cloudflared バイナリのビルド

Android の非 root 環境では Go のネイティブ DNS リゾルバが動作しないため、
`CGO_ENABLED=1` でビルドして Android のシステム DNS を使わせる必要がある。

```bash
# Go のインストール（未インストールの場合）
brew install go

# cloudflared ソースの取得
cd /tmp
git clone --depth 1 --branch 2026.2.0 https://github.com/cloudflare/cloudflared.git cloudflared-src

# Android ARM64 向けにクロスコンパイル
export NDK_ROOT="$HOME/Library/Android/sdk/ndk/27.0.12077973"
export CC="$NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang"
export CXX="$NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang++"

cd /tmp/cloudflared-src
CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC=$CC CXX=$CXX \
  go build -o /tmp/cloudflared-android -ldflags="-s -w" ./cmd/cloudflared

# プロジェクトに配置
mkdir -p <project>/app/src/main/jniLibs/arm64-v8a/
cp /tmp/cloudflared-android <project>/app/src/main/jniLibs/arm64-v8a/libcloudflared.so
```

> **重要**: `CGO_ENABLED=0`（デフォルト）でビルドすると、Go の純粋 DNS リゾルバが
> `/etc/resolv.conf` を参照するが、Android には存在しないため DNS 解決に失敗する。
> `CGO_ENABLED=1` + NDK clang で動的リンクすると `getaddrinfo()` 経由で Android の
> システム DNS が使われる。

### 4. APK のビルド

```bash
cd sn-bridge-android
./gradlew assembleDebug
```

出力先: `app/build/outputs/apk/debug/app-debug.apk`

### 5. 実機へのインストール

```bash
# USB デバッグを有効にした端末を接続
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 6. Cloudflare Tunnel の作成

Cloudflare Zero Trust ダッシュボードからトンネルを作成する。
ダッシュボードから作成したトンネルはトークン方式で管理され、アプリにトークンを入力するだけで接続できる。

#### 6-1. トンネルの作成

1. [Cloudflare Zero Trust](https://one.dash.cloudflare.com/) にログイン
2. **Networks** → **Connectors** → **Create a tunnel**
3. **Cloudflared** を選択 → **Next**
4. トンネル名を入力（例: `chrome-bridge`）→ **Save tunnel**
5. トークン（`eyJ...` 形式の文字列）が表示されるのでコピーしておく → **Next**

#### 6-2. Public Hostname の設定

トンネル作成の続きで、または既存トンネルの Configure → Public Hostname タブで設定する。

1. **Add a public hostname** をクリック
2. 以下を入力:
   - **Subdomain**: 任意（例: `bridge`）
   - **Domain**: Cloudflare で管理しているドメインを選択
   - **Type**: `HTTP`
   - **URL**: `localhost:3000`
3. **Save hostname**

これにより `https://<subdomain>.<domain>` へのリクエストがアプリの HTTP サーバーに転送される。

> **補足**: CLI（`cloudflared tunnel create`）で作成したトンネルは「ローカル管理型」となり、
> ダッシュボードから Public Hostname を設定できない。アプリのトークン方式で利用するには
> ダッシュボードから作成することを推奨する。

#### 6-3. DNS レコードの確認

Public Hostname を設定すると、Cloudflare DNS に CNAME レコードが自動作成される。
手動で確認・修正する場合:

1. Cloudflare ダッシュボード（メインサイト）→ 対象ドメイン → **DNS** → **Records**
2. Tunnel タイプのレコードが作成されていることを確認
3. Content が正しいトンネル名を指していることを確認

### 7. アプリの設定

1. アプリを起動するとサーバーが自動起動する
2. **Port**: デフォルト 3000（必要に応じて変更）
3. **API Key**: 任意の文字列を設定（外部アクセス時の認証に使用）
4. **Tunnel Token**: 手順 6-1 で取得したトークンを入力
5. **Tunnel Domain**: Tunnel に紐づくドメイン名を入力（表示用）
6. 「接続」ボタンで Tunnel を開始

### 8. 動作確認

```bash
# ローカル（同一ネットワーク内）
curl http://<Android IP>:3000/status

# Tunnel 経由
curl https://<tunnel-domain>/status

# ページ取得テスト
curl -X POST https://<tunnel-domain>/fetch \
  -H "Authorization: Bearer <API_KEY>" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com","mode":"text"}'
```

## バックグラウンド動作

- Foreground Service により、アプリを閉じてもサーバーと Tunnel は維持される
- 通知バーに「SalesNow Bridge」が表示されている間は稼働中
- 端末再起動後は `BootReceiver` が Service を自動起動する

## 長期稼働のための設定（必須）

Android OS とメーカー独自の省電力機構によって、Foreground Service ですら強制停止される
ことがある。長時間（夜間越し等）安定稼働させるには **3段階の許可**を行う必要がある。

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

### 3. 最近使ったアプリでロック（推奨）

タスクリスト（最近使ったアプリ）から SalesNow Bridge を「ロック」状態にする
（メーカー UI により名称異なる、長押し → 鍵アイコン等）。これでユーザー誤操作・
端末メモリ整理時の停止を防げる。

> **重要**: 上記いずれかが欠けると、夜間にプロセスが殺されて翌朝まで停止し続ける
> 事象が発生する。3台運用であれば**外部からのヘルスチェック + 通知**もあわせて
> 設定することを強く推奨する。

## 技術詳細

| 項目 | 値 |
|------|-----|
| 最小 API レベル | 26 (Android 8.0) |
| ターゲット API | 35 |
| HTTP サーバー | NanoHTTPd 2.3.1 |
| ページ取得 | Android WebView + evaluateJavascript |
| Tunnel | cloudflared (CGO_ENABLED=1 ビルド) |
| UI | Jetpack Compose + Material 3 |
| レート制限 | 20 req/min per IP |
| キュー上限 | 10 件 |

## ファイル構成

```
app/src/main/java/jp/salesnow/chromebridge/
├── MainActivity.kt                    # エントリーポイント
├── ui/
│   ├── MainScreen.kt                  # Compose UI
│   └── theme/Theme.kt                 # SalesNow ブランドカラー
├── server/
│   ├── BridgeHttpServer.kt            # NanoHTTPd サーバー
│   ├── AuthMiddleware.kt              # Bearer Token 認証
│   └── RateLimiter.kt                 # レート制限
├── fetcher/
│   ├── HeadlessWebViewFetcher.kt      # WebView ページ取得
│   └── JsExtractors.kt               # extractText/extractDom JS
├── service/
│   └── BridgeForegroundService.kt     # Foreground Service
├── tunnel/
│   ├── TunnelManager.kt               # cloudflared プロセス管理
│   └── DnsProxy.kt                    # DNS プロキシ（エミュレータ用）
└── data/
    └── SettingsRepository.kt           # SharedPreferences 管理
```
