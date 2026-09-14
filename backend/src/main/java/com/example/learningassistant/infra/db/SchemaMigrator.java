package com.example.learningassistant.infra.db;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动增量迁移：历史上后续版本新增的列，在已存在的老库上补齐（幂等，先查 information_schema 再 ALTER）。
 * schema.sql 用 CREATE TABLE IF NOT EXISTS，对已存在的表不会补列——老库直接升级会因缺列启动失败，
 * 故此处兜底；新增列时在本清单追加一条即可（DataSeeder 之前执行）。
 */
@Slf4j
@Order(0)
@Component
@RequiredArgsConstructor
public class SchemaMigrator implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    /** 增量列清单：表名 / 列名 / ADD COLUMN 的列定义 */
    private static final List<ColumnSpec> REQUIRED_COLUMNS = List.of(
            new ColumnSpec("t_user", "email", "VARCHAR(100) NULL AFTER nickname"),
            new ColumnSpec("t_user", "avatar", "VARCHAR(500) NULL AFTER email"),
            new ColumnSpec("t_question", "difficulty", "VARCHAR(20) NULL AFTER kp_name"),
            new ColumnSpec("t_course", "in_hub", "TINYINT DEFAULT 0 AFTER owner_name"));

    @Override
    public void run(String... args) {
        int added = 0;
        for (ColumnSpec spec : REQUIRED_COLUMNS) {
            if (!tableExists(spec.table()) || columnExists(spec.table(), spec.column())) {
                continue;
            }
            try {
                jdbcTemplate.execute("ALTER TABLE " + spec.table() + " ADD COLUMN "
                        + spec.column() + " " + spec.definition());
                log.info("增量迁移：{}.{} 已补齐", spec.table(), spec.column());
                added++;
            } catch (Exception e) {
                log.error("增量迁移失败 {}.{}：{}", spec.table(), spec.column(), e.getMessage());
            }
        }
        if (added > 0) {
            log.info("增量迁移完成，共补齐 {} 列", added);
        }
    }

    private boolean tableExists(String table) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Integer.class, table);
        return n != null && n > 0;
    }

    private boolean columnExists(String table, String column) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        return n != null && n > 0;
    }

    private record ColumnSpec(String table, String column, String definition) {
    }
}
