package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.util.List;

/** 工时原表、逐行公式结果及一次分转元的计算依据。 */
public record TechnicalDataSalaryUploadResponse(String fileName, String fileSha256, String sheetName,
    String calculationRule, List<Item> items,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amountFen,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amountYuan, List<Issue> issues) {
  public TechnicalDataSalaryUploadResponse { items = List.copyOf(items); issues = List.copyOf(issues); }

  public record Item(String itemKey, int row, String partName, String partModel, String equipment,
      String processNo, String processName, BigDecimal staffing, BigDecimal cycleTime,
      BigDecimal hourlyWage, BigDecimal shiftOutput, BigDecimal unitTime,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amountFen,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amountYuan,
      String shiftFormula, String timeFormula, String wageFormula, String remark) {}

  public record Issue(String sheetName, Integer row, String message) {}
}
