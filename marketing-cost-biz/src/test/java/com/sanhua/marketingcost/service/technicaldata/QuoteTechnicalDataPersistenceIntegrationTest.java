package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.QuoteTechDataVersionMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.math.BigDecimal;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("技术资料持久化真实 MySQL")
class QuoteTechnicalDataPersistenceIntegrationTest extends BomMapperTestBase {
  @Autowired private QuoteTechnicalDataPersistenceService service;
  @Autowired private QuoteTechnicalDataRepository repository;
  @Autowired private QuoteTechDataVersionMapper versionMapper;
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
  @DisplayName("技术资料Mapper、仓储和服务完成CRUD、批量写入与8位小数往返")
  void persistsReadsUpdatesAndDeletesCompleteAggregate() {
    QuoteTechTask task = service.createTask(task("CRUD", 701L, 81001L));
    QuoteTechProduct product = service.createProduct(product(task.getId(), 81001L));
    for (String type : List.of("PROFILE", "PACKAGE", "AUXILIARY", "SALARY")) {
      service.createModule(module(product.getId(), type));
    }
    QuoteTechDataVersion version = service.createVersion(version(product.getId(), 1));

    List<QuoteTechPackageItem> packages = List.of(
        packageItem(1, "PKG-BOX-041", "12345678901.12345678", "9.87654321"),
        packageItem(2, "PKG-LINER-011", "2.00000000", "1.23456789"));
    service.addPackageItems(version.getId(), packages);
    service.addAuxItems(version.getId(), List.of(auxItem(1)));
    service.addSalaryItems(version.getId(), List.of(salaryItem(1)));

    assertThat(repository.findTask(task.getId())).isPresent();
    assertThat(repository.findActiveProduct(81001L, "2026-08"))
        .get().extracting(QuoteTechProduct::getId).isEqualTo(product.getId());
    assertThat(repository.findModule(
        jdbc.queryForObject(
            "SELECT id FROM lp_quote_tech_module WHERE product_id=? AND module_type='PACKAGE'",
            Long.class,
            product.getId())))
        .isPresent();
    assertThat(repository.findPackageItems(version.getId())).hasSize(2);
    assertThat(repository.findPackageItems(version.getId()).getFirst().getQuantity())
        .isEqualByComparingTo("12345678901.12345678");
    assertThat(repository.findAuxItems(version.getId()).getFirst().getReferenceUnitPrice())
        .isEqualByComparingTo("3200.12345678");
    assertThat(repository.findSalaryItems(version.getId()).getFirst().getWorkingHours())
        .isEqualByComparingTo("0.42000000");
    assertThat(repository.findSalaryItems(version.getId()).getFirst().getAmount())
        .isEqualByComparingTo("18.95185185");

    QuoteTechDataVersion draft = repository.findVersion(version.getId()).orElseThrow();
    draft.setProductModel("MODEL-UPDATED");
    draft.setPackageTotalAmount(new BigDecimal("42.12345678"));
    QuoteTechDataVersion updated = service.updateDraft(draft, 0);
    assertThat(updated.getProductModel()).isEqualTo("MODEL-UPDATED");
    assertThat(updated.getPackageTotalAmount()).isEqualByComparingTo("42.12345678");
    assertThat(updated.getRowVersion()).isOne();

    QuoteTechPackageItem first = repository.findPackageItems(version.getId()).getFirst();
    first.setRemark("已修改");
    assertThat(repository.deleteAllPackageItemsIfDraft(version.getId())).isEqualTo(2);
    service.addPackageItems(version.getId(), List.of(first));
    assertThat(repository.findPackageItems(version.getId()).getFirst().getRemark()).isEqualTo("已修改");
    assertThat(repository.findPackageItems(version.getId())).hasSize(1);

    assertThat(repository.transitionVersion(
        version.getId(), "DRAFT", "SUBMITTED", 1, "c".repeat(64), null,
        701L, java.time.LocalDateTime.now())).isOne();
    assertThat(repository.findVersion(version.getId()).orElseThrow().getVersionStatus())
        .isEqualTo("SUBMITTED");
  }

