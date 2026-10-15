package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_factor_row_ref")
public class FactorRowRef {
  /** PRC-01：原稿/财务版本契约；旧数据的未知来源保持为空。 */
  private Integer sourceColumnNumber;
  private String sourceCellRef;
  private String schemeCode;
  private String conditionCode;
  private String sourceValueText;
  private String sourceFormulaExpr;
  private String sourceNumberFormat;
  private String sourceUnit;
  private String conversionBasis;

  @TableId(type = IdType.AUTO)
  private Long id;
  private Long factorUploadBatchId;
  private String sourceWorkbookName;
  private String sourceSheetName;
  private Integer sourceRowNumber;
  private Long factorIdentityId;
  private Long factorMonthlyPriceId;
  private String factorSeqNo;
  private String factorName;
  private String shortName;
  private String priceSource;
  private BigDecimal price;
  private BigDecimal originalPrice;
  private String unit;
  private LocalDateTime createdAt;
}
