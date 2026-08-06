package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuoteBomAlternativeErrorMappingTest {

  private final QuoteBomAlternativeErrorMapper mapper =
      new QuoteBomAlternativeErrorMapper();

  @Test
  void mapsEveryDesignedBusinessErrorToReadableStableResponse() {
    Map<String, Integer> expectedCodes =
        Map.of(
            "ALT_GROUP_NOT_FOUND", 404,
            "ALT_CANDIDATE_INVALID", 400,
            "ALT_STANDARD_MISSING", 400,
            "ALT_MULTIPLE_STANDARD", 400,
            "ALT_SELECTION_CONFLICT", 409,
            "ALT_MONTHLY_FROZEN", 409,
            "ALT_SOURCE_STALE", 409,
            "BOM_ALREADY_CONFIRMED", 409,
            "MANUAL_ROW_CHANGES_EXIST", 409,
            "ALT_BRANCH_STRUCTURE_MISSING", 400);

    expectedCodes.forEach(
        (businessCode, resultCode) -> {
          CommonResult<Object> result =
              mapper.map(
                  new QuoteBomAlternativeSelectionException(
                      businessCode, "父件P、子项10、标准STD、替代ALT，请刷新处理"));

          assertThat(result.getCode()).isEqualTo(resultCode);
          assertThat(result.getMsg())
              .startsWith(businessCode + ":")
              .contains("父件P")
              .contains("请刷新处理");
        });
  }

  @Test
  void doesNotDuplicateCodeAlreadyPresentInDomainMessage() {
    CommonResult<Object> result =
        mapper.map(
            new QuoteBomAlternativeSelectionException(
                "ALT_SOURCE_STALE",
                "ALT_SOURCE_STALE: BOM构建批次已变化"));

    assertThat(result.getMsg())
        .isEqualTo("ALT_SOURCE_STALE: BOM构建批次已变化");
  }

  @Test
  void mapsInvalidPeriodAndBlankGroupKeyToBadRequest() {
    CommonResult<Object> result =
        mapper.map(
            new IllegalArgumentException(
                "periodMonth必须为YYYY-MM"));

    assertThat(result.getCode()).isEqualTo(400);
    assertThat(result.getMsg())
        .isEqualTo("INVALID_REQUEST: periodMonth必须为YYYY-MM");
  }
}
