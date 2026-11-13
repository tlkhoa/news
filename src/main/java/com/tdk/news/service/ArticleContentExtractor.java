package com.tdk.news.service;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

/**
 * Kết quả trích xuất: HTML sạch (không script/ads), hình, meta.
 * Port từ C# ArticleContentExtractor sang Java (dùng jsoup thay cho HtmlAgilityPack).
 */
@Service
public class ArticleContentExtractor {

    private static final Set<String> ALLOWED_TAGS = new HashSet<String>();
    static {
        ALLOWED_TAGS.add("p");
        ALLOWED_TAGS.add("h2");
        ALLOWED_TAGS.add("h3");
        ALLOWED_TAGS.add("h4");
        ALLOWED_TAGS.add("blockquote");
        ALLOWED_TAGS.add("img");
        ALLOWED_TAGS.add("ul");
        ALLOWED_TAGS.add("ol");
        ALLOWED_TAGS.add("li");
        ALLOWED_TAGS.add("figure");
        ALLOWED_TAGS.add("figcaption");
        ALLOWED_TAGS.add("a");
        ALLOWED_TAGS.add("strong");
        ALLOWED_TAGS.add("em");
        ALLOWED_TAGS.add("b");
        ALLOWED_TAGS.add("i");
        ALLOWED_TAGS.add("br");
        ALLOWED_TAGS.add("hr");
        ALLOWED_TAGS.add("table");
        ALLOWED_TAGS.add("thead");
        ALLOWED_TAGS.add("tbody");
        ALLOWED_TAGS.add("tr");
        ALLOWED_TAGS.add("td");
        ALLOWED_TAGS.add("th");
        ALLOWED_TAGS.add("caption");
    }

    private static final Set<String> DROP_CLASSES = new HashSet<String>();
    static {
        DROP_CLASSES.add("quang-cao");
        DROP_CLASSES.add("ads");
        DROP_CLASSES.add("advert");
        DROP_CLASSES.add("social");
        DROP_CLASSES.add("share");
        DROP_CLASSES.add("comment");
        DROP_CLASSES.add("related");
        DROP_CLASSES.add("google-news");
        DROP_CLASSES.add("newsletter");
    }

    private static final String[] CONTENT_SELECTORS = {
        "article.fck_detail", "div.fck_detail",
        "div.detail-content",
        "div.singular-content",
        "div.content-details__main-content", "div.article__content",
        "div.article__body", "div.cms-body", "div.zce-content-body",
        "div[itemprop=articleBody]",
        "div.content-detail", "article.article-content",
        "div.article-content", "div.the-content",
        "article"
    };

    public static class ExtractedArticle {
        public final String html;
        public final int textLength;
        public final int imageCount;
        public final String heroImageUrl;

        public ExtractedArticle(String html, int textLength, int imageCount, String heroImageUrl) {
            this.html = html;
            this.textLength = textLength;
            this.imageCount = imageCount;
            this.heroImageUrl = heroImageUrl;
        }
    }

    /**
     * Trích xuất nội dung bài viết từ trang HTML công khai của báo (không vượt CAPTCHA/paywall):
     * 1. Tìm khối nội dung chính (selector hay gặp ở báo Việt Nam, fallback chấm điểm theo độ dài &lt;p&gt;),
     * 2. Dựng HTML sạch: chỉ giữ tag an toàn (p, h2-h4, img, ul/li, table, a, strong/em…),
     *    mọi thuộc tính ngoài src/alt/href bị loại bỏ, URL tương đối chuyển thành tuyệt đối.
     */
    public ExtractedArticle extract(String html, URL pageUrl) {
        Document doc = Jsoup.parse(html, pageUrl.toString());

        // Xoá phần nhiễu: script, style, quảng cáo, navigation, bình luận…
        dropElements(doc, "script", "style", "noscript", "iframe", "form", "header", "footer", "nav", "aside");
        for (String cls : DROP_CLASSES) {
            dropElementsByClass(doc, cls);
        }

        // Tìm khối nội dung chính theo selector hay gặp ở báo Việt Nam.
        Element best = null;
        int bestScore = 0;
        for (String sel : CONTENT_SELECTORS) {
            Elements nodes = doc.select(sel);
            for (Element n : nodes) {
                int score = paragraphTextLength(n);
                if (score > bestScore) {
                    bestScore = score;
                    best = n;
                }
            }
        }

        // Fallback: chấm điểm trên tất cả div/section/article.
        if (best == null || bestScore < 200) {
            Elements candidates = doc.select("div, section, article");
            for (Element n : candidates) {
                int score = paragraphTextLength(n);
                if (score > bestScore) {
                    bestScore = score;
                    best = n;
                }
            }
        }

        if (best == null || bestScore < 200) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        int[] imgCount = {0};
        String[] hero = {null};
        cleanNode(best, pageUrl, sb, imgCount, hero);

        String text = deEntitize(best.text()).trim();
        int textLen = text.length();
        if (textLen < 200) {
            return null;
        }

        return new ExtractedArticle(sb.toString(), textLen, imgCount[0], hero[0]);
    }

