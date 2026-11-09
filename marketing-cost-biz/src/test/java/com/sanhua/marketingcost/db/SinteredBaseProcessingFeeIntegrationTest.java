package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.mapper.BomSettlementRuleMapper;
import com.sanhua.marketingcost.service.settlement.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;

/** The configured rule runs through the production settlement engine and ordinary price pipeline. */
@Tag("integration")
@TestPropertySource(properties={"srm.fixed-price.enabled=false","oa.person-directory.enabled=false",
    "oa.person-directory.run-at-startup=false","cms.sync.publish.enabled=false","quote.drawing-verification.enabled=false"})
class SinteredBaseProcessingFeeIntegrationTest extends MonthlyRepriceCostingFixture {
  private static final String RULE = "SINTERED_BASE_PROCESS_FEE";
  private static final String SCRIPT = "db/V367__sintered_base_processing_fee.sql";
  @Autowired BomSettlementRowBuildEngine engine;
  @Autowired BomSettlementRuleMapper rules;
  @Autowired com.sanhua.marketingcost.service.QuoteCostRunWorkbenchService exports;

  @BeforeEach void installRule() {
    var resource = new ClassPathResource(SCRIPT);
    new ResourceDatabasePopulator(resource).execute(jdbc.getDataSource());
  }

  @Test void ancestorFeeUsesItsOwnCodeOnceAndKeepsAllExistingMaterialRows() {
    var nodes = List.of(node("P", null, "成品", "制造件", "1"),
        node("201850113", "P", "烧结基座", "制造件", "1"),
        node("201850157", "P/201850113", "烧结基座毛坯", "制造件", "1"),
        node("7218501041211", "P/201850113/201850157", "基座", "制造件", "1"),
        node("RAW", "P/201850113/201850157/7218501041211", "基座毛坯", "采购件", "1"),
        node("PIN", "P/201850113/201850157", "导针", "采购件", "2"));
    var before = engine.build(request(nodes, activeRules().stream().filter(r -> !RULE.equals(r.getRuleCode())).toList()));
    var after = engine.build(request(nodes, activeRules()));
    assertThat(after.costingRows()).filteredOn(r -> "OUTSOURCED_PROCESS_FEE".equals(r.getSettlementRowType()))
        .singleElement().satisfies(row -> {
          assertThat(row.getMaterialCode()).isEqualTo("201850113");
          assertThat(row.getMaterialName()).isEqualTo("烧结基座-委外加工费");
          assertThat(row.getQtyPerTop()).isEqualByComparingTo("1");
          assertThat(row.getShapeAttr()).isEqualTo("委外加工件");
          assertThat(row.getSubtreeCostRequired()).isZero();
        });
    assertThat(after.costingRows().stream().filter(r -> !"OUTSOURCED_PROCESS_FEE".equals(r.getSettlementRowType())).toList())
        .usingRecursiveComparison().isEqualTo(before.costingRows());
  }

  @Test void nameScopeIsNotAConfiguredMaterialCodeAndBlankRemainsAPricedMaterial() {
    var nodes = List.of(node("P", null, "成品", "制造件", "1"),
        node("ANOTHER-CODE", "P", "定制烧结基座组件", "制造件", "2.5"),
        node("BLANK", "P/ANOTHER-CODE", "烧结基座毛坯", "采购件", "2.5"),
        node("OTHER", "P", "普通制造组件", "制造件", "1"),
        node("OTHER-RAW", "P/OTHER", "普通原料", "采购件", "1"));
    var rows = engine.build(request(nodes, activeRules())).costingRows();
    assertThat(rows).filteredOn(r -> "OUTSOURCED_PROCESS_FEE".equals(r.getSettlementRowType()))
        .singleElement().satisfies(r -> {
          assertThat(r.getMaterialCode()).isEqualTo("ANOTHER-CODE");
          assertThat(r.getQtyPerTop()).isEqualByComparingTo("2.5");
        });
    assertThat(rows).filteredOn(r -> "DEFAULT_LEAF".equals(r.getSettlementRowType()))
        .extracting(BomCostingRow::getMaterialCode).containsExactlyInAnyOrder("BLANK", "OTHER-RAW");
  }

