package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProfileUpdateRequest;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.QuoteTechDataVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechProductMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
@DisplayName("TW-01 九模块版本、发送快照与数据库不可变保护")
class TechnicalDataSubmissionSnapshotIntegrationTest extends BomMapperTestBase {
  private static final AtomicLong IDS = new AtomicLong(9_200_000);
  private static final TechnicalDataActor TECHNICIAN = new TechnicalDataActor(101L, "技术员101", Set.of("technical:data:task:edit", "technical:data:task:list"));
  @Autowired private QuoteTechnicalDataPersistenceService persistence;
  @Autowired private QuoteTechnicalDataRepository repository;
  @Autowired private TechnicalDataSubmissionSnapshotService snapshots;
  @Autowired private TechnicalDataProfileApplicationService profiles;
  @Autowired private TechnicalDataTaskApplicationService tasks;
  @Autowired private TechnicalDataVersionContentCodec codec;
  @Autowired private QuoteTechDataVersionMapper versionMapper;
  @Autowired private QuoteTechProductMapper productMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository recipients;
  @Autowired private TechnicalDataParticipantVersions participantVersions;
  @Autowired private TechnicalDataDependencies dependencies;
  @Autowired private com.sanhua.marketingcost.mapper.QuoteTechModuleMapper moduleMapper;
  @Autowired private com.sanhua.marketingcost.mapper.QuoteTechTaskMapper taskMapper;
  @Autowired private com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper submissionMapper;
  @Autowired private TechnicalDataOaSubmissionLifecycle lifecycle;
  @Autowired private com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository oaWorkflow;
  @Autowired private com.sanhua.marketingcost.service.SysUserService userService;
  @Autowired private TechnicalDataOaUserDirectory userDirectory;
  // 本组验证九模块快照及数据库不可变约束；真实原材料来源与组树见 ManufacturingIntegrationTest。
  @org.springframework.boot.test.mock.mockito.MockBean
  private TechnicalDataManufacturingApplicationService manufacturing;
  @org.springframework.boot.test.mock.mockito.MockBean
  private TechnicalDataPackageApplicationService packaging;

  @org.springframework.boot.test.mock.mockito.MockBean
  private TechnicalDataAuxiliaryApplicationService auxiliary;
  @org.springframework.boot.test.mock.mockito.MockBean
  private TechnicalDataSolderApplicationService solder;
  @org.springframework.boot.test.mock.mockito.MockBean
  private TechnicalDataSalaryApplicationService salary;

  @org.springframework.boot.test.mock.mockito.MockBean
  private TechnicalDataNetLossApplicationService netLoss;
  @org.springframework.boot.test.mock.mockito.MockBean
  private TechnicalDataPriceApplicationService prices;
  // 本组隔离验证快照/审批版本保护；真实价格来源与发布由 PriceApplicationIntegrationTest 验证。
  @org.springframework.boot.test.mock.mockito.MockBean
  private TechnicalDataPricePublication pricePublication;

