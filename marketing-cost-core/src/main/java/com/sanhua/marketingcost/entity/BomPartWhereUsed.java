package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 当前有效 BOM 中“零件被哪些顶层产品使用”的 EasyData 聚合关系。 */
@Getter
@Setter
@TableName("lp_bom_part_where_used")
public class BomPartWhereUsed {
  @TableId(type = IdType.AUTO)
  private Long id;

  private String relationKey;
  private String priceOrgCode;
  private String partCode;
  private String partName;
  private String partSpec;
  private String topProductCode;
  private String topProductName;
  private String topBomVersion;
  private String bomPurpose;
  private BigDecimal totalQtyPerTop;
  private Long bomPathCount;
  private Integer minLevel;
  private Integer maxLevel;
  private Integer hasLeafOccurrence;
  private Integer hasNonLeafOccurrence;
  private String samplePath;
  private String shapeAttr;
  private String sourceCategory;
  private String costElementCode;
  private String sourceImportBatchId;
  private String buildBatchId;
  private LocalDateTime sourceBuiltAt;
  private LocalDate snapshotDate;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
