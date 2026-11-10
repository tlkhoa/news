package com.tdk.news.repository;

import com.tdk.news.model.NewsSource;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SourceRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<NewsSource> mapper = (rs, rowNum) -> new NewsSource(
        rs.getObject("Id", Integer.class),
        rs.getString("Name"),
        rs.getString("HomeUrl"),
        rs.getString("FeedUrl"),
        rs.getObject("Enabled", Boolean.class),
        rs.getObject("FullTextAllowed", Boolean.class),
        rs.getObject("SourceWeight", Integer.class)
    );

    public SourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<NewsSource> findAll() {
        String sql = "SELECT \"Id\", \"Name\", \"HomeUrl\", \"FeedUrl\", \"Enabled\", \"FullTextAllowed\", \"SourceWeight\" FROM \"Sources\" ORDER BY \"Name\"";
        return jdbc.query(sql, mapper);
    }

    public List<NewsSource> findEnabled() {
        String sql = "SELECT \"Id\", \"Name\", \"HomeUrl\", \"FeedUrl\", \"Enabled\", \"FullTextAllowed\", \"SourceWeight\" FROM \"Sources\" WHERE \"Enabled\" = true ORDER BY \"Name\"";
        return jdbc.query(sql, mapper);
    }

    public Optional<NewsSource> findById(Integer id) {
        String sql = "SELECT \"Id\", \"Name\", \"HomeUrl\", \"FeedUrl\", \"Enabled\", \"FullTextAllowed\", \"SourceWeight\" FROM \"Sources\" WHERE \"Id\" = ?";
        List<NewsSource> list = jdbc.query(sql, mapper, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }
}
