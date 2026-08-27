package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
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
@DisplayName("FCQ-10 单产品冻结版本导出真实数据库契约")
class QuoteCostRunWorkbenchQueryIntegrationTest extends BomMapperTestBase {

  private static final String BUSINESS_UNIT = "COMMERCIAL";

  @Autowired private QuoteCostRunWorkbenchService service;
  @Autowired private OaFormMapper oaFormMapper;
  @Autowired private OaFormItemMapper oaFormItemMapper;
  @Autowired private QuoteCostRunVersionMapper versionMapper;
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

  }

  @AfterEach
  void cleanRows() {
    SecurityContextHolder.clearContext();
    jdbcTemplate.update("DELETE FROM lp_quote_cost_run_version WHERE cost_run_no = ?", costRunNo);
    if (itemId != null) {
      jdbcTemplate.update("DELETE FROM oa_form_item WHERE id = ?", itemId);
    }
    if (formId != null) {
      jdbcTemplate.update("DELETE FROM oa_form WHERE id = ?", formId);
    }
  }

  @Test
  @DisplayName("导出只含一张成本汇总，切换BU后拒绝访问")
  void exportsSummaryAndRejectsCrossBusinessUnit() throws Exception {
    QuoteCostRunVersion storedVersion = versionMapper.selectById(versionId);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertThat(service.exportVersion(oaNo, itemId, versionId, output)).isEqualTo(1);

    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()))) {
      assertThat(workbook.getSheet("汇总").getRow(11).getCell(1).getStringCellValue())
          .isEqualTo(storedVersion.getCuMaterialAdjustment().toPlainString());
      assertThat(workbook.getSheet("汇总").getRow(13).getCell(1).getStringCellValue())
          .isEqualTo(storedVersion.getFinalQuoteAmount().toPlainString());
      assertThat(workbook.getSheet("Cu材料费差异")).isNull();
    }

    authenticate("HOUSEHOLD");
    assertThatThrownBy(
            () -> service.exportVersion(oaNo, itemId, versionId, new ByteArrayOutputStream()))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageMatching(".*(无权访问|不存在).*");
  }

  private void authenticate(String businessUnitType) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("fcq10.integration", null, List.of());
    authentication.setDetails(
        Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnitType));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
