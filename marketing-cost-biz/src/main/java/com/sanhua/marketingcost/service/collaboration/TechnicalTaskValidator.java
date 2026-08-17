package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.dto.collaboration.TechnicalTaskValidationResponse.Issue;
import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** QCBP-09 统一完成门禁；后续编辑任务只需把真实结果写入现有引用和缺口状态。 */
@Component
public class TechnicalTaskValidator {
  private final QuotePriceDraftRepository draftRepository;

  public TechnicalTaskValidator(QuotePriceDraftRepository draftRepository) {
    this.draftRepository = draftRepository;
  }

  public List<Issue> validate(
      QuoteCollaborationProductTask task, List<QuoteCollaborationGap> gaps) {
    List<Issue> issues = new ArrayList<>();
    if (enabled(task.getNeedBom())) {
      if (task.getSupplementVersionId() == null) {
        issues.add(new Issue("BOM", "BOM_DRAFT_MISSING", "尚未保存目标产品的BOM补录草稿"));
      } else if (!StringUtils.hasText(task.getElectronicBomFingerprint())) {
        issues.add(new Issue("BOM", "ELECTRONIC_BOM_NOT_VERIFIED", "电子图库BOM尚未回取并校验"));
      }
    }
    if (enabled(task.getNeedPackage()) && task.getPackageReferenceId() == null) {
      issues.add(new Issue("PACKAGE", "PACKAGE_RESULT_MISSING", "裸品包装方案尚未补齐并保存"));
    }

    List<QuoteCollaborationGap> priceGaps = safe(gaps).stream()
        .filter(gap -> "PRICE".equals(gap.getGapCategory()))
        .filter(gap -> !"OBSOLETE".equals(gap.getGapStatus()))
        .toList();
    if (enabled(task.getNeedPrice()) && priceGaps.isEmpty()) {
      issues.add(new Issue("PRICE", "PRICE_GAPS_NOT_READY", "缺价明细尚未生成，请先完成BOM或包装检查"));
    }
    for (QuoteCollaborationGap gap : priceGaps) {
      if (!resolvedPriceGap(task, gap)) {
        String material = StringUtils.hasText(gap.getMaterialName())
            ? gap.getMaterialName() : gap.getMaterialCode();
        issues.add(new Issue("PRICE", "PRICE_GAP_OPEN",
            (StringUtils.hasText(material) ? material : "底层物料") + "的价格尚未补齐"));
      }
    }
    return List.copyOf(issues);
  }

  private boolean resolvedPriceGap(
      QuoteCollaborationProductTask task, QuoteCollaborationGap gap) {
    if ("RESOLVED".equals(gap.getGapStatus()) || "WAIVED".equals(gap.getGapStatus())) {
      return true;
    }
    if (!"DRAFT_READY".equals(gap.getGapStatus()) || gap.getCurrentPriceDraftId() == null) {
      return false;
    }
    CollaborationScope scope = new CollaborationScope(
        task.getBusinessUnitType(), task.getApplicableOrgCode());
    QuotePriceDraft draft = draftRepository.findById(gap.getCurrentPriceDraftId(), scope)
        .orElse(null);
    return draft != null && task.getId().equals(draft.getProductTaskId())
        && "VALIDATED".equals(draft.getDraftStatus())
        && "PASSED".equals(draft.getValidationStatus());
  }

  private static boolean enabled(Integer value) {
    return value != null && value == 1;
  }

  private static List<QuoteCollaborationGap> safe(List<QuoteCollaborationGap> gaps) {
    return gaps == null ? List.of() : gaps;
  }
}
