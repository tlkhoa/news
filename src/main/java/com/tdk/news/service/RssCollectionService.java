package com.tdk.news.service;

import com.tdk.news.model.NewsArticle;
import com.tdk.news.model.NewsSource;
import com.tdk.news.repository.ArticleRepository;
import com.tdk.news.repository.CollectionLogRepository;
import com.tdk.news.repository.DailyStatisticsRepository;
import com.tdk.news.repository.SourceRepository;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
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
import org.xml.sax.InputSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(RssCollectionService.class);

    private static final int HTTP_TIMEOUT_MS = 6000;
    private static final int MAX_RESPONSE_BYTES = 2_000_000; // 2 MB
    private static final int FETCH_DELAY_MS = 800; // nghỉ giữa các request lấy toàn văn
    private static final int BATCH_COMMIT_SIZE = 100; // commit DB mỗi 100 bài
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
    private final CollectionLogRepository collectionLogRepository;
    private final DailyStatisticsRepository dailyStatisticsRepository;
    private final KeepAliveService keepAliveService;
    // DocumentBuilder KHÔNG an toàn luồng -> mỗi luồng tự giữ một bản sao để cho phép
    // cập nhật nhiều nguồn cùng lúc mà không bị xung đột khi parse RSS/XML.
    private final ThreadLocal<DocumentBuilder> builderThreadLocal;
    private final TransactionTemplate transactionTemplate;

    // DANH SÁCH MODEL ĐÃ XỬ LÝ CHUNG CHO TẤT CẢ CÁC REQUEST (không mỗi request 1 list riêng).
    // Các request chỉ thêm model vào queue này; luồng nền sẽ lấy batch 100 để commit DB.
    private final ConcurrentLinkedQueue<PendingArticle> pendingQueue = new ConcurrentLinkedQueue<>();

    // Luồng nền poll queue mỗi 5 giây để commit batch vào DB.
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /** Một bài viết đã xử lý xong (phân loại, trích xuất toàn văn...) nhưng chưa commit DB. */
    public static class PendingArticle {
        public final Integer sourceId;
        public final Integer categoryId;
        public final String title;
        public final String link;
        public final String description;
        public final String content;
        public final String imageUrl;
        public final String author;
        public final LocalDateTime publishedAt;
        public final String fullContentHtml;
        public final Integer sourceWeight;

        public PendingArticle(Integer sourceId, Integer categoryId, String title, String link,
                              String description, String content, String imageUrl, String author,
                              LocalDateTime publishedAt, String fullContentHtml, Integer sourceWeight) {
            this.sourceId = sourceId;
            this.categoryId = categoryId;
            this.title = title;
            this.link = link;
            this.description = description;
            this.content = content;
            this.imageUrl = imageUrl;
            this.author = author;
            this.publishedAt = publishedAt;
            this.fullContentHtml = fullContentHtml;
            this.sourceWeight = sourceWeight;
        }
    }

    public RssCollectionService(SourceRepository sourceRepository,
                                ArticleRepository articleRepository,
                                ArticleContentExtractor contentExtractor,
                                NewsClassifierService newsClassifier,
                                PopularityScorerService popularityScorer,
                                CollectionLogRepository collectionLogRepository,
                                DailyStatisticsRepository dailyStatisticsRepository,
                                KeepAliveService keepAliveService,
                                PlatformTransactionManager transactionManager) throws Exception {
        this.sourceRepository = sourceRepository;
        this.articleRepository = articleRepository;
        this.contentExtractor = contentExtractor;
        this.newsClassifier = newsClassifier;
        this.popularityScorer = popularityScorer;
        this.collectionLogRepository = collectionLogRepository;
        this.dailyStatisticsRepository = dailyStatisticsRepository;
        this.keepAliveService = keepAliveService;
        this.builderThreadLocal = ThreadLocal.withInitial(() -> {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                return factory.newDocumentBuilder();
            } catch (Exception e) {
                throw new RuntimeException("Không thể tạo DocumentBuilder", e);
            }
        });

        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Khởi động luồng nền poll queue commit batch (chỉ commit khi đủ 100).
        this.scheduler.scheduleAtFixedRate(() -> flushPendingQueue(false), 5, 5, TimeUnit.SECONDS);
    }

    /** Kết quả thu từ một nguồn feed: số bài tìm thấy và số bài chèn mới. */
    private static final class FeedResult {
        final int found;
        final int inserted;

        FeedResult(int found, int inserted) {
            this.found = found;
            this.inserted = inserted;
        }
    }

    public CollectionResult collectToday() {
        List<SourceResult> results = new ArrayList<>();
        int totalInserted = 0;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Saigon"));

        List<NewsSource> sources = sourceRepository.findEnabled();
        for (NewsSource src : sources) {
            if (src.getFeedUrl() == null || src.getFeedUrl().isBlank()) {
                results.add(new SourceResult(src.getName(), 0, 0, "Không có FeedUrl"));
                collectionLogRepository.insert(src.getId(), src.getName(), now(), now(), 0, 0,
                        "RSS", "Không có FeedUrl", null);
                continue;
            }
            LocalDateTime startedAt = now();
            try {
                FeedResult feed = collectFromSource(src.getId(), src.getName(), src.getFeedUrl(), src.getSourceWeight());
                totalInserted += feed.inserted;
                LocalDateTime finishedAt = now();
                results.add(new SourceResult(src.getName(), feed.found, feed.inserted, "OK"));
                collectionLogRepository.insert(src.getId(), src.getName(), startedAt, finishedAt, feed.found, feed.inserted,
                        "RSS", "OK", null);
                saveDailyStatistics(today, src.getId());
            } catch (Exception e) {
                LocalDateTime finishedAt = now();
                results.add(new SourceResult(src.getName(), 0, 0, e.getMessage()));
                collectionLogRepository.insert(src.getId(), src.getName(), startedAt, finishedAt, 0, 0,
                        "RSS", null, CollectionLogRepository.sanitize(e.getMessage()));
                saveDailyStatistics(today, src.getId());
            }
        }

        return new CollectionResult(today, totalInserted, results);
    }

    /**
     * Thu tin từ 1 nguồn (theo Id) và trả về kết quả, dùng cho nút cập nhật từng nguồn
     * ở trang admin. Chạy được song song với nhiều nguồn khác (mỗi gọi dùng transaction
     * mới và DocumentBuilder của luồng chính mình).
     */
    public SourceResult collectSingleSource(Integer sourceId) {
        NewsSource src = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nguồn id=" + sourceId));
        LocalDateTime startedAt = now();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Saigon"));
        if (src.getFeedUrl() == null || src.getFeedUrl().isBlank()) {
            collectionLogRepository.insert(src.getId(), src.getName(), startedAt, now(), 0, 0,
                    "RSS", "Không có FeedUrl", null);
            saveDailyStatistics(today, src.getId());
            flushPendingQueueNow();
            return new SourceResult(src.getName(), 0, 0, "Không có FeedUrl");
        }
        try {
            FeedResult feed = collectFromSource(src.getId(), src.getName(), src.getFeedUrl(), src.getSourceWeight());
            LocalDateTime finishedAt = now();
            collectionLogRepository.insert(src.getId(), src.getName(), startedAt, finishedAt, feed.found, feed.inserted,
                    "RSS", "OK", null);
            saveDailyStatistics(today, src.getId());
            // Xử lý xong hết bài của source này → commit ngay tất cả phần dư trong hàng đợi vào DB.
            flushPendingQueueNow();
            return new SourceResult(src.getName(), feed.found, feed.inserted, "OK");
        } catch (Exception e) {
            LocalDateTime finishedAt = now();
            collectionLogRepository.insert(src.getId(), src.getName(), startedAt, finishedAt, 0, 0,
                    "RSS", null, CollectionLogRepository.sanitize(e.getMessage()));
            saveDailyStatistics(today, src.getId());
            flushPendingQueueNow();
            return new SourceResult(src.getName(), 0, 0, e.getMessage());
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Saigon"));
    }

    /** Tinh toan va luu thong ke ngay cua 1 nguon vao DailyStatistics (tong + tung chuyen muc cua nguon do). */
    private void saveDailyStatistics(LocalDate date, Integer sourceId) {
        int total = articleRepository.countByDateAndSource(date, sourceId);
        Double avg = articleRepository.avgPopularityByDateAndSource(date, sourceId);
        dailyStatisticsRepository.upsert(date, null, sourceId, total, avg == null ? 0.0 : avg);

        List<Integer> categoryIds = articleRepository.findDistinctCategoryIdsByDate(date);
        for (Integer catId : categoryIds) {
            int cnt = articleRepository.countByDateAndCategory(date, catId);
            // Gioi han chi tinh theo chuyen muc cua nguon hien tai neu muon chinh xac hon;
            // o day lay tat ca chuyen muc cua ngay (de du lieu thong ke duoc du phong).
            Double a = articleRepository.avgPopularityByDateAndCategory(date, catId);
            dailyStatisticsRepository.upsert(date, catId, sourceId, cnt, a == null ? 0.0 : a);
        }
    }

    private FeedResult collectFromSource(Integer sourceId, String sourceName, String feedUrl, Integer sourceWeight) throws Exception {
//            if (!sourceName.equals("Nhân Dân"))
//            {
//                return 0;
//            }
            log.info("[RSS] Bắt đầu thu thập từ: " + sourceName + " (" + feedUrl + ")");
            Document doc = fetchAndParse(feedUrl);
            NodeList items = doc.getElementsByTagName("item");
            int itemCount = items.getLength();
            log.info("[RSS] " + sourceName + ": tìm thấy " + itemCount + " bài trong feed");
            if (itemCount == 0) {
                log.info("[RSS] " + sourceName + ": feed trống hoặc không có thẻ <item>, không có bài nào để chèn.");
                return new FeedResult(itemCount, 0);
            }
            int[] inserted = {0};
            int[] skippedDuplicate = {0};
            int[] skippedNoLink = {0};

            // Thêm model đã xử lý vào pendingQueue chung (tất cả request chia sẻ 1 list).

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
                    log.info("[RSS] Bỏ qua bài không có link: " + title);
                    skippedNoLink[0]++;
                    continue;
                }

                // Chuẩn hóa nội dung tóm tắt (dùng nếu không lấy được toàn văn).
                String content = (contentEncoded != null && !contentEncoded.isBlank()) ? contentEncoded : desc;

                LocalDateTime publishedAt = parseDate(pubDateStr).toLocalDateTime();

                log.info("[RSS] Đang xử lý: title=" + title + ", link=" + link + ", publishedAt=" + publishedAt);

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
                            log.info("[RSS] Trích xuất toàn văn OK: " + link
                                    + " (textLength=" + extracted.textLength
                                    + ", images=" + extracted.imageCount
                                    + ", hero=" + extracted.heroImageUrl + ")");
                        } else {
                            log.info("[RSS] Trích xuất toàn văn thất bại hoặc quá ngắn: " + link);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[RSS] Lỗi fetch toàn văn cho " + link + " | " + e.getMessage());
                }

                // 3. Chuẩn bị dữ liệu chèn DB (gói vào BatchJob, chưa commit).
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

                pendingQueue.offer(new PendingArticle(sourceId, catId, fTitle, fLink, fDesc, contentToStore, fImageUrl, fAuthor, fPublishedAt, fFullContentHtml, srcWeight));
            }

            log.info("[RSS] " + sourceName + ": đã thêm vào hàng đợi chung (pendingQueue), tổng bài xử lý = " + inserted[0]);
            return new FeedResult(itemCount, inserted[0]);
    }

    /**
     * Lấy model từ hàng đợi chung và commit vào DB.
     *  - force=false (luồng nền): chỉ commit nếu hàng đợi đã đủ 100 bài.
     *  - force=true (flushPendingQueueNow): commit tất cả phần dư dù chưa đủ 100.
     * Nếu poll ra chưa đủ 100 và không force → trả lại toàn bộ vào hàng đợi, không commit.
     */
    private void flushPendingQueue(boolean force) {
        List<PendingArticle> batch = new ArrayList<>(BATCH_COMMIT_SIZE);
        PendingArticle item;
        int maxTake = force ? Integer.MAX_VALUE : BATCH_COMMIT_SIZE;
        while ((item = pendingQueue.poll()) != null && batch.size() < maxTake) {
            batch.add(item);
        }
        if (batch.isEmpty()) {
            return;
        }
        // Không force và chưa đủ 100 → trả lại toàn bộ, không commit.
        if (!force && batch.size() < BATCH_COMMIT_SIZE) {
            pendingQueue.addAll(batch);
            return;
        }
        try {
            commitBatch(batch);
        } catch (Exception e) {
            // Commit thất bại (DB shutdown, lỗi kết nối...): trả lại toàn bộ batch về hàng đợi
            // để lần sau (luồng nền hoặc flushPendingQueueNow) tiếp tục thử.
            log.warn("[RSS] Commit batch thất bại, trả lại " + batch.size()
                    + " bài vào hàng đợi để thử lại sau: " + e.getMessage());
            pendingQueue.addAll(batch);
        }
    }

    /** Commit ngay tất cả phần dư trong hàng đợi (dù chưa đủ 100) khi hết request active. */
    public void flushPendingQueueNow() {
        flushPendingQueue(true);
    }

    /** Commit một batch PendingArticle vào DB trong một transaction riêng. Thử lại nếu lỗi kết nối. */
    private void commitBatch(List<PendingArticle> jobs) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                transactionTemplate.execute(status -> {
                    for (PendingArticle p : jobs) {
                        insertPendingArticle(p);
                    }
                    return null;
                });
                log.info("[RSS] Commit batch " + jobs.size() + " bài vào DB.");
                return;
            } catch (Exception e) {
                String msg = (e.getMessage() == null ? "" : e.getMessage());
                Throwable cause = e.getCause();
                while (cause != null) {
                    msg += " | " + (cause.getMessage() == null ? "" : cause.getMessage());
                    cause = cause.getCause();
                }
                boolean isConnectionError = msg.contains("08006") || msg.contains("08001") || msg.contains("08003")
                    || msg.contains("Socket closed") || msg.contains("Connection") || msg.contains("connection")
                    || msg.contains("rollback") || msg.contains("Rollback");
                if (attempt < maxAttempts && isConnectionError) {
                    log.warn("[RSS] Lỗi kết nối DB khi commit batch (lần " + attempt + "/" + maxAttempts + "), thử lại sau 1s: " + e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    continue;
                }
                log.error("[RSS] Commit batch thất bại sau " + maxAttempts + " lần, ném lỗi để trả lại hàng đợi: " + e.getMessage());
                throw e;
            }
        }
    }

    /** Chèn 1 PendingArticle vào DB (giống nội dung cũ trong BatchJob). */
    private void insertPendingArticle(PendingArticle p) {
        int rows = articleRepository.insertIfNotExists(
                p.sourceId, p.categoryId, p.title, p.link, p.description, p.content, p.imageUrl, p.author,
                p.publishedAt, 0.0, "RSS");
        if (rows > 0) {
            log.info("[RSS] ĐÃ LƯU DB: " + p.title + " (categoryId=" + p.categoryId + ")");

            if (p.fullContentHtml != null) {
                Long articleId = articleRepository.findArticleIdByUrl(p.link);
                if (articleId != null) {
                    articleRepository.updateContent(articleId, p.fullContentHtml, java.time.LocalDateTime.now());
                    log.info("[RSS] Đã lưu toàn văn cho id=" + articleId);

                    if (p.author == null || p.author.isBlank()) {
                        try {
                            String html2 = fetchHtml(p.link);
                            if (html2 != null && !html2.isBlank()) {
                                URL pageUrl2 = new URL(p.link);
                                org.jsoup.nodes.Document doc2 = Jsoup.parse(html2, pageUrl2.toString());
                                String authorFromPage = contentExtractor.extractAuthor(doc2);
                                if (authorFromPage != null && !authorFromPage.isBlank()) {
                                    articleRepository.updateAuthor(articleId, authorFromPage);
                                    log.info("[RSS] Đã lưu tác giả cho id=" + articleId + ": " + authorFromPage);
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("[RSS] Lỗi lấy tác giả từ trang chi tiết " + p.link + " | " + e.getMessage());
                        }
                    }
                }
            }

            NewsArticle tmp = new NewsArticle();
            tmp.setPublishedAt(p.publishedAt);
            tmp.setCollectedAt(java.time.LocalDateTime.now());
            tmp.setViewCount(null);
            tmp.setDescription(p.description);
            tmp.setImageUrl(p.imageUrl);
            tmp.setAuthor(p.author);

            PopularityScorerService.ScoreResult score = popularityScorer.scoreArticle(tmp, p.sourceWeight, java.time.LocalDateTime.now());

            Long articleId = articleRepository.findArticleIdByUrl(p.link);
            if (articleId != null) {
                articleRepository.updateArticleMetadata(
                        articleId, p.categoryId, score.total, score.method);
                log.info("[RSS] Đã cập nhật điểm id=" + articleId
                        + " score=" + score.total + " method=" + score.method);
            }
        } else {
            log.info("[RSS] BỎ QUA (trùng URL): " + p.title);
        }
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




    public Document fetchAndParse(String feedUrl) throws Exception {
        HttpURLConnection con = null;

        try {
            URL url = new URL(feedUrl);
            con = (HttpURLConnection) url.openConnection();

            con.setRequestMethod("GET");
            con.setConnectTimeout(180000);
            con.setReadTimeout(60000);
            con.setInstanceFollowRedirects(true);

            con.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36"
            );

            con.setRequestProperty(
                    "Accept",
                    "application/rss+xml, application/xml, text/xml, */*"
            );

            // Cho phép server trả dữ liệu không nén hoặc nén
            con.setRequestProperty(
                    "Accept-Encoding",
                    "gzip, deflate, identity"
            );

            int status = con.getResponseCode();

            if (status < 200 || status >= 300) {
                throw new IOException("HTTP error: " + status);
            }

            String contentEncoding = con.getHeaderField("Content-Encoding");
            String contentType = con.getContentType();

            log.info("[RSS] URL: " + feedUrl);
            log.info("[RSS] HTTP: " + status);
            log.info("[RSS] Content-Type: " + contentType);
            log.info("[RSS] Content-Encoding: " + contentEncoding);

            InputStream rawInput = con.getInputStream();

            // Giải nén dữ liệu theo Content-Encoding
            InputStream decodedInput = rawInput;

            if (contentEncoding != null) {
                String encoding = contentEncoding.toLowerCase(Locale.ROOT);

                // Hỗ trợ nhiều Content-Encoding theo thứ tự giải mã ngược
                String[] encodings = encoding.split(",");

                for (int i = encodings.length - 1; i >= 0; i--) {
                    String item = encodings[i].trim();

                    if (item.isEmpty() || "identity".equals(item)) {
                        continue;
                    }

                    if ("gzip".equals(item) || "x-gzip".equals(item)) {
                        decodedInput = new GZIPInputStream(decodedInput);

                    } else if ("deflate".equals(item)) {
                        decodedInput = new InflaterInputStream(decodedInput);

                    } else {
                        throw new IOException(
                                "Unsupported Content-Encoding: " + item
                        );
                    }
                }
            }

            // Giới hạn 2 MB sau giải nén
            final int MAX_BYTES = 2 * 1024 * 1024;

            InputStream limitedInput = new FilterInputStream(decodedInput) {
                private int total = 0;

                @Override
                public int read() throws IOException {
                    int value = super.read();

                    if (value != -1) {
                        total++;

                        if (total > MAX_BYTES) {
                            throw new IOException("RSS exceeds 2 MB");
                        }
                    }

                    return value;
                }

                @Override
                public int read(byte[] b, int off, int len)
                        throws IOException {

                    if (len == 0) {
                        return 0;
                    }

                    int remaining = MAX_BYTES - total;

                    int n = super.read(
                            b,
                            off,
                            Math.min(len, remaining + 1)
                    );

                    if (n == -1) {
                        return -1;
                    }

                    total += n;

                    if (total > MAX_BYTES) {
                        throw new IOException("RSS exceeds 2 MB");
                    }

                    return n;
                }
            };

            // Đọc BOM
            PushbackInputStream bomStream =
                    new PushbackInputStream(limitedInput, 4);

            byte[] prefix = new byte[4];
            int count = 0;

            while (count < prefix.length) {
                int n = bomStream.read(
                        prefix,
                        count,
                        prefix.length - count
                );

                if (n == -1) {
                    break;
                }

                count += n;
            }

            String charset = "UTF-8";
            int skip = 0;

            // UTF-8 BOM
            if (count >= 3
                    && (prefix[0] & 0xFF) == 0xEF
                    && (prefix[1] & 0xFF) == 0xBB
                    && (prefix[2] & 0xFF) == 0xBF) {

                charset = "UTF-8";
                skip = 3;

                // UTF-16 BE BOM
            } else if (count >= 2
                    && (prefix[0] & 0xFF) == 0xFE
                    && (prefix[1] & 0xFF) == 0xFF) {

                charset = "UTF-16BE";
                skip = 2;

                // UTF-16 LE BOM
            } else if (count >= 2
                    && (prefix[0] & 0xFF) == 0xFF
                    && (prefix[1] & 0xFF) == 0xFE) {

                charset = "UTF-16LE";
                skip = 2;
            }

            // Trả lại những byte chưa xử lý
            if (count > skip) {
                bomStream.unread(prefix, skip, count - skip);
            }

            // Nếu không có BOM, lấy charset từ HTTP Content-Type
            if (skip == 0 && contentType != null) {
                java.util.regex.Matcher matcher =
                        java.util.regex.Pattern.compile(
                                "charset\\s*=\\s*[\"']?([^;\"'\\s]+)",
                                java.util.regex.Pattern.CASE_INSENSITIVE
                        ).matcher(contentType);

                if (matcher.find()) {
                    try {
                        charset = matcher.group(1);
                        Charset.forName(charset);
                    } catch (Exception e) {
                        charset = "UTF-8";
                    }
                }
            }

            log.info("[RSS] Charset: " + charset);

            // Đọc trực tiếp InputStream
            StringBuilder content = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            bomStream,
                            Charset.forName(charset)
                    ))) {

                char[] buffer = new char[4096];
                int n;

                while ((n = reader.read(buffer)) != -1) {
                    content.append(buffer, 0, n);
                }
            }

            String xmlContent = content.toString().trim();

            // Xóa BOM nếu còn tồn tại
            if (!xmlContent.isEmpty()
                    && xmlContent.charAt(0) == '\uFEFF') {
                xmlContent = xmlContent.substring(1).trim();
            }

            if (xmlContent.isEmpty()) {
                throw new IOException("RSS response is empty");
            }

            // Kiểm tra HTML
            String lower = xmlContent.toLowerCase(Locale.ROOT);

            if (lower.startsWith("<!doctype html")
                    || lower.startsWith("<html")
                    || lower.contains("<html")) {

                throw new IOException(
                        "Server returned HTML instead of RSS. Content: "
                                + xmlContent.substring(
                                0,
                                Math.min(300, xmlContent.length())
                        )
                );
            }

            // Tìm thẻ RSS hoặc Atom
            int rssIndex = indexOfTag(xmlContent, "<rss");
            int feedIndex = indexOfTag(xmlContent, "<feed");

            int rootIndex = -1;

            if (rssIndex >= 0 && feedIndex >= 0) {
                rootIndex = Math.min(rssIndex, feedIndex);
            } else if (rssIndex >= 0) {
                rootIndex = rssIndex;
            } else if (feedIndex >= 0) {
                rootIndex = feedIndex;
            }

            if (rootIndex < 0) {
                throw new IOException(
                        "Không tìm thấy thẻ <rss> hoặc <feed>. Bắt đầu: '"
                                + xmlContent.substring(
                                0,
                                Math.min(300, xmlContent.length())
                        )
                );
            }

            // Loại bỏ nội dung trước root XML
            xmlContent = xmlContent.substring(rootIndex);

            // Sửa các thẻ chưa đóng
            xmlContent = fixUnclosedTags(xmlContent);

            // Parse XML
            InputSource source = new InputSource(
                    new StringReader(xmlContent)
            );

            return builderThreadLocal.get().parse(source);

        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }



    /** Trích xuất charset từ header Content-Type (ví dụ: "application/rss+xml; charset=Windows-1258"). */
