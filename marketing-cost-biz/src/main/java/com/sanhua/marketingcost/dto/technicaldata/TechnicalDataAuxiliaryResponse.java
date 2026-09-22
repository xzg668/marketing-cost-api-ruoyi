package com.sanhua.marketingcost.dto.technicaldata;

import java.math.BigDecimal;
import java.util.List;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryContent.ItemEvidence;

public record TechnicalDataAuxiliaryResponse(Long taskId, Long productId, String materialNo,
    String productName, String productModel, String accountingMonth, Long draftVersionId,
    Integer draftVersionNo, String draftVersionStatus, Integer expectedVersion, String moduleStatus,
    String entryMode, boolean editable, boolean historical, BigDecimal totalAmount,
    List<Item> items, List<String> issues) {
  public record Item(Long id, Integer lineNo, String itemKey, String name,
      BigDecimal sourceAmount, BigDecimal amount, ItemEvidence source) {}
}
