package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.controller.TechnicalDataProfileController;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProductResponse;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechSubmission;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository;
import com.sanhua.marketingcost.mapper.QuoteTechDataVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechProductMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialMappingService;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Independent committed requests against disposable MySQL, including the actual JSON controller. */
@Tag("integration")
class TechnicalDataPlateMaterialProfileIntegrationTest extends BomMapperTestBase {
  private static final AtomicLong IDS = new AtomicLong(91_040_000);
  private static final TechnicalDataActor TECH = new TechnicalDataActor(101L, "板换测试技术员",
      Set.of("technical:data:task:list", "technical:data:task:edit"));
  @Autowired TechnicalDataTaskApplicationService tasks;
  @Autowired TechnicalDataProfileApplicationService profiles;
  @Autowired TechnicalDataSubmissionValidationService validation;
  @Autowired TechnicalDataSubmissionSnapshotService snapshots;
  @Autowired TechnicalDataParticipantVersions participants;
  @Autowired TechnicalDataPlateMaterialProfile plateProfiles;
  @Autowired TechnicalDataVersionContentCodec codec;
  @Autowired TechnicalDataSubmissionSummary summary;
  @Autowired TechnicalDataSubmissionRemark remark;
  @Autowired QuoteTechnicalDataPersistenceService persistence;
  @Autowired QuoteTechDataVersionMapper versions;
  @Autowired QuoteTechProductMapper products;
  @Autowired TechnicalDataOaRecipientRepository recipients;
  @Autowired PlateMaterialMappingService mappings;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  @Autowired TechnicalDataOaSubmissionLifecycle lifecycle;
  @Autowired com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository oaWorkflow;
  @Autowired com.sanhua.marketingcost.mapper.QuoteTechTaskMapper taskMapper;
  @Autowired com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper submissionMapper;

