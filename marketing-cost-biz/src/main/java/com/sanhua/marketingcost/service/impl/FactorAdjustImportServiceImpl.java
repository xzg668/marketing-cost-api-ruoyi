package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FactorAdjustExcelParseResult;
import com.sanhua.marketingcost.dto.FactorAdjustExcelParseRow;
import com.sanhua.marketingcost.dto.FactorAdjustImportRequest;
import com.sanhua.marketingcost.dto.FactorAdjustImportResponse;
import com.sanhua.marketingcost.dto.FactorAdjustPriceDto;
import com.sanhua.marketingcost.entity.FactorAdjustBatch;
import com.sanhua.marketingcost.entity.FactorAdjustPrice;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.FactorMonthlyPriceChangeLog;
import com.sanhua.marketingcost.enums.FactorAdjustPriceStatus;
import com.sanhua.marketingcost.enums.FactorAdjustSourceType;
import com.sanhua.marketingcost.enums.FactorAdjustUsageScope;
import com.sanhua.marketingcost.enums.FactorMonthlyPriceSourceTag;
import com.sanhua.marketingcost.mapper.FactorAdjustBatchMapper;
import com.sanhua.marketingcost.mapper.FactorAdjustPriceMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceChangeLogMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.service.FactorAdjustExcelParseService;
import com.sanhua.marketingcost.service.FactorAdjustImportService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FactorAdjustImportServiceImpl implements FactorAdjustImportService {

  private static final DateTimeFormatter BATCH_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

  private final FactorAdjustExcelParseService parseService;
  private final FactorAdjustBatchMapper adjustBatchMapper;
  private final FactorAdjustPriceMapper adjustPriceMapper;
  private final FactorMonthlyPriceMapper monthlyPriceMapper;
  private final FactorMonthlyPriceChangeLogMapper monthlyPriceChangeLogMapper;

  public FactorAdjustImportServiceImpl(
      FactorAdjustExcelParseService parseService,
      FactorAdjustBatchMapper adjustBatchMapper,
      FactorAdjustPriceMapper adjustPriceMapper,
      FactorMonthlyPriceMapper monthlyPriceMapper,
      FactorMonthlyPriceChangeLogMapper monthlyPriceChangeLogMapper) {
    this.parseService = parseService;
    this.adjustBatchMapper = adjustBatchMapper;
    this.adjustPriceMapper = adjustPriceMapper;
    this.monthlyPriceMapper = monthlyPriceMapper;
    this.monthlyPriceChangeLogMapper = monthlyPriceChangeLogMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FactorAdjustImportResponse importAdjustExcel(
      InputStream input, String sourceFileName, FactorAdjustImportRequest request, String operator) {
    validateRequest(input, request);
    byte[] bytes = readAllBytes(input);
    String pricingMonth = normalize(request.getPricingMonth());
    String businessUnitType = normalize(request.getBusinessUnitType());
    String usageScope = normalizeUsageScope(request.getUsageScope());
    String normalizedOperator = normalizeOperator(operator);

    FactorAdjustExcelParseResult parseResult = parseService.parse(
        new ByteArrayInputStream(bytes), sourceFileName, pricingMonth, businessUnitType);

    FactorAdjustBatch batch = createBatch(
        parseResult, sourceFileName, bytes, pricingMonth, businessUnitType,
        usageScope, request.getRemark(), normalizedOperator);
    adjustBatchMapper.insert(batch);

    ImportCounters counters = new ImportCounters();
    FactorAdjustImportResponse response = new FactorAdjustImportResponse();
    response.setAdjustBatchId(batch.getId());
    response.setAdjustBatchNo(batch.getAdjustBatchNo());
    response.setPricingMonth(pricingMonth);
    response.setBusinessUnitType(businessUnitType);
    response.setUsageScope(usageScope);

    for (FactorAdjustExcelParseRow row : parseResult.getRows()) {
      FactorAdjustPrice detail = buildDetail(
          batch.getId(), row, pricingMonth, usageScope, normalizedOperator, counters);
      adjustPriceMapper.insert(detail);
      response.getRows().add(FactorAdjustPriceDto.fromEntity(detail));
    }

    fillBatchCounts(batch, counters);
    adjustBatchMapper.updateById(batch);
    fillResponseCounts(response, batch);
    return response;
  }

  private FactorAdjustPrice buildDetail(
      Long adjustBatchId,
      FactorAdjustExcelParseRow row,
      String pricingMonth,
      String usageScope,
      String operator,
      ImportCounters counters) {
    FactorAdjustPrice detail = baseDetail(adjustBatchId, row, usageScope);
    if (row == null || !"MATCHED".equalsIgnoreCase(row.getStatus())) {
      detail.setStatus(FactorAdjustPriceStatus.FAILED.getCode());
      detail.setFailReason(row == null ? "解析行为空" : row.getFailReason());
      counters.failed++;
      return detail;
    }

    FactorMonthlyPrice monthlyPrice = findMonthlyPrice(row, pricingMonth);
    BigDecimal oldPrice = oldPrice(row, monthlyPrice);
    BigDecimal newPrice = normalizePrice(row.getPrice());
    detail.setOriginalPrice(oldPrice);
    detail.setAdjustedPrice(newPrice);
    detail.setPriceDelta(delta(oldPrice, newPrice));
    detail.setChangeRate(changeRate(oldPrice, newPrice));
    boolean changed = !samePrice(oldPrice, newPrice);
    detail.setStatus(changed
        ? FactorAdjustPriceStatus.CHANGED.getCode()
        : FactorAdjustPriceStatus.NO_CHANGE.getCode());
    if (changed) {
      counters.changed++;
    } else {
      counters.noChange++;
    }

    if (FactorAdjustUsageScope.REPRICE_AND_DAILY.getCode().equals(usageScope)) {
      // 只有“同时作为日常报价生效价”才写 lp_factor_monthly_price，避免专项调价污染日常报价。
      FactorMonthlyPrice dailyPrice =
          upsertDailyMonthlyPrice(row, monthlyPrice, pricingMonth, newPrice, operator, adjustBatchId);
      detail.setFactorMonthlyPriceId(dailyPrice.getId());
    }
    return detail;
  }

  private FactorAdjustPrice baseDetail(
      Long adjustBatchId, FactorAdjustExcelParseRow row, String usageScope) {
    FactorAdjustPrice detail = new FactorAdjustPrice();
    LocalDateTime now = LocalDateTime.now();
    detail.setAdjustBatchId(adjustBatchId);
    detail.setFactorIdentityId(row == null ? null : row.getFactorIdentityId());
    detail.setFactorMonthlyPriceId(row == null ? null : row.getFactorMonthlyPriceId());
    detail.setFactorSeqNo(row == null ? null : normalize(row.getFactorSeqNo()));
    detail.setFactorName(row == null ? null : normalize(row.getFactorName()));
    detail.setShortName(row == null ? null : normalize(row.getShortName()));
    detail.setPriceSource(row == null ? null : normalize(row.getPriceSource()));
    detail.setUnit(row == null ? null : normalize(row.getUnit()));
    detail.setAdjustedPrice(row == null ? null : normalizePrice(row.getPrice()));
    detail.setMatchMethod(row == null ? null : row.getMatchMethod());
    detail.setApplyToDaily(FactorAdjustUsageScope.REPRICE_AND_DAILY.getCode().equals(usageScope) ? 1 : 0);
    detail.setSourceSheetName(row == null ? null : normalize(row.getSourceSheetName()));
    detail.setSourceRowNumber(row == null ? null : row.getSourceRowNumber());
    detail.setCreatedAt(now);
    detail.setUpdatedAt(now);
    detail.setDeleted(0);
    return detail;
  }

  private FactorMonthlyPrice upsertDailyMonthlyPrice(
      FactorAdjustExcelParseRow row,
      FactorMonthlyPrice existing,
      String pricingMonth,
      BigDecimal newPrice,
      String operator,
      Long adjustBatchId) {
    LocalDateTime now = LocalDateTime.now();
    FactorMonthlyPrice monthlyPrice = existing;
    BigDecimal oldPrice = monthlyPrice == null ? null : normalizePrice(monthlyPrice.getPrice());
    Long oldLatestAdjustBatchId = monthlyPrice == null ? null : monthlyPrice.getLatestAdjustBatchId();
    if (monthlyPrice == null) {
      monthlyPrice = new FactorMonthlyPrice();
      monthlyPrice.setFactorIdentityId(row.getFactorIdentityId());
      monthlyPrice.setPriceMonth(pricingMonth);
      monthlyPrice.setTaxIncluded(1);
      monthlyPrice.setStatus("ACTIVE");
      monthlyPrice.setCreatedBy(operator);
      monthlyPrice.setCreatedAt(now);
    }
    monthlyPrice.setPrice(newPrice);
    monthlyPrice.setLatestAdjustBatchId(adjustBatchId);
    monthlyPrice.setLatestAdjustSourceType(FactorAdjustSourceType.ADJUST_EXCEL_IMPORT.getCode());
    monthlyPrice.setLatestAdjustedBy(operator);
    monthlyPrice.setLatestAdjustedAt(now);
    monthlyPrice.setSourceTag(FactorMonthlyPriceSourceTag.ADJUST_IMPORT.getCode());
    monthlyPrice.setUpdatedBy(operator);
    monthlyPrice.setUpdatedAt(now);
    if (monthlyPrice.getId() == null) {
      monthlyPriceMapper.insert(monthlyPrice);
    } else if (!samePrice(oldPrice, newPrice)
        || oldLatestAdjustBatchId == null
        || !adjustBatchId.equals(oldLatestAdjustBatchId)) {
      monthlyPriceMapper.updateById(monthlyPrice);
    }
    insertDailyChangeLog(monthlyPrice, oldPrice, newPrice, adjustBatchId, operator);
    return monthlyPrice;
  }

  private void insertDailyChangeLog(
      FactorMonthlyPrice monthlyPrice,
      BigDecimal oldPrice,
      BigDecimal newPrice,
      Long adjustBatchId,
      String operator) {
    FactorMonthlyPriceChangeLog log = new FactorMonthlyPriceChangeLog();
    log.setFactorMonthlyPriceId(monthlyPrice.getId());
    log.setFactorIdentityId(monthlyPrice.getFactorIdentityId());
    log.setPriceMonth(monthlyPrice.getPriceMonth());
    log.setOldPrice(oldPrice);
    log.setNewPrice(newPrice);
    log.setChangeType(samePrice(oldPrice, newPrice) ? "NO_CHANGE" : "ADJUST_IMPORT");
    log.setSourceUploadBatchId(null);
    log.setAdjustBatchId(adjustBatchId);
    log.setSourceType(FactorMonthlyPriceSourceTag.ADJUST_IMPORT.getCode());
    log.setChangedBy(operator);
    log.setRemark("月度调价导入同步为日常报价生效价");
    log.setCreatedAt(LocalDateTime.now());
    monthlyPriceChangeLogMapper.insert(log);
  }

  private FactorMonthlyPrice findMonthlyPrice(FactorAdjustExcelParseRow row, String pricingMonth) {
    if (row == null) {
      return null;
    }
    if (row.getFactorMonthlyPriceId() != null) {
      FactorMonthlyPrice monthlyPrice =
          monthlyPriceMapper.selectById(row.getFactorMonthlyPriceId());
      if (monthlyPrice != null) {
        return monthlyPrice;
      }
    }
    if (row.getFactorIdentityId() == null) {
      return null;
    }
    return monthlyPriceMapper.selectOne(
        Wrappers.lambdaQuery(FactorMonthlyPrice.class)
            .eq(FactorMonthlyPrice::getFactorIdentityId, row.getFactorIdentityId())
            .eq(FactorMonthlyPrice::getPriceMonth, pricingMonth)
            .last("LIMIT 1"));
  }

  private FactorAdjustBatch createBatch(
      FactorAdjustExcelParseResult parseResult,
      String sourceFileName,
      byte[] bytes,
      String pricingMonth,
      String businessUnitType,
      String usageScope,
      String remark,
      String operator) {
    LocalDateTime now = LocalDateTime.now();
    FactorAdjustBatch batch = new FactorAdjustBatch();
    batch.setAdjustBatchNo(nextBatchNo());
    batch.setPricingMonth(pricingMonth);
    batch.setBusinessUnitType(businessUnitType);
    batch.setUsageScope(usageScope);
    batch.setSourceType(FactorAdjustSourceType.ADJUST_EXCEL_IMPORT.getCode());
    batch.setSourceFileName(normalize(sourceFileName));
    batch.setFileSha256(sha256(bytes));
    batch.setContentHash(contentHash(parseResult, usageScope));
    batch.setStatus("PENDING");
    batch.setUploadedBy(operator);
    batch.setUploadedAt(now);
    batch.setRemark(StringUtils.hasText(remark) ? remark.trim() : null);
    batch.setCreatedAt(now);
    batch.setUpdatedAt(now);
    batch.setDeleted(0);
    return batch;
  }

  private void fillBatchCounts(FactorAdjustBatch batch, ImportCounters counters) {
    batch.setTotalCount(counters.total());
    batch.setChangedCount(counters.changed);
    batch.setNoChangeCount(counters.noChange);
    batch.setSkippedCount(counters.skipped);
    batch.setFailedCount(counters.failed);
    batch.setStatus(resolveStatus(counters));
    batch.setUpdatedAt(LocalDateTime.now());
  }

  private void fillResponseCounts(FactorAdjustImportResponse response, FactorAdjustBatch batch) {
    response.setTotalCount(batch.getTotalCount());
    response.setChangedCount(batch.getChangedCount());
    response.setNoChangeCount(batch.getNoChangeCount());
    response.setSkippedCount(batch.getSkippedCount());
    response.setFailedCount(batch.getFailedCount());
    response.setStatus(batch.getStatus());
  }

  private String resolveStatus(ImportCounters counters) {
    if (counters.total() == 0 || counters.failed == counters.total()) {
      return "FAILED";
    }
    return counters.failed > 0 || counters.skipped > 0 ? "PARTIAL_SUCCESS" : "SUCCESS";
  }

  private BigDecimal oldPrice(FactorAdjustExcelParseRow row, FactorMonthlyPrice monthlyPrice) {
    if (monthlyPrice != null && monthlyPrice.getPrice() != null) {
      return normalizePrice(monthlyPrice.getPrice());
    }
    return row == null ? null : normalizePrice(row.getOriginalPrice());
  }

  private BigDecimal delta(BigDecimal oldPrice, BigDecimal newPrice) {
    if (newPrice == null) {
      return null;
    }
    return oldPrice == null ? newPrice : newPrice.subtract(oldPrice);
  }

  private BigDecimal changeRate(BigDecimal oldPrice, BigDecimal newPrice) {
    if (oldPrice == null || BigDecimal.ZERO.compareTo(oldPrice) == 0 || newPrice == null) {
      return null;
    }
    return newPrice.subtract(oldPrice)
        .divide(oldPrice, 6, RoundingMode.HALF_UP)
        .multiply(new BigDecimal("100"));
  }

  private String normalizeUsageScope(String usageScope) {
    String normalized = normalize(usageScope);
    if (FactorAdjustUsageScope.REPRICE_ONLY.getCode().equals(normalized)
        || FactorAdjustUsageScope.REPRICE_AND_DAILY.getCode().equals(normalized)) {
      return normalized;
    }
    throw new IllegalArgumentException("usageScope 必须是 REPRICE_ONLY 或 REPRICE_AND_DAILY");
  }

  private void validateRequest(InputStream input, FactorAdjustImportRequest request) {
    if (input == null) {
      throw new IllegalArgumentException("file 必填");
    }
    if (request == null) {
      throw new IllegalArgumentException("导入请求不能为空");
    }
    if (!StringUtils.hasText(request.getPricingMonth())) {
      throw new IllegalArgumentException("pricingMonth 必填");
    }
    if (!StringUtils.hasText(request.getBusinessUnitType())) {
      throw new IllegalArgumentException("businessUnitType 必填");
    }
    normalizeUsageScope(request.getUsageScope());
  }

  private byte[] readAllBytes(InputStream input) {
    try {
      return input.readAllBytes();
    } catch (IOException e) {
      throw new IllegalArgumentException("读取上传文件失败: " + e.getMessage(), e);
    }
  }

  private boolean samePrice(BigDecimal left, BigDecimal right) {
    if (left == null || right == null) {
      return left == right;
    }
    return left.compareTo(right) == 0;
  }

  private BigDecimal normalizePrice(BigDecimal price) {
    return price == null ? null : price.stripTrailingZeros();
  }

  private String normalizeOperator(String operator) {
    return StringUtils.hasText(operator) ? operator.trim() : "system";
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }

  private String nextBatchNo() {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    return "FAB" + LocalDateTime.now().format(BATCH_TIME_FORMAT) + suffix;
  }

  private String contentHash(FactorAdjustExcelParseResult parseResult, String usageScope) {
    StringBuilder builder = new StringBuilder();
    builder.append(usageScope).append('\n');
    if (parseResult != null) {
      builder.append(parseResult.getPricingMonth()).append('|')
          .append(parseResult.getBusinessUnitType()).append('\n');
      for (FactorAdjustExcelParseRow row : parseResult.getRows()) {
        builder.append(row == null ? "" : row.getFactorIdentityId()).append('|')
            .append(row == null ? "" : normalize(row.getFactorSeqNo())).append('|')
            .append(row == null ? "" : normalize(row.getFactorName())).append('|')
            .append(row == null ? "" : normalize(row.getShortName())).append('|')
            .append(row == null ? "" : normalize(row.getPriceSource())).append('|')
            .append(row == null ? "" : normalizePrice(row.getPrice()))
            .append('\n');
      }
    }
    return sha256(builder.toString().getBytes(StandardCharsets.UTF_8));
  }

  private String sha256(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(bytes == null ? new byte[0] : bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  private static class ImportCounters {
    private int changed;
    private int noChange;
    private int skipped;
    private int failed;

    private int total() {
      return changed + noChange + skipped + failed;
    }
  }
}
