package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.integration.drawing.*;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.quotebom.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
class QuoteBomElectronicDrawingContextIntegrationTest extends BomMapperTestBase {
  @Autowired private QuoteBomElectronicDrawingContextAdapter adapter;
  @Autowired private ElectronicDrawingSourceImportService imports;
  @Autowired private ElectronicDrawingSourceNodeRepository nodes;
  @Autowired private QuoteBomPreparationRecordMapper preparations;
  @Autowired private JdbcTemplate jdbc;
  @Autowired DrawingVerificationService verifications;
  @Autowired DrawingVerificationCodec codec;
  @MockBean DrawingVerificationClient client;
  @MockBean CurrentU9BomGateway u9;
  private String material;
  private Long itemId;
  private Long augustId;
  private Long septemberId;

  @BeforeEach
  void fixture() {
    String oaNo = "ED-MONTH-" + UUID.randomUUID();
    material = oaNo;
    when(u9.read(any())).thenReturn(CurrentU9BomResult.notFound("explicit absence"));
    when(client.verify(any()))
        .thenAnswer(
            call ->
                codec.parse(
                    DrawingVerificationFixtures.drawing(
                            "context-" + UUID.randomUUID(),
                            "ED-DRAW",
                            List.of(
                                List.of("001", "ED-CHILD", "接管", "2", "铜", "B", "B", "12", "自制")))
                        .toString()));
    jdbc.update(
        "INSERT INTO oa_form(oa_no,accounting_period_month,business_unit_type)"
            + " VALUES(?,'2026-09','COMMERCIAL')",
        oaNo);
    Long formId = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, oaNo);
    jdbc.update(
        "INSERT INTO"
            + " oa_form_item(oa_form_id,material_no,product_name,customer_drawing,business_unit_type)"
            + " VALUES(?,?,'热力膨胀阀','ED-DRAW','COMMERCIAL')",
        formId,
        material);
    itemId =
        jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, formId);
    jdbc.update(
        "INSERT INTO"
            + " lp_material_master_raw(material_code,organization_code,material_name,drawing_no,active_flag,source_type,import_batch_id)"
            + " VALUES(?,'COMMERCIAL','热力膨胀阀','ED-DRAW',1,'U9_API','EDP-06')",
        material);
    augustId = preparation(formId, oaNo, "2026-08");
    septemberId = preparation(formId, oaNo, "2026-09");
  }

  @Test
  void rejectsCrossBusinessUnitAndPriceOrganizationBeforeReturningContext() {
    assertThatThrownBy(() -> adapter.load(itemId, "OTHER", "210", "2026-08"))
        .hasMessageContaining("业务单元不一致");
    assertThatThrownBy(() -> adapter.load(itemId, "COMMERCIAL", "999", "2026-08"))
        .hasMessageContaining("组织不一致");
  }

  @Test
  void importingEqualDrawingDataKeepsMonthResultsSeparateAndConsumptionIdempotent()
      throws Exception {
    var augustResult = acquired("2026-08");
    var septemberResult = acquired("2026-09");
    var august = imports.importSource(command("2026-08"), augustResult);
    var september = imports.importSource(command("2026-09"), septemberResult);
    assertThat(august.supplementVersionId()).isNotEqualTo(september.supplementVersionId());
    assertThat(imports.importSource(command("2026-08"), augustResult).supplementVersionId())
        .isEqualTo(august.supplementVersionId());
    assertThat(adapter.load(itemId, "COMMERCIAL", "210", "2026-08").sourceVersionId())
        .isEqualTo(august.supplementVersionId());
    assertThat(adapter.load(itemId, "COMMERCIAL", "210", "2026-09").sourceVersionId())
        .isEqualTo(september.supplementVersionId());
    assertThat(nodes.findByVersionId(august.supplementVersionId()))
        .singleElement()
        .satisfies(
            node -> {
              assertThat(node.getReferenceWeight()).isEqualByComparingTo("12");
              assertThat(node.getReferenceWeightUnit()).isNull();
              assertThat(node.getQty()).isEqualByComparingTo("2");
            });
  }

  @Test
  void longQuoteNumberCanImportAndRecheckWithoutChangingTheDisplayedNumber() throws Exception {
    String longOaNo = "OA-" + "X".repeat(61);
    jdbc.update(
        "UPDATE oa_form SET oa_no=? WHERE id=(SELECT oa_form_id FROM oa_form_item WHERE id=?)",
        longOaNo,
        itemId);
    jdbc.update(
        "UPDATE lp_quote_bom_preparation_record SET oa_no=? WHERE oa_form_item_id=?",
        longOaNo,
        itemId);

    var imported = imports.importSource(command("2026-09"), acquired("2026-09"));
    var context = adapter.load(itemId, "COMMERCIAL", "210", "2026-09");

    assertThat(context.oaNo()).isEqualTo(longOaNo);
    assertThat(context.taskNo()).hasSizeLessThanOrEqualTo(64);
    assertThat(
            jdbc.queryForObject(
                "SELECT task_no FROM lp_quote_bom_supplement_version WHERE id=?",
                String.class,
                imported.supplementVersionId()))
        .isEqualTo(context.taskNo());
    assertThat(imports.importSource(command("2026-09"), acquired("2026-09")).supplementVersionId())
        .isEqualTo(imported.supplementVersionId());
  }

  @Test
  void stageReloadAndOptimisticUpdatesStayInTheSelectedMonth() {
    var septemberBefore =
        jdbc.queryForMap("SELECT * FROM lp_quote_bom_preparation_record WHERE id=?", septemberId);
    var august = adapter.load(itemId, "COMMERCIAL", "210", "2026-08");
    var updated = adapter.updateStage(august, "MATCHING", 42L, "报价员", LocalDateTime.now());
    assertThat(updated.accountingMonth()).isEqualTo("2026-08");
    assertThat(updated.preparationId()).isEqualTo(augustId);
    assertThat(updated.workflowStage()).isEqualTo("MATCHING");
    assertThat(updated.revision()).isEqualTo(august.revision() + 1);
    assertThatThrownBy(
            () -> adapter.updateStage(august, "PUBLISHED", null, "系统", LocalDateTime.now()))
        .isInstanceOf(ElectronicDrawingWorkflowRetryException.class);
    assertThat(
            jdbc.queryForMap(
                "SELECT * FROM lp_quote_bom_preparation_record WHERE id=?", septemberId))
        .isEqualTo(septemberBefore);
  }

  @Test
  void missingMonthAndCrossMonthPointersCannotSelectOrChangeAnotherMonth() throws Exception {
    assertThatThrownBy(() -> adapter.loadForCurrentBusinessUnit(itemId, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("多个核算月份");
    var august = adapter.load(itemId, "COMMERCIAL", "210", "2026-08");
    assertThatThrownBy(
            () ->
                adapter.attachPreparation(
                    august, septemberId, "QUERYING", null, "系统", LocalDateTime.now()))
        .isInstanceOf(ElectronicDrawingWorkflowRetryException.class);
    var september = imports.importSource(command("2026-09"), acquired("2026-09"));
    jdbc.update(
        "UPDATE lp_quote_bom_preparation_record SET electronic_source_version_id=? WHERE id=?",
        september.supplementVersionId(),
        augustId);
    assertThatThrownBy(() -> adapter.load(itemId, "COMMERCIAL", "210", "2026-08"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("核算月份不一致");
  }

  private Long preparation(Long formId, String oaNo, String month) {
    var row = new QuoteBomPreparationRecord();
    row.setOaFormId(formId);
    row.setOaFormItemId(itemId);
    row.setOaNo(oaNo);
    row.setQuoteProductCode(material);
    row.setProductType("NON_BARE");
    row.setPriceOrgCode("210");
    row.setMaterialOrganizationCode("COMMERCIAL");
    row.setCostPeriodMonth(month);
    row.setPreparationStatus("NEED_TECH");
    row.setActiveFlag(1);
    row.setElectronicWorkflowVersion(0);
    preparations.insert(row);
    return row.getId();
  }

  private ElectronicDrawingWorkContext command(String month) {
    return adapter.load(itemId, "COMMERCIAL", "210", month);
  }

  private Long acquired(String month) {
    return verifications.start(command(month), null).orElseThrow().getCurrentResultId();
  }
}
