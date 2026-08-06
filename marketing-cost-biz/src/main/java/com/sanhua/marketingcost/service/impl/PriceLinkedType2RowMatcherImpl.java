package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.PriceLinkedType2MatchKey;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2RowMatchResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2RowMatchSummary;
import com.sanhua.marketingcost.dto.PriceLinkedType2StandardRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import com.sanhua.marketingcost.enums.PriceLinkedType2RowMatchStatus;
import com.sanhua.marketingcost.service.PriceLinkedType2RowMatcher;
import com.sanhua.marketingcost.service.PriceLinkedType2TextNormalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 类型 2 以 Sheet1 为主的分级匹配器。
 *
 * <p>匹配顺序固定为：料号唯一、同料号供应商精确、同料号供应商简称模糊、
 * 已成功 Sheet1 行的同供应商代码、ImportData 同供应商代码。ImportData
 * 中没有被 Sheet1 使用的行不形成错误。
 */
@Component
public class PriceLinkedType2RowMatcherImpl implements PriceLinkedType2RowMatcher {

  private final PriceLinkedType2TextNormalizer textNormalizer;

  public PriceLinkedType2RowMatcherImpl(
      PriceLinkedType2TextNormalizer textNormalizer) {
    this.textNormalizer = textNormalizer;
  }

  @Override
  public PriceLinkedType2RowMatchSummary match(
      PriceLinkedType2WorkbookParseResult workbook) {
    if (workbook == null) {
      throw new IllegalArgumentException("类型 2 工作簿解析结果不能为空");
    }

    List<PriceLinkedType2RowMatchResult> results = new ArrayList<>();
    Map<PriceLinkedType2MatchKey, List<PriceLinkedType2ProductRow>> businessGroups =
        new LinkedHashMap<>();
    Map<String, List<PriceLinkedType2StandardRow>> standardByMaterial =
        indexStandardsByMaterial(workbook.getStandardRows());

    for (PriceLinkedType2ProductRow row : workbook.getProductRows()) {
      PriceLinkedType2MatchKey key = key(row.getMaterialCode(), row.getSupplierName());
      if (isInvalidBusinessKey(key)) {
        results.add(invalidBusinessResult(key, row));
      } else {
        businessGroups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
      }
    }

    List<PendingRow> pending = new ArrayList<>();
    List<MatchedProfile> directProfiles = new ArrayList<>();
    for (Map.Entry<PriceLinkedType2MatchKey, List<PriceLinkedType2ProductRow>> entry
        : businessGroups.entrySet()) {
      PriceLinkedType2MatchKey key = entry.getKey();
      List<PriceLinkedType2ProductRow> businessRows = entry.getValue();
      List<PriceLinkedType2StandardRow> materialCandidates =
          standardByMaterial.getOrDefault(key.getMaterialCode(), List.of());

      if (businessRows.size() > 1) {
        boolean bothDuplicate = materialCandidates.size() > 1;
        results.add(new PriceLinkedType2RowMatchResult(
            key,
            bothDuplicate
                ? PriceLinkedType2RowMatchStatus.BOTH_DUPLICATE
                : PriceLinkedType2RowMatchStatus.BUSINESS_DUPLICATE,
            businessRows,
            materialCandidates,
            bothDuplicate
                ? "Sheet1 和 ImportData 均存在重复，Sheet1行=" + rows(businessRows)
                    + "，ImportData行=" + standardRows(materialCandidates)
                : "Sheet1 匹配键重复，行号=" + rows(businessRows)));
        continue;
      }

      PriceLinkedType2ProductRow businessRow = businessRows.getFirst();
      Selection direct = selectByMaterial(businessRow, materialCandidates);
      if (direct.row() != null) {
        PriceLinkedType2RowMatchResult matched = matched(
            key,
            businessRow,
            direct.row(),
            PriceLinkedType2RowMatchStatus.MATCHED,
            direct.message());
        results.add(matched);
        directProfiles.add(new MatchedProfile(businessRow, direct.row()));
      } else {
        pending.add(new PendingRow(key, businessRow, materialCandidates, direct.ambiguous()));
      }
    }

    for (PendingRow unresolved : pending) {
      Selection fallback = selectSupplierFallback(
          unresolved.businessRow(),
          directProfiles,
          workbook.getStandardRows());
      if (fallback.row() != null) {
        results.add(matched(
            unresolved.key(),
            unresolved.businessRow(),
            fallback.row(),
            PriceLinkedType2RowMatchStatus.MATCHED_SUPPLIER_FALLBACK,
            "ImportData无可直接使用的料号行，按Sheet1同供应商取得供应商代码和公共字段；"
                + fallback.message()));
        continue;
      }

      String reason = unresolved.ambiguous() || fallback.ambiguous()
          ? "料号和供应商名称匹配到多个不同供应商代码，无法自动确定"
          : "料号和供应商名称均无法取得供应商代码";
      results.add(new PriceLinkedType2RowMatchResult(
          unresolved.key(),
          unresolved.ambiguous()
              ? PriceLinkedType2RowMatchStatus.STANDARD_DUPLICATE
              : PriceLinkedType2RowMatchStatus.MISSING_SUPPLIER_CODE,
          List.of(unresolved.businessRow()),
          unresolved.materialCandidates(),
          reason + "，Sheet1行=" + unresolved.businessRow().getSourceRowNumber()
              + (unresolved.materialCandidates().isEmpty()
                  ? ""
                  : "，ImportData候选行="
                      + standardRows(unresolved.materialCandidates()))));
    }
    return new PriceLinkedType2RowMatchSummary(results);
  }

