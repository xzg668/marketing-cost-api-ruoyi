package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.sanhua.marketingcost.dto.PriceLinkedImportBasisSaveResult;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisSnapshot;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariableBinding;
import com.sanhua.marketingcost.service.MaterialPriceTypeRouteSyncService;
import java.lang.reflect.Method;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("PLI2-09 类型2导入依据完整持久化")
class PriceLinkedImportBasisPersistenceTest {

  @Test
  @DisplayName("新公式版本一次写入来源、原公式、输入快照、结果快照和因素绑定")
  void persistsCompleteImportBasis() throws Exception {
    PriceLinkedImportBasisTestSupport support = new PriceLinkedImportBasisTestSupport();

    PriceLinkedImportBasisSaveResult result = support.service.save(
        support.defaultRequest(88001L, LocalDate.of(2026, 7, 1)));

    assertThat(result.action()).isEqualTo(PriceLinkedImportBasisSaveResult.ACTION_CREATED);
    assertThat(result.factorBindingCount()).isOne();
    assertThat(support.repository.items).hasSize(1);
    PriceLinkedItem item = support.repository.items.getFirst();
    assertThat(item.getSourceUploadBatchId()).isEqualTo(88001L);
    assertThat(item.getSourceSheetName()).isEqualTo("Sheet1");
    assertThat(item.getSourceRowNumber()).isEqualTo(6);
    assertThat(item.getSourceFormulaCellRef()).isEqualTo("R6");
    assertThat(item.getSourceFormulaExpr()).isEqualTo("$E$2+G6");
    assertThat(item.getFormulaExpr()).isEqualTo("[factor_identity_191]+23");
    assertThat(item.getSourceTaxIncludedPrice()).isEqualByComparingTo("113");
    assertThat(item.getSourceTaxExcludedPrice()).isEqualByComparingTo("100");
    PriceLinkedImportBasisSnapshot snapshot = support.objectMapper.readValue(
        item.getSourceInputSnapshotJson(), PriceLinkedImportBasisSnapshot.class);
    assertThat(snapshot.inputCells()).hasSize(2);
    assertThat(snapshot.inputCells()).extracting("cellRef").containsExactly("E2", "G6");
    assertThat(snapshot.factorInputs()).singleElement().satisfies(factor -> {
      assertThat(factor.originalName()).isEqualTo("1#Cu");
      assertThat(factor.factorIdentityId()).isEqualTo(191L);
      assertThat(factor.importedPrice()).isEqualByComparingTo("90.000");
    });
    assertThat(snapshot.taxBasis().normalizedTaxIncluded()).isZero();
    assertThat(snapshot.reconcileBasis().taxIncluded().passed()).isTrue();
    assertThat(snapshot.reconcileBasis().taxExcluded().passed()).isTrue();

    PriceVariableBinding binding = support.repository.bindings.getFirst();
    assertThat(binding.getLinkedItemId()).isEqualTo(item.getId());
    assertThat(binding.getFactorCode()).isEqualTo("factor_identity_191");
    assertThat(binding.getFactorIdentityId()).isEqualTo(191L);
    assertThat(binding.getFactorMonthlyPriceId()).isEqualTo(6191L);
    assertThat(binding.getFactorUploadBatchId()).isEqualTo(88001L);
    assertThat(binding.getExcelSourceSheetName()).isEqualTo("Sheet1");
    assertThat(binding.getExcelSourceCellRef()).isEqualTo("E2");
    assertThat(binding.getSource()).isEqualTo("TYPE2_IMPORT");
    ArgumentCaptor<MaterialPriceTypeRouteSyncService.RouteCommand> routeCaptor =
        ArgumentCaptor.forClass(MaterialPriceTypeRouteSyncService.RouteCommand.class);
    verify(support.priceTypeRouteSyncService).sync(routeCaptor.capture());
    assertThat(routeCaptor.getValue().priceType()).isEqualTo("联动价");
  }

