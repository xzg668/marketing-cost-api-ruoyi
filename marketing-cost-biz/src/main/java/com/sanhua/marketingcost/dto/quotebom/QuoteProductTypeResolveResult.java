package com.sanhua.marketingcost.dto.quotebom;

import com.sanhua.marketingcost.enums.QuoteProductType;

/** 报价产品裸品 / 非裸品识别结果。 */
public record QuoteProductTypeResolveResult(
    String quoteProductCode,
    QuoteProductType productType,
    String mainCategoryCode,
    String shapeAttr,
    String materialName,
    String materialSpec,
    String errorMessage) {

  public String productTypeCode() {
    return productType == null ? null : productType.getCode();
  }
}
