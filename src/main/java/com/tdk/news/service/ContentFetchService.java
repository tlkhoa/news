package com.tdk.news.service;

import com.tdk.news.repository.ArticleRepository;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dịch vụ lazy-load toàn văn bài viết (chiến thuật hybrid).
 *
 * Luồng:
 *   1. Lần đầu mở bài: nếu Content == null hoặc ContentFetchedAt == null → app GET HTML từ Url,
 *      trích xuất phần文章 chính, rồi lưu vào cột "Content" + "ContentFetchedAt".
 *   2. Lần sau mở cùng bài: nếu Content đã có và ContentFetchedAt không null → lấy trực tiếp từ DB,
 *      không gọi lại mạng.
 *
 * Không phụ thuộc thư viện ngoài JDK (HttpURLConnection + regex) để build được trong môi trường
 * không có Maven central.
 */
@Service
@Transactional
public class ContentFetchService {

    private static final int HTTP_TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 2_000_000; // 2 MB
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    // Các thẻ/xu hướng cần loại bỏ (quảngardt, thanh điều hướng, ...).
    private static final String[] REMOVE_TAGS = {
        "script", "style", "noscript", "iframe", "svg",
        "nav", "header", "footer", "aside",
        "button", "input", "form"
    };

    private static final Pattern SCRIPT_STYLE_PATTERN = Pattern.compile(
        "<(script|style|noscript)[\\s\\S]*?</\\1>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern BODY_PATTERN = Pattern.compile(
        "<body[\\s\\S]*?</body>", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    // Từ khóa class/id đánh dấu các khối KHÔNG phải nội dung bài (liên quan, quảng cáo, bình luận, sidebar...).
    private static final String[] NON_CONTENT_KEYWORDS = {
        "related", "side-bar", "sidebar", "widget", "advertisement", "ad-",
        "banner-ad", "ad-banner", "comment", "comments", "share", "social",
        "breadcrumb", "navigation", "menu", "author-bio", "about-author",
        "tags", "newsletter", "more-from", "read-also", "xem-them", "bai-lien-quan"
    };
    private static final Pattern NON_CONTENT_BLOCK_PATTERN = Pattern.compile(
        "<(div|section|article|main)[\\s\\S]*?(?:class|id)=\"[^\"]*("
        + String.join("|", NON_CONTENT_KEYWORDS) + ")[^\"]*\"[\\s\\S]*?</\\1>",
        Pattern.CASE_INSENSITIVE);

    private final ArticleRepository articleRepository;

    public ContentFetchService(ArticleRepository articleRepository) {
        this.articleRepository = articleRepository;
    }

    /**
     * Lấy toàn văn của bài viết theo id.
     * Nếu DB chưa có nội dung (Content rỗng hoặc ContentFetchedAt null) → fetch từ Url, lưu DB, rồi trả về.
     * Nếu DB đã có → trả về nội dung đã lưu.
     *
     * @return nội dung toàn văn (đã làm sạch), hoặc null nếu không lấy được.
     */
    public String fetchContent(Long id) {
        var articleOpt = articleRepository.findById(id);
        if (articleOpt.isEmpty()) {
            return null;
        }

        var article = articleOpt.get();
        String content = article.getContent();
        LocalDateTime fetchedAt = article.getContentFetchedAt();

        if (content != null && !content.isBlank() && fetchedAt != null) {
            // Đã có nội dung trong DB → trả về luôn, không gọi mạng.
            return content;
        }

        String url = article.getUrl();
        if (url == null || url.isBlank()) {
            return null;
        }

        try {
            String html = fetchHtml(url);
            if (html == null || html.isBlank()) {
                return null;
            }

            String extracted = extractMainContent(html);
            if (extracted == null || extracted.isBlank()) {
                return null;
            }

            // Chỉ giữ ảnh cùng domain với trang gốc, loại bỏ ảnh quảng cáo/tracking từ domain khác.
            extracted = filterExternalImages(extracted, url);

            // Lưu vào DB để lần sau không cần fetch lại.
            articleRepository.updateContent(id, extracted, LocalDateTime.now());
            return extracted;
        } catch (Exception e) {
            System.err.println("[ContentFetch] Lỗi fetch nội dung id=" + id + " url=" + url + " | " + e.getMessage());
            return null;
        }
    }

    /** Fetch HTML từ URL (giống pattern đã dùng trong RssCollectionService). */
    private String fetchHtml(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty("User-Agent", USER_AGENT);
        con.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        con.setConnectTimeout(HTTP_TIMEOUT_MS);
        con.setReadTimeout(HTTP_TIMEOUT_MS);
        con.setInstanceFollowRedirects(true);

        int code = con.getResponseCode();
        if (code < 200 || code >= 400) {
            throw new IOException("HTTP " + code);
        }

        try (InputStream in = con.getInputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            StringBuilder sb = new StringBuilder();
            int read;
            while ((read = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, read, StandardCharsets.UTF_8));
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("Phản hồi quá lớn (> " + MAX_RESPONSE_BYTES + " bytes)");
                }
            }
            return sb.toString();
        } finally {
            con.disconnect();
        }
    }

    /**
     * Trích xuất phần文章 chính từ HTML bằng regex (không dùng Jsoup).
     * Chiến thuật:
     *   1. Loại bỏ script/style/noscript và các thẻ không cần.
     *   2. Ưu tiên thẻ <article>, sau đó <main>, fallback lấy phần tử có văn bản dài nhất.
     *   3. Loại bỏ các thẻ HTML còn lại, giữ lại cấu trúc đoạn văn cơ bản.
     */
    private String extractMainContent(String html) {
        // Bước 1: xóa script, style, noscript cùng nội dung bên trong.
        String cleaned = SCRIPT_STYLE_PATTERN.matcher(html).replaceAll("");

        // Bước 2: xóa toàn bộ các khối không phải nội dung bài (theo thẻ).
        for (String tag : REMOVE_TAGS) {
            cleaned = cleaned.replaceAll("(?i)<" + tag + "[\\s\\S]*?</" + tag + ">", "");
            cleaned = cleaned.replaceAll("(?i)</?" + tag + "[^>]*>", "");
        }

        // Bước 3: xóa các khối đánh dấu là phụ (bài liên quan, quảng cáo, bình luận, sidebar...).
        cleaned = NON_CONTENT_BLOCK_PATTERN.matcher(cleaned).replaceAll("");

        // Bước 4: chọn vùng chứa nội dung chính.
        //   Ưu tiên thẻ <article>, sau đó <main>. Nếu có nhiều khối, lấy khối có văn bản dài nhất
        //   (tránh bị bẫy vào thẻ <article> nhỏ của khổ bài liên quan bên cạnh).
        String mainHtml = pickLargestBlock(cleaned, "article", "main");

        if (mainHtml == null || stripTags(mainHtml).length() < 200) {
            // Fallback: lấy toàn bộ body (đã được xóa các khối phụ ở trên).
            Matcher body = BODY_PATTERN.matcher(cleaned);
            mainHtml = body.find() ? body.group() : cleaned;
        }

        // Bước 5: chuyển HTML sang văn bản có cấu trúc đơn giản.
        String text = toReadableText(mainHtml);
        return WHITESPACE_PATTERN.matcher(text).replaceAll(" ").trim();
    }

    /**
     * Tìm tất cả các khối của các thẻ cho trước, trả về khối có văn bản sạch dài nhất.
     * Dùng để chọn <article> hay <main> chứa bài viết chính thay vì khối con nhỏ bên cạnh.
     */
    private String pickLargestBlock(String html, String... tags) {
        String best = null;
        int bestLen = -1;
        for (String tag : tags) {
            Pattern p = Pattern.compile("<" + tag + "[\\s\\S]*?</" + tag + ">", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(html);
            while (m.find()) {
                String block = m.group();
                int len = stripTags(block).replaceAll("\\s+", " ").trim().length();
                if (len > bestLen) {
                    bestLen = len;
                    best = block;
                }
            }
        }
        return best;
    }

    /** Chuyển HTML sang văn bản có cấu trúc (giữ ngắt dòng cho p, br, h1-h6...). */
    private static String toReadableText(String html) {
        // Thay các thẻ khối bằng ký tự ngắt dòng.
        String s = html
            .replaceAll("(?i)</?(p|div|h[1-6]|li|tr|blockquote|section)[^>]*>", "\n")
            .replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("(?i)</?article[^>]*>", "\n")
            .replaceAll("(?i)</?main[^>]*>", "\n");

        // Xóa tất cả thẻ HTML còn lại.
        s = TAG_PATTERN.matcher(s).replaceAll("");

        // Giải mã một số thực thể HTML phổ biến.
        s = s.replace("&nbsp;", " ")
             .replace("&amp;", "&")
             .replace("&lt;", "<")
             .replace("&gt;", ">")
             .replace("&quot;", "\"")
             .replace("&#39;", "'")
             .replace("&apos;", "'");

        return s;
    }

    /** Loại bỏ tất cả thẻ HTML, trả về văn bản thô. */
    private static String stripTags(String html) {
        return TAG_PATTERN.matcher(html).replaceAll(" ");
    }

    /**
     * Xóa bỏ các thẻ <img> có src không cùng domain với trang gốc.
     * Giữ lại ảnh tương đối (/path) và ảnh thuộc cùng host.
     */
    private static String filterExternalImages(String html, String pageUrl) {
        if (html == null || pageUrl == null || pageUrl.isBlank()) {
            return html;
        }
        String baseHost;
        try {
            baseHost = new URL(pageUrl).getHost();
            if (baseHost == null) {
                return html;
            }
        } catch (MalformedURLException e) {
            return html;
        }
        baseHost = baseHost.toLowerCase();

        Pattern imgPattern = Pattern.compile(
            "<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
        Matcher m = imgPattern.matcher(html);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        while (m.find()) {
            String src = m.group(1);
            boolean keep = true;
            if (src != null && !src.isBlank()) {
                String s = src.trim();
                // Ảnh tương đối (bắt đầu bằng '/' hoặc không có scheme) → cùng domain, giữ lại.
                if (s.startsWith("/") || !s.matches("(?i)^(https?|ftp)://.*")) {
                    keep = true;
                } else {
                    // Ảnh tuyệt đối → so sánh host.
                    try {
                        URL u = new URL(s);
                        String h = u.getHost();
                        keep = (h != null && h.toLowerCase().equals(baseHost));
                    } catch (MalformedURLException ex) {
                        keep = false;
                    }
                }
            }
            if (!keep) {
                // Xóa toàn bộ thẻ img này.
                result.append(html, lastEnd, m.start());
                lastEnd = m.end();
            }
        }
        if (lastEnd == 0) {
            return html;
        }
        result.append(html, lastEnd, html.length());
        return result.toString();
    }

    /* ---------- Kết quả ---------- */

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FetchResult {
        private Long articleId;
        private boolean fetched;      // true nếu đã có nội dung (từ DB hoặc vừa fetch)
        private boolean justFetched;  // true nếu vừa fetch từ mạng (không phải từ DB)
        private String content;
        private String note;
    }
}
