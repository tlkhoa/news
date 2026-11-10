package com.tdk.news.service;

import com.tdk.news.model.NewsArticle;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Chấm điểm phổ biến 0..100 cho bài viết. Port từ C# PopularityScorer.ScoreArticle.
 * Hai thành phần chính: độ mới, lượt đọc công khai, uy tín nguồn, độ đầy đủ metadata.
 */
@Service
public class PopularityScorerService {

    private static final double ArticleFreshnessMax = 35;
    private static final double ArticleViewsMax = 40;
    private static final double ArticleProminenceMax = 15;
    private static final double ArticleCompletenessMax = 10;

    /** Kết quả chấm điểm bài viết. */
    public static class ScoreResult {
        public final double total;
        public final String method;
        public final List<Component> components;

        public ScoreResult(double total, String method, List<Component> components) {
            this.total = total;
            this.method = method;
            this.components = components;
        }
    }

    /** Một thành phần điểm. */
    public static class Component {
        public final String key;
        public final String label;
        public final double value;
        public final double max;

        public Component(String key, String label, double value, double max) {
            this.key = key;
            this.label = label;
            this.value = value;
            this.max = max;
        }
    }

    /**
     * Tính điểm bài viết.
     * Port từ C# PopularityScorer.ScoreArticle.
     *
     * @param article         bài viết (có SourceId, PublishedAt, CollectedAt, ViewCount, Description, ImageUrl, Author)
     * @param sourceWeight    trọng số nguồn (0..15)
     * @param utcNow          thời điểm hiện tại (UTC)
     * @return kết quả điểm bao gồm từng thành phần
     */
    public ScoreResult scoreArticle(NewsArticle article, int sourceWeight, LocalDateTime utcNow) {
        LocalDateTime rawTime = article.getPublishedAt() != null
                ? article.getPublishedAt()
                : article.getCollectedAt();
        if (rawTime == null) rawTime = utcNow;

        long ageHours = java.time.Duration.between(rawTime, utcNow).toHours();
        if (ageHours < 0) ageHours = 0;

        // Freshness: giảm theo hàm mũ, trần 35.
        double freshness = Math.exp(-ageHours / 30.0) * ArticleFreshnessMax;

        // Views: log10, trần 40. Nếu không có lượt đọc công khai → 0.
        double views = 0;
        Long viewCount = article.getViewCount();
        if (viewCount != null && viewCount > 0) {
            views = Math.min(ArticleViewsMax, 4 + Math.log10(viewCount + 1) * 7.4);
        }

        // Prominence: trọng số nguồn, trần 15.
        double prominence = Math.max(0, Math.min(sourceWeight, ArticleProminenceMax));

        // Completeness: mô tả + ảnh + tác giả, trần 10.
        double completeness = 0;
        if (article.getDescription() != null && !article.getDescription().isBlank()) completeness += 4;
        if (article.getImageUrl() != null && !article.getImageUrl().isBlank()) completeness += 3;
        if (article.getAuthor() != null && !article.getAuthor().isBlank()) completeness += 3;

        List<Component> components = new ArrayList<>();
        components.add(new Component("freshness",
                "Độ mới (" + ageHours + "h trước)", round(freshness), ArticleFreshnessMax));
        components.add(new Component("views",
                viewCount != null && viewCount > 0
                        ? "Lượt đọc công khai (" + viewCount + ")"
                        : "Lượt đọc (nguồn không công khai)",
                round(views), ArticleViewsMax));
        components.add(new Component("prominence", "Uy tín nguồn", round(prominence), ArticleProminenceMax));
        components.add(new Component("completeness",
                "Độ đầy đủ metadata (mô tả/ảnh/tác giả)", round(completeness), ArticleCompletenessMax));

        double total = round(components.stream().mapToDouble(c -> c.value).sum());
        String method = viewCount != null && viewCount > 0 ? "Views+Estimated" : "Estimated";

        return new ScoreResult(total, method, components);
    }

    private static double round(double v) {
        return Math.round(Math.max(0, Math.min(v, 100)) * 100.0) / 100.0;
    }
}
