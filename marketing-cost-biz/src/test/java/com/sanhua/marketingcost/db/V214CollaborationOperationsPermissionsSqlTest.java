package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-25 协作运维权限迁移")
class V214CollaborationOperationsPermissionsSqlTest {
  @Test
  void addsExplicitReadAndCompensationPermissionsWithoutGrantingBusinessRoles() throws Exception {
    String sql;
    try (var input = getClass().getResourceAsStream(
        "/db/V214__collaboration_operations_permissions.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(sql).contains(
        "collaboration:operations:read",
        "collaboration:operations:compensate",
        "报价协作任务发起");
    assertThat(sql).doesNotContain("INSERT IGNORE INTO sys_role_menu");
  }
}
