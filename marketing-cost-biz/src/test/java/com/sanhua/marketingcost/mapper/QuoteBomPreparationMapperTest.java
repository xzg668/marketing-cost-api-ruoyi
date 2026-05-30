package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomCostingRowSourceRef;
import com.sanhua.marketingcost.entity.BusinessChangeLog;
import com.sanhua.marketingcost.entity.QuoteBomPackageReference;
import com.sanhua.marketingcost.entity.QuoteBomPackageReferenceDetail;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("QBP-02 报价产品 BOM 准备 Mapper CRUD")
class QuoteBomPreparationMapperTest extends BomMapperTestBase {

  private final String oaNo = "OA-QBP-" + UUID.randomUUID().toString().substring(0, 8);

  @Autowired private QuoteBomPreparationRecordMapper preparationMapper;
  @Autowired private QuoteBomPackageReferenceMapper packageReferenceMapper;
  @Autowired private QuoteBomPackageReferenceDetailMapper packageReferenceDetailMapper;
  @Autowired private QuoteBomSupplementVersionMapper supplementVersionMapper;
  @Autowired private QuoteBomSupplementDetailMapper supplementDetailMapper;
  @Autowired private BusinessChangeLogMapper changeLogMapper;
  @Autowired private BomCostingRowSourceRefMapper costingRowSourceRefMapper;

