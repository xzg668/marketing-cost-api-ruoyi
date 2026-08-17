package com.sanhua.marketingcost.integration.drawing;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Gateway 只表达查询事实；业务服务仍需自行校验结构和指纹。 */
public record ElectronicBomFetchResult(
    Status status,
    String message,
    String sourceSystem,
    String productCode,
    String materialOrganizationCode,
    String bomPurpose,
    String sourceVersion,
    String versionStatus,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    OffsetDateTime queriedAt,
    List<ElectronicBomNode> nodes) {

  public ElectronicBomFetchResult {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
  }

  public static ElectronicBomFetchResult failure(Status status, String message) {
    return new ElectronicBomFetchResult(status, message, null, null, null, null,
        null, null, null, null, null, List.of());
  }

  public enum Status {
    FOUND,
    NOT_FOUND,
    TIMEOUT,
    FORBIDDEN,
    VOID,
    UPSTREAM_ERROR,
    INTEGRATION_DISABLED
  }
}
