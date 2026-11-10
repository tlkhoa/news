package com.tdk.news.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bản sao chỉ đọc của bảng articles (chỉ lấy cột cần thiết cho trang danh sách & chi tiết).
 * Tên cột phải khớp với schema PostgreSQL thực tế (xem ghi chú ở ArticleRepository).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class NewsArticle {
    private Long id;
    private Integer sourceId;
    private String sourceName;
    private Integer categoryId;
    private String categoryName;
    private Long eventId;
    private String title;
    private String url;
    private String description;
    private String content;
    private String author;
    private String imageUrl;
    private LocalDateTime publishedAt;
    private LocalDateTime collectedAt;
    private Long viewCount;
    private Double popularityScore;
    private Boolean isFullTextStored;
    private LocalDateTime contentFetchedAt;
}
