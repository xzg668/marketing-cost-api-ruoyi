package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSolderSource.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
class TechnicalDataSolderApplicationIntegrationTest extends BomMapperTestBase {
  private static final TechnicalDataActor WANG = new TechnicalDataActor(101L, "王工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor LI = new TechnicalDataActor(102L, "李工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor ADMIN = new TechnicalDataActor(1L, "管理员", Set.of("*:*:*"));
  @Autowired private TechnicalDataSolderApplicationService service;
  @Autowired private QuoteTechnicalDataPersistenceService persistence;
  @Autowired private QuoteTechnicalDataRepository repository;
  @Autowired private TechnicalDataSubmissionValidationService validation;
  @Autowired private TechnicalDataParticipantVersions versions;
  @Autowired private TechnicalDataSubmissionSummary summaries;
  @Autowired private TechnicalDataVersionContentCodec codec;
  @Autowired private BomRawHierarchyMapper hierarchy;
  @Autowired private MaterialMasterRawMapper materials;
  @Autowired private JdbcTemplate jdbc;
  private Long taskId, productId, rootId, weldId;
  private String key;

  @BeforeEach void fixture() {
    key = "TW12-" + UUID.randomUUID().toString().substring(0, 8);
    var task = new QuoteTechTask(); task.setTaskNo(key); task.setOaFormId(10L); task.setOaFormItemId(System.nanoTime());
    task.setOaNo(key); task.setAccountingMonth("2026-09"); task.setBusinessUnitType("COMMERCIAL"); task.setApplicableOrgCode("210");
    task.setAssigneeUserId(101L); task.setAssigneeName("王工"); taskId = persistence.createTask(task).getId();
    var product = new QuoteTechProduct(); product.setTaskId(taskId); product.setOaFormItemId(task.getOaFormItemId()); product.setQuoteNo(key);
    product.setAccountingMonth("2026-09"); product.setContentSchemaVersion(2); product.setMaterialNo(key);
    product.setSourceFingerprint("a".repeat(64)); product.setSourceSnapshotJson("{\"sourceModel\":\"OA-MODEL\"}");
    productId = persistence.createProduct(product).getId();
    for (String type : TechnicalDataModuleType.orderedCodes()) {
      boolean required = Set.of("SOLDER", "PROFILE").contains(type);
      var module = new QuoteTechModule(); module.setProductId(productId); module.setModuleType(type);
      module.setRequiredFlag(required ? 1 : 0); module.setModuleStatus(required ? "PENDING" : "NOT_REQUIRED");
      module.setSourceAvailability(required ? "MISSING" : "AVAILABLE"); module.setSourceReference("TEST-U9-ORIGINAL-MISSING");
      module.setSourceCheckedAt(LocalDateTime.now()); module.setRequirementReasonCode("TEST_CHECK"); module.setRequirementReason("无原始 U9 BOM，需补焊料");
      module.setEntryMode(required ? "MANUAL" : "NONE"); module.setAssigneeUserId("PROFILE".equals(type) ? 102L : 101L);
      module.setAssigneeName("PROFILE".equals(type) ? "李工" : "王工"); persistence.createModule(module);
    }
    material("TOP", "成品", "101", "只", "TOP-DRAWING");
    material("ASM", "组件", "101", "只", null);
    weldId = material("WELD", "测试焊丝", "181811432", "千克", "WELD-DRAWING");
    material("GRAM", "焊环", "181811986", "g", null);
    material("PASTE", "焊膏", "181811435", "千克", "PASTE-DRAWING");
    material("PIECE", "按只管理焊料", "181811986", "只", null);
    rootId = node("TOP", "TOP", 0, path("TOP"), "1", "1");
    node("TOP", "ASM", 1, path("TOP", "ASM"), "3", "3");
    node("TOP", "WELD", 1, path("TOP", "WELD"), "0.0002", "0.0002");
    node("ASM", "WELD", 2, path("TOP", "ASM", "WELD"), "0.0002", "0.0006");
    node("TOP", "GRAM", 1, path("TOP", "GRAM"), "0.15", "0.15");
    node("TOP", "PASTE", 1, path("TOP", "PASTE"), "9", "9");
  }

  @Test void referenceReadsAllLevelsAndSeparateOccurrencesInKgUsingActualMaterialDrawing() {
    var source = source();
    assertThat(source.items()).hasSize(3);
    var welds = source.items().stream().filter(row -> row.materialNo().endsWith("WELD")).toList();
    assertThat(welds).hasSize(2).extracting(row -> row.quantityPerProduct()).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
        .containsExactlyInAnyOrder(new BigDecimal("0.0002"), new BigDecimal("0.0006"));
    assertThat(welds).extracting(row -> row.itemKey()).doesNotHaveDuplicates();
    assertThat(welds).allSatisfy(row -> assertThat(row.drawingNo()).isEqualTo("WELD-DRAWING"));
    var gram = source.items().stream().filter(row -> row.materialNo().endsWith("GRAM")).findFirst().orElseThrow();
    assertThat(gram.quantityPerProduct()).isEqualByComparingTo("0.00015");
    assertThat(gram.evidence().bom().quantityPerTop()).isEqualByComparingTo("0.15");
    assertThat(gram.drawingNo()).isNull();
    var request = reference(source, 0); request.setItems(request.getItems().subList(0, 2));
    assertThat(service.save(productId, request, WANG).content().items()).hasSize(2);
    assertThat(source().items()).hasSize(3);
  }

  @Test void personalFreezeAndTargetedReturnKeepOldDrawingAndOnlyReportChangedQuantity() throws Exception {
    var request = reference(source(), 0); request.getItems().getFirst().setQuantityPerProduct(new BigDecimal("0.009"));
    var saved = service.save(productId, request, WANG);
    assertThat(saved.issues()).isEmpty(); assertThat(validation.validate(taskId, 101L, WANG).issues()).isEmpty();
    var first = versions.freeze(repository.lockProduct(productId).orElseThrow(), recipient(), saved.expectedVersion(), 101L);
    String firstContent = content(first);
    assertThat(service.get(productId, null, WANG).editable()).isFalse();
    versions.restore(repository.lockProduct(productId).orElseThrow(), recipient(), first.getId(), 101L);
    request.setExpectedVersion(service.get(productId, null, WANG).expectedVersion());
    request.getItems().getFirst().setQuantityPerProduct(new BigDecimal("0.01"));
    var resaved = service.save(productId, request, WANG);
    var second = versions.freeze(repository.lockProduct(productId).orElseThrow(), recipient(), resaved.expectedVersion(), 101L);
    var changes = new com.fasterxml.jackson.databind.ObjectMapper().readTree(summaries.summarize(content(second), firstContent)).get(0).get("changes");
    assertThat(changes.size()).isOne(); assertThat(changes.get(0).get("field").asText()).endsWith(".quantityPerProduct");
    var master = materials.selectById(weldId); master.setDrawingNo("LATER-DRAWING"); materials.updateById(master);
    assertThat(content(repository.findVersion(first.getId()).orElseThrow())).isEqualTo(firstContent);
    assertThat(codec.solder(repository.findVersion(first.getId()).orElseThrow()).items()).filteredOn(row -> row.materialNo().endsWith("WELD"))
        .allSatisfy(row -> assertThat(row.drawingNo()).isEqualTo("WELD-DRAWING"));
    assertThat(repository.findProduct(productId).orElseThrow().getEffectiveVersionId()).isNull();
  }

  @Test void lookupDistinguishesMissingDrawingUnknownMaterialExcludedCategoryAndInvalidUnit() {
    assertThat(service.material(productId, key + "-WELD", WANG).status()).isEqualTo("FOUND");
    assertThat(service.material(productId, key + "-GRAM", WANG).status()).isEqualTo("NO_DRAWING");
    assertThat(service.material(productId, key + "-UNKNOWN", WANG).status()).isEqualTo("NOT_FOUND");
    assertThat(service.material(productId, key + "-PASTE", WANG).status()).isEqualTo("NOT_SOLDER");
    assertThat(service.material(productId, key + "-PIECE", WANG).status()).isEqualTo("UNIT_UNSUPPORTED");
    var saved = service.save(productId, manual("GRAM", "0.0004", 0), WANG);
    assertThat(saved.content().items().getFirst().drawingNo()).isNull(); assertThat(saved.issues()).isEmpty();
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isTrue();
    var master = materials.selectById(weldId); master.setActiveFlag(0); materials.updateById(master);
    assertThat(service.material(productId, key + "-WELD", WANG).status()).isEqualTo("NOT_FOUND");
  }

  @Test void cannotForgeSourcesEditAnotherPersonOrSubmitEmptyQuantity() {
    var input = manual("WELD", null, 0);
    assertThatThrownBy(() -> service.save(productId, input, LI)).hasMessageContaining("未分派给本人");
    var forged = manual("WELD", "0.01", 0); forged.getItems().getFirst().addUnknownField("drawingNo", null);
    assertThatThrownBy(() -> service.save(productId, forged, WANG)).hasMessageContaining("不支持的字段");
    var duplicate = manual("WELD", "0.01", 0); duplicate.setItems(List.of(duplicate.getItems().getFirst(), duplicate.getItems().getFirst()));
    assertThatThrownBy(() -> service.save(productId, duplicate, WANG)).hasMessageContaining("重复");
    assertThatThrownBy(() -> service.save(productId, input, ADMIN)).hasMessageContaining("未分派");
    var saved = service.save(productId, input, WANG);
    assertThat(saved.moduleStatus()).isEqualTo("EDITING"); assertThat(validation.validate(taskId, 101L, WANG).valid()).isFalse();
    assertThatThrownBy(() -> versions.freeze(repository.lockProduct(productId).orElseThrow(), recipient(), saved.expectedVersion(), 101L)).hasMessageContaining("校验");
    assertThat(repository.findVersion(saved.draftVersionId()).orElseThrow().getUpdatedBy()).isEqualTo(101L);
    assertThatThrownBy(() -> service.save(productId, manual("WELD", "0.02", 0), WANG)).hasMessageContaining("其他会话");
  }

  @Test void currentSourceChangesBlockSubmissionWithoutOverwritingDraftAndRespectBusinessScope() {
    var input = reference(source(), 0);
    var saved = service.save(productId, input, WANG);
    var master = materials.selectById(weldId); master.setDrawingNo("NEW"); materials.updateById(master);
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isFalse();
    input.setExpectedVersion(saved.expectedVersion());
    assertThatThrownBy(() -> service.save(productId, input, WANG)).hasMessageContaining("已变化");
    assertThat(service.get(productId, null, WANG).content().items()).filteredOn(row -> row.materialNo().endsWith("WELD"))
        .allSatisfy(row -> assertThat(row.drawingNo()).isEqualTo("WELD-DRAWING"));
    var otherBusiness = hierarchy.selectById(rootId); otherBusiness.setBusinessUnitType("PLATE"); hierarchy.updateById(otherBusiness);
    assertThat(service.references(productId, key + "-TOP", WANG)).isEmpty();
  }

  private Reference source() { return service.references(productId, key + "-TOP", WANG).getFirst(); }
  private TechnicalDataSolderSaveRequest reference(Reference source, int expected) {
    var input = new TechnicalDataSolderSaveRequest(); input.setExpectedVersion(expected); input.setEntryMode("REFERENCE");
    input.setReferenceMaterialNo(source.evidence().materialNo()); input.setReferenceFingerprint(source.evidence().fingerprint());
    input.setItems(source.items().stream().map(row -> { var item = new TechnicalDataSolderSaveRequest.Item(); item.setItemKey(row.itemKey()); item.setQuantityPerProduct(row.quantityPerProduct()); return item; }).toList()); return input;
  }
  private TechnicalDataSolderSaveRequest manual(String suffix, String quantity, int expected) {
    var input = new TechnicalDataSolderSaveRequest(); input.setExpectedVersion(expected); input.setEntryMode("MANUAL");
    var item = new TechnicalDataSolderSaveRequest.Item(); item.setMaterialNo(key + "-" + suffix);
    item.setMaterialFingerprint(service.material(productId, item.getMaterialNo(), WANG).material().fingerprint());
    item.setQuantityPerProduct(quantity == null ? null : new BigDecimal(quantity)); input.setItems(List.of(item)); return input;
  }
private Recipient recipient() { return new Recipient(1L, taskId, 1, 101L, "王工", "wang", "FILL", List.of("SOLDER"), 1L, "todo", "CONFIRMED", "OPEN", null, "工程部", "leader", "王总", null, 0, 0, null, true, null, null, "T-TEST", null); }
  private String content(QuoteTechDataVersion version) { return codec.versionContentJson(version, codec.readReferenceSnapshot(version.getReferenceSnapshotJson()), List.of(), List.of(), List.of()); }
  private Long material(String suffix, String name, String category, String unit, String drawing) {
    var row = new MaterialMasterRaw(); row.setMaterialCode(key + "-" + suffix); row.setMaterialName(name); row.setMaterialModel(key + "-MODEL-" + suffix);
    row.setDrawingNo(drawing); row.setShapeAttr("采购件"); row.setMainCategoryCode(category); row.setUnit(unit); row.setOrganizationCode("COMMERCIAL");
    row.setActiveFlag(1); row.setImportBatchId(key); row.setSourceType("EXCEL"); materials.insert(row); return row.getId();
  }
  private String path(String... suffixes) { return "/" + String.join("/", java.util.Arrays.stream(suffixes).map(suffix -> key + "-" + suffix).toList()) + "/"; }
  private Long node(String parent, String suffix, int level, String path, String quantity, String total) {
    var row = new BomRawHierarchy(); row.setTopProductCode(key + "-TOP"); row.setParentCode(key + "-" + parent); row.setMaterialCode(key + "-" + suffix);
    row.setLevel(level); row.setPath(path); row.setSortSeq(1); row.setQtyPerParent(new BigDecimal(quantity)); row.setQtyPerTop(new BigDecimal(total));
    row.setPriceOrgCode("210"); row.setBusinessUnitType("COMMERCIAL"); row.setSourceType("U9"); row.setBomPurpose("主制造"); row.setBomVersion("V1");
    row.setEffectiveTo(java.time.LocalDate.of(9999,12,31)); row.setEffectiveFrom(LocalDate.of(2026, 9, 1)); row.setBuildBatchId(key); row.setBuiltAt(LocalDateTime.now()); row.setSourceImportBatchId(key); hierarchy.insert(row); return row.getId();
  }
}
