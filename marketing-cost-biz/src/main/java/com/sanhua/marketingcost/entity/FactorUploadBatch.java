package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_factor_upload_batch")
public class FactorUploadBatch {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String batchNo;
  private String importType;
  private String importPurpose;
  private String effectiveStrategy;
  private String priceMonth;
  private String businessUnitType;
  private String fileName;
  private String fileSha256;
  private String contentHash;
  private String uploadedBy;
  private String status;
  private Integer factorSheetCount;
  private Integer linkedSheetCount;
  private Integer factorRowCount;
  private Integer linkedRowCount;
  private Integer autoBindingCount;
  private Integer warningCount;
  private Integer errorCount;
  private String errorMessage;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
