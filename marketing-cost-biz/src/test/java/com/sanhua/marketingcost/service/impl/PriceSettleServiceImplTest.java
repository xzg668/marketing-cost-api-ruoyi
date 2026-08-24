package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.PriceSettleItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceSettle;
import com.sanhua.marketingcost.mapper.PriceSettleItemMapper;
import com.sanhua.marketingcost.mapper.PriceSettleMapper;
import com.sanhua.marketingcost.service.MaterialPriceTypeRouteSyncService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PriceSettleServiceImplTest {

  @Test
  void formalSettleItemSynchronizesSettleFixedType() {
    PriceSettleMapper settleMapper = mock(PriceSettleMapper.class);
    PriceSettleItemMapper itemMapper = mock(PriceSettleItemMapper.class);
    MaterialPriceTypeRouteSyncService routeSync =
        mock(MaterialPriceTypeRouteSyncService.class);
    PriceSettle settle = new PriceSettle();
    settle.setId(1L);
    settle.setBusinessUnitType("HOUSEHOLD");
    when(settleMapper.selectById(1L)).thenReturn(settle);
    PriceSettleItemUpdateRequest request = new PriceSettleItemUpdateRequest();
    request.setMaterialCode("MAT-SETTLE");
    request.setMaterialName("结算物料");
    request.setPlannedPrice(new BigDecimal("10.00"));

    new PriceSettleServiceImpl(settleMapper, itemMapper, routeSync).createItem(1L, request);

    ArgumentCaptor<MaterialPriceTypeRouteSyncService.RouteCommand> captor =
        ArgumentCaptor.forClass(MaterialPriceTypeRouteSyncService.RouteCommand.class);
    verify(routeSync).sync(captor.capture());
    assertThat(captor.getValue().materialCode()).isEqualTo("MAT-SETTLE");
    assertThat(captor.getValue().businessUnitType()).isEqualTo("HOUSEHOLD");
    assertThat(captor.getValue().priceType()).isEqualTo("结算固定价");
  }
}
