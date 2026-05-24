package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MakePartProcessTypeResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MakePartProcessTypePolicy {
  public static final String PROCESS_TYPE_BLANK = "毛坯加工";
  public static final String PROCESS_TYPE_RAW = "原材料加工";

  /**
   * 判断制造件直接子项的料件类型。
   *
   * <p>当前业务规则：stock_unit = '只' 为毛坯加工，其他非空单位都按原材料加工。
   * 该规则后续可能调整，所以集中封装在这里，避免散落硬编码。
   */
  public MakePartProcessTypeResult resolve(String stockUnit) {
    if (!StringUtils.hasText(stockUnit)) {
      return MakePartProcessTypeResult.of(
          PROCESS_TYPE_RAW,
          "MISSING_STOCK_UNIT",
          "stock_unit 为空，无法确认料件类型，暂按原材料加工输出异常状态");
    }
    String normalized = stockUnit.trim();
    if ("只".equals(normalized)) {
      return MakePartProcessTypeResult.of(PROCESS_TYPE_BLANK, "OK", "");
    }
    return MakePartProcessTypeResult.of(PROCESS_TYPE_RAW, "OK", "");
  }
}
