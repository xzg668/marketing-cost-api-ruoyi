package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("cms_cost_import_batch")
public class CmsCostImportBatch {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String batchNo;
  private String importType;
  private String status;
  private String planFileName;
  private String workshopFileName;
  private String subjectFileName;
  private String subjectSettingFileName;
  private Integer planRowCount;
  private Integer workshopRowCount;
  private Integer subjectRowCount;
  private Integer subjectSettingRowCount;
  private Integer salaryInsertCount;
  private Integer salarySkipCount;
  private Integer salaryBlockedCount;
  private Integer auxInsertCount;
  private Integer auxSkipCount;
  private Integer errorCount;
  private String errorMessage;
  private String importedBy;
  private String businessUnitType;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
