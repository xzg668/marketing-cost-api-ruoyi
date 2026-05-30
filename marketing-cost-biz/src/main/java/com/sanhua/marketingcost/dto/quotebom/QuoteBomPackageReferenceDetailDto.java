package com.sanhua.marketingcost.dto.quotebom;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuoteBomPackageReferenceDetailDto {
  private Long id;
  private Long packageReferenceId;
  private String referenceFinishedCode;
  private String sourceTopProductCode;
  private String packageParentCode;
  private String packageParentName;
  private String packageParentSpec;
  private String packageParentModel;
  private String packageParentDrawingNo;
  private String packageParentShapeAttr;
  private String packageParentMainCategoryCode;
  private String packageParentUnit;
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
  private BigDecimal adjustedChildQtyPerParent;
  private BigDecimal adjustedChildQtyPerTop;
  private BigDecimal childParentBaseQty;
  private BigDecimal adjustedChildParentBaseQty;
  private BigDecimal qtyPerTop;
  private String unit;
  private String remark;
  private Boolean selected;
  private Boolean edited;
}
