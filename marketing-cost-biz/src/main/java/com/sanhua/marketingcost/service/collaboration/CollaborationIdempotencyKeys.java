package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.PrimaryScope;

/** 业务幂等键只从稳定业务主键和版本构造。 */
public final class CollaborationIdempotencyKeys {

  private CollaborationIdempotencyKeys() {}

  public static String start(Long oaFormItemId, String month, PrimaryScope scope) {
    return positive(oaFormItemId, "报价产品行ID") + ":" + text(month, "核算月份") + ":"
        + required(scope, "协作范围").code() + ":START";
  }

  public static String technicalSubmit(String productTaskNo, int taskVersion) {
    return text(productTaskNo, "产品任务号") + ":" + version(taskVersion) + ":TECH_SUBMIT";
  }

  public static String reviewSubmit(String reviewNo, int sourceTaskVersion) {
    return text(reviewNo, "审核号") + ":" + version(sourceTaskVersion) + ":REVIEW_SUBMIT";
  }

  public static String publish(String publishBatchNo, Long priceDraftId) {
    return text(publishBatchNo, "发布批次号") + ":" + positive(priceDraftId, "价格草稿ID");
  }

  public static String reprice(Long quoteLinkId, String publishBatchNo) {
    return positive(quoteLinkId, "报价关联ID") + ":" + text(publishBatchNo, "发布批次号")
        + ":REPRICE";
  }

  private static long positive(Long value, String name) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(name + "必须为正数");
    }
    return value;
  }

  private static int version(int value) {
    if (value <= 0) {
      throw new IllegalArgumentException("版本必须为正数");
    }
    return value;
  }

  private static String text(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + "不能为空");
    }
    return value.trim();
  }

  private static <T> T required(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + "不能为空");
    }
    return value;
  }
}
