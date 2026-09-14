package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_tech_aux_item")
public class QuoteTechAuxItem {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long versionId;
  private Integer lineNo;
  private Integer sortSeq;
  private String subjectCode;
  private String subjectName;
  private String auxiliaryMaterialNo;
  private String auxiliaryName;
  private String auxiliarySpec;
  private String pricingMethod;
  private BigDecimal quantity;
  private String originalUnit;
  private BigDecimal standardQuantity;
  private String standardUnit;
  private BigDecimal conversionFactor;
  private BigDecimal referenceUnitPrice;
  private String priceUnit;
  private BigDecimal lossRate;
  private BigDecimal amount;
  private String sourceReferenceId;
  private String sourceReferenceVersion;
  private String sourceSnapshotJson;
  private String remark;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
