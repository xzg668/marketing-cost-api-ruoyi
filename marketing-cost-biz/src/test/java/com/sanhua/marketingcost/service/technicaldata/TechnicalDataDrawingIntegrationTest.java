package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataDrawingRecheckRequest;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.drawing.*;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.electronicdrawing.*;
import com.sanhua.marketingcost.service.quotebom.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
class TechnicalDataDrawingIntegrationTest extends BomMapperTestBase {
  private static final TechnicalDataActor WANG =
      new TechnicalDataActor(101L, "王工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor LI =
      new TechnicalDataActor(102L, "李工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor ADMIN =
      new TechnicalDataActor(1L, "管理员", Set.of("*:*:*"));
  @Autowired private TechnicalDataDrawingApplicationService service;
  @Autowired private QuoteTechnicalDataPersistenceService persistence;
  @Autowired private QuoteTechnicalDataRepository repository;
  @Autowired private TechnicalDataSubmissionValidationService validation;
  @Autowired private TechnicalDataParticipantVersions versions;
  @Autowired private TechnicalDataVersionContentCodec codec;
  @Autowired private QuoteBomPreparationRecordMapper preparations;
  @Autowired private JdbcTemplate jdbc;
  @MockBean private DrawingVerificationClient acquisition;
  @MockBean private CurrentU9BomGateway u9;
  @Autowired private DrawingVerificationCodec drawingCodec;
  @Autowired private DrawingVerificationService verifications;
  @Autowired private DrawingReviewService reviews;
  @Autowired private DrawingResultConsumptionService consumption;
  @Autowired private ElectronicDrawingWorkflowContextPort drawingContexts;
  @Autowired private PlatformTransactionManager transactions;
  private Long taskId, productId, itemId;
  private String drawingNo;
  private String quantity;

  @BeforeEach
  void fixture() throws Exception {
    String key = "TW08-" + UUID.randomUUID();
    drawingNo = key + "-MX";
    jdbc.update(
        "INSERT INTO oa_form(oa_no,accounting_period_month,business_unit_type)"
            + " VALUES(?,'2026-09','COMMERCIAL')",
        key);
    Long formId = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update(
        "INSERT INTO"
            + " oa_form_item(oa_form_id,material_no,product_name,customer_drawing,business_unit_type)"
            + " VALUES(?,?,'测试产品',?,'COMMERCIAL')",
        formId,
        key,
        key);
    jdbc.update(
        "INSERT INTO"
            + " lp_material_master_raw(material_code,organization_code,material_name,drawing_no,active_flag,source_type,import_batch_id)"
            + " VALUES(?,'COMMERCIAL','测试产品',?,1,'U9_API','EDP-02')",
        key,
        key);
    itemId =
        jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, formId);
    var preparation = new QuoteBomPreparationRecord();
    preparation.setOaFormId(formId);
    preparation.setOaFormItemId(itemId);
    preparation.setOaNo(key);
    preparation.setQuoteProductCode(key);
    preparation.setProductType("NON_BARE");
    preparation.setCostPeriodMonth("2026-09");
    preparation.setPriceOrgCode("210");
    preparation.setMaterialOrganizationCode("COMMERCIAL");
    preparation.setPreparationStatus("NEED_TECH");
    preparation.setActiveFlag(1);
    preparation.setElectronicWorkflowVersion(0);
    preparations.insert(preparation);
    var task = new QuoteTechTask();
    task.setTaskNo(key);
    task.setOaFormId(formId);
    task.setOaFormItemId(itemId);
    task.setOaNo(key);
    task.setAccountingMonth("2026-09");
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("210");
    task.setAssigneeUserId(101L);
    task.setAssigneeName("王工");
    taskId = persistence.createTask(task).getId();
    var product = new QuoteTechProduct();
    product.setTaskId(taskId);
    product.setOaFormItemId(itemId);
    product.setQuoteNo(key);
    product.setAccountingMonth("2026-09");
    product.setContentSchemaVersion(2);
    product.setMaterialNo(key);
    product.setSourceFingerprint("a".repeat(64));
    product.setSourceSnapshotJson("{\"sourceModel\":\"OA-MODEL\"}");
    productId = persistence.createProduct(product).getId();
    for (String type : TechnicalDataModuleType.orderedCodes()) {
      boolean required = Set.of("DRAWING_BOM", "PROFILE").contains(type);
      var module = new QuoteTechModule();
      module.setProductId(productId);
      module.setModuleType(type);
      module.setRequiredFlag(required ? 1 : 0);
      module.setModuleStatus(required ? "PENDING" : "NOT_REQUIRED");
      module.setSourceAvailability(required ? "MISSING" : "AVAILABLE");
      module.setSourceReference("TEST-U9-NOT-FOUND");
      module.setSourceCheckedAt(LocalDateTime.now());
      module.setRequirementReasonCode("TEST_CHECK");
      module.setRequirementReason("实际无 U9 BOM");
      module.setEntryMode(required ? "MANUAL" : "NONE");
      module.setAssigneeUserId("PROFILE".equals(type) ? 102L : 101L);
      module.setAssigneeName("PROFILE".equals(type) ? "李工" : "王工");
      persistence.createModule(module);
    }
    quantity = "2";
    when(u9.read(any())).thenReturn(CurrentU9BomResult.notFound("explicit absence"));
    when(acquisition.verify(any())).thenAnswer(call -> payload(quantity));
  }

  @Test
  void confirmedConsumedResultCanBeAdoptedByTechnicalCheckWithoutAnotherVerify() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    doAnswer(
            call -> {
              var body =
                  (com.fasterxml.jackson.databind.node.ObjectNode)
                      mapper.readTree(payload("2").rawJson());
              body.put("differenceCount", 2);
              body.putObject("data");
              return drawingCodec.parse(body.toString());
            })
        .when(acquisition)
        .verify(any());
    var scope = drawingContexts.load(itemId, "COMMERCIAL", "210", "2026-09");
    var task = verifications.start(scope, drawingNo).orElseThrow();
    var confirmed = DrawingVerificationFixtures.confirmed(task.getExternalTaskId());
    confirmed.set("data", mapper.readTree(payload("2.5000").dataJson()));
    var receipt = reviews.receive(drawingCodec.parse(confirmed.toString()));
    assertThat(consumption.consume(task.getId()).consumptionState()).isEqualTo("COMPLETE");
    var stored = drawingContexts.load(itemId, "COMMERCIAL", "210", "2026-09");
    var checked = service.recheck(productId, request(0), WANG);
    assertThat(checked.acquired()).isTrue();
    assertThat(checked.drawing().sourceVersionId()).isEqualTo(stored.sourceVersionId());
    assertThat(checked.drawing().evidence().drawingResultId()).isEqualTo(receipt.resultId());
    assertThat(checked.drawing().nodes())
        .allSatisfy(row -> assertThat(row.quantityPerParent()).isEqualByComparingTo("2.5000"));
    assertThat(verifications.current(stored).orElseThrow().getId()).isEqualTo(task.getId());
    verify(acquisition, times(1)).verify(any());
  }

