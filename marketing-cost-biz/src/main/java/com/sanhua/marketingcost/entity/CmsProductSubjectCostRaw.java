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
@TableName("cms_product_subject_cost_raw")
public class CmsProductSubjectCostRaw {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long importBatchId;
  private Integer rowNo;
  private String period;
  private String firstUnitCode;
  private String firstUnitName;
  private String parentCode;
  private String parentName;
  private String parentSpec;
  private String parentType;
  private String lastSubjectCode;
  private String lastSubjectName;
  private String lastSubjectLevel;
  private BigDecimal materialPrice;
  private BigDecimal materialPriceYuan;
  private String buildFlag;
  private String path;
  private String firstSubjectCode;
  private String firstSubjectName;
  private String secondSubjectCode;
  private String secondSubjectName;
  private String thirdSubjectCode;
  private String thirdSubjectName;
  private String sourceRowId;
  private String sequenceNo;
  private String sequenceStatus;
  private String businessUnitType;
  private LocalDateTime createdAt;
}
