package com.tdk.news.repository;

import com.tdk.news.model.ArticleSummary;
import com.tdk.news.model.NewsArticle;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Truy vấn bảng Articles (schema PostgreSQL thực tế của ứng dụng .NET).
 *
 * LƯU Ý: EF Core + Npgsql đã tạo bảng/cột dưới dạng tên được trích dẫn (quoted identifiers),
 * preserving chữ hoa: "Articles", "Sources", "Categories", cột "Id", "Title", "SourceId",
 * "PublishedAt", "PopularityScore"... Do đó tất cả câu SQL dưới đây dùng nháy kép ("")
 * và lấy cột theo đúng nhãn chữ hoa/thường như trong SQL.
 *
 * Cách kiểm tra schema thực tế:
 *   SELECT table_name, column_name FROM information_schema.columns
 *   WHERE table_schema='public' ORDER BY table_name, ordinal_position;
 */
@Repository
public class ArticleRepository {

    private final JdbcTemplate jdbc;

    /* Các cột của Articles dùng cho trang chi tiết (bao gồm toàn văn). */
    private static final String ARTICLE_FULL_SELECT =
            "SELECT a.\"Id\", a.\"SourceId\", s.\"Name\" AS sourcename, a.\"CategoryId\", c.\"Name\" AS categoryname,\n" +
            "a.\"EventId\", a.\"Title\", a.\"Url\", a.\"Description\", a.\"Content\", a.\"Author\", a.\"ImageUrl\",\n" +
            "a.\"PublishedAt\", a.\"CollectedAt\", a.\"ViewCount\", a.\"PopularityScore\",\n" +
            "a.\"IsFullTextStored\", a.\"ContentFetchedAt\"\n" +
            "FROM \"Articles\" a\n" +
            "LEFT JOIN \"Sources\" s ON a.\"SourceId\" = s.\"Id\"\n" +
            "LEFT JOIN \"Categories\" c ON a.\"CategoryId\" = c.\"Id\"";

    /* Các cột rút gọn dùng cho danh sách. */
    private static final String ARTICLE_LIST_SELECT =
            "SELECT a.\"Id\", a.\"Title\", a.\"Url\", s.\"Name\" AS sourcename, c.\"Name\" AS categoryname,\n" +
            "a.\"PublishedAt\", a.\"ImageUrl\", a.\"PopularityScore\", a.\"ViewCount\", a.\"IsRead\"\n" +
            "FROM \"Articles\" a\n" +
            "LEFT JOIN \"Sources\" s ON a.\"SourceId\" = s.\"Id\"\n" +
            "LEFT JOIN \"Categories\" c ON a.\"CategoryId\" = c.\"Id\"";

    public ArticleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Lấy danh sách bài viết của một ngày (theo ngày PublishedAt), đã sắp xếp. */
    public List<ArticleSummary> findByDate(LocalDate date) {
        String sql = ARTICLE_LIST_SELECT
                + " WHERE CAST(a.\"PublishedAt\" AS DATE) = ? "
                + " ORDER BY a.\"PopularityScore\" DESC NULLS LAST, a.\"PublishedAt\" DESC";
        return jdbc.query(sql, articleSummaryMapper(), date);
    }

    /** Lấy danh sách bài viết của một ngày và chuyên mục, đã sắp xếp. */
    public List<ArticleSummary> findByDateAndCategory(LocalDate date, Integer categoryId) {
        String sql = ARTICLE_LIST_SELECT
                + " WHERE CAST(a.\"PublishedAt\" AS DATE) = ? AND a.\"CategoryId\" = ? "
                + " ORDER BY a.\"PopularityScore\" DESC NULLS LAST, a.\"PublishedAt\" DESC";
        return jdbc.query(sql, articleSummaryMapper(), date, categoryId);
    }

    /** Đếm số bài của một ngày. */
    public int countByDate(LocalDate date) {
        String sql = "SELECT COUNT(*) FROM \"Articles\" WHERE CAST(\"PublishedAt\" AS DATE) = ?";
        return jdbc.queryForObject(sql, Integer.class, date);
    }

