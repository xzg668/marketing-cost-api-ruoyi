package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult;
import com.sanhua.marketingcost.service.SupplierSupplyRatioResolveService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SupplierPreferredPriceSelectorTest {

  private SupplierSupplyRatioResolveService resolveService;
  private SupplierPreferredPriceSelector selector;

  @BeforeEach
  void setUp() {
    resolveService = mock(SupplierSupplyRatioResolveService.class);
    selector = new SupplierPreferredPriceSelector(resolveService);
  }

  @Test
  @DisplayName("先取整体主供，再按供应商代码精确匹配价格")
  void selectsOverallPrimarySupplierByExactCode() {
    Candidate supplierA = new Candidate("同名供应商", "SUP-A", "10");
    Candidate supplierB = new Candidate("同名供应商", "SUP-B", "20");
    when(resolveService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(hit(72L, "同名供应商", "SUP-B", "0.7"));

    SupplierPreferredPriceSelection<Candidate> selected = select(List.of(supplierA, supplierB));

    assertThat(selected.row()).isSameAs(supplierB);
    assertThat(selected.failed()).isFalse();
    assertThat(selected.mainSupplierCode()).isEqualTo("SUP-B");
    assertThat(selected.supplyRatio()).isEqualByComparingTo("0.7");
    assertThat(selected.supplyRatioRecordId()).isEqualTo(72L);
    assertThat(selected.matchMode()).isEqualTo("CODE");
  }

  @Test
  @DisplayName("一方缺代码时可按标准化名称匹配")
  void matchesNormalizedNameWhenOneCodeIsMissing() {
    Candidate supplierA = new Candidate("吉林省 合信汽配有限公司", null, "10");
    Candidate supplierB = new Candidate("其他供应商", "SUP-B", "20");
    when(resolveService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(hit(1L, "吉林省合信汽配有限公司", "SUP-A", "0.7"));

    SupplierPreferredPriceSelection<Candidate> selected = select(List.of(supplierB, supplierA));

    assertThat(selected.row()).isSameAs(supplierA);
    assertThat(selected.matchMode()).isEqualTo("NAME_FALLBACK");
  }

  @Test
  @DisplayName("主供应商没有价格时结构化阻断，不取第二名")
  void blocksWhenPrimarySupplierHasNoPrice() {
    Candidate supplierA = new Candidate("供应商A", "SUP-A", "10");
    Candidate supplierB = new Candidate("供应商B", "SUP-B", "20");
    when(resolveService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(hit(9L, "供应商C", "SUP-C", "0.9"));

    SupplierPreferredPriceSelection<Candidate> selected = select(List.of(supplierA, supplierB));

    assertThat(selected.row()).isNull();
    assertThat(selected.failed()).isTrue();
    assertThat(selected.failureCode())
        .isEqualTo(SupplierPreferredPriceSelector.PRIMARY_SUPPLIER_PRICE_MISSING);
    assertThat(selected.failureMessage()).contains("主供应商无价格", "SUP-C");
  }

  @Test
  @DisplayName("价格候选只有一家供应商时直接取价，不查询供货比例")
  void singlePriceSupplierBypassesSupplierRatioSelection() {
    Candidate onlySupplier = new Candidate("供应商B", "SUP-B", "20");

    SupplierPreferredPriceSelection<Candidate> selected = select(List.of(onlySupplier));

    assertThat(selected.row()).isSameAs(onlySupplier);
    assertThat(selected.matchMode()).isEqualTo("SINGLE_SUPPLIER");
    assertThat(selected.failed()).isFalse();
    verifyNoInteractions(resolveService);
  }

  @Test
  @DisplayName("多供应商未维护供货比例时不再默认取第一条")
  void blocksMultipleSuppliersWithoutRatio() {
    when(resolveService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(SupplierSupplyRatioResolveResult.miss("未维护"));

    SupplierPreferredPriceSelection<Candidate> selected = select(List.of(
        new Candidate("供应商A", "SUP-A", "10"),
        new Candidate("供应商B", "SUP-B", "20")));

    assertThat(selected.row()).isNull();
    assertThat(selected.failureCode())
        .isEqualTo(SupplierPreferredPriceSelector.SUPPLIER_RATIO_MISSING);
  }

  @Test
  @DisplayName("同一供应商存在多个价格版本时仍按价格版本排序取第一条")
  void multipleVersionsOfSingleSupplierUseVersionOrder() {
    Candidate latest = new Candidate("供应商A", "SUP-A", "10");
    Candidate history = new Candidate("供应商A", "SUP-A", "9");

    SupplierPreferredPriceSelection<Candidate> selected = select(List.of(latest, history));

    assertThat(selected.row()).isSameAs(latest);
    assertThat(selected.matchMode()).isEqualTo("SINGLE_SUPPLIER");
    assertThat(selected.failed()).isFalse();
    verifyNoInteractions(resolveService);
  }

  @Test
  @DisplayName("无供应商维度的结算价按价格版本排序取第一条")
  void sourceWithoutSupplierDimensionUsesVersionOrder() {
    Candidate latest = new Candidate(null, null, "10");

    SupplierPreferredPriceSelection<Candidate> selected = select(List.of(latest));

    assertThat(selected.row()).isSameAs(latest);
    assertThat(selected.matchMode()).isEqualTo("NO_SUPPLIER_DIMENSION");
    assertThat(selected.failed()).isFalse();
  }

  private SupplierPreferredPriceSelection<Candidate> select(List<Candidate> candidates) {
    return selector.select(
        candidates,
        "COMMERCIAL",
        "201503873",
        "管件",
        "SPEC-A",
        LocalDate.of(2026, 7, 1),
        Candidate::supplierName,
        Candidate::supplierCode);
  }

  private SupplierSupplyRatioResolveResult hit(
      Long id, String supplierName, String supplierCode, String ratio) {
    SupplierSupplyRatioResolveResult result = new SupplierSupplyRatioResolveResult();
    result.setMatched(true);
    result.setId(id);
    result.setSupplierName(supplierName);
    result.setSupplierCode(supplierCode);
    result.setSupplyRatio(new BigDecimal(ratio));
    return result;
  }

  private record Candidate(
      String supplierName,
      String supplierCode,
      String price) {
  }
}
