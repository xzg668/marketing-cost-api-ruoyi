package com.sanhua.marketingcost.dto.quotebom;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuoteBomSupplementDetailDto {
  private Long id;
  private Long supplementVersionId;
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
  private BigDecimal qtyPerParent;
  private BigDecimal qtyPerTop;
  private BigDecimal parentBaseQty;
  private String unit;
  private String remark;
}
