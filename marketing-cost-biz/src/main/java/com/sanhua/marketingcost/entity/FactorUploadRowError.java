package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_factor_upload_row_error")
public class FactorUploadRowError {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long factorUploadBatchId;
  private String sourceWorkbookName;
  private String sourceSheetName;
  private Integer excelRowNumber;
  private String materialCode;
  private String materialName;
  private String supplierCode;
  private String orderType;
  private String formula;
  private LocalDate formulaEffectiveDate;
  private String errorStage;
  private String errorCode;
  private String errorMessage;
  private String suggestion;
  private LocalDateTime createdAt;
}