    /** Đếm số bài của một ngày và chuyên mục. */
    public int countByDateAndCategory(LocalDate date, Integer categoryId) {
        String sql = "SELECT COUNT(*) FROM \"Articles\" WHERE CAST(\"PublishedAt\" AS DATE) = ? AND \"CategoryId\" = ?";
        return jdbc.queryForObject(sql, Integer.class, date, categoryId);
    }

    /** Lấy chi tiết 1 bài theo id (bao gồm toàn văn). */
    public Optional<NewsArticle> findById(Long id) {
        String sql = ARTICLE_FULL_SELECT + " WHERE a.\"Id\" = ?";
        List<NewsArticle> list = jdbc.query(sql, articleMapper(), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * Chèn bài mới nếu URL chưa tồn tại (cột "Url" có ràng buộc UNIQUE). Dùng cho chức năng
     * cập nhật tin hôm nay. Trả về số hàng thực sự chèn (0 nếu trùng URL).
     * Cần ràng buộc UNIQUE trên "Articles"."Url" (xem file database/add-unique-url.sql).
     */
    public int insertIfNotExists(Integer sourceId, Integer categoryId, String title, String url,
                                 String description, String content, String imageUrl, String author,
                                 LocalDateTime publishedAt, Double popularityScore, String popularityMethod) {
        String sql =
                "INSERT INTO \"Articles\"\n" +
                "(\"SourceId\", \"CategoryId\", \"Title\", \"NormalizedTitle\", \"Url\", \"Description\", \"Content\", \"ImageUrl\", \"Author\",\n" +
                "\"ContentHash\", \"PublishedAt\", \"CollectedAt\", \"PopularityScore\", \"IsFullTextStored\", \"PopularityMethod\")\n" +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?, false, ?)\n" +
                "ON CONFLICT (\"Url\") DO NOTHING";
        int rows = jdbc.update(sql,
            sourceId, categoryId, title, title, url, description, content, imageUrl, author,
            content == null ? "" : content,
            publishedAt == null ? java.sql.Timestamp.valueOf(LocalDateTime.now())
                                : java.sql.Timestamp.valueOf(publishedAt),
            popularityScore == null ? 0 : popularityScore,
            popularityMethod == null ? "RSS" : popularityMethod);
        System.out.println("[ArticleRepository] insertIfNotExists rows=" + rows + " url=" + url);
        return rows;
    }

    /** Cập nhật chuyên mục và điểm phổ biến cho bài viết sau khi đã tính toán. */
    public int updateArticleMetadata(Long id, Integer categoryId, Double popularityScore, String popularityMethod) {
        String sql = "UPDATE \"Articles\" " +
                "SET \"CategoryId\" = ?, \"PopularityScore\" = ?, \"PopularityMethod\" = ? " +
                "WHERE \"Id\" = ?";
        return jdbc.update(sql, categoryId, popularityScore, popularityMethod, id);
    }

    /** Cập nhật tác giả cho bài viết (dùng khi lấy author từ trang chi tiết sau khi chèn). */
    public int updateAuthor(Long id, String author) {
        if (author == null || author.isBlank()) return 0;
        String sql = "UPDATE \"Articles\" SET \"Author\" = ? WHERE \"Id\" = ?";
        return jdbc.update(sql, author, id);
    }

    /** Đánh dấu bài viết đã đọc (IsRead = true). */
    public int markAsRead(Long id) {
        String sql = "UPDATE \"Articles\" SET \"IsRead\" = true WHERE \"Id\" = ?";
        return jdbc.update(sql, id);
    }

    /**
     * Kiểm tra URL đã tồn tại trong "Articles" chưa (dùng làm lớp phòng khi chưa có UNIQUE constraint).
     */
    public boolean urlExists(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM \"Articles\" WHERE \"Url\" = ?", Integer.class, url);
        return count != null && count > 0;
    }

