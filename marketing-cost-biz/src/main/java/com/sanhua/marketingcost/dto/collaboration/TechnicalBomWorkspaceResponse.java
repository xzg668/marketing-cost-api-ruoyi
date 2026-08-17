package com.sanhua.marketingcost.dto.collaboration;

import java.util.List;

/** 三步式 BOM 协作工作区。 */
public record TechnicalBomWorkspaceResponse(
    Long taskId,
    Integer taskVersion,
    TargetProduct target,
    int currentStep,
    String primaryAction,
    String primaryActionLabel,
    List<Step> steps,
    TechnicalBomDraftResponse draft,
    String electronicBomFingerprint,
    String verificationStatus,
    List<VerificationIssue> verificationIssues) {

  public TechnicalBomWorkspaceResponse {
    steps = steps == null ? List.of() : List.copyOf(steps);
    verificationIssues = verificationIssues == null ? List.of() : List.copyOf(verificationIssues);
  }

  public record TargetProduct(
      String productCode,
      String temporaryProductKey,
      String productName,
      String productSpec,
      String productModel,
      String productDrawingNo,
      String materialNature,
      String priceOrgCode,
      String materialOrganizationCode) {}

  public record Step(int step, String title, String status) {}

  public record VerificationIssue(
      Long gapId,
      String nodeKey,
      String bomPath,
      String code,
      String message) {}
}
