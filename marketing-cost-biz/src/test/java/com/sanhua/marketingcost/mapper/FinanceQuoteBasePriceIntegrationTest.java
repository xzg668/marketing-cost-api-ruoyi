package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.dto.InfluenceFactorImportResponse;
import com.sanhua.marketingcost.dto.InfluenceFactorImportRow;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceAdjustRequest;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceInitializeRequest;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.FinanceBasePriceImportService;
import com.sanhua.marketingcost.service.FinanceQuoteBasePriceConstants;
import com.sanhua.marketingcost.service.FinanceQuoteBasePriceService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@Tag("integration")
class FinanceQuoteBasePriceIntegrationTest extends BomMapperTestBase {

  @Autowired
  private FinanceQuoteBasePriceService financeQuoteBasePriceService;

  @Autowired
  private FinanceBasePriceImportService financeBasePriceImportService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanFinanceQuoteBaseRows() {
    jdbcTemplate.update(
        "DELETE FROM sys_operation_log WHERE title = ?", "财务Cu报价基准维护");
    jdbcTemplate.update(
        "DELETE FROM lp_finance_base_price WHERE price_source = ? OR short_name = ?",
        FinanceQuoteBasePriceConstants.PRICE_SOURCE,
        FinanceQuoteBasePriceConstants.SHORT_NAME);
    authenticate("finance.integration", "COMMERCIAL");
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("真实MySQL：初始化、默认不覆盖、指定月调整和审计在现有表内闭环")
  void initializeSkipAdjustAndAuditOnExistingTables() {
    var initialized = financeQuoteBasePriceService.initialize(
        new FinanceQuoteBasePriceInitializeRequest(
            "2026-07", "2026-12", new BigDecimal("90000")));

    assertThat(initialized.createdCount()).isEqualTo(6);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lp_finance_base_price "
            + "WHERE factor_code='Cu' AND price_source='财务报价基准' "
            + "AND business_unit_type='COMMERCIAL'",
        Integer.class)).isEqualTo(6);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT price FROM lp_finance_base_price "
            + "WHERE price_month='2026-07' AND short_name='报价Cu基准' "
            + "AND price_source='财务报价基准' AND business_unit_type='COMMERCIAL'",
        BigDecimal.class)).isEqualByComparingTo("90.000000");

    var repeated = financeQuoteBasePriceService.initialize(
        new FinanceQuoteBasePriceInitializeRequest(
            "2026-07", "2026-12", new BigDecimal("99000")));
    assertThat(repeated.createdCount()).isZero();
    assertThat(repeated.skippedCount()).isEqualTo(6);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT price FROM lp_finance_base_price "
            + "WHERE price_month='2026-07' AND short_name='报价Cu基准' "
            + "AND price_source='财务报价基准' AND business_unit_type='COMMERCIAL'",
        BigDecimal.class)).isEqualByComparingTo("90.000000");

    Long septemberId = initialized.records().stream()
        .filter(row -> "2026-09".equals(row.priceMonth()))
        .findFirst()
        .orElseThrow()
        .id();
    financeQuoteBasePriceService.adjust(
        septemberId,
        new FinanceQuoteBasePriceAdjustRequest(
            new BigDecimal("95000"), "财务书面通知调整2026年9月"));

    Map<String, Object> audit = jdbcTemplate.queryForMap(
        "SELECT oper_name, oper_param, before_data, after_data, business_unit_type "
            + "FROM sys_operation_log WHERE title='财务Cu报价基准维护' "
            + "AND business_type=2 ORDER BY oper_id DESC LIMIT 1");
    assertThat(audit.get("oper_name")).isEqualTo("finance.integration");
    assertThat(String.valueOf(audit.get("oper_param")))
        .contains("财务书面通知调整2026年9月");
    assertThat(String.valueOf(audit.get("before_data"))).contains("\"pricePerKg\":90.000000");
    assertThat(String.valueOf(audit.get("after_data"))).contains("\"pricePerKg\":95.000000");
    assertThat(audit.get("business_unit_type")).isEqualTo("COMMERCIAL");
    assertThat(financeQuoteBasePriceService.list("2026-09", "2026-09"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.lastModifiedBy()).isEqualTo("finance.integration");
          assertThat(row.lastChangeReason()).isEqualTo("财务书面通知调整2026年9月");
          assertThat(row.lastModifiedAt()).isNotNull();
        });
  }

  @Test
  @DisplayName("真实MySQL：普通影响因素导入不能覆盖专用财务Cu基准")
  void ordinaryInfluenceImportCannotOverwriteFinanceQuoteBase() {
    financeQuoteBasePriceService.initialize(
        new FinanceQuoteBasePriceInitializeRequest(
            "2026-07", "2026-07", new BigDecimal("90000")));
    InfluenceFactorImportRow row = new InfluenceFactorImportRow();
    row.setSeq(1);
    row.setFactorName("财务报价电解铜基准价");
    row.setShortName("报价Cu基准");
    row.setPriceSource("财务报价基准");
    row.setPrice(new BigDecimal("99"));
    row.setUnit("公斤");

    InfluenceFactorImportResponse response =
        financeBasePriceImportService.importRows(List.of(row), "2026-07");

    assertThat(response.getImported()).isZero();
    assertThat(response.getSkipped()).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT price FROM lp_finance_base_price "
            + "WHERE price_month='2026-07' AND short_name='报价Cu基准' "
            + "AND price_source='财务报价基准' AND business_unit_type='COMMERCIAL'",
        BigDecimal.class)).isEqualByComparingTo("90.000000");
  }

  @Test
  @DisplayName("真实MySQL：同月不同BU各一条且缺专用记录时绝不回退市场记录")
  void businessUnitsAreIsolatedAndMarketRowsAreNeverFallback() {
    financeQuoteBasePriceService.initialize(
        new FinanceQuoteBasePriceInitializeRequest(
            "2026-10", "2026-10", new BigDecimal("90000")));
    authenticate("household.finance", "HOUSEHOLD");
    financeQuoteBasePriceService.initialize(
        new FinanceQuoteBasePriceInitializeRequest(
            "2026-10", "2026-10", new BigDecimal("91000")));

    assertThat(financeQuoteBasePriceService.getRequired("2026-10").getPrice())
        .isEqualByComparingTo("91.000000");
    authenticate("finance.integration", "COMMERCIAL");
    assertThat(financeQuoteBasePriceService.getRequired("2026-10").getPrice())
        .isEqualByComparingTo("90.000000");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lp_finance_base_price "
            + "WHERE price_month='2026-10' AND price_source='财务报价基准'",
        Integer.class)).isEqualTo(2);

    jdbcTemplate.update(
        "INSERT INTO lp_finance_base_price "
            + "(price_month, factor_name, short_name, factor_code, price_source, price, unit, "
            + "link_type, business_unit_type, created_at, updated_at) "
            + "VALUES ('2026-11', '长江现货电解铜', '1#Cu', 'Cu', '平均价', 102.039000, "
            + "'公斤', '固定', 'COMMERCIAL', NOW(), NOW())");
    assertThatThrownBy(() -> financeQuoteBasePriceService.getRequired("2026-11"))
        .hasMessage("未维护2026-11财务报价Cu基准，请先由财务初始化或调整后再试算。");
  }

  @Test
  @DisplayName("真实MySQL：V188仅在现有权限表注册两个权限并默认授予管理员")
  void permissionMigrationIsEffective() {
    assertThat(jdbcTemplate.queryForList(
        "SELECT perms FROM sys_menu WHERE menu_id IN (40475, 40476) ORDER BY menu_id",
        String.class)).containsExactly(
            "cost:finance-cu-base:query", "cost:finance-cu-base:edit");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM sys_role_menu WHERE role_id=1 AND menu_id IN (40475, 40476)",
        Integer.class)).isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT component FROM sys_menu WHERE menu_id=40477", String.class))
        .isEqualTo("pages:FinanceCuBasePricePage");
  }

  private void authenticate(String username, String businessUnitType) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(username, "n/a", List.of());
    authentication.setDetails(Map.of(
        BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnitType));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
