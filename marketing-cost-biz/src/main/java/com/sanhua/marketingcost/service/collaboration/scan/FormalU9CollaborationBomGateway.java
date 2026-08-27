package com.sanhua.marketingcost.service.collaboration.scan;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.collaboration.ApprovedResultFingerprints;
import java.util.List;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 将当前正式U9 BOM读取能力适配为不会把异常误判成“无BOM”的扫描结果。 */
@Component
public class FormalU9CollaborationBomGateway
    implements QuoteCollaborationLiveU9BomGateway {

  private final FormalBomReadService formalBomReadService;
  private final BomRawHierarchyMapper bomRawHierarchyMapper;
  private final ApprovedResultFingerprints fingerprints;

  public FormalU9CollaborationBomGateway(
      FormalBomReadService formalBomReadService,
      BomRawHierarchyMapper bomRawHierarchyMapper,
      ApprovedResultFingerprints fingerprints) {
    this.formalBomReadService = formalBomReadService;
    this.bomRawHierarchyMapper = bomRawHierarchyMapper;
    this.fingerprints = fingerprints;
  }

  @Override
  public CurrentU9BomResult readLive(QuoteCollaborationScanContext context) {
    if (context == null) {
      return CurrentU9BomResult.dataEmpty("报价扫描上下文为空");
    }
    try {
      FormalBomReadResult result =
          formalBomReadService.read(
              context.productCode(),
              context.accountingMonth(),
              null,
              context.bomEffectiveDate(),
              context.organization());
      if (result == null) {
        return CurrentU9BomResult.dataEmpty("U9正式BOM读取返回空对象");
      }
      List<QuoteBomSourceLineDto> lines =
          result.lines() == null ? List.of() : result.lines();
      if (result.found()) {
        if (lines.isEmpty()) {
          return CurrentU9BomResult.dataEmpty("U9返回有BOM，但BOM明细为空");
        }
        String version =
            lines.stream()
                .map(QuoteBomSourceLineDto::bomVersion)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .findFirst()
                .orElse(null);
        String batchId = resolveRootBatchId(lines);
        if (!StringUtils.hasText(batchId)) {
          return CurrentU9BomResult.dataEmpty("U9正式BOM缺少可追溯的层级构建批次");
        }
        return CurrentU9BomResult.available(
            "U9", version, batchId, lines.size(), fingerprints.u9Structure(lines));
      }
      String gapMessage = trimToNull(result.gapMessage());
      if (gapMessage != null
          && (gapMessage.contains("有效连通") || gapMessage.contains("明细为空"))) {
        return CurrentU9BomResult.dataEmpty(gapMessage);
      }
      if (gapMessage != null
          && (gapMessage.contains("展开失败") || gapMessage.contains("结构缺失"))) {
        return CurrentU9BomResult.error(gapMessage);
      }
      if (existsOnlyInOtherOrganization(context)) {
        return CurrentU9BomResult.organizationMismatch(
            "产品 "
                + context.productCode()
                + " 在目标组织 "
                + context.priceOrgCode()
                + " 无BOM，但其他组织存在BOM，禁止跨组织兜底");
      }
      return CurrentU9BomResult.notFound(
          gapMessage == null ? "U9无当前有效BOM" : gapMessage);
    } catch (TransientDataAccessException exception) {
      return CurrentU9BomResult.timeout("U9当前BOM查询超时：" + exceptionMessage(exception));
    } catch (RuntimeException exception) {
      if (isTimeout(exception)) {
        return CurrentU9BomResult.timeout("U9当前BOM查询超时：" + exceptionMessage(exception));
      }
      if (isOrganizationMismatch(exception)) {
        return CurrentU9BomResult.organizationMismatch(exceptionMessage(exception));
      }
      return CurrentU9BomResult.error("U9当前BOM查询失败：" + exceptionMessage(exception));
    }
  }

  private String resolveRootBatchId(List<QuoteBomSourceLineDto> lines) {
    Long rootId = lines.stream()
        .filter(line -> line.level() != null && line.level() == 0)
        .map(QuoteBomSourceLineDto::sourceRawHierarchyId)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);
    if (rootId == null) return null;
    BomRawHierarchy root = bomRawHierarchyMapper.selectById(rootId);
    if (root == null) return null;
    return StringUtils.hasText(root.getBuildBatchId())
        ? root.getBuildBatchId().trim()
        : trimToNull(root.getSourceImportBatchId());
  }

  private boolean existsOnlyInOtherOrganization(QuoteCollaborationScanContext context) {
    Long count =
        bomRawHierarchyMapper.selectCount(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(BomRawHierarchy::getSourceType, "U9")
                .eq(BomRawHierarchy::getTopProductCode, context.productCode())
                .eq(BomRawHierarchy::getLevel, 0)
                .ne(BomRawHierarchy::getPriceOrgCode, context.priceOrgCode())
                .le(BomRawHierarchy::getEffectiveFrom, context.bomEffectiveDate())
                .and(
                    wrapper ->
                        wrapper
                            .isNull(BomRawHierarchy::getEffectiveTo)
                            .or()
                            .ge(BomRawHierarchy::getEffectiveTo, context.bomEffectiveDate())));
    return count != null && count > 0;
  }

  private boolean isTimeout(RuntimeException exception) {
    String message = exceptionMessage(exception).toLowerCase(java.util.Locale.ROOT);
    return message.contains("timeout") || message.contains("超时");
  }

  private boolean isOrganizationMismatch(RuntimeException exception) {
    String message = exceptionMessage(exception);
    return message.contains("组织") && (message.contains("不一致") || message.contains("不匹配"));
  }

  private String exceptionMessage(RuntimeException exception) {
    return StringUtils.hasText(exception.getMessage())
        ? exception.getMessage().trim()
        : exception.getClass().getSimpleName();
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