    /**
     * Trích xuất tên tác giả từ HTML trang chi tiết.
     * Tìm theo các selector phổ biến của báo Việt Nam, fallback thẻ meta.
     */
    public String extractAuthor(Document doc) {
        String[] selectors = {
            ".author", ".author-name", ".byline", ".writer",
            "[itemprop=author]", ".entry-author", ".article-author",
            ".author-detail", ".post-author", ".tc-author", ".vne-author",
            ".author-info", ".author-wrap", ".article__author", ".detail__author"
        };
        for (String sel : selectors) {
            Element el = doc.selectFirst(sel);
            if (el != null) {
                String text = el.text().trim();
                if (!text.isEmpty()) return text;
            }
        }
        // Fallback: tìm thẻ meta author.
        Element meta = doc.selectFirst("meta[name=author], meta[property=article:author]");
        if (meta != null) {
            String content = meta.attr("content");
            if (content != null && !content.isBlank()) return content.trim();
        }
        return null;
    }

    /* ---------- Noise removal ---------- */

    private static void dropElements(Document doc, String... tags) {
        for (String tag : tags) {
            doc.getElementsByTag(tag).remove();
        }
    }

    private static void dropElementsByClass(Document doc, String cls) {
        // Chọn phần tử có class chứa cls (tương đương XPath contains(concat(' ',normalize-space(@class),' '),' cls ')).
        String query = String.format("[class~=(?:^|\\s)%s(?:\\s|$)]", java.util.regex.Pattern.quote(cls));
        doc.select(query).remove();
    }

    /* ---------- Scoring ---------- */

    private static int paragraphTextLength(Element node) {
        Elements ps = node.select("p, h2, h3, li");
        int sum = 0;
        for (Element p : ps) {
            sum += p.text().length();
        }
        return sum;
    }

    /* ---------- Clean HTML ---------- */

    private static void cleanNode(Element node, URL pageUrl, StringBuilder sb,
                                  int[] imgCount, String[] hero) {
        for (Node child : node.childNodes()) {
            if (child instanceof org.jsoup.nodes.TextNode) {
                String text = deEntitize(((org.jsoup.nodes.TextNode) child).text());
                if (!text.isBlank()) {
                    sb.append(escapeHtml(text));
                }
                continue;
            }
            if (!(child instanceof Element)) {
                continue;
            }
            Element el = (Element) child;
            String tag = el.tagName().toLowerCase();

            if (tag.equals("script") || tag.equals("style") || tag.equals("noscript")
                || tag.equals("iframe") || tag.equals("form")) {
                continue;
            }

            if (!ALLOWED_TAGS.contains(tag)) {
                // Không cho phép → unwrap, giữ nội dung con.
                cleanNode(el, pageUrl, sb, imgCount, hero);
                continue;
            }

            if (tag.equals("img")) {
                String src = el.attr("data-src");
                if (src == null || src.isBlank()) {
                    src = el.attr("src");
                }
                if (src == null || src.isBlank()) {
                    continue;
                }
                URL imgUri = resolveUrl(pageUrl, src);
                if (imgUri == null) {
                    continue;
                }
                String srcLower = imgUri.toString().toLowerCase();
                if (srcLower.contains("avatar") || srcLower.contains("logo")
                    || srcLower.contains("icon") || srcLower.contains("sprite")
                    || srcLower.contains("placeholder")) {
                    continue;
                }
                String alt = escapeHtml(deEntitize(el.attr("alt")));
                sb.append("<img src=\"").append(escapeHtml(imgUri.toString()))
                  .append("\" alt=\"").append(alt).append("\" />");
                imgCount[0]++;
                if (hero[0] == null) {
                    hero[0] = imgUri.toString();
                }
                continue;
            }

            if (tag.equals("a")) {
                String href = el.attr("href");
                URL linkUri = resolveUrl(pageUrl, href == null ? "" : href);
                if (linkUri != null) {
                    sb.append("<a href=\"").append(escapeHtml(linkUri.toString())).append("\">");
                } else {
                    sb.append("<span>");
                }
                cleanNode(el, pageUrl, sb, imgCount, hero);
                if (linkUri != null) {
                    sb.append("</a>");
                } else {
                    sb.append("</span>");
                }
                continue;
            }

            if (tag.equals("ul") || tag.equals("ol") || tag.equals("li") || tag.equals("table")
                || tag.equals("thead") || tag.equals("tbody") || tag.equals("tr")
                || tag.equals("blockquote") || tag.equals("figure") || tag.equals("figcaption")) {
                sb.append('<').append(tag).append('>');
                cleanNode(el, pageUrl, sb, imgCount, hero);
                sb.append("</").append(tag).append('>');
                continue;
            }

            if (tag.equals("td") || tag.equals("th") || tag.equals("caption")) {
                sb.append('<').append(tag).append('>');
                cleanNode(el, pageUrl, sb, imgCount, hero);
                sb.append("</").append(tag).append('>');
                continue;
            }

            if (tag.equals("br")) {
                sb.append("<br/>");
                continue;
            }

            if (tag.equals("hr")) {
                sb.append("<hr/>");
                continue;
            }

            // default: giữ thẻ, duyệt con, đóng thẻ.
            sb.append('<').append(tag).append('>');
            cleanNode(el, pageUrl, sb, imgCount, hero);
            sb.append("</").append(tag).append('>');
        }
    }

    /* ---------- Helpers ---------- */

    private static URL resolveUrl(URL base, String spec) {
        try {
            return new URL(base, spec);
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private static String deEntitize(String s) {
        // Jsoup's text() already decodes entities, but keep for safety.
        return s == null ? "" : s;
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
