package com.tdk.news.model;

import java.time.LocalDate;

/**
 * Thong ke tong hop cua mot ngay (port tu DailyStatistics o C#).
 * CategoryId = NULL la tong chung ca trang; CategoryId co gia tri la thong ke
 * theo tung chuyen muc.
 * (Lop nay khong su dung Lombok de dam bao hoat dong tren tat ca moi truong build.)
 */
public class DailyStatistics {
    private Long id;
    private LocalDate date;
    private Integer categoryId;
    private int articleCount;
    private double averagePopularity;

    public DailyStatistics() {
    }

    public DailyStatistics(Long id, LocalDate date, Integer categoryId, int articleCount,
            double averagePopularity) {
        this.id = id;
        this.date = date;
        this.categoryId = categoryId;
        this.articleCount = articleCount;
        this.averagePopularity = averagePopularity;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public Integer getCategoryId() { return categoryId; }
    public void setCategoryId(Integer categoryId) { this.categoryId = categoryId; }
    public int getArticleCount() { return articleCount; }
    public void setArticleCount(int articleCount) { this.articleCount = articleCount; }
    public double getAveragePopularity() { return averagePopularity; }
    public void setAveragePopularity(double averagePopularity) { this.averagePopularity = averagePopularity; }
}
