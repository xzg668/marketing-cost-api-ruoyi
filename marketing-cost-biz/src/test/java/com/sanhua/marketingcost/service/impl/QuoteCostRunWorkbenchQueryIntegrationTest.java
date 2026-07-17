package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCuMaterialDiffItem;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteCuMaterialDiffItemMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.QuoteCostRunWorkbenchService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
@DisplayName("FCQ-10 单产品查询与差异导出真实数据库契约")
class QuoteCostRunWorkbenchQueryIntegrationTest extends BomMapperTestBase {

  private static final String BUSINESS_UNIT = "COMMERCIAL";

  @Autowired private QuoteCostRunWorkbenchService service;
  @Autowired private OaFormMapper oaFormMapper;
  @Autowired private OaFormItemMapper oaFormItemMapper;
  @Autowired private QuoteCostRunVersionMapper versionMapper;
  @Autowired private QuoteCuMaterialDiffItemMapper diffItemMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  private final String oaNo = "OA-FCQ10-" + suffix;
  private final String costRunNo = "RUN-FCQ10-" + suffix;
  private Long formId;
  private Long itemId;
  private Long versionId;

  @BeforeEach
  void setUpSnapshot() {
    authenticate(BUSINESS_UNIT);
    // 共享 MySQL 测试基座未加载既有 V56；生产完整迁移中该列已存在。
    Integer remarkColumnCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'lp_cost_run_cost_item' AND COLUMN_NAME = 'remark'",
            Integer.class);
    if (remarkColumnCount != null && remarkColumnCount == 0) {
      jdbcTemplate.execute(
          "ALTER TABLE lp_cost_run_cost_item ADD COLUMN remark VARCHAR(255) DEFAULT NULL");
    }

    OaForm form = new OaForm();
    form.setOaNo(oaNo);
    form.setFormType("FCQ10");
    form.setBusinessUnitType(BUSINESS_UNIT);
    form.setDeleted(0);
    assertThat(oaFormMapper.insert(form)).isEqualTo(1);
    formId = form.getId();

    OaFormItem item = new OaFormItem();
    item.setOaFormId(formId);
    item.setSeq(1);
    item.setMaterialNo("TOP-FCQ10");
    item.setBusinessUnitType(BUSINESS_UNIT);
    item.setDeleted(0);
    assertThat(oaFormItemMapper.insert(item)).isEqualTo(1);
    itemId = item.getId();

    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setCostRunNo(costRunNo);
    version.setVersionNo("VER-FCQ10-" + suffix);
    version.setOaNo(oaNo);
    version.setOaFormItemId(itemId);
    version.setProductCode("TOP-FCQ10");
    version.setPricingMonth("2026-05");
    version.setResultPeriod("2026-05");
    version.setStatus("CONFIRMED");
    version.setFinanceCuPrice(new BigDecimal("90.00000000"));
    version.setOaCuPrice(new BigDecimal("102.03900000"));
    version.setFinanceMaterialCost(new BigDecimal("261.12800000"));
    version.setOaMaterialCost(new BigDecimal("285.20600000"));
    version.setCuMaterialAdjustment(new BigDecimal("24.07800000"));
    version.setTotalCost(new BigDecimal("1000.00000000"));
    version.setFinalQuoteAmount(new BigDecimal("1024.07800000"));
    version.setOaPricePrepareNo("PPR-OA-FCQ10-" + suffix);
    version.setFinancePricePrepareNo("PPR-FIN-FCQ10-" + suffix);
    version.setPartItemCount(0);
    version.setCostItemCount(0);
    version.setBusinessUnitType(BUSINESS_UNIT);
    assertThat(versionMapper.insert(version)).isEqualTo(1);
    versionId = version.getId();

