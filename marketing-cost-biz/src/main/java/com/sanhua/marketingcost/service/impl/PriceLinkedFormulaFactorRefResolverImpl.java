package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FormulaFactorRef;
import com.sanhua.marketingcost.dto.ResolvedFactorRef;
import com.sanhua.marketingcost.entity.FactorRowRef;
import com.sanhua.marketingcost.mapper.FactorRowRefMapper;
import com.sanhua.marketingcost.service.PriceLinkedFormulaFactorRefResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PriceLinkedFormulaFactorRefResolverImpl
    implements PriceLinkedFormulaFactorRefResolver {

  private final FactorRowRefMapper factorRowRefMapper;

  public PriceLinkedFormulaFactorRefResolverImpl(FactorRowRefMapper factorRowRefMapper) {
    this.factorRowRefMapper = factorRowRefMapper;
  }

  @Override
  public List<ResolvedFactorRef> resolve(Long factorUploadBatchId, List<FormulaFactorRef> refs) {
    if (refs == null || refs.isEmpty()) {
      return List.of();
    }
    if (factorUploadBatchId == null) {
      return refs.stream()
          .map(ref -> unresolved(ref, "factorUploadBatchId 必填"))
          .toList();
    }

    List<FactorRowRef> rowRefs = factorRowRefMapper.selectList(
        Wrappers.lambdaQuery(FactorRowRef.class)
            .eq(FactorRowRef::getFactorUploadBatchId, factorUploadBatchId));
    RowRefIndex index = buildIndex(rowRefs);
    List<ResolvedFactorRef> resolved = new ArrayList<>();
    for (FormulaFactorRef ref : refs) {
      resolved.add(resolveOne(ref, index));
    }
    return resolved;
  }

  private ResolvedFactorRef resolveOne(FormulaFactorRef ref, RowRefIndex index) {
    if (ref == null) {
      return unresolved(null, "公式引用为空");
    }
    if (!StringUtils.hasText(ref.getSheetName()) || ref.getRowNumber() == null) {
      return unresolved(ref, "公式引用缺少 sheetName 或 rowNumber");
    }

    FactorRowRef exact = index.bySheetAndRow().get(cellKey(ref.getSheetName(), ref.getRowNumber()));
    if (exact != null) {
      return resolved(ref, exact, null);
    }
    if (index.sheetNames().size() == 1) {
      FactorRowRef byOnlySheet = index.byRowNumber().get(ref.getRowNumber());
      if (byOnlySheet != null) {
        String warning = "公式引用 sheet 名为 " + ref.getSheetName()
            + "，本批次只有一个影响因素 sheet：" + byOnlySheet.getSourceSheetName()
            + "，已按行号匹配";
        return resolved(ref, byOnlySheet, warning);
      }
    }

    if (index.sheetNames().isEmpty()) {
      return unresolved(ref, "本批次没有影响因素行号映射，不能自动绑定");
    }
    if (index.sheetNames().size() > 1) {
      return unresolved(ref, "找不到影响因素引用：sheet=" + ref.getSheetName()
          + ", row=" + ref.getRowNumber() + "；本批次存在多个影响因素 sheet，不能盲猜");
    }
    return unresolved(ref, "找不到影响因素引用：sheet=" + ref.getSheetName()
        + ", row=" + ref.getRowNumber());
  }

  private RowRefIndex buildIndex(List<FactorRowRef> rowRefs) {
    Map<String, FactorRowRef> bySheetAndRow = new LinkedHashMap<>();
    Map<Integer, FactorRowRef> byRowNumber = new HashMap<>();
    Set<String> sheetNames = rowRefs == null ? Set.of() : rowRefs.stream()
        .map(FactorRowRef::getSourceSheetName)
        .filter(StringUtils::hasText)
        .map(this::normalize)
        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    if (rowRefs != null) {
      for (FactorRowRef rowRef : rowRefs) {
        if (rowRef == null || !StringUtils.hasText(rowRef.getSourceSheetName())
            || rowRef.getSourceRowNumber() == null) {
          continue;
        }
        bySheetAndRow.putIfAbsent(
            cellKey(rowRef.getSourceSheetName(), rowRef.getSourceRowNumber()), rowRef);
        byRowNumber.putIfAbsent(rowRef.getSourceRowNumber(), rowRef);
      }
    }
    return new RowRefIndex(bySheetAndRow, byRowNumber, sheetNames);
  }

  private ResolvedFactorRef resolved(
      FormulaFactorRef sourceRef, FactorRowRef rowRef, String warning) {
    ResolvedFactorRef result = copySource(sourceRef);
    result.setFactorIdentityId(rowRef.getFactorIdentityId());
    result.setFactorMonthlyPriceId(rowRef.getFactorMonthlyPriceId());
    result.setFactorSeqNo(rowRef.getFactorSeqNo());
    result.setShortName(rowRef.getShortName());
    result.setPriceSource(rowRef.getPriceSource());
    result.setPrice(rowRef.getPrice());
    result.setWarning(warning);
    return result;
  }

  private ResolvedFactorRef unresolved(FormulaFactorRef sourceRef, String message) {
    ResolvedFactorRef result = copySource(sourceRef);
    result.setErrorMessage(message);
    return result;
  }

  private ResolvedFactorRef copySource(FormulaFactorRef sourceRef) {
    ResolvedFactorRef result = new ResolvedFactorRef();
    if (sourceRef != null) {
      result.setWorkbookName(sourceRef.getWorkbookName());
      result.setSheetName(sourceRef.getSheetName());
      result.setColumnName(sourceRef.getColumnName());
      result.setRowNumber(sourceRef.getRowNumber());
      result.setRawRef(sourceRef.getRawRef());
    }
    return result;
  }

  private String cellKey(String sheetName, Integer rowNumber) {
    return normalize(sheetName) + "#" + rowNumber;
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }

  private record RowRefIndex(
      Map<String, FactorRowRef> bySheetAndRow,
      Map<Integer, FactorRowRef> byRowNumber,
      Set<String> sheetNames) {
  }
}
