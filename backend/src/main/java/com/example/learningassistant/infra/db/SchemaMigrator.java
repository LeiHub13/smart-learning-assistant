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

    /**
     * 增量列清单：表名 / 列名 / ADD COLUMN 的列定义。
     * 注意顺序：AFTER 依赖的列必须先补齐（如 in_hub 依赖 owner_name），否则 ALTER 会因未知列失败。
     */
    private static final List<ColumnSpec> REQUIRED_COLUMNS = List.of(
            new ColumnSpec("t_user", "email", "VARCHAR(100) NULL AFTER nickname"),
            new ColumnSpec("t_user", "avatar", "VARCHAR(500) NULL AFTER email"),
            new ColumnSpec("t_question", "difficulty", "VARCHAR(20) NULL AFTER kp_name"),
            new ColumnSpec("t_course", "owner_id", "BIGINT NULL AFTER description"),
            new ColumnSpec("t_course", "owner_name", "VARCHAR(50) NULL AFTER owner_id"),
            new ColumnSpec("t_course", "in_hub", "TINYINT DEFAULT 0 AFTER owner_name"),
            new ColumnSpec("t_knowledge_mastery", "last_practice_at", "TIMESTAMP NULL AFTER correct_count"));

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
        relaxLegacyColumns();
        if (added > 0) {
            log.info("增量迁移完成，共补齐 {} 列", added);
        }
    }

    /**
     * 历史遗留列兼容：老库保留但系统已废弃的「NOT NULL 且无默认值」列，会让新插入直接失败
     * （如 t_user.role——初始版本的角色列，现系统已无角色概念，DataSeeder/注册插入用户时必崩）。
     * 存在则放宽为可空；仅在该列仍为 NOT NULL 时执行 DDL，幂等且保留原数据。
     */
    private void relaxLegacyColumns() {
        relaxToNullable("t_user", "role", "VARCHAR(20)");
    }

    private void relaxToNullable(String table, String column, String type) {
        if (!tableExists(table) || !columnExists(table, column) || !columnIsNotNull(table, column)) {
            return;
        }
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " MODIFY COLUMN "
                    + column + " " + type + " NULL DEFAULT NULL");
            log.info("增量迁移：{}.{} 已放宽为可空（历史列，系统已不使用）", table, column);
        } catch (Exception e) {
            log.error("增量迁移失败 {}.{}：{}", table, column, e.getMessage());
        }
    }

    private boolean columnIsNotNull(String table, String column) {
        String nullable = jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                String.class, table, column);
        return "NO".equalsIgnoreCase(nullable);
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
