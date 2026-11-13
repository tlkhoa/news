package com.tdk.news.repository;

import com.tdk.news.model.CollectionLog;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Truy van bang CollectionLogs (nhat ky lan thu thuat tin).
 */
@Repository
public class CollectionLogRepository {

    private final JdbcTemplate jdbc;

    public CollectionLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Luu mot ban ghi nhat ky thu thuat (thanh cong hoac that bai). */
    public int insert(Integer sourceId, String sourceName, LocalDateTime startedAt,
            LocalDateTime finishedAt, int itemsFound, int itemsInserted,
            String collectionMethod, String note, String error) {
        String sql =
                "INSERT INTO \"CollectionLogs\"\n" +
                "(\"SourceId\", \"SourceName\", \"StartedAt\", \"FinishedAt\", \"ItemsFound\", \"ItemsInserted\",\n" +
                "\"CollectionMethod\", \"Note\", \"Error\")\n" +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return jdbc.update(sql,
            sourceId, sanitize(sourceName),
            java.sql.Timestamp.valueOf(startedAt),
            java.sql.Timestamp.valueOf(finishedAt),
            itemsFound, itemsInserted,
            sanitize(collectionMethod), sanitize(note), sanitize(error));
    }

    /** Loai bo ky tu null byte va cac ky tu dieu khien khong hop le trong UTF-8 de tranh loi PostgreSQL. */
    public static String sanitize(String s) {
        if (s == null) {
            return null;
        }
        // Xoa ky tu null (U+0000) va cac ky tu dieu khien khac ngoai tab, newline, carriage return.
        return s.replace("\u0000", "")
                .replaceAll("[\\p{Cc}&&[^\\t\\n\\r]]", "");
    }

    /** Lay N ban ghi nhat ky moi nhat (moi nhat dau). */
    public List<CollectionLog> findRecent(int limit) {
        String sql =
                "SELECT \"Id\", \"SourceId\", \"SourceName\", \"StartedAt\", \"FinishedAt\", \"ItemsFound\", \"ItemsInserted\", \"CollectionMethod\", \"Note\", \"Error\"\n" +
                "FROM \"CollectionLogs\"\n" +
                "ORDER BY \"StartedAt\" DESC\n" +
                "LIMIT ?";
        return jdbc.query(sql, collectionLogMapper(), limit);
    }

    /** Lay ban ghi nhat ky co phan trang (offset, limit), moi nhat dau. */
    public List<CollectionLog> findPaged(int offset, int limit) {
        String sql =
                "SELECT \"Id\", \"SourceId\", \"SourceName\", \"StartedAt\", \"FinishedAt\", \"ItemsFound\", \"ItemsInserted\", \"CollectionMethod\", \"Note\", \"Error\"\n" +
                "FROM \"CollectionLogs\"\n" +
                "ORDER BY \"StartedAt\" DESC\n" +
                "LIMIT ? OFFSET ?";
        return jdbc.query(sql, collectionLogMapper(), limit, offset);
    }

    /** Dem tong so ban ghi nhat ky. */
    public int countAll() {
        String sql = "SELECT COUNT(*) FROM \"CollectionLogs\"";
        return jdbc.queryForObject(sql, Integer.class);
    }

    /** Xoa tat ca nhat ky trong mot ngay (dung khi xoa tin hom nay). */
    public int deleteByDate(LocalDate date) {
        String sql = "DELETE FROM \"CollectionLogs\" WHERE CAST(\"StartedAt\" AS DATE) = ?";
        return jdbc.update(sql, java.sql.Date.valueOf(date));
    }

    private RowMapper<CollectionLog> collectionLogMapper() {
        return (rs, rowNum) -> new CollectionLog(
            rs.getLong("Id"),
            rs.getObject("SourceId", Integer.class),
            rs.getString("SourceName"),
            toLocalDateTime(rs.getTimestamp("StartedAt")),
            toLocalDateTime(rs.getTimestamp("FinishedAt")),
            rs.getInt("ItemsFound"),
            rs.getInt("ItemsInserted"),
            rs.getString("CollectionMethod"),
            rs.getString("Note"),
            rs.getString("Error")
        );
    }

    private static LocalDateTime toLocalDateTime(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
