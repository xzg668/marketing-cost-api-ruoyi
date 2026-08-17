package com.sanhua.marketingcost.service.collaboration.scan;

/** 审核结果生成和复验共用的正式BOM/包装来源快照。 */
public record ApprovedResultSourceSnapshot(
    Status status,
    String sourceObjectType,
    String sourceSystem,
    String sourceVersionText,
    int lineCount,
    String structureFingerprint,
    String message) {

  public enum Status { READY, NOT_FOUND, INVALID, ERROR }

  public static ApprovedResultSourceSnapshot ready(
      String sourceObjectType,
      String sourceSystem,
      String sourceVersionText,
      int lineCount,
      String structureFingerprint) {
    return new ApprovedResultSourceSnapshot(
        Status.READY, sourceObjectType, sourceSystem, sourceVersionText,
        lineCount, structureFingerprint, null);
  }

  public static ApprovedResultSourceSnapshot notFound(String message) {
    return failure(Status.NOT_FOUND, message);
  }

  public static ApprovedResultSourceSnapshot invalid(String message) {
    return failure(Status.INVALID, message);
  }

  public static ApprovedResultSourceSnapshot error(String message) {
    return failure(Status.ERROR, message);
  }

  private static ApprovedResultSourceSnapshot failure(Status status, String message) {
    return new ApprovedResultSourceSnapshot(status, null, null, null, 0, null, message);
  }
}
