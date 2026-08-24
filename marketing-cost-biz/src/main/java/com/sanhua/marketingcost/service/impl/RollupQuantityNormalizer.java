package com.sanhua.marketingcost.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 特殊上卷父件合并后，将子件累计用量还原为每个父件的真实单件用量。 */
final class RollupQuantityNormalizer {

  private RollupQuantityNormalizer() {}

  static BigDecimal perParent(
      BigDecimal totalChildQtyPerTop,
      BigDecimal parentQtyPerTop,
      BigDecimal fallbackQtyPerParent) {
    if (totalChildQtyPerTop != null
        && parentQtyPerTop != null
        && parentQtyPerTop.signum() > 0) {
      /*
       * 上游各级 BOM 倍率已经同时包含在分子、分母中。相除只消除父件合并产生的
       * 重复倍率，不会丢掉祖先节点数量；后续再乘 parentQtyPerTop 即还原顶层总用量。
       */
      return totalChildQtyPerTop.divide(parentQtyPerTop, 16, RoundingMode.HALF_UP)
          .stripTrailingZeros();
    }
    return fallbackQtyPerParent;
  }
}
