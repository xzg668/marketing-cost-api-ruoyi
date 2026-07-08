package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.PriceRangeItemImportRequest;
import com.sanhua.marketingcost.dto.PriceRangeItemImportResult;
import com.sanhua.marketingcost.dto.RangePriceTypeConflict;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import com.sanhua.marketingcost.service.MaterialPriceTypeService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

@DisplayName("MFRP-02 区间价导入服务")
class PriceRangeItemServiceImplTest {

  private PriceRangeItemMapper itemMapper;
  private PriceRangeFactorRuleMapper factorRuleMapper;
  private PriceRangeItemServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceRangeItem.class);
    TableInfoHelper.initTableInfo(assistant, PriceRangeFactorRule.class);
  }

  @BeforeEach
  void setUp() {
    itemMapper = mock(PriceRangeItemMapper.class);
    factorRuleMapper = mock(PriceRangeFactorRuleMapper.class);
    service = new PriceRangeItemServiceImpl(itemMapper, factorRuleMapper);
  }

  @Test
  @DisplayName("QTY 导入保持旧逻辑，只写 lp_price_range_item")
  void qtyImportStillWritesOnlyRangeItems() {
    when(itemMapper.selectOne(any())).thenReturn(null);
    when(itemMapper.selectList(any())).thenReturn(List.of());
    PriceRangeItemImportRequest request = new PriceRangeItemImportRequest();
    request.setRows(List.of(row("MAT-QTY", "0", "10", "5.000000")));

    List<PriceRangeItem> imported = service.importItems(request);

    assertThat(imported).hasSize(1);
    verifyNoInteractions(factorRuleMapper);
    ArgumentCaptor<PriceRangeItem> itemCaptor = ArgumentCaptor.forClass(PriceRangeItem.class);
    verify(itemMapper).insert(itemCaptor.capture());
    PriceRangeItem inserted = itemCaptor.getValue();
    assertThat(inserted.getMaterialCode()).isEqualTo("MAT-QTY");
    assertThat(inserted.getRangeBasis()).isEqualTo("QTY");
    assertThat(inserted.getFactorRuleId()).isNull();
    assertThat(inserted.getFactorCode()).isNull();
    assertThat(inserted.getCurrentFlag()).isOne();
  }

  @Test
  @DisplayName("FACTOR 导入插入 1 条规则和多条区间明细")
  void factorImportCreatesRuleAndItems() {
    when(factorRuleMapper.selectList(any())).thenReturn(List.of());
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class))).thenAnswer(invocation -> {
      PriceRangeFactorRule rule = invocation.getArgument(0);
      rule.setId(101L);
      return 1;
    });
    PriceRangeItemImportRequest request = factorRequest(
        "CU",
        row("201850160", "87501", "92500", "0.392035"),
        row("201850160", "92501", "97500", "0.412035"));

    List<PriceRangeItem> imported = service.importItems(request);

    assertThat(imported).hasSize(2);
    ArgumentCaptor<PriceRangeFactorRule> ruleCaptor =
        ArgumentCaptor.forClass(PriceRangeFactorRule.class);
    verify(factorRuleMapper).insert(ruleCaptor.capture());
    PriceRangeFactorRule rule = ruleCaptor.getValue();
    assertThat(rule.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(rule.getMaterialCode()).isEqualTo("201850160");
    assertThat(rule.getFactorCode()).isEqualTo("CU");
    assertThat(rule.getFactorName()).isEqualTo("电解铜");
    assertThat(rule.getVersionNo()).isOne();
    assertThat(rule.getImportBatchNo()).isEqualTo("BATCH-CU-001");
    assertThat(rule.getSourceSheet()).isEqualTo("区间铜价");
    assertThat(rule.getCurrentFlag()).isOne();

    ArgumentCaptor<PriceRangeItem> itemCaptor = ArgumentCaptor.forClass(PriceRangeItem.class);
    verify(itemMapper, times(2)).insert(itemCaptor.capture());
    assertThat(itemCaptor.getAllValues())
        .allSatisfy(item -> {
          assertThat(item.getBusinessUnitType()).isEqualTo("COMMERCIAL");
          assertThat(item.getRangeBasis()).isEqualTo("FACTOR");
          assertThat(item.getFactorRuleId()).isEqualTo(101L);
          assertThat(item.getFactorCode()).isEqualTo("CU");
          assertThat(item.getImportBatchNo()).isEqualTo("BATCH-CU-001");
          assertThat(item.getCurrentFlag()).isOne();
          assertThat(item.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 7, 1));
        });
  }

  @Test
  @DisplayName("FACTOR 再次导入同一物料时旧规则和旧明细失效")
  void factorReimportExpiresOldRuleAndItems() {
    PriceRangeFactorRule oldRule = oldRule(10L, "CU", 1);
    PriceRangeItem oldItem = oldItem(100L, 10L);
    when(factorRuleMapper.selectList(any())).thenReturn(List.of(oldRule));
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class))).thenAnswer(invocation -> {
      PriceRangeFactorRule rule = invocation.getArgument(0);
      rule.setId(11L);
      return 1;
    });
    when(itemMapper.selectList(any())).thenReturn(List.of(oldItem));
    PriceRangeItemImportRequest request = factorRequest(
        "ZN",
        row("201850160", "20000", "25000", "0.512000"));

    service.importItems(request);

    assertThat(oldRule.getCurrentFlag()).isZero();
    assertThat(oldRule.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 7, 1));
    assertThat(oldItem.getCurrentFlag()).isZero();
    assertThat(oldItem.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 7, 1));
    verify(factorRuleMapper).updateById(oldRule);
    verify(itemMapper).updateById(oldItem);

    InOrder order = inOrder(factorRuleMapper, itemMapper);
    order.verify(factorRuleMapper).insert(any(PriceRangeFactorRule.class));
    order.verify(itemMapper).insert(any(PriceRangeItem.class));
    order.verify(factorRuleMapper).updateById(oldRule);
  }

  @Test
  @DisplayName("同一物料同一批次存在多个 factorCode 时整批失败")
  void factorImportRejectsMultipleFactorCodesForSameMaterial() {
    PriceRangeItemImportRequest.PriceRangeItemImportRow cu =
        row("201850160", "87501", "92500", "0.392035");
    cu.setFactorCode("CU");
    PriceRangeItemImportRequest.PriceRangeItemImportRow zn =
        row("201850160", "92501", "97500", "0.412035");
    zn.setFactorCode("ZN");
    PriceRangeItemImportRequest request = factorRequest("CU", cu, zn);

    assertThatThrownBy(() -> service.importItems(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("多个 factorCode");
    verifyNoInteractions(factorRuleMapper);
    verify(itemMapper, never()).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("同一物料同一批次区间重叠时整批失败")
  void factorImportRejectsOverlappingRanges() {
    PriceRangeItemImportRequest request = factorRequest(
        "CU",
        row("201850160", "87501", "92500", "0.392035"),
        row("201850160", "92400", "97500", "0.412035"));

    assertThatThrownBy(() -> service.importItems(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("区间重叠");
    verifyNoInteractions(factorRuleMapper);
    verify(itemMapper, never()).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("FACTOR 导入价格为空时整批失败")
  void factorImportRejectsMissingPrice() {
    PriceRangeItemImportRequest.PriceRangeItemImportRow row =
        row("201850160", "87501", "92500", null);
    PriceRangeItemImportRequest request = factorRequest("CU", row);

    assertThatThrownBy(() -> service.importItems(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("价格为空");
    verifyNoInteractions(factorRuleMapper);
    verify(itemMapper, never()).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("新规则插入失败时旧版本不会被提前失效")
  void factorRuleInsertFailureDoesNotExpireOldVersion() {
    PriceRangeFactorRule oldRule = oldRule(10L, "CU", 1);
    when(factorRuleMapper.selectList(any())).thenReturn(List.of(oldRule));
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class)))
        .thenThrow(new IllegalStateException("insert failed"));
    PriceRangeItemImportRequest request = factorRequest(
        "CU",
        row("201850160", "87501", "92500", "0.392035"));

    assertThatThrownBy(() -> service.importItems(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("insert failed");

    assertThat(oldRule.getCurrentFlag()).isOne();
    assertThat(oldRule.getEffectiveTo()).isNull();
    verify(factorRuleMapper, never()).updateById(any(PriceRangeFactorRule.class));
    verify(itemMapper, never()).updateById(any(PriceRangeItem.class));
    verify(itemMapper, never()).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("FACTOR 导入结果返回价格类型冲突清单")
  void factorImportWithResultReturnsPriceTypeConflicts() {
    MaterialPriceTypeService materialPriceTypeService = mock(MaterialPriceTypeService.class);
    PriceRangeItemServiceImpl serviceWithPriceType =
        new PriceRangeItemServiceImpl(itemMapper, factorRuleMapper, materialPriceTypeService);
    when(factorRuleMapper.selectList(any())).thenReturn(List.of());
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class))).thenAnswer(invocation -> {
      PriceRangeFactorRule rule = invocation.getArgument(0);
      rule.setId(101L);
      return 1;
    });
    RangePriceTypeConflict conflict = new RangePriceTypeConflict();
    conflict.setMaterialCode("201850160");
    conflict.setCurrentPriceType("固定价");
    conflict.setSuggestedPriceType("区间价");
    when(materialPriceTypeService.findRangePriceTypeConflicts(any())).thenReturn(List.of(conflict));

    PriceRangeItemImportResult result = serviceWithPriceType.importItemsWithResult(
        factorRequest("CU", row("201850160", "87501", "92500", "0.392035")));

    assertThat(result.getItems()).hasSize(1);
    assertThat(result.getPriceTypeConflicts()).hasSize(1);
    assertThat(result.getPriceTypeConflicts().get(0).getCurrentPriceType()).isEqualTo("固定价");
    verify(materialPriceTypeService).findRangePriceTypeConflicts(any());
  }

  private static PriceRangeItemImportRequest factorRequest(
      String factorCode,
      PriceRangeItemImportRequest.PriceRangeItemImportRow... rows) {
    PriceRangeItemImportRequest request = new PriceRangeItemImportRequest();
    request.setBusinessUnitType("COMMERCIAL");
    request.setRangeBasis("FACTOR");
    request.setFactorCode(factorCode);
    request.setFactorName("电解铜");
    request.setFactorUnit("元/吨");
    request.setPriceUnit("元/米");
    request.setSourceFile("range-price.xlsx");
    request.setSourceSheet("区间铜价");
    request.setImportBatchNo("BATCH-CU-001");
    request.setRows(List.of(rows));
    return request;
  }

  private static PriceRangeItemImportRequest.PriceRangeItemImportRow row(
      String materialCode,
      String rangeLow,
      String rangeHigh,
      String priceExclTax) {
    PriceRangeItemImportRequest.PriceRangeItemImportRow row =
        new PriceRangeItemImportRequest.PriceRangeItemImportRow();
    row.setMaterialCode(materialCode);
    row.setMaterialName("测试物料");
    row.setSpecModel("SPEC");
    row.setRangeLow(new BigDecimal(rangeLow));
    row.setRangeHigh(new BigDecimal(rangeHigh));
    if (priceExclTax != null) {
      row.setPriceExclTax(new BigDecimal(priceExclTax));
    }
    row.setEffectiveFrom(LocalDate.of(2026, 7, 1));
    return row;
  }

  private static PriceRangeFactorRule oldRule(Long id, String factorCode, int versionNo) {
    PriceRangeFactorRule rule = new PriceRangeFactorRule();
    rule.setId(id);
    rule.setBusinessUnitType("COMMERCIAL");
    rule.setMaterialCode("201850160");
    rule.setFactorCode(factorCode);
    rule.setVersionNo(versionNo);
    rule.setCurrentFlag(1);
    return rule;
  }

  private static PriceRangeItem oldItem(Long id, Long factorRuleId) {
    PriceRangeItem item = new PriceRangeItem();
    item.setId(id);
    item.setRangeBasis("FACTOR");
    item.setFactorRuleId(factorRuleId);
    item.setCurrentFlag(1);
    return item;
  }
}
