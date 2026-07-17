package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.FinanceBasePriceImportRequest;
import com.sanhua.marketingcost.dto.FinanceBasePriceRequest;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FinanceBasePriceProtectedIdentityTest {

  private FinanceBasePriceMapper mapper;
  private FinanceBasePriceServiceImpl service;

  @BeforeEach
  void setUp() {
    mapper = org.mockito.Mockito.mock(FinanceBasePriceMapper.class);
    service = new FinanceBasePriceServiceImpl(mapper);
  }

  @Test
  @DisplayName("普通CRUD入口不能新增、修改或删除财务报价Cu基准")
  void genericCrudCannotMaintainProtectedRecord() {
    FinanceBasePriceRequest request = protectedRequest();
    assertThatThrownBy(() -> service.create(request))
        .hasMessageContaining("专用接口");
    verify(mapper, never()).insert(any(FinanceBasePrice.class));

    FinanceBasePrice protectedEntity = protectedEntity();
    when(mapper.selectById(7L)).thenReturn(protectedEntity);
    assertThatThrownBy(() -> service.update(7L, ordinaryRequest()))
        .hasMessageContaining("专用接口");
    assertThatThrownBy(() -> service.delete(7L))
        .hasMessageContaining("专用接口");
    verify(mapper, never()).updateById(any(FinanceBasePrice.class));
    verify(mapper, never()).deleteById(7L);
  }

  @Test
  @DisplayName("普通JSON批量入口不能冒用财务报价基准来源")
  void genericJsonImportCannotUseProtectedIdentity() {
    FinanceBasePriceImportRequest request = new FinanceBasePriceImportRequest();
    request.setPriceMonth("2026-07");
    FinanceBasePriceImportRequest.FinanceBasePriceImportRow row =
        new FinanceBasePriceImportRequest.FinanceBasePriceImportRow();
    row.setShortName("任意简称");
    row.setPriceSource("财务报价基准");
    row.setPrice(new BigDecimal("90"));
    request.setRows(List.of(row));

    assertThatThrownBy(() -> service.importPrices(request))
        .hasMessageContaining("专用接口");
    verify(mapper, never()).insert(any(FinanceBasePrice.class));
    verify(mapper, never()).updateById(any(FinanceBasePrice.class));
  }

  private FinanceBasePriceRequest protectedRequest() {
    FinanceBasePriceRequest request = ordinaryRequest();
    request.setShortName("报价Cu基准");
    request.setPriceSource("财务报价基准");
    return request;
  }

  private FinanceBasePriceRequest ordinaryRequest() {
    FinanceBasePriceRequest request = new FinanceBasePriceRequest();
    request.setPriceMonth("2026-07");
    request.setShortName("1#Cu");
    request.setFactorName("长江现货电解铜");
    request.setPriceSource("平均价");
    request.setPrice(new BigDecimal("90"));
    return request;
  }

  private FinanceBasePrice protectedEntity() {
    FinanceBasePrice entity = new FinanceBasePrice();
    entity.setId(7L);
    entity.setShortName("报价Cu基准");
    entity.setPriceSource("财务报价基准");
    return entity;
  }
}
