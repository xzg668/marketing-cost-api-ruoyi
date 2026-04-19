package com.sanhua.marketingcost.db;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V7__data_migration.sql 数据迁移脚本静态校验（T32）。
 * <p>
 * 本地无 MySQL/H2 环境，采用正则文本校验验证：
 * <ul>
 *   <li>admin 字段补齐（nick_name 回填 / business_unit_type 设为 NULL）</li>
 *   <li>测试账号 commercial_staff / household_staff 创建，business_unit_type 正确</li>
 *   <li>用户-角色绑定到 BU_STAFF (role_id=11)</li>
 *   <li>幂等保障：UPDATE 带 WHERE；INSERT 使用 INSERT IGNORE；DELETE 带具体条件</li>
 * </ul>
 */
class V7DataMigrationSqlTest {

    private static String sql;

    @BeforeAll
    static void loadSql() throws Exception {
        Path p = Paths.get("src/main/resources/db/V7__data_migration.sql");
        if (!Files.exists(p)) {
            // 兼容 IDE 从仓库根目录运行的情况
            p = Paths.get("marketing-cost-biz/src/main/resources/db/V7__data_migration.sql");
        }
        assertTrue(Files.exists(p), "V7 SQL 文件不存在: " + p.toAbsolutePath());
        sql = Files.readString(p);
    }

    // ========== admin 字段迁移 ==========

    @Test
    @DisplayName("admin 用户补齐 nick_name —— 通过 UPDATE WHERE user_id=1")
    void adminNickNameBackfilled() {
        assertTrue(sql.contains("UPDATE sys_user"), "应包含 admin UPDATE 语句");
        assertTrue(sql.contains("WHERE user_id = 1"), "必须指定 user_id=1，避免误改其他用户");
        assertTrue(sql.contains("'系统管理员'"), "nick_name 默认应为'系统管理员'");
    }

    @Test
    @DisplayName("admin 的 business_unit_type 显式置 NULL —— 登录时动态选择业务单元")
    void adminBusinessUnitTypeNull() {
        assertTrue(sql.matches("(?s).*business_unit_type\\s*=\\s*NULL.*"),
                "admin 的 business_unit_type 应显式置 NULL");
    }

    @Test
    @DisplayName("admin UPDATE 幂等 —— nick_name 已赋值则不覆盖")
    void adminUpdateIdempotent() {
        // 通过 IFNULL(NULLIF(..,''), '系统管理员') 实现：原值非空则保持，空串或 NULL 才回填
        assertTrue(sql.contains("IFNULL(NULLIF(nick_name, ''), '系统管理员')"),
                "nick_name 应通过 IFNULL+NULLIF 保证幂等，不覆盖已有值");
    }

    // ========== 测试账号创建 ==========

    @Test
    @DisplayName("创建商用 BU_STAFF 测试账号 commercial_staff")
    void commercialStaffCreated() {
        assertTrue(sql.contains("'commercial_staff'"), "应创建 commercial_staff 用户");
        // user_id=10, COMMERCIAL
        Pattern p = Pattern.compile(
                "\\(\\s*10\\s*,\\s*'commercial_staff'[\\s\\S]*?'COMMERCIAL'[\\s\\S]*?\\)");
        Matcher m = p.matcher(sql);
        assertTrue(m.find(), "commercial_staff 应为 user_id=10、business_unit_type=COMMERCIAL");
    }

    @Test
    @DisplayName("创建家用 BU_STAFF 测试账号 household_staff")
    void householdStaffCreated() {
        assertTrue(sql.contains("'household_staff'"), "应创建 household_staff 用户");
        Pattern p = Pattern.compile(
                "\\(\\s*11\\s*,\\s*'household_staff'[\\s\\S]*?'HOUSEHOLD'[\\s\\S]*?\\)");
        Matcher m = p.matcher(sql);
        assertTrue(m.find(), "household_staff 应为 user_id=11、business_unit_type=HOUSEHOLD");
    }

    @Test
    @DisplayName("测试账号密码使用 bcrypt hash（非明文）")
    void testUsersPasswordBcrypt() {
        // 校验密码字段形如 $2a$10$... 的 bcrypt hash
        assertTrue(sql.contains("$2a$10$"),
                "测试账号密码必须为 bcrypt 哈希，绝不可为明文");
    }

    @Test
    @DisplayName("测试账号绑定到 bu_staff 角色 (role_id=11)")
    void testUsersBoundToBuStaffRole() {
        assertTrue(sql.contains("INSERT IGNORE INTO sys_user_role"),
                "应有 sys_user_role 插入语句");
        // 允许 (10, 11) 与 (11, 11) 以列表或多行形式出现
        assertTrue(sql.matches("(?s).*\\(\\s*10\\s*,\\s*11\\s*\\).*"),
                "commercial_staff (user_id=10) 应绑定 role_id=11");
        assertTrue(sql.matches("(?s).*\\(\\s*11\\s*,\\s*11\\s*\\).*"),
                "household_staff (user_id=11) 应绑定 role_id=11");
    }

    // ========== 幂等性 ==========

    @Test
    @DisplayName("所有用户插入使用 INSERT IGNORE —— 重复执行不报错")
    void userInsertIsIgnoreIdempotent() {
        // sys_user 的插入必须用 INSERT IGNORE
        Pattern p = Pattern.compile("(?s)INSERT\\s+INTO\\s+sys_user\\s*\\(");
        assertFalse(p.matcher(sql).find(),
                "sys_user 禁止使用裸 INSERT INTO，必须用 INSERT IGNORE INTO 保证幂等");
        assertTrue(sql.contains("INSERT IGNORE INTO sys_user"),
                "sys_user 必须使用 INSERT IGNORE");
    }

    @Test
    @DisplayName("清理旧角色关联使用具体 role_id 条件 —— 不会误删新角色")
    void deleteUsesExplicitRoleIds() {
        // 旧 v1.1 角色 id 2/3/4/5；新 admin 继续使用 role_id=1，bu_director=10, bu_staff=11, oa_collaborator=12
        assertTrue(sql.contains("DELETE FROM sys_user_role"),
                "应清理 admin 与旧角色的关联");
        assertTrue(sql.contains("role_id IN (2, 3, 4, 5)"),
                "DELETE 必须明确限定旧 role_id (2,3,4,5)，不可误删新角色");
        assertTrue(sql.contains("user_id = 1"),
                "DELETE 必须限定 user_id=1，不可跨用户批量删");
    }

    @Test
    @DisplayName("不含危险全表操作 —— 无 TRUNCATE / 无 DELETE FROM 无 WHERE")
    void noDangerousStatements() {
        assertFalse(sql.toUpperCase().contains("TRUNCATE"),
                "迁移脚本禁止使用 TRUNCATE");
        assertFalse(sql.toUpperCase().contains("DROP TABLE"),
                "迁移脚本禁止 DROP TABLE");
        // DELETE 不能不带 WHERE —— 简化校验：所有 DELETE 行后续非换行前必须包含 WHERE
        Pattern bareDelete = Pattern.compile(
                "(?im)DELETE\\s+FROM\\s+\\w+\\s*;");
        assertFalse(bareDelete.matcher(sql).find(),
                "DELETE 语句必须带 WHERE 子句");
    }
}
