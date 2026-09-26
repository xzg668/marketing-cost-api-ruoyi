package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataDrawingRecheckRequest;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingExcelAcquisitionPort;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingExcelAcquisitionException;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
class TechnicalDataDrawingIntegrationTest extends BomMapperTestBase {
  private static final TechnicalDataActor WANG = new TechnicalDataActor(101L, "王工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor LI = new TechnicalDataActor(102L, "李工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor ADMIN = new TechnicalDataActor(1L, "管理员", Set.of("*:*:*"));
  @Autowired private TechnicalDataDrawingApplicationService service;
  @Autowired private QuoteTechnicalDataPersistenceService persistence;
  @Autowired private QuoteTechnicalDataRepository repository;
  @Autowired private TechnicalDataSubmissionValidationService validation;
  @Autowired private TechnicalDataParticipantVersions versions;
  @Autowired private TechnicalDataVersionContentCodec codec;
  @Autowired private QuoteBomPreparationRecordMapper preparations;
  @Autowired private JdbcTemplate jdbc;
  @MockBean private ElectronicDrawingExcelAcquisitionPort acquisition;
  private Long taskId, productId, itemId;
  private String drawingNo;
  private ElectronicDrawingExcelAcquisitionPort.AcquiredExcel initialExcel;

  @BeforeEach void fixture() throws Exception {
    String key = "TW08-" + UUID.randomUUID(); drawingNo = key;
    jdbc.update("INSERT INTO oa_form(oa_no,accounting_period_month,business_unit_type) VALUES(?,'2026-09','COMMERCIAL')", key);
    Long formId = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,customer_drawing,business_unit_type) VALUES(?,? ,?,'COMMERCIAL')", formId, key, key);
    itemId = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, formId);
    var preparation = new QuoteBomPreparationRecord();
    preparation.setOaFormId(formId); preparation.setOaFormItemId(itemId); preparation.setOaNo(key);
    preparation.setQuoteProductCode(key); preparation.setProductType("NON_BARE");
    preparation.setCostPeriodMonth("2026-09"); preparation.setPriceOrgCode("210"); preparation.setMaterialOrganizationCode("COMMERCIAL");
    preparation.setPreparationStatus("NEED_TECH"); preparation.setActiveFlag(1); preparation.setElectronicWorkflowVersion(0);
    preparations.insert(preparation);
    var task = new QuoteTechTask(); task.setTaskNo(key); task.setOaFormId(formId); task.setOaFormItemId(itemId);
    task.setOaNo(key); task.setAccountingMonth("2026-09"); task.setBusinessUnitType("COMMERCIAL"); task.setApplicableOrgCode("210");
    task.setAssigneeUserId(101L); task.setAssigneeName("王工"); taskId = persistence.createTask(task).getId();
    var product = new QuoteTechProduct(); product.setTaskId(taskId); product.setOaFormItemId(itemId); product.setQuoteNo(key);
    product.setAccountingMonth("2026-09"); product.setContentSchemaVersion(2); product.setMaterialNo(key);
    product.setSourceFingerprint("a".repeat(64)); product.setSourceSnapshotJson("{\"sourceModel\":\"OA-MODEL\"}");
    productId = persistence.createProduct(product).getId();
    for (String type : TechnicalDataModuleType.orderedCodes()) {
      boolean required = Set.of("DRAWING_BOM", "PROFILE").contains(type);
      var module = new QuoteTechModule(); module.setProductId(productId); module.setModuleType(type);
      module.setRequiredFlag(required ? 1 : 0); module.setModuleStatus(required ? "PENDING" : "NOT_REQUIRED");
      module.setSourceAvailability(required ? "MISSING" : "AVAILABLE"); module.setSourceReference("TEST-U9-NOT-FOUND");
      module.setSourceCheckedAt(LocalDateTime.now()); module.setRequirementReasonCode("TEST_CHECK"); module.setRequirementReason("实际无 U9 BOM");
      module.setEntryMode(required ? "MANUAL" : "NONE"); module.setAssigneeUserId("PROFILE".equals(type) ? 102L : 101L);
      module.setAssigneeName("PROFILE".equals(type) ? "李工" : "王工"); persistence.createModule(module);
    }
    initialExcel = excel("2");
    when(acquisition.acquire(any())).thenReturn(initialExcel);
  }

  @Test void realImportKeepsGramValuesAndDuplicateDrawingsSeparateAndDoesNotCompleteOtherPersonsModules() {
    var response = service.recheck(productId, request(0), WANG);
    assertThat(response.acquired()).isTrue(); assertThat(response.materialsMatched()).isFalse();
    assertThat(response.bomPublished()).isFalse(); assertThat(response.bomComposed()).isFalse();
    assertThat(response.drawing().nodes()).hasSize(2).extracting(node -> node.sourceNodeId()).doesNotHaveDuplicates();
    assertThat(response.drawing().nodes()).allSatisfy(node -> {
      assertThat(node.sourceWeight()).isEqualByComparingTo("12"); assertThat(node.sourceWeightUnit()).isEqualTo("g");
      assertThat(node.quantityPerParent()).isEqualByComparingTo("2"); assertThat(node.unit()).isNull();
    });
    assertThat(jdbc.queryForObject("SELECT module_status FROM lp_quote_tech_module WHERE product_id=? AND module_type='PROFILE'", String.class, productId)).isEqualTo("PENDING");
    assertThat(jdbc.queryForObject("SELECT source_availability FROM lp_quote_tech_module WHERE product_id=? AND module_type='DRAWING_BOM'", String.class, productId)).isEqualTo("MISSING");
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isTrue();
    var repeated = service.recheck(productId, request(response.expectedVersion()), WANG);
    assertThat(repeated.drawing().sourceVersionId()).isEqualTo(response.drawing().sourceVersionId());
    assertThat(repeated.drawing()).isEqualTo(response.drawing());
  }

  @Test void sourceUpdatePreservesOldRowsAndPersonalFrozenSnapshot() throws Exception {
    var first = service.recheck(productId, request(0), WANG);
    when(acquisition.acquire(any())).thenReturn(excel("3"));
    var second = service.recheck(productId, request(first.expectedVersion()), WANG);
    assertThat(second.drawing().sourceVersionId()).isNotEqualTo(first.drawing().sourceVersionId());
    assertThat(jdbc.queryForObject("SELECT MIN(qty) FROM lp_electronic_drawing_source_node WHERE supplement_version_id=?", String.class, first.drawing().sourceVersionId())).startsWith("2.");
    var person = new Recipient(1L, taskId, 1, 101L, "王工", "wang", "FILL", List.of("DRAWING_BOM"), 1L,
        "todo", "CONFIRMED", "OPEN", null, "工程部", "leader", "王总", null, 0, 0, null, true, null, null, "T-TEST", null);
    var frozen = versions.freeze(repository.lockProduct(productId).orElseThrow(), person, second.expectedVersion(), 101L);
    assertThat(codec.drawingBom(frozen)).isEqualTo(second.drawing());
    assertThat(service.read(productId, null, WANG).editable()).isFalse();
    assertThat(service.read(productId, frozen.getId(), WANG).drawing()).isEqualTo(second.drawing());
    assertThatThrownBy(() -> service.recheck(productId, request(second.expectedVersion() + 1), WANG))
        .isInstanceOf(TechnicalDataTaskException.class).hasMessageContaining("已提交");
    assertThat(repository.findVersion(frozen.getId()).orElseThrow().getContentFingerprint()).isEqualTo(frozen.getContentFingerprint());
  }

  @Test void failedRecheckRetainsContentButBlocksSubmissionAndCanRecover() {
    var saved = service.recheck(productId, request(0), WANG);
    when(acquisition.acquire(any())).thenThrow(new ElectronicDrawingExcelAcquisitionException(
        ElectronicDrawingExcelAcquisitionException.BOM_NOT_FOUND, false, "该产品明细已移走"));
    var failed = service.recheck(productId, request(saved.expectedVersion()), WANG);
    assertThat(failed.acquired()).isFalse(); assertThat(failed.message()).contains("已移走");
    assertThat(failed.drawing()).isEqualTo(saved.drawing());
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isFalse();
    assertThatThrownBy(() -> service.recheck(productId, request(saved.expectedVersion()), WANG)).hasMessageContaining("刷新");
    doReturn(initialExcel).when(acquisition).acquire(any());
    var recovered = service.recheck(productId, request(failed.expectedVersion()), WANG);
    assertThat(recovered.acquired()).isTrue();
    assertThat(recovered.drawing().sourceVersionId()).isEqualTo(saved.drawing().sourceVersionId());
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isTrue();
  }

  @Test void otherAssigneeAndForgedCompletionAreRejectedWhileAdminKeepsOriginalOwner() {
    assertThat(service.read(productId, null, LI).editable()).isFalse();
    assertThatThrownBy(() -> service.recheck(productId, request(0), LI)).hasMessageContaining("不属于本人");
    var forged = request(0); forged.unknown("acquired", true);
    assertThatThrownBy(() -> service.recheck(productId, forged, WANG)).hasMessageContaining("不接受客户端");
    verifyNoInteractions(acquisition);
    var saved = service.recheck(productId, request(0), ADMIN);
    assertThat(saved.acquired()).isTrue();
    assertThat(jdbc.queryForObject("SELECT assignee_user_id FROM lp_quote_tech_module WHERE product_id=? AND module_type='DRAWING_BOM'", Long.class, productId)).isEqualTo(101L);
    assertThat(repository.findVersion(saved.versionId()).orElseThrow().getUpdatedBy()).isEqualTo(1L);
  }

  private TechnicalDataDrawingRecheckRequest request(int expected) {
    var request = new TechnicalDataDrawingRecheckRequest(); request.setExpectedVersion(expected);
    request.setMaintained(true); request.setDrawingNo(drawingNo); return request;
  }

  private ElectronicDrawingExcelAcquisitionPort.AcquiredExcel excel(String quantity) throws Exception {
    byte[] bytes;
    try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("明细");
      var headers = List.of("序号", "代号", "名称", "材料", "物料重要性分类", "HSF风险分类", "数量", "单重", "备注");
      for (int row = 0; row < 3; row++) {
        var values = row == 0 ? headers : List.of(String.valueOf(row), "TW08-SAME-DRAWING", "接管", "铜", "B", "B", quantity, "12", "");
        var target = sheet.createRow(row);
        for (int col = 0; col < values.size(); col++) target.createCell(col).setCellValue(values.get(col));
      }
      workbook.write(out); bytes = out.toByteArray();
    }
    return new ElectronicDrawingExcelAcquisitionPort.AcquiredExcel(bytes, "drawing.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.length,
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), drawingNo,
        "TW08-REQUEST", "TEST", LocalDateTime.of(2026, 9, 16, 10, 0));
  }
}
