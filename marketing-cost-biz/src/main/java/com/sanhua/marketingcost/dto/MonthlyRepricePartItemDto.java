package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.MonthlyRepricePartItem;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MonthlyRepricePartItemDto {
  private Long id;
  private String repriceNo;
  private String oaNo;
  private String calcObjectKey;
  private String productCode;
  private Integer lineNo;
  private String partCode;
  private String partName;
  private String partDrawingNo;
  private String material;
  private String shapeAttr;
  private BigDecimal quantity;
  private BigDecimal unitPrice;
  private BigDecimal amount;
  private String priceSource;
  private String calcStatus;
  private String calcMessage;

  public static MonthlyRepricePartItemDto fromEntity(MonthlyRepricePartItem item) {
    MonthlyRepricePartItemDto dto = new MonthlyRepricePartItemDto();
    if (item == null) {
      return dto;
    }
    dto.setId(item.getId());
    dto.setRepriceNo(item.getRepriceNo());
    dto.setOaNo(item.getOaNo());
    dto.setCalcObjectKey(item.getCalcObjectKey());
    dto.setProductCode(item.getProductCode());
    dto.setLineNo(item.getLineNo());
    dto.setPartCode(item.getPartCode());
    dto.setPartName(item.getPartName());
    dto.setPartDrawingNo(item.getPartDrawingNo());
    dto.setMaterial(item.getMaterial());
    dto.setShapeAttr(item.getShapeAttr());
    dto.setQuantity(item.getQuantity());
    dto.setUnitPrice(item.getUnitPrice());
    dto.setAmount(item.getAmount());
    dto.setPriceSource(item.getPriceSource());
    dto.setCalcStatus(item.getCalcStatus());
    dto.setCalcMessage(item.getCalcMessage());
    return dto;
  }
}