  @BeforeAll
  static void initQbpTables() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(
          """
          CREATE TABLE IF NOT EXISTS lp_quote_bom_status (
            id BIGINT NOT NULL AUTO_INCREMENT,
            oa_form_id BIGINT NOT NULL,
            oa_form_item_id BIGINT NOT NULL,
            oa_no VARCHAR(64) NOT NULL,
            product_code VARCHAR(64) DEFAULT NULL,
            bom_status VARCHAR(32) NOT NULL DEFAULT 'NOT_CHECKED',
            sync_batch_id VARCHAR(64) DEFAULT NULL,
            manual_task_no VARCHAR(128) DEFAULT NULL,
            supplement_task_id BIGINT DEFAULT NULL,
            technician_name VARCHAR(128) DEFAULT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            UNIQUE KEY uk_quote_bom_status_item (oa_form_item_id)
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
    }
    runV142ViaMysqlCli("V142_QBP_MAPPER_FIRST");
    runV142ViaMysqlCli("V142_QBP_MAPPER_SECOND");
  }

  @AfterEach
  void cleanUp() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate("DELETE FROM lp_bom_costing_row_source_ref WHERE oa_no = '" + oaNo + "'");
      stmt.executeUpdate("DELETE FROM lp_business_change_log WHERE oa_no = '" + oaNo + "'");
      stmt.executeUpdate("DELETE FROM lp_quote_bom_supplement_detail WHERE oa_no = '" + oaNo + "'");
      stmt.executeUpdate("DELETE FROM lp_quote_bom_supplement_version WHERE oa_no = '" + oaNo + "'");
      stmt.executeUpdate(
          "DELETE FROM lp_quote_bom_package_reference_detail WHERE oa_no = '" + oaNo + "'");
      stmt.executeUpdate("DELETE FROM lp_quote_bom_package_reference WHERE oa_no = '" + oaNo + "'");
      stmt.executeUpdate("DELETE FROM lp_quote_bom_preparation_record WHERE oa_no = '" + oaNo + "'");
    }
  }

  @Test
  @DisplayName("准备记录 insert/select/update：可保存裸品包装参考上下文")
  void preparationRecordCrud() {
    QuoteBomPreparationRecord record = newPreparationRecord();

    assertThat(preparationMapper.insert(record)).isEqualTo(1);
    assertThat(record.getId()).isNotNull().isPositive();

    QuoteBomPreparationRecord loaded = preparationMapper.selectById(record.getId());
    assertThat(loaded.getQuoteProductCode()).isEqualTo("110000000001");
    assertThat(loaded.getProductType()).isEqualTo("BARE");
    assertThat(loaded.getBareProductCode()).isEqualTo("110000000001");
    assertThat(loaded.getReferenceFinishedCode()).isEqualTo("1079900000538");
    assertThat(loaded.getSourceTopProductCode()).isEqualTo("1079900000538");

    QuoteBomPreparationRecord patch = new QuoteBomPreparationRecord();
    patch.setId(record.getId());
    patch.setReviewStatus("APPROVED");
    patch.setReviewedAt(LocalDateTime.of(2026, 5, 28, 14, 30));
    assertThat(preparationMapper.updateById(patch)).isEqualTo(1);
    assertThat(preparationMapper.selectById(record.getId()).getReviewStatus()).isEqualTo("APPROVED");
  }

  @Test
  @DisplayName("包装参考明细保存快照原值、调整后值和来源成品隔离字段")
  void packageReferenceDetailCrud() {
    QuoteBomPreparationRecord record = insertPreparationRecord();
    QuoteBomPackageReference reference = newPackageReference(record.getId());

    assertThat(packageReferenceMapper.insert(reference)).isEqualTo(1);
    QuoteBomPackageReferenceDetail detail =
        newPackageReferenceDetail(record.getId(), reference.getId());
    assertThat(packageReferenceDetailMapper.insert(detail)).isEqualTo(1);

    QuoteBomPackageReferenceDetail loaded = packageReferenceDetailMapper.selectById(detail.getId());
    assertThat(loaded.getReferenceFinishedCode()).isEqualTo("1079900000538");
    assertThat(loaded.getSourceTopProductCode()).isEqualTo("1079900000538");
    assertThat(loaded.getPackageParentCode()).isEqualTo("151550000001");
    assertThat(loaded.getPackageMaterialCode()).isEqualTo("PKG-CHILD-001");
    assertThat(loaded.getChildQtyPerParent()).isEqualByComparingTo("2.00000000");
    assertThat(loaded.getAdjustedChildQtyPerParent()).isEqualByComparingTo("2.50000000");
    assertThat(loaded.getEditedFlag()).isEqualTo(1);

    assertThat(packageReferenceDetailMapper.selectCount(
            Wrappers.lambdaQuery(QuoteBomPackageReferenceDetail.class)
                .eq(QuoteBomPackageReferenceDetail::getReferenceFinishedCode, "1079900000538")
                .eq(QuoteBomPackageReferenceDetail::getSourceTopProductCode, "1079900000538")
                .eq(QuoteBomPackageReferenceDetail::getPackageParentCode, "151550000001")))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("补录 BOM 版本和明细支持非裸品完整 BOM / 裸品本体 BOM 区分")
  void supplementVersionAndDetailCrud() {
    QuoteBomPreparationRecord record = insertPreparationRecord();
    QuoteBomSupplementVersion version = newSupplementVersion(record.getId());
    assertThat(supplementVersionMapper.insert(version)).isEqualTo(1);

    QuoteBomSupplementDetail detail = newSupplementDetail(record.getId(), version.getId());
    assertThat(supplementDetailMapper.insert(detail)).isEqualTo(1);

    QuoteBomSupplementDetail loaded = supplementDetailMapper.selectById(detail.getId());
    assertThat(loaded.getSupplementScope()).isEqualTo("BARE_BODY_BOM");
    assertThat(loaded.getMaterialCode()).isEqualTo("MAT-BODY-001");
    assertThat(loaded.getQtyPerParent()).isEqualByComparingTo("1.20000000");

    QuoteBomSupplementVersion patch = new QuoteBomSupplementVersion();
    patch.setId(version.getId());
    patch.setVersionStatus("APPROVED");
    patch.setReuseValidUntil(LocalDate.of(2026, 11, 30));
    assertThat(supplementVersionMapper.updateById(patch)).isEqualTo(1);
    assertThat(supplementVersionMapper.selectById(version.getId()).getReuseValidUntil())
        .isEqualTo(LocalDate.of(2026, 11, 30));
  }

  @Test
  @DisplayName("统一变更日志和结算行来源追溯可插入查询")
  void changeLogAndCostingSourceRefCrud() {
    QuoteBomPreparationRecord record = insertPreparationRecord();

    BusinessChangeLog log = new BusinessChangeLog();
    log.setBizDomain("QUOTE_BOM_PREPARATION");
    log.setBizType("PACKAGE_REFERENCE_DETAIL");
    log.setBizId(record.getId());
    log.setBizDetailId(9002L);
    log.setOaNo(oaNo);
    log.setOaFormItemId(3001L);
    log.setTaskId(7001L);
    log.setFieldName("adjustedChildQtyPerParent");
    log.setFieldLabel("子件用量");
    log.setBeforeValue("2.00000000");
    log.setAfterValue("2.50000000");
    log.setChangedByName("技术员A");
    log.setChangeSource("OA_COLLABORATION");
    log.setSubmitBatchNo("SUBMIT-" + oaNo);
    assertThat(changeLogMapper.insert(log)).isEqualTo(1);
    assertThat(changeLogMapper.selectById(log.getId()).getAfterValue()).isEqualTo("2.50000000");

    BomCostingRowSourceRef ref = new BomCostingRowSourceRef();
    ref.setCostingRowId(91001L);
    ref.setOaNo(oaNo);
    ref.setOaFormItemId(3001L);
    ref.setQuoteProductCode("110000000001");
    ref.setSourcePartType("REFERENCED_PACKAGE");
    ref.setSourceTaskId(7001L);
    ref.setPreparationId(record.getId());
    ref.setPackageReferenceId(8001L);
    ref.setPackageReferenceDetailId(8002L);
    ref.setReferenceFinishedCode("1079900000538");
    ref.setSourceTopProductCode("1079900000538");
    assertThat(costingRowSourceRefMapper.insert(ref)).isEqualTo(1);
    assertThat(costingRowSourceRefMapper.selectById(ref.getId()).getSourcePartType())
        .isEqualTo("REFERENCED_PACKAGE");
  }

  private QuoteBomPreparationRecord insertPreparationRecord() {
    QuoteBomPreparationRecord record = newPreparationRecord();
    preparationMapper.insert(record);
    return record;
  }

  private QuoteBomPreparationRecord newPreparationRecord() {
    QuoteBomPreparationRecord record = new QuoteBomPreparationRecord();
    record.setOaFormId(2001L);
    record.setOaFormItemId(3001L);
    record.setOaNo(oaNo);
    record.setQuoteProductCode("110000000001");
    record.setProductType("BARE");
    record.setBareProductCode("110000000001");
    record.setNeedPackage(1);
    record.setReferenceFinishedCode("1079900000538");
    record.setSourceTopProductCode("1079900000538");
    record.setCostPeriodMonth("2026-05");
    record.setPreparationStatus("NEED_TECH");
    record.setReviewStatus("PENDING");
    record.setTaskId(7001L);
    record.setActiveFlag(1);
    return record;
  }

  private QuoteBomPackageReference newPackageReference(Long preparationId) {
    QuoteBomPackageReference reference = new QuoteBomPackageReference();
    reference.setPreparationId(preparationId);
    reference.setTaskId(7001L);
    reference.setOaNo(oaNo);
    reference.setOaFormItemId(3001L);
    reference.setQuoteProductCode("110000000001");
    reference.setBareProductCode("110000000001");
    reference.setReferenceFinishedCode("1079900000538");
    reference.setSourceTopProductCode("1079900000538");
    reference.setPeriodMonth("2026-05");
    reference.setSnapshotId(6001L);
    reference.setReferenceStatus("SUBMITTED");
    reference.setSelectedLineCount(1);
    reference.setEditedFlag(1);
    reference.setActiveFlag(1);
    return reference;
  }

  private QuoteBomPackageReferenceDetail newPackageReferenceDetail(
      Long preparationId, Long packageReferenceId) {
    QuoteBomPackageReferenceDetail detail = new QuoteBomPackageReferenceDetail();
    detail.setPackageReferenceId(packageReferenceId);
    detail.setPreparationId(preparationId);
    detail.setTaskId(7001L);
    detail.setOaNo(oaNo);
    detail.setOaFormItemId(3001L);
    detail.setBareProductCode("110000000001");
    detail.setReferenceFinishedCode("1079900000538");
    detail.setSourceTopProductCode("1079900000538");
    detail.setSnapshotId(6001L);
    detail.setSnapshotDetailId(6002L);
    detail.setLineNo(1);
    detail.setPackageParentCode("151550000001");
    detail.setPackageParentName("包装组件");
    detail.setPackageParentMainCategoryCode("1515501");
    detail.setPackageParentShapeAttr("虚拟");
    detail.setPackageParentUnit("EA");
    detail.setPackageQtyPerParent(new BigDecimal("1.00000000"));
    detail.setPackageQtyPerTop(new BigDecimal("1.00000000"));
    detail.setPackageParentBaseQty(new BigDecimal("1.00000000"));
    detail.setAdjustedPackageQtyPerParent(new BigDecimal("1.00000000"));
    detail.setPackageMaterialCode("PKG-CHILD-001");
    detail.setPackageMaterialName("纸箱");
    detail.setPackageMaterialMainCategoryCode("1515601");
    detail.setPackageMaterialUnit("PCS");
    detail.setChildQtyPerParent(new BigDecimal("2.00000000"));
    detail.setChildQtyPerTop(new BigDecimal("2.00000000"));
    detail.setChildParentBaseQty(new BigDecimal("1.00000000"));
    detail.setAdjustedChildQtyPerParent(new BigDecimal("2.50000000"));
    detail.setAdjustedChildQtyPerTop(new BigDecimal("2.50000000"));
    detail.setQtyPerTop(new BigDecimal("2.50000000"));
    detail.setUnit("PCS");
    detail.setSourceRawHierarchyId(5001L);
    detail.setSourceU9BomId(5002L);
    detail.setSourceParentCode("151550000001");
    detail.setSourcePath("/1079900000538/151550000001/PKG-CHILD-001/");
    detail.setSelectedFlag(1);
    detail.setEditedFlag(1);
    return detail;
  }

  private QuoteBomSupplementVersion newSupplementVersion(Long preparationId) {
    QuoteBomSupplementVersion version = new QuoteBomSupplementVersion();
    version.setPreparationId(preparationId);
    version.setTaskId(7001L);
    version.setTaskNo("QBP-TASK-" + oaNo);
    version.setOaNo(oaNo);
    version.setOaFormItemId(3001L);
    version.setQuoteProductCode("110000000001");
    version.setProductType("BARE");
    version.setSupplementScope("BARE_BODY_BOM");
    version.setBomSource("TECH_SUPPLEMENT");
    version.setVersionNo(1);
    version.setVersionStatus("SUBMITTED");
    version.setActiveFlag(1);
    version.setPeriodMonth("2026-05");
    version.setEffectiveFrom(LocalDate.of(2026, 5, 1));
    version.setSubmittedByName("技术员A");
    return version;
  }

  private QuoteBomSupplementDetail newSupplementDetail(Long preparationId, Long versionId) {
    QuoteBomSupplementDetail detail = new QuoteBomSupplementDetail();
    detail.setSupplementVersionId(versionId);
    detail.setPreparationId(preparationId);
    detail.setTaskId(7001L);
    detail.setOaNo(oaNo);
    detail.setOaFormItemId(3001L);
    detail.setQuoteProductCode("110000000001");
    detail.setSupplementScope("BARE_BODY_BOM");
    detail.setLineNo(1);
    detail.setLevel(1);
    detail.setParentCode("110000000001");
    detail.setMaterialCode("MAT-BODY-001");
    detail.setMaterialName("裸品子件");
    detail.setQtyPerParent(new BigDecimal("1.20000000"));
    detail.setQtyPerTop(new BigDecimal("1.20000000"));
    detail.setParentBaseQty(new BigDecimal("1.00000000"));
    detail.setUnit("PCS");
    detail.setPath("/110000000001/MAT-BODY-001/");
    detail.setManualFlag(1);
    return detail;
  }

  private static void runV142ViaMysqlCli(String logTag) throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V142__quote_bom_preparation_schema.sql"),
        "/tmp/" + logTag + ".sql");
    ExecResult result =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
                + " " + MYSQL.getDatabaseName() + " < /tmp/" + logTag + ".sql");
    if (result.getExitCode() != 0) {
      throw new IllegalStateException(
          logTag + " mysql CLI 执行失败: exit=" + result.getExitCode()
              + "\nstdout:\n" + result.getStdout()
              + "\nstderr:\n" + result.getStderr());
    }
  }
}