  private Selection selectByMaterial(
      PriceLinkedType2ProductRow businessRow,
      List<PriceLinkedType2StandardRow> candidates) {
    if (candidates.isEmpty()) {
      return Selection.none(false);
    }
    if (candidates.size() == 1) {
      PriceLinkedType2StandardRow candidate = candidates.getFirst();
      return StringUtils.hasText(candidate.getSupplierCode())
          ? Selection.found(candidate, "料号唯一匹配")
          : Selection.none(false);
    }

    List<PriceLinkedType2StandardRow> exact =
        exactSupplierCandidates(businessRow.getSupplierName(), candidates);
    Selection exactSelection = selectUniqueSupplierCode(
        exact, "同料号按供应商名称精确匹配");
    if (exactSelection.row() != null || exactSelection.ambiguous()) {
      return exactSelection;
    }

    List<PriceLinkedType2StandardRow> fuzzy =
        fuzzySupplierCandidates(businessRow.getSupplierName(), candidates);
    Selection fuzzySelection = selectUniqueSupplierCode(
        fuzzy, "同料号按供应商简称唯一模糊匹配");
    if (fuzzySelection.row() != null || fuzzySelection.ambiguous()) {
      return fuzzySelection;
    }
    return Selection.none(true);
  }

  private Selection selectSupplierFallback(
      PriceLinkedType2ProductRow businessRow,
      List<MatchedProfile> directProfiles,
      List<PriceLinkedType2StandardRow> allStandards) {
    List<PriceLinkedType2StandardRow> exactProfiles = directProfiles.stream()
        .filter(profile -> sameSupplier(
            businessRow.getSupplierName(), profile.businessRow().getSupplierName()))
        .map(MatchedProfile::standardRow)
        .toList();
    Selection result = selectUniqueSupplierCode(
        exactProfiles, "复用Sheet1已匹配的同名供应商代码");
    if (result.row() != null || result.ambiguous()) {
      return result;
    }

    List<PriceLinkedType2StandardRow> fuzzyProfiles = directProfiles.stream()
        .filter(profile -> fuzzySupplier(
            businessRow.getSupplierName(), profile.businessRow().getSupplierName()))
        .map(MatchedProfile::standardRow)
        .toList();
    result = selectUniqueSupplierCode(
        fuzzyProfiles, "复用Sheet1已匹配的供应商简称代码");
    if (result.row() != null || result.ambiguous()) {
      return result;
    }

    result = selectUniqueSupplierCode(
        exactSupplierCandidates(businessRow.getSupplierName(), allStandards),
        "按供应商名称从ImportData取得公共字段");
    if (result.row() != null || result.ambiguous()) {
      return result;
    }
    return selectUniqueSupplierCode(
        fuzzySupplierCandidates(businessRow.getSupplierName(), allStandards),
        "按供应商简称从ImportData取得公共字段");
  }

  private Selection selectUniqueSupplierCode(
      List<PriceLinkedType2StandardRow> candidates, String message) {
    List<PriceLinkedType2StandardRow> withCode = candidates.stream()
        .filter(row -> StringUtils.hasText(row.getSupplierCode()))
        .toList();
    Set<String> codes = new LinkedHashSet<>();
    for (PriceLinkedType2StandardRow row : withCode) {
      codes.add(textNormalizer.normalize(row.getSupplierCode()));
    }
    if (codes.size() == 1) {
      return Selection.found(withCode.getFirst(), message);
    }
    return Selection.none(codes.size() > 1);
  }

