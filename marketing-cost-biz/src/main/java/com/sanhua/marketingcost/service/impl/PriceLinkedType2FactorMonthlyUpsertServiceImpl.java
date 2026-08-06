package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.FactorRowParseResult;
import com.sanhua.marketingcost.dto.FactorRowRefSaveResult;
import com.sanhua.marketingcost.dto.FactorSheetParseResult;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2FactorIdentityResolution;
import com.sanhua.marketingcost.dto.PriceLinkedType2FactorRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.FactorMonthlyPriceChangeLog;
import com.sanhua.marketingcost.enums.FactorMonthlyPriceSourceTag;
import com.sanhua.marketingcost.enums.FactorPriceConflictStrategy;
import com.sanhua.marketingcost.enums.PriceLinkedImportEffectiveStrategy;
import com.sanhua.marketingcost.enums.PriceLinkedType2FactorIdentityResolutionStatus;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceChangeLogMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.service.PriceLinkedType2FactorIdentityResolver;
import com.sanhua.marketingcost.service.PriceLinkedType2FactorMonthlyUpsertService;
import com.sanhua.marketingcost.service.FactorUploadBatchService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PriceLinkedType2FactorMonthlyUpsertServiceImpl
    implements PriceLinkedType2FactorMonthlyUpsertService {

  private static final String STATUS_ACTIVE = "ACTIVE";
  private static final String IDENTITY_ORIGIN_STANDARD = "STANDARD_IMPORT";
  private static final String IDENTITY_ORIGIN_TYPE2 = "TYPE2_AUTO_CREATE";

  private final PriceLinkedType2FactorIdentityResolver identityResolver;
  private final FactorIdentityMapper factorIdentityMapper;
  private final FactorMonthlyPriceMapper factorMonthlyPriceMapper;
  private final FactorMonthlyPriceChangeLogMapper changeLogMapper;
  private final FactorUploadBatchService factorUploadBatchService;

  public PriceLinkedType2FactorMonthlyUpsertServiceImpl(
      PriceLinkedType2FactorIdentityResolver identityResolver,
      FactorIdentityMapper factorIdentityMapper,
      FactorMonthlyPriceMapper factorMonthlyPriceMapper,
      FactorMonthlyPriceChangeLogMapper changeLogMapper,
      FactorUploadBatchService factorUploadBatchService) {
    this.identityResolver = identityResolver;
    this.factorIdentityMapper = factorIdentityMapper;
    this.factorMonthlyPriceMapper = factorMonthlyPriceMapper;
    this.changeLogMapper = changeLogMapper;
    this.factorUploadBatchService = factorUploadBatchService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FactorMonthlyPriceUpsertResult upsert(
      PriceLinkedType2WorkbookParseResult parseResult,
      String priceMonth,
      String businessUnitType,
      String operator,
      Long sourceUploadBatchId) {
    return upsert(
        parseResult,
        priceMonth,
        businessUnitType,
        operator,
        sourceUploadBatchId,
        FactorPriceConflictStrategy.KEEP_EXISTING.getCode());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FactorMonthlyPriceUpsertResult upsert(
      PriceLinkedType2WorkbookParseResult parseResult,
      String priceMonth,
      String businessUnitType,
      String operator,
      Long sourceUploadBatchId,
      String factorPriceConflictStrategy) {
    String normalizedMonth = normalize(priceMonth);
    String normalizedBusinessUnit = normalize(businessUnitType);
    String normalizedOperator = normalize(operator);
    String strategy = normalizeStrategy(factorPriceConflictStrategy);
    FactorMonthlyPriceUpsertResult result = new FactorMonthlyPriceUpsertResult();
    String requestError = validateRequest(
        parseResult, normalizedMonth, normalizedBusinessUnit, sourceUploadBatchId);
    if (requestError != null) {
      result.getErrors().add(
          new FactorMonthlyPriceUpsertResult.RowError(null, null, requestError));
      return result;
    }

    List<PriceLinkedType2FactorIdentityResolution> resolutions =
        identityResolver.resolve(
            parseResult.getFactorRows(), normalizedBusinessUnit, normalizedMonth);
    validateDuplicateCanonicalRows(resolutions, result);
    validateResolutions(resolutions, strategy, result);
    if (!result.getErrors().isEmpty()) {
      return result;
    }

    Map<String, FactorIdentity> createdIdentityByCanonicalKey = new HashMap<>();
    Set<Long> metadataUpdatedIdentityIds = new LinkedHashSet<>();
    for (PriceLinkedType2FactorIdentityResolution resolution : resolutions) {
      IdentityOutcome identityOutcome = resolveIdentityForWrite(
          resolution,
          normalizedBusinessUnit,
          normalizedOperator,
          createdIdentityByCanonicalKey,
          metadataUpdatedIdentityIds,
          result);
      FactorIdentity identity = identityOutcome.identity();
      MonthlyPriceOutcome priceOutcome = upsertMonthlyPrice(
          identity,
          resolution.getSourceRow().getPrice(),
          normalizedMonth,
          normalizedOperator,
          sourceUploadBatchId,
          strategy,
          result);
      result.getRows().add(toRowResult(
          resolution.getSourceRow(), identityOutcome, priceOutcome));
    }

    FactorWorkbookParseResult factorParseResult = toFactorWorkbook(parseResult);
    FactorRowRefSaveResult rowRefResult = factorUploadBatchService.saveRowRefs(
        sourceUploadBatchId, factorParseResult, result);
    if (rowRefResult != null && !rowRefResult.getErrors().isEmpty()) {
      String message = rowRefResult.getErrors().stream()
          .map(FactorRowRefSaveResult.RowError::getMessage)
          .filter(StringUtils::hasText)
          .findFirst()
          .orElse("类型 2 影响因素来源行保存失败");
      throw new IllegalStateException(message);
    }
    return result;
  }

  private String validateRequest(
      PriceLinkedType2WorkbookParseResult parseResult,
      String priceMonth,
      String businessUnitType,
      Long sourceUploadBatchId) {
    if (parseResult == null) {
      return "类型 2 解析结果不能为空";
    }
    if (!priceMonth.matches("\\d{4}-\\d{2}")) {
      return "priceMonth 必须为 YYYY-MM";
    }
    if (!StringUtils.hasText(businessUnitType)) {
      return "businessUnitType 不能为空";
    }
    if (sourceUploadBatchId == null) {
      return "sourceUploadBatchId 不能为空，类型 2 因素必须保留来源行";
    }
    if (!parseResult.getErrors().isEmpty()) {
      return "类型 2 Excel 存在解析错误，不能写入因素价格";
    }
    if (parseResult.getFactorRows().isEmpty()) {
      return "类型 2 Excel 未识别到影响因素";
    }
    return null;
  }

  private void validateDuplicateCanonicalRows(
      List<PriceLinkedType2FactorIdentityResolution> resolutions,
      FactorMonthlyPriceUpsertResult result) {
    Map<String, Map<String, List<PriceLinkedType2FactorIdentityResolution>>> grouped =
        new LinkedHashMap<>();
    for (PriceLinkedType2FactorIdentityResolution resolution : resolutions) {
      String canonicalKey = normalize(resolution.getCanonicalFactorKey());
      String price = normalizePriceText(
          resolution.getSourceRow() == null ? null : resolution.getSourceRow().getPrice());
      grouped.computeIfAbsent(canonicalKey, ignored -> new LinkedHashMap<>())
          .computeIfAbsent(price, ignored -> new ArrayList<>())
          .add(resolution);
    }
    for (Map.Entry<String, Map<String, List<PriceLinkedType2FactorIdentityResolution>>> entry
        : grouped.entrySet()) {
      if (StringUtils.hasText(entry.getKey()) && entry.getValue().size() > 1) {
        List<Integer> rows = entry.getValue().values().stream()
            .flatMap(List::stream)
            .map(PriceLinkedType2FactorIdentityResolution::getSourceRow)
            .filter(Objects::nonNull)
            .map(PriceLinkedType2FactorRow::getSourceRowNumber)
            .filter(Objects::nonNull)
            .toList();
        result.setMonthlyPriceConflictCount(result.getMonthlyPriceConflictCount() + 1);
        result.getErrors().add(new FactorMonthlyPriceUpsertResult.RowError(
            null,
            rows.isEmpty() ? null : rows.getFirst(),
            "同一统一因素 " + entry.getKey() + " 在 Excel 中出现不同价格，来源行 " + rows));
      }
    }
  }

  private void validateResolutions(
      List<PriceLinkedType2FactorIdentityResolution> resolutions,
      String strategy,
      FactorMonthlyPriceUpsertResult result) {
    for (PriceLinkedType2FactorIdentityResolution resolution : resolutions) {
      PriceLinkedType2FactorIdentityResolutionStatus status = resolution.getStatus();
      if (status == PriceLinkedType2FactorIdentityResolutionStatus.INVALID_REQUEST
          || status
              == PriceLinkedType2FactorIdentityResolutionStatus.CANONICAL_MASTER_CONFLICT) {
        addResolutionError(resolution, result);
        continue;
      }
      if (status == PriceLinkedType2FactorIdentityResolutionStatus.PRICE_CONFLICT) {
        if (!FactorPriceConflictStrategy.OVERWRITE.getCode().equals(strategy)
            || !resolution.isOverwriteAllowed()
            || resolution.getRecommendedFactorIdentityId() == null) {
          result.setMonthlyPriceConflictCount(result.getMonthlyPriceConflictCount() + 1);
          result.setMonthlyPriceSkippedCount(result.getMonthlyPriceSkippedCount() + 1);
          addResolutionError(resolution, result);
        }
      }
    }
  }

  private void addResolutionError(
      PriceLinkedType2FactorIdentityResolution resolution,
      FactorMonthlyPriceUpsertResult result) {
    PriceLinkedType2FactorRow row = resolution.getSourceRow();
    result.getErrors().add(new FactorMonthlyPriceUpsertResult.RowError(
        row == null ? null : row.getSourceSheetName(),
        row == null ? null : row.getSourceRowNumber(),
        resolution.getMessage()));
  }

  private IdentityOutcome resolveIdentityForWrite(
      PriceLinkedType2FactorIdentityResolution resolution,
      String businessUnitType,
      String operator,
      Map<String, FactorIdentity> createdIdentityByCanonicalKey,
      Set<Long> metadataUpdatedIdentityIds,
      FactorMonthlyPriceUpsertResult result) {
    if (resolution.getStatus()
        == PriceLinkedType2FactorIdentityResolutionStatus.CREATE_REQUIRED) {
      FactorIdentity cached = createdIdentityByCanonicalKey.get(
          resolution.getCanonicalFactorKey());
      if (cached != null) {
        result.setIdentityReusedCount(result.getIdentityReusedCount() + 1);
        return new IdentityOutcome(cached, "REUSE");
      }
      FactorIdentity created = createIdentity(
          resolution.getSourceRow(),
          resolution.getCanonicalFactorKey(),
          businessUnitType,
          operator);
      createdIdentityByCanonicalKey.put(resolution.getCanonicalFactorKey(), created);
      metadataUpdatedIdentityIds.add(created.getId());
      result.setIdentityCreatedCount(result.getIdentityCreatedCount() + 1);
      return new IdentityOutcome(created, "CREATE");
    }

    Long identityId = resolution.getStatus()
            == PriceLinkedType2FactorIdentityResolutionStatus.PRICE_CONFLICT
        ? resolution.getRecommendedFactorIdentityId()
        : resolution.getSelectedFactorIdentityId();
    FactorIdentity identity = factorIdentityMapper.selectById(identityId);
    if (identity == null) {
      throw new IllegalStateException("统一影响因素身份不存在：" + identityId);
    }
    Long canonicalMasterId = resolution.getStatus()
            == PriceLinkedType2FactorIdentityResolutionStatus.PRICE_CONFLICT
        ? resolution.getRecommendedCanonicalFactorIdentityId()
        : resolution.getSelectedCanonicalFactorIdentityId();
    backfillCanonicalMetadata(
        resolution,
        canonicalMasterId,
        operator,
        metadataUpdatedIdentityIds);
    result.setIdentityReusedCount(result.getIdentityReusedCount() + 1);
    return new IdentityOutcome(factorIdentityMapper.selectById(identityId), "REUSE");
  }

  private FactorIdentity createIdentity(
      PriceLinkedType2FactorRow row,
      String canonicalFactorKey,
      String businessUnitType,
      String operator) {
    LocalDateTime now = LocalDateTime.now();
    FactorIdentity identity = new FactorIdentity();
    identity.setBusinessUnitType(businessUnitType);
    identity.setFactorSeqNo(normalize(row.getFactorSeqNo()));
    identity.setFactorName(normalize(row.getFactorName()));
    identity.setShortName(normalize(row.getShortName()));
    identity.setPriceSource(normalize(row.getPriceSource()));
    identity.setIdentityHash(sha256(identityKey(identity)));
    identity.setCanonicalFactorKey(canonicalFactorKey);
    identity.setIdentityOrigin(IDENTITY_ORIGIN_TYPE2);
    identity.setStatus(STATUS_ACTIVE);
    identity.setCreatedBy(operator);
    identity.setCreatedAt(now);
    identity.setUpdatedBy(operator);
    identity.setUpdatedAt(now);
    factorIdentityMapper.insert(identity);
    if (identity.getId() == null) {
      throw new IllegalStateException("类型 2 影响因素身份创建后未返回 ID");
    }
    identity.setCanonicalFactorIdentityId(identity.getId());
    factorIdentityMapper.updateById(identity);
    return identity;
  }

  private void backfillCanonicalMetadata(
      PriceLinkedType2FactorIdentityResolution resolution,
      Long canonicalMasterId,
      String operator,
      Set<Long> metadataUpdatedIdentityIds) {
    for (Long identityId : resolution.getCanonicalMetadataRequiredIdentityIds()) {
      if (identityId == null || !metadataUpdatedIdentityIds.add(identityId)) {
        continue;
      }
      FactorIdentity candidate = factorIdentityMapper.selectById(identityId);
      if (candidate == null) {
        throw new IllegalStateException("待补统一身份元数据的记录不存在：" + identityId);
      }
      candidate.setCanonicalFactorKey(resolution.getCanonicalFactorKey());
      candidate.setCanonicalFactorIdentityId(canonicalMasterId);
      if (!StringUtils.hasText(candidate.getIdentityOrigin())) {
        candidate.setIdentityOrigin(IDENTITY_ORIGIN_STANDARD);
      }
      candidate.setUpdatedBy(operator);
      candidate.setUpdatedAt(LocalDateTime.now());
      factorIdentityMapper.updateById(candidate);
    }
  }

  private MonthlyPriceOutcome upsertMonthlyPrice(
      FactorIdentity identity,
      BigDecimal incomingPrice,
      String priceMonth,
      String operator,
      Long sourceUploadBatchId,
      String strategy,
      FactorMonthlyPriceUpsertResult result) {
    FactorMonthlyPrice existing =
        factorMonthlyPriceMapper.findActiveByIdentityAndMonth(identity.getId(), priceMonth);
    BigDecimal normalizedIncoming = normalizePrice(incomingPrice);
    if (existing == null) {
      FactorMonthlyPrice created = new FactorMonthlyPrice();
      created.setFactorIdentityId(identity.getId());
      created.setPriceMonth(priceMonth);
      created.setPrice(normalizedIncoming);
      created.setTaxIncluded(1);
      created.setSourceUploadBatchId(sourceUploadBatchId);
      created.setSourceTag(FactorMonthlyPriceSourceTag.EXCEL_IMPORT.getCode());
      created.setStatus(STATUS_ACTIVE);
      created.setCreatedBy(operator);
      created.setCreatedAt(LocalDateTime.now());
      created.setUpdatedBy(operator);
      created.setUpdatedAt(LocalDateTime.now());
      factorMonthlyPriceMapper.insert(created);
      if (created.getId() == null) {
        throw new IllegalStateException("类型 2 月度价格创建后未返回 ID");
      }
      insertChangeLog(
          created, null, normalizedIncoming, "CREATE", sourceUploadBatchId, operator);
      result.setMonthlyPriceCreatedCount(result.getMonthlyPriceCreatedCount() + 1);
      return new MonthlyPriceOutcome(created, "CREATE", null);
    }

    BigDecimal oldPrice = normalizePrice(existing.getPrice());
    if (samePrice(oldPrice, normalizedIncoming)) {
      result.setMonthlyPriceUnchangedCount(result.getMonthlyPriceUnchangedCount() + 1);
      return new MonthlyPriceOutcome(existing, "NO_CHANGE", oldPrice);
    }
    if (!FactorPriceConflictStrategy.OVERWRITE.getCode().equals(strategy)) {
      throw new IllegalStateException(
          "影响因素价格在写入前发生变化，已停止并回滚：" + identity.getId());
    }

    existing.setPrice(normalizedIncoming);
    existing.setSourceUploadBatchId(sourceUploadBatchId);
    existing.setSourceTag(FactorMonthlyPriceSourceTag.EXCEL_IMPORT.getCode());
    existing.setUpdatedBy(operator);
    existing.setUpdatedAt(LocalDateTime.now());
    factorMonthlyPriceMapper.updateById(existing);
    insertChangeLog(
        existing, oldPrice, normalizedIncoming, "UPDATE", sourceUploadBatchId, operator);
    result.setMonthlyPriceUpdatedCount(result.getMonthlyPriceUpdatedCount() + 1);
    result.setMonthlyPriceOverwriteCount(result.getMonthlyPriceOverwriteCount() + 1);
    return new MonthlyPriceOutcome(existing, "UPDATE", oldPrice);
  }

  private void insertChangeLog(
      FactorMonthlyPrice monthlyPrice,
      BigDecimal oldPrice,
      BigDecimal newPrice,
      String changeType,
      Long sourceUploadBatchId,
      String operator) {
    FactorMonthlyPriceChangeLog log = new FactorMonthlyPriceChangeLog();
    log.setFactorMonthlyPriceId(monthlyPrice.getId());
    log.setFactorIdentityId(monthlyPrice.getFactorIdentityId());
    log.setPriceMonth(monthlyPrice.getPriceMonth());
    log.setOldPrice(oldPrice);
    log.setNewPrice(newPrice);
    log.setChangeType(changeType);
    log.setSourceUploadBatchId(sourceUploadBatchId);
    log.setSourceType(FactorMonthlyPriceSourceTag.EXCEL_IMPORT.getCode());
    log.setChangedBy(operator);
    log.setRemark("PLI2-06 类型 2 影响因素月度价格");
    log.setCreatedAt(LocalDateTime.now());
    changeLogMapper.insert(log);
  }

  private FactorMonthlyPriceUpsertResult.RowResult toRowResult(
      PriceLinkedType2FactorRow row,
      IdentityOutcome identityOutcome,
      MonthlyPriceOutcome priceOutcome) {
    FactorIdentity identity = identityOutcome.identity();
    FactorMonthlyPriceUpsertResult.RowResult result =
        new FactorMonthlyPriceUpsertResult.RowResult();
    result.setSourceSheetName(row.getSourceSheetName());
    result.setSourceRowNumber(row.getSourceRowNumber());
    result.setFactorIdentityId(identity.getId());
    result.setFactorMonthlyPriceId(priceOutcome.monthlyPrice().getId());
    result.setFactorSeqNo(identity.getFactorSeqNo());
    result.setFactorName(identity.getFactorName());
    result.setShortName(identity.getShortName());
    result.setPriceSource(identity.getPriceSource());
    result.setIdentityAction(identityOutcome.action());
    result.setMonthlyPriceAction(priceOutcome.action());
    result.setOldPrice(priceOutcome.oldPrice());
    result.setNewPrice(priceOutcome.monthlyPrice().getPrice());
    result.setOriginalPrice(normalizePrice(row.getPrice()));
    result.setUnit(normalize(row.getUnit()));
    return result;
  }

  private FactorWorkbookParseResult toFactorWorkbook(
      PriceLinkedType2WorkbookParseResult type2) {
    FactorWorkbookParseResult workbook = new FactorWorkbookParseResult();
    workbook.setSourceFileName(type2.getSourceFileName());
    FactorSheetParseResult sheet = new FactorSheetParseResult();
    sheet.setSheetName(type2.getBusinessSheetName());
    sheet.setHeaderRowNumber(type2.getBusinessHeaderRowNumber());
    for (PriceLinkedType2FactorRow source : type2.getFactorRows()) {
      FactorRowParseResult row = new FactorRowParseResult();
      row.setSourceSheetName(source.getSourceSheetName());
      row.setSourceRowNumber(source.getSourceRowNumber());
      row.setFactorSeqNo(source.getFactorSeqNo());
      row.setFactorName(source.getFactorName());
      row.setShortName(source.getShortName());
      row.setPriceSource(source.getPriceSource());
      row.setPrice(source.getPrice());
      row.setOriginalPrice(source.getPrice());
      row.setUnit(source.getUnit());
      sheet.getRows().add(row);
    }
    workbook.getSheets().add(sheet);
    return workbook;
  }

  private String normalizeStrategy(String strategy) {
    String normalized = normalize(strategy).toUpperCase(java.util.Locale.ROOT);
    if (!StringUtils.hasText(normalized)
        || FactorPriceConflictStrategy.KEEP_EXISTING.getCode().equals(normalized)
        || PriceLinkedImportEffectiveStrategy.APPEND_ONLY.getCode().equals(normalized)) {
      return FactorPriceConflictStrategy.KEEP_EXISTING.getCode();
    }
    if (FactorPriceConflictStrategy.OVERWRITE.getCode().equals(normalized)
        || PriceLinkedImportEffectiveStrategy.OVERRIDE_EFFECTIVE.getCode().equals(normalized)) {
      return FactorPriceConflictStrategy.OVERWRITE.getCode();
    }
    throw new IllegalArgumentException(
        "factorPriceConflictStrategy 非法，仅支持 KEEP_EXISTING / OVERWRITE: " + strategy);
  }

  private String identityKey(FactorIdentity identity) {
    return String.join(
        "|",
        normalize(identity.getBusinessUnitType()),
        normalize(identity.getFactorSeqNo()),
        normalize(identity.getFactorName()),
        normalize(identity.getShortName()),
        normalize(identity.getPriceSource()));
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
  }

  private BigDecimal normalizePrice(BigDecimal price) {
    return price == null ? null : price.stripTrailingZeros();
  }

  private String normalizePriceText(BigDecimal price) {
    BigDecimal normalized = normalizePrice(price);
    return normalized == null ? "" : normalized.toPlainString();
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

  private record MonthlyPriceOutcome(
      FactorMonthlyPrice monthlyPrice, String action, BigDecimal oldPrice) {
  }

  private record IdentityOutcome(FactorIdentity identity, String action) {
  }
}
