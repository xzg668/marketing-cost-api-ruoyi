package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingResult;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.ProductCostingPipeline;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * T7 真实数据副本门禁。
 *
 * <p>默认跳过，只有显式传入 {@code -Dsales.cost.real.pipeline=true} 才会执行。运行时的数据源
 * 必须指向可删除的数据库副本；本测试会真实写入 BOM 工作区、价格准备和成功成本版本。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "sales.cost.real.pipeline", matches = "true")
@DisplayName("T7 统一产品核算流水线真实副本门禁")
class ProductCostingPipelineRealDataTest {

  private static final String OA_NO = "FI-SC-006-20260326-032";
  private static final long ITEM_ID = 284L;
  private static final String PERIOD_MONTH = "2026-08";

  @Autowired private ProductCostingPipeline pipeline;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void authenticate() {
    String databaseName = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
    assertThat(databaseName)
        .as("真实数据流水线测试只能连接明确命名的 T7 隔离库，禁止写入 marketing_cost 正式库")
        .startsWith("marketing_cost_t7_");

    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("admin", null, List.of());
    authentication.setDetails(
        Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "COMMERCIAL", "userId", 1L));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("一次执行自动成功，重复请求复用同一成功版本")
  void executesAndReusesSuccessfulVersion() {
    Long oldVersionId =
        jdbcTemplate.queryForObject(
            "SELECT confirmed_cost_version_id FROM oa_form_item WHERE id = ?", Long.class, ITEM_ID);
    int beforeVersionCount = versionCount();

    ProductCostingResult first =
        pipeline.execute(new ProductCostingRequest(OA_NO, ITEM_ID, PERIOD_MONTH, "t7-real-copy", true));

    assertThat(first.getPipelineStatus()).isEqualTo("SUCCESS");
    assertThat(first.getBlockingStatus()).isEqualTo("NONE");
    assertThat(first.getCurrentStep()).isEqualTo("COST_RUN");
    assertThat(first.isReusedSuccess()).isFalse();
    assertThat(first.getCostVersionId()).isNotNull().isNotEqualTo(oldVersionId);
    assertThat(first.getPricePrepareNo()).isNotBlank();
    assertThat(first.getTotalCost()).isPositive();
    assertThat(first.getWarningCount()).isGreaterThan(0);

    Long currentVersionId =
        jdbcTemplate.queryForObject(
            "SELECT confirmed_cost_version_id FROM oa_form_item WHERE id = ?", Long.class, ITEM_ID);
    assertThat(currentVersionId).isEqualTo(first.getCostVersionId());
    assertThat(singleText("SELECT status FROM lp_quote_cost_run_version WHERE id = ?", currentVersionId))
        .isEqualTo("SUCCESS");
    assertThat(singleText("SELECT status FROM lp_quote_cost_run_version WHERE id = ?", oldVersionId))
        .isEqualTo("HISTORY");
    assertThat(singleText(
            "SELECT workspace_status FROM lp_quote_costing_workspace "
                + "WHERE oa_form_item_id = ? AND period_month = ?",
            ITEM_ID,
            PERIOD_MONTH))
        .isEqualTo("SUCCESS");
    assertThat(singleLong(
            "SELECT current_cost_version_id FROM lp_quote_costing_workspace "
                + "WHERE oa_form_item_id = ? AND period_month = ?",
            ITEM_ID,
            PERIOD_MONTH))
        .isEqualTo(currentVersionId);
    assertThat(countByVersion("lp_cost_run_part_item", currentVersionId)).isEqualTo(31);
    assertThat(countByVersion("lp_cost_run_cost_item", currentVersionId)).isEqualTo(23);
    assertThat(countByVersion("lp_cost_run_trace_snapshot", currentVersionId)).isEqualTo(54);

    ProductCostingResult repeated =
        pipeline.execute(new ProductCostingRequest(OA_NO, ITEM_ID, PERIOD_MONTH, "t7-real-copy", false));

    assertThat(repeated.getPipelineStatus()).isEqualTo("SUCCESS");
    assertThat(repeated.isReusedSuccess()).isTrue();
    assertThat(repeated.getCostVersionId()).isEqualTo(currentVersionId);
    assertThat(versionCount()).isEqualTo(beforeVersionCount + 1);
  }

  private int versionCount() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id = ?",
        Integer.class,
        ITEM_ID);
  }

  private int countByVersion(String tableName, Long versionId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM " + tableName + " WHERE cost_run_version_id = ?",
        Integer.class,
        versionId);
  }

  private String singleText(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, String.class, args);
  }

  private Long singleLong(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, Long.class, args);
  }
}