  @Test void onlyTheLastConfirmedEffectiveVersionPublishesItsSelectedMaterial() throws Exception {
    var f = fixture(); master(f);
    assertCode(sendPatch(f, request(view(f), "水氟SUS304"), TECH), 0, null);
    assertThat(mappings.list("COMMERCIAL", f.model(), null, 1, 20).records()).isEmpty();
    assertCode(sendPatch(f, request(view(f), "水水316L"), TECH), 0, null);
    assertThat(mappings.list("COMMERCIAL", f.model(), null, 1, 20).records()).isEmpty();
    var submitted = freeze(f);
    String frozenSnapshot = submissionMapper.selectById(submitted.getId()).getContentSnapshotJson();
    assertThat(mappings.list("COMMERCIAL", f.model(), null, 1, 20).records()).isEmpty();
    long effectiveId = acceptAndPublish(f, submitted);
    assertThat(effectiveId).isEqualTo(submitted.getTechnicalVersionId());
    assertThat(mappings.list("COMMERCIAL", f.model(), null, 1, 20).records())
        .singleElement().satisfies(row -> {
          assertThat(row.getPlateMaterial()).isEqualTo("水水316L");
          assertThat(row.getCreatedBy()).isEqualTo(TECH.userId());
        });
    assertThat(codec.plateMaterial(versions.selectById(effectiveId)).material().value()).isEqualTo("水水316L");
    assertThat(submissionMapper.selectById(submitted.getId()).getContentSnapshotJson())
        .isEqualTo(frozenSnapshot);
    var next = fixture(f.model());
    assertThat(view(next).profile().plateMaterial().mode()).isEqualTo(PlateMaterialMode.CONFIG_LOCKED);
    assertThat(view(next).profile().plateMaterial().value().value()).isEqualTo("水水316L");
    assertCode(sendPatch(next, request(view(next), "水氟316L"), TECH), 400, "不允许修改");

    var mapping = mappings.list("COMMERCIAL", f.model(), null, 1, 20).records().getFirst();
    mappings.update("COMMERCIAL", mapping.getId(), f.model(), "氟氟316L", mapping.getRowVersion(), 1);
    new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status ->
        lifecycle.publishSubmitted(taskMapper.selectById(f.task()),
            submissionMapper.selectById(submitted.getId()), TECH.userId()));
    assertThat(mappings.list("COMMERCIAL", f.model(), null, 1, 20).records().getFirst().getPlateMaterial())
        .isEqualTo("氟氟316L");
    assertThat(publicationCount(submitted)).isEqualTo(1);
    assertThat(submissionMapper.selectById(submitted.getId()).getContentSnapshotJson()).isEqualTo(frozenSnapshot);
  }

  @Test void replacedFrozenCandidatePublishesOnlyItsFinalReplacement() throws Exception {
    var f = fixture(); master(f);
    assertCode(sendPatch(f, request(view(f), "水氟SUS304"), TECH), 0, null);
    var first = freeze(f);
    var firstSnapshot = submissionMapper.selectById(first.getId()).getContentSnapshotJson();
    var person = recipients.current(f.task()).getFirst();
    participants.restore(products.selectById(f.product()), person, first.getTechnicalVersionId(), TECH.userId());
    recipients.state(person.id(), first.getId(), "PREPARED", "OPEN", "重新确认最终材质");
    assertCode(sendPatch(f, request(view(f), "氟氟316L"), TECH), 0, null);
    var last = freeze(f);
    assertThat(last.getTechnicalVersionId()).isNotEqualTo(first.getTechnicalVersionId());
    assertThat(acceptAndPublish(f, last)).isEqualTo(last.getTechnicalVersionId());
    assertThat(mappings.list("COMMERCIAL", f.model(), null, 1, 20).records())
        .singleElement().satisfies(row -> assertThat(row.getPlateMaterial()).isEqualTo("氟氟316L"));
    assertThat(publicationCount(first)).isZero();
    assertThat(publicationCount(last)).isEqualTo(1);
    assertThat(submissionMapper.selectById(first.getId()).getContentSnapshotJson()).isEqualTo(firstSnapshot);
  }

  @Test void unacceptedSubmissionCannotPublishEvenWhenItsSnapshotIsFrozen() throws Exception {
    var f = fixture(); master(f);
    assertCode(sendPatch(f, request(view(f), "水水316L"), TECH), 0, null);
    var prepared = freeze(f);
    var before = state(f);
    assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> lifecycle.publishSubmitted(taskMapper.selectById(f.task()),
            submissionMapper.selectById(prepared.getId()), TECH.userId())))
        .hasMessageContaining("尚未成功提交 OA");
    assertThat(state(f)).isEqualTo(before);
    assertThat(mappings.list("COMMERCIAL", f.model(), null, 1, 20).records()).isEmpty();
    assertThat(publicationCount(prepared)).isZero();
  }

  @Test void conflictingConfigurationAddedAfterFreezeBlocksActivationWithoutPartialWrites() throws Exception {
    var f = fixture(); master(f);
    assertCode(sendPatch(f, request(view(f), "水水316L"), TECH), 0, null);
    var prepared = freeze(f);
    mappings.create("COMMERCIAL", f.model(), "水氟316L", 1);
    var before = state(f);
    assertThatThrownBy(() -> acceptAndPublish(f, prepared)).hasMessageContaining("不一致");
    assertThat(state(f)).isEqualTo(before);
    assertThat(publicationCount(prepared)).isZero();
    assertThat(mappings.list("COMMERCIAL", f.model(), null, 1, 20).records())
        .singleElement().satisfies(row -> assertThat(row.getPlateMaterial()).isEqualTo("水氟316L"));
  }

  @Test void activationFailureRollsBackMappingAndAuditTogetherWithVersionState() throws Exception {
    var f = fixture(); master(f);
    assertCode(sendPatch(f, request(view(f), "水水316L"), TECH), 0, null);
    var prepared = freeze(f);
    var before = state(f);
    jdbc.execute("""
        CREATE TRIGGER pmm_test_reject_activation BEFORE UPDATE ON lp_quote_tech_product FOR EACH ROW
        BEGIN
          IF NEW.id = %d AND NEW.effective_version_id IS NOT NULL THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PMM_TEST_ACTIVATION_FAILURE';
          END IF;
        END
        """.formatted(f.product()));
    try {
      assertThatThrownBy(() -> acceptAndPublish(f, prepared)).hasMessageContaining("PMM_TEST_ACTIVATION_FAILURE");
      assertThat(state(f)).isEqualTo(before);
      assertThat(mappings.list("COMMERCIAL", f.model(), null, 1, 20).records()).isEmpty();
      assertThat(publicationCount(prepared)).isZero();
    } finally {
      jdbc.execute("DROP TRIGGER pmm_test_reject_activation");
    }
    assertThat(acceptAndPublish(f, prepared)).isEqualTo(prepared.getTechnicalVersionId());
    assertThat(publicationCount(prepared)).isEqualTo(1);
  }

  @Test void hiddenAndAlreadyConfiguredSnapshotsActivateWithoutPublishingManualConfiguration() throws Exception {
    for (boolean configured : List.of(false, true)) {
      var f = fixture();
      if (configured) mappings.create("COMMERCIAL", f.model(), "水氟316L", 1);
      var before = jdbc.queryForList("SELECT * FROM lp_plate_material_mapping WHERE normalized_model=?", f.model());
      assertCode(sendPatch(f, request(view(f), null), TECH), 0, null);
      var prepared = freeze(f);
      assertThat(acceptAndPublish(f, prepared)).isEqualTo(prepared.getTechnicalVersionId());
      assertThat(publicationCount(prepared)).isZero();
      assertThat(jdbc.queryForList("SELECT * FROM lp_plate_material_mapping WHERE normalized_model=?", f.model()))
          .isEqualTo(before);
    }
  }

  @Test void configuredUnknownModelIsLockedAndForgeryCannotPartiallyWrite() throws Exception {
    var f = fixture();
    mappings.create("COMMERCIAL", f.model(), "水水316L", 1);
    var initial = view(f);
    assertThat(initial.profile().plateMaterial().mode()).isEqualTo(PlateMaterialMode.CONFIG_LOCKED);
    assertThat(initial.profile().plateMaterial().value().value()).isEqualTo("水水316L");
    assertThat(initial.profile().plateMaterial().saved()).isNull();
    var before = state(f);
    assertCode(sendPatch(f, request(initial, "水氟316L"), TECH), 400, "不允许修改");
    assertThat(state(f)).isEqualTo(before);
    var saved = sendPatch(f, request(initial, null), TECH);
    assertCode(saved, 0, null);
    assertThat(saved.at("/data/plateMaterial/value").asText()).isEqualTo("水水316L");
    assertThat(saved.at("/data/plateMaterial/source/mappingVersion").asInt()).isZero();
    assertThat(view(f).profile().plateMaterial().saved().material().value()).isEqualTo("水水316L");
    assertThat(validation.validate(f.task(), TECH.userId(), TECH).valid()).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_master_raw WHERE material_model=?",
        Integer.class, f.model())).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"水氟SUS304", "水氟316L", "氟氟SUS304", "氟氟316L", "水水SUS304", "水水316L"})
  void everyManualChoiceSurvivesReloadAndFreezeWithoutWritingConfiguration(String material) throws Exception {
    var f = fixture(); master(f);
    var initial = view(f);
    assertThat(initial.profile().plateMaterial().mode()).isEqualTo(PlateMaterialMode.MANUAL_REQUIRED);
    assertThat(initial.profile().plateMaterial().value()).isNull();
    assertCode(sendPatch(f, request(initial, material), TECH), 0, null);
    var saved = view(f).profile().plateMaterial();
    assertThat(saved.value().value()).isEqualTo(material);
    assertThat(saved.saved().material().value()).isEqualTo(material);
    assertThat(saved.saved().source().materialMasterIds()).hasSize(1);
    assertThat(saved.sourceChanged()).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_plate_material_mapping WHERE normalized_model=?",
        Integer.class, f.model())).isZero();
    var submitted = freeze(f);
    var content = json.readTree(submitted.getContentSnapshotJson());
    assertThat(content.at("/supplements/plateMaterial/material").asText()).isEqualTo(material);
    assertThat(submitted.getSummaryJson()).contains("plateMaterial", material);
    assertThat(remark.generate(f.model(), submitted)).contains("板片材质" + material);
    assertThat(view(f).profile().plateMaterial().historical()).isTrue();
  }

  @Test void requiredHiddenUnknownFieldsAndOwnerPermissionsAreEnforcedByActualPatch() throws Exception {
    var manual = fixture(); master(manual);
    var initial = view(manual); var before = state(manual);
    for (String value : new String[] {null, "", "水水316", "316L", "水水316L "}) {
      assertCode(sendPatch(manual, request(initial, value), TECH), 400, "必填");
      assertThat(state(manual)).isEqualTo(before);
    }
    for (String field : List.of("mode", "productionDivision", "materialOrganizationCode", "sourceModel")) {
      var forged = request(initial, "水水316L");
      forged.put(field, "HIDDEN");
      assertCode(sendPatch(manual, forged, TECH), 400, null);
      assertThat(state(manual)).isEqualTo(before);
    }
    for (var actor : List.of(
        new TechnicalDataActor(102L, "其他技术员", TECH.authorities()),
        new TechnicalDataActor(1L, "管理员", Set.of("technical:data:admin:operate")))) {
      assertCode(sendPatch(manual, request(initial, "水水316L"), actor), 403, null);
      assertThat(state(manual)).isEqualTo(before);
    }
    var missingRevision = request(initial, "水水316L"); missingRevision.remove("expectedPlateMaterialRevision");
    assertCode(sendPatch(manual, missingRevision, TECH), 400, "先读取");
    var hidden = fixture();
    assertThat(view(hidden).profile().plateMaterial().mode()).isEqualTo(PlateMaterialMode.HIDDEN);
    assertCode(sendPatch(hidden, request(view(hidden), "水水316L"), TECH), 400, "不适用");
    assertCode(sendPatch(hidden, request(view(hidden), null), TECH), 0, null);
    assertThat(view(hidden).profile().plateMaterial().saved().material()).isNull();
    assertThat(validation.validate(hidden.task(), TECH.userId(), TECH).valid()).isTrue();
  }

  @Test void rowConflictAndInvalidSourceContextDoNotCreateOrChangeDraft() throws Exception {
    var f = fixture();
    var request = request(view(f), null);
    assertCode(sendPatch(f, request, TECH), 0, null);
    var before = state(f);
    assertCode(sendPatch(f, request, TECH), 409, null);
    assertThat(state(f)).isEqualTo(before);
    var invalid = fixture();
    var validRequest = request(view(invalid), null);
    jdbc.update("UPDATE lp_quote_tech_product SET source_snapshot_json='{}' WHERE id=?", invalid.product());
    assertThatThrownBy(() -> view(invalid)).hasMessageContaining("来源上下文无效");
    assertCode(sendPatch(invalid, validRequest, TECH), 400, "来源上下文无效");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_data_version WHERE product_id=?",
        Integer.class, invalid.product())).isZero();
  }

  @Test void currentConfigurationChangeBlocksSaveAndBothFreezePathsUntilReconfirmed() throws Exception {
    var f = fixture();
    var mapping = mappings.create("COMMERCIAL", f.model(), "水氟316L", 1);
    assertCode(sendPatch(f, request(view(f), null), TECH), 0, null);
    var stale = request(view(f), "水氟316L");
    var before = state(f);
    mappings.update("COMMERCIAL", mapping.getId(), f.model(), "水水316L", 0, 1);
    var refreshed = view(f).profile().plateMaterial();
    assertThat(refreshed.sourceChanged()).isTrue();
    assertThat(refreshed.value().value()).isEqualTo("水水316L");
    assertThat(refreshed.saved().material().value()).isEqualTo("水氟316L");
    assertThat(state(f)).isEqualTo(before); // GET did not rewrite the saved draft.
    assertCode(sendPatch(f, stale, TECH), 409, "来源已变化");
    assertThat(validation.validate(f.task(), TECH.userId(), TECH).issues())
        .anyMatch(issue -> "plateMaterial".equals(issue.field()));
    assertThatThrownBy(() -> freeze(f)).hasMessageContaining("来源已变化");
    assertThatThrownBy(() -> persistence.freezeDraftForSubmission(f.product(),
        view(f).rowVersion(), TECH.userId())).hasMessageContaining("来源已变化");
    assertThat(state(f)).isEqualTo(before);
    assertCode(sendPatch(f, request(view(f), null), TECH), 0, null);
    assertThat(json.readTree(freeze(f).getContentSnapshotJson())
        .at("/supplements/plateMaterial/material").asText()).isEqualTo("水水316L");
  }

  @Test void publicSourcesAreRecheckedWithinAnOpenWorkflowTransaction() throws Exception {
    var f = fixture();
    var mapping = mappings.create("COMMERCIAL", f.model(), "水氟316L", 1);
    assertCode(sendPatch(f, request(view(f), null), TECH), 0, null);
    var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.executeWithoutResult(status -> {
      assertThat(view(f).profile().plateMaterial().value().value()).isEqualTo("水氟316L");
      try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        executor.submit(() -> mappings.update("COMMERCIAL", mapping.getId(), f.model(), "水水316L", 0, 1))
            .get(10, java.util.concurrent.TimeUnit.SECONDS);
      } catch (Exception failure) { throw new AssertionError(failure); }
      assertThat(validation.validate(f.task(), TECH.userId(), TECH).issues())
          .anyMatch(issue -> "plateMaterial".equals(issue.field()));
    });
  }

  @Test void newlyAddedConfigurationAndChangedMasterCannotBeBypassedWithOldManualSelection() throws Exception {
    var f = fixture(); master(f);
    assertCode(sendPatch(f, request(view(f), "水氟SUS304"), TECH), 0, null);
    var stale = request(view(f), "水氟SUS304");
    mappings.create("COMMERCIAL", f.model(), "氟氟316L", 1);
    assertCode(sendPatch(f, stale, TECH), 409, "来源已变化");
    assertThat(view(f).profile().plateMaterial().mode()).isEqualTo(PlateMaterialMode.CONFIG_LOCKED);
    assertCode(sendPatch(f, request(view(f), "水氟SUS304"), TECH), 400, "不允许修改");
    assertCode(sendPatch(f, request(view(f), null), TECH), 0, null);
    var other = fixture(); long masterId = master(other);
    assertCode(sendPatch(other, request(view(other), "水水316L"), TECH), 0, null);
    var oldManual = request(view(other), "水水316L");
    jdbc.update("UPDATE lp_material_master_raw SET active_flag=0 WHERE id=?", masterId);
    assertThat(view(other).profile().plateMaterial().mode()).isEqualTo(PlateMaterialMode.HIDDEN);
    assertCode(sendPatch(other, oldManual, TECH), 409, "来源已变化");
    assertCode(sendPatch(other, request(view(other), null), TECH), 0, null);
    assertThat(view(other).profile().plateMaterial().saved().material()).isNull();
  }

  @Test void historyRemainsImmutableAndReturnedDraftRequiresCurrentSourceConfirmation() throws Exception {
    var f = fixture();
    var mapping = mappings.create("COMMERCIAL", f.model(), "水氟316L", 1);
    assertCode(sendPatch(f, request(view(f), null), TECH), 0, null);
    var first = freeze(f);
    var frozenBefore = jdbc.queryForMap("SELECT * FROM lp_quote_tech_data_version WHERE id=?", first.getTechnicalVersionId());
    mappings.update("COMMERCIAL", mapping.getId(), f.model(), "水水316L", 0, 1);
    var historical = view(f).profile().plateMaterial();
    assertThat(historical.historical()).isTrue();
    assertThat(historical.value().value()).isEqualTo("水氟316L");
    assertThat(historical.sourceChanged()).isFalse();
    var person = recipients.current(f.task()).getFirst();
    participants.restore(products.selectById(f.product()), person, first.getTechnicalVersionId(), TECH.userId());
    recipients.state(person.id(), first.getId(), "PREPARED", "OPEN", "隔离测试退回");
    var restored = view(f);
    assertThat(restored.profile().plateMaterial().saved().material().value()).isEqualTo("水氟316L");
    assertThat(restored.profile().plateMaterial().sourceChanged()).isTrue();
    assertThatThrownBy(() -> freeze(f)).hasMessageContaining("来源已变化");
    assertCode(sendPatch(f, request(restored, null), TECH), 0, null);
    var second = freeze(f);
    assertThat(second.getTechnicalVersionId()).isNotEqualTo(first.getTechnicalVersionId());
    assertThat(second.getContentFingerprint()).isNotEqualTo(first.getContentFingerprint());
    assertThat(summary.summarize(second.getContentSnapshotJson(), first.getContentSnapshotJson()))
        .contains("plateMaterial.material", "水氟316L", "水水316L");
    assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_tech_data_version WHERE id=?", first.getTechnicalVersionId()))
        .isEqualTo(frozenBefore);
  }

  @Test void legacyDraftNeedsConfirmationWhileHistoryWithNoCapturedMaterialStaysUnchanged() throws Exception {
    var f = fixture();
    assertCode(sendPatch(f, request(view(f), null), TECH), 0, null);
    long draftId = view(f).profile().versionId();
    jdbc.update("UPDATE lp_quote_tech_data_version SET plate_material_json=NULL WHERE id=?", draftId);
    assertThat(validation.validate(f.task(), TECH.userId(), TECH).valid()).isFalse();
    assertThatThrownBy(() -> freeze(f)).hasMessageContaining("尚未确认");
    assertCode(sendPatch(f, request(view(f), null), TECH), 0, null);
    var saved = versions.selectById(draftId);
    var oldHistory = TechnicalDataVersionCopies.copiedDraft(saved, 99, TECH.userId(), LocalDateTime.now());
    oldHistory.setPlateMaterialJson(null); oldHistory.setVersionStatus("APPROVED");
    String oldJson = codec.versionContentJson(oldHistory, List.of(), List.of(), List.of(), List.of());
    assertThat(plateProfiles.view(oldHistory, null)).isNull();
    assertThat(plateProfiles.validateCurrent(products.selectById(f.product()), oldHistory)).isEmpty();
    assertThat(codec.versionContentJson(oldHistory, List.of(), List.of(), List.of(), List.of())).isEqualTo(oldJson);
    assertThat(oldJson).doesNotContain("plateMaterial");
  }

  @Test void nonProfileCopyAndSavePreserveMaterialWhilePersonalScopeDoesNotLeakIt() throws Exception {
    var f = fixture(); master(f);
    assertCode(sendPatch(f, request(view(f), "水水316L"), TECH), 0, null);
    var draft = versions.selectById(view(f).profile().versionId());
    var source = new QuoteTechDataVersion();
    source.setNetLossJson("{\"bareMaterialNo\":\"PMM-BARE\",\"rate\":0.01,\"entryMode\":\"MANUAL\"}");
    TechnicalDataVersionCopies.copyModule(source, draft, "NET_LOSS");
    persistence.updateDraft(draft, draft.getRowVersion());
    assertThat(codec.plateMaterial(versions.selectById(draft.getId())).material().value()).isEqualTo("水水316L");
    var profileOnly = TechnicalDataVersionCopies.scopedDraft(draft, 2, 101L, LocalDateTime.now(), Set.of("PROFILE"));
    var lossOnly = TechnicalDataVersionCopies.scopedDraft(draft, 3, 102L, LocalDateTime.now(), Set.of("NET_LOSS"));
    assertThat(profileOnly.getPlateMaterialJson()).isEqualTo(draft.getPlateMaterialJson());
    assertThat(profileOnly.getNetLossJson()).isNull();
    assertThat(lossOnly.getPlateMaterialJson()).isNull();
    assertThat(lossOnly.getNetLossJson()).isEqualTo(source.getNetLossJson());
  }

  private TechnicalDataProductResponse view(Fixture f) {
    return tasks.detail(f.task(), TECH).products().getFirst();
  }

  private QuoteTechSubmission freeze(Fixture f) {
    var task = tasks.detail(f.task(), TECH);
    return snapshots.prepare(f.task(), "PMM-" + UUID.randomUUID(), task.taskVersion(),
        task.products().getFirst().rowVersion(), recipients.current(f.task()).getFirst(), TECH);
  }

  private Long acceptAndPublish(Fixture f, QuoteTechSubmission submission) {
    return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
      var person = recipients.current(f.task()).getFirst();
      var frozen = versions.selectById(submission.getTechnicalVersionId());
      participants.transition(frozen, "SUBMITTED", TECH.userId());
      participants.state(products.selectById(f.product()), person, frozen.getId(), "SUBMITTED");
      oaWorkflow.markSending(submission.getId());
      oaWorkflow.acceptSubmission(submission.getId(), "PMM-ACCEPTED-" + f.task());
      recipients.state(person.id(), submission.getId(), "PREPARED", "SUBMITTED", null);
      recipients.refreshTask(f.task());
      lifecycle.publishSubmitted(taskMapper.selectById(f.task()),
          submissionMapper.selectById(submission.getId()), TECH.userId());
      return products.selectById(f.product()).getEffectiveVersionId();
    });
  }

  private int publicationCount(QuoteTechSubmission submission) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM lp_business_change_log WHERE idempotency_key=?",
        Integer.class, "TD-PLATE-MATERIAL:" + submission.getTechnicalVersionId());
  }

  private com.fasterxml.jackson.databind.node.ObjectNode request(TechnicalDataProductResponse product, String material) {
    var request = json.createObjectNode().put("productProperty", "非标品").put("hasAdditionalFees", false)
        .put("unitMouldFee", "0.3").put("expectedVersion", product.rowVersion())
        .put("expectedPlateMaterialRevision", product.profile().plateMaterial().revision());
    if (material != null) request.put("plateMaterial", material);
    return request;
  }

  private JsonNode sendPatch(Fixture f, JsonNode request, TechnicalDataActor actor) throws Exception {
    var mvc = MockMvcBuilders.standaloneSetup(new TechnicalDataProfileController(profiles, () -> actor)).build();
    var response = mvc.perform(patch("/api/v2/technical-data/products/{id}/profile", f.product())
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
        .andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    return json.readTree(response.getContentAsByteArray());
  }

  private void assertCode(JsonNode result, int code, String message) {
    assertThat(result.path("code").asInt()).as(result.toString()).isEqualTo(code);
    if (message != null) assertThat(result.path("msg").asText()).contains(message);
  }

  private Map<String, Object> state(Fixture f) {
    return Map.of("task", jdbc.queryForMap("SELECT * FROM lp_quote_tech_task WHERE id=?", f.task()),
        "product", jdbc.queryForMap("SELECT * FROM lp_quote_tech_product WHERE id=?", f.product()),
        "versions", jdbc.queryForList("SELECT * FROM lp_quote_tech_data_version WHERE product_id=? ORDER BY id", f.product()),
        "modules", jdbc.queryForList("SELECT * FROM lp_quote_tech_module WHERE product_id=? ORDER BY id", f.product()),
        "submissions", jdbc.queryForList("SELECT * FROM lp_quote_tech_submission WHERE task_id=? ORDER BY id", f.task()));
  }

  private long master(Fixture f) {
    jdbc.update("""
        INSERT INTO lp_material_master_raw(organization_code,material_code,material_model,production_division,
          active_flag,import_batch_id) VALUES ('PLATE',?,?,'板换事业部',1,'PMM-04-TEST')
        """, f.model(), f.model());
    return jdbc.queryForObject("SELECT id FROM lp_material_master_raw WHERE material_code=? AND organization_code='PLATE'",
        Long.class, f.model());
  }

  private Fixture fixture() {
    return fixture("PMM04-" + UUID.randomUUID());
  }

  private Fixture fixture(String model) {
    long itemId = IDS.incrementAndGet(); String key = "PMM04-" + UUID.randomUUID();
    jdbc.update("""
        INSERT INTO lp_quote_tech_task(task_no,oa_form_id,oa_form_item_id,oa_no,accounting_month,
          business_unit_type,applicable_org_code,assignee_user_id,assignee_name,active_flag,active_lock_key,
          oa_assignment_version,external_task_status)
        VALUES(?,?,?,?,'2026-09','COMMERCIAL','220',101,'板换测试技术员',1,?,1,'PUBLISHED')
        """, key, itemId + 1000, itemId, key, "ITEM:" + itemId + ":MONTH:2026-09");
    long task = jdbc.queryForObject("SELECT id FROM lp_quote_tech_task WHERE task_no=?", Long.class, key);
    String source = json.createObjectNode().put("sourceModel", model).put("businessUnitType", "COMMERCIAL")
        .put("materialOrganizationCode", "PLATE").put("productionDivision", "板换事业部").toString();
    jdbc.update("""
        INSERT INTO lp_quote_tech_product(task_id,oa_form_item_id,level_no,source_model,quote_no,accounting_month,
          source_snapshot_json,source_fingerprint,active_flag,active_lock_key,content_schema_version)
        VALUES(?,?,1,?,?,'2026-09',?,REPEAT('a',64),1,?,2)
        """, task, itemId, model, key, source, "ITEM:" + itemId + ":MONTH:2026-09");
    long product = jdbc.queryForObject("SELECT id FROM lp_quote_tech_product WHERE task_id=?", Long.class, task);
    for (String type : TechnicalDataModuleType.orderedCodes()) {
      boolean required = type.equals("PROFILE");
      jdbc.update("""
          INSERT INTO lp_quote_tech_module(product_id,module_type,required_flag,requirement_reason_code,
            requirement_reason,module_status,source_availability,assignee_user_id,assignee_name,source_checked_at,source_reference)
          VALUES(?,?,?,'ISOLATED_TEST','PMM04 隔离来源',?,?,?,?,NOW(6),'PMM04_TEST')
          """, product, type, required ? 1 : 0, required ? "PENDING" : "NOT_REQUIRED",
          required ? "MISSING" : "AVAILABLE", required ? 101L : null, required ? TECH.name() : null);
    }
    jdbc.update("""
        INSERT INTO lp_oa_integration_message(source_system,environment,direction,request_id,interface_type,
          schema_version,occurred_at,raw_payload,payload_hash,allowed_business_units)
        VALUES('TEST','UNIT','OUTBOUND',?,'TASK_DISPATCH',1,NOW(3),'{}',REPEAT('b',64),'COMMERCIAL')
        """, key);
    long message = jdbc.queryForObject("SELECT id FROM lp_oa_integration_message WHERE request_id=?", Long.class, key);
    jdbc.update("""
        INSERT INTO lp_quote_tech_oa_recipient(task_id,assignment_version,assignee_user_id,assignee_name,
          external_user_id,module_types_json,outbound_message_id,external_task_id,dispatch_status,todo_status,active_flag,leader_external_id)
        VALUES(?,1,101,'板换测试技术员','oa-pmm-test','["PROFILE"]',?,'PMM-TEST-TODO','CONFIRMED','OPEN',1,'oa-pmm-test-leader')
        """, task, message);
    return new Fixture(task, product, model);
  }

  private record Fixture(long task, long product, String model) {}
}
