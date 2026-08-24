package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 将替代选择领域错误稳定映射为前端可识别且业务可读的 CommonResult。 */
@Component
public class QuoteBomAlternativeErrorMapper {

  private static final Set<String> CONFLICT_CODES =
      Set.of(
          "ALT_SELECTION_CONFLICT",
          "ALT_SELECTION_DISABLED",
          "ALT_SOURCE_STALE");

  public <T> CommonResult<T> map(RuntimeException exception) {
    if (exception instanceof QuoteBomAlternativeSelectionException business) {
      String businessCode = business.getCode();
      int resultCode =
          "ALT_GROUP_NOT_FOUND".equals(businessCode)
              ? 404
              : CONFLICT_CODES.contains(businessCode) ? 409 : 400;
      return CommonResult.error(
          resultCode,
          readableMessage(businessCode, business.getMessage()));
    }
    return CommonResult.error(
        400,
        readableMessage("INVALID_REQUEST", exception.getMessage()));
  }

  private static String readableMessage(String code, String message) {
    String normalizedCode =
        StringUtils.hasText(code) ? code.trim() : "INVALID_REQUEST";
    String normalizedMessage =
        StringUtils.hasText(message) ? message.trim() : "请求参数不正确";
    if (normalizedMessage.startsWith(normalizedCode + ":")
        || normalizedMessage.startsWith(normalizedCode + "：")) {
      return normalizedMessage;
    }
    return normalizedCode + ": " + normalizedMessage;
  }
}
