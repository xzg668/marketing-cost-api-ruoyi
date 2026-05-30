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
@TableName("lp_quote_bom_package_reference_detail")
public class QuoteBomPackageReferenceDetail {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long packageReferenceId;
  private Long preparationId;
  private Long taskId;
  private String oaNo;
  private Long oaFormItemId;
  private String bareProductCode;
  private String referenceFinishedCode;
  private String sourceTopProductCode;
  private Long snapshotId;
  private Long snapshotDetailId;
  private Integer lineNo;
  private String packageParentCode;
  private String packageParentName;
  private String packageParentSpec;
  private String packageParentModel;
  private String packageParentDrawingNo;
  private String packageParentShapeAttr;
  private String packageParentMainCategoryCode;
  private String packageParentUnit;
  private String packageParentCodeInReferenceBom;
  private BigDecimal packageQtyPerParent;
  private BigDecimal packageQtyPerTop;
  private BigDecimal packageParentBaseQty;
  private BigDecimal adjustedPackageQtyPerParent;
  private BigDecimal adjustedPackageQtyPerTop;
  private BigDecimal adjustedPackageParentBaseQty;
  private String packageMaterialCode;
  private String packageMaterialName;
  private String packageMaterialSpec;
  private String packageMaterialModel;
  private String packageMaterialDrawingNo;
  private String packageMaterialShapeAttr;
  private String packageMaterialMainCategoryCode;
  private String packageMaterialUnit;
  private BigDecimal childQtyPerParent;
  private BigDecimal childQtyPerTop;
  private BigDecimal childParentBaseQty;
  private BigDecimal adjustedChildQtyPerParent;
  private BigDecimal adjustedChildQtyPerTop;
  private BigDecimal adjustedChildParentBaseQty;
  private BigDecimal qtyPerTop;
  private String unit;
  private Long sourceRawHierarchyId;
  private Long sourceU9BomId;
  private String sourceParentCode;
  private String sourcePath;
  private Integer selectedFlag;
  private Integer editedFlag;
  private String remark;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