  @Test
  @DisplayName("高精度小数以普通十进制写入JSON且反序列化精度不丢失")
  void preservesBigDecimalPrecisionInJson() throws Exception {
    PriceLinkedImportBasisTestSupport support = new PriceLinkedImportBasisTestSupport();
    support.service.save(support.request(
        88002L,
        LocalDate.of(2026, 7, 1),
        "$E$2+G6",
        "0.000000123456",
        "90.000000123456",
        null,
        "TRUE",
        "COMMERCIAL"));

    String json = support.repository.items.getFirst().getSourceInputSnapshotJson();
    assertThat(json).contains("0.000000123456").doesNotContain("1.23456E-7");
    PriceLinkedImportBasisSnapshot snapshot = support.objectMapper.readValue(
        json, PriceLinkedImportBasisSnapshot.class);
    assertThat(snapshot.inputCells().get(1).numericValue())
        .isEqualByComparingTo("0.000000123456");
    assertThat(snapshot.reconcileBasis().formulaResult())
        .isEqualByComparingTo("90.000000123456");
  }

  @Test
  @DisplayName("普通空白输入保留原始空值并记录计算按0")
  void persistsBlankDefaultedToZeroAudit() throws Exception {
    PriceLinkedImportBasisTestSupport support = new PriceLinkedImportBasisTestSupport();

    support.service.save(
        support.requestWithBlankFixedInput(88003L, LocalDate.of(2026, 7, 1)));

    PriceLinkedImportBasisSnapshot snapshot = support.objectMapper.readValue(
        support.repository.items.getFirst().getSourceInputSnapshotJson(),
        PriceLinkedImportBasisSnapshot.class);
    assertThat(snapshot.inputCells())
        .filteredOn(cell -> "G6".equals(cell.cellRef()))
        .singleElement()
        .satisfies(cell -> {
          assertThat(cell.displayValue()).isEmpty();
          assertThat(cell.numericValue()).isNull();
          assertThat(cell.calculationValue()).isZero();
          assertThat(cell.blankDefaultedToZero()).isTrue();
          assertThat(cell.sourceCellType()).isEqualTo("BLANK");
        });
    assertThat(support.repository.items.getFirst().getFormulaExpr())
        .isEqualTo("[factor_identity_191]+0");
  }

  @Test
  @DisplayName("旧快照缺少空白处理字段时仍可反序列化")
  void readsLegacySnapshotWithoutBlankAuditFields() throws Exception {
    PriceLinkedImportBasisTestSupport support = new PriceLinkedImportBasisTestSupport();
    String legacyJson = """
        {
          "sourceFormula":"G6",
          "inputCells":[{
            "sheetName":"Sheet1",
            "cellRef":"G6",
            "header":"加工费",
            "displayValue":"23",
            "numericValue":23,
            "sourceCellFormula":null,
            "unit":"元/只"
          }],
          "factorInputs":[],
          "taxBasis":null,
          "reconcileBasis":null
        }
        """;

    PriceLinkedImportBasisSnapshot snapshot = support.objectMapper.readValue(
        legacyJson, PriceLinkedImportBasisSnapshot.class);

    assertThat(snapshot.inputCells()).singleElement().satisfies(cell -> {
      assertThat(cell.numericValue()).isEqualByComparingTo("23");
      assertThat(cell.calculationValue()).isNull();
      assertThat(cell.blankDefaultedToZero()).isFalse();
      assertThat(cell.sourceCellType()).isNull();
    });
  }

  @Test
  @DisplayName("写入入口声明任意异常回滚，防止版本和绑定只成功一半")
  void saveDeclaresRollbackForException() throws Exception {
    Method method = PriceLinkedImportBasisServiceImpl.class.getMethod(
        "save", com.sanhua.marketingcost.dto.PriceLinkedImportBasisSaveRequest.class);
    Transactional annotation = method.getAnnotation(Transactional.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.rollbackFor()).contains(Exception.class);
  }
}
