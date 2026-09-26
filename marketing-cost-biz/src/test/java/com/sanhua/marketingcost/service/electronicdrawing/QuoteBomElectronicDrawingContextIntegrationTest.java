package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
class QuoteBomElectronicDrawingContextIntegrationTest extends BomMapperTestBase {
  @Autowired private QuoteBomElectronicDrawingContextAdapter adapter;
  @Autowired private ElectronicDrawingSourceImportService imports;
  @Autowired private ElectronicDrawingSourceNodeRepository nodes;
  @Autowired private QuoteBomPreparationRecordMapper preparations;
  @Autowired private JdbcTemplate jdbc;
  private Long itemId;
  private Long augustId;
  private Long septemberId;

  @BeforeEach
  void fixture() {
    String oaNo = "ED-MONTH-" + UUID.randomUUID();
    jdbc.update("INSERT INTO oa_form(oa_no,accounting_period_month,business_unit_type) VALUES(?,'2026-09','COMMERCIAL')", oaNo);
    Long formId = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, oaNo);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,customer_drawing,business_unit_type) VALUES(?,'ED-P','ED-DRAW','COMMERCIAL')", formId);
    itemId = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, formId);
    augustId = preparation(formId, oaNo, "2026-08");
    septemberId = preparation(formId, oaNo, "2026-09");
  }

  @Test
  void importingTheSameFileKeepsMonthVersionsSeparateAndRechecksIdempotent() throws Exception {
    var acquired = acquired();
    var august = imports.importSource(command("2026-08"), acquired);
    var september = imports.importSource(command("2026-09"), acquired);
    assertThat(august.supplementVersionId()).isNotEqualTo(september.supplementVersionId());
    assertThat(imports.importSource(command("2026-08"), acquired).supplementVersionId())
        .isEqualTo(august.supplementVersionId());
    assertThat(adapter.load(itemId, "COMMERCIAL", "210", "2026-08").sourceVersionId())
        .isEqualTo(august.supplementVersionId());
    assertThat(adapter.load(itemId, "COMMERCIAL", "210", "2026-09").sourceVersionId())
        .isEqualTo(september.supplementVersionId());
    assertThat(nodes.findByVersionId(august.supplementVersionId())).singleElement().satisfies(node -> {
      assertThat(node.getReferenceWeight()).isEqualByComparingTo("12");
      assertThat(node.getReferenceWeightUnit()).isEqualTo("g");
      assertThat(node.getQty()).isEqualByComparingTo("2");
    });
  }

  @Test
  void stageReloadAndOptimisticUpdatesStayInTheSelectedMonth() {
    var septemberBefore = jdbc.queryForMap("SELECT * FROM lp_quote_bom_preparation_record WHERE id=?", septemberId);
    var august = adapter.load(itemId, "COMMERCIAL", "210", "2026-08");
    var updated = adapter.updateStage(august, "MATCHING", 42L, "报价员", LocalDateTime.now());
    assertThat(updated.accountingMonth()).isEqualTo("2026-08");
    assertThat(updated.preparationId()).isEqualTo(augustId);
    assertThat(updated.workflowStage()).isEqualTo("MATCHING");
    assertThat(updated.revision()).isEqualTo(august.revision() + 1);
    assertThatThrownBy(() -> adapter.updateStage(august, "PUBLISHED", null, "系统", LocalDateTime.now()))
        .isInstanceOf(ElectronicDrawingWorkflowRetryException.class);
    assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_bom_preparation_record WHERE id=?", septemberId))
        .isEqualTo(septemberBefore);
  }

  @Test
  void missingMonthAndCrossMonthPointersCannotSelectOrChangeAnotherMonth() throws Exception {
    assertThatThrownBy(() -> adapter.loadForCurrentBusinessUnit(itemId, null))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("多个核算月份");
    var august = adapter.load(itemId, "COMMERCIAL", "210", "2026-08");
    assertThatThrownBy(() -> adapter.attachPreparation(august, septemberId, "QUERYING", null, "系统", LocalDateTime.now()))
        .isInstanceOf(ElectronicDrawingWorkflowRetryException.class);
    var september = imports.importSource(command("2026-09"), acquired());
    jdbc.update("UPDATE lp_quote_bom_preparation_record SET electronic_source_version_id=? WHERE id=?",
        september.supplementVersionId(), augustId);
    assertThatThrownBy(() -> adapter.load(itemId, "COMMERCIAL", "210", "2026-08"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("核算月份不一致");
  }

  private Long preparation(Long formId, String oaNo, String month) {
    var row = new QuoteBomPreparationRecord();
    row.setOaFormId(formId); row.setOaFormItemId(itemId); row.setOaNo(oaNo);
    row.setQuoteProductCode("ED-P"); row.setProductType("NON_BARE");
    row.setPriceOrgCode("210"); row.setMaterialOrganizationCode("COMMERCIAL");
    row.setCostPeriodMonth(month); row.setPreparationStatus("NEED_TECH");
    row.setActiveFlag(1); row.setElectronicWorkflowVersion(0);
    preparations.insert(row);
    return row.getId();
  }

  private ElectronicDrawingSourceImportService.ImportCommand command(String month) {
    return new ElectronicDrawingSourceImportService.ImportCommand(itemId, "COMMERCIAL", "210", "ED-DRAW", month);
  }

  private ElectronicDrawingExcelAcquisitionPort.AcquiredExcel acquired() throws Exception {
    byte[] bytes;
    try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("Sheet");
      List<String> header = List.of("序号", "代号", "名称", "材料", "物料重要性分类", "HSF风险分类", "数量", "单重", "备注");
      List<String> values = List.of("1", "ED-CHILD", "接管", "铜", "B", "B", "2", "12", "自制");
      for (int i = 0; i < 2; i++) {
        var row = sheet.createRow(i); var data = i == 0 ? header : values;
        for (int j = 0; j < data.size(); j++) row.createCell(j).setCellValue(data.get(j));
      }
      workbook.write(output); bytes = output.toByteArray();
    }
    return new ElectronicDrawingExcelAcquisitionPort.AcquiredExcel(bytes, "drawing.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.length,
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        "ED-DRAW", "MONTH-TEST", "LOCAL_TEST", LocalDateTime.now());
  }
}