    /**
     * Lấy Id của bài viết theo URL (dùng sau khi chèn mới để cập nhật toàn văn).
     */
    public Long findArticleIdByUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return jdbc.queryForObject(
                "SELECT \"Id\" FROM \"Articles\" WHERE \"Url\" = ?", Long.class, url);
        } catch (Exception e) {
            return null;
        }
    }

    /** Lưu toàn văn và thời điểm fetch vào bảng Articles. */
    public int updateContent(Long id, String content, LocalDateTime contentFetchedAt) {
        String sql = "UPDATE \"Articles\" " +
                "SET \"Content\" = ?, \"ContentFetchedAt\" = ? " +
                "WHERE \"Id\" = ?";

        return jdbc.update(sql, content, contentFetchedAt, id);
    }

    /** Trung binh diem popularity cua bai trong mot ngay. */
    public Double avgPopularityByDate(LocalDate date) {
        String sql = "SELECT AVG(\"PopularityScore\") FROM \"Articles\" WHERE CAST(\"PublishedAt\" AS DATE) = ?";
        return jdbc.queryForObject(sql, Double.class, date);
    }

    /** Trung binh diem popularity cua bai trong mot ngay va chuyen muc. */
    public Double avgPopularityByDateAndCategory(LocalDate date, Integer categoryId) {
        String sql = "SELECT AVG(\"PopularityScore\") FROM \"Articles\" WHERE CAST(\"PublishedAt\" AS DATE) = ? AND \"CategoryId\" = ?";
        return jdbc.queryForObject(sql, Double.class, date, categoryId);
    }

    /** Lay danh sach CategoryId phan biet cua bai trong mot ngay (loai bo NULL). */
    public List<Integer> findDistinctCategoryIdsByDate(LocalDate date) {
        String sql = "SELECT DISTINCT \"CategoryId\" FROM \"Articles\" WHERE CAST(\"PublishedAt\" AS DATE) = ? AND \"CategoryId\" IS NOT NULL";
        return jdbc.queryForList(sql, Integer.class, date);
    }

    /** Xoa tat ca bai tin cua mot ngay (dung chuc nang xoa tin hom nay). */
    public int deleteByDate(LocalDate date) {
        String sql = "DELETE FROM \"Articles\" WHERE CAST(\"PublishedAt\" AS DATE) = ?";
        return jdbc.update(sql, java.sql.Date.valueOf(date));
    }

    /** Lấy danh sách nguồn đã bật (dùng cho trang admin cập nhật). */
    public List<Object[]> findEnabledSources() {
        String sql = "SELECT \"Id\", \"Name\", \"FeedUrl\" FROM \"Sources\" WHERE \"Enabled\" = true ORDER BY \"Name\"";
        return jdbc.queryForList(sql).stream()
            .map(r -> new Object[]{
                r.get("Id"), r.get("Name"), r.get("FeedUrl")
            })
            .collect(java.util.stream.Collectors.toList());
    }

    /* ---------- RowMapper ---------- */

    private RowMapper<ArticleSummary> articleSummaryMapper() {
        return (rs, rowNum) -> new ArticleSummary(
            rs.getLong("Id"),
            rs.getString("Title"),
            rs.getString("Url"),
            rs.getString("sourcename"),
            rs.getString("categoryname"),
            toLocalDateTime(rs.getTimestamp("PublishedAt")),
            rs.getString("ImageUrl"),
            rs.getObject("PopularityScore", Double.class),
            rs.getObject("ViewCount", Long.class),
            rs.getObject("IsRead", Boolean.class)
        );
    }

    private RowMapper<NewsArticle> articleMapper() {
        return (rs, rowNum) -> new NewsArticle(
            rs.getLong("Id"),
            rs.getObject("SourceId", Integer.class),
            rs.getString("sourcename"),
            rs.getObject("CategoryId", Integer.class),
            rs.getString("categoryname"),
            rs.getObject("EventId", Long.class),
            rs.getString("Title"),
            rs.getString("Url"),
            rs.getString("Description"),
            rs.getString("Content"),
            rs.getString("Author"),
            rs.getString("ImageUrl"),
            toLocalDateTime(rs.getTimestamp("PublishedAt")),
            toLocalDateTime(rs.getTimestamp("CollectedAt")),
            rs.getObject("ViewCount", Long.class),
            rs.getObject("PopularityScore", Double.class),
            rs.getObject("IsFullTextStored", Boolean.class),
            toLocalDateTime(rs.getTimestamp("ContentFetchedAt"))
        );
    }

    private static LocalDateTime toLocalDateTime(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
