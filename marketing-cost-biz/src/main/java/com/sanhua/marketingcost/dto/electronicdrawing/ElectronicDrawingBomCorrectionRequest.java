package com.sanhua.marketingcost.dto.electronicdrawing;

import java.math.BigDecimal;
import java.util.List;

/** 财务以一条或多条并列材料替换本单的指定图库行；用量相对原父项。 */
public record ElectronicDrawingBomCorrectionRequest(
    Integer expectedTaskVersion,
    Long expectedSourceVersionId,
    Long sourceNodeId,
    String reason,
    List<Line> lines) {
  public record Line(String materialCode, BigDecimal quantity, String unit) {}
}
