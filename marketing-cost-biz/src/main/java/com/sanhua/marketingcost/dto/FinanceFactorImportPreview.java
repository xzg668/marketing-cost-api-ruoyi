package com.sanhua.marketingcost.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.util.List;

public record FinanceFactorImportPreview(
    FinanceFactorImportContext context,
    FinanceFactorWorkbook workbook,
    Long previousBatchId,
    Integer previousRevision,
    String contentHash,
    String previewFingerprint,
    List<Change> changes,
    String publicationNote,
    String importPurpose) {
  public FinanceFactorImportPreview {
    changes = List.copyOf(changes);
  }

  public record Change(
      String canonicalKey,
      String schemeCode,
      String conditionCode,
      @JsonSerialize(using = ToStringSerializer.class) BigDecimal previousPrice,
      @JsonSerialize(using = ToStringSerializer.class) BigDecimal price,
      String unit,
      String kind) {}
}
