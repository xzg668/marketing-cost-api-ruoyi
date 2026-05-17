package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.FactorRowParseResult;
import com.sanhua.marketingcost.dto.FactorSheetParseResult;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.dto.QuoteBasePriceDetectResult;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.FactorMonthlyPriceChangeLog;
import com.sanhua.marketingcost.enums.PriceLinkedImportEffectiveStrategy;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceChangeLogMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.service.FactorMonthlyPriceUpsertService;
import com.sanhua.marketingcost.service.QuoteBasePriceMappingService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FactorMonthlyPriceUpsertServiceImpl implements FactorMonthlyPriceUpsertService {

  private static final String STATUS_ACTIVE = "ACTIVE";

  private final FactorIdentityMapper factorIdentityMapper;
  private final FactorMonthlyPriceMapper factorMonthlyPriceMapper;
  private final FactorMonthlyPriceChangeLogMapper changeLogMapper;
  @Autowired(required = false)
  private QuoteBasePriceMappingService quoteBasePriceMappingService;

  public FactorMonthlyPriceUpsertServiceImpl(
      FactorIdentityMapper factorIdentityMapper,
      FactorMonthlyPriceMapper factorMonthlyPriceMapper,
      FactorMonthlyPriceChangeLogMapper changeLogMapper) {
    this.factorIdentityMapper = factorIdentityMapper;
    this.factorMonthlyPriceMapper = factorMonthlyPriceMapper;
    this.changeLogMapper = changeLogMapper;
  }

  void setQuoteBasePriceMappingService(QuoteBasePriceMappingService quoteBasePriceMappingService) {
    this.quoteBasePriceMappingService = quoteBasePriceMappingService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FactorMonthlyPriceUpsertResult upsert(
      FactorWorkbookParseResult parseResult,
      String priceMonth,
      String businessUnitType,
      String operator,
      Long sourceUploadBatchId) {
    return upsert(parseResult, priceMonth, businessUnitType, operator, sourceUploadBatchId,
        PriceLinkedImportEffectiveStrategy.OVERRIDE_EFFECTIVE.getCode());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FactorMonthlyPriceUpsertResult upsert(
      FactorWorkbookParseResult parseResult,
      String priceMonth,
      String businessUnitType,
      String operator,
      Long sourceUploadBatchId,
      String effectiveStrategy) {
    FactorMonthlyPriceUpsertResult result = new FactorMonthlyPriceUpsertResult();
    if (!StringUtils.hasText(priceMonth)) {
      result.getErrors().add(new FactorMonthlyPriceUpsertResult.RowError(
          null, null, "priceMonth 必填"));
      return result;
    }
    if (!StringUtils.hasText(businessUnitType)) {
      result.getErrors().add(new FactorMonthlyPriceUpsertResult.RowError(
          null, null, "businessUnitType 必填"));
      return result;
    }
    if (parseResult == null || parseResult.getSheets().isEmpty()) {
      return result;
    }

    String normalizedMonth = normalize(priceMonth);
    String normalizedBu = normalize(businessUnitType);
    String normalizedOperator = normalize(operator);
    String strategy = normalizeEffectiveStrategy(effectiveStrategy);
    Map<String, FactorIdentity> identityCache = new HashMap<>();
    Map<String, FactorMonthlyPrice> priceCache = new HashMap<>();

    for (FactorSheetParseResult sheet : parseResult.getSheets()) {
      for (FactorRowParseResult row : sheet.getRows()) {
        processRow(row, normalizedMonth, normalizedBu, normalizedOperator,
            sourceUploadBatchId, strategy, identityCache, priceCache, result);
      }
    }
    return result;
  }

  private void processRow(
      FactorRowParseResult row,
      String priceMonth,
      String businessUnitType,
      String operator,
      Long sourceUploadBatchId,
      String effectiveStrategy,
      Map<String, FactorIdentity> identityCache,
      Map<String, FactorMonthlyPrice> priceCache,
      FactorMonthlyPriceUpsertResult result) {
    String validateError = validateRow(row);
    if (validateError != null) {
      result.getErrors().add(new FactorMonthlyPriceUpsertResult.RowError(
          row == null ? null : row.getSourceSheetName(),
          row == null ? null : row.getSourceRowNumber(),
          validateError));
      return;
    }

    IdentityUpsertOutcome identityOutcome = upsertIdentity(
        row, businessUnitType, operator, identityCache, result);
    FactorIdentity identity = identityOutcome.identity();
    if (identity == null || identity.getId() == null) {
      result.getErrors().add(new FactorMonthlyPriceUpsertResult.RowError(
          row.getSourceSheetName(), row.getSourceRowNumber(), "影响因素身份保存失败"));
      return;
    }

    PriceUpsertOutcome priceOutcome = upsertMonthlyPrice(
        identity, row, priceMonth, operator, sourceUploadBatchId, effectiveStrategy, priceCache, result);

    FactorMonthlyPriceUpsertResult.RowResult rowResult =
        new FactorMonthlyPriceUpsertResult.RowResult();
    rowResult.setSourceSheetName(row.getSourceSheetName());
    rowResult.setSourceRowNumber(row.getSourceRowNumber());
    rowResult.setFactorIdentityId(identity.getId());
    rowResult.setFactorMonthlyPriceId(priceOutcome.monthlyPrice().getId());
    rowResult.setFactorSeqNo(identity.getFactorSeqNo());
    rowResult.setFactorName(identity.getFactorName());
    rowResult.setShortName(identity.getShortName());
    rowResult.setPriceSource(identity.getPriceSource());
    rowResult.setIdentityAction(identityOutcome.action());
    rowResult.setMonthlyPriceAction(priceOutcome.action());
    rowResult.setOldPrice(priceOutcome.oldPrice());
    rowResult.setNewPrice(priceOutcome.monthlyPrice().getPrice());
    rowResult.setOriginalPrice(normalizePrice(row.getOriginalPrice()));
    rowResult.setUnit(normalize(row.getUnit()));
    applyQuoteBaseDetect(identity.getId(), rowResult, result);
    result.getRows().add(rowResult);
  }

  private void applyQuoteBaseDetect(
      Long factorIdentityId,
      FactorMonthlyPriceUpsertResult.RowResult rowResult,
      FactorMonthlyPriceUpsertResult result) {
    if (quoteBasePriceMappingService == null || factorIdentityId == null) {
      return;
    }
    QuoteBasePriceDetectResult detect =
        quoteBasePriceMappingService.detectAndSaveFactorQuoteBaseMapping(factorIdentityId);
    if (detect == null) {
      return;
    }
    rowResult.setQuoteBaseDetectStatus(detect.getStatus());
    rowResult.setQuoteBaseQuoteFieldCode(detect.getQuoteFieldCode());
    rowResult.setQuoteBaseQuoteFieldName(detect.getQuoteFieldName());
    rowResult.setQuoteBaseVariableCode(detect.getVariableCode());
    rowResult.setQuoteBaseMatchedKeyword(detect.getMatchedKeyword());
    rowResult.setQuoteBaseMatchSource("AUTO");
    rowResult.setQuoteBaseDetectMessage(detect.getMessage());
    if (detect.recognized()) {
      result.setQuoteBaseRecognizedCount(result.getQuoteBaseRecognizedCount() + 1);
    } else if (detect.conflict()) {
      result.setQuoteBaseConflictCount(result.getQuoteBaseConflictCount() + 1);
    } else {
      result.setQuoteBaseUnrecognizedCount(result.getQuoteBaseUnrecognizedCount() + 1);
    }
  }

  private IdentityUpsertOutcome upsertIdentity(
      FactorRowParseResult row,
      String businessUnitType,
      String operator,
      Map<String, FactorIdentity> identityCache,
      FactorMonthlyPriceUpsertResult result) {
    String key = identityKey(businessUnitType, row);
    FactorIdentity cached = identityCache.get(key);
    if (cached != null) {
      result.setIdentityReusedCount(result.getIdentityReusedCount() + 1);
      return new IdentityUpsertOutcome(cached, "REUSE");
    }

    FactorIdentity existing = factorIdentityMapper.selectOne(
        Wrappers.lambdaQuery(FactorIdentity.class)
            .eq(FactorIdentity::getBusinessUnitType, businessUnitType)
            .eq(FactorIdentity::getFactorSeqNo, normalize(row.getFactorSeqNo()))
            .eq(FactorIdentity::getFactorName, normalize(row.getFactorName()))
            .eq(FactorIdentity::getShortName, normalize(row.getShortName()))
            .eq(FactorIdentity::getPriceSource, normalize(row.getPriceSource()))
            .last("LIMIT 1"));
    if (existing != null) {
      identityCache.put(key, existing);
      result.setIdentityReusedCount(result.getIdentityReusedCount() + 1);
      return new IdentityUpsertOutcome(existing, "REUSE");
    }

    LocalDateTime now = LocalDateTime.now();
    FactorIdentity identity = new FactorIdentity();
    identity.setBusinessUnitType(businessUnitType);
    identity.setFactorSeqNo(normalize(row.getFactorSeqNo()));
    identity.setFactorName(normalize(row.getFactorName()));
    identity.setShortName(normalize(row.getShortName()));
    identity.setPriceSource(normalize(row.getPriceSource()));
    identity.setIdentityHash(sha256(key));
    identity.setStatus(STATUS_ACTIVE);
    identity.setCreatedBy(operator);
    identity.setCreatedAt(now);
    identity.setUpdatedBy(operator);
    identity.setUpdatedAt(now);
    factorIdentityMapper.insert(identity);
    identityCache.put(key, identity);
    result.setIdentityCreatedCount(result.getIdentityCreatedCount() + 1);
    return new IdentityUpsertOutcome(identity, "CREATE");
  }

  private PriceUpsertOutcome upsertMonthlyPrice(
      FactorIdentity identity,
      FactorRowParseResult row,
      String priceMonth,
      String operator,
      Long sourceUploadBatchId,
      String effectiveStrategy,
      Map<String, FactorMonthlyPrice> priceCache,
      FactorMonthlyPriceUpsertResult result) {
    String key = identity.getId() + "|" + priceMonth;
    FactorMonthlyPrice cached = priceCache.get(key);
    if (cached != null) {
      return handleExistingMonthlyPrice(
          cached, identity.getId(), row.getPrice(), priceMonth, operator,
          sourceUploadBatchId, effectiveStrategy, priceCache, result, key);
    }

    FactorMonthlyPrice existing = factorMonthlyPriceMapper.selectOne(
        Wrappers.lambdaQuery(FactorMonthlyPrice.class)
            .eq(FactorMonthlyPrice::getFactorIdentityId, identity.getId())
            .eq(FactorMonthlyPrice::getPriceMonth, priceMonth)
            .last("LIMIT 1"));
    if (existing != null) {
      priceCache.put(key, existing);
      return handleExistingMonthlyPrice(
          existing, identity.getId(), row.getPrice(), priceMonth, operator,
          sourceUploadBatchId, effectiveStrategy, priceCache, result, key);
    }

    LocalDateTime now = LocalDateTime.now();
    FactorMonthlyPrice monthlyPrice = new FactorMonthlyPrice();
    monthlyPrice.setFactorIdentityId(identity.getId());
    monthlyPrice.setPriceMonth(priceMonth);
    monthlyPrice.setPrice(normalizePrice(row.getPrice()));
    monthlyPrice.setTaxIncluded(1);
    monthlyPrice.setSourceUploadBatchId(sourceUploadBatchId);
    monthlyPrice.setStatus(STATUS_ACTIVE);
    monthlyPrice.setCreatedBy(operator);
    monthlyPrice.setCreatedAt(now);
    monthlyPrice.setUpdatedBy(operator);
    monthlyPrice.setUpdatedAt(now);
    factorMonthlyPriceMapper.insert(monthlyPrice);
    priceCache.put(key, monthlyPrice);
    result.setMonthlyPriceCreatedCount(result.getMonthlyPriceCreatedCount() + 1);
    insertChangeLog(monthlyPrice.getId(), identity.getId(), priceMonth, null,
        monthlyPrice.getPrice(), "CREATE", sourceUploadBatchId, operator);
    return new PriceUpsertOutcome(monthlyPrice, "CREATE", null);
  }

  private PriceUpsertOutcome handleExistingMonthlyPrice(
      FactorMonthlyPrice existing,
      Long factorIdentityId,
      BigDecimal incomingPrice,
      String priceMonth,
      String operator,
      Long sourceUploadBatchId,
      String effectiveStrategy,
      Map<String, FactorMonthlyPrice> priceCache,
      FactorMonthlyPriceUpsertResult result,
      String cacheKey) {
    BigDecimal newPrice = normalizePrice(incomingPrice);
    BigDecimal oldPrice = normalizePrice(existing.getPrice());
    if (samePrice(oldPrice, newPrice)) {
      result.setMonthlyPriceUnchangedCount(result.getMonthlyPriceUnchangedCount() + 1);
      return new PriceUpsertOutcome(existing, "NO_CHANGE", oldPrice);
    }
    if (PriceLinkedImportEffectiveStrategy.APPEND_ONLY.getCode().equals(effectiveStrategy)) {
      result.setMonthlyPriceSkippedCount(result.getMonthlyPriceSkippedCount() + 1);
      return new PriceUpsertOutcome(existing, "SKIP_EXISTING", oldPrice);
    }
    existing.setPrice(newPrice);
    existing.setSourceUploadBatchId(sourceUploadBatchId);
    existing.setUpdatedBy(operator);
    existing.setUpdatedAt(LocalDateTime.now());
    factorMonthlyPriceMapper.updateById(existing);
    priceCache.put(cacheKey, existing);
    result.setMonthlyPriceUpdatedCount(result.getMonthlyPriceUpdatedCount() + 1);
    insertChangeLog(existing.getId(), factorIdentityId, priceMonth, oldPrice,
        newPrice, "UPDATE", sourceUploadBatchId, operator);
    return new PriceUpsertOutcome(existing, "UPDATE", oldPrice);
  }

  private String normalizeEffectiveStrategy(String effectiveStrategy) {
    String normalized = normalize(effectiveStrategy);
    if (PriceLinkedImportEffectiveStrategy.APPEND_ONLY.getCode().equals(normalized)) {
      return normalized;
    }
    return PriceLinkedImportEffectiveStrategy.OVERRIDE_EFFECTIVE.getCode();
  }

  private void insertChangeLog(
      Long factorMonthlyPriceId,
      Long factorIdentityId,
      String priceMonth,
      BigDecimal oldPrice,
      BigDecimal newPrice,
      String changeType,
      Long sourceUploadBatchId,
      String operator) {
    FactorMonthlyPriceChangeLog log = new FactorMonthlyPriceChangeLog();
    log.setFactorMonthlyPriceId(factorMonthlyPriceId);
    log.setFactorIdentityId(factorIdentityId);
    log.setPriceMonth(priceMonth);
    log.setOldPrice(oldPrice);
    log.setNewPrice(newPrice);
    log.setChangeType(changeType);
    log.setSourceUploadBatchId(sourceUploadBatchId);
    log.setChangedBy(operator);
    log.setRemark("V2-04 影响因素月度价格汇总");
    log.setCreatedAt(LocalDateTime.now());
    changeLogMapper.insert(log);
  }

  private String validateRow(FactorRowParseResult row) {
    if (row == null) {
      return "影响因素行为空";
    }
    if (!StringUtils.hasText(row.getFactorSeqNo())) {
      return "序号不能为空";
    }
    if (!StringUtils.hasText(row.getFactorName())) {
      return "价表影响因素名称不能为空";
    }
    if (!StringUtils.hasText(row.getShortName())) {
      return "简称不能为空";
    }
    if (!StringUtils.hasText(row.getPriceSource())) {
      return "取价来源不能为空";
    }
    if (row.getPrice() == null) {
      return "价格不能为空或格式非法";
    }
    return null;
  }

  private String identityKey(String businessUnitType, FactorRowParseResult row) {
    return String.join("|",
        normalize(businessUnitType),
        normalize(row.getFactorSeqNo()),
        normalize(row.getFactorName()),
        normalize(row.getShortName()),
        normalize(row.getPriceSource()));
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }

  private BigDecimal normalizePrice(BigDecimal price) {
    return price == null ? null : price.stripTrailingZeros();
  }

  private boolean samePrice(BigDecimal left, BigDecimal right) {
    if (left == null || right == null) {
      return left == right;
    }
    return left.compareTo(right) == 0;
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  private record PriceUpsertOutcome(
      FactorMonthlyPrice monthlyPrice, String action, BigDecimal oldPrice) {
  }

  private record IdentityUpsertOutcome(FactorIdentity identity, String action) {
  }
}
