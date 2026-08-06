package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.PriceLinkedImportBasisResponse;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-09 类型2导入依据只读查询")
class PriceLinkedImportBasisServiceTest {

  private PriceLinkedImportBasisTestSupport support;

  @AfterEach
  void tearDown() {
    if (support != null) {
      support.clearLogin();
    }
  }

  @Test
  @DisplayName("返回原公式、输入字段、因素身份、系统公式、税转换和双口径差异")
  void returnsCompleteSavedBasis() {
    support = new PriceLinkedImportBasisTestSupport();
    FactorUploadBatch batch = new FactorUploadBatch();
    batch.setId(88101L);
    batch.setBatchNo("PLI2-202607-001");
    batch.setFileName("采购价联动类型2-202607.xls");
    support.repository.batches.put(batch.getId(), batch);
    long id = support.service.save(
        support.defaultRequest(88101L, LocalDate.of(2026, 7, 1))).linkedItemId();
    support.login("COMMERCIAL");

    PriceLinkedImportBasisResponse response = support.service.getImportBasis(id);

    assertThat(response).isNotNull();
    assertThat(response.isImportBasisAvailable()).isTrue();
    assertThat(response.getSourceBatchNo()).isEqualTo("PLI2-202607-001");
    assertThat(response.getSourceFileName()).isEqualTo("采购价联动类型2-202607.xls");
    assertThat(response.getSourceFormula()).isEqualTo("$E$2+G6");
    assertThat(response.getSystemFormula()).isEqualTo("[factor_identity_191]+23");
    assertThat(response.getSnapshot().inputCells()).extracting("header")
        .containsExactly("1#Cu", "加工费");
    assertThat(response.getSnapshot().inputCells()).extracting("unit")
        .containsExactly("元/公斤", "元/只");
    assertThat(response.getSnapshot().taxBasis().taxAdjustmentRequired()).isTrue();
    assertThat(response.getSnapshot().reconcileBasis().taxIncluded().passed()).isTrue();
    assertThat(response.getSnapshot().reconcileBasis().taxExcluded().passed()).isTrue();
    assertThat(response.getFactorBindings()).singleElement().satisfies(factor -> {
      assertThat(factor.originalName()).isEqualTo("1#Cu");
      assertThat(factor.factorIdentityId()).isEqualTo(191L);
      assertThat(factor.factorMonthlyPriceId()).isEqualTo(6191L);
      assertThat(factor.importedPrice()).isEqualByComparingTo("90.000");
      assertThat(factor.systemVariable()).isEqualTo("factor_identity_191");
    });
  }

  @Test
  @DisplayName("老联动价来源字段为空时仍能查询基本信息且不读取绑定、不写库")
  void supportsLegacyItemWithoutType2Basis() {
    support = new PriceLinkedImportBasisTestSupport();
    PriceLinkedItem legacy = legacyItem(301L, "COMMERCIAL");
    support.repository.items.add(legacy);
    support.login("COMMERCIAL");
    int itemWrites = support.repository.itemInsertCount + support.repository.itemUpdateCount;
    int bindingWrites = support.repository.bindingInsertCount;

    PriceLinkedImportBasisResponse response = support.service.getImportBasis(301L);

    assertThat(response).isNotNull();
    assertThat(response.isImportBasisAvailable()).isFalse();
    assertThat(response.getMessage()).contains("历史联动价");
    assertThat(response.getSystemFormula()).isEqualTo("[factor_identity_191]+1");
    assertThat(response.getSnapshot()).isNull();
    assertThat(support.repository.bindingReadCount).isZero();
    assertThat(support.repository.itemInsertCount + support.repository.itemUpdateCount)
        .isEqualTo(itemWrites);
    assertThat(support.repository.bindingInsertCount).isEqualTo(bindingWrites);
  }

  @Test
  @DisplayName("不存在ID返回空，查询不会补建任何记录")
  void missingIdReturnsNullWithoutWrites() {
    support = new PriceLinkedImportBasisTestSupport();
    support.login("COMMERCIAL");

    assertThat(support.service.getImportBasis(999999L)).isNull();
    assertThat(support.repository.itemInsertCount).isZero();
    assertThat(support.repository.itemUpdateCount).isZero();
    assertThat(support.repository.bindingInsertCount).isZero();
  }

  @Test
  @DisplayName("普通用户仅能读取本业务单元，管理员可跨业务单元审计")
  void isolatesBusinessUnitAndAllowsAdminAudit() {
    support = new PriceLinkedImportBasisTestSupport();
    PriceLinkedItem otherBu = legacyItem(401L, "HOUSEHOLD");
    support.repository.items.add(otherBu);
    support.login("COMMERCIAL");

    assertThat(support.service.getImportBasis(401L)).isNull();

    support.loginAdmin();
    assertThat(support.service.getImportBasis(401L)).isNotNull();
  }

  @Test
  @DisplayName("无登录业务单元上下文时不返回导入依据")
  void unauthenticatedContextCannotRead() {
    support = new PriceLinkedImportBasisTestSupport();
    support.repository.items.add(legacyItem(501L, "COMMERCIAL"));

    assertThat(support.service.getImportBasis(501L)).isNull();
  }

  @Test
  @DisplayName("查询仅反序列化快照，不会更新版本或绑定")
  void queryHasNoRecalculationOrWriteSideEffect() {
    support = new PriceLinkedImportBasisTestSupport();
    long id = support.service.save(
        support.defaultRequest(88102L, LocalDate.of(2026, 7, 1))).linkedItemId();
    support.login("COMMERCIAL");
    int itemWrites = support.repository.itemInsertCount + support.repository.itemUpdateCount;
    int bindingWrites = support.repository.bindingInsertCount;

    PriceLinkedImportBasisResponse first = support.service.getImportBasis(id);
    PriceLinkedImportBasisResponse second = support.service.getImportBasis(id);

    assertThat(first.getSourceInputSnapshotJson())
        .isEqualTo(second.getSourceInputSnapshotJson());
    assertThat(support.repository.itemInsertCount + support.repository.itemUpdateCount)
        .isEqualTo(itemWrites);
    assertThat(support.repository.bindingInsertCount).isEqualTo(bindingWrites);
  }

  private PriceLinkedItem legacyItem(long id, String businessUnitType) {
    PriceLinkedItem item = new PriceLinkedItem();
    item.setId(id);
    item.setPricingMonth("2026-07");
    item.setMaterialCode("LEGACY-001");
    item.setBusinessUnitType(businessUnitType);
    item.setFormulaExpr("[factor_identity_191]+1");
    item.setEffectiveFrom(LocalDate.of(2026, 7, 1));
    item.setDeleted(0);
    return item;
  }
}
