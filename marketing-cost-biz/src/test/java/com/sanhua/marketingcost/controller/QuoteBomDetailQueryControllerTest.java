package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingProductPageResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingRowPageResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomPackageStructurePageResponse;
import com.sanhua.marketingcost.service.QuoteBomDetailQueryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteBomDetailQueryControllerTest {

  private QuoteBomDetailQueryService service;
  private QuoteBomDetailQueryController controller;

  @BeforeEach
  void setUp() {
    service = mock(QuoteBomDetailQueryService.class);
    controller = new QuoteBomDetailQueryController(service);
  }

  @Test
  void packageStructuresDelegatesToQueryService() {
    QuoteBomPackageStructurePageResponse response =
        new QuoteBomPackageStructurePageResponse(
            "REF-001", "REF-001", "2026-05", null, true, 0, List.of(), List.of());
    when(service.pagePackageStructures("REF-001", "REF-001", "PKG-A", "2026-05", 1, 20))
        .thenReturn(response);

    CommonResult<QuoteBomPackageStructurePageResponse> result =
        controller.packageStructures("REF-001", "REF-001", "PKG-A", "2026-05", 1, 20);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().referenceFinishedCode()).isEqualTo("REF-001");
    verify(service).pagePackageStructures("REF-001", "REF-001", "PKG-A", "2026-05", 1, 20);
  }

  @Test
  void costingRowsDelegatesToQueryService() {
    QuoteBomCostingRowPageResponse response = new QuoteBomCostingRowPageResponse(1, List.of());
    when(service.pageCostingRows("OA-001", "FIN-001", "BOX-001", "2026-05", 1, 20))
        .thenReturn(response);

    CommonResult<QuoteBomCostingRowPageResponse> result =
        controller.costingRows("OA-001", "FIN-001", "BOX-001", "2026-05", 1, 20);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().total()).isEqualTo(1);
    verify(service).pageCostingRows("OA-001", "FIN-001", "BOX-001", "2026-05", 1, 20);
  }

  @Test
  void costingProductsDelegatesToQueryService() {
    QuoteBomCostingProductPageResponse response =
        new QuoteBomCostingProductPageResponse(1, List.of());
    when(service.pageCostingProducts("OA-001", "FIN-001", "BOX-001", "2026-05", 1, 20))
        .thenReturn(response);

    CommonResult<QuoteBomCostingProductPageResponse> result =
        controller.costingProducts("OA-001", "FIN-001", "BOX-001", "2026-05", 1, 20);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().total()).isEqualTo(1);
    verify(service).pageCostingProducts("OA-001", "FIN-001", "BOX-001", "2026-05", 1, 20);
  }
}
