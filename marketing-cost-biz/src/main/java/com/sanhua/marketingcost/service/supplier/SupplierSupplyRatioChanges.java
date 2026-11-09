package com.sanhua.marketingcost.service.supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.BusinessChangeLog;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import java.time.LocalDateTime;

/** Import and manual corrections retain the same immutable before/after evidence. */
public final class SupplierSupplyRatioChanges {
  private SupplierSupplyRatioChanges() {}

  public static BusinessChangeLog version(SupplierSupplyRatio previous, SupplierSupplyRatio next,
      String actor, LocalDateTime now, String source, String reason, ObjectMapper json) {
    var entry = new BusinessChangeLog();
    entry.setBizDomain("SUPPLIER_SUPPLY_RATIO");
    entry.setBizType("SUPPLIER_RELATION");
    entry.setBizId(next.getId());
    entry.setBizDetailId(previous == null ? null : previous.getId());
    entry.setFieldName("SUPPLIER_RELATION");
    entry.setFieldLabel("供货关系");
    try {
      entry.setBeforeValue(previous == null ? null : json.writeValueAsString(previous));
      entry.setAfterValue(json.writeValueAsString(next));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("供货关系变更依据保存失败", ex);
    }
    entry.setChangeReason(reason);
    entry.setChangedByName(actor);
    entry.setChangedAt(now);
    entry.setCreatedAt(now);
    entry.setChangeSource(source);
    entry.setSubmitBatchNo(next.getSourceBatchNo());
    entry.setIdempotencyKey("SUPPLIER_RATIO_VERSION:" + next.getId());
    return entry;
  }
}