  @Test
  void
      jsonImportKeepsUnknownWeightUnitAndDuplicateRowsSeparateWithoutCompletingOtherPersonsModules() {
    var response = service.recheck(productId, request(0), WANG);
    assertThat(response.acquired()).isTrue();
    assertThat(response.materialsMatched()).isFalse();
    assertThat(response.bomPublished()).isFalse();
    assertThat(response.bomComposed()).isFalse();
    assertThat(response.drawing().nodes())
        .hasSize(2)
        .extracting(node -> node.sourceNodeId())
        .doesNotHaveDuplicates();
    assertThat(response.drawing().nodes())
        .allSatisfy(
            node -> {
              assertThat(node.sourceWeight()).isEqualByComparingTo("12");
              assertThat(node.sourceWeightUnit()).isNull();
              assertThat(node.quantityPerParent()).isEqualByComparingTo("2");
              assertThat(node.unit()).isNull();
            });
    assertThat(
            jdbc.queryForObject(
                "SELECT module_status FROM lp_quote_tech_module WHERE product_id=? AND"
                    + " module_type='PROFILE'",
                String.class,
                productId))
        .isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT source_availability FROM lp_quote_tech_module WHERE product_id=? AND"
                    + " module_type='DRAWING_BOM'",
                String.class,
                productId))
        .isEqualTo("MISSING");
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isTrue();
    var repeated = service.recheck(productId, request(response.expectedVersion()), WANG);
    assertThat(repeated.drawing().sourceVersionId())
        .isEqualTo(response.drawing().sourceVersionId());
    assertThat(repeated.drawing().nodes()).hasSize(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_electronic_drawing_source_node WHERE"
                    + " supplement_version_id=?",
                Integer.class,
                response.drawing().sourceVersionId()))
        .isEqualTo(2);
    verify(acquisition, times(1)).verify(any());
  }

  @Test
  void sourceUpdatePreservesOldRowsAndPersonalFrozenSnapshot() throws Exception {
    var first = service.recheck(productId, request(0), WANG);
    quantity = "3";
    explicitlyReacquire();
    var second = service.recheck(productId, request(first.expectedVersion()), WANG);
    assertThat(second.drawing().sourceVersionId()).isNotEqualTo(first.drawing().sourceVersionId());
    assertThat(
            jdbc.queryForObject(
                "SELECT MIN(qty) FROM lp_electronic_drawing_source_node WHERE"
                    + " supplement_version_id=?",
                String.class,
                first.drawing().sourceVersionId()))
        .startsWith("2.");
    var person =
        new Recipient(
            1L,
            taskId,
            1,
            101L,
            "王工",
            "wang",
            "FILL",
            List.of("DRAWING_BOM"),
            1L,
            "todo",
            "CONFIRMED",
            "OPEN",
            null,
            "工程部",
            "leader",
            "王总",
            null,
            0,
            0,
            null,
            true,
            null,
            null,
            "T-TEST",
            null);
    var frozen =
        new TransactionTemplate(transactions)
            .execute(
                status -> {
                  repository.lockTask(taskId).orElseThrow();
                  return versions.freeze(
                      repository.lockProduct(productId).orElseThrow(),
                      person,
                      second.expectedVersion(),
                      101L);
                });
    assertThat(codec.drawingBom(frozen)).isEqualTo(second.drawing());
    assertThat(service.read(productId, null, WANG).editable()).isFalse();
    assertThat(service.read(productId, frozen.getId(), WANG).drawing()).isEqualTo(second.drawing());
    assertThatThrownBy(
            () -> service.recheck(productId, request(second.expectedVersion() + 1), WANG))
        .isInstanceOf(TechnicalDataTaskException.class)
        .hasMessageContaining("已提交");
    assertThat(repository.findVersion(frozen.getId()).orElseThrow().getContentFingerprint())
        .isEqualTo(frozen.getContentFingerprint());
  }

  @Test
  void failedRecheckRetainsContentButBlocksSubmissionAndCanRecover() {
    var saved = service.recheck(productId, request(0), WANG);
    doThrow(
            new DrawingVerificationException(
                DrawingVerificationException.Code.HTTP_ERROR, "该产品明细已移走", 404, null, null))
        .when(acquisition)
        .verify(any());
    explicitlyReacquire();
    var failed = service.recheck(productId, request(saved.expectedVersion()), WANG);
    assertThat(failed.acquired()).isFalse();
    assertThat(failed.message()).contains("已移走");
    assertThat(failed.drawing()).isEqualTo(saved.drawing());
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isFalse();
    assertThatThrownBy(() -> service.recheck(productId, request(saved.expectedVersion()), WANG))
        .hasMessageContaining("刷新");
    doAnswer(call -> payload("2")).when(acquisition).verify(any());
    explicitlyReacquire();
    var recovered = service.recheck(productId, request(failed.expectedVersion()), WANG);
    assertThat(recovered.acquired()).isTrue();
    assertThat(recovered.drawing().sourceVersionId())
        .isNotEqualTo(saved.drawing().sourceVersionId());
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isTrue();
  }

  @Test
  void otherAssigneeAdminAndForgedCompletionAreRejectedWhileOwnerCanRecheck() {
    assertThat(service.read(productId, null, LI).editable()).isFalse();
    assertThatThrownBy(() -> service.recheck(productId, request(0), LI))
        .hasMessageContaining("不属于本人");
    var forged = request(0);
    forged.unknown("acquired", true);
    assertThatThrownBy(() -> service.recheck(productId, forged, WANG))
        .hasMessageContaining("不接受客户端");
    assertThat(service.read(productId, null, ADMIN).editable()).isFalse();
    assertThatThrownBy(() -> service.recheck(productId, request(0), ADMIN))
        .hasMessageContaining("不属于本人");
    verifyNoInteractions(acquisition);
    var saved = service.recheck(productId, request(0), WANG);
    assertThat(saved.acquired()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT assignee_user_id FROM lp_quote_tech_module WHERE product_id=? AND"
                    + " module_type='DRAWING_BOM'",
                Long.class,
                productId))
        .isEqualTo(101L);
    assertThat(repository.findVersion(saved.versionId()).orElseThrow().getUpdatedBy())
        .isEqualTo(101L);
  }

  @Test
  void missingMasterDrawingStopsBeforeAcquisitionAndVersionWrite() {
    String material = jdbc.queryForObject(
        "SELECT material_no FROM oa_form_item WHERE id=?", String.class, itemId);
    assertThat(jdbc.update(
        "UPDATE lp_material_master_raw SET drawing_no=NULL WHERE material_code=? AND"
            + " organization_code='COMMERCIAL'",
        material)).isEqualTo(1);
    assertThatThrownBy(() -> service.recheck(productId, request(0), WANG))
        .hasMessageContaining("未查到");
    verifyNoInteractions(acquisition);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_quote_bom_supplement_version WHERE oa_form_item_id=?",
                Integer.class,
                itemId))
        .isZero();
  }

  private TechnicalDataDrawingRecheckRequest request(int expected) {
    var request = new TechnicalDataDrawingRecheckRequest();
    request.setExpectedVersion(expected);
    request.setMaintained(true);
    request.setDrawingNo(drawingNo);
    return request;
  }

  private void explicitlyReacquire() {
    var scope = drawingContexts.load(itemId, "COMMERCIAL", "210", "2026-09");
    var current = verifications.current(scope).orElseThrow();
    verifications.restart(scope, drawingNo, current.getId());
  }

  private DrawingVerificationPayload payload(String quantity) {
    return drawingCodec.parse(
        DrawingVerificationFixtures.drawing(
                "TW08-" + UUID.randomUUID(),
                drawingNo,
                List.of(
                    List.of("001", "TW08-SAME-DRAWING", "接管", quantity, "铜", "B", "B", "12", ""),
                    List.of("001", "TW08-SAME-DRAWING", "接管", quantity, "铜", "B", "B", "12", "")))
            .toString());
  }
}
