package com.tdk.news.repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CategoryRepository {

    private final JdbcTemplate jdbc;

    public CategoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Lấy map tên chuyên mục → ID. */
    @SuppressWarnings("unchecked")
    public Map<String, Integer> findAllNameToId() {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT \"Id\", \"Name\" FROM \"Categories\" ORDER BY \"Name\"");
        return rows.stream().collect(Collectors.toMap(
            r -> (String) r.get("Name"),
            r -> ((Number) r.get("Id")).intValue()
        ));
    }

    /** Lấy danh sách chuyên mục (id, name) dùng hiển thị dropdown lọc. */
    public List<Object[]> findAllCategories() {
        return jdbc.queryForList(
            "SELECT \"Id\", \"Name\" FROM \"Categories\" ORDER BY \"Name\"")
            .stream().map(r -> new Object[]{
                r.get("Id"), r.get("Name")
            }).collect(Collectors.toList());
    }
}
