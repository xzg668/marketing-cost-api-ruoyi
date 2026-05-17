package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.FactorRowParseResult;
import com.sanhua.marketingcost.dto.FactorRowRefSaveResult;
import com.sanhua.marketingcost.dto.FactorSheetParseResult;
import com.sanhua.marketingcost.dto.FactorUploadBatchCreateRequest;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.entity.FactorRowRef;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.mapper.FactorRowRefMapper;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.service.FactorUploadBatchService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FactorUploadBatchServiceImpl implements FactorUploadBatchService {

  private static final String DEFAULT_IMPORT_TYPE = "MONTHLY_LINKED_FACTOR";
  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_PARTIAL = "PARTIAL";
  private static final String STATUS_FAILED = "FAILED";
  private static final DateTimeFormatter BATCH_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

  private final FactorUploadBatchMapper factorUploadBatchMapper;
  private final FactorRowRefMapper factorRowRefMapper;

  public FactorUploadBatchServiceImpl(
      FactorUploadBatchMapper factorUploadBatchMapper,
      FactorRowRefMapper factorRowRefMapper) {
    this.factorUploadBatchMapper = factorUploadBatchMapper;
    this.factorRowRefMapper = factorRowRefMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FactorUploadBatch createFactorBatch(FactorUploadBatchCreateRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("上传批次请求不能为空");
    }
    String priceMonth = requireText(request.getPriceMonth(), "priceMonth 必填");
    String businessUnitType = requireText(request.getBusinessUnitType(), "businessUnitType 必填");
    FactorWorkbookParseResult parseResult = request.getParseResult();
    LocalDateTime now = LocalDateTime.now();

    FactorUploadBatch batch = new FactorUploadBatch();
    batch.setBatchNo(nextBatchNo());
    batch.setImportType(StringUtils.hasText(request.getImportType())
        ? normalize(request.getImportType())
        : DEFAULT_IMPORT_TYPE);
    batch.setImportPurpose(normalize(request.getImportPurpose()));
    batch.setEffectiveStrategy(normalize(request.getEffectiveStrategy()));
    batch.setPriceMonth(normalize(priceMonth));
    batch.setBusinessUnitType(normalize(businessUnitType));
    batch.setFileName(normalize(request.getFileName()));
    batch.setFileSha256(normalize(request.getFileSha256()));
    batch.setContentHash(contentHash(parseResult, priceMonth, businessUnitType));
    batch.setUploadedBy(normalize(request.getUploadedBy()));
    batch.setStatus(STATUS_PENDING);
    batch.setFactorSheetCount(parseResult == null ? 0 : parseResult.getSheets().size());
    batch.setLinkedSheetCount(0);
    batch.setFactorRowCount(parseResult == null ? 0 : parseResult.getValidRowCount());
    batch.setLinkedRowCount(0);
    batch.setAutoBindingCount(0);
    batch.setWarningCount(0);
    batch.setErrorCount(parseResult == null ? 0 : parseResult.getErrorCount());
    batch.setStartedAt(now);
    batch.setCreatedAt(now);
    batch.setUpdatedAt(now);
    factorUploadBatchMapper.insert(batch);
    return batch;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FactorRowRefSaveResult saveRowRefs(
      Long factorUploadBatchId,
      FactorWorkbookParseResult parseResult,
      FactorMonthlyPriceUpsertResult upsertResult) {
    FactorRowRefSaveResult result = new FactorRowRefSaveResult();
    result.setFactorUploadBatchId(factorUploadBatchId);
    if (factorUploadBatchId == null) {
      result.getErrors().add(new FactorRowRefSaveResult.RowError(
          null, null, "factorUploadBatchId 必填"));
      return result;
    }
    if (upsertResult == null || upsertResult.getRows().isEmpty()) {
      updateBatchStatus(factorUploadBatchId, result);
      return result;
    }

    Map<String, FactorRowParseResult> parseRows = parseRowsByCell(parseResult);
    for (FactorMonthlyPriceUpsertResult.RowResult rowResult : upsertResult.getRows()) {
      saveOneRowRef(factorUploadBatchId, parseResult, rowResult, parseRows, result);
    }
    updateBatchStatus(factorUploadBatchId, result);
    return result;
  }

  @Override
  public FactorRowRef findRowRef(
      Long factorUploadBatchId,
      String sourceSheetName,
      Integer sourceRowNumber) {
    if (factorUploadBatchId == null
        || !StringUtils.hasText(sourceSheetName)
        || sourceRowNumber == null) {
      return null;
    }
    return factorRowRefMapper.selectOne(
        Wrappers.lambdaQuery(FactorRowRef.class)
            .eq(FactorRowRef::getFactorUploadBatchId, factorUploadBatchId)
            .eq(FactorRowRef::getSourceSheetName, normalize(sourceSheetName))
            .eq(FactorRowRef::getSourceRowNumber, sourceRowNumber)
            .last("LIMIT 1"));
  }

  private void saveOneRowRef(
      Long factorUploadBatchId,
      FactorWorkbookParseResult parseResult,
      FactorMonthlyPriceUpsertResult.RowResult rowResult,
      Map<String, FactorRowParseResult> parseRows,
      FactorRowRefSaveResult result) {
    if (rowResult.getFactorIdentityId() == null || rowResult.getFactorMonthlyPriceId() == null) {
      result.setSkippedCount(result.getSkippedCount() + 1);
      result.getErrors().add(new FactorRowRefSaveResult.RowError(
          rowResult.getSourceSheetName(), rowResult.getSourceRowNumber(),
          "影响因素身份或月度价格 id 为空"));
      return;
    }
    String sheetName = normalize(rowResult.getSourceSheetName());
    Integer rowNumber = rowResult.getSourceRowNumber();
    if (!StringUtils.hasText(sheetName) || rowNumber == null) {
      result.setSkippedCount(result.getSkippedCount() + 1);
      result.getErrors().add(new FactorRowRefSaveResult.RowError(
          rowResult.getSourceSheetName(), rowResult.getSourceRowNumber(),
          "sheetName 或 rowNumber 为空"));
      return;
    }

    FactorRowParseResult parseRow = parseRows.get(cellKey(sheetName, rowNumber));
    FactorRowRef rowRef = new FactorRowRef();
    rowRef.setFactorUploadBatchId(factorUploadBatchId);
    rowRef.setSourceWorkbookName(parseResult == null ? null : normalize(parseResult.getSourceFileName()));
    rowRef.setSourceSheetName(sheetName);
    rowRef.setSourceRowNumber(rowNumber);
    rowRef.setFactorIdentityId(rowResult.getFactorIdentityId());
    rowRef.setFactorMonthlyPriceId(rowResult.getFactorMonthlyPriceId());
    if (parseRow != null) {
      rowRef.setFactorSeqNo(normalize(parseRow.getFactorSeqNo()));
      rowRef.setFactorName(normalize(parseRow.getFactorName()));
      rowRef.setShortName(normalize(parseRow.getShortName()));
      rowRef.setPriceSource(normalize(parseRow.getPriceSource()));
      rowRef.setPrice(normalizePrice(parseRow.getPrice()));
      rowRef.setOriginalPrice(normalizePrice(parseRow.getOriginalPrice()));
      rowRef.setUnit(normalize(parseRow.getUnit()));
    }
    rowRef.setCreatedAt(LocalDateTime.now());

    FactorRowRef existing = findRowRef(factorUploadBatchId, sheetName, rowNumber);
    if (existing == null) {
      factorRowRefMapper.insert(rowRef);
      result.setInsertedCount(result.getInsertedCount() + 1);
      return;
    }
    rowRef.setId(existing.getId());
    rowRef.setCreatedAt(existing.getCreatedAt());
    factorRowRefMapper.updateById(rowRef);
    result.setUpdatedCount(result.getUpdatedCount() + 1);
  }

  private void updateBatchStatus(Long factorUploadBatchId, FactorRowRefSaveResult result) {
    FactorUploadBatch batch = factorUploadBatchMapper.selectById(factorUploadBatchId);
    if (batch == null) {
      result.getErrors().add(new FactorRowRefSaveResult.RowError(
          null, null, "上传批次不存在：" + factorUploadBatchId));
      return;
    }
    int errorCount = nullToZero(batch.getErrorCount()) + result.getErrors().size();
    batch.setErrorCount(errorCount);
    batch.setFactorRowCount(result.getSavedCount() + result.getSkippedCount());
    batch.setStatus(resolveStatus(result.getSavedCount(), errorCount));
    batch.setFinishedAt(LocalDateTime.now());
    batch.setUpdatedAt(LocalDateTime.now());
    factorUploadBatchMapper.updateById(batch);
  }

  private String resolveStatus(int savedCount, int errorCount) {
    if (errorCount == 0) {
      return STATUS_SUCCESS;
    }
    return savedCount > 0 ? STATUS_PARTIAL : STATUS_FAILED;
  }

  private Map<String, FactorRowParseResult> parseRowsByCell(FactorWorkbookParseResult parseResult) {
    Map<String, FactorRowParseResult> rows = new HashMap<>();
    if (parseResult == null) {
      return rows;
    }
    for (FactorSheetParseResult sheet : parseResult.getSheets()) {
      for (FactorRowParseResult row : sheet.getRows()) {
        if (row.getSourceRowNumber() == null) {
          continue;
        }
        String sheetName = normalize(row.getSourceSheetName());
        if (!StringUtils.hasText(sheetName)) {
          sheetName = normalize(sheet.getSheetName());
        }
        rows.put(cellKey(sheetName, row.getSourceRowNumber()), row);
      }
    }
    return rows;
  }

  private String contentHash(
      FactorWorkbookParseResult parseResult,
      String priceMonth,
      String businessUnitType) {
    List<String> parts = new ArrayList<>();
    parts.add(normalize(priceMonth));
    parts.add(normalize(businessUnitType));
    if (parseResult != null) {
      for (FactorSheetParseResult sheet : parseResult.getSheets()) {
        for (FactorRowParseResult row : sheet.getRows()) {
          parts.add(String.join("|",
              normalize(row.getFactorSeqNo()),
              normalize(row.getFactorName()),
              normalize(row.getShortName()),
              normalize(row.getPriceSource()),
              normalizePriceText(row.getPrice())));
        }
      }
    }
    parts.sort(Comparator.naturalOrder());
    return sha256(String.join("\n", parts));
  }

  private String nextBatchNo() {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    return "FUB" + LocalDateTime.now().format(BATCH_TIME_FORMAT) + suffix;
  }

  private String cellKey(String sheetName, Integer rowNumber) {
    return normalize(sheetName) + "#" + rowNumber;
  }

  private String requireText(String value, String message) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(message);
    }
    return value;
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

  private String normalizePriceText(BigDecimal price) {
    BigDecimal normalized = normalizePrice(price);
    return normalized == null ? "" : normalized.toPlainString();
  }

  private int nullToZero(Integer value) {
    return value == null ? 0 : value;
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }
}
