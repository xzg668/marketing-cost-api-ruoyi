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
@TableName("lp_quote_bom_supplement_detail")
public class QuoteBomSupplementDetail {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long supplementVersionId;
  private Long preparationId;
  private Long taskId;
  private String oaNo;
  private Long oaFormItemId;
  private String quoteProductCode;
  private String supplementScope;
  private Integer lineNo;
  private Integer level;
  private String parentCode;
  private String materialCode;
  private String materialName;
  private String materialSpec;
  private String materialModel;
  private String drawingNo;
  private String shapeAttr;
  private String mainCategoryCode;
  private String sourceCategory;
  private String costElementCode;
  private String bomPurpose;
  private String bomVersion;
  private BigDecimal qtyPerParent;
  private BigDecimal qtyPerTop;
  private BigDecimal parentBaseQty;
  private String unit;
  private String path;
  private Integer sortSeq;
  private Long sourceRawHierarchyId;
  private Long sourceU9BomId;
  private Integer manualFlag;
  private String remark;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
