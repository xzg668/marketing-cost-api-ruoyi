package com.sanhua.marketingcost.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.BomManageItem;
import com.sanhua.marketingcost.mapper.BomManageItemMapper;
import com.sanhua.marketingcost.mapper.DynamicValueMapper;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PriceLinkedCalcServiceImplTest {

  @Test
  void page_returnsPagedRecords() {
    BomManageItemMapper mapper = Mockito.mock(BomManageItemMapper.class);
    PriceLinkedCalcItemMapper calcMapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    PriceLinkedItemMapper linkedItemMapper = Mockito.mock(PriceLinkedItemMapper.class);
    PriceVariableMapper variableMapper = Mockito.mock(PriceVariableMapper.class);
    OaFormMapper oaFormMapper = Mockito.mock(OaFormMapper.class);
    FinanceBasePriceMapper financeBasePriceMapper = Mockito.mock(FinanceBasePriceMapper.class);
    DynamicValueMapper dynamicValueMapper = Mockito.mock(DynamicValueMapper.class);
    PriceLinkedCalcServiceImpl service =
        new PriceLinkedCalcServiceImpl(
            mapper,
            calcMapper,
            linkedItemMapper,
            variableMapper,
            oaFormMapper,
            financeBasePriceMapper,
            dynamicValueMapper);

    BomManageItem record = new BomManageItem();
    record.setOaNo("OA-001");
    record.setItemCode("MAT-1");

    Page<BomManageItem> page = new Page<>(1, 20);
    page.setRecords(List.of(record));
    page.setTotal(1);

    when(mapper.selectPage(any(), any())).thenReturn(page);
    when(calcMapper.selectList(any())).thenReturn(List.of());

    var result = service.page("OA-001", null, null, 1, 20);

    assertEquals(1, result.getTotal());
    assertEquals("OA-001", result.getRecords().get(0).getOaNo());
  }

  @Test
  void refresh_insertsCalculatedItems() {
    BomManageItemMapper mapper = Mockito.mock(BomManageItemMapper.class);
    PriceLinkedCalcItemMapper calcMapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    PriceLinkedItemMapper linkedItemMapper = Mockito.mock(PriceLinkedItemMapper.class);
    PriceVariableMapper variableMapper = Mockito.mock(PriceVariableMapper.class);
    OaFormMapper oaFormMapper = Mockito.mock(OaFormMapper.class);
    FinanceBasePriceMapper financeBasePriceMapper = Mockito.mock(FinanceBasePriceMapper.class);
    DynamicValueMapper dynamicValueMapper = Mockito.mock(DynamicValueMapper.class);
    PriceLinkedCalcServiceImpl service =
        new PriceLinkedCalcServiceImpl(
            mapper,
            calcMapper,
            linkedItemMapper,
            variableMapper,
            oaFormMapper,
            financeBasePriceMapper,
            dynamicValueMapper);

    BomManageItem item = new BomManageItem();
    item.setOaNo("OA-001");
    item.setItemCode("MAT-1");
    item.setShapeAttr("制造件");
    item.setBomQty(new BigDecimal("2.5"));

    when(mapper.selectList(any())).thenReturn(List.of(item));
    when(calcMapper.selectList(any())).thenReturn(List.of());
    when(linkedItemMapper.selectList(any())).thenReturn(List.of());
    when(variableMapper.selectList(any())).thenReturn(List.of());
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(financeBasePriceMapper.selectList(any())).thenReturn(List.of());

    int inserted = service.refresh("OA-001");

    assertEquals(1, inserted);
    ArgumentCaptor<com.sanhua.marketingcost.entity.PriceLinkedCalcItem> captor =
        ArgumentCaptor.forClass(com.sanhua.marketingcost.entity.PriceLinkedCalcItem.class);
    Mockito.verify(calcMapper).insert(captor.capture());
    var saved = captor.getValue();
    assertEquals(new BigDecimal("2.5"), saved.getBomQty());
    assertEquals(null, saved.getPartUnitPrice());
    assertEquals(null, saved.getPartAmount());
  }
}