  @Test void differentOccurrencesKeepTheirQuantityAndExistingOutsourceFeeIsNotDuplicated() {
    var nodes = List.of(node("P", null, "成品", "制造件", "1"),
        node("A", "P", "结构A", "制造件", "1"), node("B", "P", "结构B", "制造件", "1"),
        node("BASE", "P/A", "烧结基座", "委外加工件", "2"),
        node("BASE", "P/B", "烧结基座", "制造件", "3"),
        node("RAW-A", "P/A/BASE", "原料A", "采购件", "2"),
        node("RAW-B", "P/B/BASE", "原料B", "采购件", "3"));
    var fees = engine.build(request(nodes, activeRules())).costingRows().stream()
        .filter(r -> "OUTSOURCED_PROCESS_FEE".equals(r.getSettlementRowType())).toList();
    assertThat(fees).hasSize(2).extracting(BomCostingRow::getMaterialCode).containsOnly("BASE");
    assertThat(fees.stream().map(BomCostingRow::getQtyPerTop).reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("5");
  }

  @Test void wholeAssemblyHouseholdPriceStillStopsItsSubtreeAndFee() {
    var nodes = List.of(node("P", null, "成品", "制造件", "1"),
        node("BASE", "P", "烧结基座", "制造件", "1"),
        node("RAW", "P/BASE", "烧结基座毛坯", "采购件", "1"));
    var rows = engine.build(request(nodes, activeRules()), Set.of("P/BASE")).costingRows();
    assertThat(rows).singleElement().satisfies(r -> {
      assertThat(r.getMaterialCode()).isEqualTo("BASE");
      assertThat(r.getSettlementRowType()).isEqualTo("HOUSEHOLD_INQUIRY");
    });
  }

  @Test void freshCostAddsTheFeeAdoptsItsMonthlyPriceAndPreservesSavedHistory() throws Exception {
    unit = "COMMERCIAL"; priceOrg = "210"; materialOrg = "COMMERCIAL";
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    repriceProperties.setGoLiveMonths(Map.of(unit, month));
    var date = java.time.YearMonth.parse(month).atDay(1).atTime(12, 0);
    String product = "SINTERED-" + UUID.randomUUID().toString().substring(0, 8);
    String material = product + "-RAW", base = "201850113";
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,customer) VALUES(?,'COMMERCIAL',80000,'SIN-TEST')", product);
    long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, product);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')", form, product, product);
    long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)", date.getYear());
    ordinarySources(product, material, form, date);
    comparisonFixedPrice(material, "2", date);
    // Use the observed November fee in a controlled source; no production price/adoption is changed.
    comparisonFixedPrice(base, "2.876106194690265487", date);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr) VALUES(?,'烧结基座','件','COMMERCIAL',?,1,'15','制造件')", base, product);
    var raw = rawHierarchy.selectOne(Wrappers.<com.sanhua.marketingcost.entity.BomRawHierarchy>lambdaQuery()
        .eq(com.sanhua.marketingcost.entity.BomRawHierarchy::getBuildBatchId, product)
        .eq(com.sanhua.marketingcost.entity.BomRawHierarchy::getMaterialCode, material));
    var parent = new com.sanhua.marketingcost.entity.BomRawHierarchy();
    org.springframework.beans.BeanUtils.copyProperties(raw, parent);
    parent.setId(null); parent.setMaterialCode(base); parent.setMaterialName("烧结基座");
    parent.setShapeAttr("制造件"); parent.setIsLeaf(0); parent.setQtyPerParent(BigDecimal.ONE); parent.setQtyPerTop(BigDecimal.ONE);
    parent.setPath("/" + product + "/" + base + "/"); parent.setSourceLineKey("SINTERED-BASE"); rawHierarchy.insert(parent);
    raw.setPath(parent.getPath() + material + "/"); raw.setParentCode(base); raw.setLevel(2); rawHierarchy.updateById(raw);
    var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("sintered-test", null,
        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    auth.setDetails(Map.of("businessUnitType", unit, "userId", 1L));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      jdbc.update("UPDATE lp_bom_settlement_rule SET enabled=0 WHERE rule_code=?", RULE);
      buildOriginalFixture(form, item, product, month, date, material, Map.of());
      var before = pipeline.execute(new com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest(product, item, null, "sintered-test", true));
      assertThat(before.getPipelineStatus()).as(before.getMessage()).isEqualTo("SUCCESS");
      var old = jdbc.queryForMap("SELECT id,total_cost,final_quote_amount,jianji_snapshot_json FROM lp_quote_cost_run_version WHERE id=?", before.getCostVersionId());
      var oldParts = jdbc.queryForList("SELECT part_code AS material_code,part_name,unit_price,amount AS material_cost FROM lp_cost_run_part_item WHERE cost_run_version_id=? ORDER BY part_code", before.getCostVersionId());
      jdbc.update("UPDATE lp_bom_settlement_rule SET enabled=1 WHERE rule_code=?", RULE);
      var request = new com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest(product, item, null, "sintered-test", false);
      var prepared = pipeline.prepare(request); // The whole-quote entry uses this non-forced path.
      assertThat(prepared.getPipelineStatus()).as(prepared.getMessage()).isEqualTo("READY");
      assertThat(prepared.isReusedSuccess()).isFalse();
      var after = pipeline.execute(request);
      assertThat(after.getPipelineStatus()).as(after.getMessage()).isEqualTo("SUCCESS");
      assertThat(after.getCostVersionId()).isNotEqualTo(before.getCostVersionId());
      var parts = jdbc.queryForList("SELECT part_code AS material_code,part_name,unit_price,amount AS material_cost FROM lp_cost_run_part_item WHERE cost_run_version_id=? ORDER BY part_code", after.getCostVersionId());
      var fee = parts.stream().filter(p -> base.equals(p.get("material_code"))).toList();
      assertThat(fee).hasSize(1);
      assertThat(fee.getFirst().get("part_name")).isEqualTo("烧结基座-委外加工费");
      assertThat((BigDecimal) fee.getFirst().get("unit_price")).isCloseTo(new BigDecimal("2.87610619"), org.assertj.core.data.Offset.offset(new BigDecimal("0.000001")));
      assertThat(parts.stream().filter(p -> !base.equals(p.get("material_code"))).toList()).isEqualTo(oldParts);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?", Integer.class, base)).isEqualTo(1);
      assertThat(jdbc.queryForMap("SELECT id,total_cost,final_quote_amount,jianji_snapshot_json FROM lp_quote_cost_run_version WHERE id=?", before.getCostVersionId())).isEqualTo(old);
      var saved = jdbc.queryForMap("SELECT id,total_cost,final_quote_amount,jianji_snapshot_json FROM lp_quote_cost_run_version WHERE id=?", after.getCostVersionId());
      assertThat(saved.get("jianji_snapshot_json").toString()).contains(base);
      assertThat((BigDecimal) saved.get("total_cost")).isGreaterThan((BigDecimal) old.get("total_cost"));
      var repeated = pipeline.execute(request);
      assertThat(repeated.isReusedSuccess()).isTrue();
      assertThat(repeated.getCostVersionId()).isEqualTo(after.getCostVersionId());
      var workbook = new java.io.ByteArrayOutputStream();
      exports.exportVersion(product, item, after.getCostVersionId(), workbook);
      try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(workbook.toByteArray()))) {
        var text = new StringBuilder(); var formatter = new org.apache.poi.ss.usermodel.DataFormatter();
        for (var sheet : book) for (var row : sheet) for (var cell : row) text.append(formatter.formatCellValue(cell)).append('\n');
        assertThat(text.toString()).contains(base, "烧结基座-委外加工费");
      }
      String evidence = System.getProperty("sintered.test.evidence");
      if (evidence != null) {
        java.nio.file.Files.write(java.nio.file.Path.of(evidence).resolveSibling("isolated-cost.xlsx"), workbook.toByteArray());
        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValue(
          java.nio.file.Path.of(evidence).toFile(), Map.of("before", old, "after", saved, "beforeParts", oldParts, "afterParts", parts,
              "totalCostDelta", ((BigDecimal) saved.get("total_cost")).subtract((BigDecimal) old.get("total_cost"))));
      }
    } finally {
      jdbc.update("UPDATE lp_bom_settlement_rule SET enabled=1 WHERE rule_code=?", RULE);
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }

  @Test void applyingConfigurationAgainKeepsAllExistingRulesUnchanged() {
    var before = jdbc.queryForList("SELECT * FROM lp_bom_settlement_rule ORDER BY id");
    installRule();
    assertThat(jdbc.queryForList("SELECT * FROM lp_bom_settlement_rule ORDER BY id")).isEqualTo(before);
  }

  private List<BomSettlementRule> activeRules() {
    return rules.selectList(Wrappers.<BomSettlementRule>lambdaQuery().eq(BomSettlementRule::getEnabled, 1));
  }

  private BomSettlementBuildRequest request(List<BomSettlementNode> nodes, List<BomSettlementRule> configured) {
    return new BomSettlementBuildRequest("SINTERED-TEST", "P", LocalDate.of(2026, 11, 8), "2026-11",
        "SINTERED-TEST", LocalDateTime.of(2026, 11, 8, 0, 0), "COMMERCIAL", "主制造", nodes, configured);
  }

  private BomSettlementNode node(String code, String parentPath, String name, String shape, String qty) {
    String path = "/" + (parentPath == null ? "" : parentPath + "/") + code + "/";
    return new BomSettlementNode(null, "P", parentPath == null ? null : parentPath.substring(parentPath.lastIndexOf('/') + 1),
        code, parentPath == null ? 0 : parentPath.split("/").length, path, new BigDecimal(qty), new BigDecimal(qty),
        name, null, shape, null, null, "15", "普通物料", null, "主制造", "V1", 1, "采购件".equals(shape) ? 1 : 0,
        LocalDate.of(2026, 1, 1), null, null, "210", "COMMERCIAL", "COMMERCIAL", null);
  }
}
