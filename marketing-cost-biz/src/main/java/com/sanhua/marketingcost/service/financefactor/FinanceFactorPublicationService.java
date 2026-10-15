package com.sanhua.marketingcost.service.financefactor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.dto.FinanceFactorImportPreview;
import com.sanhua.marketingcost.dto.FinanceFactorImportPreview.Change;
import com.sanhua.marketingcost.dto.FinanceFactorWorkbook;
import com.sanhua.marketingcost.dto.FinanceFactorWorkbook.Value;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.FactorRowRef;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.mapper.FactorRowRefMapper;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.service.FactorCanonicalKeyService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 预检不写库；确认时在一个事务中保存整个财务发布及所有来源，已发布行只追加修订。 */
@Service
public class FinanceFactorPublicationService {
  private final FinanceFactorWorkbookParser parser;
  private final FactorCanonicalKeyService canonicalKeys;
  private final FactorIdentityMapper identities;
  private final FactorUploadBatchMapper batches;
  private final FactorMonthlyPriceMapper prices;
  private final FactorRowRefMapper sources;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;
  private final com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService
      effectivePrices;

  public FinanceFactorPublicationService(
      FinanceFactorWorkbookParser parser,
      FactorCanonicalKeyService canonicalKeys,
      FactorIdentityMapper identities,
      FactorUploadBatchMapper batches,
      FactorMonthlyPriceMapper prices,
      FactorRowRefMapper sources,
      ObjectMapper json,
      PlatformTransactionManager transactions,
      com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService effectivePrices) {
    this.parser = parser;
    this.canonicalKeys = canonicalKeys;
    this.identities = identities;
    this.batches = batches;
    this.prices = prices;
    this.sources = sources;
    this.json = json;
    this.transaction = new TransactionTemplate(transactions);
    this.effectivePrices = effectivePrices;
  }

  public FinanceFactorImportPreview preview(
      byte[] file, String fileName, FinanceFactorImportContext context) {
    FinanceFactorWorkbook workbook =
        parser.parse(file, fileName, context.priceOrgCode(), context.sheetName());
    return previewPrepared(workbook, context, null, "FINANCE_WORKBOOK");
  }

  public Publication publish(
      byte[] file,
      String fileName,
      FinanceFactorImportContext context,
      String previewFingerprint,
      String operator) {
    FinanceFactorWorkbook workbook =
        parser.parse(file, fileName, context.priceOrgCode(), context.sheetName());
    return publishPrepared(
        workbook, context, previewFingerprint, operator, null, "FINANCE_WORKBOOK");
  }

  FinanceFactorImportPreview previewPrepared(
      FinanceFactorWorkbook workbook,
      FinanceFactorImportContext context,
      String publicationNote,
      String importPurpose) {
    return preview(
        workbook,
        context,
        latest(context),
        catalog(context.businessUnitType()),
        publicationNote,
        importPurpose);
  }

