package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.PriceSettleItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceSettle;
import com.sanhua.marketingcost.mapper.PriceSettleItemMapper;
import com.sanhua.marketingcost.mapper.PriceSettleMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PriceSettleServiceImplTest {

  @Test
  void formalSettleItemRetainsMaterialAndBusinessUnit() {
    PriceSettleMapper settleMapper = mock(PriceSettleMapper.class);
    PriceSettleItemMapper itemMapper = mock(PriceSettleItemMapper.class);

    PriceSettle settle = new PriceSettle();
    settle.setId(1L);
    settle.setBusinessUnitType("HOUSEHOLD");
    when(settleMapper.selectById(1L)).thenReturn(settle);
    PriceSettleItemUpdateRequest request = new PriceSettleItemUpdateRequest();
    request.setMaterialCode("MAT-SETTLE");
    request.setMaterialName("结算物料");
    request.setPlannedPrice(new BigDecimal("10.00"));

    var item = new PriceSettleServiceImpl(settleMapper, itemMapper).createItem(1L, request);
    assertThat(item.getMaterialCode()).isEqualTo("MAT-SETTLE");
    assertThat(item.getBusinessUnitType()).isEqualTo("HOUSEHOLD");
    verify(itemMapper).insert(item);

  }
}
