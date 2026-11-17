package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.technicaldata.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Real saved October checks, durable queue and price calculator after November becomes active. */
@Tag("integration")
class TechnicalDataPendingMonthIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired BomRawHierarchyMapper hierarchy;
  @Autowired ProductCostingPipeline pipeline;
  @Autowired PricePrepareService prices;
  @Autowired TechnicalDataPendingState state;
  @Autowired TechnicalDataPendingRefresh refresh;
  @Autowired TechnicalDataPendingProductQuery pending;
  @MockBean(answer = org.mockito.Answers.CALLS_REAL_METHODS) QuotePricingPeriod periods;

  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void historicalRefreshRetainsItsMonthAndOnlyResolvesActuallyRepairedPrice(boolean repaired) throws Exception {
    when(periods.currentMonth(any())).thenReturn("2026-10");
    var actor = new UsernamePasswordAuthenticationToken("admin", null,
        List.of(new SimpleGrantedAuthority("*:*:*")));
    actor.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(actor);
    String oa = "OLD-PENDING-" + UUID.randomUUID().toString().substring(0, 8), material = oa + "-PART";
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)", oa);
    long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, oa);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,seq,material_no,product_name,business_type,shipping_fee,business_unit_type) VALUES(?,1,?,'历史待办月份验收','批量品',0,'COMMERCIAL')", form, oa);
    long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    var sources = new OrdinaryCostingTestSources(jdbc, hierarchy);
    sources.ordinarySources(oa, material, form, LocalDateTime.of(2026, 10, 1, 12, 0));
    jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?", oa + "-BARE", oa);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')", oa + "-BARE", oa + "-BARE", oa);
    jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?", oa + "-BARE", oa);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET child_type='STANDARD' WHERE top_product_code=? AND level>0", oa);
    var checked = pipeline.prepare(new ProductCostingRequest(oa, item, "2026-10", "month-regression", false));
    assertThat(checked.getTechnicalDataCheck()).as(checked.getMessage()).isNotNull();
    assertThat(checked.getTechnicalDataCheck().modules()).anySatisfy(m -> {
      assertThat(m.moduleType()).isEqualTo("PRICE"); assertThat(m.required()).isTrue();
    });
    // Reproduce the pre-optimization record: a saved costing check without a pending projection.
    jdbc.update("UPDATE lp_quote_costing_workspace SET technical_check_json=JSON_REMOVE(technical_check_json,'$.pending') WHERE oa_form_item_id=?", item);
    var original = jdbc.queryForMap("SELECT workspace_status,period_month,technical_check_fingerprint,JSON_EXTRACT(technical_check_json,'$.check') saved_check FROM lp_quote_costing_workspace WHERE oa_form_item_id=?", item);
    var counts = protectedCounts();
    when(periods.currentMonth(any())).thenReturn("2026-11");
    if (repaired) sources.comparisonFixedPrice(material, "2", LocalDateTime.of(2026, 10, 1, 12, 0));
    state.initializeLegacy();
    assertThat(state.waiting(16)).anySatisfy(w -> {
      assertThat(w.itemId()).isEqualTo(item); assertThat(w.month()).isEqualTo("2026-10");
    });

    var scheduler = new TechnicalDataPendingRefreshSchedule(state, refresh);
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      executor.submit(() -> {
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        for (int pass = 0; pass < 3; pass++) scheduler.tick();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
      }).get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    assertThat(state.waiting(16)).noneMatch(w -> w.itemId() == item);
    var document = json.readTree(jdbc.queryForObject("SELECT technical_check_json FROM lp_quote_costing_workspace WHERE oa_form_item_id=?", String.class, item));
    var projected = document.path("pending").path("check");
    assertThat(projected.path("accountingMonth").asText()).isEqualTo("2026-10");
    var price = java.util.stream.StreamSupport.stream(projected.path("modules").spliterator(), false)
        .filter(m -> "PRICE".equals(m.path("moduleType").asText())).findFirst().orElseThrow();
    assertThat(price.path("required").asBoolean()).isEqualTo(!repaired);
    assertThat(price.path("availability").asText()).isEqualTo(repaired ? "AVAILABLE" : "MISSING");
    assertThat(pending.count("ALL", null, "2026-10", null, oa)).isEqualTo(repaired ? 0 : 1);
    assertThat(jdbc.queryForMap("SELECT workspace_status,period_month,technical_check_fingerprint,JSON_EXTRACT(technical_check_json,'$.check') saved_check FROM lp_quote_costing_workspace WHERE oa_form_item_id=?", item)).isEqualTo(original);
    assertThat(protectedCounts()).isEqualTo(counts);
    var request = new PricePrepareGenerateRequest(); request.setOaNo(oa); request.setOaFormItemId(item);
    request.setTopProductCode(oa); request.setPeriodMonth("2026-10"); request.setBusinessUnitType("COMMERCIAL");
    assertThatThrownBy(() -> prices.calculate(request)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("当前生效核算月份为 2026-11");
  }

  private Map<String, Long> protectedCounts() {
    var counts = new LinkedHashMap<String, Long>();
    for (var table : List.of("lp_price_prepare_batch", "lp_price_prepare_item", "lp_price_prepare_gap",
        "lp_material_monthly_price", "lp_quote_cost_run_version", "lp_quote_tech_task", "lp_quote_tech_data_version")) {
      counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
    }
    return counts;
  }
}
