package com.sanhua.marketingcost.service.collaboration.scan;

/** U9当前有效BOM的结构化读取结果；明确区分“无BOM”和系统/数据异常。 */
public record CurrentU9BomResult(
    Status status,
    String source,
    String bomVersion,
    String syncBatchId,
    int lineCount,
    String structureFingerprint,
    String message,
    Long monthlySnapshotId,
    boolean monthlySnapshotCreated) {

  public enum Status {
    AVAILABLE,
    NOT_FOUND,
    TIMEOUT,
    ORGANIZATION_MISMATCH,
    DATA_EMPTY,
    ERROR
  }

  public static CurrentU9BomResult available(
      String source, String bomVersion, String syncBatchId, int lineCount) {
    return available(source, bomVersion, syncBatchId, lineCount, null);
  }

  public static CurrentU9BomResult available(
      String source,
      String bomVersion,
      String syncBatchId,
      int lineCount,
      String structureFingerprint) {
    return new CurrentU9BomResult(
        Status.AVAILABLE, source, bomVersion, syncBatchId, lineCount,
        structureFingerprint, null, null, false);
  }

  public static CurrentU9BomResult notFound(String message) {
    return failure(Status.NOT_FOUND, message);
  }

  public static CurrentU9BomResult timeout(String message) {
    return failure(Status.TIMEOUT, message);
  }

  public static CurrentU9BomResult organizationMismatch(String message) {
    return failure(Status.ORGANIZATION_MISMATCH, message);
  }

  public static CurrentU9BomResult dataEmpty(String message) {
    return failure(Status.DATA_EMPTY, message);
  }

  public static CurrentU9BomResult error(String message) {
    return failure(Status.ERROR, message);
  }

  private static CurrentU9BomResult failure(Status status, String message) {
    return new CurrentU9BomResult(
        status, null, null, null, 0, null, message, null, false);
  }

  public CurrentU9BomResult withMonthlySnapshot(Long snapshotId, boolean created) {
    return new CurrentU9BomResult(
        status, source, bomVersion, syncBatchId, lineCount, structureFingerprint, message,
        snapshotId, created);
  }
}