  @Test
  @DisplayName("并发创建同一OA产品行和月份只有一个成功")
  void enforcesActiveProductUniquenessUnderConcurrency() throws Exception {
    QuoteTechTask task = service.createTask(task("CONCURRENT", 702L, 82001L));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      List<java.util.concurrent.Future<Long>> futures = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        futures.add(executor.submit(() -> {
          ready.countDown();
          start.await();
          return service.createProduct(product(task.getId(), 82001L)).getId();
        }));
      }
      ready.await();
      start.countDown();

      int success = 0;
      int conflicts = 0;
      for (var future : futures) {
        try {
          assertThat(future.get()).isPositive();
          success++;
        } catch (ExecutionException exception) {
          assertThat(exception.getCause()).isInstanceOf(DataIntegrityViolationException.class);
          conflicts++;
        }
      }
      assertThat(success).isOne();
      assertThat(conflicts).isOne();
    }
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_tech_product "
            + "WHERE oa_form_item_id=82001 AND accounting_month='2026-08' AND active_flag=1",
        Integer.class)).isOne();
  }

  @Test
  @DisplayName("已提交版本在服务层和条件SQL层都不能修改或删除明细")
  void protectsSubmittedVersionAtServiceAndSqlLayers() {
    QuoteTechTask task = service.createTask(task("IMMUTABLE", 703L, 83001L));
    QuoteTechProduct product = service.createProduct(product(task.getId(), 83001L));
    QuoteTechDataVersion version = service.createVersion(version(product.getId(), 1));
    service.addPackageItems(version.getId(), List.of(packageItem(1, "PKG-1", "1", "2")));
    service.addAuxItems(version.getId(), List.of(auxItem(1)));
    service.addSalaryItems(version.getId(), List.of(salaryItem(1)));

    QuoteTechPackageItem packageItem = repository.findPackageItems(version.getId()).getFirst();
    QuoteTechAuxItem auxItem = repository.findAuxItems(version.getId()).getFirst();
    QuoteTechSalaryItem salaryItem = repository.findSalaryItems(version.getId()).getFirst();
    assertThat(repository.transitionVersion(
        version.getId(), "DRAFT", "SUBMITTED", 0, "d".repeat(64), null,
        703L, java.time.LocalDateTime.now())).isOne();
    QuoteTechDataVersion submitted = repository.findVersion(version.getId()).orElseThrow();

    submitted.setProductModel("ILLEGAL-SUBMITTED-EDIT");
    assertThatThrownBy(() -> service.updateDraft(submitted, submitted.getRowVersion()))
        .isInstanceOf(QuoteTechnicalDataImmutableVersionException.class);
    assertThat(repository.updateDraftVersion(
        submitted, submitted.getRowVersion(), java.time.LocalDateTime.now())).isZero();

    assertThatThrownBy(() -> service.addPackageItems(
        version.getId(), List.of(packageItem(2, "PKG-2", "1", "2"))))
        .isInstanceOf(QuoteTechnicalDataImmutableVersionException.class);
    assertThatThrownBy(() -> service.addAuxItems(version.getId(), List.of(auxItem)))
        .isInstanceOf(QuoteTechnicalDataImmutableVersionException.class);
    assertThatThrownBy(() -> service.addSalaryItems(version.getId(), List.of(salaryItem)))
        .isInstanceOf(QuoteTechnicalDataImmutableVersionException.class);

    assertThat(repository.insertPackageItemsIfDraft(
        version.getId(), List.of(packageItem(2, "PKG-SQL", "1", "2")))).isZero();
    assertThat(repository.insertAuxItemsIfDraft(version.getId(), List.of(auxItem))).isZero();
    assertThat(repository.insertSalaryItemsIfDraft(version.getId(), List.of(salaryItem))).isZero();
    assertThat(repository.deleteAllPackageItemsIfDraft(version.getId())).isZero();
    assertThat(repository.deleteAllAuxItemsIfDraft(version.getId())).isZero();
    assertThat(repository.deleteAllSalaryItemsIfDraft(version.getId())).isZero();

    assertThat(repository.findPackageItems(version.getId())).hasSize(1);
    assertThat(repository.findAuxItems(version.getId())).hasSize(1);
    assertThat(repository.findSalaryItems(version.getId())).hasSize(1);

    QuoteTechDataVersion approved = service.transitionVersion(
        version.getId(), "SUBMITTED", "APPROVED", submitted.getRowVersion(),
        submitted.getContentFingerprint(), 704L);
    approved.setProductModel("ILLEGAL-APPROVED-EDIT");
    assertThatThrownBy(() -> service.updateDraft(approved, approved.getRowVersion()))
        .isInstanceOf(QuoteTechnicalDataImmutableVersionException.class);
    assertThatThrownBy(() -> service.transitionVersion(
        approved.getId(), "APPROVED", "DRAFT", approved.getRowVersion(),
        approved.getContentFingerprint(), 704L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(repository.updateDraftVersion(
        approved, approved.getRowVersion(), java.time.LocalDateTime.now())).isZero();
    assertThat(repository.transitionVersion(
        approved.getId(), "APPROVED", "DRAFT", approved.getRowVersion(),
        approved.getContentFingerprint(), null, 704L, java.time.LocalDateTime.now())).isZero();
    assertThat(repository.deleteAllPackageItemsIfDraft(approved.getId())).isZero();
  }

  @Test
  @DisplayName("批量明细违反唯一键时版本和明细整体回滚")
  void rollsBackVersionAndBatchDetailsTogether() {
    QuoteTechTask task = service.createTask(task("ROLLBACK", 704L, 84001L));
    QuoteTechProduct product = service.createProduct(product(task.getId(), 84001L));
    QuoteTechDataVersion candidate = version(product.getId(), 9);
    QuoteTechPackageItem first = packageItem(1, "PKG-R1", "1", "2");
    QuoteTechPackageItem duplicate = packageItem(1, "PKG-R2", "1", "2");

    assertThatThrownBy(() -> service.createDraftWithPackageItems(
        candidate, List.of(first, duplicate)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(versionMapper.selectList(
        com.baomidou.mybatisplus.core.toolkit.Wrappers.<QuoteTechDataVersion>lambdaQuery()
            .eq(QuoteTechDataVersion::getProductId, product.getId())
            .eq(QuoteTechDataVersion::getVersionNo, 9)))
        .isEmpty();
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_tech_package_item", Integer.class)).isZero();
  }

  @Test
  @DisplayName("草稿乐观锁只允许同一rowVersion成功一次")
  void rejectsStaleDraftOptimisticLock() {
    QuoteTechTask task = service.createTask(task("LOCK", 705L, 85001L));
    QuoteTechProduct product = service.createProduct(product(task.getId(), 85001L));
    QuoteTechDataVersion version = service.createVersion(version(product.getId(), 1));
    QuoteTechDataVersion firstRead = repository.findVersion(version.getId()).orElseThrow();
    QuoteTechDataVersion staleRead = repository.findVersion(version.getId()).orElseThrow();
    firstRead.setProductModel("FIRST");
    staleRead.setProductModel("STALE");

    assertThat(service.updateDraft(firstRead, 0).getRowVersion()).isOne();
    assertThatThrownBy(() -> service.updateDraft(staleRead, 0))
        .isInstanceOf(QuoteTechnicalDataOptimisticLockException.class);
    assertThat(repository.findVersion(version.getId()).orElseThrow().getProductModel())
        .isEqualTo("FIRST");
  }

  private QuoteTechTask task(String marker, Long assigneeId, Long itemId) {
    String suffix = marker + "-" + UUID.randomUUID().toString().substring(0, 8);
    QuoteTechTask task = new QuoteTechTask();
    task.setTaskNo("TECH-" + suffix);
    task.setOaFormId(positive(suffix));
    task.setOaFormItemId(itemId);
    task.setOaNo("OA-" + suffix);
    task.setAccountingMonth("2026-08");
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("220");
    task.setAssigneeUserId(assigneeId);
    task.setAssigneeName("技术员" + assigneeId);
    return task;
  }

  private QuoteTechProduct product(Long taskId, Long itemId) {
    QuoteTechProduct product = new QuoteTechProduct();
    product.setTaskId(taskId);
    product.setOaFormItemId(itemId);
    product.setMaterialNo("M-" + itemId);
    product.setProductName("测试产品");
    product.setSourceModel("MODEL-SOURCE");
    product.setSourceSpec("SPEC-SOURCE");
    product.setQuoteNo("OA-TEST");
    product.setAccountingMonth("2026-08");
    product.setSourceSnapshotJson("{\"materialNo\":\"M-" + itemId + "\"}");
    product.setSourceFingerprint("a".repeat(64));
    return product;
  }

  private QuoteTechModule module(Long productId, String type) {
    QuoteTechModule module = new QuoteTechModule();
    module.setProductId(productId);
    module.setModuleType(type);
    module.setRequiredFlag(1);
    module.setRequirementReasonCode("BUSINESS_REQUIRED");
    module.setRequirementReason("报价核算需要");
    return module;
  }

  private QuoteTechDataVersion version(Long productId, int versionNo) {
    QuoteTechDataVersion version = new QuoteTechDataVersion();
    version.setProductId(productId);
    version.setVersionNo(versionNo);
    version.setProductModel("MODEL-DRAFT");
    version.setProductProperty("标准品");
    version.setNewProductFlag(0);
    return version;
  }

  private QuoteTechPackageItem packageItem(
      int lineNo, String materialNo, String quantity, String unitPrice) {
    QuoteTechPackageItem item = new QuoteTechPackageItem();
    item.setLineNo(lineNo);
    item.setSortSeq(lineNo);
    item.setComponentMaterialNo(materialNo);
    item.setComponentName("包装组件" + lineNo);
    item.setComponentSpec("SPEC-" + lineNo);
    item.setQuantity(new BigDecimal(quantity));
    item.setOriginalUnit("只");
    item.setStandardQuantity(new BigDecimal(quantity));
    item.setStandardUnit("只");
    item.setConversionFactor(new BigDecimal("1.00000000"));
    item.setPriceBasisType("HISTORY");
    item.setReferenceUnitPrice(new BigDecimal(unitPrice));
    item.setAmount(new BigDecimal("19.75308642"));
    return item;
  }

  private QuoteTechAuxItem auxItem(int lineNo) {
    QuoteTechAuxItem item = new QuoteTechAuxItem();
    item.setLineNo(lineNo);
    item.setSortSeq(lineNo);
    item.setSubjectCode("AUX-001");
    item.setSubjectName("焊接材料");
    item.setAuxiliaryMaterialNo("AUX-MAT-001");
    item.setAuxiliaryName("银基焊料");
    item.setAuxiliarySpec("Φ14.9×0.8");
    item.setPricingMethod("QTY");
    item.setQuantity(new BigDecimal("0.00022000"));
    item.setOriginalUnit("KG");
    item.setStandardQuantity(new BigDecimal("0.00022000"));
    item.setStandardUnit("KG");
    item.setConversionFactor(new BigDecimal("1.00000000"));
    item.setReferenceUnitPrice(new BigDecimal("3200.12345678"));
    item.setPriceUnit("元/KG");
    item.setLossRate(BigDecimal.ZERO.setScale(8));
    item.setAmount(new BigDecimal("0.70402716"));
    return item;
  }

  private QuoteTechSalaryItem salaryItem(int lineNo) {
    QuoteTechSalaryItem item = new QuoteTechSalaryItem();
    item.setLineNo(lineNo);
    item.setSortSeq(lineNo);
    item.setProcessCode("OP-001");
    item.setProcessName("阀体装配");
    item.setLaborType("DIRECT");
    item.setWorkingHours(new BigDecimal("0.42000000"));
    item.setOriginalTimeUnit("HOUR");
    item.setStandardHours(new BigDecimal("0.42000000"));
    item.setStandardTimeUnit("HOUR");
    item.setConversionFactor(new BigDecimal("1.00000000"));
    item.setHourlyRate(new BigDecimal("45.12345678"));
    item.setAmount(new BigDecimal("18.95185185"));
    return item;
  }

  private long positive(String text) {
    return Integer.toUnsignedLong(text.hashCode()) + 1L;
  }

}
