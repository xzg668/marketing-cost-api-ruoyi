package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PackagePriceDetailResult;
import com.sanhua.marketingcost.dto.PackagePriceRequest;
import com.sanhua.marketingcost.dto.PackagePriceResult;
import com.sanhua.marketingcost.dto.PackageSnapshotDetailResult;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentGapPageResponse;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentGapQueryRequest;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentPricePageResponse;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentPriceQueryRequest;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentSnapshotPageResponse;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentSnapshotQueryRequest;
import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.service.PackageComponentPriceQueryService;
import com.sanhua.marketingcost.service.PackageComponentPriceService;
import com.sanhua.marketingcost.service.PackageComponentSnapshotService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PackageComponentPriceControllerTest {

  private PackageComponentPriceQueryService queryService;
  private PackageComponentPriceService priceService;
  private PackageComponentSnapshotService snapshotService;
  private PackageComponentPriceController controller;

  @BeforeEach
  void setUp() {
    queryService = mock(PackageComponentPriceQueryService.class);
    priceService = mock(PackageComponentPriceService.class);
    snapshotService = mock(PackageComponentSnapshotService.class);
    controller = new PackageComponentPriceController(queryService, priceService, snapshotService);
  }

  @Test
  @DisplayName("/package-components/prices：透传价格列表过滤条件")
  void listPricesDelegatesQuery() {
    PackageComponentPricePageResponse mocked =
        new PackageComponentPricePageResponse(1, List.of(new PackageComponentPrice()));
    when(queryService.pagePrices(any())).thenReturn(mocked);

    CommonResult<PackageComponentPricePageResponse> result =
        controller.listPrices(
            "2026-05", "9830000026238", "1079900000536", "MISSING_CHILD_PRICE", 2, 50);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isSameAs(mocked);
    ArgumentCaptor<PackageComponentPriceQueryRequest> captor =
        ArgumentCaptor.forClass(PackageComponentPriceQueryRequest.class);
    verify(queryService).pagePrices(captor.capture());
    assertThat(captor.getValue().getPeriodMonth()).isEqualTo("2026-05");
    assertThat(captor.getValue().getPackageMaterialCode()).isEqualTo("9830000026238");
    assertThat(captor.getValue().getTopProductCode()).isEqualTo("1079900000536");
    assertThat(captor.getValue().getPriceStatus()).isEqualTo("MISSING_CHILD_PRICE");
    assertThat(captor.getValue().getPage()).isEqualTo(2);
    assertThat(captor.getValue().getPageSize()).isEqualTo(50);
  }

  @Test
  @DisplayName("/package-components/prices/{id}/details：存在时返回价格和明细")
  void priceDetailsReturnsDetail() {
    PackagePriceDetailResult detail = new PackagePriceDetailResult();
    detail.setPrice(new PackageComponentPrice());
    when(priceService.getPriceDetail(10L)).thenReturn(detail);

    CommonResult<PackagePriceDetailResult> result = controller.priceDetails(10L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isSameAs(detail);
  }

  @Test
  @DisplayName("/package-components/prices/{id}/details：不存在时返回业务错误")
  void priceDetailsNotFound() {
    when(priceService.getPriceDetail(404L)).thenReturn(new PackagePriceDetailResult());

    CommonResult<PackagePriceDetailResult> result = controller.priceDetails(404L);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getMsg()).contains("not found");
  }

  @Test
  @DisplayName("/package-components/prices/generate：生成价格复用包装取价服务")
  void generatePriceDelegatesService() {
    PackagePriceResult mocked = new PackagePriceResult();
    PackagePriceRequest request = new PackagePriceRequest();
    request.setPackageMaterialCode("9830000026238");
    request.setPeriodMonth("2026-05");
    when(priceService.ensurePrice(request)).thenReturn(mocked);

    CommonResult<PackagePriceResult> result = controller.generatePrice(request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isSameAs(mocked);
    verify(priceService).ensurePrice(request);
  }

  @Test
  @DisplayName("/package-components/prices/generate：缺包装父料号直接返回参数错误")
  void generatePriceRequiresPackageCode() {
    PackagePriceRequest request = new PackagePriceRequest();
    request.setPeriodMonth("2026-05");

    CommonResult<PackagePriceResult> result = controller.generatePrice(request);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getMsg()).contains("packageMaterialCode");
  }

  @Test
  @DisplayName("/package-components/snapshots：透传结构快照查询条件")
  void listSnapshotsDelegatesQuery() {
    PackageComponentSnapshotPageResponse mocked =
        new PackageComponentSnapshotPageResponse(1, List.of(new PackageComponentSnapshot()));
    when(queryService.pageSnapshots(any())).thenReturn(mocked);

    CommonResult<PackageComponentSnapshotPageResponse> result =
        controller.listSnapshots("2026-05", "9830000026238", "NORMAL", 3, 40);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isSameAs(mocked);
    ArgumentCaptor<PackageComponentSnapshotQueryRequest> captor =
        ArgumentCaptor.forClass(PackageComponentSnapshotQueryRequest.class);
    verify(queryService).pageSnapshots(captor.capture());
    assertThat(captor.getValue().getPeriodMonth()).isEqualTo("2026-05");
    assertThat(captor.getValue().getPackageMaterialCode()).isEqualTo("9830000026238");
    assertThat(captor.getValue().getStatus()).isEqualTo("NORMAL");
    assertThat(captor.getValue().getPage()).isEqualTo(3);
    assertThat(captor.getValue().getPageSize()).isEqualTo(40);
  }

  @Test
  @DisplayName("/package-components/snapshots/{id}/details：不存在时返回业务错误")
  void snapshotDetailsNotFound() {
    when(snapshotService.getSnapshotDetail(404L)).thenReturn(new PackageSnapshotDetailResult());

    CommonResult<PackageSnapshotDetailResult> result = controller.snapshotDetails(404L);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getMsg()).contains("not found");
  }

  @Test
  @DisplayName("/package-components/gaps：透传缺口清单过滤条件")
  void listGapsDelegatesQuery() {
    PackageComponentGapPageResponse mocked = new PackageComponentGapPageResponse(0, List.of());
    when(queryService.pageGaps(any())).thenReturn(mocked);

    CommonResult<PackageComponentGapPageResponse> result =
        controller.listGaps("2026-05", "9830000026238", "MISSING_PRICE", "NOT_PUSHED", 1, 20);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isSameAs(mocked);
    ArgumentCaptor<PackageComponentGapQueryRequest> captor =
        ArgumentCaptor.forClass(PackageComponentGapQueryRequest.class);
    verify(queryService).pageGaps(captor.capture());
    assertThat(captor.getValue().getPeriodMonth()).isEqualTo("2026-05");
    assertThat(captor.getValue().getPackageMaterialCode()).isEqualTo("9830000026238");
    assertThat(captor.getValue().getGapType()).isEqualTo("MISSING_PRICE");
    assertThat(captor.getValue().getOaPushStatus()).isEqualTo("NOT_PUSHED");
  }
}
