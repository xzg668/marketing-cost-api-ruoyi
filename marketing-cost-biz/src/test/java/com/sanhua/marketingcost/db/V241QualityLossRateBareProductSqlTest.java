package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class V241QualityLossRateBareProductSqlTest {

  @Test
  void migrationReplacesLegacyMatchColumnsWithBareProductRule() throws Exception {
    String sql =
        new ClassPathResource("db/V241__replace_quality_loss_rate_with_bare_product_rules.sql")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(sql)
        .contains("`bare_product_code` VARCHAR(80) NOT NULL")
        .contains("`business_unit_type`, `rate_year`, `bare_product_code`")
        .contains("mm.`main_category_code` LIKE '11%'")
        .contains("NULLIF(TRIM(mm.`bare_code`), '')")
        .contains("DROP TABLE `lp_quality_loss_rate`")
        .contains("RENAME TABLE `lp_quality_loss_rate_v241` TO `lp_quality_loss_rate`")
        .doesNotContain("`match_level` VARCHAR")
        .doesNotContain("`match_key` VARCHAR");
  }
}
