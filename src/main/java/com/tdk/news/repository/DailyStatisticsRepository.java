package com.tdk.news.repository;

import com.tdk.news.model.DailyStatistics;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Truy van bang DailyStatistics (thong ke tong hop theo ngay).
 * (Date, CategoryId, SourceId) co rang buoc UNIQUE de ho tro upsert.
 */
@Repository
public class DailyStatisticsRepository {

    private final JdbcTemplate jdbc;

    public DailyStatisticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Chen moi hoac cap nhat thong ke mot ngay / chuyen muc / nguon. */
    public void upsert(LocalDate date, Integer categoryId, Integer sourceId, int articleCount,
            double averagePopularity) {
        // Postgres UNIQUE voi cot NULL co the khong conflict theo cach mong muon (tru khi NULLS NOT DISTINCT),
        // nen dung UPDATE theo IS NOT DISTINCT FROM + INSERT neu chua co de dam bao dung.
        String updateSql =
                "UPDATE \"DailyStatistics\"\n" +
                "SET \"ArticleCount\" = ?, \"AveragePopularity\" = ?\n" +
                "WHERE \"Date\" = ?\n" +
                "  AND \"CategoryId\" IS NOT DISTINCT FROM ?\n" +
                "  AND \"SourceId\" IS NOT DISTINCT FROM ?";
        int updated = jdbc.update(updateSql, articleCount, averagePopularity,
                java.sql.Date.valueOf(date), categoryId, sourceId);
        if (updated == 0) {
            String insertSql =
                    "INSERT INTO \"DailyStatistics\" (\"Date\", \"CategoryId\", \"SourceId\", \"ArticleCount\", \"AveragePopularity\")\n" +
                    "VALUES (?, ?, ?, ?, ?)";
            jdbc.update(insertSql, java.sql.Date.valueOf(date), categoryId, sourceId, articleCount, averagePopularity);
        }
    }

    /** Xoa thong ke cua mot ngay theo nguon (de ghi lai tap hop moi, tranh ban ghi trung lap). */
    public int deleteByDateAndSource(LocalDate date, Integer sourceId) {
        String sql = "DELETE FROM \"DailyStatistics\" WHERE \"Date\" = ? AND \"SourceId\" IS NOT DISTINCT FROM ?";
        return jdbc.update(sql, java.sql.Date.valueOf(date), sourceId);
    }

    /** Lay tat ca thong ke cua mot ngay (bao gom hang tong chung CategoryId = NULL, SourceId = NULL). */
    public List<DailyStatistics> findByDate(LocalDate date) {
        String sql =
                "SELECT \"Id\", \"Date\", \"CategoryId\", \"SourceId\", \"ArticleCount\", \"AveragePopularity\"\n" +
                "FROM \"DailyStatistics\"\n" +
                "WHERE \"Date\" = ?\n" +
                "ORDER BY \"CategoryId\" NULLS FIRST, \"SourceId\" NULLS FIRST";
        return jdbc.query(sql, dailyStatisticsMapper(), java.sql.Date.valueOf(date));
    }

    /** Lay thong ke mot ngay, mot chuyen muc, mot nguon. */
    public Optional<DailyStatistics> findByDateAndCategoryAndSource(LocalDate date, Integer categoryId,
            Integer sourceId) {
        String sql =
                "SELECT \"Id\", \"Date\", \"CategoryId\", \"SourceId\", \"ArticleCount\", \"AveragePopularity\"\n" +
                "FROM \"DailyStatistics\"\n" +
                "WHERE \"Date\" = ? AND \"CategoryId\" = ? AND \"SourceId\" = ?";
        List<DailyStatistics> list = jdbc.query(sql, dailyStatisticsMapper(),
            java.sql.Date.valueOf(date), categoryId, sourceId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** Xoa thong ke cua mot ngay (dung khi xoa tin hom nay). */
    public int deleteByDate(LocalDate date) {
        String sql = "DELETE FROM \"DailyStatistics\" WHERE \"Date\" = ?";
        return jdbc.update(sql, java.sql.Date.valueOf(date));
    }

    private RowMapper<DailyStatistics> dailyStatisticsMapper() {
        return (rs, rowNum) -> new DailyStatistics(
            rs.getLong("Id"),
            toLocalDate(rs.getDate("Date")),
            rs.getObject("CategoryId", Integer.class),
            rs.getObject("SourceId", Integer.class),
            rs.getInt("ArticleCount"),
            rs.getDouble("AveragePopularity")
        );
    }

    private static LocalDate toLocalDate(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
