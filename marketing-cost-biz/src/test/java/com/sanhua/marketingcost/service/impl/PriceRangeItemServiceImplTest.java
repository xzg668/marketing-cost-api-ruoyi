package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.PriceRangeItemImportRequest;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("MFRP-02 区间价导入服务")
class PriceRangeItemServiceImplTest {

  private PriceRangeItemMapper itemMapper;

  private PriceRangeItemServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceRangeItem.class);
    TableInfoHelper.initTableInfo(assistant, PriceRangeFactorRule.class);
  }

  @BeforeEach
  void setUp() {
    itemMapper = mock(PriceRangeItemMapper.class);

    service =
        new PriceRangeItemServiceImpl(
            itemMapper,
            org.mockito.Mockito.mock(
                com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper.class));
  }

  @Test
  void pageReadsUnitsFromEachRuleWithoutChangingStoredBands() {
    var ruleMapper = mock(PriceRangeFactorRuleMapper.class);
    var service = new PriceRangeItemServiceImpl(itemMapper, ruleMapper);
    var kg = new PriceRangeItem();
    kg.setFactorRuleId(1L);
    var gram = new PriceRangeItem();
    gram.setFactorRuleId(2L);
    var qty = new PriceRangeItem();
    var page =
        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<PriceRangeItem>(1, 20);
    page.setRecords(List.of(kg, gram, qty));
    when(itemMapper.selectPage(any(), any())).thenReturn(page);
    var kgRule = new PriceRangeFactorRule();
    kgRule.setId(1L);
    kgRule.setFactorUnit("CNY_KG");
    var gramRule = new PriceRangeFactorRule();
    gramRule.setId(2L);
    gramRule.setFactorUnit("CNY_G");
    when(ruleMapper.selectList(any())).thenReturn(List.of(kgRule, gramRule));
    var result = service.page(null, null, null, null, 1, 20);
    assertThat(result.getRecords())
        .extracting(PriceRangeItem::getFactorUnit)
        .containsExactly("CNY_KG", "CNY_G", null);
    verify(ruleMapper).selectList(any());
    verify(itemMapper, never()).updateById(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("QTY 正式导入写区间价并按同一业务单元自动维护价格类型")
  void qtyImportStillWritesOnlyRangeItems() {
    when(itemMapper.selectOne(any())).thenReturn(null);
    when(itemMapper.selectList(any())).thenReturn(List.of());
    PriceRangeItemImportRequest request = new PriceRangeItemImportRequest();
    request.setBusinessUnitType("COMMERCIAL");
    request.setRows(List.of(row("MAT-QTY", "0", "10", "5.000000")));

    List<PriceRangeItem> imported = service.importItems(request);

    assertThat(imported).hasSize(1);
    ArgumentCaptor<PriceRangeItem> itemCaptor = ArgumentCaptor.forClass(PriceRangeItem.class);
    verify(itemMapper).insert(itemCaptor.capture());
    PriceRangeItem inserted = itemCaptor.getValue();
    assertThat(inserted.getMaterialCode()).isEqualTo("MAT-QTY");
    assertThat(inserted.getRangeBasis()).isEqualTo("QTY");
    assertThat(inserted.getFactorRuleId()).isNull();
    assertThat(inserted.getFactorCode()).isNull();
    assertThat(inserted.getCurrentFlag()).isOne();
    assertThat(inserted.getBusinessUnitType()).isEqualTo("COMMERCIAL");
  }

  @Test
  void marketJsonImportIsRejectedBeforeAnyDatabaseAccess() {
    var request = new PriceRangeItemImportRequest();
    request.setRangeBasis("FACTOR");
    request.setRows(List.of(row("MAT", "90000", "94999", "1")));
    assertThatThrownBy(() -> service.importItems(request)).hasMessageContaining("完整原稿");
    verifyNoInteractions(itemMapper);
  }

  @Test
  void rowCannotSmuggleMarketRangesIntoQuantityImport() {
    var request = new PriceRangeItemImportRequest();
    var row = row("MAT", "90000", "94999", "1");
    row.setRangeBasis("FACTOR");
    request.setRows(List.of(row));
    assertThatThrownBy(() -> service.importItems(request)).hasMessageContaining("完整原稿");
    verifyNoInteractions(itemMapper);
  }

  @Test
  void publishedMarketBandsCannotBeEditedOrDeleted() {
    var row = new PriceRangeItem();
    row.setId(1L);
    row.setRangeBasis("FACTOR");
    row.setFactorRuleId(2L);
    when(itemMapper.selectById(1L)).thenReturn(row);
    assertThatThrownBy(
            () ->
                service.update(1L, new com.sanhua.marketingcost.dto.PriceRangeItemUpdateRequest()))
        .hasMessageContaining("新原稿修订");
    assertThatThrownBy(() -> service.delete(1L)).hasMessageContaining("新原稿修订");
    verify(itemMapper, never()).updateById(any(PriceRangeItem.class));
    verify(itemMapper, never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
  }

  private static PriceRangeItemImportRequest.PriceRangeItemImportRow row(
      String material, String low, String high, String price) {
    var row = new PriceRangeItemImportRequest.PriceRangeItemImportRow();
    row.setMaterialCode(material);
    row.setRangeLow(new BigDecimal(low));
    row.setRangeHigh(new BigDecimal(high));
    row.setPriceExclTax(new BigDecimal(price));
    row.setEffectiveFrom(LocalDate.of(2026, 10, 1));
    return row;
  }
}
