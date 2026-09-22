package com.sanhua.marketingcost.dto.technicaldata;

/** 原始依据随明细冻结，本次金额单独保存，不能覆盖参考值。 */
public final class TechnicalDataAuxiliaryContent {
  private TechnicalDataAuxiliaryContent() {}
  public record CmsEvidence(String materialNo, String name, String model, String fingerprint,
      TechnicalDataAuxiliaryCmsSource.Item item) {}
  public record UploadEvidence(String fileName, String fileSha256, String sheetName,
      TechnicalDataAuxiliaryUploadResponse.Item item) {}
  public record ItemEvidence(String itemKey, CmsEvidence cms, UploadEvidence upload) {}
}
