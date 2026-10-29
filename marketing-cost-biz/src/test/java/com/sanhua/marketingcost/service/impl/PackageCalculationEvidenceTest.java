package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.service.pricing.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PackageCalculationEvidenceTest {
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  @Test
  void historicalEvidenceRetainsItsSavedChildDenominator() throws Exception {
    var legacy = item("FIXED", false);
    var tree = JSON.readTree(legacy.getInputSnapshotJson());
    var child = (ObjectNode) tree.path("components").get(0);
    child.remove("quantityBasis"); child.put("parentBaseQty", 2); child.put("amount", 12);
    legacy.setInputSnapshotJson(tree.toString());
    legacy.setUnitPrice(new BigDecimal("6")); legacy.setAmount(new BigDecimal("18"));
    assertThat(new PackageCalculationEvidence(JSON).validatePair(legacy, legacy, null, null)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"LINKED", "RANGE"})
  void validatesStoredChildPricesAndParentBaseQuantityWithoutFormulaCalculation(String type)
      throws Exception {
    var validator = new PackageCalculationEvidence(JSON);
    assertThat(
            validator.validatePair(
                item(type, true), item(type, false), new BigDecimal("100"), new BigDecimal("90")))
        .isTrue();
    assertThatThrownBy(
            () ->
                validator.validatePair(
                    item(type, true),
                    item(type, false),
                    new BigDecimal("101"),
                    new BigDecimal("90")))
        .hasMessageContaining("Cu");
    var modified = item(type, false);
    modified.setUnitPrice(new BigDecimal("999"));
    assertThatThrownBy(() -> validator.validatePair(item(type, true), modified, null, null))
        .hasMessageContaining("合计");
  }

  @ParameterizedTest
  @ValueSource(strings = {"LINKED", "RANGE"})
  void rejectsChangedSupplierOrFinancialPublication(String type) throws Exception {
    for (String scope : List.of("supplier", "finance")) {
      var finance = item(type, false);
      var tree = JSON.readTree(finance.getInputSnapshotJson());
      var inputs = tree.path("components").get(0).path("inputs");
      if (scope.equals("supplier"))
        ((ObjectNode) inputs.path("ruleIdentity")).put("supplierCode", "OTHER");
      else ((ObjectNode) inputs.path("context").path("financial")).put("batchId", 99);
      finance.setInputSnapshotJson(tree.toString());
      assertThatThrownBy(
              () ->
                  new PackageCalculationEvidence(JSON)
                      .validatePair(item(type, true), finance, null, null))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void rejectsChangedQuantitiesAndKeepsFixedPackageExact() throws Exception {
    var finance = item("RANGE", false);
    var tree = JSON.readTree(finance.getInputSnapshotJson());
    ((ObjectNode) tree.path("components").get(0)).put("quantity", 3);
    finance.setInputSnapshotJson(tree.toString());
    assertThatThrownBy(
            () ->
                new PackageCalculationEvidence(JSON)
                    .validatePair(item("RANGE", true), finance, null, null))
        .hasMessageContaining("quantity");
    var fixed = item("FIXED", false);
    assertThat(new PackageCalculationEvidence(JSON).validatePair(fixed, fixed, null, null))
        .isFalse();
    assertThatThrownBy(
            () ->
                new PackageCalculationEvidence(JSON)
                    .validatePair(item("FIXED", true), fixed, null, null))
        .hasMessageContaining("非Cu");
  }

  static PricePrepareItem item(String type, boolean customer) throws Exception {
    BigDecimal copper = customer ? new BigDecimal("100") : new BigDecimal("90");
    BigDecimal price = customer ? new BigDecimal("13") : new BigDecimal("12");
    var scope =
        new FactorPriceScope(
            "COMMERCIAL", "210", "2026-05", "BASE", FactorPriceScope.Condition.GENERAL);
    var base =
        new FactorPriceSnapshot(
            scope,
            42L,
            1,
            "finance.xlsx",
            "hash",
            "content",
            Map.of(
                PriceFactorContext.COPPER,
                new FactorPriceSnapshot.FactorValue(
                    1L, 2L, "Cu", "Cu", new BigDecimal("90"), "CNY/KG", true, List.of())),
            List.of("BASE"),
            "test",
            LocalDateTime.of(2026, 5, 1, 0, 0),
            null);
    var context =
        new PriceFactorContext(
            "CHILD",
            new PriceMonthSelection("2026-05", "2026-05"),
            base,
            customer ? new BigDecimal("100000") : null);
    var key = new MaterialPriceRuleKey("COMMERCIAL", "210", "CHILD", "S1", "Supplier", "SPEC", "只");
    String inputs = null;
    if (type.equals("RANGE"))
      inputs =
          JSON.writeValueAsString(
              new OriginalRangePriceRuntime.Calculation(
                  OriginalRangeRuleSnapshot.VERSION,
                  1L,
                  1,
                  "2026-05",
                  40L,
                  key,
                  PriceFactorContext.COPPER,
                  FactorPriceUnit.CNY_TON,
                  context,
                  new OriginalRangeRuleCalculator.Result(
                      copper.movePointRight(3),
                      price,
                      price,
                      new OriginalRangeWorkbookParser.Bounds(
                          BigDecimal.ZERO, new BigDecimal("200000"), true, true),
                      null,
                      null),
                  10L));
    if (type.equals("LINKED")) {
      var root = JSON.createObjectNode();
      root.put("calculatorVersion", OriginalLinkedRuleSnapshot.VERSION);
      root.put("ruleId", 1);
      root.put("ruleRevision", 1);
      root.put("ruleMonth", "2026-05");
      root.put("sourceUploadBatchId", 40);
      root.put("effectivePriceId", 10);
      root.put("expression", "[cu]*0.1+3");
      root.put("sourceTaxIncluded", false);
      root.set("ruleIdentity", JSON.valueToTree(key));
      root.set("context", JSON.valueToTree(context));
      root.set(
          "factorBindings",
          JSON.valueToTree(
              Map.of("cu", Map.of("canonicalKey", PriceFactorContext.COPPER, "unit", "CNY_KG"))));
      root.set("inputs", JSON.valueToTree(Map.of("cu", copper)));
      root.put("priceExcludingTax", price);
      inputs = root.toString();
    }
    var parent = new PackageComponentPrice();
    parent.setPackageMaterialCode("PACK");
    parent.setBusinessUnitType("COMMERCIAL");
    parent.setPriceOrgCode("210");
    parent.setPeriodMonth("2026-05");
    parent.setSnapshotId(100L);
    parent.setPackageParentBaseQty(new BigDecimal("2"));
    var child = new PackageComponentPriceDetail();
    child.setSnapshotDetailId(101L);
    child.setChildMaterialCode("CHILD");
    child.setPriceType(type);
    child.setPriceSource(type);
    child.setQtyPerParent(new BigDecimal("2"));
    child.setChildParentBaseQty(BigDecimal.ONE);
    child.setChildUnitPrice(price);
    child.setChildAmount(price.multiply(new BigDecimal("2")));
    child.setInputSnapshotJson(inputs);
    var item = new PricePrepareItem();
    item.setMaterialCode("PACK");
    item.setBusinessUnitType("COMMERCIAL");
    item.setPeriodMonth("2026-05");
    item.setStatus("READY");
    item.setSettlementKey("SET:PACK");
    item.setItemType("PACKAGE_COMPONENT");
    item.setResultRefType("PACKAGE_PRICE");
    item.setQuantity(new BigDecimal("3"));
    item.setUnitPrice(price);
    item.setAmount(price.multiply(new BigDecimal("3")));
    item.setInputSnapshotJson(new PackageCalculationEvidence(JSON).capture(parent, List.of(child)));
    return item;
  }
}