//    private static String extractCharset(String contentType) {
//        if (contentType == null) {
//            return null;
//        }
//        String[] parts = contentType.split(";");
//        for (int i = 1; i < parts.length; i++) {
//            String part = parts[i].trim();
//            if (part.startsWith("charset=")) {
//                return part.substring("charset=".length()).trim().replace("\"", "");
//            }
//        }
//        return null;
//    }

    private String extractCharset(String contentType) {
        if (contentType == null) {
            return null;
        }

        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(
                        "charset\\s*=\\s*[\"']?([^;\"'\\s]+)",
                        java.util.regex.Pattern.CASE_INSENSITIVE
                ).matcher(contentType);

        return matcher.find() ? matcher.group(1).trim() : null;
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

    /** Tìm vị trí đầu tiên của thẻ tag (không phân biệt hoa thường). Trả về -1 nếu không tìm thấy. */
    private static int indexOfTag(String content, String tag) {
        if (content == null) return -1;
        String lower = content.toLowerCase(Locale.ROOT);
        String tagLower = tag.toLowerCase(Locale.ROOT);
        return lower.indexOf(tagLower);
    }

    /**
     * Sửa một số thẻ HTML thường gặp bị thiếu đóng trong RSS (ví dụ <img ...> không có />)
     * để tránh lỗi "The element type img must be terminated by the matching end-tag".
     * Chuyển <img ...> thành <img ... /> khi chưa có dấu đóng.
     */
    private static String fixUnclosedTags(String xml) {
        if (xml == null) return xml;
        // Tìm các thẻ <img ...> chưa có /> ở cuối và chưa có </img>
        return xml.replaceAll(
            "(?i)<img\\b([^>]*?)(?<!/)>",
            "<img$1 />"
        );
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
