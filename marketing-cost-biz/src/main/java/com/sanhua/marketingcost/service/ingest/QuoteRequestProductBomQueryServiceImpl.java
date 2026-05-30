package com.sanhua.marketingcost.service.ingest;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestProductBomListItemResponse;
import com.sanhua.marketingcost.enums.QuoteBomStatusCode;
import com.sanhua.marketingcost.mapper.QuoteRequestProductBomMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QuoteRequestProductBomQueryServiceImpl implements QuoteRequestProductBomQueryService {
  private static final int DEFAULT_PAGE_NO = 1;
  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 200;

  private final QuoteRequestProductBomMapper quoteRequestProductBomMapper;

  public QuoteRequestProductBomQueryServiceImpl(
      QuoteRequestProductBomMapper quoteRequestProductBomMapper) {
    this.quoteRequestProductBomMapper = quoteRequestProductBomMapper;
  }

  @Override
  public PageResult<QuoteRequestProductBomListItemResponse> pageProductBomRows(
      Integer pageNo,
      Integer pageSize,
      String oaNo,
      String productCode,
      String customer,
      String productType,
      String packageMethod,
      String businessUnit,
      String technicianName,
      Boolean needTechnicianTask,
      String reviewStatus,
      List<String> bomStatuses) {
    int normalizedPageNo = normalizePageNo(pageNo);
    int normalizedPageSize = normalizePageSize(pageSize);
    List<String> normalizedStatuses = normalizeStatuses(bomStatuses);
    long total =
        quoteRequestProductBomMapper.countProductBomRows(
            trimToNull(oaNo),
            trimToNull(productCode),
            trimToNull(customer),
            trimToNull(productType),
            trimToNull(packageMethod),
            trimToNull(businessUnit),
            trimToNull(technicianName),
            needTechnicianTask,
            trimToNull(reviewStatus),
            normalizedStatuses);
    if (total == 0) {
      return new PageResult<>(List.of(), 0L);
    }
    List<QuoteRequestProductBomListItemResponse> rows =
        quoteRequestProductBomMapper.selectProductBomRows(
            trimToNull(oaNo),
            trimToNull(productCode),
            trimToNull(customer),
            trimToNull(productType),
            trimToNull(packageMethod),
            trimToNull(businessUnit),
            trimToNull(technicianName),
            needTechnicianTask,
            trimToNull(reviewStatus),
            normalizedStatuses,
            normalizedPageSize,
            (normalizedPageNo - 1) * normalizedPageSize);
    for (QuoteRequestProductBomListItemResponse row : rows) {
      enrichStatus(row);
    }
    return new PageResult<>(rows, total);
  }

  private void enrichStatus(QuoteRequestProductBomListItemResponse row) {
    String status = trimToNull(row.getBomStatus());
    if (status == null) {
      status = QuoteBomStatusCode.NOT_CHECKED.getCode();
      row.setBomStatus(status);
    }
    QuoteBomStatusCode statusCode = statusCode(status);
    row.setBomStatusLabel(statusCode == null ? status : statusCode.getLabel());
    row.setCanCostRun(isCostReadyBomStatus(status));
    if (!StringUtils.hasText(row.getOaTodoPushStatus())) {
      row.setOaTodoPushStatus(row.getTaskId() == null ? "NOT_CREATED" : "NOT_PUSHED");
    }
  }

  private boolean isCostReadyBomStatus(String bomStatus) {
    return QuoteBomStatusCode.SYNCED.getCode().equals(bomStatus)
        || QuoteBomStatusCode.REUSED_CURRENT_MONTH.getCode().equals(bomStatus)
        || QuoteBomStatusCode.MANUAL_ENTERED.getCode().equals(bomStatus);
  }

  private QuoteBomStatusCode statusCode(String status) {
    for (QuoteBomStatusCode code : QuoteBomStatusCode.values()) {
      if (code.getCode().equals(status)) {
        return code;
      }
    }
    return null;
  }

  private List<String> normalizeStatuses(List<String> statuses) {
    if (statuses == null || statuses.isEmpty()) {
      return List.of();
    }
    List<String> normalized = new ArrayList<>();
    for (String status : statuses) {
      String value = trimToNull(status);
      if (value != null && !normalized.contains(value)) {
        normalized.add(value);
      }
    }
    return normalized;
  }

  private int normalizePageNo(Integer pageNo) {
    return pageNo == null || pageNo < 1 ? DEFAULT_PAGE_NO : pageNo;
  }

  private int normalizePageSize(Integer pageSize) {
    if (pageSize == null || pageSize < 1) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(pageSize, MAX_PAGE_SIZE);
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
