package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataDrawingRecheckRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataManufacturingResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataManufacturingSaveRequest;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingExcelAcquisitionPort;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.*;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
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
class TechnicalDataManufacturingIntegrationTest extends BomMapperTestBase {
  private static final TechnicalDataActor WANG = new TechnicalDataActor(101L, "王工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor LI = new TechnicalDataActor(102L, "李工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor ADMIN = new TechnicalDataActor(1L, "管理员", Set.of("*:*:*"));
  @Autowired private TechnicalDataManufacturingApplicationService service;
  @Autowired private TechnicalDataDrawingApplicationService drawings;
  @Autowired private QuoteTechnicalDataPersistenceService persistence;
  @Autowired private QuoteTechnicalDataRepository repository;
  @Autowired private TechnicalDataSubmissionValidationService validation;
  @Autowired private TechnicalDataParticipantVersions versions;
  @Autowired private TechnicalDataVersionContentCodec codec;
  @Autowired private QuoteBomPreparationRecordMapper preparations;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingSourceNodeRepository sourceNodes;
  @Autowired private com.sanhua.marketingcost.mapper.QuoteTechModuleMapper moduleMapper;
  @Autowired private com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort contexts;
  @Autowired private TechnicalDataManufacturingBomSource manufacturingBom;
  @MockBean private ElectronicDrawingExcelAcquisitionPort acquisition;
  @MockBean private ElectronicDrawingU9SubBomPort u9;
  private Long taskId, productId, itemId, sourceId;
  private String key;

  @BeforeEach void fixture() throws Exception {
    key = "TW09-" + UUID.randomUUID().toString().substring(0, 8);
    jdbc.update("INSERT INTO oa_form(oa_no,accounting_period_month,business_unit_type) VALUES(?,'2026-09','COMMERCIAL')", key);
    Long formId = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,customer_drawing,business_unit_type) VALUES(?,?,?,'COMMERCIAL')", formId, key, key);
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
      boolean required = Set.of("DRAWING_BOM", "MANUFACTURING", "PROFILE").contains(type);
      var module = new QuoteTechModule(); module.setProductId(productId); module.setModuleType(type);
      module.setRequiredFlag(required ? 1 : 0); module.setModuleStatus(required ? "PENDING" : "NOT_REQUIRED");
      module.setSourceAvailability(required ? "MISSING" : "AVAILABLE"); module.setSourceReference("TEST-U9-NOT-FOUND");
      module.setSourceCheckedAt(LocalDateTime.now()); module.setRequirementReasonCode("TEST_CHECK"); module.setRequirementReason("实际缺失原料关系");
      module.setEntryMode(required ? "MANUAL" : "NONE"); module.setAssigneeUserId("PROFILE".equals(type) ? 102L : 101L);
      module.setAssigneeName("PROFILE".equals(type) ? "李工" : "王工"); persistence.createModule(module);
    }
    material(key, key, "制造件", "只"); material(key + "-A", key + "-DA", "制造件", "只");
    material(key + "-B", key + "-DB", "制造件", "只"); material(key + "-RAW", key + "-DR", "采购件", "kg");
    when(u9.query(any())).thenAnswer(call -> {
      SubBomQuery query = call.getArgument(0);
      if (!query.parentMaterialCode().equals(key + "-A")) return SubBomResult.failure(Status.NOT_FOUND, query.parentMaterialCode(), "无正式下级");
      return SubBomResult.available(key + "-A", "210", "COMMERCIAL", List.of(new U9Node("u1", null, 101L, 1001L,
          key + "-RAW", "已有原料", "T2", null, key + "-DR", "采购件", "111001008", "RAW", "RAW",
          "主制造", "V1", new BigDecimal("0.015"), BigDecimal.ONE, "kg", 1)));
    });
    when(acquisition.acquire(any())).thenReturn(excel());
    var input = new TechnicalDataDrawingRecheckRequest(); input.setExpectedVersion(0); input.setMaintained(true); input.setDrawingNo(key);
    sourceId = drawings.recheck(productId, input, WANG).drawing().sourceVersionId();
  }

  @Test void partialSaveSurvivesFinanceResolutionAndComposesExactParentsWithoutMultiplyingSingleWeightTwice() {
    var first = service.read(productId, null, WANG);
    assertThat(first.source().nodes()).extracting(node -> node.state().name()).containsExactly("U9_READY", "MISSING_RAW", "MISSING_RAW", "WAIT_FINANCE");
    var partial = service.save(productId, request(first, true), WANG);
    assertThat(partial.priceRequirements()).hasSize(1).allSatisfy(row -> assertThat(row.status()).isEqualTo("MISSING_ROUTE"));
    assertThat(partial.issues()).isNotEmpty(); assertThat(partial.bomComposed()).isFalse();
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isFalse();
    confirmC();
    var current = service.read(productId, null, WANG);
    assertThat(current.saved()).isEqualTo(partial.saved());
    var saved = service.save(productId, request(current, true), WANG);
    assertThat(saved.source()).as("单纯保存原材料不能改动图库或 U9 来源").isEqualTo(current.source());
    assertThat(saved.issues()).isEmpty(); assertThat(saved.bomComposed()).isTrue();
    assertThat(saved.calculationInputs()).allSatisfy(row -> {
      assertThat(row.grossWeightG()).isEqualByComparingTo("12"); assertThat(row.netWeightG()).isEqualByComparingTo("10");
      assertThat(row.netLengthMm()).isEqualByComparingTo("120"); assertThat(row.purchasingQuantity()).isEqualByComparingTo("0.012");
    });
    var rows = jdbc.queryForList("SELECT parent_code,path,source_electronic_node_id,qty_per_parent,qty_per_top,manual_flag FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=? AND node_source_type='TECH_RAW' ORDER BY line_no", sourceId);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("qty_per_top").toString()).startsWith("0.048");
    assertThat(rows.get(1).get("qty_per_top").toString()).startsWith("0.036");
    assertThat(rows.get(0).get("path")).isNotEqualTo(rows.get(1).get("path"));
    assertThat(jdbc.queryForObject("SELECT module_status FROM lp_quote_tech_module WHERE product_id=? AND module_type='PROFILE'", String.class, productId)).isEqualTo("PENDING");
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isTrue();
    assertThat(service.save(productId, request(saved, true), WANG).bomComposed()).isTrue();
  }

  @Test void deletingDraftRawClearsCompositionAndCannotSubmitAnEmptyGapWhileFrozenSnapshotSurvivesReturn() {
    confirmC(); var saved = service.save(productId, request(service.read(productId, null, WANG), true), WANG);
    var person = new Recipient(1L, taskId, 1, 101L, "王工", "wang", "FILL", List.of("DRAWING_BOM", "MANUFACTURING"), 1L,
        "todo", "CONFIRMED", "OPEN", null, "工程部", "leader", "王总", null, 0, 0, null, true, null, null, "T-TEST", null);
    var frozen = versions.freeze(repository.lockProduct(productId).orElseThrow(), person, saved.expectedVersion(), 101L);
    String fingerprint = frozen.getContentFingerprint();
    assertThat(service.read(productId, null, WANG).editable()).isFalse();
    versions.restore(repository.lockProduct(productId).orElseThrow(), person, frozen.getId(), 101L);
    var removed = service.save(productId, request(service.read(productId, null, WANG), false), WANG);
    assertThat(removed.saved().items()).isEmpty(); assertThat(removed.issues()).isNotEmpty(); assertThat(removed.bomComposed()).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=?", Long.class, sourceId)).isZero();
    assertThat(jdbc.queryForObject("SELECT composition_fingerprint FROM lp_quote_bom_supplement_version WHERE id=?", String.class, sourceId)).isNull();
    assertThat(repository.findVersion(frozen.getId()).orElseThrow().getContentFingerprint()).isEqualTo(fingerprint);
    assertThat(codec.manufacturing(repository.findVersion(frozen.getId()).orElseThrow())).isEqualTo(saved.saved());
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isFalse();
  }

  @Test void authorizationOriginSpoofingAndDuplicateParentAreRejectedAndAdminCannotWrite() {
    var state = service.read(productId, null, WANG);
    assertThat(service.read(productId, null, LI).editable()).isFalse();
    assertThatThrownBy(() -> service.save(productId, request(state, true), LI)).hasMessageContaining("未分派给本人");
    var duplicate = request(state, true); duplicate.setItems(List.of(duplicate.getItems().getFirst(), duplicate.getItems().getFirst()));
    assertThatThrownBy(() -> service.save(productId, duplicate, WANG)).hasMessageContaining("只能补一种原材料");
    var forged = request(state, true); forged.getItems().getFirst().unknown("netWeightKg", "0.001");
    assertThatThrownBy(() -> service.save(productId, forged, WANG)).hasMessageContaining("来源字段");
    assertThatThrownBy(() -> service.save(productId, request(state, true), ADMIN)).hasMessageContaining("未分派");
    var saved = service.save(productId, request(state, true), WANG);
    assertThat(repository.findVersion(saved.versionId()).orElseThrow().getUpdatedBy()).isEqualTo(101L);
    assertThat(jdbc.queryForObject("SELECT assignee_user_id FROM lp_quote_tech_module WHERE product_id=? AND module_type='MANUFACTURING'", Long.class, productId)).isEqualTo(101L);
  }

  @Test void fulfilledFormalRequirementStopsConsumingRetainedTechnicalDraft() {
    var saved = service.save(productId, request(service.read(productId, null, WANG), true), WANG);
    var module = moduleMapper.selectByTaskId(taskId).stream().filter(row -> "MANUFACTURING".equals(row.getModuleType())).findFirst().orElseThrow();
    module.setRequiredFlag(0); module.setSourceAvailability("AVAILABLE"); module.setModuleStatus("NOT_REQUIRED");
    moduleMapper.updateById(module);
    assertThat(manufacturingBom.load(contexts.load(itemId, "COMMERCIAL", "210", "2026-09"))).isEmpty();
    var viewed = service.read(productId, null, WANG);
    assertThat(viewed.editable()).isFalse(); assertThat(viewed.issues()).isEmpty();
    assertThat(viewed.saved()).isEqualTo(saved.saved());
  }

  private void confirmC() {
    material(key + "-C", key + "-DC", "采购件", "只");
    Long node = jdbc.queryForObject("SELECT id FROM lp_electronic_drawing_source_node WHERE supplement_version_id=? AND source_sequence='4'", Long.class, sourceId);
    assertThat(sourceNodes.updateResolution(node, "UNMATCHED", "MANUALLY_SELECTED", key + "-C", "FINANCE", LocalDateTime.now())).isTrue();
  }

  private TechnicalDataManufacturingSaveRequest request(TechnicalDataManufacturingResponse state, boolean fill) {
    var request = new TechnicalDataManufacturingSaveRequest(); request.setExpectedVersion(state.expectedVersion());
    request.setSourceVersionId(state.source().sourceVersionId()); request.setSourceFingerprint(state.source().fingerprint());
    request.setItems(fill ? state.source().nodes().stream().filter(row -> row.state() == TechnicalDataManufacturingSourceQuery.State.MISSING_RAW).map(row -> {
      var item = new TechnicalDataManufacturingSaveRequest.Item(); item.setParentSourceNodeId(row.sourceNodeId()); item.setRawMaterialNo(key + "-RAW");
      item.setNetLengthMm(new BigDecimal("120")); item.setGrossWeightKg(new BigDecimal("0.012")); return item;
    }).toList() : List.of());
    return request;
  }

  private void material(String code, String drawing, String shape, String unit) {
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,drawing_no,material_name,shape_attr,unit,organization_code,import_batch_id,source_type,active_flag,main_category_code) VALUES(?,?,?,?,?,'COMMERCIAL',?,'EXCEL',1,'111001008')", code, drawing, code, shape, unit, key);
  }

  private ElectronicDrawingExcelAcquisitionPort.AcquiredExcel excel() throws Exception {
    byte[] bytes;
    try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("明细");
      var headers = List.of("序号", "代号", "名称", "材料", "物料重要性分类", "HSF风险分类", "数量", "单重", "备注");
      var header = sheet.createRow(0); for (int col = 0; col < headers.size(); col++) header.createCell(col).setCellValue(headers.get(col));
      for (int row = 1; row <= 4; row++) {
        var values = List.of(String.valueOf(row), key + (row == 1 ? "-DA" : row == 4 ? "-DC" : "-DB"), "接管" + row, "铜", "B", "B", row == 2 ? "4" : "3", "10", "");
        var target = sheet.createRow(row); for (int col = 0; col < values.size(); col++) target.createCell(col).setCellValue(values.get(col));
      }
      workbook.write(out); bytes = out.toByteArray();
    }
    return new ElectronicDrawingExcelAcquisitionPort.AcquiredExcel(bytes, "drawing.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        bytes.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), key, key, "TEST", LocalDateTime.now());
  }
}
