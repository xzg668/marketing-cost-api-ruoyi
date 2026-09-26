package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProfileUpdateRequest;
import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.QuoteTechAuxItemMapper;
import com.sanhua.marketingcost.mapper.QuoteTechDataVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.mapper.QuoteTechPackageItemMapper;
import com.sanhua.marketingcost.mapper.QuoteTechProductMapper;
import com.sanhua.marketingcost.mapper.QuoteTechSalaryItemMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@DisplayName("技术资料草稿、提交和不可变真实MySQL")
class TechnicalDataVersionLifecycleIntegrationTest extends BomMapperTestBase {
  private static final TechnicalDataActor TECHNICIAN = new TechnicalDataActor(
      501L, "技术员T5", Set.of("technical:data:task:edit"));

  @Autowired private QuoteTechnicalDataPersistenceService persistence;
  @Autowired private QuoteTechnicalDataRepository repository;
  @Autowired private TechnicalDataProfileApplicationService profileService;
  @Autowired private QuoteTechProductMapper productMapper;
  @Autowired private QuoteTechModuleMapper moduleMapper;
  @Autowired private QuoteTechDataVersionMapper versionMapper;
  @Autowired private QuoteTechPackageItemMapper packageMapper;
  @Autowired private QuoteTechAuxItemMapper auxMapper;
  @Autowired private QuoteTechSalaryItemMapper salaryMapper;
  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void clean() {
    jdbc.execute((ConnectionCallback<Void>) connection -> {
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : List.of(
            "lp_quote_tech_submission", "lp_quote_tech_package_item",
            "lp_quote_tech_aux_item", "lp_quote_tech_salary_item",
            "lp_quote_tech_module", "lp_quote_tech_data_version",
            "lp_quote_tech_product", "lp_quote_tech_task")) {
          statement.execute("TRUNCATE TABLE " + table);
        }
        statement.execute("SET FOREIGN_KEY_CHECKS=1");
      }
      return null;
    });
  }

  @Test
  void submitFreezesContentAndPreservesSubmittedVersion() {
    Prepared prepared = prepared(93001L);
    QuoteTechDataVersion submitted = persistence.freezeDraftForSubmission(
        prepared.aggregate().product().getId(), 1, TECHNICIAN.userId());

    assertThat(submitted.getVersionStatus()).isEqualTo("SUBMITTED");
    assertThat(submitted.getContentFingerprint()).hasSize(64);
    assertThat(submitted.getReferenceSnapshotJson()).contains("schemaVersion", "PACKAGE");
    assertThat(submitted.getPackageTotalAmount()).isEqualByComparingTo("12.34000000");
    assertThat(submitted.getAuxiliaryTotalAmount()).isEqualByComparingTo("4.56000000");
    assertThat(submitted.getSalaryTotalAmount()).isEqualByComparingTo("7.89000000");
    QuoteTechProduct submittedProduct = productMapper.selectById(prepared.aggregate().product().getId());
    assertThat(submittedProduct.getCurrentEditVersionId()).isNull();
    assertThat(submittedProduct.getLatestSubmittedVersionId()).isEqualTo(submitted.getId());

    assertDatabaseRejectsSubmittedMutation(submitted, prepared);

  }

  @Test
  void readyStatusWithoutValidatedRealDetailsCannotBypassSubmission() {
    Aggregate aggregate = aggregate(94001L);
    seedLegacyProfile(aggregate.product().getId());
    Long draftId = productMapper.selectById(aggregate.product().getId()).getCurrentEditVersionId();
    for (QuoteTechModule module : moduleMapper.selectByProductIdForUpdate(aggregate.product().getId())) {
      if (!"PROFILE".equals(module.getModuleType())) {
        int rowVersion = module.getRowVersion();
        module.setEntryMode("MANUAL");
        module.setModuleStatus("READY");
        module.setCurrentVersionId(draftId);
        module.setLastValidationCode("VALID");
        assertThat(repository.updateModule(module, rowVersion, LocalDateTime.now())).isOne();
      }
    }
    assertThatThrownBy(() -> persistence.transitionVersion(
        draftId, "DRAFT", "SUBMITTED", 0, "b".repeat(64), TECHNICIAN.userId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("没有真实完整明细");
    QuoteTechProduct product = productMapper.selectById(aggregate.product().getId());
    assertThat(product.getCurrentEditVersionId()).isEqualTo(draftId);
    assertThat(product.getLatestSubmittedVersionId()).isNull();
    assertThat(versionMapper.selectById(draftId).getVersionStatus()).isEqualTo("DRAFT");
  }

  private void assertDatabaseRejectsSubmittedMutation(
      QuoteTechDataVersion submitted, Prepared prepared) {
    assertThatThrownBy(() -> jdbc.update(
        "UPDATE lp_quote_tech_data_version SET product_model='DB-BYPASS' WHERE id=?",
        submitted.getId())).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update(
        "DELETE FROM lp_quote_tech_data_version WHERE id=?", submitted.getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> packageMapper.updateById(
        withRemark(prepared.packageItem(), "DB-BYPASS")))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update(
        "DELETE FROM lp_quote_tech_package_item WHERE id=?", prepared.packageItem().getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update(
        "UPDATE lp_quote_tech_aux_item SET remark='DB-BYPASS' WHERE id=?",
        prepared.auxItem().getId())).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> auxMapper.deleteById(prepared.auxItem().getId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update(
        "UPDATE lp_quote_tech_salary_item SET remark='DB-BYPASS' WHERE id=?",
        prepared.salaryItem().getId())).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> salaryMapper.deleteById(prepared.salaryItem().getId()))
        .isInstanceOf(DataAccessException.class);
  }

  private QuoteTechPackageItem withRemark(QuoteTechPackageItem item, String remark) {
    item.setRemark(remark);
    return item;
  }

  private Prepared prepared(Long itemId) {
    Aggregate aggregate = aggregate(itemId);
    seedLegacyProfile(aggregate.product().getId());
    QuoteTechProduct product = productMapper.selectById(aggregate.product().getId());
    Long versionId = product.getCurrentEditVersionId();
    QuoteTechPackageItem packageItem = packageItem();
    QuoteTechAuxItem auxItem = auxItem();
    QuoteTechSalaryItem salaryItem = salaryItem();
    persistence.addPackageItems(versionId, List.of(packageItem));
    persistence.addAuxItems(versionId, List.of(auxItem));
    persistence.addSalaryItems(versionId, List.of(salaryItem));
    packageItem = repository.findPackageItems(versionId).getFirst();
    auxItem = repository.findAuxItems(versionId).getFirst();
    salaryItem = repository.findSalaryItems(versionId).getFirst();
    for (QuoteTechModule module : moduleMapper.selectByProductIdForUpdate(product.getId())) {
      if ("PROFILE".equals(module.getModuleType())) continue;
      int rowVersion = module.getRowVersion();
      module.setEntryMode("PACKAGE".equals(module.getModuleType()) ? "REFERENCE" : "MANUAL");
      module.setModuleStatus("READY");
      module.setCurrentVersionId(versionId);
      module.setLastValidationCode(module.getModuleType() + "_VALID");
      module.setLastValidationMessage("真实明细校验通过");
      if ("PACKAGE".equals(module.getModuleType())) {
        module.setReferenceSourceType("PACKAGE-TEMPLATE");
        module.setReferenceSourceId("TPL-001");
        module.setReferenceSourceVersion("V3");
        module.setReferenceFingerprint("f".repeat(64));
        module.setReferenceSnapshotJson("{\"template\":\"TPL-001\",\"version\":\"V3\"}");
      }
      assertThat(repository.updateModule(module, rowVersion, LocalDateTime.now())).isOne();
    }
    return new Prepared(aggregate, packageItem, auxItem, salaryItem);
  }

  private Aggregate aggregate(Long itemId) {
    String suffix = itemId + "-" + UUID.randomUUID().toString().substring(0, 8);
    QuoteTechTask task = new QuoteTechTask();
    task.setTaskNo("T5-" + suffix);
    task.setOaFormId(itemId + 100000);
    task.setOaFormItemId(itemId);
    task.setOaNo("OA-T5-" + suffix);
    task.setAccountingMonth("2026-08");
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("220");
    task.setAssigneeUserId(TECHNICIAN.userId());
    task.setAssigneeName(TECHNICIAN.name());
    task = persistence.createTask(task);

    QuoteTechProduct product = new QuoteTechProduct();
    product.setTaskId(task.getId());
    product.setOaFormItemId(itemId);
    product.setLevelNo(1);
    product.setMaterialNo("MAT-" + itemId);
    product.setProductName("T5测试产品");
    product.setSourceModel("SOURCE-MODEL");
    product.setSourceSpec("SOURCE-SPEC");
    product.setQuoteNo(task.getOaNo());
    product.setAccountingMonth("2026-08");
    product.setSourceSnapshotJson("{\"materialNo\":\"MAT-" + itemId + "\"}");
    product.setSourceFingerprint("a".repeat(64));
    product = persistence.createProduct(product);

    for (String type : List.of("PROFILE", "PACKAGE", "AUXILIARY", "SALARY")) {
      QuoteTechModule module = new QuoteTechModule();
      module.setProductId(product.getId());
      module.setModuleType(type);
      module.setRequiredFlag(1);
      module.setRequirementReasonCode("T5_REQUIRED");
      module.setRequirementReason("T5版本生命周期测试必填");
      persistence.createModule(module);
    }
    return new Aggregate(task, product);
  }

  // 四模块历史版本仍承担历史复制和数据库不可变验证，不能再通过新表单入口构造。
  private void seedLegacyProfile(Long productId) {
    var version = new QuoteTechDataVersion();
    version.setProductId(productId); version.setVersionNo(1); version.setProductModel("MODEL-V1");
    version.setProductProperty("标准品"); version.setNewProductFlag(0);
    version = persistence.createVersion(version);
    jdbc.update("UPDATE lp_quote_tech_product SET current_edit_version_id=?,row_version=1 WHERE id=?", version.getId(), productId);
    jdbc.update("UPDATE lp_quote_tech_module SET current_version_id=?,entry_mode='MANUAL',module_status='READY',last_validation_code='PROFILE_COMPLETE' WHERE product_id=? AND module_type='PROFILE'", version.getId(), productId);
  }

  private List<QuoteTechDataVersion> versions(Long productId) {
    return versionMapper.selectList(
        com.baomidou.mybatisplus.core.toolkit.Wrappers.<QuoteTechDataVersion>lambdaQuery()
            .eq(QuoteTechDataVersion::getProductId, productId)
            .orderByAsc(QuoteTechDataVersion::getVersionNo));
  }

  private QuoteTechPackageItem packageItem() {
    QuoteTechPackageItem item = new QuoteTechPackageItem();
    item.setLineNo(1);
    item.setSortSeq(1);
    item.setComponentMaterialNo("PKG-001");
    item.setComponentName("外包装箱");
    item.setComponentSpec("420x260x180");
    item.setQuantity(new BigDecimal("1.00000000"));
    item.setOriginalUnit("只");
    item.setStandardQuantity(new BigDecimal("1.00000000"));
    item.setStandardUnit("只");
    item.setConversionFactor(BigDecimal.ONE);
    item.setPriceBasisType("REFERENCE");
    item.setReferenceUnitPrice(new BigDecimal("12.34000000"));
    item.setAmount(new BigDecimal("12.34000000"));
    item.setSourceReferenceId("TPL-001");
    item.setSourceReferenceVersion("V3");
    item.setSourceSnapshotJson("{\"line\":1}");
    return item;
  }

  private QuoteTechAuxItem auxItem() {
    QuoteTechAuxItem item = new QuoteTechAuxItem();
    item.setLineNo(1);
    item.setSortSeq(1);
    item.setSubjectCode("AUX-001");
    item.setSubjectName("焊料");
    item.setAuxiliaryMaterialNo("AUX-MAT-001");
    item.setAuxiliaryName("银基焊环");
    item.setAuxiliarySpec("Φ14.9×0.8");
    item.setPricingMethod("QUANTITY");
    item.setQuantity(new BigDecimal("1.00000000"));
    item.setOriginalUnit("只");
    item.setStandardQuantity(new BigDecimal("1.00000000"));
    item.setStandardUnit("只");
    item.setConversionFactor(BigDecimal.ONE);
    item.setReferenceUnitPrice(new BigDecimal("4.56000000"));
    item.setPriceUnit("元/只");
    item.setLossRate(BigDecimal.ZERO.setScale(8));
    item.setAmount(new BigDecimal("4.56000000"));
    return item;
  }

  private QuoteTechSalaryItem salaryItem() {
    QuoteTechSalaryItem item = new QuoteTechSalaryItem();
    item.setLineNo(1);
    item.setSortSeq(1);
    item.setProcessCode("PROC-001");
    item.setProcessName("装配");
    item.setLaborType("DIRECT");
    item.setWorkingHours(new BigDecimal("0.50000000"));
    item.setOriginalTimeUnit("HOUR");
    item.setStandardHours(new BigDecimal("0.50000000"));
    item.setStandardTimeUnit("HOUR");
    item.setConversionFactor(BigDecimal.ONE);
    item.setHourlyRate(new BigDecimal("15.78000000"));
    item.setAmount(new BigDecimal("7.89000000"));
    return item;
  }

  private record Aggregate(QuoteTechTask task, QuoteTechProduct product) {}

  private record Prepared(
      Aggregate aggregate,
      QuoteTechPackageItem packageItem,
      QuoteTechAuxItem auxItem,
      QuoteTechSalaryItem salaryItem) {}
}
