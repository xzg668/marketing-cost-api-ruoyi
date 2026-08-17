package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_price_draft_field")
public class QuotePriceDraftField {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long priceDraftId;
  private String sectionCode;
  private String rowKey;
  private String fieldCode;
  private String fieldName;
  private String valueType;
  private String referenceValueJson;
  private String targetValueJson;
  private String unit;
  private Integer requiredFlag;
  private Integer techInputRequired;
  private Integer changedFlag;
  private String validationStatus;
  private String validationMessage;
  private Integer sortSeq;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
