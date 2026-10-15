package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 实际MySQL验证批量结果的ID、全部字段、空值和事务回滚。 */
@Tag("integration")
@TestPropertySource(
    properties = {
      "srm.fixed-price.enabled=false", "oa.person-directory.enabled=false",
      "oa.person-directory.run-at-startup=false", "monthly-reprice.enabled=false",
      "cms.sync.publish.enabled=false"
    })
class LinkedPriceResultBatchIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired PriceLinkedCalcItemMapper mapper;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcTemplate jdbc;
  @Autowired com.sanhua.marketingcost.mapper.MaterialPriceMonthMapper months;
  @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
  @Autowired com.sanhua.marketingcost.mapper.PriceLinkedItemMapper rules;

  @Autowired com.sanhua.marketingcost.mapper.PriceVariableBindingMapper bindings;

  @Test
  void retirementRemovesOnlyOldAdjustmentStorage() {
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('lp_factor_adjust_batch','lp_factor_adjust_price')", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name IN ('adjust_batch_id','latest_adjust_batch_id')", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('lp_factor_monthly_price','lp_factor_monthly_price_change_log','lp_factor_upload_batch')", Integer.class)).isEqualTo(3);
  }

  @Test
  void monthlyResultsWithoutOaKeepTimeOrganizationAndTechnicalInputUniqueness() {
    new TransactionTemplate(transactions).executeWithoutResult(status -> {
      var original = row("MONTHLY-RETIRED-ADJUST", "MONTHLY_FACTOR");
      original.setCalcScene("MONTHLY_ADJUST");
      original.setOaNo(null);
      mapper.insert(original);
      original.setId(null);
      assertThatThrownBy(() -> mapper.insert(original))
          .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
      original.setPriceAsOfTime(original.getPriceAsOfTime().plusDays(1));
      mapper.insert(original);
      original.setId(null);
      original.setPriceOrgCode("220");
      mapper.insert(original);
      original.setId(null);
      original.setSourceKind("TECH_SUPPLEMENTAL");
      original.setCalcFingerprint("input-v1");
      mapper.insert(original);
      original.setId(null);
      assertThatThrownBy(() -> mapper.insert(original))
          .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
      original.setCalcFingerprint("input-v2");
      mapper.insert(original);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_linked_calc_item WHERE item_code='MONTHLY-RETIRED-ADJUST'", Integer.class)).isEqualTo(5);
      status.setRollbackOnly();
    });
  }

  @Test
  void bindingComparisonReadsActualRuleWithinSupplierOrganizationUnitAndMonth() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              var scope = new com.sanhua.marketingcost.dto.StandardBindingCheckRequest();
              scope.setBusinessUnitType("COMMERCIAL");
              scope.setOrganizationCode("210");
              scope.setMaterialCode("BINDING-SCOPE");
              scope.setSupplierCode("S1");
              scope.setUnit("KG");
              scope.setSpecModel("SPEC");
              scope.setPricingMonth("2037-10");
              scope.setLinkedItemId(Long.MAX_VALUE);
              assertThat(bindings.findPreviousFactorIdentity(scope, "材料含税价格")).isNull();
              long selected = 0;
              int n = 0;
              for (var values :
                  List.of(
                      List.of("COMMERCIAL", "210", "S1", "KG", "SPEC", "2037-09", "AVAILABLE"),
                      List.of("COMMERCIAL", "220", "S1", "KG", "SPEC", "2037-10", "AVAILABLE"),
                      List.of("HOUSEHOLD", "210", "S1", "KG", "SPEC", "2037-10", "AVAILABLE"),
                      List.of("COMMERCIAL", "210", "S2", "KG", "SPEC", "2037-10", "AVAILABLE"),
                      List.of("COMMERCIAL", "210", "S1", "EA", "SPEC", "2037-10", "AVAILABLE"),
                      List.of("COMMERCIAL", "210", "S1", "KG", "OTHER", "2037-10", "AVAILABLE"),
                      List.of("COMMERCIAL", "210", "S1", "KG", "SPEC", "2037-11", "AVAILABLE"),
                      List.of("COMMERCIAL", "210", "S1", "KG", "SPEC", "2037-10", "FAILED"))) {
                jdbc.update(
                    "INSERT INTO"
                        + " lp_price_linked_item(business_unit_type,org_code,supplier_code,unit,spec_model,pricing_month,technical_publication_status,material_code,source_kind,formula_expr,effective_from,created_at,updated_at)"
                        + " VALUES(?,?,?,?,?,?,?,'BINDING-SCOPE','TECH_SUPPLEMENTAL','[Cu]','2037-09-01',NOW(),NOW())",
                    values.toArray());
                long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                jdbc.update(
                    "INSERT INTO"
                        + " lp_price_variable_binding(linked_item_id,token_name,factor_identity_id,effective_date,source)"
                        + " VALUES(?,'材料含税价格',?,'2037-09-01','MANUAL')",
                    id,
                    1000 + n++);
                if (selected == 0) selected = id;
              }
              sqlSession.clearCache();
              assertThat(bindings.findPreviousFactorIdentity(scope, "材料含税价格")).isEqualTo(1000L);
              scope.setLinkedItemId(selected); // 重导同一行也要核对其已保存绑定。
              assertThat(bindings.findPreviousFactorIdentity(scope, "材料含税价格")).isEqualTo(1000L);
              assertThat(bindings.findPreviousFactorIdentity(scope, "废料含税价格")).isNull();
              status.setRollbackOnly();
            });
  }

  @Test
  void technicalInputRevisionsAppendWithoutChangingHistoricalOrPublicResults() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              for (String scene : List.of("QUOTE", "MONTHLY_ADJUST")) {
                var old = row("TECH-REVISION-" + scene, "OA_LOCKED");
                old.setSourceKind("TECH_SUPPLEMENTAL");
                old.setCalcScene(scene);
                old.setCalcFingerprint(null); // 已冻结的旧结果没有完整输入指纹。
                mapper.insert(old);
                var before = mapper.selectById(old.getId());
                for (String revision : List.of("financial-90", "financial-100")) {
                  var revised = row(old.getItemCode(), "OA_LOCKED");
                  revised.setSourceKind("TECH_SUPPLEMENTAL");
                  revised.setCalcScene(scene);
                  revised.setCalcFingerprint(revision);
                  mapper.insert(revised);
                  var duplicate = row(old.getItemCode(), "OA_LOCKED");
                  duplicate.setSourceKind("TECH_SUPPLEMENTAL");
                  duplicate.setCalcScene(scene);
                  duplicate.setCalcFingerprint(revision);
                  assertThatThrownBy(() -> mapper.insert(duplicate))
                      .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
                }
                assertThat(mapper.selectById(old.getId()))
                    .usingRecursiveComparison()
                    .isEqualTo(before);
                var publicRow = row("PUBLIC-REVISION-" + scene, "OA_LOCKED");
                publicRow.setSourceKind("PUBLIC");
                publicRow.setCalcScene(scene);
                mapper.insert(publicRow);
                publicRow.setId(null);
                publicRow.setCalcFingerprint("new-financial-input");
                assertThatThrownBy(() -> mapper.insert(publicRow))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
              }
              status.setRollbackOnly();
            });
  }

  @Test
  void factorRetirementMigrationCanBeRepeatedWithoutChangingRows() throws Exception {
    var saved = row("TECH-MIGRATION-REPEAT", "OA_LOCKED");
    saved.setSourceKind("TECH_SUPPLEMENTAL");
    mapper.insert(saved);
    try {
      var before = mapper.selectById(saved.getId());
      var result =
          MYSQL.execInContainer(
              "sh",
              "-c",
              "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot marketing_cost <"
                  + " /tmp/V321__retire_factor_adjustment.sql");
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();
      assertThat(mapper.selectById(saved.getId())).usingRecursiveComparison().isEqualTo(before);
    } finally {
      mapper.deleteById(saved.getId());
    }
  }

  @Test
  void indexedCandidateReadRetainsBusinessUnitIsolationAndCompleteSnapshot() {
    var security = org.springframework.security.core.context.SecurityContextHolder.getContext();
    var previous = security.getAuthentication();
    var auth =
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "prc10", "unused", List.of());
    auth.setDetails(java.util.Map.of("businessUnitType", "COMMERCIAL"));
    security.setAuthentication(auth);
    try {
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              status -> {
                for (String business : List.of("COMMERCIAL", "HOUSEHOLD")) {
                  jdbc.update(
                      "INSERT INTO lp_price_linked_item(business_unit_type,org_code,material_code,"
                          + "pricing_month,rule_status,source_kind,source_input_snapshot_json,formula_expr,"
                          + "effective_from,created_at,updated_at)"
                          + " VALUES(?,'220','BATCH-SCOPE','2037-10','PUBLISHED','PUBLIC',?,?,'2037-10-01',NOW(),NOW())",
                      business,
                      "{\"business\": \"" + business + "\"}",
                      "ROUND(1.23456,4)");
                }
                var rows =
                    rules.selectPricingCandidates(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(
                                com.sanhua.marketingcost.entity.PriceLinkedItem.class)
                            .eq(
                                com.sanhua.marketingcost.entity.PriceLinkedItem::getMaterialCode,
                                "BATCH-SCOPE")
                            .eq(com.sanhua.marketingcost.entity.PriceLinkedItem::getDeleted, 0));
                assertThat(rows)
                    .singleElement()
                    .satisfies(
                        row -> {
                          assertThat(row.getBusinessUnitType()).isEqualTo("COMMERCIAL");
                          assertThat(row.getSourceInputSnapshotJson())
                              .isEqualTo("{\"business\": \"COMMERCIAL\"}");
                          assertThat(row.getFormulaExpr()).isEqualTo("ROUND(1.23456,4)");
                          assertThat(row.getOrgCode()).isEqualTo("220");
                          assertThat(row.getPricingMonth()).isEqualTo("2037-10");
                        });
                status.setRollbackOnly();
              });
    } finally {
      security.setAuthentication(previous);
    }
  }

  @Test
  void concurrentNewBatchesCannotOverwriteEachOther() throws Exception {
    var ready = new java.util.concurrent.CountDownLatch(2);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var jobs = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int side = 1; side <= 2; side++) {
        int price = side;
        jobs.add(
            pool.submit(
                () -> {
                  var rows =
                      List.of(row("BATCH-RACE-A", "OA_LOCKED"), row("BATCH-RACE-B", "OA_LOCKED"));
                  rows.forEach(row -> row.setPartUnitPrice(BigDecimal.valueOf(price)));
                  ready.countDown();
                  assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                  try {
                    new TransactionTemplate(transactions)
                        .executeWithoutResult(status -> mapper.insertResults(rows));
                    return true;
                  } catch (org.springframework.dao.DuplicateKeyException expected) {
                    return false;
                  }
                }));
      }
      assertThat(
              List.of(
                  jobs.get(0).get(30, java.util.concurrent.TimeUnit.SECONDS),
                  jobs.get(1).get(30, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      var prices =
          jdbc.queryForList(
              "SELECT part_unit_price FROM lp_price_linked_calc_item"
                  + " WHERE item_code LIKE 'BATCH-RACE-%' ORDER BY item_code",
              BigDecimal.class);
      assertThat(prices).hasSize(2);
      assertThat(prices.get(0)).isEqualByComparingTo(prices.get(1));
    } finally {
      jdbc.update("DELETE FROM lp_price_linked_calc_item WHERE item_code LIKE 'BATCH-RACE-%'");
    }
  }

  @Test
  void indexedMonthSelectionKeepsScopeStatusFutureAndPartialMonthRules() throws Exception {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              // 两张表各有月份；未来、草稿、其他组织/业务单元、技术来源和删除行不能参与选月。
              for (String month : List.of("2037-10", "2037-12")) {
                jdbc.update(
                    "INSERT INTO lp_price_linked_item(business_unit_type,org_code,material_code,"
                        + "pricing_month,rule_status,source_kind,effective_from,created_at,updated_at)"
                        + " VALUES('HOUSEHOLD','220',?"
                        + " ,?,'PUBLISHED','PUBLIC','2037-10-01',NOW(),NOW())",
                    "BATCH-MONTH-" + month,
                    month);
              }
              jdbc.update(
                  "INSERT INTO"
                      + " lp_price_range_factor_rule(business_unit_type,org_code,material_code,"
                      + "pricing_month,rule_status,factor_code,import_batch_no,effective_from,created_at,updated_at)"
                      + " VALUES('HOUSEHOLD','220','BATCH-MONTH-OTHER','2037-11','PUBLISHED','Cu',"
                      + "'BATCH-MONTH','2037-11-01',NOW(),NOW())");
              assertThat(months.findLatestMonth("HOUSEHOLD", "220", "2037-10"))
                  .isEqualTo("2037-10");
              assertThat(months.findLatestMonth("HOUSEHOLD", "220", "2037-11"))
                  .isEqualTo("2037-11");
              assertThat(
                      months.countRangeRules("HOUSEHOLD", "220", "2037-11", "BATCH-MONTH-2037-10"))
                  .isZero();
              jdbc.update(
                  "UPDATE lp_price_range_factor_rule SET rule_status='DRAFT' WHERE"
                      + " material_code='BATCH-MONTH-OTHER'");
              // 测试直接用JDBC改输入，显式清除MyBatis会话缓存后核对实际SQL；生产导入用Mapper写入。
              sqlSession.clearCache();
              assertThat(months.findLatestMonth("HOUSEHOLD", "220", "2037-11"))
                  .isEqualTo("2037-10");
              for (String excluded :
                  List.of(
                      "org_code='210'",
                      "business_unit_type='COMMERCIAL'",
                      "source_kind='TECHNICAL'",
                      "deleted=1")) {
                jdbc.update(
                    "UPDATE lp_price_linked_item SET "
                        + excluded
                        + " WHERE material_code='BATCH-MONTH-2037-10'");
                sqlSession.clearCache();
                assertThat(months.findLatestMonth("HOUSEHOLD", "220", "2037-11"))
                    .isNotEqualTo("2037-10");
                jdbc.update(
                    "UPDATE lp_price_linked_item SET"
                        + " org_code='220',business_unit_type='HOUSEHOLD',source_kind='PUBLIC',deleted=0"
                        + " WHERE material_code='BATCH-MONTH-2037-10'");
              }
              jdbc.update(
                  "UPDATE lp_price_linked_item SET org_code=NULL WHERE"
                      + " material_code='BATCH-MONTH-2037-10'");
              sqlSession.clearCache();
              assertThat(months.findLatestMonth("HOUSEHOLD", "", "2037-11")).isEqualTo("2037-10");
              status.setRollbackOnly();
            });
    var repeat =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot marketing_cost <"
                + " /tmp/V315__original_price_month_lookup_indexes.sql");
    assertThat(repeat.getExitCode()).as(repeat.getStderr()).isZero();
  }

  @Test
  void generatedIdsAndEveryEvidenceFieldRoundTripIncludingFailureNulls() {
    var p0 = row("BATCH-P0", "FINANCE_QUOTE_BASE");
    var p1 = row("BATCH-P1", "OA_LOCKED");
    p1.setPartUnitPrice(null);
    p1.setPartAmount(null);
    p1.setInputSnapshotJson(null);
    p1.setCalcFingerprint(null);
    p1.setCalcStatus("FAILED");
    p1.setFailureCode("LINKED_RULE_ERROR");
    p1.setCalcMessage("缺少因素");
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              assertThat(mapper.insertResults(List.of(p0, p1))).isEqualTo(2);
              assertThat(p0.getId()).isPositive().isNotEqualTo(p1.getId());
              assertThat(p1.getId()).isPositive();
              for (var expected : List.of(p0, p1)) {
                assertThat(mapper.selectById(expected.getId()))
                    .usingRecursiveComparison()
                    .isEqualTo(expected);
              }
              status.setRollbackOnly();
            });
    assertThat(mapper.selectById(p0.getId())).isNull();
    assertThat(mapper.selectById(p1.getId())).isNull();
  }

  @Test
  void laterBatchUniqueConflictRollsBackEarlierBatchAndDoesNotOverwrite() {
    var retained = row("BATCH-EXISTING", "OA_LOCKED");
    mapper.insert(retained);
    try {
      var first = row("BATCH-ROLLBACK-A", "OA_LOCKED");
      var second = row("BATCH-ROLLBACK-B", "OA_LOCKED");
      var conflicting = row("BATCH-EXISTING", "OA_LOCKED");
      conflicting.setPartUnitPrice(new BigDecimal("999.000000"));
      assertThatThrownBy(
              () ->
                  new TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            mapper.insertResults(List.of(first, second));
                            mapper.insertResults(
                                List.of(row("BATCH-ROLLBACK-C", "OA_LOCKED"), conflicting));
                          }))
          .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM lp_price_linked_calc_item WHERE item_code LIKE"
                      + " 'BATCH-ROLLBACK-%'",
                  Integer.class))
          .isZero();
      assertThat(mapper.selectById(retained.getId()).getPartUnitPrice())
          .isEqualByComparingTo(retained.getPartUnitPrice());
    } finally {
      mapper.deleteById(retained.getId());
    }
  }

  private PriceLinkedCalcItem row(String code, String side) {
    var row = new PriceLinkedCalcItem();
    row.setOaNo("PRC10-BATCH-WRITE");
    row.setItemCode(code);
    row.setShapeAttr("外购");
    row.setBomQty(new BigDecimal("2.000000"));
    row.setPartUnitPrice(new BigDecimal("84.070796"));
    row.setPartAmount(new BigDecimal("168.141592"));
    row.setBusinessUnitType("COMMERCIAL");
    row.setCalcScene("QUOTE");
    row.setPricingMonth("2026-10");
    row.setFactorSource(side);
    row.setPriceAsOfTime(LocalDateTime.of(2026, 10, 1, 12, 0));
    row.setPriceOrgCode("210");
    row.setCalcFingerprint("fingerprint-" + code);
    row.setCalcStatus("OK");
    row.setSourcePriceRecordId(123L);
    row.setSourcePriceBatchNo("batch-123");
    row.setSupplierName("供应商");
    row.setSupplierCode("S1");
    row.setSupplyRatio(new BigDecimal("100.000000"));
    row.setSupplyRatioRecordId(456L);
    row.setSourceEffectiveFrom(LocalDate.of(2026, 10, 1));
    row.setSourceEffectiveTo(LocalDate.of(2026, 10, 31));
    row.setCarriedForward(1);
    row.setWarningMessage("沿用历史月份");
    row.setTraceJson("{\"result\":84.070796}");
    // MySQL JSON字段读取时使用其规范空格格式。
    row.setInputSnapshotJson("{\"materialCode\": \"" + code + "\"}");
    row.setCalculatorVersion("ORIGINAL_V1");
    row.setResultRevision(0L);
    row.setCreatedAt(LocalDateTime.of(2026, 10, 13, 12, 0));
    row.setUpdatedAt(row.getCreatedAt());
    return row;
  }
}
