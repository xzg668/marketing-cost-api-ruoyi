package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_excel_auto_binding_import_log")
public class ExcelAutoBindingImportLog {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long factorUploadBatchId;
  private Long linkedItemId;
  private String materialCode;
  private String supplierCode;
  private String tokenName;
  private String action;
  private String status;
  private Long factorIdentityId;
  private Long factorMonthlyPriceId;
  private String sourceWorkbookName;
  private String sourceSheetName;
  private String sourceCellRef;
  private String excelFormula;
  private String message;
  private LocalDateTime createdAt;
}
