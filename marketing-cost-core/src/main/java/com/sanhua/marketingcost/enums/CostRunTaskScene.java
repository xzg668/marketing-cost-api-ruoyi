package com.sanhua.marketingcost.enums;

import org.springframework.util.StringUtils;

/** 通用成本核算任务场景。 */
public enum CostRunTaskScene {
  QUOTE,
  MONTHLY_REPRICE;

  public static CostRunTaskScene fromCode(String code) {
    if (!StringUtils.hasText(code)) {
      throw new IllegalArgumentException("成本核算任务场景不能为空");
    }
    for (CostRunTaskScene scene : values()) {
      if (scene.name().equalsIgnoreCase(code.trim())) {
        return scene;
      }
    }
    throw new IllegalArgumentException("不支持的成本核算任务场景：" + code);
  }
}
