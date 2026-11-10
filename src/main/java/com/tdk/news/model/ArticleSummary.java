package com.tdk.news.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/* Dto dùng hiển thị trên trang danh sách tin (không chứa toàn văn). */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ArticleSummary {
    private Long id;
    private String title;
    private String url;
    private String sourceName;
    private String categoryName;
    private LocalDateTime publishedAt;
    private String imageUrl;
    private Double popularityScore;
    private Long viewCount;
    private Boolean isRead;
}
