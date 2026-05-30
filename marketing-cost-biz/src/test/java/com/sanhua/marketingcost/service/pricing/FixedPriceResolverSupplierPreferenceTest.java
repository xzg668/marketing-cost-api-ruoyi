package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.service.SupplierSupplyRatioResolveService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FixedPriceResolverSupplierPreferenceTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceFixedItem.class);
  }

  @Test
  @DisplayName("固定价多供应商：按供货比例主供应商取价")
  void fixedPriceUsesMainSupplierWhenCandidateExists() {
    PriceFixedItemMapper mapper = mock(PriceFixedItemMapper.class);
    SupplierSupplyRatioResolveService ratioService = mock(SupplierSupplyRatioResolveService.class);
    FixedPriceResolver resolver = resolver(mapper, ratioService);
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(
            row(1L, "供应商A", "SA", "88.00"),
            row(2L, "供应商B", "SB", "66.00")));
    when(ratioService.resolve("COMMERCIAL", "MAT-1", "小阀座", "SHF-01", LocalDate.parse("2026-05-01")))
        .thenReturn(hit("供应商B", "SB", "0.75"));

    PriceResolveResult result = resolver.resolve("OA-1", item("MAT-1"), route());

    assertThat(result.unitPrice()).isEqualByComparingTo("66.00");
    assertThat(result.priceSource()).isEqualTo("固定采购价");
    assertThat(result.remark()).isEqualTo("按主供应商供货比例匹配价格");
  }

  @Test
  @DisplayName("主供命中但价格源无该供应商时：回退原固定价排序第一条")
  void fallsBackWhenMainSupplierHasNoPriceRow() {
    PriceFixedItemMapper mapper = mock(PriceFixedItemMapper.class);
    SupplierSupplyRatioResolveService ratioService = mock(SupplierSupplyRatioResolveService.class);
    FixedPriceResolver resolver = resolver(mapper, ratioService);
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(
            row(1L, "供应商A", "SA", "88.00"),
            row(2L, "供应商B", "SB", "66.00")));
    when(ratioService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(hit("供应商C", "SC", "0.80"));

    PriceResolveResult result = resolver.resolve("OA-1", item("MAT-1"), route());

    assertThat(result.unitPrice()).isEqualByComparingTo("88.00");
    assertThat(result.remark()).isEqualTo("主供应商无价格记录，按默认价格取价");
  }

  @Test
  @DisplayName("未维护供货比例时：不阻断取价，回退原固定价排序第一条")
  void fallsBackWhenRatioMissing() {
    PriceFixedItemMapper mapper = mock(PriceFixedItemMapper.class);
    SupplierSupplyRatioResolveService ratioService = mock(SupplierSupplyRatioResolveService.class);
    FixedPriceResolver resolver = resolver(mapper, ratioService);
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(
            row(1L, "供应商A", "SA", "88.00"),
            row(2L, "供应商B", "SB", "66.00")));
    when(ratioService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(SupplierSupplyRatioResolveResult.miss("未命中供货比例"));

    PriceResolveResult result = resolver.resolve("OA-1", item("MAT-1"), route());

    assertThat(result.unitPrice()).isEqualByComparingTo("88.00");
    assertThat(result.remark()).isEqualTo("未维护主供应商供货比例，按默认价格取价");
  }

  @Test
  @DisplayName("单供应商固定价：不查询供货比例，沿用原逻辑")
  void singleSupplierKeepsOriginalLogic() {
    PriceFixedItemMapper mapper = mock(PriceFixedItemMapper.class);
    SupplierSupplyRatioResolveService ratioService = mock(SupplierSupplyRatioResolveService.class);
    FixedPriceResolver resolver = resolver(mapper, ratioService);
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(row(1L, "供应商A", "SA", "88.00")));

    PriceResolveResult result = resolver.resolve("OA-1", item("MAT-1"), route());

    assertThat(result.unitPrice()).isEqualByComparingTo("88.00");
    assertThat(result.remark()).isEmpty();
    org.mockito.Mockito.verifyNoInteractions(ratioService);
    ArgumentCaptor<Wrapper<PriceFixedItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("material_code", "source_type", "fixed_price", "IS NOT NULL",
            "ORDER BY", "effective_from", "id", "DESC")
        .doesNotContain("LIMIT 1");
  }

  @Test
  @DisplayName("固定采购价路由：只查询 PURCHASE_FIXED/PURCHASE，避免取到结算固定价")
  void purchaseRouteQueriesPurchaseSourceTypesOnly() {
    PriceFixedItemMapper mapper = mock(PriceFixedItemMapper.class);
    SupplierSupplyRatioResolveService ratioService = mock(SupplierSupplyRatioResolveService.class);
    FixedPriceResolver resolver = resolver(mapper, ratioService);
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(row(1L, "供应商A", "SA", "88.00")));

    PriceResolveResult result = resolver.resolve("OA-1", item("MAT-1"), route("固定采购价"));

    assertThat(result.unitPrice()).isEqualByComparingTo("88.00");
    assertThat(result.priceSource()).isEqualTo("固定采购价");
    ArgumentCaptor<Wrapper<PriceFixedItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("material_code", "source_type", "IN", "fixed_price", "IS NOT NULL",
            "ORDER BY", "effective_from", "id");
  }

  @Test
  @DisplayName("结算固定价路由：只查询 SETTLE_FIXED/SETTLE，且不走供应商供货比例")
  void settleRouteQueriesSettleSourceTypesOnly() {
    PriceFixedItemMapper mapper = mock(PriceFixedItemMapper.class);
    SupplierSupplyRatioResolveService ratioService = mock(SupplierSupplyRatioResolveService.class);
    FixedPriceResolver resolver = resolver(mapper, ratioService);
    PriceFixedItem row = row(1L, null, null, "0.340314");
    row.setSourceType("SETTLE_FIXED");
    row.setSourceSystem("EXCEL");
    row.setPricingMonth("2026-05");
    row.setPlannedPrice(new BigDecimal("28.3595"));
    row.setMarkupRatio(new BigDecimal("1.2000"));
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));

    PriceResolveResult result = resolver.resolve("OA-1", item("MAT-1"), route("结算价"));

    assertThat(result.unitPrice()).isEqualByComparingTo("0.340314");
    assertThat(result.priceSource()).isEqualTo("结算固定价");
    assertThat(result.remark()).contains("来源系统=EXCEL", "结算期间=2026-05", "最后一列铜价/锌价列");
    org.mockito.Mockito.verifyNoInteractions(ratioService);
    ArgumentCaptor<Wrapper<PriceFixedItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("material_code", "source_type", "IN", "fixed_price", "IS NOT NULL",
            "effective_from", "effective_to", "ORDER BY", "pricing_month", "id");
  }

  @Test
  @DisplayName("结算固定价备注行：fixed_price 为空时不参与取价")
  void settleRemarkOnlyRowDoesNotResolve() {
    PriceFixedItemMapper mapper = mock(PriceFixedItemMapper.class);
    SupplierSupplyRatioResolveService ratioService = mock(SupplierSupplyRatioResolveService.class);
    FixedPriceResolver resolver = resolver(mapper, ratioService);
    PriceFixedItem row = row(1L, null, null, "0.00");
    row.setSourceType("SETTLE_FIXED");
    row.setSettleReferenceText("不用提供");
    row.setFixedPrice(null);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));

    PriceResolveResult result = resolver.resolve("OA-1", item("MAT-1"), route("家用结算价"));

    assertThat(result.unitPrice()).isNull();
    assertThat(result.remark()).contains("lp_price_fixed_item 无记录");
    org.mockito.Mockito.verifyNoInteractions(ratioService);
  }

  private FixedPriceResolver resolver(
      PriceFixedItemMapper mapper,
      SupplierSupplyRatioResolveService ratioService) {
    return new FixedPriceResolver(mapper, new SupplierPreferredPriceSelector(ratioService));
  }

  private SupplierSupplyRatioResolveResult hit(String supplierName, String supplierCode, String ratio) {
    SupplierSupplyRatioResolveResult result = new SupplierSupplyRatioResolveResult();
    result.setMatched(true);
    result.setSupplierName(supplierName);
    result.setSupplierCode(supplierCode);
    result.setSupplyRatio(new BigDecimal(ratio));
    result.setSourceType("EXCEL");
    result.setSourceBatchNo("SSR-batch");
    return result;
  }

  private PriceFixedItem row(Long id, String supplierName, String supplierCode, String price) {
    PriceFixedItem row = new PriceFixedItem();
    row.setId(id);
    row.setBusinessUnitType("COMMERCIAL");
    row.setSourceType("PURCHASE_FIXED");
    row.setMaterialCode("MAT-1");
    row.setMaterialName("小阀座");
    row.setSpecModel("SHF-01");
    row.setSupplierName(supplierName);
    row.setSupplierCode(supplierCode);
    row.setFixedPrice(new BigDecimal(price));
    row.setEffectiveFrom(LocalDate.parse("2026-05-01"));
    return row;
  }

  private CostRunPartItemDto item(String code) {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setPartCode(code);
    return item;
  }

  private com.sanhua.marketingcost.dto.PriceTypeRoute route() {
    return route("固定价");
  }

  private com.sanhua.marketingcost.dto.PriceTypeRoute route(String rawPriceType) {
    return new com.sanhua.marketingcost.dto.PriceTypeRoute(
        "MAT-1", null, com.sanhua.marketingcost.enums.PriceTypeEnum.FIXED,
        1, LocalDate.parse("2026-05-01"), null, "manual", rawPriceType);
  }
}
