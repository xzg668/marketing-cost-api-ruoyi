package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.FactorLinkedItemReferenceDto;
import com.sanhua.marketingcost.service.FactorLinkedItemReferenceService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FactorIdentityControllerTest {

  private FactorLinkedItemReferenceService referenceService;
  private FactorIdentityController controller;

  @BeforeEach
  void setUp() {
    referenceService = mock(FactorLinkedItemReferenceService.class);
    controller = new FactorIdentityController(referenceService);
  }

  @Test
  @DisplayName("/factor-identities/{id}/linked-items：透传月份和业务单元")
  void listLinkedItemsDelegatesToService() {
    FactorLinkedItemReferenceDto ref = new FactorLinkedItemReferenceDto();
    ref.setLinkedItemId(100L);
    ref.setPricingMonth("2026-05");
    ref.setBusinessUnitType("COMMERCIAL");
    when(referenceService.listLinkedItems(191L, "2026-05", "COMMERCIAL"))
        .thenReturn(List.of(ref));

    CommonResult<List<FactorLinkedItemReferenceDto>> result =
        controller.listLinkedItems(191L, "2026-05", "COMMERCIAL");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).hasSize(1);
    assertThat(result.getData().getFirst().getLinkedItemId()).isEqualTo(100L);
    verify(referenceService).listLinkedItems(191L, "2026-05", "COMMERCIAL");
  }

  @Test
  @DisplayName("/factor-identities/{id}/linked-items：参数非法返回 BAD_REQUEST")
  void listLinkedItemsBadRequest() {
    when(referenceService.listLinkedItems(null, "2026-05", "COMMERCIAL"))
        .thenThrow(new IllegalArgumentException("factorIdentityId 必填"));

    CommonResult<List<FactorLinkedItemReferenceDto>> result =
        controller.listLinkedItems(null, "2026-05", "COMMERCIAL");

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
  }
}
