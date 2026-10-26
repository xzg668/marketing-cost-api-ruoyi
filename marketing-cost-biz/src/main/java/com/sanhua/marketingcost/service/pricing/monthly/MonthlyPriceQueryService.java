package com.sanhua.marketingcost.service.pricing.monthly;

import static com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceQuery.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sanhua.marketingcost.entity.monthly.MonthlyMaterialPriceRow;
import com.sanhua.marketingcost.mapper.monthly.MonthlyPriceQueryMapper;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MonthlyPriceQueryService {
  private final MonthlyPriceQueryMapper mapper;
  private final MonthlyPriceDocumentCodec codec;

  public MonthlyPriceQueryService(MonthlyPriceQueryMapper mapper, MonthlyPriceDocumentCodec codec) {
    this.mapper = mapper;
    this.codec = codec;
  }

  public Page<Entry> list(String bu, Filter filter) {
    unit(bu);
    Objects.requireNonNull(filter);
    long total = mapper.count(bu, filter);
    return new Page<>(
        mapper.list(bu, filter).stream().map(this::entry).toList(),
        total,
        filter.page(),
        filter.size());
  }

  public Detail detail(String bu, long id) {
    var row = own(bu, id);
    return new Detail(
        entry(row), safe(codec.decode(row.getFirstUseJson(), JsonNode.class)), safe(evidence(row)));
  }

  public Page<Reference> references(String bu, long id, int page, int size) {
    pagination(page, size);
    var row = own(bu, id);
    return new Page<>(
        mapper.references(bu, row.getPricingMonth(), id, size, (long) (page - 1) * size),
        mapper.referenceCount(bu, row.getPricingMonth(), id),
        page,
        size);
  }

  private MonthlyMaterialPriceRow own(String bu, long id) {
    unit(bu);
    if (id <= 0) throw new IllegalArgumentException("月价ID必须大于0");
    var row = mapper.detail(bu, id);
    if (row == null) throw new AccessDeniedException("月价不存在或不属于当前业务单元");
    return row;
  }

  private Entry entry(MonthlyMaterialPriceRow row) {
    var evidence = evidence(row);
    var source = evidence.path("source");
    var selected =
        switch (row.getSourceType()) {
          case "HOUSEHOLD_INQUIRY" -> source.path("evidence").path("source");
          case "RANGE_LEGACY_FACTOR" -> source.path("selectedRule");
          case "SETTLE_FIXED", "SETTLE_LINKED" -> source.path("rule");
          default -> source.path("selected");
        };
    var sourceView =
        new Source(
            row.getSourceType(),
            row.getSourceRecordId(),
            first(source, "revisionNo", "version", "versionNo") != null
                ? first(source, "revisionNo", "version", "versionNo")
                : first(selected, "ruleRevision", "revisionNo", "technicalVersionId"),
            first(source, "importBatchId", "batchId") != null
                ? first(source, "importBatchId", "batchId")
                : first(selected, "sourceBatchNo", "importBatchNo", "batchNo"),
            first(selected, "importFileName", "sourceName", "sourceSystem"),
            row.getHouseholdSourceId());
    return new Entry(
        row.getId(),
        row.getPricingMonth(),
        row.getMaterialCode(),
        first(selected, "materialName", "name"),
        row.getSpecification(),
        row.getPricingUnit(),
        row.getCurrency(),
        row.getTaxBasis(),
        row.getRequestedSupplierCode(),
        row.getSupplierCode(),
        row.getSupplierName(),
        row.getPricingType(),
        decimal(row.getUnitPrice()),
        decimal(row.getHouseholdRate()),
        safe(codec.decode(row.getApplicabilityJson(), JsonNode.class)),
        sourceView,
        row.getSourceEvidenceHash(),
        row.getCreatedBy(),
        row.getCreatedAt());
  }

  private JsonNode evidence(MonthlyMaterialPriceRow row) {
    String document = codec.unwrap(row.getSourceEvidenceJson());
    if (!MonthlyPriceDocumentCodec.sha256(document).equals(row.getSourceEvidenceHash()))
      throw new IllegalStateException("月价保存依据校验失败");
    return codec.decode(row.getSourceEvidenceJson(), JsonNode.class);
  }

  /**
   * Financial-reference columns and source-file paths retain their separate inquiry permissions.
   */
  private JsonNode safe(JsonNode node) {
    if (node.isNumber()) return TextNode.valueOf(node.asText());
    if (node.isObject()) {
      var copy = JsonNodeFactory.instance.objectNode();
      node.fields()
          .forEachRemaining(
              e -> {
                if (!Set.of("fileKey", "cells", "financialReference").contains(e.getKey()))
                  copy.set(e.getKey(), safe(e.getValue()));
              });
      return copy;
    }
    if (node.isArray()) {
      var copy = JsonNodeFactory.instance.arrayNode();
      node.forEach(n -> copy.add(safe(n)));
      return copy;
    }
    return node;
  }

  private static String first(JsonNode node, String... keys) {
    for (var key : keys)
      if (node.hasNonNull(key) && !node.path(key).asText().isBlank())
        return node.path(key).asText();
    return null;
  }

  private static String decimal(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros().toPlainString();
  }

  private static void unit(String bu) {
    if (!Set.of("COMMERCIAL", "HOUSEHOLD").contains(bu == null ? "" : bu))
      throw new AccessDeniedException("缺少有效业务单元");
  }
}
