package com.sanhua.marketingcost.service.technicaldata;

public class QuoteTechnicalDataOptimisticLockException extends IllegalStateException {
  public QuoteTechnicalDataOptimisticLockException(String objectType, Long objectId) {
    super("技术资料" + objectType + "已被其他操作修改：id=" + objectId);
  }
}
