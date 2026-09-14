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
@TableName("lp_quote_tech_package_item")
public class QuoteTechPackageItem {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long versionId;
  private Integer lineNo;
  private Integer sortSeq;
  private String componentMaterialNo;
  private String componentName;
  private String componentSpec;
  private BigDecimal quantity;
  private String originalUnit;
  private BigDecimal standardQuantity;
  private String standardUnit;
  private BigDecimal conversionFactor;
  private String priceBasisType;
  private BigDecimal referenceUnitPrice;
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
