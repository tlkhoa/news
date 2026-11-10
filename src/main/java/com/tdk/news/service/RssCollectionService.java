package com.tdk.news.service;

import com.tdk.news.model.NewsArticle;
import com.tdk.news.model.NewsSource;
import com.tdk.news.repository.ArticleRepository;
import com.tdk.news.repository.SourceRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dịch vụ thu thập tin từ RSS của các nguồn đã bật và chèn vào bảng articles.
 * Dùng cho chức năng "Cập nhật tin hôm nay" trên trang admin.
 *
 * Luồng cho mỗi bài mới:
 *   1. Phân loại chuyên mục (CategoryId) bằng NewsClassifierService.
 *   2. Chèn metadata vào "Articles" (kèm CategoryId, PopularityScore tạm = 0).
 *   3. Fetch HTML trang chi tiết, trích xuất toàn văn bằng ArticleContentExtractor.
 *   4. Lưu toàn văn vào cột "Content" + "ContentFetchedAt" nếu trích xuất thành công.
 *   5. Tính điểm phổ biến (PopularityScore) bằng PopularityScorerService.
 *   6. Cập nhật CategoryId, PopularityScore, PopularityMethod vào DB.
 *
 * Commit DB mỗi 10 bài (tương đương SaveChangesAsync trong NewsCollectorService C#).
 */
@Service
public class RssCollectionService {

    private static final int HTTP_TIMEOUT_MS = 6000;
    private static final int MAX_RESPONSE_BYTES = 2_000_000; // 2 MB
    private static final int FETCH_DELAY_MS = 800; // nghỉ giữa các request lấy toàn văn
    private static final int BATCH_COMMIT_SIZE = 10; // commit DB mỗi 10 bài
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private static final DateTimeFormatter RSS_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .optionalStart()
            .appendPattern("EEE, dd MMM yyyy HH:mm:ss Z")
            .optionalStart()
            .appendPattern("dd MMM yyyy HH:mm:ss Z")
            .optionalStart()
            .appendPattern("EEE MMM dd HH:mm:ss z yyyy")
            .optionalStart()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ssX")
            .optionalStart()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter(Locale.ENGLISH);

    private final SourceRepository sourceRepository;
    private final ArticleRepository articleRepository;
    private final ArticleContentExtractor contentExtractor;
    private final NewsClassifierService newsClassifier;
    private final PopularityScorerService popularityScorer;
    private final DocumentBuilder builder;
    private final TransactionTemplate transactionTemplate;

    public RssCollectionService(SourceRepository sourceRepository,
                                ArticleRepository articleRepository,
                                ArticleContentExtractor contentExtractor,
                                NewsClassifierService newsClassifier,
                                PopularityScorerService popularityScorer,
                                PlatformTransactionManager transactionManager) throws Exception {
        this.sourceRepository = sourceRepository;
        this.articleRepository = articleRepository;
        this.contentExtractor = contentExtractor;
        this.newsClassifier = newsClassifier;
        this.popularityScorer = popularityScorer;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        this.builder = factory.newDocumentBuilder();

        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public CollectionResult collectToday() {
        List<SourceResult> results = new ArrayList<>();
        int totalInserted = 0;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Saigon"));

        List<NewsSource> sources = sourceRepository.findEnabled();
        for (NewsSource src : sources) {
            if (src.getFeedUrl() == null || src.getFeedUrl().isBlank()) {
                results.add(new SourceResult(src.getName(), 0, 0, "Không có FeedUrl"));
                continue;
            }
            try {
                int inserted = collectFromSource(src.getId(), src.getName(), src.getFeedUrl(), src.getSourceWeight());
                totalInserted += inserted;
                results.add(new SourceResult(src.getName(), inserted, inserted, "OK"));
            } catch (Exception e) {
                results.add(new SourceResult(src.getName(), 0, 0, e.getMessage()));
            }
        }

        return new CollectionResult(today, totalInserted, results);
    }

    private int collectFromSource(Integer sourceId, String sourceName, String feedUrl, Integer sourceWeight) throws Exception {
        System.out.println("[RSS] Bắt đầu thu thập từ: " + sourceName + " (" + feedUrl + ")");
        Document doc = fetchAndParse(feedUrl);
        NodeList items = doc.getElementsByTagName("item");
        System.out.println("[RSS] " + sourceName + ": tìm thấy " + items.getLength() + " bài");
        int[] inserted = {0};

        // Danh sách thao tác DB đợi commit (batch).
        List<BatchJob> batch = new ArrayList<>(BATCH_COMMIT_SIZE);

        for (int i = 0; i < items.getLength(); i++) {
            Node item = items.item(i);
            String title = getText(item, "title");
            String link = getText(item, "link");
            String desc = getText(item, "description");
            String pubDateStr = getText(item, "pubDate");
            String contentEncoded = getTextNS(item, "http://purl.org/rss/1.0/modules/content/", "encoded");
            String imageUrl = extractImageUrl(item, desc, contentEncoded);
            String author = extractAuthor(item);

            if (link == null || link.isBlank()) {
                System.out.println("[RSS] Bỏ qua bài không có link: " + title);
                continue;
            }

            // Chuẩn hóa nội dung tóm tắt (dùng nếu không lấy được toàn văn).
            String content = (contentEncoded != null && !contentEncoded.isBlank()) ? contentEncoded : desc;

            LocalDateTime publishedAt = parseDate(pubDateStr).toLocalDateTime();

            System.out.println("[RSS] Đang chèn: title=" + title + ", link=" + link + ", publishedAt=" + publishedAt);

            // 1. Phân loại chuyên mục (giống .NET NewsClassifier).
            Integer categoryId = newsClassifier.classify(title, desc);

            // 2. Fetch toàn văn từ trang chi tiết (mạng, nằm ngoài transaction).
            String fullContentHtml = null;
            try {
                Thread.sleep(FETCH_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                String html = fetchHtml(link);
                if (html != null && !html.isBlank()) {
                    URL pageUrl = new URL(link);
                    ArticleContentExtractor.ExtractedArticle extracted = contentExtractor.extract(html, pageUrl);
                    if (extracted != null && extracted.textLength >= 200) {
                        fullContentHtml = extracted.html;
                        System.out.println("[RSS] Trích xuất toàn văn OK: " + link
                            + " (textLength=" + extracted.textLength
                            + ", images=" + extracted.imageCount
                            + ", hero=" + extracted.heroImageUrl + ")");
                    } else {
                        System.out.println("[RSS] Trích xuất toàn văn thất bại hoặc quá ngắn: " + link);
                    }
                }
            } catch (Exception e) {
                System.err.println("[RSS] Lỗi fetch toàn văn cho " + link + " | " + e.getMessage());
            }

            // 3. Chuẩn bị dữ liệu chèn DB.
            final String contentToStore = fullContentHtml != null ? fullContentHtml : content;
            final String contentForClassifier = desc; // classify dùng mô tả RSS
            final Integer catId = categoryId;
            final Integer srcWeight = sourceWeight != null ? sourceWeight : 10;
            final String fTitle = title;
            final String fLink = link;
            final String fDesc = desc;
            final String fImageUrl = imageUrl;
            final String fAuthor = author;
            final LocalDateTime fPublishedAt = publishedAt;
            final String fFullContentHtml = fullContentHtml;

            batch.add(() -> {
                // Chèn bài (CategoryId đã có, PopularityScore tạm = 0).
                int rows = articleRepository.insertIfNotExists(
                    sourceId, catId, fTitle, fLink, fDesc, contentToStore, fImageUrl, fAuthor,
                    fPublishedAt, 0.0, "RSS");
                if (rows > 0) {
                    inserted[0]++;
                    System.out.println("[RSS] ĐÃ LƯU DB: " + fTitle
                        + " (categoryId=" + catId + ")");

                    // Lưu toàn văn nếu có.
                    if (fFullContentHtml != null) {
                        Long articleId = articleRepository.findArticleIdByUrl(fLink);
                        if (articleId != null) {
                            articleRepository.updateContent(articleId, fFullContentHtml, java.time.LocalDateTime.now());
                            System.out.println("[RSS] Đã lưu toàn văn cho id=" + articleId);

                            // Nếu RSS không có tác giả, thử trích từ trang chi tiết.
                            if (fAuthor == null || fAuthor.isBlank()) {
                                try {
                                    String html2 = fetchHtml(fLink);
                                    if (html2 != null && !html2.isBlank()) {
                                        URL pageUrl2 = new URL(fLink);
                                        org.jsoup.nodes.Document doc2 = Jsoup.parse(html2, pageUrl2.toString());
                                        String authorFromPage = contentExtractor.extractAuthor(doc2);
                                        if (authorFromPage != null && !authorFromPage.isBlank()) {
                                            articleRepository.updateAuthor(articleId, authorFromPage);
                                            System.out.println("[RSS] Đã lưu tác giả cho id=" + articleId + ": " + authorFromPage);
                                        }
                                    }
                                } catch (Exception e) {
                                    System.err.println("[RSS] Lỗi lấy tác giả từ trang chi tiết " + fLink + " | " + e.getMessage());
                                }
                            }
                        }
                    }

                    // 4. Tính điểm phổ biến (giống .NET PopularityScorer).
                    NewsArticle tmp = new NewsArticle();
                    tmp.setPublishedAt(fPublishedAt);
                    tmp.setCollectedAt(java.time.LocalDateTime.now());
                    tmp.setViewCount(null); // RSS không có lượt đọc công khai
                    tmp.setDescription(fDesc);
                    tmp.setImageUrl(fImageUrl);
                    tmp.setAuthor(fAuthor);

                    PopularityScorerService.ScoreResult score = popularityScorer.scoreArticle(tmp, srcWeight, java.time.LocalDateTime.now());

                    // Cập nhật PopularityScore, PopularityMethod (CategoryId đã lưu ở trên).
                    Long articleId = articleRepository.findArticleIdByUrl(fLink);
                    if (articleId != null) {
                        articleRepository.updateArticleMetadata(
                            articleId, catId, score.total, score.method);
                        System.out.println("[RSS] Đã cập nhật điểm id=" + articleId
                            + " score=" + score.total + " method=" + score.method);
                    }
                } else {
                    System.out.println("[RSS] BỎ QUA (trùng URL): " + fTitle);
                }
                return null;
            });

            // Commit mỗi BATCH_COMMIT_SIZE bài.
            if (batch.size() >= BATCH_COMMIT_SIZE) {
                commitBatch(batch);
                batch.clear();
            }
        }

        // Commit phần dư (dưới 10 bài).
        if (!batch.isEmpty()) {
            commitBatch(batch);
            batch.clear();
        }

        System.out.println("[RSS] " + sourceName + ": tổng mới chèn = " + inserted[0]);
        return inserted[0];
    }

    /** Commit một batch thao tác DB trong một transaction riêng. */
    private void commitBatch(List<BatchJob> jobs) {
        transactionTemplate.execute(status -> {
            for (BatchJob job : jobs) {
                job.run();
            }
            return null;
        });
        System.out.println("[RSS] Commit batch " + jobs.size() + " bài vào DB.");
    }

    /** Lấy văn bản của phần tử con theo tên thẻ (không namespace). */
    private static String getText(Node parent, String tagName) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tagName.equalsIgnoreCase(n.getNodeName())) {
                return trim(n.getTextContent());
            }
        }
        return null;
    }

    /** Lấy văn bản có namespace (vd: content:encoded). */
    private static String getTextNS(Node parent, String ns, String localName) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && ns.equals(n.getNamespaceURI())
                    && localName.equals(n.getLocalName())) {
                return trim(n.getTextContent());
            }
        }
        return null;
    }

    /** Trích xuất tác giả từ thẻ <author> hoặc <dc:creator> trong RSS item. */
    private static String extractAuthor(Node item) {
        // Thử thẻ <author> chuẩn RSS 2.0
        String author = getText(item, "author");
        if (author != null && !author.isBlank()) return trim(author);
        // Thử thẻ <dc:creator>
        author = getTextNS(item, "http://purl.org/dc/elements/1.1/", "creator");
        if (author != null && !author.isBlank()) return trim(author);
        return null;
    }

    /** Trích xuất URL ảnh từ thẻ <enclosure>, <media:thumbnail> hoặc mô tả. */
    private static String extractImageUrl(Node item, String desc, String contentEncoded) {
        NodeList nl = item.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "enclosure".equalsIgnoreCase(n.getNodeName())) {
                String type = getAttribute(n, "type");
                String url = getAttribute(n, "url");
                if (url != null && (type == null || type.startsWith("image/"))) {
                    return url;
                }
            }
        }
        // Fallback: tìm ảnh đầu tiên trong mô tả/nội dung.
        String html = (contentEncoded != null ? contentEncoded : (desc != null ? desc : ""));
        Matcher m = Pattern.compile("<img[^>]+src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String getAttribute(Node n, String name) {
        if (n.getAttributes() != null) {
            Node attr = n.getAttributes().getNamedItem(name);
            return attr != null ? attr.getNodeValue() : null;
        }
        return null;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private ZonedDateTime parseDate(String s) {
        if (s == null || s.isBlank()) return ZonedDateTime.now(ZoneId.of("Asia/Saigon"));
        try {
            return ZonedDateTime.parse(s, RSS_DATE);
        } catch (Exception ignored) {
            return ZonedDateTime.now(ZoneId.of("Asia/Saigon"));
        }
    }

    private Document fetchAndParse(String feedUrl) throws Exception {
        URL url = new URL(feedUrl);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty("User-Agent", USER_AGENT);
        con.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*");
        con.setConnectTimeout(HTTP_TIMEOUT_MS);
        con.setReadTimeout(HTTP_TIMEOUT_MS);
        con.setInstanceFollowRedirects(true);
        int code = con.getResponseCode();
        try (InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream()) {
            if (in == null) throw new IOException("HTTP " + code + " không có nội dung");
            int maxBytes = 2_000_000; // 2 MB
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(tmp)) != -1) {
                buf.write(tmp, 0, read);
                total += read;
                if (total > maxBytes) throw new IOException("Phản hồi quá lớn (> " + maxBytes + " bytes)");
            }
            return builder.parse(new ByteArrayInputStream(buf.toByteArray()));
        } finally {
            con.disconnect();
        }
    }

    /** Fetch HTML từ URL trang chi tiết (dùng để trích xuất toàn văn). */
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

    /* ---------- Kết quả ---------- */

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CollectionResult {
        private LocalDate date;
        private int totalInserted;
        private List<SourceResult> sources;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SourceResult {
        private String sourceName;
        private int found;
        private int inserted;
        private String note;
    }

    /** Một thao tác DB đợi được commit trong batch. */
    @FunctionalInterface
    private interface BatchJob {
        Void run();
    }
}
