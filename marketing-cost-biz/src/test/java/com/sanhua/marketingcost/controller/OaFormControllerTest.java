package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.OaFormListItemDto;
import com.sanhua.marketingcost.service.OaFormService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OaFormControllerTest {
  private OaFormService oaFormService;
  private OaFormController controller;

  @BeforeEach
  void setUp() {
    oaFormService = mock(OaFormService.class);
    controller = new OaFormController(oaFormService);
  }

  @Test
  void listStillDelegatesToOaFormService() {
    OaFormListItemDto row = new OaFormListItemDto();
    row.setOaNo("OA-GOLDEN-001");
    when(oaFormService.listForms("OA-GOLDEN", null, null, null, null, null))
        .thenReturn(List.of(row));

    CommonResult<List<OaFormListItemDto>> result =
        controller.list("OA-GOLDEN", null, null, null, null, null);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).hasSize(1);
    assertThat(result.getData().get(0).getOaNo()).isEqualTo("OA-GOLDEN-001");
    verify(oaFormService).listForms("OA-GOLDEN", null, null, null, null, null);
  }

  @Test
  void listKeepsDateFilters() {
    LocalDate start = LocalDate.of(2026, 5, 1);
    LocalDate end = LocalDate.of(2026, 5, 31);
    when(oaFormService.listForms(null, "FI-SC-006", "客户A", "未核算", start, end))
        .thenReturn(List.of());

    CommonResult<List<OaFormListItemDto>> result =
        controller.list(null, "FI-SC-006", "客户A", "未核算", start, end);

    assertThat(result.isSuccess()).isTrue();
    verify(oaFormService).listForms(null, "FI-SC-006", "客户A", "未核算", start, end);
  }
}
