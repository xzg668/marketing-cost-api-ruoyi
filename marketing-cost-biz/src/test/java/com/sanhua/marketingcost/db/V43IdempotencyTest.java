package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.MountableFile;

/**
 * T11 · V43 SQL 幂等性专项验证。
 *
 * <p>BomMapperTestBase 在 static 块里跑了一次 V43；本测试再跑一次，检查：
 * <ul>
 *   <li>不抛异常（INSERT NOT EXISTS 防重）</li>
 *   <li>字典类型行数仍为 1</li>
 *   <li>字典数据条目仍为 5</li>
 *   <li>UPDATE 老规则 #4 第二次影响 0 行（如果该规则存在；不存在也安全）</li>
 * </ul>
 */
@Tag("integration")
@DisplayName("T11 V43 DDL · 幂等重跑")
class V43IdempotencyTest extends BomMapperTestBase {

  @Test
  @DisplayName("V43 重跑无报错且数据不重复")
  void testV43Rerun() throws Exception {
    // 第二次执行 V43（基类 static 块已执行过 1 次）
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V43__bom_leaf_rollup_dict.sql"),
        "/tmp/V43_rerun.sql");
    var execResult = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + MYSQL.getDatabaseName() + " < /tmp/V43_rerun.sql");
    assertThat(execResult.getExitCode())
        .as("V43 重跑必须成功 stderr=" + execResult.getStderr())
        .isZero();

    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      // 1) sys_dict_type 仍仅 1 行（NOT EXISTS 防重）
      try (ResultSet rs = stmt.executeQuery(
          "SELECT COUNT(*) FROM sys_dict_type WHERE dict_type='bom_leaf_rollup_codes'")) {
        rs.next();
        assertThat(rs.getInt(1)).as("sys_dict_type 行数").isEqualTo(1);
      }
      // 2) sys_dict_data 仍恰好 5 条（5 条种子）
      try (ResultSet rs = stmt.executeQuery(
          "SELECT COUNT(*) FROM sys_dict_data "
              + "WHERE dict_type='bom_leaf_rollup_codes' AND deleted=0")) {
        rs.next();
        assertThat(rs.getInt(1)).as("sys_dict_data 行数").isEqualTo(5);
      }
      // 3) 字典 5 条种子的 dict_value 集合正确
      try (ResultSet rs = stmt.executeQuery(
          "SELECT GROUP_CONCAT(dict_value ORDER BY dict_sort) FROM sys_dict_data "
              + "WHERE dict_type='bom_leaf_rollup_codes' AND deleted=0")) {
        rs.next();
        String concat = rs.getString(1);
        assertThat(concat).contains("171711404");
        assertThat(concat).contains("NAME:拉制铜管");
        assertThat(concat).contains("NAME:紫铜盘管");
        assertThat(concat).contains("NAME:紫铜直管");
        assertThat(concat).contains("NAME:直管");
      }
    }

    // 4) 第三次执行 —— 再幂等一次（双重保险）
    var third = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + MYSQL.getDatabaseName() + " < /tmp/V43_rerun.sql");
    assertThat(third.getExitCode()).isZero();
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(
            "SELECT COUNT(*) FROM sys_dict_data "
                + "WHERE dict_type='bom_leaf_rollup_codes' AND deleted=0")) {
      rs.next();
      assertThat(rs.getInt(1)).as("第三次执行后行数").isEqualTo(5);
    }
  }

  @Test
  @DisplayName("V43 内嵌的 UPDATE 老规则 #4 在测试环境无该规则时 affected=0 不报错")
  void testV43RuleUpdateNoMatch() throws Exception {
    // testBase 没跑 V41 之前的环境是没 'copper-tube-assembly' 这条规则的；
    //   现在 testBase 跑了 V41 + V43，V41 INSERT 该规则、V43 UPDATE 它停用 →
    //   再跑一遍 V43 时该规则 enabled 已是 0，UPDATE 命中条件 enabled=1 不再命中。
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V43__bom_leaf_rollup_dict.sql"),
        "/tmp/V43_rerun2.sql");
    var execResult = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + MYSQL.getDatabaseName() + " < /tmp/V43_rerun2.sql");
    assertThat(execResult.getExitCode()).isZero();

    // 校验：规则 enabled=0（被 V43 停用）；remark 含 [T11停用-业务废弃-2026-04-27]
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(
            "SELECT enabled, remark FROM bom_stop_drill_rule "
                + "WHERE match_value='copper-tube-assembly' AND drill_action='ROLLUP_TO_PARENT' "
                + "  AND deleted=0")) {
      assertThat(rs.next()).as("V41 应已 INSERT 该规则").isTrue();
      assertThat(rs.getInt("enabled")).as("V43 应将其停用 enabled=0").isZero();
      assertThat(rs.getString("remark")).contains("T11停用-业务废弃-2026-04-27");
    }
  }
}
