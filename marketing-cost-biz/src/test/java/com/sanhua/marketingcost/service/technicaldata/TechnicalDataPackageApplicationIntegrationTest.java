package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPackageReferenceResponse.*;
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
class TechnicalDataPackageApplicationIntegrationTest extends BomMapperTestBase {
  private static final TechnicalDataActor WANG = new TechnicalDataActor(101L, "王工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor LI = new TechnicalDataActor(102L, "李工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor ADMIN = new TechnicalDataActor(1L, "管理员", Set.of("*:*:*"));
  @Autowired private TechnicalDataPackageApplicationService service;
  @Autowired private TechnicalDataPackageSourceQuery sources;
  @Autowired private QuoteTechnicalDataPersistenceService persistence;
  @Autowired private QuoteTechnicalDataRepository repository;
  @Autowired private TechnicalDataSubmissionValidationService validation;
  @Autowired private TechnicalDataParticipantVersions versions;
  @Autowired private TechnicalDataSubmissionSummary summaries;
  @Autowired private TechnicalDataVersionContentCodec codec;
  @Autowired private BomRawHierarchyMapper hierarchy;
  @Autowired private MaterialMasterRawMapper materials;
  @Autowired private JdbcTemplate jdbc;
  private Long taskId, productId, parent1, parent2, child1, child2;
  private String key;

  @BeforeEach void fixture() {
    key = "TW10-" + UUID.randomUUID().toString().substring(0, 8);
    var task = new QuoteTechTask(); task.setTaskNo(key); task.setOaFormId(10L); task.setOaFormItemId(System.nanoTime());
    task.setOaNo(key); task.setAccountingMonth("2026-09"); task.setBusinessUnitType("COMMERCIAL"); task.setApplicableOrgCode("210");
    task.setAssigneeUserId(101L); task.setAssigneeName("王工"); taskId = persistence.createTask(task).getId();
    var product = new QuoteTechProduct(); product.setTaskId(taskId); product.setOaFormItemId(task.getOaFormItemId()); product.setQuoteNo(key);
    product.setAccountingMonth("2026-09"); product.setContentSchemaVersion(2); product.setMaterialNo(key);
    product.setSourceFingerprint("a".repeat(64)); product.setSourceSnapshotJson("{\"sourceModel\":\"OA-MODEL\"}");
    productId = persistence.createProduct(product).getId();
    for (String type : TechnicalDataModuleType.orderedCodes()) {
      boolean required = Set.of("PACKAGE", "PROFILE").contains(type);
      var module = new QuoteTechModule(); module.setProductId(productId); module.setModuleType(type);
      module.setRequiredFlag(required ? 1 : 0); module.setModuleStatus(required ? "PENDING" : "NOT_REQUIRED");
      module.setSourceAvailability(required ? "MISSING" : "AVAILABLE"); module.setSourceReference("TEST-U9-PACKAGE-MISSING");
      module.setSourceCheckedAt(LocalDateTime.now()); module.setRequirementReasonCode("TEST_CHECK"); module.setRequirementReason("缺包装关系");
      module.setEntryMode(required ? "MANUAL" : "NONE"); module.setAssigneeUserId("PROFILE".equals(type) ? 102L : 101L);
      module.setAssigneeName("PROFILE".equals(type) ? "李工" : "王工"); persistence.createModule(module);
    }
    material("T1", "成品一", "成品", "只"); material("T2", "成品二", "成品", "只");
    material("P", "测试包装", "包装组件", "套"); material("C", "纸箱", "包装材料", "张");
    node("T1", "T1", "T1", 0, "/" + key + "-T1/", "1", "1");
    node("T2", "T2", "T2", 0, "/" + key + "-T2/", "1", "1");
    parent1 = node("T1", "T1", "P", 1, "/" + key + "-T1/" + key + "-P/", "1", "1");
    parent2 = node("T2", "T2", "P", 1, "/" + key + "-T2/" + key + "-P/", "2", "2");
    child1 = node("T1", "P", "C", 2, "/" + key + "-T1/" + key + "-P/" + key + "-C/", "0.5", "0.5");
    child2 = node("T2", "P", "C", 2, "/" + key + "-T2/" + key + "-P/" + key + "-C/", "0.5", "1");
  }

  @Test void realBomGroupsComponentButPreservesEachProductQuantityAndIgnoresExpiredOrOtherOrganization() {
    var components = service.references(productId, key + "-P", WANG).components();
    assertThat(components).hasSize(1);
    assertThat(components.getFirst().sources()).hasSize(2).extracting(s -> s.evidence().parentQuantity())
        .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class).containsExactly(new BigDecimal("1"), new BigDecimal("2"));
    assertThat(service.references(productId, key + "-T2", WANG).components().getFirst().sources()).hasSize(1);
    assertThat(service.references(productId, key + "-SPEC-T2", WANG).components().getFirst().sources()).hasSize(1);
    var children = service.children(productId, key + "-C", WANG);
    assertThat(children).hasSize(2).allSatisfy(row -> assertThat(row.source().parentMaterialNo()).isEqualTo(key + "-P"));
    assertThat(service.children(productId, key + "-T1", WANG)).isEmpty();
    var parent = hierarchy.selectById(parent2); parent.setEffectiveTo(LocalDate.of(2026, 8, 31)); hierarchy.updateById(parent);
    assertThat(service.references(productId, key + "-P", WANG).components().getFirst().sources()).hasSize(1);
    assertThatThrownBy(() -> service.save(productId, reference(components.getFirst().sources().get(1), "2"), WANG)).hasMessageContaining("失效");
    var foreign = hierarchy.selectById(parent1); foreign.setPriceOrgCode("220"); hierarchy.updateById(foreign);
    assertThatThrownBy(() -> service.save(productId, reference(components.getFirst().sources().get(0), "1"), WANG)).hasMessageContaining("不属于本次组织");
  }

