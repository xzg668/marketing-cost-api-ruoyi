package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 确认后不可变的报价最终有效 BOM 节点。 */
@Data
@TableName("lp_quote_effective_bom_node")
public class QuoteEffectiveBomNode {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String buildBatchId;
  private Long originMonthlySnapshotId;
  private String effectiveVariantHash;
  private String topProductCode;
  private String costPeriodMonth;
  private String priceOrgCode;
  private String nodeKey;
  private String parentNodeKey;
  private Integer nodeLevel;
  private Integer sortSeq;
  private String nodePath;
  private String materialCode;
  private String materialName;
  private String materialSpec;
  private String materialModel;
  private String materialUnit;
  private BigDecimal qtyPerParent;
  private BigDecimal qtyPerTop;
  private String sourceMaterialShape;
  private String effectiveMaterialShape;
  private String shapeResolutionSource;
  private Long shapePolicyId;
  private String shapePolicyFingerprint;
  private Long selectedSupplierRatioId;
  private String selectedSupplierCode;
  private String selectedSupplierName;
  private BigDecimal selectedSupplyRatio;
  private String alternativeGroupKey;
  private String alternativeChildType;
  private Long alternativeSelectionId;
  private String alternativeSelectionSource;
  private String sourceBomType;
  private String sourceBomBatchId;
  private Long sourceHierarchyId;
  private String sourceNodePath;
  private LocalDateTime createdAt;
  private Long createdBy;
}
