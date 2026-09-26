package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V255TechnicalDataPreCutoverSqlTest {

  @Test
  void disablesOnlyLegacyWritePermissionsAndKeepsNewChainEnabled() throws IOException {
    try (var stream = getClass().getResourceAsStream(
        "/db/V255__technical_data_pre_cutover.sql")) {
      assertThat(stream).isNotNull();
      String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(sql)
          .contains("'collaboration:task:create'")
          .contains("'collaboration:task:edit'")
          .contains("'collaboration:task:submit'")
          .contains("'collaboration:review:decide'")
          .contains("'collaboration:operations:compensate'")
          .contains("'technical:data:task:list'")
          .contains("'technical:data:review:decide'")
          .contains("'technical:data:admin:operate'")
          .doesNotContain("DELETE FROM")
          .doesNotContain("DROP TABLE");
    }
  }
}
