package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentGapPageResponse;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentGapQueryRequest;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentPricePageResponse;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentPriceQueryRequest;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentSnapshotPageResponse;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentSnapshotQueryRequest;
import com.sanhua.marketingcost.entity.PackageComponentGapItem;
import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.mapper.PackageComponentGapItemMapper;
import com.sanhua.marketingcost.mapper.PackageComponentPriceMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PackageComponentPriceQueryServiceImplTest {

  private PackageComponentPriceMapper priceMapper;
  private PackageComponentSnapshotMapper snapshotMapper;
  private PackageComponentGapItemMapper gapItemMapper;
  private PackageComponentPriceQueryServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PackageComponentPrice.class);
    TableInfoHelper.initTableInfo(assistant, PackageComponentSnapshot.class);
    TableInfoHelper.initTableInfo(assistant, PackageComponentGapItem.class);
  }

  @BeforeEach
  void setUp() {
    priceMapper = mock(PackageComponentPriceMapper.class);
    snapshotMapper = mock(PackageComponentSnapshotMapper.class);
    gapItemMapper = mock(PackageComponentGapItemMapper.class);
    service = new PackageComponentPriceQueryServiceImpl(priceMapper, snapshotMapper, gapItemMapper);
  }

  @Test
  @DisplayName("价格列表：分页参数归一化并带上价格过滤条件")
  void pagePricesBuildsPageAndFilters() {
    when(priceMapper.selectPage(any(), any())).thenAnswer(invocation -> {
      Page<PackageComponentPrice> page = invocation.getArgument(0);
      page.setTotal(1);
      page.setRecords(List.of(new PackageComponentPrice()));
      return page;
    });
    PackageComponentPriceQueryRequest request = new PackageComponentPriceQueryRequest();
    request.setPeriodMonth(" 2026-05 ");
    request.setPackageMaterialCode(" 9830000026238 ");
    request.setPriceStatus(" PRICED ");
    request.setPage(2);
    request.setPageSize(600);

    PackageComponentPricePageResponse response = service.pagePrices(request);

    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getRecords()).hasSize(1);
    ArgumentCaptor<Page<PackageComponentPrice>> pageCaptor = ArgumentCaptor.forClass(Page.class);
    ArgumentCaptor<LambdaQueryWrapper<PackageComponentPrice>> queryCaptor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(priceMapper).selectPage(pageCaptor.capture(), queryCaptor.capture());
    assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
    assertThat(pageCaptor.getValue().getSize()).isEqualTo(500);
    assertThat(((AbstractWrapper<?, ?, ?>) queryCaptor.getValue()).getSqlSegment())
        .contains("period_month", "package_material_code", "price_status", "ORDER BY");
    assertThat(queryCaptor.getValue().getParamNameValuePairs().values())
        .contains("2026-05", "9830000026238", "PRICED");
  }

  @Test
  @DisplayName("结构快照列表：默认分页并带上状态过滤条件")
  void pageSnapshotsBuildsDefaultPageAndFilters() {
    when(snapshotMapper.selectPage(any(), any())).thenAnswer(invocation -> {
      Page<PackageComponentSnapshot> page = invocation.getArgument(0);
      page.setTotal(1);
      page.setRecords(List.of(new PackageComponentSnapshot()));
      return page;
    });
    PackageComponentSnapshotQueryRequest request = new PackageComponentSnapshotQueryRequest();
    request.setPeriodMonth("2026-05");
    request.setPackageMaterialCode("9830000026238");
    request.setStatus("NORMAL");

    PackageComponentSnapshotPageResponse response = service.pageSnapshots(request);

    assertThat(response.getTotal()).isEqualTo(1);
    ArgumentCaptor<Page<PackageComponentSnapshot>> pageCaptor = ArgumentCaptor.forClass(Page.class);
    ArgumentCaptor<LambdaQueryWrapper<PackageComponentSnapshot>> queryCaptor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(snapshotMapper).selectPage(pageCaptor.capture(), queryCaptor.capture());
    assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
    assertThat(pageCaptor.getValue().getSize()).isEqualTo(20);
    assertThat(((AbstractWrapper<?, ?, ?>) queryCaptor.getValue()).getSqlSegment())
        .contains("period_month", "package_material_code", "status", "ORDER BY");
    assertThat(queryCaptor.getValue().getParamNameValuePairs().values())
        .contains("2026-05", "9830000026238", "NORMAL");
  }

  @Test
  @DisplayName("缺口清单：分页参数归一化并带上缺口过滤条件")
  void pageGapsBuildsPageAndFilters() {
    when(gapItemMapper.selectPage(any(), any())).thenAnswer(invocation -> {
      Page<PackageComponentGapItem> page = invocation.getArgument(0);
      page.setTotal(2);
      page.setRecords(List.of(new PackageComponentGapItem(), new PackageComponentGapItem()));
      return page;
    });
    PackageComponentGapQueryRequest request = new PackageComponentGapQueryRequest();
    request.setPeriodMonth("2026-05");
    request.setPackageMaterialCode("9830000026238");
    request.setGapType("MISSING_PRICE");
    request.setOaPushStatus("NOT_PUSHED");
    request.setPage(0);
    request.setPageSize(-1);

    PackageComponentGapPageResponse response = service.pageGaps(request);

    assertThat(response.getTotal()).isEqualTo(2);
    assertThat(response.getRecords()).hasSize(2);
    ArgumentCaptor<Page<PackageComponentGapItem>> pageCaptor = ArgumentCaptor.forClass(Page.class);
    ArgumentCaptor<LambdaQueryWrapper<PackageComponentGapItem>> queryCaptor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(gapItemMapper).selectPage(pageCaptor.capture(), queryCaptor.capture());
    assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
    assertThat(pageCaptor.getValue().getSize()).isEqualTo(20);
    assertThat(((AbstractWrapper<?, ?, ?>) queryCaptor.getValue()).getSqlSegment())
        .contains("period_month", "package_material_code", "gap_type", "oa_push_status", "ORDER BY");
    assertThat(queryCaptor.getValue().getParamNameValuePairs().values())
        .contains("2026-05", "9830000026238", "MISSING_PRICE", "NOT_PUSHED");
  }
}
