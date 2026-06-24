package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmBatch;
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmItem;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmBatchMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmItemMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QuotePriceTypeConfirmationInvalidationService {
  private static final int CHUNK_SIZE = 500;
  private static final String COST_STATUS_CONFIRMED = "CONFIRMED";
  private static final String REASON_MATERIAL_TYPE_CHANGED = "价格类型主数据已变化，请重新确认价格类型";

  private final QuotePriceTypeConfirmBatchMapper batchMapper;
  private final QuotePriceTypeConfirmItemMapper itemMapper;
  private final QuoteCostRunVersionMapper costRunVersionMapper;

  public QuotePriceTypeConfirmationInvalidationService(
      QuotePriceTypeConfirmBatchMapper batchMapper,
      QuotePriceTypeConfirmItemMapper itemMapper,
      QuoteCostRunVersionMapper costRunVersionMapper) {
    this.batchMapper = batchMapper;
    this.itemMapper = itemMapper;
    this.costRunVersionMapper = costRunVersionMapper;
  }

  public int invalidateScope(
      String oaNo, Long oaFormItemId, String productCode, String periodMonth) {
    if (!StringUtils.hasText(oaNo)
        || oaFormItemId == null
        || !StringUtils.hasText(productCode)
        || !StringUtils.hasText(periodMonth)) {
      return 0;
    }
    List<QuotePriceTypeConfirmBatch> batches =
        batchMapper.selectList(
            Wrappers.<QuotePriceTypeConfirmBatch>lambdaQuery()
                .eq(QuotePriceTypeConfirmBatch::getOaNo, oaNo.trim())
                .eq(QuotePriceTypeConfirmBatch::getOaFormItemId, oaFormItemId)
                .eq(QuotePriceTypeConfirmBatch::getProductCode, productCode.trim())
                .eq(QuotePriceTypeConfirmBatch::getPeriodMonth, periodMonth.trim())
                .eq(QuotePriceTypeConfirmBatch::getStatus, QuotePriceTypeConfirmBatch.STATUS_CONFIRMED));
    return markConfirmNosStale(collectBatchConfirmNos(batches), LocalDateTime.now());
  }

  public int invalidateByMaterialPriceTypeChanges(Collection<MaterialPriceType> changedRows) {
    if (changedRows == null || changedRows.isEmpty()) {
      return 0;
    }
    Map<InvalidationWindow, LinkedHashSet<String>> groupedCodes = new LinkedHashMap<>();
    for (MaterialPriceType row : changedRows) {
      if (row == null || !StringUtils.hasText(row.getMaterialCode())) {
        continue;
      }
      InvalidationWindow window =
          new InvalidationWindow(
              trimToNull(row.getBusinessUnitType()),
              lowerBoundMonth(row),
              month(row.getEffectiveTo()));
      groupedCodes
          .computeIfAbsent(window, ignored -> new LinkedHashSet<>())
          .add(row.getMaterialCode().trim());
    }
    int affected = 0;
    for (Map.Entry<InvalidationWindow, LinkedHashSet<String>> entry : groupedCodes.entrySet()) {
      for (List<String> codes : chunks(new ArrayList<>(entry.getValue()))) {
        affected += invalidateMaterialCodes(codes, entry.getKey());
      }
    }
    return affected;
  }

  private int invalidateMaterialCodes(List<String> materialCodes, InvalidationWindow window) {
    if (materialCodes == null || materialCodes.isEmpty()) {
      return 0;
    }
    var query =
        Wrappers.<QuotePriceTypeConfirmItem>lambdaQuery()
            .select(QuotePriceTypeConfirmItem::getConfirmNo)
            .in(QuotePriceTypeConfirmItem::getMaterialCode, materialCodes)
            .eq(QuotePriceTypeConfirmItem::getStatus, QuotePriceTypeConfirmItem.STATUS_CONFIRMED);
    if (StringUtils.hasText(window.businessUnitType())) {
      query.and(
          q ->
              q.eq(QuotePriceTypeConfirmItem::getBusinessUnitType, window.businessUnitType())
                  .or()
                  .isNull(QuotePriceTypeConfirmItem::getBusinessUnitType)
                  .or()
                  .eq(QuotePriceTypeConfirmItem::getBusinessUnitType, ""));
    }
    if (StringUtils.hasText(window.fromMonth())) {
      query.ge(QuotePriceTypeConfirmItem::getPeriodMonth, window.fromMonth());
    }
    if (StringUtils.hasText(window.toMonth())) {
      query.le(QuotePriceTypeConfirmItem::getPeriodMonth, window.toMonth());
    }
    List<QuotePriceTypeConfirmItem> items = itemMapper.selectList(query);
    return markConfirmNosStale(collectItemConfirmNos(items), LocalDateTime.now());
  }

  private int markConfirmNosStale(Set<String> confirmNos, LocalDateTime now) {
    if (confirmNos == null || confirmNos.isEmpty()) {
      return 0;
    }
    Set<String> mutableConfirmNos = new LinkedHashSet<>(confirmNos);
    mutableConfirmNos.removeAll(confirmedCostVersionConfirmNos(mutableConfirmNos));
    if (mutableConfirmNos.isEmpty()) {
      return 0;
    }
    int affected = 0;
    for (List<String> chunk : chunks(new ArrayList<>(mutableConfirmNos))) {
      affected +=
          batchMapper.update(
              null,
              Wrappers.<QuotePriceTypeConfirmBatch>update()
                  .set("status", QuotePriceTypeConfirmBatch.STATUS_STALE)
                  .set("message", REASON_MATERIAL_TYPE_CHANGED)
                  .set("updated_at", now)
                  .in("confirm_no", chunk)
                  .eq("status", QuotePriceTypeConfirmBatch.STATUS_CONFIRMED));
    }
    return affected;
  }

  private Set<String> confirmedCostVersionConfirmNos(Set<String> confirmNos) {
    Set<String> result = new LinkedHashSet<>();
    if (confirmNos == null || confirmNos.isEmpty()) {
      return result;
    }
    for (List<String> chunk : chunks(new ArrayList<>(confirmNos))) {
      List<QuoteCostRunVersion> versions =
          costRunVersionMapper.selectList(
              Wrappers.<QuoteCostRunVersion>lambdaQuery()
                  .select(QuoteCostRunVersion::getPriceTypeConfirmNo)
                  .in(QuoteCostRunVersion::getPriceTypeConfirmNo, chunk)
                  .eq(QuoteCostRunVersion::getStatus, COST_STATUS_CONFIRMED));
      if (versions == null) {
        continue;
      }
      for (QuoteCostRunVersion version : versions) {
        String confirmNo = trimToNull(version.getPriceTypeConfirmNo());
        if (confirmNo != null) {
          result.add(confirmNo);
        }
      }
    }
    return result;
  }

  private Set<String> collectItemConfirmNos(List<QuotePriceTypeConfirmItem> items) {
    Set<String> confirmNos = new LinkedHashSet<>();
    if (items == null) {
      return confirmNos;
    }
    for (QuotePriceTypeConfirmItem item : items) {
      String confirmNo = trimToNull(item.getConfirmNo());
      if (confirmNo != null) {
        confirmNos.add(confirmNo);
      }
    }
    return confirmNos;
  }

  private Set<String> collectBatchConfirmNos(List<QuotePriceTypeConfirmBatch> batches) {
    Set<String> confirmNos = new LinkedHashSet<>();
    if (batches == null) {
      return confirmNos;
    }
    for (QuotePriceTypeConfirmBatch batch : batches) {
      String confirmNo = trimToNull(batch.getConfirmNo());
      if (confirmNo != null) {
        confirmNos.add(confirmNo);
      }
    }
    return confirmNos;
  }

  private String lowerBoundMonth(MaterialPriceType row) {
    String periodMonth = normalizeMonth(row.getPeriod());
    if (periodMonth != null) {
      return periodMonth;
    }
    return month(row.getEffectiveFrom());
  }

  private String month(LocalDate date) {
    return date == null ? null : YearMonth.from(date).toString();
  }

  private String normalizeMonth(String value) {
    String text = trimToNull(value);
    if (text == null) {
      return null;
    }
    try {
      return YearMonth.parse(text).toString();
    } catch (DateTimeParseException ignored) {
      // fall through
    }
    try {
      return YearMonth.from(LocalDate.parse(text)).toString();
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  private <T> List<List<T>> chunks(List<T> values) {
    List<List<T>> chunks = new ArrayList<>();
    if (values == null || values.isEmpty()) {
      return chunks;
    }
    for (int i = 0; i < values.size(); i += CHUNK_SIZE) {
      chunks.add(values.subList(i, Math.min(i + CHUNK_SIZE, values.size())));
    }
    return chunks;
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private record InvalidationWindow(String businessUnitType, String fromMonth, String toMonth) {}
}
