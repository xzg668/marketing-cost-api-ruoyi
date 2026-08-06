package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomQueryException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 将最终树查询错误稳定映射成前端可读结果。 */
@Component
public class QuoteEffectiveBomErrorMapper {

  private static final Set<String> CONFLICT_CODES =
      Set.of(
          "EFFECTIVE_BOM_FROZEN",
          "EFFECTIVE_BOM_FROZEN_INVALID",
          "EFFECTIVE_BOM_CONFIRM_CONFLICT",
          "EFFECTIVE_BOM_ALREADY_CONFIRMED",
          "EFFECTIVE_BOM_BUILD_MISMATCH");

  public <T> CommonResult<T> map(RuntimeException exception) {
    if (exception instanceof QuoteEffectiveBomQueryException business) {
      int status =
          "EFFECTIVE_BOM_NOT_FOUND".equals(business.getCode())
              ? 404
              : CONFLICT_CODES.contains(business.getCode()) ? 409 : 400;
      return CommonResult.error(status, readable(business.getCode(), business.getMessage()));
    }
    return CommonResult.error(400, readable("EFFECTIVE_BOM_INVALID_REQUEST", exception.getMessage()));
  }

  private static String readable(String code, String message) {
    String normalizedCode =
        StringUtils.hasText(code) ? code.trim() : "EFFECTIVE_BOM_INVALID_REQUEST";
    String normalizedMessage =
        StringUtils.hasText(message) ? message.trim() : "最终BOM请求参数不正确";
    return normalizedMessage.startsWith(normalizedCode + ":")
        || normalizedMessage.startsWith(normalizedCode + "：")
        ? normalizedMessage
        : normalizedCode + ": " + normalizedMessage;
  }
}
