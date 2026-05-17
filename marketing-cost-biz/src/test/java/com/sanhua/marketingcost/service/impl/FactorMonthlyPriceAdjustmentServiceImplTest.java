package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceAdjustRequest;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceAdjustmentResponse;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.FactorMonthlyPriceChangeLog;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceChangeLogMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FactorMonthlyPriceAdjustmentServiceImplTest {

  private FactorMonthlyPriceMapper monthlyPriceMapper;
  private FactorMonthlyPriceChangeLogMapper changeLogMapper;
  private FactorMonthlyPriceAdjustmentServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FactorMonthlyPrice.class);
    TableInfoHelper.initTableInfo(assistant, FactorMonthlyPriceChangeLog.class);
  }

  @BeforeEach
  void setUp() {
    monthlyPriceMapper = mock(FactorMonthlyPriceMapper.class);
    changeLogMapper = mock(FactorMonthlyPriceChangeLogMapper.class);
    service = new FactorMonthlyPriceAdjustmentServiceImpl(monthlyPriceMapper, changeLogMapper);
  }

  @Test
  @DisplayName("调价：只更新月度价格并写 MANUAL_ADJUST 日志")
  void adjustUpdatesMonthlyPriceAndWritesChangeLog() {
    FactorMonthlyPrice current = monthlyPrice(501L, 64L, "2026-05", "16.40");
    when(monthlyPriceMapper.selectById(501L)).thenReturn(current);

    FactorMonthlyPriceAdjustRequest request = new FactorMonthlyPriceAdjustRequest();
    request.setNewPrice(new BigDecimal("17.00"));
    request.setRemark("5月调价");

    FactorMonthlyPriceAdjustmentResponse response = service.adjust(501L, request, "alice");

    assertThat(response.getFactorMonthlyPriceId()).isEqualTo(501L);
    assertThat(response.getFactorIdentityId()).isEqualTo(64L);
    assertThat(response.getOldPrice()).isEqualByComparingTo("16.4");
    assertThat(response.getNewPrice()).isEqualByComparingTo("17.0");
    assertThat(response.getChangeType()).isEqualTo("MANUAL_ADJUST");
    assertThat(response.getChangedBy()).isEqualTo("alice");

    ArgumentCaptor<FactorMonthlyPrice> priceCaptor =
        ArgumentCaptor.forClass(FactorMonthlyPrice.class);
    verify(monthlyPriceMapper).updateById(priceCaptor.capture());
    assertThat(priceCaptor.getValue().getId()).isEqualTo(501L);
    assertThat(priceCaptor.getValue().getPrice()).isEqualByComparingTo("17.0");
    assertThat(priceCaptor.getValue().getUpdatedBy()).isEqualTo("alice");

    ArgumentCaptor<FactorMonthlyPriceChangeLog> logCaptor =
        ArgumentCaptor.forClass(FactorMonthlyPriceChangeLog.class);
    verify(changeLogMapper).insert(logCaptor.capture());
    FactorMonthlyPriceChangeLog log = logCaptor.getValue();
    assertThat(log.getFactorMonthlyPriceId()).isEqualTo(501L);
    assertThat(log.getFactorIdentityId()).isEqualTo(64L);
    assertThat(log.getPriceMonth()).isEqualTo("2026-05");
    assertThat(log.getOldPrice()).isEqualByComparingTo("16.4");
    assertThat(log.getNewPrice()).isEqualByComparingTo("17.0");
    assertThat(log.getChangeType()).isEqualTo("MANUAL_ADJUST");
    assertThat(log.getSourceUploadBatchId()).isNull();
    assertThat(log.getRemark()).isEqualTo("5月调价");
  }

  @Test
  @DisplayName("调价：同价保存只写 NO_CHANGE 日志，不更新主表")
  void samePriceWritesNoChangeLogWithoutUpdatingMonthlyPrice() {
    FactorMonthlyPrice current = monthlyPrice(502L, 64L, "2026-05", "16.4000");
    when(monthlyPriceMapper.selectById(502L)).thenReturn(current);

    FactorMonthlyPriceAdjustRequest request = new FactorMonthlyPriceAdjustRequest();
    request.setNewPrice(new BigDecimal("16.4"));
    request.setRemark("确认无需调价");

    FactorMonthlyPriceAdjustmentResponse response = service.adjust(502L, request, "alice");

    assertThat(response.getChangeType()).isEqualTo("NO_CHANGE");
    verify(monthlyPriceMapper, never()).updateById(any(FactorMonthlyPrice.class));
    ArgumentCaptor<FactorMonthlyPriceChangeLog> logCaptor =
        ArgumentCaptor.forClass(FactorMonthlyPriceChangeLog.class);
    verify(changeLogMapper).insert(logCaptor.capture());
    assertThat(logCaptor.getValue().getChangeType()).isEqualTo("NO_CHANGE");
  }

  @Test
  @DisplayName("调价：缺少月度价格或新价格时直接拒绝")
  void adjustRejectsInvalidInput() {
    FactorMonthlyPriceAdjustRequest request = new FactorMonthlyPriceAdjustRequest();
    request.setNewPrice(new BigDecimal("17.0"));
    when(monthlyPriceMapper.selectById(999L)).thenReturn(null);

    assertThatThrownBy(() -> service.adjust(999L, request, "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("影响因素月度价格不存在");

    assertThatThrownBy(() -> service.adjust(999L, new FactorMonthlyPriceAdjustRequest(), "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("newPrice 必填");
  }

  @Test
  @DisplayName("日志：按月度价格 ID 倒序查询")
  void listChangeLogsByMonthlyPriceId() {
    FactorMonthlyPriceChangeLog log = new FactorMonthlyPriceChangeLog();
    log.setId(9001L);
    log.setFactorMonthlyPriceId(501L);
    log.setFactorIdentityId(64L);
    log.setPriceMonth("2026-05");
    log.setOldPrice(new BigDecimal("16.4"));
    log.setNewPrice(new BigDecimal("17.0"));
    log.setChangeType("MANUAL_ADJUST");
    log.setChangedBy("alice");
    when(changeLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(log));

    var rows = service.listChangeLogs(501L);

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().getId()).isEqualTo(9001L);
    assertThat(rows.getFirst().getOldPrice()).isEqualByComparingTo("16.4");
    assertThat(rows.getFirst().getNewPrice()).isEqualByComparingTo("17.0");
  }

  private static FactorMonthlyPrice monthlyPrice(
      Long id, Long identityId, String priceMonth, String price) {
    FactorMonthlyPrice monthlyPrice = new FactorMonthlyPrice();
    monthlyPrice.setId(id);
    monthlyPrice.setFactorIdentityId(identityId);
    monthlyPrice.setPriceMonth(priceMonth);
    monthlyPrice.setPrice(new BigDecimal(price));
    monthlyPrice.setStatus("ACTIVE");
    return monthlyPrice;
  }
}
