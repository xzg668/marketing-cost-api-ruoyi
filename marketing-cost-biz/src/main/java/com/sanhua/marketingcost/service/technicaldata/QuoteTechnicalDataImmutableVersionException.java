package com.sanhua.marketingcost.service.technicaldata;

public class QuoteTechnicalDataImmutableVersionException extends IllegalStateException {
  public QuoteTechnicalDataImmutableVersionException(Long versionId, String status) {
    super("技术资料版本已不可修改：versionId=" + versionId + "，status=" + status);
  }
}
