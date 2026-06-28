// Version: 1.4.0 | Updated: 2026-06-10
// [2026-06-10] versionCode / versionName を環境変数で上書き可能に（GitHub Actions 用）
// [2026-06-10] OTA デフォルト値（manifest URL / check token）を BuildConfig 経由で埋め込む
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "jp.salesnow.chromebridge"
    compileSdk = 35

    // [2026-06-10] CI 上で AGP のデフォルト挙動が ~/.android/debug.keystore を見ない
    //   ケースがあったため、debug 署名 keystore のパスを明示する。
    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "jp.salesnow.chromebridge"
        minSdk = 26
        targetSdk = 35
        // [2026-06-10] CI から env で上書き可能（手元ビルドは固定値）
        versionCode = System.getenv("VERSION_CODE_OVERRIDE")?.toIntOrNull() ?: 3
        versionName = System.getenv("VERSION_NAME_OVERRIDE") ?: "1.2.0"

        // [2026-06-10] OTA デフォルト値（端末側で空欄なら BuildConfig から fallback）
        //   - manifest URL は固定値（公開エンドポイント）
        //   - check token は CI ビルド時の env から注入。env 無し（手元ビルド）の場合は空文字
        buildConfigField(
            "String",
            "DEFAULT_PORTAL_MANIFEST_URL",
            "\"https://cs.salesnow.jp/api/bridge-app/manifest\"",
        )
        buildConfigField(
            "String",
            "DEFAULT_PORTAL_CHECK_TOKEN",
            "\"${System.getenv("BRIDGE_APP_CHECK_TOKEN_BUILD") ?: ""}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // [2026-06-10] OTA デフォルト値 (DEFAULT_PORTAL_MANIFEST_URL / DEFAULT_PORTAL_CHECK_TOKEN)
        buildConfig = true
    }

    // [2026-03-08] cloudflared バイナリ（Go ELF）をストリップしない
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libcloudflared.so"
        }
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // NanoHTTPd（軽量 HTTP サーバー）
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")

    // [2026-03-14] SAF DocumentFile（ログバックアップ用）
    implementation("androidx.documentfile:documentfile:1.0.1")

    // [2026-06-28] AndroidX WebKit (WebViewCompat.addDocumentStartJavaScript 用)
    //   navigator.* を document 読み込み直前に inject する API。
    //   onPageStarted の evaluateJavascript より確実にページ JS より前に実行される。
    implementation("androidx.webkit:webkit:1.12.1")

    // [2026-06-28] Cronet（Chromium ネットワークスタック）
    //   WebView の HTTP リクエストを intercept し、独自の TLS handshake を使うため。
    //   embedded 版は APK サイズ +約50MB だが Google Play Services 非依存で確実に動作する。
    implementation("org.chromium.net:cronet-embedded:119.6045.31")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
