package com.sanhua.marketingcost.service.collaboration.scan;

/** 已审核结果指向的电子图库BOM或包装结构是否仍能被当前系统读取。 */
public record ApprovedSourceInspection(
    Status status,
    String source,
    int lineCount,
    String structureFingerprint,
    String message) {

  public enum Status {
    READY,
    NOT_FOUND,
    INVALID,
    ERROR
  }

  public static ApprovedSourceInspection ready(
      String source, int lineCount, String structureFingerprint) {
    return new ApprovedSourceInspection(
        Status.READY, source, lineCount, structureFingerprint, null);
  }

  public static ApprovedSourceInspection notFound(String message) {
    return failure(Status.NOT_FOUND, message);
  }

  public static ApprovedSourceInspection invalid(String message) {
    return failure(Status.INVALID, message);
  }

  public static ApprovedSourceInspection error(String message) {
    return failure(Status.ERROR, message);
  }

  private static ApprovedSourceInspection failure(Status status, String message) {
    return new ApprovedSourceInspection(status, null, 0, null, message);
  }
}
