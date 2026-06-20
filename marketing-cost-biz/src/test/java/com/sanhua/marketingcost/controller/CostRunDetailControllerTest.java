package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunDetailDto;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.CostRunCostItemService;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import com.sanhua.marketingcost.service.CostRunResultService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CostRunDetailControllerTest {

  @Test
  @DisplayName("传入 costRunNo 时，成本一览表只查询该成本批次明细")
  void detailUsesRequestedCostRunNo() {
    CostRunPartItemService partService = mock(CostRunPartItemService.class);
    CostRunCostItemService costService = mock(CostRunCostItemService.class);
    CostRunResultService resultService = mock(CostRunResultService.class);
    MaterialMasterMapper materialMasterMapper = mock(MaterialMasterMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    OaFormItemMapper oaFormItemMapper = mock(OaFormItemMapper.class);
    QuoteCostRunVersionMapper versionMapper = mock(QuoteCostRunVersionMapper.class);
    CostRunDetailController controller =
        new CostRunDetailController(
            partService,
            costService,
            resultService,
            materialMasterMapper,
            oaFormMapper,
            oaFormItemMapper,
            versionMapper);
    QuoteCostRunVersion requested = new QuoteCostRunVersion();
    requested.setCostRunNo("TRIAL-V1");
    requested.setVersionNo("COST-V1");
    when(versionMapper.selectOne(any(Wrapper.class))).thenReturn(requested);
    CostRunPartItemDto part = new CostRunPartItemDto();
    part.setId(501L);
    part.setPartCode("MAT-V1");
    CostRunCostItemDto total = new CostRunCostItemDto();
    total.setId(601L);
    total.setCostCode("TOTAL");
    total.setAmount(new BigDecimal("101.01"));
    when(partService.listAggregatedByCostRunNo("TRIAL-V1", "P-1")).thenReturn(List.of(part));
    when(costService.listStoredByCostRunNo("TRIAL-V1")).thenReturn(List.of(total));

    CommonResult<CostRunDetailDto> response = controller.getDetail("OA-1", "P-1", "TRIAL-V1");

    assertThat(response.getData().getCostRunNo()).isEqualTo("TRIAL-V1");
    assertThat(response.getData().getVersionNo()).isEqualTo("COST-V1");
    assertThat(response.getData().getPartItems()).extracting(CostRunPartItemDto::getPartCode)
        .containsExactly("MAT-V1");
    assertThat(response.getData().getPartItems()).extracting(CostRunPartItemDto::getId)
        .containsExactly(501L);
    assertThat(response.getData().getCostItems()).extracting(CostRunCostItemDto::getId)
        .containsExactly(601L);
    assertThat(response.getData().getTotal()).isEqualByComparingTo("101.01");
    verify(partService).listAggregatedByCostRunNo(eq("TRIAL-V1"), eq("P-1"));
    verify(costService).listStoredByCostRunNo(eq("TRIAL-V1"));
    verify(partService, never()).listAggregatedByOaNo(any(), any());
    verify(costService, never()).listStoredByOaNo(any(), any());
  }

  @Test
  @DisplayName("旧链接未传 costRunNo 时，默认解析最新版本并只查询该版本明细")
  void detailDefaultsToLatestCostRunVersion() {
    CostRunPartItemService partService = mock(CostRunPartItemService.class);
    CostRunCostItemService costService = mock(CostRunCostItemService.class);
    CostRunResultService resultService = mock(CostRunResultService.class);
    MaterialMasterMapper materialMasterMapper = mock(MaterialMasterMapper.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    OaFormItemMapper oaFormItemMapper = mock(OaFormItemMapper.class);
    QuoteCostRunVersionMapper versionMapper = mock(QuoteCostRunVersionMapper.class);
    CostRunDetailController controller =
        new CostRunDetailController(
            partService,
            costService,
            resultService,
            materialMasterMapper,
            oaFormMapper,
            oaFormItemMapper,
            versionMapper);
    QuoteCostRunVersion latest = new QuoteCostRunVersion();
    latest.setCostRunNo("TRIAL-LATEST");
    latest.setVersionNo("V3");
    when(versionMapper.selectOne(any(Wrapper.class))).thenReturn(latest);
    CostRunPartItemDto part = new CostRunPartItemDto();
    part.setPartCode("MAT-1");
    CostRunCostItemDto total = new CostRunCostItemDto();
    total.setCostCode("TOTAL");
    total.setAmount(new BigDecimal("12.34"));
    when(partService.listAggregatedByCostRunNo("TRIAL-LATEST", "P-1"))
        .thenReturn(List.of(part));
    when(costService.listStoredByCostRunNo("TRIAL-LATEST")).thenReturn(List.of(total));

    CommonResult<CostRunDetailDto> response = controller.getDetail("OA-1", "P-1", null);

    assertThat(response.getData().getCostRunNo()).isEqualTo("TRIAL-LATEST");
    assertThat(response.getData().getVersionNo()).isEqualTo("V3");
    assertThat(response.getData().getPartItems()).hasSize(1);
    assertThat(response.getData().getTotal()).isEqualByComparingTo("12.34");
    verify(partService).listAggregatedByCostRunNo(eq("TRIAL-LATEST"), eq("P-1"));
    verify(costService).listStoredByCostRunNo(eq("TRIAL-LATEST"));
    verify(partService, never()).listAggregatedByOaNo(any(), any());
    verify(costService, never()).listStoredByOaNo(any(), any());
  }
}
