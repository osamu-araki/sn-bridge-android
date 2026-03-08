// Version: 1.0.0 | Updated: 2026-03-08
// [2026-03-08] service-worker.js の extractText/extractDom をそのまま文字列として保持
package jp.salesnow.chromebridge.fetcher

/**
 * WebView の evaluateJavascript() に渡す JS 関数
 * Chrome 拡張の service-worker.js (L248-345) と同一ロジック
 */
object JsExtractors {

    /** テキストモード: ページのテキスト・タイトル・URL を返す */
    val EXTRACT_TEXT = """
        (function() {
            return JSON.stringify({
                text: document.body.innerText,
                title: document.title,
                url: location.href
            });
        })();
    """.trimIndent()

    /** DOM モード: テキスト + 構造データ（メタ・見出し・リンク・テーブル・JSON-LD・画像）を返す */
    val EXTRACT_DOM = """
        (function() {
            var text = document.body.innerText;
            var title = document.title;
            var url = location.href;

            // メタデータ
            var meta = {};
            document.querySelectorAll('meta[name], meta[property]').forEach(function(el) {
                var key = el.getAttribute('name') || el.getAttribute('property');
                if (key) meta[key] = el.getAttribute('content') || '';
            });

            // 見出し構造
            var headings = [];
            document.querySelectorAll('h1, h2, h3, h4, h5, h6').forEach(function(el) {
                var t = el.innerText.trim();
                if (t) headings.push({ level: parseInt(el.tagName[1]), text: t });
            });

            // リンク一覧
            var links = [];
            var seenHrefs = {};
            document.querySelectorAll('a[href]').forEach(function(el) {
                var href = el.href;
                var linkText = el.innerText.trim();
                if (href && linkText && !seenHrefs[href] && href.indexOf('http') === 0) {
                    seenHrefs[href] = true;
                    links.push({ text: linkText.slice(0, 200), href: href });
                }
            });

            // テーブルデータ
            var tables = [];
            var allTables = document.querySelectorAll('table');
            for (var tIdx = 0; tIdx < allTables.length && tIdx < 10; tIdx++) {
                var table = allTables[tIdx];
                var headers = [];
                table.querySelectorAll('thead th, thead td, tr:first-child th').forEach(function(th) {
                    headers.push(th.innerText.trim());
                });
                var rows = [];
                var bodyRows = table.querySelectorAll('tbody tr');
                var targetRows = bodyRows.length > 0 ? bodyRows : table.querySelectorAll('tr');
                for (var rIdx = 0; rIdx < targetRows.length && rIdx < 100; rIdx++) {
                    var cells = [];
                    targetRows[rIdx].querySelectorAll('td, th').forEach(function(td) {
                        cells.push(td.innerText.trim());
                    });
                    if (cells.length > 0 && cells.some(function(c) { return c; })) rows.push(cells);
                }
                if (rows.length > 0) {
                    tables.push({ headers: headers.length > 0 ? headers : null, rows: rows });
                }
            }

            // 構造化データ (JSON-LD)
            var jsonLd = [];
            document.querySelectorAll('script[type="application/ld+json"]').forEach(function(el) {
                try { jsonLd.push(JSON.parse(el.textContent)); } catch(e) {}
            });

            // 画像一覧
            var images = [];
            document.querySelectorAll('img[src]').forEach(function(el) {
                var src = el.src;
                var alt = el.alt || '';
                if (src && src.indexOf('http') === 0) {
                    images.push({ src: src, alt: alt.slice(0, 200) });
                }
            });
            images.splice(50);

            return JSON.stringify({
                text: text,
                title: title,
                url: url,
                dom: {
                    meta: meta,
                    headings: headings,
                    links: links.slice(0, 200),
                    tables: tables,
                    json_ld: jsonLd.length > 0 ? jsonLd : null,
                    images: images.length > 0 ? images : null
                }
            });
        })();
    """.trimIndent()
}
