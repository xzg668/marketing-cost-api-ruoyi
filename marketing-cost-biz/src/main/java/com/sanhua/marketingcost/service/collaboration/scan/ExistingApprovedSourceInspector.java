package com.sanhua.marketingcost.service.collaboration.scan;

import com.sanhua.marketingcost.entity.QuoteCollaborationApprovedResult;
import com.sanhua.marketingcost.service.collaboration.ApprovedResultFingerprints;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ResultType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 重新读取正式来源并比较审核时指纹，来源变化时绝不静默复用。 */
@Component
public class ExistingApprovedSourceInspector
    implements QuoteCollaborationApprovedSourceInspector {

  private final ApprovedResultSourceSnapshotReader sourceReader;
  private final ApprovedResultFingerprints fingerprints;

  public ExistingApprovedSourceInspector(
      ApprovedResultSourceSnapshotReader sourceReader,
      ApprovedResultFingerprints fingerprints) {
    this.sourceReader = sourceReader;
    this.fingerprints = fingerprints;
  }

  @Override
  public ApprovedSourceInspection inspect(
      QuoteCollaborationApprovedResult result,
      QuoteCollaborationScanContext context,
      CurrentU9BomResult currentU9Bom) {
    if (result == null || context == null || result.getSourceObjectId() == null) {
      return ApprovedSourceInspection.invalid("已审核结果缺少来源对象");
    }
    ApprovedResultSourceSnapshot snapshot;
    if (ResultType.FULL_BOM.code().equals(result.getResultType())) {
      snapshot = sourceReader.readFullBom(result.getSourceObjectId(), context.productCode());
    } else if (ResultType.BARE_PACKAGE.code().equals(result.getResultType())) {
      snapshot = sourceReader.readBarePackage(
          result.getSourceObjectId(), context.productCode(),
          context.priceOrgCode(), context.materialOrganizationCode());
    } else {
      return ApprovedSourceInspection.invalid(
          "不支持的审核结果类型：" + result.getResultType());
    }
    if (snapshot == null) {
      return ApprovedSourceInspection.error("审核结果来源读取没有返回结果");
    }
    ApprovedSourceInspection failure = failure(snapshot);
    if (failure != null) {
      return failure;
    }
    if (!same(result.getSourceObjectType(), snapshot.sourceObjectType())
        || !same(result.getSourceSystem(), snapshot.sourceSystem())) {
      return ApprovedSourceInspection.invalid("审核结果的正式来源类型已变化");
    }
    if (!same(result.getStructureFingerprint(), snapshot.structureFingerprint())) {
      return ApprovedSourceInspection.invalid(
          ResultType.FULL_BOM.code().equals(result.getResultType())
              ? "电子图库BOM结构指纹已变化"
              : "包装来源结构指纹已变化");
    }
    if (ResultType.BARE_PACKAGE.code().equals(result.getResultType())) {
      if (currentU9Bom == null
          || currentU9Bom.status() != CurrentU9BomResult.Status.AVAILABLE) {
        return ApprovedSourceInspection.invalid("裸品U9本体BOM已不存在或不可用");
      }
      String currentContext = fingerprints.u9Context(context, currentU9Bom);
      if (!same(result.getU9ContextFingerprint(), currentContext)) {
        return ApprovedSourceInspection.invalid("裸品U9本体上下文已变化");
      }
    }
    return ApprovedSourceInspection.ready(
        snapshot.sourceSystem(), snapshot.lineCount(), snapshot.structureFingerprint());
  }

  private ApprovedSourceInspection failure(ApprovedResultSourceSnapshot snapshot) {
    return switch (snapshot.status()) {
      case READY -> null;
      case NOT_FOUND -> ApprovedSourceInspection.notFound(snapshot.message());
      case INVALID -> ApprovedSourceInspection.invalid(snapshot.message());
      case ERROR -> ApprovedSourceInspection.error(snapshot.message());
    };
  }

  private boolean same(String left, String right) {
    return StringUtils.hasText(left) && StringUtils.hasText(right)
        && left.trim().equals(right.trim());
  }
}
