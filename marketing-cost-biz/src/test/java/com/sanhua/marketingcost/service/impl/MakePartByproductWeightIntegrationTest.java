package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.service.MakePartPriceCalculator;
import com.sanhua.marketingcost.service.MakePartWeightService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
class MakePartByproductWeightIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired MakePartWeightService weights;
  @Autowired MakePartPriceCalculator calculator;
  @Autowired MakePartPriceCalcRowMapper calculations;
  @Autowired ObjectMapper json;
  @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;

  @ParameterizedTest
  @CsvSource({"原材料加工,千克,0.00250753", "原材料加工,克,2.50753",
      "毛坯加工,kg,0.00250753", "毛坯加工,g,2.50753"})
  void realMapperConvertsUnitsAndPersistsExactWeightsAndDeduction(
      String process, String unit, String output) {
    String key = fixture();
    byproduct(key, "210", "F007", "主制造", "已核准", "2026-08-05", "9999-12-31", output, unit);
    jdbc.update("INSERT INTO lp_u9_bom_byproduct_master(price_org_code,parent_material_no,bom_purpose,version_no,output_type,byproduct_material_no,output_qty,unit,status,effective_from,effective_to) VALUES('210',?,'主制造','F007','副产品',?,0.01,'kg','已核准','2026-08-05','9999-12-31')", key, key + "-OTHER");
    byproduct(key, "210", "F006", "主制造", "已核准", "2026-08-06", "9999-12-31", "0.019", "kg");
    byproduct(key, "220", "F007", "主制造", "已核准", "2026-08-05", "9999-12-31", "0.019", "kg");
    byproduct(key, "210", "F007", "专机", "已核准", "2026-08-05", "9999-12-31", "0.019", "kg");
    byproduct(key, "210", "F007", "主制造", "未核准", "2026-08-07", "9999-12-31", "0.019", "kg");
    // 旧父子净重也不能覆盖更优先的副产品。
    jdbc.update("INSERT INTO lp_make_part_child_net_weight(material_organization_code,parent_material_no,child_material_no,bom_version,period_month,net_weight_g) VALUES('COMMERCIAL',?,?,'F007','2026-11',20.1)", key, key + "-C");
    var weight = resolve(key, process, false);
    assertThat(weight.getStatus()).isEqualTo("OK");
    assertThat(weight.getGrossWeightG()).isEqualByComparingTo("22.20653");
    assertThat(weight.getNetWeightG()).isEqualByComparingTo("19.699");
    var row = new MakePartPriceCalcRow();
    row.setCalcBatchId(key); row.setOaNo(key); row.setBusinessUnitType("COMMERCIAL");
    row.setPricingMonth("2026-11"); row.setParentMaterialNo(key); row.setChildMaterialNo(key + "-C");
    row.setPriceAsOfTime(java.time.LocalDateTime.of(2026, 11, 9, 0, 0));
    row.setItemProcessType(process); row.setStockUnit("毛坯加工".equals(process) ? "只" : "千克");
    row.setGrossWeightG(weight.getGrossWeightG()); row.setNetWeightG(weight.getNetWeightG());
    row.setRawUnitPrice(BigDecimal.TEN); row.setScrapCode(key + "-S");
    row.setScrapUnitPrice(new BigDecimal("2")); row.setRemark(weight.getRemark());
    var calculated = calculator.calculate(List.of(row)).getFirst();
    calculations.insert(calculated);
    var saved = calculations.selectById(calculated.getId());
    assertThat(saved.getNetWeightG()).isEqualByComparingTo("19.699");
    assertThat(saved.getCostPrice()).isEqualByComparingTo(
        "原材料加工".equals(process) ? "0.21705024" : "9.99498494");
    assertThat(saved.getRemark()).contains("byproduct_weight_g=2.50753", "scrap_weight_kg=0.00250753");
    var parent = new BomCostingRow(); parent.setId(1L); parent.setMaterialCode(key);
    parent.setBusinessUnitType("COMMERCIAL"); parent.setPriceOrgCode("210"); parent.setPeriodMonth("2026-11");
    var evidence = new MakePartCalculationEvidence(json);
    var prepared = new PricePrepareItem(); prepared.setMaterialCode(key);
    prepared.setBusinessUnitType("COMMERCIAL"); prepared.setPriceOrgCode("210");
    prepared.setPeriodMonth("2026-11"); prepared.setUnitPrice(saved.getParentTotalCostPrice());
    prepared.setInputSnapshotJson(evidence.capture(parent, List.of(saved)));
    jdbc.update("UPDATE lp_u9_bom_byproduct_master SET output_qty=0.001,unit='kg' WHERE parent_material_no=?", key);
    assertThat(evidence.rows(prepared).getFirst().getNetWeightG()).isEqualByComparingTo("19.699");
    assertThat(calculations.selectById(saved.getId()).getCostPrice()).isEqualByComparingTo(saved.getCostPrice());
  }

  @ParameterizedTest
  @ValueSource(strings = {"原材料加工", "毛坯加工"})
  void absenceOfApplicableByproductUsesParentTheoreticalNetWeight(String process) {
    String key = fixture();
    byproduct(key, "210", "F006", "主制造", "已核准", "2026-01-01", "2026-08-04", "0.001", "kg");
    byproduct(key, "220", "F007", "主制造", "已核准", "2026-08-05", "9999-12-31", "0.002", "kg");
    byproduct(key, "210", "F007", "专机", "已核准", "2026-08-05", "9999-12-31", "0.003", "kg");
    byproduct(key, "210", "F007", "主制造", "未核准", "2026-08-05", "9999-12-31", "0.004", "kg");
    var result = resolve(key, process, false);
    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getNetWeightG()).isEqualByComparingTo("20.016");
    assertThat(result.getRemark()).contains("缺有效U9主制造副产品", "净重取 parent 理论净重");
  }

  @ParameterizedTest
  @ValueSource(strings = {"原材料加工", "毛坯加工"})
  void missingAllNetWeightSourcesCannotSilentlyCalculateZero(String process) {
    String key = fixture();
    jdbc.update("UPDATE lp_material_master_raw SET global_seg_3_theoretical_net_weight='' WHERE material_code=?", key);
    var result = resolve(key, process, false);
    assertThat(result.getStatus()).isEqualTo("MISSING_WEIGHT");
    assertThat(result.getNetWeightG()).isNull();
  }

  @Test void multipleMaterialsKeepSeparateFallbacksAndNeverReuseWholeParentNetWeight() {
    String key = fixture();
    var missing = resolve(key, "原材料加工", true);
    assertThat(missing.getStatus()).isEqualTo("MISSING_WEIGHT");
    jdbc.update("INSERT INTO lp_make_part_child_net_weight(material_organization_code,parent_material_no,child_material_no,bom_version,period_month,net_weight_g) VALUES('COMMERCIAL',?,?,'F007','2026-11',20.1)", key, key + "-C");
    assertThat(resolve(key, "原材料加工", true).getNetWeightG()).isEqualByComparingTo("20.1");
    byproduct(key, "210", "F007", "主制造", "已核准", "2026-08-05", "9999-12-31", "0.00250753", "kg");
    assertThat(resolve(key, "原材料加工", true).getNetWeightG()).isEqualByComparingTo("19.699");
  }

  @ParameterizedTest
  @CsvSource({"原材料加工,千克,0.00250753,false", "毛坯加工,g,2.50753,false",
      "原材料加工,千克,0.00250753,true", "毛坯加工,g,2.50753,true"})
  void cmsScrapIdentityMustMatchEvenForOneUniqueU9Output(
      String process, String unit, String output, boolean parentWeightMissing) {
    String key = fixture();
    jdbc.update("UPDATE lp_material_scrap_ref SET scrap_code=? WHERE material_code=?", key + "-CMS", key + "-C");
    if (parentWeightMissing)
      jdbc.update("UPDATE lp_material_master_raw SET global_seg_3_theoretical_net_weight='' WHERE material_code=?", key);
    byproduct(key, "210", "F007", "主制造", "已核准", "2026-08-05", "9999-12-31", output, unit);
    var single = resolve(key, process, false);
    if (parentWeightMissing) {
      assertThat(single.getStatus()).isEqualTo("MISSING_WEIGHT");
      assertThat(single.getNetWeightG()).isNull();
    } else {
      assertThat(single.getNetWeightG()).isEqualByComparingTo("20.016");
      assertThat(single.getRemark()).contains("净重取 parent 理论净重");
    }
    assertThat(single.getRemark()).contains("缺有效U9主制造副产品");
    assertThat(single.getRemark()).doesNotContain("净重按U9副产品反算");
    // 同一资料作为多子料的一部分时不能无依据扣除这条产出。
    var multi = resolve(key, process, true);
    assertThat(multi.getStatus()).isEqualTo("MISSING_WEIGHT");
    assertThat(multi.getNetWeightG()).isNull();
  }

  private String fixture() {
    String key = "BPW-" + UUID.randomUUID().toString().substring(0, 12);
    for (String code : List.of(key, key + "-C"))
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,global_seg_3_theoretical_net_weight) VALUES(?,?,'COMMERCIAL',?,1,?)", code, code, key, code.equals(key) ? "20.016" : "22.20653");
    jdbc.update("INSERT INTO lp_material_scrap_ref(material_code,scrap_code,scrap_unit,ratio,business_unit_type) VALUES(?,?,'KG',1,'COMMERCIAL')", key + "-C", key + "-S");
    return key;
  }

  private com.sanhua.marketingcost.dto.MakePartWeightResult resolve(String key, String process, boolean multi) {
    // Fixture SQL changes bypass MyBatis; each assertion reads the newly written test rows.
    sqlSession.clearCache();
    var child = new BomU9Source(); child.setChildMaterialNo(key + "-C");
    child.setPriceOrgCode("210"); child.setBomVersion("F007");
    child.setQtyPerParent(new BigDecimal("0.02220653"));
    return weights.resolveWeights(key, child, process, "2026-11", LocalDate.of(2026, 11, 9), "COMMERCIAL", multi);
  }

  private void byproduct(String key, String org, String version, String purpose, String status,
      String from, String to, String output, String unit) {
    jdbc.update("INSERT INTO lp_u9_bom_byproduct_master(price_org_code,parent_material_no,bom_purpose,version_no,output_type,byproduct_material_no,output_qty,unit,status,effective_from,effective_to) VALUES(?,?,?,?,'副产品',?,?,?,?,?,?)",
        org, key, purpose, version, key + "-S", output, unit, status, from, to);
  }
}
