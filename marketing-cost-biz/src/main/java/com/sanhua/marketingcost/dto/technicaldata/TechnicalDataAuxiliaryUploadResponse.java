package com.sanhua.marketingcost.dto.technicaldata;

import java.math.BigDecimal;
import java.util.List;

/** 上传原表的解析结果；原归类与待财务填写的二级科目分开保留。 */
public record TechnicalDataAuxiliaryUploadResponse(String fileName, String fileSha256, String sheetName,
    List<Item> items, List<Issue> issues) {
  public record Item(String itemKey, int sheetRow, int sequence, String partName, String processName,
      String materialNo, String name, BigDecimal priceExcludingTax, BigDecimal volumeOrArea,
      BigDecimal processableQuantity, BigDecimal amountPerProduct, String category,
      String secondarySubjectName, String remark) {}
  public record Issue(String sheetName, Integer row, String column, String message) {}
}
