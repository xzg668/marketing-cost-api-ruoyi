package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.PriceLinkedImportBasisResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisSaveResult;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-09 类型2公式版本和快照隔离")
class PriceLinkedImportBasisVersionIsolationTest {

  private PriceLinkedImportBasisTestSupport support;

  @AfterEach
  void tearDown() {
    if (support != null) {
      support.clearLogin();
    }
  }

  @Test
  @DisplayName("同公式重复导入直接复用版本，不覆盖原批次、JSON或因素绑定")
  void duplicateFormulaDoesNotOverwriteSnapshot() {
    support = new PriceLinkedImportBasisTestSupport();
    PriceLinkedImportBasisSaveResult first = support.service.save(
        support.defaultRequest(88201L, LocalDate.of(2026, 7, 1)));
    PriceLinkedItem original = support.repository.items.getFirst();
    String originalJson = original.getSourceInputSnapshotJson();

    PriceLinkedImportBasisSaveResult duplicate = support.service.save(
        support.defaultRequest(99999L, LocalDate.of(2026, 7, 15)));

    assertThat(duplicate.action())
        .isEqualTo(PriceLinkedImportBasisSaveResult.ACTION_DUPLICATE_SKIPPED);
    assertThat(duplicate.linkedItemId()).isEqualTo(first.linkedItemId());
    assertThat(support.repository.items).hasSize(1);
    assertThat(support.repository.bindings).hasSize(1);
    assertThat(original.getSourceUploadBatchId()).isEqualTo(88201L);
    assertThat(original.getSourceInputSnapshotJson()).isEqualTo(originalJson);
    assertThat(support.repository.itemUpdateCount).isZero();
  }

  @Test
  @DisplayName("原公式变化创建新版本，旧版本按本次版本日期归档")
  void changedFormulaCreatesNewVersion() {
    support = new PriceLinkedImportBasisTestSupport();
    PriceLinkedImportBasisSaveResult first = support.service.save(
        support.defaultRequest(88202L, LocalDate.of(2026, 7, 1)));

    PriceLinkedImportBasisSaveResult second = support.service.save(support.request(
        88203L,
        LocalDate.of(2026, 7, 15),
        "$E$2+G6+1",
        "23",
        "114",
        null,
        "TRUE",
        "COMMERCIAL"));

    assertThat(second.action()).isEqualTo(PriceLinkedImportBasisSaveResult.ACTION_CREATED);
    assertThat(second.previousVersionId()).isEqualTo(first.linkedItemId());
    assertThat(support.repository.items).hasSize(2);
    assertThat(support.repository.items.get(0).getEffectiveTo())
        .isEqualTo(LocalDate.of(2026, 7, 15));
    assertThat(support.repository.items.get(1).getEffectiveTo()).isNull();
    assertThat(support.repository.items.get(1).getFormulaExpr())
        .isEqualTo("[factor_identity_191]+23+1");
    assertThat(support.repository.bindings).hasSize(2);
  }

  @Test
  @DisplayName("按旧ID和新ID查询时分别返回各自原公式、批次和系统公式")
  void historyVersionsKeepIndependentBasis() {
    support = new PriceLinkedImportBasisTestSupport();
    long oldId = support.service.save(
        support.defaultRequest(88204L, LocalDate.of(2026, 7, 1))).linkedItemId();
    long newId = support.service.save(support.request(
        88205L,
        LocalDate.of(2026, 7, 15),
        "$E$2+G6+1",
        "23",
        "114",
        null,
        "TRUE",
        "COMMERCIAL")).linkedItemId();
    support.login("COMMERCIAL");

    PriceLinkedImportBasisResponse oldBasis = support.service.getImportBasis(oldId);
    PriceLinkedImportBasisResponse newBasis = support.service.getImportBasis(newId);

    assertThat(oldBasis.getSourceUploadBatchId()).isEqualTo(88204L);
    assertThat(oldBasis.getSourceFormula()).isEqualTo("$E$2+G6");
    assertThat(oldBasis.getSystemFormula()).isEqualTo("[factor_identity_191]+23");
    assertThat(newBasis.getSourceUploadBatchId()).isEqualTo(88205L);
    assertThat(newBasis.getSourceFormula()).isEqualTo("$E$2+G6+1");
    assertThat(newBasis.getSystemFormula()).isEqualTo("[factor_identity_191]+23+1");
    assertThat(oldBasis.getSourceInputSnapshotJson())
        .isNotEqualTo(newBasis.getSourceInputSnapshotJson());
  }

  @Test
  @DisplayName("历史老记录即使公式相同也不被补写，而是创建带依据的新版本")
  void legacySameFormulaCreatesAuditableType2Version() {
    support = new PriceLinkedImportBasisTestSupport();
    PriceLinkedItem legacy = new PriceLinkedItem();
    legacy.setId(700L);
    legacy.setPricingMonth("2026-07");
    legacy.setMaterialCode("MAT-6");
    legacy.setSupplierCode("SUP-001");
    legacy.setBusinessUnitType("COMMERCIAL");
    legacy.setFormulaExpr("[factor_identity_191]+23");
    legacy.setTaxIncluded(0);
    legacy.setEffectiveFrom(LocalDate.of(2026, 7, 1));
    support.repository.items.add(legacy);

    PriceLinkedImportBasisSaveResult result = support.service.save(
        support.defaultRequest(88206L, LocalDate.of(2026, 7, 15)));

    assertThat(result.action()).isEqualTo(PriceLinkedImportBasisSaveResult.ACTION_CREATED);
    assertThat(result.previousVersionId()).isEqualTo(700L);
    assertThat(legacy.getSourceUploadBatchId()).isNull();
    assertThat(legacy.getSourceInputSnapshotJson()).isNull();
    assertThat(legacy.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 7, 15));
    assertThat(support.repository.items).hasSize(2);
  }

  @Test
  @DisplayName("公式版本日期不再阻断新导入，版本先后由正式导入顺序确定")
  void effectiveDateDoesNotBlockNewImportVersion() {
    support = new PriceLinkedImportBasisTestSupport();
    long firstId = support.service.save(
        support.defaultRequest(88207L, LocalDate.of(2026, 7, 15))).linkedItemId();

    PriceLinkedImportBasisSaveResult result = support.service.save(support.request(
        88208L,
        LocalDate.of(2026, 7, 1),
        "$E$2+G6+1",
        "23",
        "114",
        null,
        "TRUE",
        "COMMERCIAL"));

    assertThat(result.action()).isEqualTo(PriceLinkedImportBasisSaveResult.ACTION_CREATED);
    assertThat(result.previousVersionId()).isEqualTo(firstId);
    assertThat(support.repository.items).hasSize(2);
    assertThat(support.repository.itemUpdateCount).isEqualTo(1);
  }
}
