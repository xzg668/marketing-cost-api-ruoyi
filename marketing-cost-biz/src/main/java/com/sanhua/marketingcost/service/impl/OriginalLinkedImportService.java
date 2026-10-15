package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportCommand;
import com.sanhua.marketingcost.dto.PriceLinkedType2ImportPreviewResponse;
import com.sanhua.marketingcost.dto.PriceLinkedType2ImportPreviewResponse.RowPreview;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.impl.OriginalLinkedWorkbookParser.Rule;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 公共联动原稿：完整只读预检、确认时版本复核、整批追加规则；不写公共因素价格。 */
@Service
public class OriginalLinkedImportService {
  private static final BigDecimal VAT = new BigDecimal("1.13");
  private final OriginalLinkedWorkbookParser parser;
  private final OriginalLinkedRuleCalculator calculator;
  private final OriginalLinkedRuleSnapshot snapshots;
  private final FinanceFactorPriceReader finance;
  private final FactorIdentityMapper identities;
  private final FactorUploadBatchMapper batches;
  private final PriceLinkedItemMapper items;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;
  private final com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService
      effectivePrices;

  public OriginalLinkedImportService(
      OriginalLinkedWorkbookParser parser,
      OriginalLinkedRuleCalculator calculator,
      OriginalLinkedRuleSnapshot snapshots,
      FinanceFactorPriceReader finance,
      FactorIdentityMapper identities,
      FactorUploadBatchMapper batches,
      PriceLinkedItemMapper items,
      ObjectMapper json,
      PlatformTransactionManager transactions,
      com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService effectivePrices) {
    this.parser = parser;
    this.calculator = calculator;
    this.snapshots = snapshots;
    this.finance = finance;
    this.identities = identities;
    this.batches = batches;
    this.items = items;
    this.json = json;
    this.transaction = new TransactionTemplate(transactions);
    this.effectivePrices = effectivePrices;
    // 分流器的技术补录事务不承载公共发布；每次冲突重试必须开启独立事务。
    this.transaction.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public PriceLinkedType2ImportPreviewResponse preview(PriceLinkedImportCommand command) {
    return prepare(command, scope(command)).preview();
  }

  public PriceItemImportResponse confirm(PriceLinkedImportCommand command) {
    Scope scope = scope(command);
    String fileHash = PriceLinkedImportFileDigest.sha256(command.getFileBytes());
    if (!fileHash.equalsIgnoreCase(Objects.toString(command.getExpectedPreviewSha256(), "")))
      throw new IllegalArgumentException("文件SHA-256与预检不一致，请重新预检");
    if (command.getPreviewFingerprint() == null || command.getPreviewFingerprint().isBlank())
      throw new IllegalArgumentException("确认原稿导入必须携带完整预检版本，请先预检");
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return transaction.execute(
            status -> {
              // 与财务发布共用目录锁，保证复核至保存期间财务版本不被另一个发布改变。
              identities.lockForFinancePublication(scope.businessUnit());
              Prepared checked = prepare(command, scope);
              if (!checked.preview().isCanConfirm())
                throw new IllegalArgumentException(
                    "原稿预检存在错误，整批未发布：" + checked.preview().getErrors().getFirst().getMessage());
              var currentBatchIds =
                  checked.previous().values().stream()
                      .map(PriceLinkedItem::getSourceUploadBatchId)
                      .filter(Objects::nonNull)
                      .distinct()
                      .toList();
              List<FactorUploadBatch> replay =
                  currentBatchIds.isEmpty()
                      ? List.of()
                      : batches.selectList(
                          Wrappers.lambdaQuery(FactorUploadBatch.class)
                              .eq(FactorUploadBatch::getBusinessUnitType, scope.businessUnit())
                              .in(FactorUploadBatch::getPriceOrgCode, scope.orgs())
                              .eq(FactorUploadBatch::getPriceMonth, scope.month())
                              .eq(FactorUploadBatch::getImportType, "LINKED_ORIGINAL")
                              .eq(FactorUploadBatch::getStatus, "PUBLISHED")
                              .eq(FactorUploadBatch::getFileSha256, fileHash)
                              .in(FactorUploadBatch::getId, currentBatchIds)
                              .orderByDesc(FactorUploadBatch::getId));
              var replayIds =
                  replay.stream()
                      .map(FactorUploadBatch::getId)
                      .collect(java.util.stream.Collectors.toSet());
              if (replay.size() == scope.orgs().size()
                  && checked.rules().stream()
                      .allMatch(
                          rule -> {
                            var prior = checked.previous().get(rule.identity().identityKey());
                            return prior != null
                                && replayIds.contains(prior.getSourceUploadBatchId());
                          }))
                return complete(
                    response(
                        replay,
                        true,
                        (int)
                            checked.rules().stream()
                                .map(r -> r.identity().identityKey())
                                .distinct()
                                .count()),
                    scope);
              if (!checked
                  .preview()
                  .getPreviewFingerprint()
                  .equals(command.getPreviewFingerprint()))
                throw new IllegalArgumentException("原稿、导入月份、财务基价或物料规则版本已变化，请重新预检后发布");
              return complete(save(command, scope, checked), scope);
            });
      } catch (PessimisticLockingFailureException error) {
        if (attempt == 2) throw error;
      }
    }
    throw new IllegalStateException("原稿发布事务未完成");
  }

  private Prepared prepare(PriceLinkedImportCommand command, Scope scope) {
    var preview = new PriceLinkedType2ImportPreviewResponse();
    preview.setTemplateType("ORIGINAL");
    preview.setExpressionVersion(OriginalLinkedRuleSnapshot.VERSION);
    preview.setFileSha256(PriceLinkedImportFileDigest.sha256(command.getFileBytes()));
    Map<String, java.util.Set<String>> keysByOrg = new LinkedHashMap<>();
    Map<String, PriceFactorContext> financialByOrg = new LinkedHashMap<>();
    for (String org : scope.orgs()) {
      keysByOrg.put(
          org, new LinkedHashSet<>(identities.findFinanceManagedKeys(scope.businessUnit(), org)));
      try {
        financialByOrg.put(
            org,
            finance.readDefault(
                new FinanceFactorImportContext(
                    scope.businessUnit(), org, scope.month(), null, null),
                "IMPORT_PREVIEW"));
      } catch (FinanceFactorPriceReader.MissingPublicationException waiting) {
        // 文件结构与原输入仍可验证；原始工作簿保留至财务发布时绑定因素。
      } catch (IllegalArgumentException issue) {
        error(preview, command, null, null, null, issue.getMessage());
      }
    }
    var parsed =
        parser.parseWithCatalog(
            command.getFileBytes(),
            command.getSourceFileName(),
            scope.businessUnit(),
            keysByOrg::get);
    for (var issue : parsed.errors())
      error(preview, command, issue.sheet(), issue.row(), issue.materialCode(), issue.message());
    var previous = latest(scope);
    Map<String, Long> previousIds = new LinkedHashMap<>();
    Map<String, Rule> seen = new LinkedHashMap<>();
    var factorCells = new LinkedHashSet<String>();
    for (Rule rule : parsed.rules()) {
      var key = rule.identity();
      var row = new RowPreview();
      row.setRuleIdentity(key.identityKey());
      row.setOrgCode(key.orgCode());
      row.setUnit(key.unit());
      row.setSourceCell(rule.cell());
      row.setSourceSheetName(rule.sheet());
      row.setSourceRowNumber(rule.row());
      row.setMaterialCode(key.materialCode());
      row.setSupplierCode(key.supplierCode());
      row.setSupplierName(key.supplierName());
      row.setSourceFormula(rule.sourceFormula());
      row.setSystemFormula(rule.compiled().expression());
      row.setTaxIncluded(rule.taxIncluded() ? 1 : 0);
      row.setExcelTaxIncludedPrice(
          rule.taxIncluded() ? rule.originalPrice() : rule.originalPrice().multiply(VAT));
      row.setExcelTaxExcludedPrice(
          rule.taxIncluded()
              ? rule.originalPrice().divide(VAT, MathContext.DECIMAL128)
              : rule.originalPrice());
      row.setMatchStatus("MATCHED");
      preview.getRows().add(row);
      rule.factors().values().forEach(factor -> factorCells.add(factor.address().toString()));
      var prior = previous.get(key.identityKey());
      previousIds.put(key.identityKey(), prior == null ? 0L : prior.getId());
      Rule repeated = seen.putIfAbsent(key.identityKey(), rule);
      if (repeated != null) {
        preview.setDuplicateRowCount(preview.getDuplicateRowCount() + 1);
        if (!sameCalculation(repeated, rule)) {
          error(
              preview,
              command,
              rule.sheet(),
              rule.row(),
              key.materialCode(),
              "同一组织/料号/供应商/规格/单位存在不同规则，不能按文件顺序覆盖");
          row.setMatchStatus("CONFLICT");
          continue;
        }
        row.setMatchStatus("DUPLICATE_SOURCE");
      }
      PriceFactorContext financial = financialByOrg.get(key.orgCode());
      if (financial == null) {
        row.setImportable(true);
        row.setMatchStatus("WAIT_FINANCE_BASE");
        row.setMessage("原稿可保存，待该组织财务基价发布后生成有效价");
        continue;
      }
      try {
        var context =
            new PriceFactorContext(
                key.materialCode(), financial.months(), financial.financial(), null);
        var result = calculator.calculate(rule, context);
        row.setCalculatedInputs(result.inputs());
        row.setOriginalFactors(
            rule.factors().entrySet().stream()
                .map(
                    entry -> {
                      var factor = entry.getValue();
                      return new RowPreview.OriginalFactor(
                          factor.canonicalKey(),
                          factor.unit().name(),
                          factor.address().sheet(),
                          factor.address().cell(),
                          rule.compiled().sourceInputs().get(entry.getKey()),
                          result.inputs().get(entry.getKey()));
                    })
                .toList());
        row.setSystemTaxExcludedPrice(result.priceExcludingTax());
        row.setSystemTaxIncludedPrice(
            rule.taxIncluded() ? result.sourceResult() : result.sourceResult().multiply(VAT));
        row.setTaxExcludedDifference(
            row.getSystemTaxExcludedPrice().subtract(row.getExcelTaxExcludedPrice()));
        row.setTaxIncludedDifference(
            row.getSystemTaxIncludedPrice().subtract(row.getExcelTaxIncludedPrice()));
        row.setImportable(true);
        row.setMessage(
            "原输入核对通过；复算使用财务基价 "
                + financial.months().sourceMonth()
                + " / "
                + financial.financial().scope().schemeCode());
      } catch (IllegalArgumentException issue) {
        error(preview, command, rule.sheet(), rule.row(), key.materialCode(), issue.getMessage());
        row.setMessage(issue.getMessage());
      }
    }
    preview.setBusinessRowCount(parsed.rules().size() + parsed.errors().size());
    preview.setMatchedRowCount(
        (int) preview.getRows().stream().filter(RowPreview::isImportable).count());
    preview.setFormulaConvertedCount(parsed.rules().size());
    preview.setFormulaMismatchCount(parsed.errors().size());
    preview.setFactorRowCount(factorCells.size());
    preview.setCanConfirm(!parsed.rules().isEmpty() && preview.getErrors().isEmpty());
    if (!parsed.rules().isEmpty()) {
      preview.setImportDataSheetName(parsed.rules().getFirst().sheet());
      preview.setBusinessSheetName(parsed.rules().getFirst().sheet());
    }
    preview.setDetectionMessage("原稿实际公式已逐行解析；采购原输入仅供核对，公共因素使用已发布财务基价");
    Map<String, Object> financeVersions = new LinkedHashMap<>();
    financialByOrg.forEach(
        (org, context) ->
            financeVersions.put(
                org, List.of(context.financial().batchId(), context.financial().scope())));
    String inputVersionFingerprint =
        hash(
            List.of(
                preview.getFileSha256(),
                Objects.toString(command.getSourceFileName(), ""),
                scope,
                OriginalLinkedRuleSnapshot.VERSION));
    preview.setPreviewFingerprint(
        hash(List.of(inputVersionFingerprint, previousIds, financeVersions, keysByOrg)));
    preview.setFinancial(financialByOrg);
    var sourceIds =
        previous.values().stream()
            .map(PriceLinkedItem::getSourceUploadBatchId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    var sameFileBatches =
        sourceIds.isEmpty()
            ? java.util.Set.<Long>of()
            : batches.selectBatchIds(sourceIds).stream()
                .filter(batch -> preview.getFileSha256().equals(batch.getFileSha256()))
                .map(FactorUploadBatch::getId)
                .collect(java.util.stream.Collectors.toSet());
    boolean replay =
        !parsed.rules().isEmpty()
            && parsed.rules().stream()
                .allMatch(
                    rule -> {
                      var prior = previous.get(rule.identity().identityKey());
                      return prior != null
                          && sameFileBatches.contains(prior.getSourceUploadBatchId());
                    });
    for (var row : preview.getRows()) {
      row.setAction(
          replay ? "UNCHANGED" : previous.containsKey(row.getRuleIdentity()) ? "REVISION" : "NEW");
    }
    return new Prepared(preview, parsed.rules(), previous, inputVersionFingerprint);
  }

  /** 只合并表达式、动态因素绑定和税价口径相同的重复来源，不能按当前价格相等判定。 */
  private boolean sameCalculation(Rule first, Rule second) {
    return first.taxIncluded() == second.taxIncluded()
        && first.compiled().expression().equals(second.compiled().expression())
        && first.factors().equals(second.factors())
        && first.originalPrice().compareTo(second.originalPrice()) == 0;
  }

  private Map<String, PriceLinkedItem> latest(Scope scope) {
    var versions =
        items.selectList(
            Wrappers.lambdaQuery(PriceLinkedItem.class)
                .eq(PriceLinkedItem::getBusinessUnitType, scope.businessUnit())
                .in(PriceLinkedItem::getOrgCode, scope.orgs())
                .eq(PriceLinkedItem::getPricingMonth, scope.month())
                .eq(PriceLinkedItem::getSourceKind, "PUBLIC")
                .eq(PriceLinkedItem::getRuleStatus, "PUBLISHED")
                .isNotNull(PriceLinkedItem::getRuleIdentityKey)
                .orderByDesc(PriceLinkedItem::getRuleRevision));
    Map<String, PriceLinkedItem> result = new LinkedHashMap<>();
    versions.forEach(item -> result.putIfAbsent(item.getRuleIdentityKey(), item));
    return result;
  }

  private PriceItemImportResponse save(
      PriceLinkedImportCommand command, Scope scope, Prepared checked) {
    var now = LocalDateTime.now();
    Map<String, FactorUploadBatch> saved = new LinkedHashMap<>();
    for (String org : scope.orgs()) {
      var batch = new FactorUploadBatch();
      batch.setBatchNo("ORIGINAL-" + UUID.randomUUID());
      batch.setImportType("LINKED_ORIGINAL");
      batch.setImportPurpose("MATERIAL_RULE");
      batch.setBusinessUnitType(scope.businessUnit());
      batch.setPriceOrgCode(org);
      batch.setPriceMonth(scope.month());
      batch.setFileName(command.getSourceFileName());
      batch.setFileSha256(checked.preview().getFileSha256());
      batch.setContentHash(checked.preview().getPreviewFingerprint());
      batch.setParserVersion(OriginalLinkedPublicationSource.VERSION);
      batch.setSourceWorkbook(command.getFileBytes());
      var auth = SecurityContextHolder.getContext().getAuthentication();
      batch.setUploadedBy(auth == null ? "system" : auth.getName());
      batch.setStatus("PUBLISHED");
      batch.setLinkedRowCount(
          (int) checked.rules().stream().filter(r -> r.identity().orgCode().equals(org)).count());
      batch.setFactorRowCount(checked.preview().getFactorRowCount());
      batch.setLinkedSheetCount(1);
      batch.setErrorCount(0);
      batch.setWarningCount(0);
      batch.setStartedAt(now);
      batch.setFinishedAt(now);
      batch.setPublishedAt(now);
      batch.setCreatedAt(now);
      batch.setUpdatedAt(now);
      batches.insert(batch);
      saved.put(org, batch);
    }
    Map<String, List<Rule>> sourceGroups = new LinkedHashMap<>();
    checked
        .rules()
        .forEach(
            rule ->
                sourceGroups
                    .computeIfAbsent(
                        rule.identity().identityKey(), key -> new java.util.ArrayList<>())
                    .add(rule));
    for (List<Rule> sourceRows : sourceGroups.values()) {
      Rule rule = sourceRows.getFirst();
      var key = rule.identity();
      var prior = checked.previous().get(key.identityKey());
      var batch = saved.get(key.orgCode());
      var item = new PriceLinkedItem();
      item.setBusinessUnitType(key.businessUnitType());
      item.setOrgCode(key.orgCode());
      item.setMaterialCode(key.materialCode());
      item.setMaterialName(rule.materialName());
      item.setSupplierCode(key.supplierCode());
      item.setSupplierName(key.supplierName());
      item.setSpecModel(key.specification());
      item.setUnit(key.unit());
      item.setPricingMonth(scope.month());
      item.setRuleIdentityKey(key.identityKey());
      item.setRuleRevision(prior == null ? 1 : prior.getRuleRevision() + 1);
      item.setRuleStatus("PUBLISHED");
      item.setExpressionVersion(OriginalLinkedRuleSnapshot.VERSION);
      item.setSourceKind("PUBLIC");
      item.setOrderType("联动");
      item.setFormulaExpr(rule.compiled().expression());
      item.setTaxIncluded(rule.taxIncluded() ? 1 : 0);
      item.setSourceUploadBatchId(batch.getId());
      item.setSourceName("供管部原稿");
      item.setEffectiveFrom(java.time.YearMonth.parse(scope.month()).atDay(1));
      item.setSourceSheetName(rule.sheet());
      item.setSourceRowNumber(rule.row());
      item.setSourceFormulaCellRef(rule.cell());
      item.setSourceFormulaExpr(rule.sourceFormula());
      item.setSourceInputSnapshotJson(
          snapshots.write(sourceRows, checked.inputVersionFingerprint()));
      item.setManualPrice(rule.originalPrice());
      item.setSourceTaxIncludedPrice(
          rule.taxIncluded() ? rule.originalPrice() : rule.originalPrice().multiply(VAT));
      item.setSourceTaxExcludedPrice(
          rule.taxIncluded()
              ? rule.originalPrice().divide(VAT, MathContext.DECIMAL128)
              : rule.originalPrice());
      item.setDeleted(0);
      item.setCreatedAt(now);
      item.setUpdatedAt(now);
      items.insert(item);
    }
    return response(List.copyOf(saved.values()), false, sourceGroups.size());
  }

  private PriceItemImportResponse response(
      List<FactorUploadBatch> publications, boolean replay, int ruleCount) {
    var batch = publications.getFirst();
    int rowCount = publications.stream().mapToInt(FactorUploadBatch::getLinkedRowCount).sum();
    var result = new PriceItemImportResponse();
    result.setSourceUploadBatchIds(publications.stream().map(FactorUploadBatch::getId).toList());
    result.setBatchId(batch.getBatchNo());
    result.setFactorUploadBatchId(batch.getId());
    result.setFileSha256(batch.getFileSha256());
    result.setTemplateType("ORIGINAL");
    result.setImportStatus("SUCCESS");
    result.setBusinessRowCount(rowCount);
    result.setLinkedCount(replay ? 0 : ruleCount);
    result.setLinkedVersionCreatedCount(replay ? 0 : ruleCount);
    result.setLinkedUnchangedSkippedCount(replay ? ruleCount : 0);
    return result;
  }

  private PriceItemImportResponse complete(PriceItemImportResponse response, Scope scope) {
    var generation =
        new com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService.Generation(
            0, 0, 0);
    for (String org : scope.orgs())
      generation = generation.plus(effectivePrices.generateScope(scope.businessUnit(), org));
    response.setEffectivePriceGeneratedCount(generation.generated());
    response.setEffectivePriceWaitingCount(generation.waitingRules());
    response.setEffectivePriceStatus(generation.waitingRules() > 0 ? "WAIT_FINANCE_BASE" : "READY");
    return response;
  }

  private Scope scope(PriceLinkedImportCommand command) {
    if (command == null || command.getFileBytes().length == 0)
      throw new IllegalArgumentException("原稿文件不能为空");
    String current = BusinessUnitContext.getCurrentBusinessUnitType();
    String selected = command.getBusinessUnitType();
    if (selected == null || selected.isBlank()) selected = current;
    if (selected == null || selected.isBlank()) throw new IllegalArgumentException("业务单元不能为空");
    selected = selected.trim().toUpperCase(Locale.ROOT);
    if (current != null && !BusinessUnitContext.isAdmin() && !current.equalsIgnoreCase(selected))
      throw new IllegalArgumentException("不能为其他业务单元导入原稿");
    var orgs = parser.organizations(command.getFileBytes());
    var context =
        new FinanceFactorImportContext(
            selected, orgs.iterator().next(), command.getPricingMonth(), null, null);
    return new Scope(
        context.businessUnitType(),
        orgs.stream().sorted().toList(),
        FactorPriceScope.month(context.priceMonth()));
  }

  private void error(
      PriceLinkedType2ImportPreviewResponse preview,
      PriceLinkedImportCommand command,
      String sheet,
      Integer row,
      String material,
      String message) {
    var error = new PriceItemImportResponse.ErrorRow(row, material, "联动", message);
    error.setSourceWorkbookName(command.getSourceFileName());
    error.setSourceSheetName(sheet);
    error.setErrorStage("ORIGINAL_PRECHECK");
    error.setErrorCode("ORIGINAL_RULE_INVALID");
    preview.getErrors().add(error);
  }

  private String hash(Object value) {
    try {
      return PriceLinkedImportFileDigest.sha256(json.writeValueAsBytes(value));
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("无法计算原稿预检版本", error);
    }
  }

  private record Scope(String businessUnit, List<String> orgs, String month) {}

  private record Prepared(
      PriceLinkedType2ImportPreviewResponse preview,
      List<Rule> rules,
      Map<String, PriceLinkedItem> previous,
      String inputVersionFingerprint) {}
}
