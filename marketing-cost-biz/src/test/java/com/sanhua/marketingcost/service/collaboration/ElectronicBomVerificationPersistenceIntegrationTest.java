package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("QCBP-11 电子图库校验结果真实MySQL持久化")
class ElectronicBomVerificationPersistenceIntegrationTest extends BomMapperTestBase {

  private static final CollaborationScope SCOPE = new CollaborationScope("COMMERCIAL", "210");
  private static final CollaborationPrincipal TECHNICIAN = new CollaborationPrincipal(
      601L, "王工", Set.of(CollaborationRole.TECHNICIAN));

  @Autowired private ElectronicBomVerificationPersistenceService persistence;
  @Autowired private CollaborationProductStateService stateService;
  @Autowired private QuoteCollaborationTaskRepository repository;
  @Autowired private QuoteBomPreparationRecordMapper preparationMapper;
  @Autowired private QuoteBomSupplementVersionMapper versionMapper;
  @Autowired private QuoteBomSupplementDetailMapper detailMapper;
  @Autowired private JdbcTemplate jdbc;

  private final String marker = "Q11-" + UUID.randomUUID().toString().replace("-", "")
      .substring(0, 12);
  private Long masterId;
  private Long productTaskId;
  private Long preparationId;
  private Long supplementVersionId;

  @BeforeAll
  static void ensureSchemas() throws Exception {
    int index = 0;
    for (String resource : List.of(
        "/db/V142__quote_bom_preparation_schema.sql",
        "/db/V206__quote_bom_price_collaboration_schema.sql")) {
      String target = "/tmp/QCBP11-" + (++index) + ".sql";
      MYSQL.copyFileToContainer(MountableFile.forClasspathResource(resource), target);
      ExecResult result = MYSQL.execInContainer(
          "sh", "-c",
          "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
              + " " + MYSQL.getDatabaseName() + " < " + target);
      if (result.getExitCode() != 0) {
        throw new IllegalStateException(
            resource + " 执行失败：" + result.getStderr() + result.getStdout());
      }
    }
  }

  @AfterEach
  void clean() {
    if (productTaskId != null) {
      jdbc.update("DELETE FROM lp_integration_outbox WHERE aggregate_type='PRODUCT_TASK' AND aggregate_id=?",
          productTaskId);
      jdbc.update("DELETE FROM lp_quote_collaboration_gap WHERE product_task_id=?", productTaskId);
      jdbc.update("DELETE FROM lp_quote_collaboration_quote_link WHERE product_task_id=?", productTaskId);
      jdbc.update("DELETE FROM lp_quote_collaboration_product_task WHERE id=?", productTaskId);
    }
    if (masterId != null) {
      jdbc.update("DELETE FROM lp_quote_collaboration_task WHERE id=?", masterId);
    }
    if (supplementVersionId != null) {
      jdbc.update("DELETE FROM lp_quote_bom_supplement_detail WHERE supplement_version_id=?",
          supplementVersionId);
      jdbc.update("DELETE FROM lp_quote_bom_supplement_version WHERE id=?", supplementVersionId);
    }
    if (preparationId != null) {
      jdbc.update("DELETE FROM lp_quote_bom_preparation_record WHERE id=?", preparationId);
    }
  }

  @Test
  @DisplayName("失败保留同一任务和原因，重试成功替换BOM、保存指纹并保留失败审计")
  void failureThenRetrySuccessPreservesAuditAndReplacesElectronicBom() {
    QuoteCollaborationProductTask task = createTaskAndDraft();
    var failed = persistence.persistFailure(task.getId(), task.getTaskVersion(), TECHNICIAN,
        SCOPE, List.of(new ElectronicBomValidationIssue(
            "CHILD-1", "/" + marker + "/CHILD-1/", "MANUFACTURE_CHILD_REQUIRED",
            "制造件必须至少包含一个有效下级")));

    assertThat(failed.task().getId()).isEqualTo(task.getId());
    assertThat(failed.task().getTaskStatus()).isEqualTo("TECH_VALIDATION_FAILED");
    assertThat(failed.task().getLastValidationStatus()).isEqualTo("FAILED");
    assertThat(repository.findGaps(task.getId(), SCOPE)).singleElement().satisfies(gap -> {
      assertThat(gap.getGapStatus()).isEqualTo("OPEN");
      assertThat(gap.getReasonCode()).isEqualTo("MANUFACTURE_CHILD_REQUIRED");
      assertThat(gap.getBomNodeKey()).isEqualTo("CHILD-1");
    });
    String failurePayload = jdbc.queryForObject("""
        SELECT payload_json FROM lp_integration_outbox
        WHERE aggregate_type='PRODUCT_TASK' AND aggregate_id=?
          AND event_type='TECH_TASK_UPDATED' AND aggregate_version=?
        """, String.class, task.getId(), failed.task().getTaskVersion());
    assertThat(failurePayload)
        .contains("MANUFACTURE_CHILD_REQUIRED", "制造件必须至少包含一个有效下级")
        .doesNotContain("quantityPerParent");

    QuoteCollaborationProductTask retrying = stateService.transition(
        task.getId(), failed.task().getTaskVersion(), SCOPE,
        ProductAction.RETRY_BOM, TECHNICIAN).task();
    var verified = persistence.persistVerifiedBom(
        task.getId(), retrying.getTaskVersion(), TECHNICIAN, SCOPE, validBom());

    assertThat(verified.fingerprint()).hasSize(64);
    assertThat(verified.task().getElectronicBomFingerprint()).isEqualTo(verified.fingerprint());
    assertThat(versionMapper.selectById(supplementVersionId)).satisfies(version -> {
      assertThat(version.getBomSource()).isEqualTo("ELECTRONIC_DRAWING");
      assertThat(version.getVersionStatus()).isEqualTo("DRAFT");
      assertThat(version.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 8, 1));
    });
    List<QuoteBomSupplementDetail> details = detailMapper.selectList(
        Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
            .eq(QuoteBomSupplementDetail::getSupplementVersionId, supplementVersionId)
            .orderByAsc(QuoteBomSupplementDetail::getLineNo));
    assertThat(details).hasSize(2);
    assertThat(details).extracting(QuoteBomSupplementDetail::getMaterialCode)
        .containsExactly(marker, marker + "-RAW");
    assertThat(details.get(1).getParentCode()).isEqualTo(marker);
    assertThat(details.get(1).getQtyPerTop()).isEqualByComparingTo("0.28600000");
    assertThat(details).extracting(QuoteBomSupplementDetail::getShapeAttr)
        .containsExactly("制造件", "采购件");
    assertThat(details).allSatisfy(detail -> assertThat(detail.getSourceCategory()).isNull());
    assertThat(details).allSatisfy(detail -> {
      assertThat(detail.getTaskId()).isNull();
      assertThat(detail.getRemark()).isEqualTo("ELECTRONIC_DRAWING:ELECTRONIC_DRAWING");
    });

