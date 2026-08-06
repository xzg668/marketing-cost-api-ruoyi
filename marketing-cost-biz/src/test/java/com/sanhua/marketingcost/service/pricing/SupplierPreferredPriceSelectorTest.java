package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
  @DisplayName("RPI1-09 双方有代码时按代码精确匹配")
  void selectsCandidateByExactSupplierCode() {
    Candidate supplierA = new Candidate("同名供应商", "SUP-A", "10");
    Candidate supplierB = new Candidate("同名供应商", "SUP-B", "20");
    when(resolveService.resolveAmongSuppliers(any(), any(), any(), any(), any(), any()))
        .thenReturn(hit("同名供应商", "SUP-B", "0.7"));

    SupplierPreferredPriceSelection<Candidate> selected = select(List.of(supplierA, supplierB));

    assertThat(selected.row()).isSameAs(supplierB);
    assertThat(selected.traceMessage()).contains("主供应商供货比例");
    assertThat(selected.candidateSupplierCount()).isEqualTo(2);
    assertThat(selected.mainSupplierCode()).isEqualTo("SUP-B");
    assertThat(selected.mainSupplierName()).isEqualTo("同名供应商");
    assertThat(selected.supplyRatio()).isEqualByComparingTo("0.7");
    assertThat(selected.matchMode()).isEqualTo("CODE");
    assertThat(selected.fallback()).isFalse();
    assertThat(selected.fallbackReason()).isBlank();
  }

  @Test
  @DisplayName("RPI1-09 候选一方缺代码时按标准化完整名称兜底")
  void fallsBackToNameWhenCandidateCodeIsMissing() {
    Candidate supplierA = new Candidate("吉林省 合信汽配有限公司", null, "10");
    Candidate supplierB = new Candidate("其他供应商", "SUP-B", "20");
    when(resolveService.resolveAmongSuppliers(any(), any(), any(), any(), any(), any()))
        .thenReturn(hit("吉林省合信汽配有限公司", "SUP-A", "0.7"));

    SupplierPreferredPriceSelection<Candidate> selected = select(List.of(supplierB, supplierA));

    assertThat(selected.row()).isSameAs(supplierA);
    assertThat(selected.matchMode()).isEqualTo("NAME_FALLBACK");
    assertThat(selected.fallback()).isFalse();
  }

  @Test
  @DisplayName("RPI1-09 主供一方缺代码时按标准化完整名称兜底")
  void fallsBackToNameWhenMainSupplierCodeIsMissing() {
    Candidate supplierA = new Candidate("供应商 A", "SUP-A", "10");
    Candidate supplierB = new Candidate("供应商B", "SUP-B", "20");
    when(resolveService.resolveAmongSuppliers(any(), any(), any(), any(), any(), any()))
        .thenReturn(hit("供应商A", null, "0.7"));

    SupplierPreferredPriceSelection<Candidate> selected = select(List.of(supplierB, supplierA));

    assertThat(selected.row()).isSameAs(supplierA);
  }

  @Test
  @DisplayName("RPI1-09 双方代码不同即使名称相同也不匹配")
  void doesNotMatchSameNameWhenExplicitCodesDiffer() {
    Candidate fallback = new Candidate("同名供应商", "SUP-A", "10");
    Candidate other = new Candidate("其他供应商", "SUP-B", "20");
    when(resolveService.resolveAmongSuppliers(any(), any(), any(), any(), any(), any()))
        .thenReturn(hit("同名供应商", "SUP-C", "0.8"));

    SupplierPreferredPriceSelection<Candidate> selected = select(List.of(fallback, other));

    assertThat(selected.row()).isSameAs(fallback);
    assertThat(selected.traceMessage()).contains("主供应商无价格记录");
    assertThat(selected.mainSupplierCode()).isEqualTo("SUP-C");
    assertThat(selected.supplyRatio()).isEqualByComparingTo("0.8");
    assertThat(selected.fallback()).isTrue();
    assertThat(selected.fallbackReason()).isEqualTo("主供应商无价格记录");
    assertThat(selected.matchMode()).isEqualTo("DEFAULT_FALLBACK");
  }

  @Test
  @DisplayName("RPI1-13 候选供应商未命中后查询整体主供并记录主供无价格兜底")
  void candidateMissFallsBackToOverallMainSupplierLookup() {
    Candidate fallback = new Candidate("供应商A", "SUP-A", "10");
    Candidate other = new Candidate("供应商B", "SUP-B", "20");
    when(resolveService.resolveAmongSuppliers(any(), any(), any(), any(), any(), any()))
        .thenReturn(SupplierSupplyRatioResolveResult.miss("候选供应商无数据"));
    when(resolveService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(hit("供应商C", "SUP-C", "0.9"));

    SupplierPreferredPriceSelection<Candidate> selected = select(List.of(fallback, other));

    assertThat(selected.row()).isSameAs(fallback);
    assertThat(selected.mainSupplierCode()).isEqualTo("SUP-C");
    assertThat(selected.supplyRatio()).isEqualByComparingTo("0.9");
    assertThat(selected.fallback()).isTrue();
    assertThat(selected.fallbackReason()).isEqualTo("主供应商无价格记录");
  }

  @Test
  @DisplayName("RPI1-09 同名不同代码仍识别为多供应商并查询供货比例")
  void sameNameDifferentCodesStillTriggersSupplierResolution() {
    Candidate supplierA = new Candidate("同名供应商", "SUP-A", "10");
    Candidate supplierB = new Candidate("同名供应商", "SUP-B", "20");
    when(resolveService.resolveAmongSuppliers(any(), any(), any(), any(), any(), any()))
        .thenReturn(hit("同名供应商", "SUP-B", "0.7"));

    SupplierPreferredPriceSelection<Candidate> selected = select(List.of(supplierA, supplierB));

    assertThat(selected.row()).isSameAs(supplierB);
    verify(resolveService).resolveAmongSuppliers(any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("RPI1-09 单一供应商候选直接返回且不查询供货比例")
  void singleSupplierDoesNotQuerySupplyRatio() {
    Candidate only = new Candidate("供应商A", "SUP-A", "10");

    SupplierPreferredPriceSelection<Candidate> selected = select(List.of(only));

    assertThat(selected.row()).isSameAs(only);
    assertThat(selected.candidateSupplierCount()).isEqualTo(1);
    assertThat(selected.mainSupplierName()).isEqualTo("供应商A");
    assertThat(selected.mainSupplierCode()).isEqualTo("SUP-A");
    assertThat(selected.matchMode()).isEqualTo("SINGLE_SUPPLIER");
    assertThat(selected.fallback()).isFalse();
    verify(resolveService, never()).resolveAmongSuppliers(any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("RPI1-11 供货比例缺失时完整记录兜底原因")
  void recordsMissingRatioFallbackLedger() {
    Candidate supplierA = new Candidate("供应商A", "SUP-A", "10");
    Candidate supplierB = new Candidate("供应商B", "SUP-B", "20");
    when(resolveService.resolveAmongSuppliers(any(), any(), any(), any(), any(), any()))
        .thenReturn(SupplierSupplyRatioResolveResult.miss("未维护"));

    SupplierPreferredPriceSelection<Candidate> selected = select(List.of(supplierA, supplierB));

    assertThat(selected.row()).isSameAs(supplierA);
    assertThat(selected.candidateSupplierCount()).isEqualTo(2);
    assertThat(selected.fallback()).isTrue();
    assertThat(selected.fallbackReason()).isEqualTo("未维护主供应商供货比例");
    assertThat(selected.matchMode()).isEqualTo("DEFAULT_FALLBACK");
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
      String supplierName,
      String supplierCode,
      String ratio) {
    SupplierSupplyRatioResolveResult result = new SupplierSupplyRatioResolveResult();
    result.setMatched(true);
    result.setSupplierName(supplierName);
    result.setSupplierCode(supplierCode);
    result.setSupplyRatio(new BigDecimal(ratio));
    return result;
  }

  private record Candidate(String supplierName, String supplierCode, String price) {}
}