  @Test void legacyU9WithoutBusinessUnitUsesItsExplicitPriceOrganization() {
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET business_unit_type=NULL WHERE top_product_code=?", key + "-T2");
    var source = service.references(productId, key + "-T2", WANG).components().getFirst().sources().getFirst();
    assertThat(source.evidence().parentQuantity()).isEqualByComparingTo("2");
    assertThat(service.save(productId, reference(source, "2"), WANG).issues()).isEmpty();
    assertThat(service.children(productId, key + "-C", WANG)).hasSize(2);
    hierarchy.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<BomRawHierarchy>lambdaUpdate()
        .eq(BomRawHierarchy::getTopProductCode, key + "-T2").set(BomRawHierarchy::getBusinessUnitType, "PLATE"));
    assertThat(service.references(productId, key + "-T2", WANG).components()).isEmpty();
    assertThatThrownBy(() -> sources.require(repository.findTask(taskId).orElseThrow(),
        repository.findProduct(productId).orElseThrow(), parent2, source.evidence().fingerprint()))
        .hasMessageContaining("不属于本次组织");
  }

  @Test void parentAndChildAreSavedSeparatelyAndSubmittedSnapshotSurvivesParentOnlyReturn() throws Exception {
    var source = service.references(productId, key + "-T2", WANG).components().getFirst().sources().getFirst();
    var saved = service.save(productId, reference(source, "2"), WANG);
    assertThat(saved.issues()).isEmpty();
    assertThat(saved.items().getFirst().quantity()).isEqualByComparingTo("0.5");
    assertThat(saved.items().getFirst().quantityPerProduct()).isEqualByComparingTo("1");
    var persisted = repository.findPackageItems(saved.draftVersionId()).getFirst();
    assertThat(persisted.getStandardQuantity()).isEqualByComparingTo("0.5");
    assertThat(persisted.getPriceBasisType()).isNull(); assertThat(persisted.getReferenceUnitPrice()).isNull(); assertThat(persisted.getAmount()).isNull();
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isTrue();
    var person = recipient();
    var first = versions.freeze(repository.lockProduct(productId).orElseThrow(), person, saved.expectedVersion(), 101L);
    String firstContent = content(first);
    versions.restore(repository.lockProduct(productId).orElseThrow(), person, first.getId(), 101L);
    var changed = reference(source, "3"); changed.setExpectedVersion(service.get(productId, null, WANG).expectedVersion());
    var resaved = service.save(productId, changed, WANG);
    assertThat(resaved.packaging().sourceParentQuantity()).isEqualByComparingTo("2");
    assertThat(resaved.items().getFirst().quantityPerProduct()).isEqualByComparingTo("1.5");
    var second = versions.freeze(repository.lockProduct(productId).orElseThrow(), person, resaved.expectedVersion(), 101L);
    assertThat(content(repository.findVersion(first.getId()).orElseThrow())).isEqualTo(firstContent);
    var diff = new com.fasterxml.jackson.databind.ObjectMapper().readTree(summaries.summarize(content(second), firstContent));
    var changes = diff.get(0).get("changes"); assertThat(changes.size()).isOne();
    assertThat(changes.get(0).get("field").asText()).isEqualTo("packaging.parentQuantity");
    assertThat(hierarchy.selectById(parent2).getQtyPerTop()).isEqualByComparingTo("2");
  }

  @Test void emptyParentRemainsDraftAndManualModelGeneratesSkuWithoutInventingPriceOrReference() {
    var input = manual("BOX-MODEL", null);
    var draft = service.save(productId, input, WANG);
    assertThat(draft.moduleStatus()).isEqualTo("EDITING"); assertThat(draft.packaging().parentQuantity()).isNull();
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isFalse();
    assertThatThrownBy(() -> versions.freeze(repository.lockProduct(productId).orElseThrow(), recipient(), draft.expectedVersion(), 101L)).hasMessageContaining("校验");
    var zero = manual("BOX-MODEL", "0"); zero.setExpectedVersion(draft.expectedVersion());
    assertThatThrownBy(() -> service.save(productId, zero, WANG)).hasMessageContaining("母件用量必须大于 0");
    var complete = manual("BOX-CHANGED", "2"); complete.setExpectedVersion(draft.expectedVersion());
    var saved = service.save(productId, complete, WANG);
    assertThat(saved.items().getFirst().componentMaterialNo()).isEqualTo("BOX-CHANGED");
    assertThat(saved.items().getFirst().componentModel()).isEqualTo("BOX-CHANGED");
    assertThat(saved.items().getFirst().quantity()).isEqualByComparingTo("0.5");
    assertThat(saved.items().getFirst().quantityPerProduct()).isEqualByComparingTo("1");
    assertThat(saved.packaging().source()).isNull(); assertThat(saved.issues()).isEmpty();
  }

  @Test void sourcesAndPermissionsAreCheckedAgainAtSaveAndSubmission() {
    var source = service.references(productId, key + "-T1", WANG).components().getFirst().sources().getFirst();
    assertThat(service.get(productId, null, LI).editable()).isFalse();
    assertThatThrownBy(() -> service.save(productId, reference(source, "1"), LI)).hasMessageContaining("未分派给本人");
    var forged = reference(source, "1"); forged.getItems().getFirst().addUnknownField("priceBasisType", "PENDING_INQUIRY");
    assertThatThrownBy(() -> service.save(productId, forged, WANG)).hasMessageContaining("未知字段");
    assertThatThrownBy(() -> service.save(productId, reference(source, "1"), ADMIN)).hasMessageContaining("未分派");
    var saved = service.save(productId, reference(source, "1"), WANG);
    assertThat(repository.findVersion(saved.draftVersionId()).orElseThrow().getUpdatedBy()).isEqualTo(101L);
    assertThat(repository.lockModules(productId).stream().filter(m -> "PACKAGE".equals(m.getModuleType())).findFirst().orElseThrow().getAssigneeUserId()).isEqualTo(101L);
    var child = hierarchy.selectById(child1); child.setQtyPerParent(new BigDecimal("0.75")); hierarchy.updateById(child);
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isFalse();
    var stale = reference(source, "1"); stale.setExpectedVersion(saved.expectedVersion());
    assertThatThrownBy(() -> service.save(productId, stale, WANG)).hasMessageContaining("已变化");
    assertThat(service.get(productId, null, WANG).items().getFirst().quantity()).isEqualByComparingTo("0.5");
  }

  private TechnicalDataPackageSaveRequest reference(Source source, String parent) {
    var input = new TechnicalDataPackageSaveRequest(); input.setExpectedVersion(0); input.setEntryMode("REFERENCE");
    input.setParentQuantity(new BigDecimal(parent)); input.setReferenceParentNodeId(source.evidence().parentNodeId()); input.setReferenceFingerprint(source.evidence().fingerprint());
    var child = source.children().getFirst(); var row = new TechnicalDataPackageItemRequest(); row.setSourceParentNodeId(source.evidence().parentNodeId());
    row.setSourceNodeId(child.sourceNodeId()); row.setSourceFingerprint(source.evidence().fingerprint()); row.setQuantity(child.quantity()); input.setItems(List.of(row)); return input;
  }
  private TechnicalDataPackageSaveRequest manual(String model, String parent) {
    var input = new TechnicalDataPackageSaveRequest(); input.setExpectedVersion(0); input.setEntryMode("MANUAL"); input.setParentQuantity(parent == null ? null : new BigDecimal(parent));
    var row = new TechnicalDataPackageItemRequest(); row.setComponentModel(model); row.setComponentName("新纸箱"); row.setQuantity(new BigDecimal("0.5")); row.setUnit("张"); input.setItems(List.of(row)); return input;
  }