  Publication publishPrepared(
      FinanceFactorWorkbook workbook,
      FinanceFactorImportContext context,
      String previewFingerprint,
      String operator,
      String publicationNote,
      String importPurpose) {
    if (previewFingerprint == null || previewFingerprint.isBlank()) {
      throw new IllegalArgumentException("请先预检并确认财务基价");
    }
    if (operator == null || operator.isBlank()) throw new IllegalArgumentException("发布人不能为空");
    if (!workbook.problems().isEmpty()) {
      var problem = workbook.problems().getFirst();
      throw new IllegalArgumentException(
          "财务基价存在错误，整批未发布："
              + problem.sheetName()
              + "!"
              + problem.cellRef()
              + " "
              + problem.message());
    }
    // 空目录的首次并发发布可能产生InnoDB间隙锁死锁；整次事务回滚后重读，最多尝试三次。
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return transaction.execute(
            status -> {
              List<FactorIdentity> catalog =
                  new ArrayList<>(identities.lockForFinancePublication(context.businessUnitType()));
              FactorUploadBatch previous = latest(context);
              FinanceFactorImportPreview checked =
                  preview(workbook, context, previous, catalog, publicationNote, importPurpose);
              if (previous != null && checked.contentHash().equals(previous.getContentHash())) {
                return complete(
                    new Publication(
                        previous.getId(),
                        previous.getPublicationRevision(),
                        true,
                        previous.getFactorRowCount()),
                    context);
              }
              if (!checked.previewFingerprint().equals(previewFingerprint)) {
                throw new IllegalArgumentException("文件、默认方案或有效财务版本已变化，请重新预检后发布");
              }
              return complete(save(checked, catalog, operator.trim()), context);
            });
      } catch (PessimisticLockingFailureException conflict) {
        if (attempt == 2) throw conflict;
      }
    }
    throw new IllegalStateException("财务发布事务未完成");
  }

  public FactorUploadBatch latest(FinanceFactorImportContext context) {
    return batches.selectOne(
        Wrappers.lambdaQuery(FactorUploadBatch.class)
            .eq(FactorUploadBatch::getBusinessUnitType, context.businessUnitType())
            .eq(FactorUploadBatch::getPriceOrgCode, context.priceOrgCode())
            .eq(FactorUploadBatch::getPriceMonth, context.priceMonth())
            .eq(FactorUploadBatch::getImportType, "FINANCE_BASE")
            .eq(FactorUploadBatch::getStatus, "PUBLISHED")
            .isNotNull(FactorUploadBatch::getPublishedAt)
            .orderByDesc(FactorUploadBatch::getPublicationRevision)
            .last("LIMIT 1"));
  }

  private Publication complete(Publication publication, FinanceFactorImportContext context) {
    var generated =
        effectivePrices.generateScope(context.businessUnitType(), context.priceOrgCode());
    return new Publication(
        publication.batchId(),
        publication.revision(),
        publication.unchanged(),
        publication.sourceRowCount(),
        generated);
  }

  private FinanceFactorImportPreview preview(
      FinanceFactorWorkbook workbook,
      FinanceFactorImportContext requested,
      FactorUploadBatch previous,
      List<FactorIdentity> catalog,
      String publicationNote,
      String importPurpose) {
    String defaultScheme = requested.defaultSchemeCode();
    if (defaultScheme == null
        && previous != null
        && workbook.schemes().stream()
            .anyMatch(s -> s.code().equals(previous.getDefaultSchemeCode())))
      defaultScheme = previous.getDefaultSchemeCode();
    if (defaultScheme == null) defaultScheme = workbook.schemes().getFirst().code();
    final String selected = defaultScheme;
    if (workbook.schemes().stream().noneMatch(s -> s.code().equals(selected))) {
      throw new IllegalArgumentException("所选默认方案不存在：" + selected);
    }
    var context =
        new FinanceFactorImportContext(
            requested.businessUnitType(),
            requested.priceOrgCode(),
            requested.priceMonth(),
            workbook.sheetName(),
            selected);
    Map<String, Object> logical = new LinkedHashMap<>();
    logical.put("context", context);
    logical.put("parserVersion", workbook.parserVersion());
    logical.put("values", workbook.values());
    logical.put("publicationNote", publicationNote);
    logical.put("importPurpose", importPurpose);
    String contentHash = hash(logical);
    String fingerprint =
        hash(List.of(contentHash, workbook.fileSha256(), previous == null ? 0L : previous.getId()));
    return new FinanceFactorImportPreview(
        context,
        workbook,
        previous == null ? null : previous.getId(),
        previous == null ? null : previous.getPublicationRevision(),
        contentHash,
        fingerprint,
        changes(workbook, previous, catalog),
        publicationNote,
        importPurpose);
  }

  private List<Change> changes(
      FinanceFactorWorkbook workbook, FactorUploadBatch previous, List<FactorIdentity> catalog) {
    Map<Long, String> identityKeys = new LinkedHashMap<>();
    catalog.forEach(i -> identityKeys.put(i.getId(), i.getCanonicalFactorKey()));
    Map<String, FactorMonthlyPrice> old = new LinkedHashMap<>();
    if (previous != null) {
      for (var price :
          prices.selectList(
              Wrappers.lambdaQuery(FactorMonthlyPrice.class)
                  .eq(FactorMonthlyPrice::getSourceUploadBatchId, previous.getId())
                  .eq(FactorMonthlyPrice::getStatus, "PUBLISHED"))) {
        String key = identityKeys.get(price.getFactorIdentityId());
        if (key == null)
          throw new IllegalStateException("已发布财务因素缺少主身份键：" + price.getFactorIdentityId());
        old.put(priceKey(key, price.getSchemeCode(), price.getConditionCode()), price);
      }
    }
    List<Change> result = new ArrayList<>();
    Map<String, Value> unique = uniqueValues(workbook);
    for (var entry : unique.entrySet()) {
      Value value = entry.getValue();
      FactorMonthlyPrice prior = old.remove(entry.getKey());
      String kind =
          prior == null
              ? "NEW"
              : prior.getPrice().compareTo(value.price()) == 0
                      && Objects.equals(prior.getUnit(), value.unit())
                  ? "UNCHANGED"
                  : "CHANGED";
      result.add(
          new Change(
              value.canonicalKey(),
              value.schemeCode(),
              value.condition().name(),
              prior == null ? null : prior.getPrice(),
              value.price(),
              value.unit(),
              kind));
    }
    for (var prior : old.values()) {
      result.add(
          new Change(
              identityKeys.get(prior.getFactorIdentityId()),
              prior.getSchemeCode(),
              prior.getConditionCode(),
              prior.getPrice(),
              null,
              prior.getUnit(),
              "REMOVED"));
    }
    return result;
  }

  private Publication save(
      FinanceFactorImportPreview preview, List<FactorIdentity> catalog, String operator) {
    var context = preview.context();
    var workbook = preview.workbook();
    LocalDateTime now = LocalDateTime.now();
    FactorUploadBatch batch = new FactorUploadBatch();
    batch.setBatchNo("FIN-" + UUID.randomUUID());
    batch.setImportType("FINANCE_BASE");
    batch.setImportPurpose(preview.importPurpose());
    batch.setPublicationNote(preview.publicationNote());
    batch.setBusinessUnitType(context.businessUnitType());
    batch.setPriceOrgCode(context.priceOrgCode());
    batch.setPriceMonth(context.priceMonth());
    batch.setPublicationRevision(
        preview.previousRevision() == null ? 1 : preview.previousRevision() + 1);
    batch.setDefaultSchemeCode(context.defaultSchemeCode());
    batch.setParserVersion(workbook.parserVersion());
    batch.setFileName(workbook.fileName());
    batch.setFileSha256(workbook.fileSha256());
    batch.setContentHash(preview.contentHash());
    batch.setUploadedBy(operator);
    batch.setStatus("DRAFT");
    batch.setStartedAt(now);
    batch.setFactorSheetCount(1);
    batch.setFactorRowCount(workbook.values().size());
    requireOne(batches.insert(batch), "创建财务发布批次");
    Map<String, FactorMonthlyPrice> saved = new LinkedHashMap<>();
    for (var entry : uniqueValues(workbook).entrySet()) {
      Value value = entry.getValue();
      FactorIdentity identity = identity(value, context.businessUnitType(), catalog, operator);
      FactorMonthlyPrice price = new FactorMonthlyPrice();
      price.setFactorIdentityId(identity.getId());
      price.setPriceOrgCode(context.priceOrgCode());
      price.setPriceMonth(context.priceMonth());
      price.setSchemeCode(value.schemeCode());
      price.setConditionCode(value.condition().name());
      price.setRevisionNo(batch.getPublicationRevision());
      price.setPrice(value.price());
      price.setUnit(value.unit());
      price.setTaxIncluded(value.taxIncluded() ? 1 : 0);
      price.setSourceUploadBatchId(batch.getId());
      price.setSourceTag("FINANCE_BASE");
      price.setStatus("PUBLISHED");
      price.setPublishedAt(now);
      price.setCreatedBy(operator);
      requireOne(prices.insert(price), "保存财务因素价格：" + value.canonicalKey());
      saved.put(entry.getKey(), price);
    }
    for (Value value : workbook.values()) {
      FactorMonthlyPrice price =
          saved.get(priceKey(value.canonicalKey(), value.schemeCode(), value.condition().name()));
      FactorRowRef source = new FactorRowRef();
      source.setFactorUploadBatchId(batch.getId());
      source.setSourceWorkbookName(workbook.fileName());
      source.setSourceSheetName(workbook.sheetName());
      source.setSourceRowNumber(value.rowNumber());
      source.setSourceColumnNumber(value.columnNumber());
      source.setSourceCellRef(value.cellRef());
      source.setFactorIdentityId(price.getFactorIdentityId());
      source.setFactorMonthlyPriceId(price.getId());
      source.setFactorName(value.factorName());
      source.setShortName(value.shortName());
      source.setPriceSource(value.priceSource());
      source.setSchemeCode(value.schemeCode());
      source.setConditionCode(value.condition().name());
      source.setOriginalPrice(value.originalValue());
      source.setPrice(value.price());
      source.setUnit(value.unit());
      source.setSourceUnit(value.sourceUnit());
      source.setSourceValueText(value.sourceValueText());
      source.setSourceFormulaExpr(value.sourceFormula());
      source.setSourceNumberFormat(value.numberFormat());
      source.setConversionBasis(value.conversionBasis());
      requireOne(sources.insert(source), "保存财务源格：" + value.cellRef());
    }
    batch.setStatus("PUBLISHED");
    batch.setPublishedAt(now);
    batch.setFinishedAt(now);
    requireOne(batches.updateById(batch), "发布财务基价");
    return new Publication(
        batch.getId(), batch.getPublicationRevision(), false, workbook.values().size());
  }

  private FactorIdentity identity(
      Value value, String bu, List<FactorIdentity> catalog, String operator) {
    List<FactorIdentity> masters =
        catalog.stream()
            .filter(
                i ->
                    value.canonicalKey().equals(i.getCanonicalFactorKey())
                        && Objects.equals(i.getId(), i.getCanonicalFactorIdentityId()))
            .toList();
    if (masters.size() > 1) throw new IllegalStateException("财务因素存在多个主身份：" + value.canonicalKey());
    if (masters.size() == 1) {
      if (!"ACTIVE".equals(masters.getFirst().getStatus())) {
        throw new IllegalArgumentException("财务因素主身份已停用：" + value.canonicalKey());
      }
      return masters.getFirst();
    }
    List<FactorIdentity> candidates =
        catalog.stream()
            .filter(i -> "ACTIVE".equals(i.getStatus()))
            .filter(
                i ->
                    value
                        .canonicalKey()
                        .equals(canonicalKeys.build(i.getPriceSource(), i.getShortName())))
            .toList();
    if (candidates.size() == 1 && candidates.getFirst().getCanonicalFactorIdentityId() == null) {
      FactorIdentity existing = candidates.getFirst();
      existing.setCanonicalFactorKey(value.canonicalKey());
      existing.setCanonicalFactorIdentityId(existing.getId());
      requireOne(identities.updateById(existing), "关联既有财务因素主身份");
      return existing;
    }
    // 多个旧来源不合并、不重绑；建立一个稳定的财务主身份，旧记录继续保留自己的来源。
    FactorIdentity created = new FactorIdentity();
    created.setBusinessUnitType(bu);
    created.setFactorSeqNo("FINANCE_" + hash(value.canonicalKey()).substring(0, 24));
    created.setFactorName(value.factorName());
    created.setShortName(value.shortName());
    created.setPriceSource(value.priceSource());
    created.setCanonicalFactorKey(value.canonicalKey());
    created.setIdentityOrigin("FINANCE_BASE");
    created.setStatus("ACTIVE");
    created.setCreatedBy(operator);
    requireOne(identities.insert(created), "创建财务因素主身份");
    created.setCanonicalFactorIdentityId(created.getId());
    requireOne(identities.updateById(created), "记录财务因素主身份");
    catalog.add(created);
    return created;
  }

  private List<FactorIdentity> catalog(String bu) {
    return identities.selectList(
        Wrappers.lambdaQuery(FactorIdentity.class).eq(FactorIdentity::getBusinessUnitType, bu));
  }

  private Map<String, Value> uniqueValues(FinanceFactorWorkbook workbook) {
    Map<String, Value> values = new LinkedHashMap<>();
    workbook
        .values()
        .forEach(
            v ->
                values.putIfAbsent(
                    priceKey(v.canonicalKey(), v.schemeCode(), v.condition().name()), v));
    return values;
  }

  private static String priceKey(String canonicalKey, String scheme, String condition) {
    return canonicalKey.length() + ":" + canonicalKey + "|" + scheme + "|" + condition;
  }

  private String hash(Object value) {
    try {
      return FinanceFactorWorkbookParser.sha256(
          json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("财务基价输入摘要生成失败", error);
    }
  }

  private static void requireOne(int count, String operation) {
    if (count != 1) throw new IllegalStateException(operation + "失败：影响行数=" + count);
  }

  public record Publication(
      Long batchId,
      Integer revision,
      boolean unchanged,
      Integer sourceRowCount,
      com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService.Generation
          effectivePrices) {
    private Publication(Long batchId, Integer revision, boolean unchanged, Integer sourceRowCount) {
      this(batchId, revision, unchanged, sourceRowCount, null);
    }
  }
}