  private List<PriceLinkedType2StandardRow> exactSupplierCandidates(
      String supplierName, List<PriceLinkedType2StandardRow> candidates) {
    String normalized = textNormalizer.normalize(supplierName);
    if (!StringUtils.hasText(normalized)) {
      return List.of();
    }
    return candidates.stream()
        .filter(row -> normalized.equals(textNormalizer.normalize(row.getSupplierName())))
        .toList();
  }

  private List<PriceLinkedType2StandardRow> fuzzySupplierCandidates(
      String supplierName, List<PriceLinkedType2StandardRow> candidates) {
    return candidates.stream()
        .filter(row -> fuzzySupplier(supplierName, row.getSupplierName()))
        .toList();
  }

  private boolean sameSupplier(String left, String right) {
    String normalizedLeft = textNormalizer.normalize(left);
    return StringUtils.hasText(normalizedLeft)
        && normalizedLeft.equals(textNormalizer.normalize(right));
  }

  private boolean fuzzySupplier(String left, String right) {
    String normalizedLeft = compactSupplier(left);
    String normalizedRight = compactSupplier(right);
    return StringUtils.hasText(normalizedLeft)
        && StringUtils.hasText(normalizedRight)
        && (normalizedLeft.contains(normalizedRight)
            || normalizedRight.contains(normalizedLeft));
  }

  private String compactSupplier(String value) {
    return textNormalizer.normalize(value).replace(" ", "");
  }

  private Map<String, List<PriceLinkedType2StandardRow>> indexStandardsByMaterial(
      List<PriceLinkedType2StandardRow> standards) {
    Map<String, List<PriceLinkedType2StandardRow>> result = new LinkedHashMap<>();
    for (PriceLinkedType2StandardRow row : standards) {
      String materialCode = textNormalizer.normalize(row.getMaterialCode());
      if (StringUtils.hasText(materialCode)) {
        result.computeIfAbsent(materialCode, ignored -> new ArrayList<>()).add(row);
      }
    }
    return result;
  }

  private PriceLinkedType2RowMatchResult matched(
      PriceLinkedType2MatchKey key,
      PriceLinkedType2ProductRow businessRow,
      PriceLinkedType2StandardRow standardRow,
      PriceLinkedType2RowMatchStatus status,
      String message) {
    return new PriceLinkedType2RowMatchResult(
        key, status, List.of(businessRow), List.of(standardRow), message);
  }

  private PriceLinkedType2RowMatchResult invalidBusinessResult(
      PriceLinkedType2MatchKey key, PriceLinkedType2ProductRow row) {
    return new PriceLinkedType2RowMatchResult(
        key,
        PriceLinkedType2RowMatchStatus.INVALID_BUSINESS_KEY,
        List.of(row),
        List.of(),
        "Sheet1行缺少料号或供应商名称，Sheet="
            + row.getSourceSheetName() + "，行号=" + row.getSourceRowNumber());
  }

  private PriceLinkedType2MatchKey key(String materialCode, String supplierName) {
    return new PriceLinkedType2MatchKey(
        textNormalizer.normalize(materialCode),
        textNormalizer.normalize(supplierName));
  }

  private boolean isInvalidBusinessKey(PriceLinkedType2MatchKey key) {
    return !StringUtils.hasText(key.getMaterialCode())
        || !StringUtils.hasText(key.getSupplierName());
  }

  private List<Integer> rows(List<PriceLinkedType2ProductRow> rows) {
    return rows.stream().map(PriceLinkedType2ProductRow::getSourceRowNumber).toList();
  }

  private List<Integer> standardRows(List<PriceLinkedType2StandardRow> rows) {
    return rows.stream().map(PriceLinkedType2StandardRow::getSourceRowNumber).toList();
  }

  private record Selection(
      PriceLinkedType2StandardRow row, boolean ambiguous, String message) {

    private static Selection found(PriceLinkedType2StandardRow row, String message) {
      return new Selection(row, false, message);
    }

    private static Selection none(boolean ambiguous) {
      return new Selection(null, ambiguous, null);
    }
  }

  private record PendingRow(
      PriceLinkedType2MatchKey key,
      PriceLinkedType2ProductRow businessRow,
      List<PriceLinkedType2StandardRow> materialCandidates,
      boolean ambiguous) {
  }

  private record MatchedProfile(
      PriceLinkedType2ProductRow businessRow,
      PriceLinkedType2StandardRow standardRow) {
  }
}
