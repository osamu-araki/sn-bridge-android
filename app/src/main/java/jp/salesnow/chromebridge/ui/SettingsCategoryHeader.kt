// Version: 1.0.0 | Updated: 2026-06-27
// [2026-06-27] 設定画面のカテゴリヘッダー Composable。
//   アイコン (lucide ライン風) + タイトル + サブテキストで 4 カテゴリを視覚的に区別する。
package jp.salesnow.chromebridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.ImageVector.Builder
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.salesnow.chromebridge.ui.theme.*

/**
 * 4 カテゴリ用のセクションヘッダー。
 *
 * @param icon lucide ライン風のアイコン (ServerIcon / FetchIcon / ShieldIcon / BellIcon を渡す)
 * @param title カテゴリ名
 * @param subtitle 配下のサブセクションを 1 行で要約 (例: "サーバー / アップデート / 省電力")
 */
@Composable
fun CategoryHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = Color(0xFFE1F1F2),
                    shape = RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Teal,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = NavyDark,
            )
            Text(
                subtitle,
                fontSize = 11.sp,
                color = GrayLight,
            )
        }
    }
}

// =====================================================================
// アイコン定義 (lucide-react スタイルのライン SVG パスを ImageVector で再現)
// =====================================================================

/** server: 接続・基盤用。サーバー筐体のアイコン */
val ServerIcon: ImageVector by lazy {
    Builder(
        name = "Server",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        ) {
            // 上部ラック
            moveTo(2f, 4f); lineTo(22f, 4f); lineTo(22f, 10f); lineTo(2f, 10f); close()
            // 下部ラック
            moveTo(2f, 14f); lineTo(22f, 14f); lineTo(22f, 20f); lineTo(2f, 20f); close()
            // インジケータ点 (上)
            moveTo(6f, 7f); lineTo(6.01f, 7f)
            // インジケータ点 (下)
            moveTo(6f, 17f); lineTo(6.01f, 17f)
        }
    }.build()
}

/** arrows: fetch 動作用。双方向矢印 */
val FetchIcon: ImageVector by lazy {
    Builder(
        name = "Fetch",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        ) {
            // 横線
            moveTo(3f, 12f); lineTo(21f, 12f)
            // 右矢印
            moveTo(15f, 6f); lineTo(21f, 12f); lineTo(15f, 18f)
            // 左矢印
            moveTo(9f, 6f); lineTo(3f, 12f); lineTo(9f, 18f)
        }
    }.build()
}

/** shield: チャレンジ・ブロック用。盾 */
val ShieldIcon: ImageVector by lazy {
    Builder(
        name = "Shield",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        ) {
            moveTo(12f, 22f)
            curveTo(12f, 22f, 20f, 18f, 20f, 12f)
            lineTo(20f, 5f)
            lineTo(12f, 2f)
            lineTo(4f, 5f)
            lineTo(4f, 12f)
            curveTo(4f, 18f, 12f, 22f, 12f, 22f)
            close()
        }
    }.build()
}

/** bell: 通知・ログ用。ベル */
val BellIcon: ImageVector by lazy {
    Builder(
        name = "Bell",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        ) {
            // ベル本体
            moveTo(6f, 8f)
            curveTo(6f, 4.69f, 8.69f, 2f, 12f, 2f)
            curveTo(15.31f, 2f, 18f, 4.69f, 18f, 8f)
            curveTo(18f, 15f, 21f, 17f, 21f, 17f)
            lineTo(3f, 17f)
            curveTo(3f, 17f, 6f, 15f, 6f, 8f)
            close()
            // クラッパー
            moveTo(10f, 21f)
            curveTo(10f, 22.1f, 10.9f, 23f, 12f, 23f)
            curveTo(13.1f, 23f, 14f, 22.1f, 14f, 21f)
        }
    }.build()
}
