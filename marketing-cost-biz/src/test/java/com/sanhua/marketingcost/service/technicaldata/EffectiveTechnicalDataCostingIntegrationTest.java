package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
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
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataQueryService;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@DisplayName("T11有效技术版本成本取数真实MySQL")
class EffectiveTechnicalDataCostingIntegrationTest extends BomMapperTestBase {
  private static final Long ITEM_ID = 1053100052030L;
  private static final String MONTH = "2026-08";

  @Autowired private EffectiveTechnicalDataQueryService queryService;
  @Autowired private TechnicalDataVersionContentCodec contentCodec;
  @Autowired private QuoteTechTaskMapper taskMapper;
  @Autowired private QuoteTechProductMapper productMapper;
  @Autowired private QuoteTechModuleMapper moduleMapper;
  @Autowired private QuoteTechDataVersionMapper versionMapper;
  @Autowired private QuoteTechPackageItemMapper packageMapper;
  @Autowired private QuoteTechAuxItemMapper auxMapper;
  @Autowired private QuoteTechSalaryItemMapper salaryMapper;
  @Autowired private JdbcTemplate jdbc;

  private Fixture fixture;

  @BeforeEach
  void setUp() {
    fixture = createFixture();
  }

  @AfterEach
  void clean() {
    jdbc.execute((ConnectionCallback<Void>) connection -> {
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : List.of(
            "lp_quote_cost_run_version", "lp_business_change_log",
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
  void exactMonthReadsOnlyApprovedV2AndIgnoresReturnedV1AndSubmittedV3() {
    EffectiveTechnicalDataInput input = queryService.resolve(ITEM_ID, MONTH);

    assertThat(input.versionId()).isEqualTo(fixture.v2Id());
    assertThat(input.versionNo()).isEqualTo(2);
    assertThat(input.salaryTotalAmount()).isEqualByComparingTo("18.90000000");
    assertThat(input.auxiliaryTotalAmount()).isEqualByComparingTo("0.70400000");
    assertThat(input.packageTotalAmount()).isEqualByComparingTo("3.50000000");
    assertThat(input.versionId()).isNotIn(fixture.v1Id(), fixture.v3Id());
    assertThat(queryService.resolve(ITEM_ID, "2026-09")).isNull();
  }

  @Test
  void concurrentCostReadersAllResolveTheSameImmutableV2() throws Exception {
    List<Long> selected = new ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(6)) {
      List<java.util.concurrent.Future<Long>> futures = new ArrayList<>();
      for (int index = 0; index < 12; index++) {
        futures.add(executor.submit(() -> queryService.resolve(ITEM_ID, MONTH).versionId()));
      }
      for (var future : futures) selected.add(future.get());
    }

    assertThat(selected).hasSize(12).containsOnly(fixture.v2Id());
  }

  @Test
  void costVersionTraceRequiresCompleteInputAndRestrictsDeletingReferencedVersion() {
    jdbc.update("""
        INSERT INTO lp_quote_cost_run_version(
          cost_run_no,oa_no,oa_form_item_id,product_code,pricing_month,result_period,status,
          tech_data_version_id,tech_data_version_no,tech_data_source,
          tech_data_input_json,tech_data_retrieved_at)
        VALUES (?,?,?,?,?,?,'TRIAL',?,?,?,CAST(? AS JSON),?)
        """,
        "T11-RUN-" + UUID.randomUUID(), "OA-T11", ITEM_ID, "205686641", MONTH, MONTH,
        fixture.v2Id(), 2, EffectiveTechnicalDataInput.SOURCE_EFFECTIVE_VERSION,
        "{\"versionId\":" + fixture.v2Id() + ",\"salaryTotalAmount\":18.90}",
        LocalDateTime.of(2026, 8, 31, 8, 0));

    assertThat(jdbc.queryForMap(
        "SELECT tech_data_version_id,tech_data_version_no,tech_data_source "
            + "FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",
        ITEM_ID))
        .containsEntry("tech_data_version_id", fixture.v2Id())
        .containsEntry("tech_data_version_no", 2)
        .containsEntry("tech_data_source", EffectiveTechnicalDataInput.SOURCE_EFFECTIVE_VERSION);

    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO lp_quote_cost_run_version(
          cost_run_no,oa_no,oa_form_item_id,product_code,pricing_month,result_period,status,
          tech_data_version_id)
        VALUES (?,?,?,?,?,?,'TRIAL',?)
        """,
        "T11-INCOMPLETE-" + UUID.randomUUID(), "OA-T11", ITEM_ID + 1,
        "205686641", MONTH, MONTH, fixture.v2Id()))
        .isInstanceOf(DataAccessException.class);

    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS "
            + "WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='fk_quote_cost_run_tech_version' "
            + "AND DELETE_RULE='RESTRICT'",
        Integer.class)).isOne();
    assertThatThrownBy(() -> jdbc.update(
        "DELETE FROM lp_quote_tech_data_version WHERE id=?", fixture.v2Id()))
        .isInstanceOf(DataAccessException.class);
  }

  private Fixture createFixture() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    QuoteTechTask task = new QuoteTechTask();
    task.setTaskNo("T11-" + suffix);
    task.setOaFormId(700001L);
    task.setOaFormItemId(ITEM_ID);
    task.setOaNo("OA-T11-" + suffix);
    task.setAccountingMonth(MONTH);
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("220");
    task.setAssigneeUserId(501L);
    task.setAssigneeName("技术员T11");
    task.setTaskStatus("APPROVED");
    task.setTaskVersion(3);
    task.setReviewRound(2);
    task.setReviewStatus("PASSED");
    task.setActiveFlag(1);
    task.setActiveLockKey("ITEM:" + ITEM_ID + ":MONTH:" + MONTH);
    taskMapper.insert(task);

    QuoteTechProduct product = new QuoteTechProduct();
    product.setTaskId(task.getId());
    product.setOaFormItemId(ITEM_ID);
    product.setLevelNo(1);
    product.setMaterialNo("205686641");
    product.setProductName("A板片组件");
    product.setQuoteNo(task.getOaNo());
    product.setAccountingMonth(MONTH);
    product.setSourceSnapshotJson("{\"materialNo\":\"205686641\"}");
    product.setSourceFingerprint("a".repeat(64));
    product.setProductStatus("APPROVED");
    product.setActiveFlag(1);
    product.setActiveLockKey("ITEM:" + ITEM_ID + ":MONTH:" + MONTH);
    product.setRowVersion(0);
    productMapper.insert(product);

    List<QuoteTechModule> modules = new ArrayList<>();
    for (String type : List.of("PROFILE", "PACKAGE", "AUXILIARY", "SALARY")) {
      QuoteTechModule module = new QuoteTechModule();
      module.setProductId(product.getId());
      module.setModuleType(type);
      module.setRequiredFlag(1);
      module.setRequirementReasonCode("T11_REQUIRED");
      module.setRequirementReason("T11核算取数固定样例");
      module.setEntryMode("MANUAL");
      module.setModuleStatus("APPROVED");
      module.setRowVersion(0);
      moduleMapper.insert(module);
      modules.add(module);
    }

    QuoteTechDataVersion v1 = version(product.getId(), 1, "RETURNED");
    versionMapper.insert(v1);
    QuoteTechDataVersion v2 = version(product.getId(), 2, "DRAFT");
    v2.setCreatedFromVersionId(v1.getId());
    v2.setProductModel("HDF25H-014051");
    v2.setProductProperty("非标品");
    v2.setPackageTotalAmount(new BigDecimal("3.50000000"));
    v2.setAuxiliaryTotalAmount(new BigDecimal("0.70400000"));
    v2.setSalaryTotalAmount(new BigDecimal("18.90000000"));
    versionMapper.insert(v2);

    QuoteTechPackageItem packageItem = packageItem(v2.getId());
    QuoteTechAuxItem auxItem = auxItem(v2.getId());
    QuoteTechSalaryItem salaryItem = salaryItem(v2.getId());
    packageMapper.insert(packageItem);
    auxMapper.insert(auxItem);
    salaryMapper.insert(salaryItem);

    List<QuoteTechModule> persistedModules = moduleMapper.selectByProductId(product.getId());
    QuoteTechDataVersion persistedV2 = versionMapper.selectById(v2.getId());
    String referenceSnapshot = contentCodec.referenceSnapshotJson(
        contentCodec.moduleSnapshots(persistedModules));
    persistedV2.setReferenceSnapshotJson(referenceSnapshot);
    persistedV2.setContentFingerprint(contentCodec.fingerprint(
        persistedV2,
        contentCodec.moduleSnapshots(persistedModules),
        packageMapper.selectByVersionId(v2.getId()),
        auxMapper.selectByVersionId(v2.getId()),
        salaryMapper.selectByVersionId(v2.getId())));
    versionMapper.updateById(persistedV2);
    int submitted = versionMapper.transitionStatus(
        v2.getId(), "DRAFT", "SUBMITTED", 0, persistedV2.getContentFingerprint(),
        referenceSnapshot, 501L, LocalDateTime.of(2026, 8, 30, 20, 0));
    assertThat(submitted).isOne();
    int approved = versionMapper.transitionStatus(
        v2.getId(), "SUBMITTED", "APPROVED", 1, persistedV2.getContentFingerprint(),
        referenceSnapshot, 601L, LocalDateTime.of(2026, 8, 31, 8, 0));
    assertThat(approved).isOne();

    QuoteTechDataVersion v3 = version(product.getId(), 3, "SUBMITTED");
    v3.setCreatedFromVersionId(v2.getId());
    versionMapper.insert(v3);
    jdbc.update("""
        UPDATE lp_quote_tech_product
           SET current_edit_version_id=?,latest_submitted_version_id=?,effective_version_id=?,
               effective_review_round=2,effective_at=?,row_version=row_version+1
         WHERE id=?
        """, v3.getId(), v3.getId(), v2.getId(), LocalDateTime.of(2026, 8, 31, 8, 0),
        product.getId());
    return new Fixture(task.getId(), product.getId(), v1.getId(), v2.getId(), v3.getId());
  }

  private QuoteTechDataVersion version(Long productId, int versionNo, String status) {
    QuoteTechDataVersion value = new QuoteTechDataVersion();
    value.setProductId(productId);
    value.setVersionNo(versionNo);
    value.setVersionStatus(status);
    value.setNewProductFlag(0);
    value.setPackageTotalAmount(BigDecimal.ZERO);
    value.setAuxiliaryTotalAmount(BigDecimal.ZERO);
    value.setSalaryTotalAmount(BigDecimal.ZERO);
    value.setRowVersion(0);
    value.setCreatedBy(501L);
    return value;
  }

  private QuoteTechPackageItem packageItem(Long versionId) {
    QuoteTechPackageItem value = new QuoteTechPackageItem();
    value.setVersionId(versionId);
    value.setLineNo(1);
    value.setSortSeq(10);
    value.setComponentMaterialNo("PKG-BOX-041");
    value.setComponentName("外包装箱");
    value.setQuantity(BigDecimal.ONE);
    value.setOriginalUnit("只");
    value.setStandardQuantity(BigDecimal.ONE);
    value.setStandardUnit("只");
    value.setConversionFactor(BigDecimal.ONE);
    value.setPriceBasisType("HISTORY");
    value.setReferenceUnitPrice(new BigDecimal("3.50000000"));
    value.setAmount(new BigDecimal("3.50000000"));
    return value;
  }

  private QuoteTechAuxItem auxItem(Long versionId) {
    QuoteTechAuxItem value = new QuoteTechAuxItem();
    value.setVersionId(versionId);
    value.setLineNo(1);
    value.setSortSeq(10);
    value.setSubjectCode("0201");
    value.setSubjectName("辅助焊料类");
    value.setAuxiliaryMaterialNo("301010307");
    value.setAuxiliaryName("银基焊环");
    value.setPricingMethod("QTY");
    value.setQuantity(new BigDecimal("0.00022000"));
    value.setOriginalUnit("KG");
    value.setStandardQuantity(new BigDecimal("0.00022000"));
    value.setStandardUnit("KG");
    value.setConversionFactor(BigDecimal.ONE);
    value.setReferenceUnitPrice(new BigDecimal("3200.00000000"));
    value.setPriceUnit("元/KG");
    value.setLossRate(BigDecimal.ZERO);
    value.setAmount(new BigDecimal("0.70400000"));
    return value;
  }

  private QuoteTechSalaryItem salaryItem(Long versionId) {
    QuoteTechSalaryItem value = new QuoteTechSalaryItem();
    value.setVersionId(versionId);
    value.setLineNo(1);
    value.setSortSeq(10);
    value.setProcessCode("OP-1");
    value.setProcessName("装配");
    value.setLaborType("DIRECT");
    value.setWorkingHours(new BigDecimal("0.42000000"));
    value.setOriginalTimeUnit("HOUR");
    value.setStandardHours(new BigDecimal("0.42000000"));
    value.setStandardTimeUnit("HOUR");
    value.setConversionFactor(BigDecimal.ONE);
    value.setWageRate(new BigDecimal("45.00000000"));
    value.setRateUnit("元/小时");
    value.setHourlyRate(new BigDecimal("45.00000000"));
    value.setPersonCoefficient(BigDecimal.ONE);
    value.setAmount(new BigDecimal("18.90000000"));
    return value;
  }

  private record Fixture(Long taskId, Long productId, Long v1Id, Long v2Id, Long v3Id) {}
}
