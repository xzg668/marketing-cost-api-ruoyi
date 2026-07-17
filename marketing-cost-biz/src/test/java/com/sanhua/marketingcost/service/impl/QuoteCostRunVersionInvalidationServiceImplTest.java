package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.CostRunResultMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteCostRunVersionInvalidationServiceImplTest {

  private QuoteCostRunVersionMapper versionMapper;
  private CostRunResultMapper resultMapper;
  private QuoteCostRunVersionInvalidationServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, QuoteCostRunVersion.class);
    TableInfoHelper.initTableInfo(assistant, CostRunResult.class);
  }

  @BeforeEach
  void setUp() {
    versionMapper = mock(QuoteCostRunVersionMapper.class);
    resultMapper = mock(CostRunResultMapper.class);
    service = new QuoteCostRunVersionInvalidationServiceImpl(versionMapper, resultMapper);
  }

  @Test
  void financeCuInvalidatesOnlyMatchingTrialVersionsAndTheirResults() {
    when(versionMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(version(11L), version(12L)));
    when(versionMapper.update(any(QuoteCostRunVersion.class), any(Wrapper.class))).thenReturn(2);
    when(resultMapper.update(any(CostRunResult.class), any(Wrapper.class))).thenReturn(2);

    int affected = service.invalidateByFinanceCu(" 2026-09 ", " COMMERCIAL ");

    assertThat(affected).isEqualTo(2);
    ArgumentCaptor<Wrapper<QuoteCostRunVersion>> selectWrapper = wrapperCaptor();
    verify(versionMapper).selectList(selectWrapper.capture());
    AbstractWrapper<?, ?, ?> select = (AbstractWrapper<?, ?, ?>) selectWrapper.getValue();
    assertThat(select.getSqlSegment().toLowerCase())
        .contains("pricing_month", "business_unit_type", "status");
    assertThat(select.getParamNameValuePairs().values())
        .contains("2026-09", "COMMERCIAL", "TRIAL");

    ArgumentCaptor<QuoteCostRunVersion> versionPatch =
        ArgumentCaptor.forClass(QuoteCostRunVersion.class);
    ArgumentCaptor<Wrapper<QuoteCostRunVersion>> versionWrapper = wrapperCaptor();
    verify(versionMapper).update(versionPatch.capture(), versionWrapper.capture());
    assertThat(versionPatch.getValue().getStatus()).isEqualTo("STALE");
    AbstractWrapper<?, ?, ?> versionCondition =
        (AbstractWrapper<?, ?, ?>) versionWrapper.getValue();
    assertThat(versionCondition.getSqlSegment().toLowerCase()).contains("id", "status");
    assertThat(versionCondition.getParamNameValuePairs().values())
        .contains(11L, 12L, "TRIAL");

    ArgumentCaptor<CostRunResult> resultPatch = ArgumentCaptor.forClass(CostRunResult.class);
    verify(resultMapper).update(resultPatch.capture(), any(Wrapper.class));
    assertThat(resultPatch.getValue().getResultStatus()).isEqualTo("STALE");
  }

  @Test
  void noTrialCandidateLeavesVersionAndSnapshotRowsUntouched() {
    when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    int affected = service.invalidateProduct("OA-001", 10L, "TOP-001", "2026-09");

    assertThat(affected).isZero();
    verify(versionMapper, never()).update(any(QuoteCostRunVersion.class), any(Wrapper.class));
    verify(resultMapper, never()).update(any(CostRunResult.class), any(Wrapper.class));
  }

  @Test
  void priceTypeConfirmNumbersAreTrimmedAndDeduplicated() {
    when(versionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    service.invalidateByPriceTypeConfirmNos(
        List.of(" PTC-001 ", "PTC-001", "PTC-002", " "));

    ArgumentCaptor<Wrapper<QuoteCostRunVersion>> captor = wrapperCaptor();
    verify(versionMapper).selectList(captor.capture());
    AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
    wrapper.getSqlSegment();
    assertThat(wrapper.getParamNameValuePairs().values())
        .contains("PTC-001", "PTC-002", "TRIAL")
        .doesNotContain(" PTC-001 ");
  }

  private QuoteCostRunVersion version(Long id) {
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(id);
    version.setStatus("TRIAL");
    return version;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private <T> ArgumentCaptor<Wrapper<T>> wrapperCaptor() {
    return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
  }
}