private Recipient recipient() { return new Recipient(1L, taskId, 1, 101L, "王工", "wang", "FILL", List.of("PACKAGE"), 1L, "todo", "CONFIRMED", "OPEN", null, "工程部", "leader", "王总", null, 0, 0, null, true, null, null, "T-TEST", null); }
  private String content(QuoteTechDataVersion version) { return codec.versionContentJson(version, codec.readReferenceSnapshot(version.getReferenceSnapshotJson()), repository.findPackageItems(version.getId()), List.of(), List.of()); }
  private void material(String suffix, String name, String category, String unit) {
    var material = new MaterialMasterRaw(); material.setMaterialCode(key + "-" + suffix); material.setMaterialName(name); material.setMaterialModel(key + "-MODEL-" + suffix);
    material.setMaterialSpec(key + "-SPEC-" + suffix); material.setDrawingNo(key + "-DRAWING-" + suffix); material.setShapeAttr(category.equals("包装组件") ? "虚拟件" : "采购件"); material.setMainCategoryName(category);
    material.setUnit(unit); material.setOrganizationCode("COMMERCIAL"); material.setActiveFlag(1); material.setImportBatchId(key); material.setSourceType("EXCEL"); materials.insert(material);
  }
  private Long node(String top, String parent, String suffix, int level, String path, String quantity, String total) {
    var row = new BomRawHierarchy(); row.setTopProductCode(key + "-" + top); row.setParentCode(key + "-" + parent); row.setMaterialCode(key + "-" + suffix);
    row.setLevel(level); row.setPath(path); row.setSortSeq(1); row.setQtyPerParent(new BigDecimal(quantity)); row.setQtyPerTop(new BigDecimal(total));
    row.setPriceOrgCode("210"); row.setBusinessUnitType("COMMERCIAL"); row.setSourceType("U9"); row.setBomPurpose("主制造"); row.setBomVersion("V1");
    row.setEffectiveFrom(LocalDate.of(2026, 9, 1)); row.setBuildBatchId(key); row.setBuiltAt(LocalDateTime.now()); row.setSourceImportBatchId(key); hierarchy.insert(row); return row.getId();
  }
}
