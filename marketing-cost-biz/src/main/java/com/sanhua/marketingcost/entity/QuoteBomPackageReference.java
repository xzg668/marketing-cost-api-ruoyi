package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_bom_package_reference")
public class QuoteBomPackageReference {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long preparationId;
  private Long taskId;
  private String oaNo;
  private Long oaFormItemId;
  private String quoteProductCode;
  private String bareProductCode;
  private String referenceFinishedCode;
  private String sourceTopProductCode;
  private String periodMonth;
  private Long snapshotId;
  private String referenceStatus;
  private Integer selectedLineCount;
  private Integer editedFlag;
  private Integer activeFlag;
  private Long reusedFromReferenceId;
  private String remark;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