    var ready = persistence.persistPriceScan(task.getId(), verified.task().getTaskVersion(),
        TECHNICIAN, SCOPE, CollaborationPriceScanResult.ready(2));
    assertThat(ready.gapCount()).isZero();
    assertThat(ready.continuedToPrice()).isFalse();
    assertThat(ready.task().getTaskStatus()).isEqualTo("BOM_IN_PROGRESS");
    assertThat(ready.task().getLastValidationStatus()).isEqualTo("PASSED");
    assertThat(repository.findGaps(task.getId(), SCOPE)).singleElement()
        .extracting(gap -> gap.getGapStatus()).isEqualTo("OBSOLETE");
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM lp_integration_outbox
        WHERE aggregate_type='PRODUCT_TASK' AND aggregate_id=?
          AND event_type='TECH_TASK_UPDATED'
        """, Integer.class, task.getId())).isGreaterThanOrEqualTo(2);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_collaboration_product_task WHERE id=?",
        Integer.class, task.getId())).isOne();
  }

  @Test
  @DisplayName("财务退回的BOM可直接修改并重新回取，不会在最后一步被状态拦截")
  void returnedBomCanBeVerifiedAndPriceCheckedInPlace() {
    QuoteCollaborationProductTask task = createTaskAndDraft();
    assertThat(jdbc.update("""
        UPDATE lp_quote_collaboration_product_task
        SET task_status='RETURNED_TO_TECH'
        WHERE id=? AND task_version=?
        """, task.getId(), task.getTaskVersion())).isOne();
    task = repository.findProductTaskById(task.getId(), SCOPE).orElseThrow();

    var verified = persistence.persistVerifiedBom(
        task.getId(), task.getTaskVersion(), TECHNICIAN, SCOPE, validBom());

    assertThat(verified.task().getTaskStatus()).isEqualTo("RETURNED_TO_TECH");
    assertThat(verified.task().getElectronicBomFingerprint()).hasSize(64);
    var checked = persistence.persistPriceScan(
        task.getId(), verified.task().getTaskVersion(), TECHNICIAN, SCOPE,
        CollaborationPriceScanResult.ready(1));
    assertThat(checked.task().getTaskStatus()).isEqualTo("RETURNED_TO_TECH");
    assertThat(checked.task().getLastValidationStatus()).isEqualTo("PASSED");
  }

  private QuoteCollaborationProductTask createTaskAndDraft() {
    QuoteCollaborationTask master = new QuoteCollaborationTask();
    master.setOaFormId(positiveKey(marker + "-FORM"));
    master.setOaNo(marker + "-OA");
    master.setBusinessUnitType(SCOPE.businessUnitType());
    master.setAccountingMonth("2026-08");
    master.setMasterStatus("WAIT_TECH");
    master.setFinanceReviewerUserId(701L);
    master.setFinanceReviewerName("财务审核员");
    master = repository.saveTask(master);
    masterId = master.getId();

    QuoteBomPreparationRecord preparation = new QuoteBomPreparationRecord();
    preparation.setOaFormId(master.getOaFormId());
    preparation.setOaFormItemId(positiveKey(marker + "-ITEM"));
    preparation.setOaNo(master.getOaNo());
    preparation.setQuoteProductCode(marker);
    preparation.setProductType("NON_BARE");
    preparation.setNeedPackage(0);
    preparation.setCostPeriodMonth("2026-08");
    preparation.setPreparationStatus("NEED_TECH");
    preparation.setReviewStatus("NOT_SUBMITTED");
    preparation.setTechnicianUserId(TECHNICIAN.userId());
    preparation.setTechnicianName(TECHNICIAN.userName());
    preparation.setActiveFlag(1);
    assertThat(preparationMapper.insert(preparation)).isOne();
    preparationId = preparation.getId();

    QuoteBomSupplementVersion version = new QuoteBomSupplementVersion();
    version.setPreparationId(preparationId);
    version.setTaskId(null);
    version.setTaskNo(null);
    version.setOaNo(master.getOaNo());
    version.setOaFormItemId(preparation.getOaFormItemId());
    version.setQuoteProductCode(marker);
    version.setProductType("NON_BARE");
    version.setSupplementScope("NON_BARE_FULL_BOM");
    version.setBomSource("TECH_SUPPLEMENT");
    version.setVersionNo(1);
    version.setVersionStatus("DRAFT");
    version.setActiveFlag(1);
    version.setPeriodMonth("2026-08");
    assertThat(versionMapper.insert(version)).isOne();
    supplementVersionId = version.getId();
    insertOldDraftDetail(version, preparation);

    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setOriginCollaborationId(master.getId());
    task.setAccountingMonth("2026-08");
    task.setBusinessUnitType(SCOPE.businessUnitType());
    task.setApplicableOrgCode(SCOPE.applicableOrgCode());
    task.setMaterialOrgCode("COMMERCIAL");
    task.setPriceOrgCode(SCOPE.applicableOrgCode());
    task.setProductCode(marker);
    task.setProductName("热力膨胀阀");
    task.setProductSpec("RFKH11");
    task.setProductModel("RFKH11E-4.5-54A");
    task.setProductForm("NORMAL");
    task.setPrimaryScope("FULL_BOM");
    task.setNeedBom(1);
    task.setNeedPackage(0);
    task.setNeedPrice(1);
    task.setTaskStatus("BOM_IN_PROGRESS");
    task.setOriginalTechnicianUserId(TECHNICIAN.userId());
    task.setOriginalTechnicianName(TECHNICIAN.userName());
    task.setCurrentAssigneeUserId(TECHNICIAN.userId());
    task.setCurrentAssigneeName(TECHNICIAN.userName());
    task.setActiveLockKey("COMMERCIAL:210:" + marker);
    task.setPreparationId(preparationId);
    task.setSupplementVersionId(supplementVersionId);
    task = repository.saveProductTask(task);
    productTaskId = task.getId();
    return task;
  }

  private void insertOldDraftDetail(
      QuoteBomSupplementVersion version, QuoteBomPreparationRecord preparation) {
    QuoteBomSupplementDetail detail = new QuoteBomSupplementDetail();
    detail.setSupplementVersionId(version.getId());
    detail.setPreparationId(preparation.getId());
    detail.setOaNo(preparation.getOaNo());
    detail.setOaFormItemId(preparation.getOaFormItemId());
    detail.setQuoteProductCode(marker);
    detail.setSupplementScope("NON_BARE_FULL_BOM");
    detail.setLineNo(1);
    detail.setLevel(0);
    detail.setMaterialCode(marker + "-OLD");
    detail.setMaterialName("待替换旧草稿");
    detail.setQtyPerParent(BigDecimal.ONE);
    detail.setQtyPerTop(BigDecimal.ONE);
    detail.setParentBaseQty(BigDecimal.ONE);
    detail.setUnit("件");
    detail.setPath("/" + marker + "-OLD/");
    detail.setManualFlag(1);
    assertThat(detailMapper.insert(detail)).isOne();
  }

  private ValidatedElectronicBom validBom() {
    return new ValidatedElectronicBom(
        "ELECTRONIC_DRAWING", marker, "COMMERCIAL", "主制造", "V6", "ACTIVE",
        LocalDate.of(2026, 8, 1), null,
        OffsetDateTime.parse("2026-08-13T14:00:00+08:00"),
        List.of(
            new ValidatedElectronicBom.Node(
                "ROOT", null, 0, null, marker, "热力膨胀阀", "RFKH11",
                "RFKH11E-4.5-54A", "RFKH11-ZT", "MANUFACTURE", BigDecimal.ONE,
                BigDecimal.ONE, "件", 1, "/" + marker + "/"),
            new ValidatedElectronicBom.Node(
                "RAW", "ROOT", 1, marker, marker + "-RAW", "紫铜管",
                "TP2 Φ9.52×0.7", "TP2-952-07", "TP2-952-07", "PURCHASE",
                new BigDecimal("0.286"), new BigDecimal("0.286"), "kg", 2,
                "/" + marker + "/" + marker + "-RAW/")));
  }

  private static long positiveKey(String value) {
    long hash = value.hashCode();
    return hash == Integer.MIN_VALUE ? Integer.MAX_VALUE : Math.abs(hash) + 1L;
  }
}
