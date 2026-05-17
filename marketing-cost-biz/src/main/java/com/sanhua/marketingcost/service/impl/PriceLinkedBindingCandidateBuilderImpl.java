package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.BindingCandidate;
import com.sanhua.marketingcost.dto.BindingCandidateBuildResult;
import com.sanhua.marketingcost.dto.ResolvedFactorRef;
import com.sanhua.marketingcost.service.PriceLinkedBindingCandidateBuilder;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PriceLinkedBindingCandidateBuilderImpl
    implements PriceLinkedBindingCandidateBuilder {

  private static final String TOKEN_MATERIAL_INCL = "材料含税价格";
  private static final String TOKEN_MATERIAL = "材料价格";
  private static final String TOKEN_SCRAP_INCL = "废料含税价格";
  private static final String TOKEN_SCRAP = "废料价格";

  @Override
  public BindingCandidateBuildResult build(
      String materialCode,
      String linkedItemImportKey,
      String formulaText,
      List<ResolvedFactorRef> resolvedRefs) {
    BindingCandidateBuildResult result = new BindingCandidateBuildResult();
    List<ResolvedFactorRef> usableRefs = usableRefs(resolvedRefs, result);
    if (usableRefs.isEmpty()) {
      result.getWarnings().add("未识别到可自动绑定的影响因素引用");
      return result;
    }
    if (usableRefs.size() < 2) {
      result.getWarnings().add("仅识别到 1 处影响因素引用，只生成材料价格绑定候选");
    }
    if (usableRefs.size() > 2) {
      result.getWarnings().add("识别到 " + usableRefs.size()
          + " 处影响因素引用，第一版仅处理前 2 处：材料、废料");
    }

    result.getCandidates().add(candidate(
        materialCode, linkedItemImportKey, materialTokenName(formulaText), usableRefs.get(0)));
    if (usableRefs.size() >= 2) {
      result.getCandidates().add(candidate(
          materialCode, linkedItemImportKey, scrapTokenName(formulaText), usableRefs.get(1)));
    }
    return result;
  }

  private List<ResolvedFactorRef> usableRefs(
      List<ResolvedFactorRef> resolvedRefs, BindingCandidateBuildResult result) {
    List<ResolvedFactorRef> usable = new ArrayList<>();
    if (resolvedRefs == null) {
      return usable;
    }
    for (ResolvedFactorRef ref : resolvedRefs) {
      if (ref == null) {
        continue;
      }
      if (ref.isResolved()) {
        usable.add(ref);
        continue;
      }
      if (StringUtils.hasText(ref.getErrorMessage())) {
        result.getWarnings().add("影响因素引用未解析，已跳过："
            + ref.getSheetName() + "!" + ref.getRowNumber() + "，" + ref.getErrorMessage());
      }
    }
    return usable;
  }

  private BindingCandidate candidate(
      String materialCode,
      String linkedItemImportKey,
      String tokenName,
      ResolvedFactorRef sourceRef) {
    BindingCandidate candidate = new BindingCandidate();
    candidate.setMaterialCode(normalize(materialCode));
    candidate.setLinkedItemImportKey(normalize(linkedItemImportKey));
    candidate.setTokenName(tokenName);
    candidate.setFactorIdentityId(sourceRef.getFactorIdentityId());
    candidate.setFactorMonthlyPriceId(sourceRef.getFactorMonthlyPriceId());
    candidate.setSourceRef(sourceRef);
    return candidate;
  }

  private String materialTokenName(String formulaText) {
    String raw = formulaText == null ? "" : formulaText;
    return raw.contains(TOKEN_MATERIAL) && !raw.contains(TOKEN_MATERIAL_INCL)
        ? TOKEN_MATERIAL
        : TOKEN_MATERIAL_INCL;
  }

  private String scrapTokenName(String formulaText) {
    String raw = formulaText == null ? "" : formulaText;
    return raw.contains(TOKEN_SCRAP) && !raw.contains(TOKEN_SCRAP_INCL)
        ? TOKEN_SCRAP
        : TOKEN_SCRAP_INCL;
  }

  private String normalize(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
