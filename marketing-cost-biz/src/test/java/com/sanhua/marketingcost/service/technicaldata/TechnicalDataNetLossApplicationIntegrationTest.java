package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QualityLossRateMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.NetLossRateQuery;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
class TechnicalDataNetLossApplicationIntegrationTest extends BomMapperTestBase {
  private static final TechnicalDataActor WANG = new TechnicalDataActor(101L, "王工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor LI = new TechnicalDataActor(102L, "李工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor ADMIN = new TechnicalDataActor(1L, "管理员", Set.of("*:*:*"));
  @Autowired TechnicalDataNetLossApplicationService service;
  @Autowired QuoteTechnicalDataPersistenceService persistence;
  @Autowired QuoteTechnicalDataRepository repository;
  @Autowired TechnicalDataSubmissionValidationService validation;
  @Autowired TechnicalDataParticipantVersions versions;
  @Autowired TechnicalDataVersionContentCodec codec;
  @Autowired MaterialMasterRawMapper materials;
  @Autowired QualityLossRateMapper rates;
  @Autowired NetLossRateQuery sources;
  @Autowired TechnicalDataSharedModules sharedModules;
  @Autowired TechnicalDataSharedModuleQuery sharedQuery;
  private Long taskId, productId, rateA;
  private String key;

  @BeforeEach void fixture() {
    key = "TW14-" + UUID.randomUUID().toString().substring(0, 8);
    var task = new QuoteTechTask(); task.setTaskNo(key); task.setOaFormId(10L); task.setOaFormItemId(System.nanoTime());
    task.setOaNo(key); task.setAccountingMonth("2026-09"); task.setBusinessUnitType("COMMERCIAL"); task.setApplicableOrgCode("210");
    task.setAssigneeUserId(101L); task.setAssigneeName("王工"); taskId = persistence.createTask(task).getId();
    var product = new QuoteTechProduct(); product.setTaskId(taskId); product.setOaFormItemId(task.getOaFormItemId()); product.setQuoteNo(key);
    product.setAccountingMonth("2026-09"); product.setContentSchemaVersion(2); product.setMaterialNo(key);
    product.setSourceFingerprint("a".repeat(64)); product.setSourceSnapshotJson("{\"sourceModel\":\"OA-MODEL\"}");
    productId = persistence.createProduct(product).getId();
    for (String type : TechnicalDataModuleType.orderedCodes()) {
      boolean required = Set.of("NET_LOSS", "PROFILE").contains(type);
      var module = new QuoteTechModule(); module.setProductId(productId); module.setModuleType(type);
      module.setRequiredFlag(required ? 1 : 0); module.setModuleStatus(required ? "PENDING" : "NOT_REQUIRED");
      module.setSourceAvailability(required ? "MISSING" : "AVAILABLE"); module.setSourceReference("TEST_SOURCE");
      module.setSourceCheckedAt(LocalDateTime.now()); module.setRequirementReasonCode("TEST_CHECK"); module.setRequirementReason("没有本产品的公共净损失率");
      module.setEntryMode(required ? "MANUAL" : "NONE"); module.setAssigneeUserId("PROFILE".equals(type) ? 102L : 101L);
      module.setAssigneeName("PROFILE".equals(type) ? "李工" : "王工"); persistence.createModule(module);
    }
    material("A", "10", "BA", "SAME", "COMMERCIAL");
    material("B", "11", null, "SAME", "COMMERCIAL");
    material("PART", "12", null, "SAME", "COMMERCIAL");
    material("OTHER", "11", null, "SAME", "PLATE");
    material("NOBARE", "10", null, "NOBARE", "COMMERCIAL");
    material("NORATE", "10", "BR", "NORATE", "COMMERCIAL");
    material("ZERO", "11", null, "ZERO", "COMMERCIAL");
    rateA = rate("BA", "0.00475001", 2026, "COMMERCIAL");
    rate("B", "0.008", 2026, "COMMERCIAL");
    rate("ZERO", "0", 2026, "COMMERCIAL");
    rate("BR", "0.02", 2025, "COMMERCIAL");
    rate("BR", "0.03", 2026, "PLATE");
  }

  @Test void sameModelRequiresExplicitFinishedProductAndReferenceKeepsFullSourcePrecision() {
    var matches = service.references(productId, "MODEL", key + "-SAME", WANG);
    assertThat(matches).extracting(value -> value.source().materialNo()).containsExactly(key + "-A", key + "-B");
    assertThat(service.get(productId, null, WANG).content()).isNull();
    var saved = service.save(productId, reference(matches.getFirst(), 0), WANG);
    assertThat(saved.content().rate()).isEqualByComparingTo("0.00475001");
    assertThat(saved.content().bareMaterialNo()).isEqualTo(key + "-BA");
    assertThat(saved.content().reference().source().year()).isEqualTo(2026);
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isTrue();
    var second = service.save(productId, reference(matches.getLast(), saved.expectedVersion()), WANG);
    assertThat(second.content().rate()).isEqualByComparingTo("0.008");
    assertThat(second.content().bareMaterialNo()).isEqualTo(key + "-B");
  }

  @Test void missingProductBareOrYearlyRateStayMissingAndPublicZeroIsInvalid() {
    assertThat(sources.lookup(key, null, "COMMERCIAL", 2026, "COMMERCIAL").reasonCode()).isEqualTo("NET_LOSS_PRODUCT_MISSING");
    assertThat(sources.lookup(key, key + "-SAME", "COMMERCIAL", 2026, "COMMERCIAL").reasonCode()).isEqualTo("NET_LOSS_PRODUCT_AMBIGUOUS");
    assertThat(sources.lookup(key, key + "-ZERO", "COMMERCIAL", 2026, "COMMERCIAL").status()).isEqualTo("ERROR");
    assertThat(source("NOBARE").source().reasonCode()).isEqualTo("NET_LOSS_BARE_MISSING");
    assertThat(source("NORATE").source().reasonCode()).isEqualTo("NET_LOSS_RATE_MISSING");
    assertThatThrownBy(() -> service.save(productId, reference(source("NORATE"), 0), WANG)).hasMessageContaining("暂无");
    assertThatThrownBy(() -> service.save(productId, reference(source("ZERO"), 0), WANG)).hasMessageContaining("必须大于 0%");
  }

  @Test void manualDoesNotRequireBareOrWritePublicTableAndEmptyDraftCannotSubmit() {
    long before = rates.selectCount(null);
    var empty = service.save(productId, manual(null, 0), WANG);
    assertThat(empty.moduleStatus()).isEqualTo("EDITING"); assertThat(empty.content().rate()).isNull();
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isFalse();
    assertThatThrownBy(() -> versions.freeze(repository.lockProduct(productId).orElseThrow(), recipient(), empty.expectedVersion(), 101L)).hasMessageContaining("校验");
    var saved = service.save(productId, manual("0.475", empty.expectedVersion()), WANG);
    assertThat(saved.content().rate()).isEqualByComparingTo("0.00475");
    assertThat(saved.content().reference()).isNull(); assertThat(saved.content().bareMaterialNo()).isNull();
    assertThat(rates.selectCount(null)).isEqualTo(before);
    var zero = service.save(productId, manual("0", saved.expectedVersion()), WANG);
    assertThat(zero.moduleStatus()).isEqualTo("READY"); assertThat(validation.validate(taskId, 101L, WANG).valid()).isTrue();
  }

  @Test void personCanOnlySaveOwnModuleAndAdminCannotWriteAndAssigneeKeepsConcurrency() {
    assertThatThrownBy(() -> service.save(productId, manual("1", 0), LI)).hasMessageContaining("未分派给本人");
    var forged = manual("1", 0); forged.addUnknownField("rate", null);
    assertThatThrownBy(() -> service.save(productId, forged, WANG)).hasMessageContaining("不支持的字段");
    assertThatThrownBy(() -> service.save(productId, manual("1", 0), ADMIN)).hasMessageContaining("未分派");
    var saved = service.save(productId, manual("1", 0), WANG);
    assertThat(repository.findVersion(saved.draftVersionId()).orElseThrow().getUpdatedBy()).isEqualTo(101L);
    assertThat(repository.lockModules(productId)).filteredOn(module -> module.getModuleType().equals("NET_LOSS"))
        .allSatisfy(module -> assertThat(module.getAssigneeUserId()).isEqualTo(101L));
    assertThatThrownBy(() -> service.save(productId, manual("2", 0), WANG)).hasMessageContaining("其他会话");
  }

  @Test void changedReferenceBlocksSaveAndSubmissionUntilReselectedWithoutOverwritingDraft() {
    var reference = source("A");
    var saved = service.save(productId, reference(reference, 0), WANG);
    var row = rates.selectById(rateA); row.setLossRate(new BigDecimal("0.006")); rates.updateById(row);
    assertThat(validation.validate(taskId, 101L, WANG).issues()).anySatisfy(issue -> assertThat(issue.message()).contains("已变化"));
    assertThatThrownBy(() -> versions.freeze(repository.lockProduct(productId).orElseThrow(), recipient(), saved.expectedVersion(), 101L)).hasMessageContaining("净损失率");
    assertThatThrownBy(() -> service.save(productId, reference(reference, saved.expectedVersion()), WANG)).hasMessageContaining("已变化");
    assertThat(service.get(productId, null, WANG).content().rate()).isEqualByComparingTo("0.00475001");
    var next = service.save(productId, reference(source("A"), saved.expectedVersion()), WANG);
    assertThat(next.content().rate()).isEqualByComparingTo("0.006");
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isTrue();
  }

  @Test void personalReturnAndModeSwitchKeepOldSnapshotAndOtherPersonsModule() {
    var saved = service.save(productId, reference(source("A"), 0), WANG);
    var first = versions.freeze(repository.lockProduct(productId).orElseThrow(), recipient(), saved.expectedVersion(), 101L);
    var oldContent = content(first);
    assertThat(service.get(productId, null, WANG).editable()).isFalse();
    versions.restore(repository.lockProduct(productId).orElseThrow(), recipient(), first.getId(), 101L);
    var current = service.get(productId, null, WANG);
    var next = service.save(productId, manual("0.8", current.expectedVersion()), WANG);
    var second = versions.freeze(repository.lockProduct(productId).orElseThrow(), recipient(), next.expectedVersion(), 101L);
    assertThat(service.get(productId, second.getId(), WANG).content().reference()).isNull();
    assertThat(service.get(productId, second.getId(), WANG).content().rate()).isEqualByComparingTo("0.008");
    var row = rates.selectById(rateA); row.setLossRate(new BigDecimal("0.09")); rates.updateById(row);
    assertThat(content(repository.findVersion(first.getId()).orElseThrow())).isEqualTo(oldContent);
    assertThat(codec.netLoss(repository.findVersion(first.getId()).orElseThrow()).rate()).isEqualByComparingTo("0.00475001");
    assertThat(repository.lockModules(productId)).filteredOn(module -> module.getModuleType().equals("PROFILE"))
        .allSatisfy(module -> assertThat(module.getModuleStatus()).isEqualTo("PENDING"));
    assertThat(repository.findProduct(productId).orElseThrow().getEffectiveVersionId()).isNull();
  }

  @Test void inactiveAndWrongOrganizationCannotBeSelectedAndSourceRangeErrorsAreExplicit() {
    var master = materials.selectByLatestBatchAndCodes(List.of(key + "-A"), null, "COMMERCIAL").getFirst();
    master.setActiveFlag(0); materials.updateById(master);
    assertThat(service.references(productId, "CODE", key + "-A", WANG)).isEmpty();
    assertThat(service.references(productId, "CODE", key + "-OTHER", WANG)).isEmpty();
    var row = rates.selectById(rateA); row.setLossRate(BigDecimal.ONE); rates.updateById(row);
    master.setActiveFlag(1); materials.updateById(master);
    assertThat(source("A").source().status()).isEqualTo("ERROR");
    assertThatThrownBy(() -> service.save(productId, reference(source("A"), 0), WANG)).hasMessageContaining("必须大于 0%");
  }

  @Test void publicRateArrivingBeforeSubmissionRequiresRecheckAndRetainsPriorDraft() {
    var saved = service.save(productId, manual("0", 0), WANG);
    material("OWN", "11", null, "OWN", "COMMERCIAL");
    var master = materials.selectByLatestBatchAndCodes(List.of(key + "-OWN"), null, "COMMERCIAL").getFirst();
    master.setMaterialCode(key); materials.updateById(master);
    var row = new QualityLossRate(); row.setBareProductCode(key); row.setBusinessUnitType("COMMERCIAL");
    row.setRateYear(2026); row.setLossRate(new BigDecimal("0.003")); rates.insert(row);
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isFalse();
    assertThatThrownBy(() -> service.save(productId, manual("1", saved.expectedVersion()), WANG)).hasMessageContaining("公共净损失率");
    var module = repository.lockModules(productId).stream().filter(value -> value.getModuleType().equals("NET_LOSS")).findFirst().orElseThrow();
    module.setRequiredFlag(0); module.setModuleStatus("NOT_REQUIRED"); module.setSourceAvailability("AVAILABLE");
    repository.updateModule(module, module.getRowVersion(), LocalDateTime.now());
    var current = service.get(productId, null, WANG);
    assertThat(current.editable()).isFalse(); assertThat(current.content().rate()).isZero();
    assertThat(current.publicSource().rate()).isEqualByComparingTo("0.003");
    assertThat(current.draftVersionId()).isEqualTo(saved.draftVersionId());
  }

  @Test void publicRateWinsAfterApprovalAndInvalidPublicRateDoesNotFallBack() {
    var saved = service.save(productId, manual("0.475", 0), WANG);
    assertThat(service.get(productId, null, WANG).applicableRate().status()).isEqualTo("MISSING");
    var frozen = versions.freeze(repository.lockProduct(productId).orElseThrow(), recipient(), saved.expectedVersion(), 101L);
    var submitted = versions.transition(frozen, "SUBMITTED", 101L);
    var approved = versions.transition(submitted, "APPROVED", 101L);
    versions.state(repository.lockProduct(productId).orElseThrow(), recipient(), approved.getId(), "APPROVED");
    var original = content(approved);
    var shared = sharedModules.find(key, 999999L, "NET_LOSS");
    assertThat(shared.productId()).isEqualTo(productId);
    assertThat(shared.versionId()).isEqualTo(approved.getId());
    assertThat(shared.moduleStatus()).isEqualTo("APPROVED");
    assertThat(shared.assigneeName()).isEqualTo("王工");
    var target = new TechnicalDataProductSource(20L, "OTHER", 999999L, "OTHER", 1, key, "同产品",
        null, null, null, null, null, null, null, "COMMERCIAL", "210", "COMMERCIAL");
    var publicMissing = new TechnicalDataSourceFact(TechnicalDataModuleType.NET_LOSS, TechnicalDataAvailability.MISSING,
        "PUBLIC_MISSING", "公共配置缺失", null, LocalDateTime.now());
    var sharedInfo = sharedQuery.describe(target, "2026-10", List.of(publicMissing)).getFirst();
    assertThat(sharedInfo.status()).isEqualTo("APPROVED");
    assertThat(sharedInfo.sourceVersionId()).isEqualTo(approved.getId());
    assertThat(sharedInfo.sourceFingerprint()).isEqualTo(approved.getContentFingerprint());
    var fallback = service.get(productId, null, WANG);
    assertThat(fallback.applicableRate().sourceType()).isEqualTo("TECH_SUPPLEMENTAL");
    assertThat(fallback.applicableRate().rate()).isEqualByComparingTo("0.00475");

    material("OWN", "11", null, "OWN", "COMMERCIAL");
    var master = materials.selectByLatestBatchAndCodes(List.of(key + "-OWN"), null, "COMMERCIAL").getFirst();
    master.setMaterialCode(key); materials.updateById(master);
    var publicRate = new QualityLossRate(); publicRate.setBareProductCode(key);
    publicRate.setRateYear(2026); publicRate.setBusinessUnitType("COMMERCIAL");
    publicRate.setLossRate(new BigDecimal("0.003")); rates.insert(publicRate);
    var current = service.get(productId, null, WANG);
    assertThat(current.applicableRate().sourceType()).isEqualTo("PUBLIC");
    assertThat(current.applicableRate().rate()).isEqualByComparingTo("0.003");
    assertThat(current.content().rate()).isEqualByComparingTo("0.00475");
    publicRate.setLossRate(BigDecimal.ZERO); rates.updateById(publicRate);
    var invalid = service.get(productId, null, WANG);
    assertThat(invalid.applicableRate().status()).isEqualTo("ERROR");
    assertThat(invalid.applicableRate().rate()).isNull();
    rates.deleteById(publicRate.getId());
    assertThat(service.get(productId, null, WANG).applicableRate().sourceType()).isEqualTo("TECH_SUPPLEMENTAL");
    assertThat(content(repository.findVersion(approved.getId()).orElseThrow())).isEqualTo(original);
    assertThat(service.get(productId, approved.getId(), WANG).applicableRate()).isNull();
    assertThat(repository.findProduct(productId).orElseThrow().getEffectiveVersionId()).isNull();
  }

  private TechnicalDataNetLossReference source(String suffix) { return service.references(productId, "CODE", key + "-" + suffix, WANG).getFirst(); }
  private TechnicalDataNetLossSaveRequest reference(TechnicalDataNetLossReference source, int expected) {
    var input = new TechnicalDataNetLossSaveRequest(); input.setExpectedVersion(expected); input.setEntryMode("REFERENCE");
    input.setReferenceMaterialNo(source.source().materialNo()); input.setReferenceFingerprint(source.fingerprint()); return input;
  }
  private TechnicalDataNetLossSaveRequest manual(String percent, int expected) {
    var input = new TechnicalDataNetLossSaveRequest(); input.setExpectedVersion(expected); input.setEntryMode("MANUAL"); input.setPercent(percent); return input;
  }
private Recipient recipient() { return new Recipient(1L, taskId, 1, 101L, "王工", "wang", "FILL", List.of("NET_LOSS"), 1L, "todo", "CONFIRMED", "OPEN", null, "工程部", "leader", "王总", null, 0, 0, null, true, null, null, "T-TEST", null); }
  private String content(QuoteTechDataVersion version) { return codec.versionContentJson(version, codec.readReferenceSnapshot(version.getReferenceSnapshotJson()), List.of(), List.of(), List.of()); }
  private void material(String suffix, String category, String bare, String model, String org) {
    var row = new MaterialMasterRaw(); row.setMaterialCode(key + "-" + suffix); row.setMaterialName("测试成品" + suffix);
    row.setMaterialModel(key + "-" + model); row.setMainCategoryCode(category); row.setBareCode(bare == null ? null : key + "-" + bare);
    row.setUnit("只"); row.setOrganizationCode(org); row.setActiveFlag(1); row.setImportBatchId(key); row.setSourceType("EXCEL"); materials.insert(row);
  }
  private Long rate(String bare, String value, int year, String unit) {
    var row = new QualityLossRate(); row.setBareProductCode(key + "-" + bare); row.setRateYear(year); row.setBusinessUnitType(unit);
    row.setLossRate(new BigDecimal(value)); rates.insert(row); return row.getId();
  }
}
