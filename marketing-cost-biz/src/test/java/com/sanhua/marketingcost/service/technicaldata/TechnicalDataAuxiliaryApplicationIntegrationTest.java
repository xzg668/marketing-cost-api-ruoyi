package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.math.BigDecimal;
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
class TechnicalDataAuxiliaryApplicationIntegrationTest extends BomMapperTestBase {
  private static final TechnicalDataActor WANG = new TechnicalDataActor(101L, "王工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor LI = new TechnicalDataActor(102L, "李工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor ADMIN = new TechnicalDataActor(1L, "管理员", Set.of("*:*:*"));
  @Autowired private TechnicalDataAuxiliaryApplicationService service;
  @Autowired private QuoteTechnicalDataPersistenceService persistence;
  @Autowired private QuoteTechnicalDataRepository repository;
  @Autowired private TechnicalDataSubmissionValidationService validation;
  @Autowired private TechnicalDataParticipantVersions versions;
  @Autowired private TechnicalDataSubmissionSummary summaries;
  @Autowired private TechnicalDataVersionContentCodec codec;
  @Autowired private CmsCostSourceEffectiveMapper cms;
  @Autowired private JdbcTemplate jdbc;
  private Long taskId, productId;
  private String key;

  @BeforeEach void fixture() {
    key = "TW11-" + UUID.randomUUID().toString().substring(0, 8);
    var task = new QuoteTechTask(); task.setTaskNo(key); task.setOaFormId(10L); task.setOaFormItemId(System.nanoTime());
    task.setOaNo(key); task.setAccountingMonth("2026-09"); task.setBusinessUnitType("COMMERCIAL"); task.setApplicableOrgCode("210");
    task.setAssigneeUserId(101L); task.setAssigneeName("王工"); taskId = persistence.createTask(task).getId();
    var product = new QuoteTechProduct(); product.setTaskId(taskId); product.setOaFormItemId(task.getOaFormItemId()); product.setQuoteNo(key);
    product.setAccountingMonth("2026-09"); product.setContentSchemaVersion(2); product.setMaterialNo(key);
    product.setSourceFingerprint("a".repeat(64)); product.setSourceSnapshotJson("{\"sourceModel\":\"OA-MODEL\"}");
    productId = persistence.createProduct(product).getId();
    for (String type : TechnicalDataModuleType.orderedCodes()) {
      boolean required = Set.of("AUXILIARY", "PROFILE").contains(type);
      var module = new QuoteTechModule(); module.setProductId(productId); module.setModuleType(type);
      module.setRequiredFlag(required ? 1 : 0); module.setModuleStatus(required ? "PENDING" : "NOT_REQUIRED");
      module.setSourceAvailability(required ? "MISSING" : "AVAILABLE"); module.setSourceReference("TEST-U9-ORIGINAL-MISSING");
      module.setSourceCheckedAt(LocalDateTime.now()); module.setRequirementReasonCode("TEST_CHECK"); module.setRequirementReason("原始 U9 BOM 不存在，需补辅料");
      module.setEntryMode(required ? "MANUAL" : "NONE"); module.setAssigneeUserId("PROFILE".equals(type) ? 102L : 101L);
      module.setAssigneeName("PROFILE".equals(type) ? "李工" : "王工"); persistence.createModule(module);
    }
    cmsSource("01", "清洗类", "10", "2026-08");
    cmsSource("02", "刀具类", "0.25", "2026-09");
  }

  @Test void directlyEditedAmountAndOriginalRemainDistinctAcrossFreezeAndTargetedReturn() throws Exception {
    var source = service.references(productId, key + "-REF", WANG).getFirst();
    var saved = service.save(productId, reference(source, "12", 0), WANG);
    assertThat(saved.moduleStatus()).isEqualTo("READY");
    assertThat(saved.totalAmount()).isEqualByComparingTo("12.25");
    assertThat(saved.items().getFirst().sourceAmount()).isEqualByComparingTo("10");
    assertThat(saved.items().getFirst().amount()).isEqualByComparingTo("12");
    var row = repository.findAuxItems(saved.draftVersionId()).getFirst();
    assertThat(row.getQuantity()).isNull(); assertThat(row.getReferenceUnitPrice()).isNull(); assertThat(row.getAuxiliaryMaterialNo()).isNull();
    assertThat(validation.validate(taskId, 101L, WANG).issues()).isEmpty();
    var first = versions.freeze(repository.lockProduct(productId).orElseThrow(), recipient(), saved.expectedVersion(), 101L);
    String content = content(first);
    assertThat(service.get(productId, null, WANG).editable()).isFalse();
    assertThatThrownBy(() -> service.save(productId, reference(source, "15", service.get(productId, null, WANG).expectedVersion()), WANG)).hasMessageContaining("已送审");
    versions.restore(repository.lockProduct(productId).orElseThrow(), recipient(), first.getId(), 101L);
    var second = service.save(productId, reference(source, "15", service.get(productId, null, WANG).expectedVersion()), WANG);
    assertThat(second.items().getFirst().sourceAmount()).isEqualByComparingTo("10");
    assertThat(content(repository.findVersion(first.getId()).orElseThrow())).isEqualTo(content);
    assertThat(repository.findAuxItems(first.getId()).getFirst().getAmount()).isEqualByComparingTo("12");
    assertThat(repository.lockModules(productId).stream().filter(m -> "PROFILE".equals(m.getModuleType())).findFirst().orElseThrow().getModuleStatus()).isEqualTo("PENDING");
    assertThat(cms.selectById(source.items().getFirst().sourceId()).getAmountYuan()).isEqualByComparingTo("10");
  }

  @Test void missingAmountSavesOnlyDraftAndZeroCanBeExplicitlyConfirmed() {
    var source = service.references(productId, key + "-REF", WANG).getFirst();
    var incomplete = service.save(productId, reference(source, null, 0), WANG);
    assertThat(incomplete.totalAmount()).isNull(); assertThat(incomplete.moduleStatus()).isEqualTo("EDITING");
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isFalse();
    assertThatThrownBy(() -> versions.freeze(repository.lockProduct(productId).orElseThrow(), recipient(), incomplete.expectedVersion(), 101L)).hasMessageContaining("尚未全部完成");
    var zero = service.save(productId, reference(source, "0", incomplete.expectedVersion()), WANG);
    assertThat(zero.items().getFirst().amount()).isEqualByComparingTo("0");
    assertThat(validation.validate(taskId, 101L, WANG).issues()).isEmpty();
  }

  @Test void rejectsCrossPersonWriteStaleSourcesUnknownFieldsAndPartialSourceWithoutMutation() {
    var source = service.references(productId, key + "-REF", WANG).getFirst();
    assertThatThrownBy(() -> service.save(productId, reference(source, "12", 0), LI)).hasMessageContaining("未分派给本人");
    var forged = reference(source, "12", 0); forged.getItems().getFirst().addUnknownField("multiplier", 2);
    assertThatThrownBy(() -> service.save(productId, forged, WANG)).hasMessageContaining("不支持");
    var omitted = reference(source, "12", 0); omitted.setItems(omitted.getItems().subList(0, 1));
    assertThatThrownBy(() -> service.save(productId, omitted, WANG)).hasMessageContaining("完整明细");
    assertThatThrownBy(() -> service.save(productId, reference(source, "-1", 0), WANG)).hasMessageContaining("大于等于 0");
    assertThatThrownBy(() -> service.save(productId, reference(source, "12", 0), ADMIN)).hasMessageContaining("未分派");
    var saved = service.save(productId, reference(source, "12", 0), WANG);
    assertThat(repository.findVersion(saved.draftVersionId()).orElseThrow().getUpdatedBy()).isEqualTo(101L);
    assertThatThrownBy(() -> service.save(productId, reference(source, "15", 0), WANG)).hasMessageContaining("其他会话");
    var changed = cms.selectById(source.items().getFirst().sourceId()); changed.setAmountYuan(new BigDecimal("20")); cms.updateById(changed);
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isFalse();
    assertThatThrownBy(() -> service.save(productId, reference(source, "15", saved.expectedVersion()), WANG)).hasMessageContaining("已变化");
    assertThat(service.get(productId, null, WANG).items().getFirst().amount()).isEqualByComparingTo("12");
  }

  @Test void uploadRetainsOriginalFormAndAllowsBlankFinanceSubjectBeforePersonalApproval() throws Exception {
    byte[] bytes = new org.springframework.core.io.ClassPathResource("templates/technical-data/auxiliary.xlsx").getContentAsByteArray();
    var parsed = service.preview(productId, "原始辅料.xlsx", bytes, WANG);
    assertThat(parsed.issues()).isEmpty(); assertThat(parsed.items()).hasSize(3);
    var request = upload(parsed, 0); request.getItems().get(1).setAmount(new BigDecimal("0.5"));
    var saved = service.save(productId, request, WANG);
    assertThat(saved.issues()).isEmpty();
    assertThat(saved.items().get(1).sourceAmount()).isEqualByComparingTo("0.25");
    assertThat(saved.totalAmount()).isEqualByComparingTo("0.58830169");
    var rows = repository.findAuxItems(saved.draftVersionId());
    assertThat(rows).allSatisfy(row -> assertThat(row.getSubjectCode()).isNull());
    assertThat(codec.auxiliaryEvidence(rows.getFirst()).upload().item().category()).isEqualTo("清洗类");
    assertThat(codec.auxiliaryEvidence(rows.getFirst()).upload().item().secondarySubjectName()).isNull();
    assertThat(validation.validate(taskId, 101L, WANG).issues()).isEmpty();
    var frozen = versions.freeze(repository.lockProduct(productId).orElseThrow(), recipient(), saved.expectedVersion(), 101L);
    assertThat(service.file(productId, frozen.getId(), WANG).bytes()).containsExactly(bytes);
    assertThat(service.get(productId, frozen.getId(), WANG).items().get(1).amount()).isEqualByComparingTo("0.5");
    assertThatThrownBy(() -> service.preview(productId, "again.xlsx", bytes, LI)).hasMessageContaining("未分派给本人");
  }

  private TechnicalDataAuxiliarySaveRequest reference(TechnicalDataAuxiliaryCmsSource source, String amount, int expected) {
    var request = new TechnicalDataAuxiliarySaveRequest(); request.setExpectedVersion(expected); request.setEntryMode("REFERENCE");
    request.setReferenceMaterialNo(source.materialNo()); request.setReferenceFingerprint(source.fingerprint());
    request.setItems(source.items().stream().map(row -> input("CMS:" + row.sourceId(), row.sourceAmount())).toList());
    request.getItems().getFirst().setAmount(amount == null ? null : new BigDecimal(amount)); return request;
  }
  private TechnicalDataAuxiliarySaveRequest upload(TechnicalDataAuxiliaryUploadResponse source, int expected) {
    var request = new TechnicalDataAuxiliarySaveRequest(); request.setExpectedVersion(expected); request.setEntryMode("UPLOAD"); request.setFileSha256(source.fileSha256());
    request.setItems(source.items().stream().map(row -> input(row.itemKey(), row.amountPerProduct())).toList()); return request;
  }
  private TechnicalDataAuxiliaryItemRequest input(String key, BigDecimal amount) {
    var item = new TechnicalDataAuxiliaryItemRequest(); item.setItemKey(key); item.setAmount(amount); return item;
  }
  private void cmsSource(String subject, String name, String amount, String period) {
    var row = new CmsCostSourceEffective(); row.setCostYear(2026); row.setSourceType("AUX_SUBJECT"); row.setParentCode(key + "-REF");
    row.setPeriod(period); row.setSubjectCode(subject); row.setSubjectName(name); row.setAmountYuan(new BigDecimal(amount));
    row.setSourceTable("cms_aux_material_cost"); row.setSourceRowIds("[1]"); row.setDefaultFlag(0); row.setConfirmedBy("TW11"); row.setBusinessUnitType("COMMERCIAL"); cms.insert(row);
  }
private Recipient recipient() { return new Recipient(1L, taskId, 1, 101L, "王工", "wang", "FILL", List.of("AUXILIARY"), 1L, "todo", "CONFIRMED", "OPEN", null, "工程部", "leader", "王总", null, 0, 0, null, true, null, null, "T-TEST", null); }
  private String content(QuoteTechDataVersion version) { return codec.versionContentJson(version, codec.readReferenceSnapshot(version.getReferenceSnapshotJson()), List.of(), repository.findAuxItems(version.getId()), List.of()); }
}
