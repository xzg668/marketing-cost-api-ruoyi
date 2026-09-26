package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
class TechnicalDataSalaryApplicationIntegrationTest extends BomMapperTestBase {
  private static final TechnicalDataActor WANG = new TechnicalDataActor(101L, "王工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor LI = new TechnicalDataActor(102L, "李工", Set.of("technical:data:task:edit"));
  private static final TechnicalDataActor ADMIN = new TechnicalDataActor(1L, "管理员", Set.of("*:*:*"));
  @Autowired TechnicalDataSalaryApplicationService service;
  @Autowired QuoteTechnicalDataPersistenceService persistence;
  @Autowired QuoteTechnicalDataRepository repository;
  @Autowired TechnicalDataSubmissionValidationService validation;
  @Autowired TechnicalDataParticipantVersions versions;
  @Autowired TechnicalDataVersionContentCodec codec;
  @Autowired CmsCostSourceEffectiveMapper cms;
  private Long taskId, productId;
  private String key;
  private CmsCostSourceEffective direct;

  @BeforeEach void fixture() {
    key = "TW13-" + UUID.randomUUID().toString().substring(0, 8);
    var task = new QuoteTechTask(); task.setTaskNo(key); task.setOaFormId(10L); task.setOaFormItemId(System.nanoTime());
    task.setOaNo(key); task.setAccountingMonth("2026-09"); task.setBusinessUnitType("COMMERCIAL"); task.setApplicableOrgCode("210");
    task.setAssigneeUserId(101L); task.setAssigneeName("王工"); taskId = persistence.createTask(task).getId();
    var product = new QuoteTechProduct(); product.setTaskId(taskId); product.setOaFormItemId(task.getOaFormItemId()); product.setQuoteNo(key);
    product.setAccountingMonth("2026-09"); product.setContentSchemaVersion(2); product.setMaterialNo(key);
    product.setSourceFingerprint("a".repeat(64)); product.setSourceSnapshotJson("{}");
    productId = persistence.createProduct(product).getId();
    for (String type : TechnicalDataModuleType.orderedCodes()) {
      boolean required = Set.of("SALARY", "PROFILE").contains(type);
      var module = new QuoteTechModule(); module.setProductId(productId); module.setModuleType(type);
      module.setRequiredFlag(required ? 1 : 0); module.setModuleStatus(required ? "PENDING" : "NOT_REQUIRED");
      module.setSourceAvailability(required ? "MISSING" : "AVAILABLE"); module.setSourceReference("TEST-CMS-CONFIRMED");
      module.setSourceCheckedAt(LocalDateTime.now()); module.setRequirementReasonCode("TEST_CHECK"); module.setRequirementReason("本产品缺工资");
      module.setEntryMode(required ? "REFERENCE" : "NONE"); module.setAssigneeUserId("PROFILE".equals(type) ? 102L : 101L);
      module.setAssigneeName("PROFILE".equals(type) ? "李工" : "王工"); persistence.createModule(module);
    }
    direct = source("DIRECT", "4.050877", "2026-11"); source("INDIRECT", "0.099600", "2026-01");
  }

  @Test void sourceAmountsAreSavedWithoutFakeHoursAndOnlyOwnersCompleteTheirModules() {
    var saved = service.save(productId, request(0), WANG);
    assertThat(saved.totalAmount()).isEqualByComparingTo("4.150477");
    assertThat(saved.moduleStatus()).isEqualTo("READY"); assertThat(saved.issues()).isEmpty();
    assertThat(saved.items()).extracting(row -> row.source().cmsItem().sourcePeriod()).containsExactly("2026-11", "2026-01");
    assertThat(repository.findSalaryItems(saved.draftVersionId())).hasSize(2).allSatisfy(row -> {
      assertThat(row.getWorkingHours()).isNull(); assertThat(row.getWageRate()).isNull();
      assertThat(row.getHourlyRate()).isNull(); assertThat(row.getPersonCoefficient()).isNull();
    });
    assertThat(service.get(productId, null, WANG).totalAmount()).isEqualByComparingTo("4.150477");
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isTrue();
    assertThat(validation.validate(taskId, 102L, LI).valid()).isFalse();
    assertThat(repository.findProduct(productId).orElseThrow().getEffectiveVersionId()).isNull();
  }

  @Test void freezeReturnAndNewReferenceKeepOldPeriodAmountsAndFingerprint() {
    var saved = service.save(productId, request(0), WANG);
    var first = versions.freeze(repository.lockProduct(productId).orElseThrow(), recipient(), saved.expectedVersion(), 101L);
    String original = content(first);
    assertThat(service.get(productId, null, WANG).editable()).isFalse();
    direct.setAmountYuan(new BigDecimal("7")); cms.updateById(direct);
    assertThat(repository.findVersion(first.getId()).orElseThrow().getSalaryTotalAmount()).isEqualByComparingTo("4.150477");
    versions.restore(repository.lockProduct(productId).orElseThrow(), recipient(), first.getId(), 101L);
    var current = service.get(productId, null, WANG);
    assertThat(validation.validate(taskId, 101L, WANG).valid()).isFalse();
    assertThatThrownBy(() -> versions.freeze(repository.lockProduct(productId).orElseThrow(), recipient(), current.expectedVersion(), 101L))
        .hasMessageContaining("工资金额或来源");
    var updated = service.save(productId, request(current.expectedVersion()), WANG);
    assertThat(updated.totalAmount()).isEqualByComparingTo("7.099600");
    var second = versions.freeze(repository.lockProduct(productId).orElseThrow(), recipient(), updated.expectedVersion(), 101L);
    assertThat(second.getId()).isNotEqualTo(first.getId());
    assertThat(content(repository.findVersion(first.getId()).orElseThrow())).isEqualTo(original);
    assertThat(repository.findVersion(first.getId()).orElseThrow().getSalaryTotalAmount()).isEqualByComparingTo("4.150477");
  }

  @Test void permissionsUnknownFieldsAndConcurrentVersionsCannotReplaceSavedSalary() throws Exception {
    var input = request(0);
    assertThatThrownBy(() -> service.save(productId, input, LI)).hasMessageContaining("未分派");
    var forged = new ObjectMapper().readValue("{\"entryMode\":\"REFERENCE\",\"expectedVersion\":0,\"amount\":null}", TechnicalDataSalarySaveRequest.class);
    assertThatThrownBy(() -> service.save(productId, forged, WANG)).hasMessageContaining("不支持的字段");
    assertThatThrownBy(() -> service.save(productId, input, ADMIN)).hasMessageContaining("未分派");
    var saved = service.save(productId, input, WANG);
    assertThatThrownBy(() -> service.save(productId, input, WANG)).hasMessageContaining("其他会话");
    assertThat(service.get(productId, null, WANG).draftVersionId()).isEqualTo(saved.draftVersionId());
    input.setExpectedVersion(saved.expectedVersion()); input.setReferenceFingerprint("forged");
    assertThatThrownBy(() -> service.save(productId, input, WANG)).hasMessageContaining("已变化");
  }

  @Test void zeroIsValidButMissingOrNegativeSalaryCannotBeApproved() {
    direct.setAmountYuan(BigDecimal.ZERO); cms.updateById(direct);
    var zero = service.save(productId, request(0), WANG);
    assertThat(zero.items().getFirst().amount()).isZero(); assertThat(validation.validate(taskId, 101L, WANG).valid()).isTrue();
    direct.setAmountYuan(new BigDecimal("-1")); cms.updateById(direct);
    assertThatThrownBy(() -> service.save(productId, request(zero.expectedVersion()), WANG)).hasMessageContaining("小于零");
    cms.deleteById(direct.getId());
    assertThatThrownBy(() -> service.save(productId, request(zero.expectedVersion()), WANG)).hasMessageContaining("缺少直接人工");
    assertThat(service.get(productId, null, WANG).totalAmount()).isEqualByComparingTo("0.099600");
  }

  @Test void uploadUsesOnlyIndirectCmsAndRetainsOriginalForTheFrozenVersion() throws Exception {
    byte[] original = TechnicalDataSalaryUploadParserTest.original();
    var uploaded = service.preview(productId, "工时.xlsx", original, WANG);
    assertThat(uploaded.issues()).isEmpty();
    cms.deleteById(direct.getId()); // 上传直接工资不要求参考成品也有直接工资。
    var saved = service.save(productId, uploadRequest(0, uploaded.fileSha256()), WANG);
    assertThat(saved.totalAmount()).isEqualByComparingTo("0.625634");
    assertThat(saved.items().getFirst().sourceAmount()).isEqualByComparingTo("0.526034");
    assertThat(saved.entryMode()).isEqualTo("UPLOAD");
    assertThat(service.validateCurrent(repository.findProduct(productId).orElseThrow(), repository.findVersion(saved.draftVersionId()).orElseThrow())).isEmpty();
    var checked = validation.validate(taskId, 101L, WANG);
    assertThat(checked.valid()).as("%s", checked).isTrue();
    var frozen = versions.freeze(repository.lockProduct(productId).orElseThrow(), recipient(), saved.expectedVersion(), 101L);
    String firstContent = content(frozen);
    assertThat(service.file(productId, frozen.getId(), WANG).bytes()).isEqualTo(original);
    assertThat(service.get(productId, frozen.getId(), WANG).entryMode()).isEqualTo("UPLOAD");
    versions.restore(repository.lockProduct(productId).orElseThrow(), recipient(), frozen.getId(), 101L);
    try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(original))) {
      workbook.getSheetAt(0).getRow(2).getCell(7).setCellValue(46);
      var next = service.preview(productId, "工时修订.xlsx", TechnicalDataSalaryUploadParserTest.bytes(workbook), WANG);
      var current = service.get(productId, null, WANG);
      var changed = service.save(productId, uploadRequest(current.expectedVersion(), next.fileSha256()), WANG);
      assertThat(changed.items().getFirst().amount()).isEqualByComparingTo("0.601240");
      assertThat(changed.totalAmount()).isEqualByComparingTo("0.700840");
      assertThatThrownBy(() -> service.file(productId, frozen.getId(), WANG)).hasMessageContaining("已提交");
      assertThat(codec.salaryEvidence(repository.findSalaryItems(frozen.getId()).getFirst()).upload().fileSha256()).isEqualTo(uploaded.fileSha256());
      assertThat(content(repository.findVersion(frozen.getId()).orElseThrow())).isEqualTo(firstContent);
      assertThat(validation.validate(taskId, 101L, WANG).valid()).isTrue();
    }
  }

  @Test void uploadRequiresOwnModuleValidFileAndCompleteIndirectReference() throws Exception {
    var original = TechnicalDataSalaryUploadParserTest.original();
    assertThatThrownBy(() -> service.preview(productId, "工资.xlsx", original, LI)).hasMessageContaining("未分派");
    var uploaded = service.preview(productId, "工资.xlsx", original, WANG);
    var wrong = uploadRequest(0, "0".repeat(64));
    assertThatThrownBy(() -> service.save(productId, wrong, WANG)).hasMessageContaining("原表不存在");
    var withoutIndirect = new TechnicalDataSalarySaveRequest(); withoutIndirect.setExpectedVersion(0); withoutIndirect.setEntryMode("UPLOAD");
    withoutIndirect.setFileSha256(uploaded.fileSha256());
    assertThatThrownBy(() -> service.save(productId, withoutIndirect, WANG)).hasMessageContaining("参考成品");
    var reference = request(0); reference.setFileSha256(uploaded.fileSha256());
    assertThatThrownBy(() -> service.save(productId, reference, WANG)).hasMessageContaining("不能带入上传");
    assertThat(repository.findProduct(productId).orElseThrow().getCurrentEditVersionId()).isNull();
  }

  private TechnicalDataSalarySaveRequest uploadRequest(int expected, String hash) {
    var source = service.references(productId, key + "-REF", "UPLOAD", WANG).getFirst();
    var input = new TechnicalDataSalarySaveRequest(); input.setExpectedVersion(expected); input.setEntryMode("UPLOAD");
    input.setFileSha256(hash); input.setReferenceMaterialNo(source.materialNo()); input.setReferenceFingerprint(source.fingerprint());
    return input;
  }

  private TechnicalDataSalarySaveRequest request(int expected) {
    var source = service.references(productId, key + "-REF", "REFERENCE", WANG).getFirst();
    var input = new TechnicalDataSalarySaveRequest(); input.setExpectedVersion(expected); input.setEntryMode("REFERENCE");
    input.setReferenceMaterialNo(source.materialNo()); input.setReferenceFingerprint(source.fingerprint()); return input;
  }
private Recipient recipient() { return new Recipient(1L, taskId, 1, 101L, "王工", "wang", "FILL", List.of("SALARY"), 1L, "todo", "CONFIRMED", "OPEN", null, "工程部", "leader", "王总", null, 0, 0, null, true, null, null, "T-TEST", null); }
  private String content(QuoteTechDataVersion version) { return codec.versionContentJson(version, codec.readReferenceSnapshot(version.getReferenceSnapshotJson()), List.of(), List.of(), repository.findSalaryItems(version.getId())); }
  private CmsCostSourceEffective source(String type, String amount, String period) {
    var row = new CmsCostSourceEffective(); row.setCostYear(2026); row.setBusinessUnitType("COMMERCIAL"); row.setSourceType("SALARY_" + type);
    row.setParentCode(key + "-REF"); row.setPeriod(period); row.setSubjectCode(type); row.setSubjectName("DIRECT".equals(type) ? "直接人工工资" : "辅助人员工资");
    row.setAmountYuan(new BigDecimal(amount)); row.setSourceTable("cms_workshop_labor_raw"); row.setSourceRowIds("11,12"); cms.insert(row); return row;
  }
}