    insertDifference(1, "MAKE-1", "RAW-CU-1", "24.07800000");
    insertDifference(2, "MAKE-2", "RAW-CU-2", "-1.50000000");
    insertDifference(3, "MAKE-3", "RAW-NONCU-1", "0.00000000");
  }

  @AfterEach
  void cleanRows() {
    SecurityContextHolder.clearContext();
    jdbcTemplate.update("DELETE FROM lp_quote_cu_material_diff_item WHERE cost_run_no = ?", costRunNo);
    jdbcTemplate.update("DELETE FROM lp_quote_cost_run_version WHERE cost_run_no = ?", costRunNo);
    if (itemId != null) {
      jdbcTemplate.update("DELETE FROM oa_form_item WHERE id = ?", itemId);
    }
    if (formId != null) {
      jdbcTemplate.update("DELETE FROM oa_form WHERE id = ?", formId);
    }
  }

  @Test
  @DisplayName("历史分页读取冻结值，Excel只导出成本汇总，切换BU后拒绝访问")
  void readsFrozenRowsForPageAndExportAndRejectsCrossBusinessUnit() throws Exception {
    var firstPage =
        service.pageCuMaterialDifferences(
            oaNo, itemId, costRunNo, 1, 2, null, null, false, null);
    var secondPage =
        service.pageCuMaterialDifferences(
            oaNo, itemId, costRunNo, 2, 2, null, null, false, null);
    var positive =
        service.pageCuMaterialDifferences(
            oaNo, itemId, costRunNo, 1, 20, "MAKE-1", "RAW-CU-1", true, "POSITIVE");

    assertThat(firstPage.getTotal()).isEqualTo(3L);
    assertThat(firstPage.getList()).hasSize(2);
    assertThat(secondPage.getList()).hasSize(1);
    assertThat(positive.getTotal()).isEqualTo(1L);
    assertThat(positive.getList().get(0).getDiffAmount()).isEqualByComparingTo("24.07800000");

    QuoteCostRunVersion storedVersion = versionMapper.selectById(versionId);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertThat(service.exportVersion(oaNo, itemId, versionId, output)).isEqualTo(3);

    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()))) {
      assertThat(workbook.getSheet("汇总").getRow(11).getCell(1).getStringCellValue())
          .isEqualTo(storedVersion.getCuMaterialAdjustment().toPlainString());
      assertThat(workbook.getSheet("汇总").getRow(13).getCell(1).getStringCellValue())
          .isEqualTo(storedVersion.getFinalQuoteAmount().toPlainString());
      assertThat(workbook.getSheet("Cu材料费差异")).isNull();
    }

    authenticate("HOUSEHOLD");
    assertThatThrownBy(
            () ->
                service.pageCuMaterialDifferences(
                    oaNo, itemId, costRunNo, 1, 20, null, null, false, null))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageMatching(".*(无权访问|不存在).*");
    assertThatThrownBy(
            () -> service.exportVersion(oaNo, itemId, versionId, new ByteArrayOutputStream()))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageMatching(".*(无权访问|不存在).*");
  }

  private void insertDifference(
      int lineNo, String parentMaterialCode, String materialCode, String diffAmount) {
    QuoteCuMaterialDiffItem row = new QuoteCuMaterialDiffItem();
    row.setCostRunVersionId(versionId);
    row.setCostRunNo(costRunNo);
    row.setLineNo(lineNo);
    row.setSettlementKey("SET:FCQ10:" + suffix + ":" + lineNo);
    row.setDetailLevel("RAW_COMPONENT");
    row.setContributesToAdjustment(lineNo == 1 ? 1 : 0);
    row.setTopProductCode("TOP-FCQ10");
    row.setParentMaterialCode(parentMaterialCode);
    row.setMaterialCode(materialCode);
    row.setMaterialName("FCQ10材料" + lineNo);
    row.setQuantity(new BigDecimal("2.00000000"));
    row.setFinanceUnitPrice(new BigDecimal("90.00000000"));
    row.setOaUnitPrice(new BigDecimal("102.03900000"));
    row.setFinanceAmount(new BigDecimal("261.12800000"));
    row.setOaAmount(new BigDecimal("285.20600000"));
    row.setDiffAmount(new BigDecimal(diffAmount));
    row.setCuAffected(lineNo == 3 ? 0 : 1);
    row.setPriceFormulaRefType("MAKE_PART_COMPONENT");
    row.setPriceFormulaRefId(9000L + lineNo);
    row.setTraceJson("{\"lineNo\":" + lineNo + "}");
    row.setBusinessUnitType(BUSINESS_UNIT);
    assertThat(diffItemMapper.insert(row)).isEqualTo(1);
  }

  private void authenticate(String businessUnitType) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("fcq10.integration", null, List.of());
    authentication.setDetails(
        Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnitType));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