  @BeforeEach void allowSnapshotFixtureManufacturing() {
    org.mockito.Mockito.when(prices.validateCurrent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    org.mockito.Mockito.when(netLoss.validateCurrent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    org.mockito.Mockito.when(salary.validateCurrent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    org.mockito.Mockito.when(solder.validateCurrent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    org.mockito.Mockito.when(manufacturing.validateCurrent(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    org.mockito.Mockito.when(auxiliary.validateCurrent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    org.mockito.Mockito.when(packaging.validateCurrent(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
  }

  @Test void identityLookupAllowsSharedAdminButStillRejectsDisabledAndDeletedAccounts() {
    long active = IDS.incrementAndGet(), disabled = IDS.incrementAndGet(), deleted = IDS.incrementAndGet();
    for (long id : List.of(active, disabled, deleted)) {
      jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,password,business_unit_type,status,del_flag,create_time,update_time) VALUES(?,?,?,'test',NULL,?,?,NOW(),NOW())",
          id, "tw04-identity-" + id, "公共账号", id == disabled ? "1" : "0", id == deleted ? "1" : "0");
    }
    var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null, List.of());
    auth.setDetails(java.util.Map.of("businessUnitType", "COMMERCIAL"));
    var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
    var previous = context.getAuthentication();
    context.setAuthentication(auth);
    try {
      assertThat(userService.getById(active)).as("人员管理仍按事业部筛选").isNull();
      assertThat(userDirectory.activeUser(active).getUserId()).isEqualTo(active);
      for (long id : List.of(disabled, deleted)) {
        assertThatThrownBy(() -> userDirectory.activeUser(id)).hasMessageContaining("已删除或停用");
      }
    } finally {
      context.setAuthentication(previous);
    }
  }

  @Test void financeReturnFromApprovedTaskRestoresDraftAtomicallyUnderDatabaseGuards() {
    var fixture = fixture();
    var task = taskMapper.selectById(fixture.taskId());
    jdbc.update("INSERT INTO lp_oa_technical_flow(source_system,environment,oa_form_id,accounting_month,external_document_id,external_flow_id) VALUES('TEST','UNIT',?,'2026-09',?,?)",
        task.getOaFormId(), "RETURN-DOC-" + task.getId(), "RETURN-FLOW-" + task.getId());
    Long flow = jdbc.queryForObject("SELECT id FROM lp_oa_technical_flow WHERE oa_form_id=?", Long.class, task.getOaFormId());
    jdbc.update("UPDATE lp_quote_tech_task SET oa_flow_id=? WHERE id=?", flow, task.getId());
    var person = recipients.current(task.getId()).getFirst();
    var prepared = snapshots.prepare(task.getId(), "APPROVED-RETURN-" + task.getId(), 0, 0, person, TECHNICIAN);
    var frozen = versionMapper.selectById(prepared.getTechnicalVersionId());
    participantVersions.transition(frozen, "SUBMITTED", 1L);
    participantVersions.state(productMapper.selectById(fixture.productId()), person, frozen.getId(), "SUBMITTED");
    oaWorkflow.markSending(prepared.getId());
    oaWorkflow.acceptSubmission(prepared.getId(), "RETURN-FLOW-" + task.getId());
    recipients.state(person.id(), prepared.getId(), "PREPARED", "SUBMITTED", null);
    recipients.refreshTask(task.getId());
    task = taskMapper.selectById(task.getId());
    lifecycle.approve(task, submissionMapper.selectById(prepared.getId()), person.messageId(), TECHNICIAN);
    assertThat(productMapper.selectById(fixture.productId()).getEffectiveVersionId()).isNotNull();
    recipients.requestReturn(person.id(), prepared.getId(), person.messageId(), 1L, "核对产品属性");
    lifecycle.financeReturned(taskMapper.selectById(task.getId()), submissionMapper.selectById(prepared.getId()), 1L, "核对产品属性");
    var restored = productMapper.selectById(fixture.productId());
    assertThat(restored.getEffectiveVersionId()).isNull();
    assertThat(versionMapper.selectById(restored.getCurrentEditVersionId()).getVersionStatus()).isEqualTo("DRAFT");
    assertThat(recipients.current(task.getId()).getFirst().todoStatus()).isEqualTo("OPEN");
    assertThat(versionMapper.selectById(frozen.getId()).getVersionStatus()).isEqualTo("APPROVED");
    assertThat(submissionMapper.selectById(prepared.getId()).getSubmissionStatus()).isEqualTo("APPROVED");
  }

  @Test void incompleteBomBlocksDependentPriceButAllowsIndependentProfile() {
    var fixture = fixture();
    jdbc.update("UPDATE lp_quote_tech_module SET module_status='EDITING' WHERE product_id=? AND module_type='DRAWING_BOM'", fixture.productId());
    var modules = moduleMapper.selectByTaskId(fixture.taskId());
    assertThat(dependencies.pending(modules, Set.of("PRICE"))).extracting(TechnicalDataDependencies.Issue::sourceModuleType).containsExactly("DRAWING_BOM");
    assertThat(dependencies.pending(modules, Set.of("PROFILE"))).isEmpty();
  }

  @Test void priceDependencySurvivesQuantityChangeButDetectsMaterialReplacement() {
    var fixture = fixture(); splitPriceToSecondPerson(fixture);
    var li = recipients.current(fixture.taskId()).get(1);
    var submitted = snapshots.prepare(fixture.taskId(), "LI-DEPS", 0, 0, li, new TechnicalDataActor(102L, "技术员102", Set.of("technical:data:task:edit", "technical:data:task:list")));
    var frozen = versionMapper.selectById(submitted.getTechnicalVersionId());
    assertThat(codec.supplementContent(frozen).sources().dependencies()).hasSize(4);
    var changed = versionMapper.selectById(fixture.versionId());
    changed.setDrawingBomJson(changed.getDrawingBomJson().replace("\"quantityPerParent\": 2", "\"quantityPerParent\": 7").replace("\"quantityPerParent\":2", "\"quantityPerParent\":7"));
    persistence.updateDraft(changed, changed.getRowVersion());
    assertThat(dependencies.stale(frozen, moduleMapper.selectByTaskId(fixture.taskId()))).isEmpty();
    changed = versionMapper.selectById(fixture.versionId());
    changed.setDrawingBomJson(changed.getDrawingBomJson().replace("MAT", "DIFFERENT"));
    persistence.updateDraft(changed, changed.getRowVersion());
    assertThat(dependencies.stale(frozen, moduleMapper.selectByTaskId(fixture.taskId())))
        .extracting(TechnicalDataDependencies.Issue::sourceModuleType).containsExactly("DRAWING_BOM");
    assertThat(versionMapper.selectById(frozen.getId()).getContentFingerprint()).isEqualTo(submitted.getContentFingerprint());
  }

  @Test void listAndDetailUseActualModuleAssigneesAfterFullOverride() {
    var fixture = fixture();
    jdbc.update("UPDATE lp_quote_tech_module SET assignee_user_id=102,assignee_name='李工' WHERE product_id=? AND required_flag=1", fixture.productId());
    assertThat(productMapper.selectAccessibleWorkbenchPage("ASSIGNEE", 101L, null, null, "2026-09", null, null, 0, 100))
        .extracting(QuoteTechProduct::getId).doesNotContain(fixture.productId());
    assertThat(productMapper.selectAccessibleWorkbenchPage("ASSIGNEE", 102L, null, null, "2026-09", null, null, 0, 100))
        .extracting(QuoteTechProduct::getId).contains(fixture.productId());
  }

  @Test void prepareFreezesOneProductAndSeparatesAssigneeOperatorAndOaState() {
    var fixture = fixture();
    String requestId = UUID.randomUUID().toString();
    var prepared = snapshots.prepare(fixture.taskId(), requestId, 0, 0, recipients.current(fixture.taskId()).getFirst(), TECHNICIAN);
    assertThat(prepared.getSubmissionStatus()).isEqualTo("PREPARED");
    assertThat(prepared.getAssigneeUserId()).isEqualTo(101L);
    assertThat(prepared.getSubmittedBy()).isEqualTo(101L);
    assertThat(prepared.getPreviousSubmissionId()).isNull();
    assertThat(prepared.getExternalSubmissionId()).isNull();
    assertThat(prepared.getContentSnapshotJson()).contains("PROFILE", "DRAWING_BOM", "MANUFACTURING", "SOLDER", "NET_LOSS", "PRICE");
    assertThat(snapshots.prepare(fixture.taskId(), requestId, 0, 0, recipients.current(fixture.taskId()).getFirst(), TECHNICIAN).getId()).isEqualTo(prepared.getId());
    assertThat(jdbc.queryForObject("SELECT task_status FROM lp_quote_tech_task WHERE id=?", String.class, fixture.taskId())).isEqualTo("PREPARED");
    var version = versionMapper.selectById(prepared.getTechnicalVersionId());
    assertThat(version.getVersionStatus()).isEqualTo("FROZEN");
    assertThat(version.getNewProductFlag()).isEqualTo(1);
    assertThat(codec.supplementContent(version).productFees().includesNewToolingMouldCertificationFee()).isFalse();
    assertThat(version.getContentFingerprint()).isEqualTo(codec.fingerprint(version,
        codec.readReferenceSnapshot(version.getReferenceSnapshotJson()), List.of(), List.of(), List.of()));
    assertThat(productMapper.selectById(fixture.productId()).getCurrentEditVersionId()).isEqualTo(fixture.versionId());
    assertThat(tasks.detail(fixture.taskId(), TECHNICIAN).products().getFirst().supplementContent().netLoss().rate())
        .isEqualByComparingTo("0.0125");
  }

  @Test void everyNewFrozenFieldAndSubmissionSnapshotRejectsOrdinarySqlMutation() {
    var fixture = fixture();
    var prepared = snapshots.prepare(fixture.taskId(), UUID.randomUUID().toString(), 0, 0, recipients.current(fixture.taskId()).getFirst(), TECHNICIAN);
    for (String column : List.of("product_fees_json", "drawing_bom_json", "manufacturing_json", "packaging_json",
        "solder_items_json", "net_loss_json", "price_items_json", "source_facts_json")) {
      assertThatThrownBy(() -> jdbc.update("UPDATE lp_quote_tech_data_version SET " + column + "='{}' WHERE id=?", prepared.getTechnicalVersionId()))
          .as(column).isInstanceOf(DataAccessException.class).hasMessageContaining("IMMUTABLE");
    }
    assertThatThrownBy(() -> jdbc.update("UPDATE lp_quote_tech_data_version SET content_schema_version=1 WHERE id=?", prepared.getTechnicalVersionId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("UPDATE lp_quote_tech_submission SET content_snapshot_json='{}' WHERE id=?", prepared.getId()))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("IMMUTABLE");
    assertThatThrownBy(() -> jdbc.update("DELETE FROM lp_quote_tech_submission WHERE id=?", prepared.getId()))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("IMMUTABLE");
    TechnicalDataProfileUpdateRequest request = new TechnicalDataProfileUpdateRequest();
    request.setProductProperty("标准品"); request.setHasAdditionalFees(false); request.setExpectedVersion(1);
    assertThatThrownBy(() -> profiles.save(fixture.productId(), request, TECHNICIAN)).isInstanceOf(TechnicalDataTaskException.class);
    assertThat(versionMapper.selectById(prepared.getTechnicalVersionId()).getContentFingerprint()).isEqualTo(prepared.getContentFingerprint());
  }

  @Test void twoDraftReadersCannotOverwriteEachOtherAndFingerprintCoversNewContent() {
    var fixture = fixture();
    var first = versionMapper.selectById(fixture.versionId());
    var stale = versionMapper.selectById(fixture.versionId());
    String original = codec.fingerprint(first, List.of(), List.of(), List.of(), List.of());
    first.setNetLossJson("{\"bareMaterialNo\":\"BARE\",\"rate\":0.02,\"entryMode\":\"MANUAL\"}");
    var saved = persistence.updateDraft(first, 0);
    assertThat(codec.fingerprint(saved, List.of(), List.of(), List.of(), List.of())).isNotEqualTo(original);
    stale.setNetLossJson("{\"rate\":0.99}");
    assertThatThrownBy(() -> persistence.updateDraft(stale, 0)).isInstanceOf(QuoteTechnicalDataOptimisticLockException.class);
    assertThat(codec.supplementContent(versionMapper.selectById(first.getId())).netLoss().rate()).isEqualByComparingTo("0.02");
  }

  @Test void returnedCopyRetainsAllContentButHasIndependentDraftAndFingerprint() {
    var fixture = fixture();
    var prepared = snapshots.prepare(fixture.taskId(), UUID.randomUUID().toString(), 0, 0, recipients.current(fixture.taskId()).getFirst(), TECHNICIAN);
    var frozen = versionMapper.selectById(prepared.getTechnicalVersionId());
    // 这里只构造已明确送达并退回的版本状态，OA 事件处理在 TW-04 验收。
    var sent = persistence.transitionVersion(frozen.getId(), "FROZEN", "SUBMITTED", frozen.getRowVersion(), frozen.getContentFingerprint(), 1L);
    var returned = persistence.transitionVersion(sent.getId(), "SUBMITTED", "RETURNED", sent.getRowVersion(), sent.getContentFingerprint(), 1L);
    participantVersions.restore(productMapper.selectById(fixture.productId()), recipients.current(fixture.taskId()).getFirst(), returned.getId(), 1L);
    var copied = versionMapper.selectById(productMapper.selectById(fixture.productId()).getCurrentEditVersionId());
    assertThat(copied.getId()).isNotEqualTo(returned.getId());
    assertThat(copied.getId()).isEqualTo(fixture.versionId());
    assertThat(copied.getVersionStatus()).isEqualTo("DRAFT");
    assertThat(copied.getContentFingerprint()).isNull();
    assertThat(codec.supplementContent(copied).solder()).isEqualTo(codec.supplementContent(returned).solder());
    assertThat(copied.getNewProductFlag()).isEqualTo(returned.getNewProductFlag());
    copied.setSolderItemsJson("{\"items\":[{\"itemKey\":\"S-1\",\"materialNo\":\"311990182\",\"drawingNo\":\"D-1\",\"quantityPerProduct\":0.004,\"unit\":\"kg\"}]}");
    persistence.updateDraft(copied, copied.getRowVersion());
    assertThat(versionMapper.selectById(returned.getId()).getSolderItemsJson()).isEqualTo(returned.getSolderItemsJson());
    var person = recipients.current(fixture.taskId()).getFirst();
    recipients.state(person.id(), prepared.getId(), "PREPARED", "OPEN", "测试部门退回");
    var product = productMapper.selectById(fixture.productId());
    int taskVersion = jdbc.queryForObject("SELECT task_version FROM lp_quote_tech_task WHERE id=?", Integer.class, fixture.taskId());
    var resubmitted = snapshots.prepare(fixture.taskId(), "RESUBMIT-" + fixture.taskId(), taskVersion, product.getRowVersion(), recipients.current(fixture.taskId()).getFirst(), TECHNICIAN);
    assertThat(resubmitted.getSubmissionRound()).isEqualTo(2);
    assertThat(resubmitted.getTechnicalVersionId()).isNotEqualTo(returned.getId());
  }

  @Test void unknownSourcesBlockFreezeWithoutLosingDraft() {
    var fixture = fixture();
    jdbc.update("UPDATE lp_quote_tech_module SET source_availability='ERROR',required_flag=0,module_status='PENDING' WHERE product_id=? AND module_type='PRICE'", fixture.productId());
    assertThatThrownBy(() -> snapshots.prepare(fixture.taskId(), UUID.randomUUID().toString(), 0, 0, recipients.current(fixture.taskId()).getFirst(), TECHNICIAN))
        .isInstanceOf(TechnicalDataTaskException.class).hasMessageContaining("本人负责");
    assertThat(versionMapper.selectById(fixture.versionId()).getVersionStatus()).isEqualTo("DRAFT");
    assertThat(productMapper.selectById(fixture.productId()).getCurrentEditVersionId()).isEqualTo(fixture.versionId());
  }

  @Test void ownershipAndVersionProductBindingsAreChecked() {
    var first = fixture(); var other = fixture();
    var stranger = new TechnicalDataActor(102L, "无关技术员", Set.of("technical:data:task:edit"));
    assertThatThrownBy(() -> snapshots.prepare(first.taskId(), "forbidden", 0, 0, recipients.current(first.taskId()).getFirst(), stranger))
        .isInstanceOf(TechnicalDataTaskException.class).hasMessageContaining("无权");
    var submission = snapshots.prepare(first.taskId(), "allowed", 0, 0, recipients.current(first.taskId()).getFirst(), TECHNICIAN);
    assertThatThrownBy(() -> snapshots.read(other.taskId(), submission.getId(), TECHNICIAN)).hasMessageContaining("跨产品");
    snapshots.prepare(other.taskId(), "other", 0, 0, recipients.current(other.taskId()).getFirst(), TECHNICIAN);
    assertThatThrownBy(() -> participantVersions.verified(submission.getTechnicalVersionId(), other.productId()))
        .isInstanceOf(TechnicalDataTaskException.class);
  }

  @Test void fixedContentRejectsUnknownFieldsAndCannotHideNewFieldsInLegacySchema() {
    var fixture = fixture(); var draft = versionMapper.selectById(fixture.versionId());
    draft.setPriceItemsJson("{\"vendorArbitraryField\":123}");
    assertThatThrownBy(() -> codec.supplementContent(draft)).hasMessageContaining("固定结构");
    draft.setContentSchemaVersion(1);
    assertThatThrownBy(() -> codec.fingerprint(draft, List.of(), List.of(), List.of(), List.of()))
        .hasMessageContaining("未计入指纹");
  }

  @Test void sourceModelAndOriginalFlagStayReadOnlyWhileTechnicianCanConfirmProperty() {
    var fixture = fixture();
    var request = new TechnicalDataProfileUpdateRequest();
    request.setProductProperty("非标品"); request.setHasAdditionalFees(false); request.setExpectedVersion(0);
    request.captureUnknownField("productModel", "OTHER-MODEL");
    assertThatThrownBy(() -> profiles.save(fixture.productId(), request, TECHNICIAN))
        .hasMessageContaining("OA只读字段");
    var valid = new TechnicalDataProfileUpdateRequest();
    valid.setProductProperty("非标品"); valid.setHasAdditionalFees(false); valid.setExpectedVersion(0);
    profiles.save(fixture.productId(), valid, TECHNICIAN);
    var saved = versionMapper.selectById(fixture.versionId());
    assertThat(saved.getProductProperty()).isEqualTo("非标品");
    assertThat(saved.getProductModel()).isEqualTo("SOURCE-MODEL");
    assertThat(saved.getNewProductFlag()).isEqualTo(1);
    assertThat(codec.supplementContent(saved).productFees().includesNewToolingMouldCertificationFee()).isFalse();
  }

  @Test void personalFreezeAndTargetReturnPreserveOtherPersonContentAndVersion() {
    var fixture = fixture();
    splitPriceToSecondPerson(fixture);
    var people = recipients.current(fixture.taskId());
    var wang = people.get(0); var li = people.get(1);
    var wangSubmission = snapshots.prepare(fixture.taskId(), "WANG-1", 0, 0, wang, TECHNICIAN);
    assertThat(wangSubmission.getContentSnapshotJson()).doesNotContain("\"moduleType\":\"PRICE\"");
    assertThat(codec.supplementContent(versionMapper.selectById(wangSubmission.getTechnicalVersionId())).prices()).isNull();
    var shared = versionMapper.selectById(fixture.versionId());
    assertThat(shared.getVersionStatus()).isEqualTo("DRAFT");
    assertThat(jdbc.queryForObject("SELECT module_status FROM lp_quote_tech_module WHERE product_id=? AND module_type='PRICE'", String.class, fixture.productId())).isEqualTo("READY");
    shared.setPriceItemsJson("{\"items\":[{\"itemKey\":\"P-1\",\"materialNo\":\"MAT\",\"unit\":\"kg\",\"currency\":\"CNY\",\"entryMode\":\"MANUAL\",\"unitPrice\":16}]}");
    persistence.updateDraft(shared, shared.getRowVersion());
    var liSubmission = snapshots.prepare(fixture.taskId(), "LI-1", repository.findTask(fixture.taskId()).orElseThrow().getTaskVersion(),
        productMapper.selectById(fixture.productId()).getRowVersion(), li, new TechnicalDataActor(102L, "技术员102", Set.of("technical:data:task:edit", "technical:data:task:list")));
    assertThat(liSubmission.getSubmissionRound()).isEqualTo(1);
    assertThat(liSubmission.getContentSnapshotJson()).contains("\"moduleType\":\"PRICE\"").doesNotContain("\"moduleType\":\"PROFILE\"");
    var frozenLi = versionMapper.selectById(liSubmission.getTechnicalVersionId());
    String before = codec.versionContentJson(frozenLi, codec.readReferenceSnapshot(frozenLi.getReferenceSnapshotJson()), List.of(), List.of(), List.of());
    participantVersions.restore(productMapper.selectById(fixture.productId()), recipients.findById(wang.id()), wangSubmission.getTechnicalVersionId(), 1L);
    var restored = versionMapper.selectById(productMapper.selectById(fixture.productId()).getCurrentEditVersionId());
    assertThat(restored.getId()).isEqualTo(shared.getId());
    assertThat(codec.supplementContent(restored).prices().items().getFirst().unitPrice()).isEqualByComparingTo("16");
    assertThat(jdbc.queryForObject("SELECT current_version_id FROM lp_quote_tech_module WHERE product_id=? AND module_type='PRICE'", Long.class, fixture.productId())).isEqualTo(frozenLi.getId());
    assertThat(jdbc.queryForObject("SELECT module_status FROM lp_quote_tech_module WHERE product_id=? AND module_type='PRICE'", String.class, fixture.productId())).isEqualTo("FROZEN");
    var after = participantVersions.verified(frozenLi.getId(), fixture.productId());
    assertThat(codec.versionContentJson(after, codec.readReferenceSnapshot(after.getReferenceSnapshotJson()), List.of(), List.of(), List.of())).isEqualTo(before);
    assertThat(versionMapper.selectById(shared.getId()).getVersionStatus()).isEqualTo("DRAFT");
  }

  @Test void personalSubmissionIdentityIsImmutableInDatabase() {
    var fixture = fixture();
    var person = recipients.current(fixture.taskId()).getFirst();
    var submission = snapshots.prepare(fixture.taskId(), "PERSON-IMMUTABLE", 0, 0, person, TECHNICIAN);
    for (String assignment : List.of("recipient_id=NULL", "module_types_json='[]'", "leader_external_id='other-leader'")) {
      assertThatThrownBy(() -> jdbc.update("UPDATE lp_quote_tech_submission SET " + assignment + " WHERE id=?", submission.getId()))
          .isInstanceOf(DataAccessException.class).hasMessageContaining("PERSON_SUBMISSION_IDENTITY_IMMUTABLE");
    }
  }

  private void splitPriceToSecondPerson(Fixture fixture) {
    jdbc.update("UPDATE lp_quote_tech_module SET assignee_user_id=102,assignee_name='李工' WHERE product_id=? AND module_type='PRICE'", fixture.productId());
    jdbc.update("UPDATE lp_quote_tech_oa_recipient SET module_types_json='[\"PROFILE\",\"DRAWING_BOM\",\"MANUFACTURING\",\"SOLDER\",\"NET_LOSS\"]' WHERE task_id=?", fixture.taskId());
    String requestId = "TW04-LI-" + fixture.taskId();
    jdbc.update("INSERT INTO lp_oa_integration_message(source_system,environment,direction,request_id,interface_type,schema_version,occurred_at,raw_payload,payload_hash,allowed_business_units) VALUES('TEST','UNIT','OUTBOUND',?,'TASK_DISPATCH',1,NOW(3),'{}',?,'COMMERCIAL')", requestId, "c".repeat(64));
    Long messageId = jdbc.queryForObject("SELECT id FROM lp_oa_integration_message WHERE request_id=?", Long.class, requestId);
    jdbc.update("INSERT INTO lp_quote_tech_oa_recipient(task_id,assignment_version,assignee_user_id,assignee_name,external_user_id,module_types_json,outbound_message_id,external_task_id,dispatch_status,todo_status,active_flag,leader_external_id) VALUES(?,1,102,'李工','oa-tech-2','[\"PRICE\"]',?,'LI-TODO','CONFIRMED','OPEN',1,'oa-leader-2')", fixture.taskId(), messageId);
  }

  private Fixture fixture() {
    long itemId = IDS.incrementAndGet();
    QuoteTechTask task = new QuoteTechTask(); task.setTaskNo("TW01-V-" + UUID.randomUUID());
    task.setOaFormId(itemId + 1000); task.setOaFormItemId(itemId); task.setOaNo("TW01-V-" + itemId);
    task.setAccountingMonth("2026-09"); task.setBusinessUnitType("COMMERCIAL"); task.setApplicableOrgCode("210");
    task.setAssigneeUserId(101L); task.setAssigneeName("技术员101"); task = persistence.createTask(task);
    QuoteTechProduct product = new QuoteTechProduct(); product.setTaskId(task.getId()); product.setOaFormItemId(itemId);
    product.setAccountingMonth("2026-09"); product.setQuoteNo(task.getOaNo()); product.setContentSchemaVersion(2);
    product.setSourceModel("SOURCE-MODEL"); product.setSourceSnapshotJson("{\"sourceModel\":\"SOURCE-MODEL\",\"newProduct\":true}");
    product.setSourceFingerprint("a".repeat(64)); product = persistence.createProduct(product);
    QuoteTechDataVersion draft = new QuoteTechDataVersion(); draft.setProductId(product.getId()); draft.setVersionNo(1);
    draft.setContentSchemaVersion(2); draft.setProductModel("SOURCE-MODEL"); draft.setProductProperty("标准品"); draft.setNewProductFlag(1);
    draft.setProductFeesJson("{\"includesNewToolingMouldCertificationFee\":false,\"unitToolingFee\":0,\"unitMouldFee\":0,\"unitCertificationFee\":0,\"currency\":\"CNY\"}");
    draft.setDrawingBomJson("{\"sourceVersionId\":5,\"nodes\":[{\"itemKey\":\"N-1\",\"sourceNodeId\":\"N-1\","
        + "\"drawingNo\":\"DRAW-1\",\"name\":\"接管\",\"materialNo\":\"MAT\",\"quantityPerParent\":2,\"unit\":\"件\"}],"
        + "\"evidence\":{\"oaFormItemId\":" + itemId + ",\"accountingMonth\":\"2026-09\",\"drawingNo\":\"DRAW-ROOT\","
        + "\"fileSha256\":\"" + "a".repeat(64) + "\",\"requestId\":\"DRAW-TEST\",\"acquiredAt\":\"2026-09-15T10:00:00\"}}");
    draft.setManufacturingJson("{\"items\":[{\"itemKey\":\"R-1\",\"parentSourceNodeId\":\"N-1\",\"rawMaterialNo\":\"RAW\",\"netWeightKg\":0.03,\"quantityPerParent\":0.031,\"unit\":\"kg\"}]}");
    draft.setPackagingJson("{\"parentMaterialNo\":\"PK\",\"sourceParentQuantity\":1,\"parentQuantity\":2,\"parentQuantityUnit\":\"包装组件/件产品\",\"entryMode\":\"MANUAL\"}");
    draft.setSolderItemsJson("{\"items\":[{\"itemKey\":\"S-1\",\"materialNo\":\"311990182\",\"drawingNo\":\"D-1\",\"quantityPerProduct\":0.002,\"unit\":\"kg\"}]}");
    draft.setNetLossJson("{\"bareMaterialNo\":\"BARE\",\"rate\":0.0125,\"entryMode\":\"MANUAL\"}");
    draft.setPriceItemsJson("{\"items\":[{\"itemKey\":\"P-1\",\"materialNo\":\"MAT\",\"unit\":\"kg\",\"currency\":\"CNY\",\"entryMode\":\"MANUAL\",\"unitPrice\":12}]}");
    draft = persistence.createVersion(draft);
    jdbc.update("UPDATE lp_quote_tech_product SET current_edit_version_id=? WHERE id=?", draft.getId(), product.getId());
    for (String type : TechnicalDataModuleType.orderedCodes()) {
      boolean required = !List.of("PACKAGE", "AUXILIARY", "SALARY").contains(type);
      QuoteTechModule module = new QuoteTechModule(); module.setProductId(product.getId()); module.setModuleType(type);
      module.setRequiredFlag(required ? 1 : 0); module.setSourceAvailability(required ? "MISSING" : "AVAILABLE");
      module.setSourceCheckedAt(LocalDateTime.now()); module.setSourceReference("ISOLATED_TEST_FIXTURE:" + itemId);
      module.setRequirementReasonCode("ISOLATED_TEST_CHECK"); module.setRequirementReason("隔离场景构造的来源检查");
      module.setCurrentVersionId(draft.getId()); module.setModuleStatus(required ? "READY" : "NOT_REQUIRED");
      module.setEntryMode(required ? "MANUAL" : "NONE"); module.setLastValidationCode("CONTENT_VALID");
      persistence.createModule(module);
    }
    jdbc.update("UPDATE lp_quote_tech_module SET assignee_user_id=101,assignee_name='技术员101' WHERE product_id=? AND required_flag=1", product.getId());
    String dispatchId = "TW04-TEST-DISPATCH-" + task.getId();
    jdbc.update("INSERT INTO lp_oa_integration_message(source_system,environment,direction,request_id,interface_type,schema_version,occurred_at,raw_payload,payload_hash,allowed_business_units) VALUES('TEST','UNIT','OUTBOUND',?,'TASK_DISPATCH',1,NOW(3),'{}',?,'COMMERCIAL')", dispatchId, "b".repeat(64));
    Long messageId = jdbc.queryForObject("SELECT id FROM lp_oa_integration_message WHERE request_id=?", Long.class, dispatchId);
    jdbc.update("INSERT INTO lp_quote_tech_oa_recipient(task_id,assignment_version,assignee_user_id,assignee_name,external_user_id,module_types_json,outbound_message_id,external_task_id,dispatch_status,todo_status,active_flag,leader_external_id) VALUES(?,1,101,'技术员101','oa-tech-1',?,?,'TEST-TODO','CONFIRMED','OPEN',1,'oa-leader-1')",
        task.getId(), "[\"PROFILE\",\"DRAWING_BOM\",\"MANUFACTURING\",\"SOLDER\",\"NET_LOSS\",\"PRICE\"]", messageId);
    return new Fixture(task.getId(), product.getId(), draft.getId());
  }
  private record Fixture(Long taskId, Long productId, Long versionId) {}
}
