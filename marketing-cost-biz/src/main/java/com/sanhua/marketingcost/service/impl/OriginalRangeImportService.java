package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser.Rule;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import com.sanhua.marketingcost.service.pricing.OriginalRuleGroups;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 完整文件预检与不可变发布。行情规则发布不会写入财务公共因素。 */
@Service
public class OriginalRangeImportService {
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingState pendingState;

  private final OriginalRangeWorkbookParser parser;
  private final OriginalRangeRuleCalculator calculator;
  private final OriginalRangeRuleSnapshot snapshots;
  private final FinanceFactorPriceReader finance;
  private final FactorIdentityMapper identities;
  private final FactorUploadBatchMapper batches;
  private final PriceRangeFactorRuleMapper rules;
  private final PriceRangeItemMapper bands;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;
  private final com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService
      effectivePrices;

  public OriginalRangeImportService(
      OriginalRangeWorkbookParser parser,
      OriginalRangeRuleCalculator calculator,
      OriginalRangeRuleSnapshot snapshots,
      FinanceFactorPriceReader finance,
      FactorIdentityMapper identities,
      FactorUploadBatchMapper batches,
      PriceRangeFactorRuleMapper rules,
      PriceRangeItemMapper bands,
      ObjectMapper json,
      PlatformTransactionManager manager,
      com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService effectivePrices) {
    this.parser = parser;
    this.calculator = calculator;
    this.snapshots = snapshots;
    this.finance = finance;
    this.identities = identities;
    this.batches = batches;
    this.rules = rules;
    this.bands = bands;
    this.json = json;
    this.transaction = new TransactionTemplate(manager);
    this.effectivePrices = effectivePrices;
    transaction.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public Preview preview(Command command) {
    return prepare(command, scope(command)).preview();
  }

  public PublishedRule detail(Long id) {
    var item = requireOriginal(id, true);
    var saved = snapshots.read(item);
    return new PublishedRule(
        item.getId(),
        item.getPricingMonth(),
        item.getSourceFile(),
        saved,
        calculator.extrapolation(saved.rule()));
  }

  public TemplateFile supplementTemplate(Long id) {
    return currentWorkbook(requireOriginal(id));
  }

  public List<RuleOption> options(String pricingMonth, String materialCode) {
    String month = FactorPriceScope.month(pricingMonth);
    String code = materialCode == null ? "" : materialCode.trim();
    if (code.isEmpty() || code.length() > 64) throw new IllegalArgumentException("请填写有效料号");
    String businessUnit = BusinessUnitContext.getCurrentBusinessUnitType();
    if (businessUnit == null) throw new IllegalArgumentException("请先选择业务单元");
    var candidates = rules.selectList(Wrappers.lambdaQuery(PriceRangeFactorRule.class)
        .eq(PriceRangeFactorRule::getBusinessUnitType, businessUnit)
        .eq(PriceRangeFactorRule::getPricingMonth, month)
        .eq(PriceRangeFactorRule::getMaterialCode, code)
        .in(PriceRangeFactorRule::getRuleStatus, List.of("PUBLISHED", "VOIDED"))
        .isNotNull(PriceRangeFactorRule::getSourceUploadBatchId)
        .orderByDesc(PriceRangeFactorRule::getId));
    return OriginalRuleGroups.ranges(candidates).stream()
        .map(row -> new RuleOption(row.getId(), row.getMaterialCode(), row.getMaterialName(),
            row.getSupplierCode(), row.getSupplierName(), row.getSpecModel(), row.getOrgCode(),
            row.getSourceFile()))
        .toList();
  }

  public Publication addBand(Long id, BandAddition request) {
    var reference = requireOriginal(id);
    if (request == null) throw new IllegalArgumentException("请填写新增档位及单价");
    var bounds = OriginalRangeBandWorkbook.bounds(
        request.low(), request.high(), request.includeLow(), request.includeHigh());
    var file = currentWorkbook(reference);
    var bytes = OriginalRangeBandWorkbook.append(
        file.bytes(), snapshots.read(reference).rule(), bounds, request.price());
    var command = new Command(bytes, file.name(), reference.getBusinessUnitType(),
        reference.getPricingMonth(), null, null, id);
    var preview = preview(command);
    return confirm(new Command(bytes, file.name(), command.businessUnitType(), command.pricingMonth(),
        preview.fileSha256(), preview.previewFingerprint(), id));
  }

  private TemplateFile currentWorkbook(PriceRangeFactorRule item) {
    var current = OriginalRuleGroups.ranges(publishedVersions(item));
    if (!containsRule(current, item.getId()))
      throw new IllegalArgumentException("区间价已有新版本，请从列表中最新原稿补充");
    requireOneWorkbook(current);
    var file = batches.findSourceWorkbook(item.getSourceUploadBatchId());
    if (file == null || file.getSourceWorkbook() == null || file.getSourceWorkbook().length == 0)
      throw new IllegalArgumentException("此区间原稿未留存 Excel，请使用原始采购价表补充档位");
    return new TemplateFile(file.getFileName(), file.getSourceWorkbook());
  }

  private PriceRangeFactorRule requireOriginal(Long id) {
    return requireOriginal(id, false);
  }

  private PriceRangeFactorRule requireOriginal(Long id, boolean history) {
    var item = rules.selectById(id);
    if (!OriginalRangeRuleSnapshot.isOriginal(item)
        && !(history && item != null && item.getSourceUploadBatchId() != null && "VOIDED".equals(item.getRuleStatus())))
      throw new IllegalArgumentException("区间原稿规则不存在");
    String current = BusinessUnitContext.getCurrentBusinessUnitType();
    if (current != null
        && !BusinessUnitContext.isAdmin()
        && !current.equals(item.getBusinessUnitType()))
      throw new IllegalArgumentException("不能读取其他业务单元的区间原稿");
    return item;
  }

  public Publication confirm(Command command) {
    Scope scope = scope(command);
    if (!PriceLinkedImportFileDigest.sha256(command.bytes()).equals(command.expectedSha256()))
      throw new IllegalArgumentException("文件与预检摘要不一致，请重新预检");
    if (command.previewFingerprint() == null || command.previewFingerprint().isBlank())
      throw new IllegalArgumentException("发布区间原稿必须携带预检版本");
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return transaction.execute(
            status -> {
              identities.lockForFinancePublication(scope.businessUnit());
              Prepared checked = prepare(command, scope);
              if (!checked.preview().canConfirm())
                throw new IllegalArgumentException("区间原稿存在错误，整批未发布：" + checked.preview().errors());
              var currentBatchIds =
                  checked.previous().values().stream()
                      .map(PriceRangeFactorRule::getSourceUploadBatchId)
                      .filter(Objects::nonNull)
                      .distinct()
                      .toList();
              var replay =
                  currentBatchIds.isEmpty()
                      ? List.<FactorUploadBatch>of()
                      : batches.selectList(
                          Wrappers.lambdaQuery(FactorUploadBatch.class)
                              .eq(FactorUploadBatch::getBusinessUnitType, scope.businessUnit())
                              .eq(FactorUploadBatch::getPriceMonth, scope.month())
                              .eq(FactorUploadBatch::getImportType, "RANGE_ORIGINAL")
                              .eq(FactorUploadBatch::getStatus, "PUBLISHED")
                              .eq(FactorUploadBatch::getFileSha256, command.expectedSha256())
                              .in(FactorUploadBatch::getId, currentBatchIds));
              var replayIds =
                  replay.stream()
                      .map(FactorUploadBatch::getId)
                      .collect(java.util.stream.Collectors.toSet());
              if (checked.voidedBands().isEmpty() && !replay.isEmpty()
                  && replay.size() == organizations(checked).size()
                  && checked.sources().values().stream().map(rows -> rows.getFirst().identity().identityKey())
                      .allMatch(
                          key -> {
                            var prior = checked.previous().get(key);
                            return prior != null
                                && replayIds.contains(prior.getSourceUploadBatchId());
                          }))
                return complete(
                    new Publication(
                        replay.stream().map(FactorUploadBatch::getId).toList(),
                        checked.preview().businessRowCount(),
                        checked.sources().size(),
                        true),
                    scope,
                    checked);
              if (!checked.preview().previewFingerprint().equals(command.previewFingerprint()))
                throw new IllegalArgumentException("文件、月份、财务基价或区间规则版本已变化，请重新预检");
              return complete(save(command, scope, checked), scope, checked);
            });
      } catch (PessimisticLockingFailureException error) {
        if (attempt == 2) throw error;
      }
    }
    throw new IllegalStateException("区间发布事务未完成");
  }

  private Prepared prepare(Command command, Scope scope) {
    return prepare(command, scope, null);
  }

  public Publication stageSelectedInTransaction(Command command, Set<String> selectedRows) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("选择发布必须与文件夹处理结果在同一事务内保存");
    if (selectedRows == null || selectedRows.isEmpty()) throw new IllegalArgumentException("未选择区间原稿来源行");
    Scope scope = scope(command);
    identities.lockForFinancePublication(scope.businessUnit());
    Prepared checked = prepare(command, scope, selectedRows);
    if (!checked.preview().canConfirm())
      throw new IllegalArgumentException("所选区间行无法发布：" + checked.preview().errors());
    if (checked.preview().businessRowCount() != selectedRows.size())
      throw new IllegalArgumentException("所选来源行包含无价或缺失定位，未发布");
    var selectedKeys = checked.sources().values().stream().map(rows -> rows.getFirst().identity().identityKey()).collect(java.util.stream.Collectors.toSet());
    boolean replay = checked.voidedBands().isEmpty() && selectedKeys.stream().allMatch(key -> {
      var prior = checked.previous().get(key);
      return prior != null && !"VOIDED".equals(prior.getRuleStatus()) && checked.inputFingerprint().equals(snapshots.read(prior).inputFingerprint());
    });
    if (replay) return new Publication(checked.previous().values().stream()
        .filter(r -> selectedKeys.contains(r.getRuleIdentityKey()))
        .map(PriceRangeFactorRule::getSourceUploadBatchId).distinct().toList(),
        checked.preview().businessRowCount(), checked.sources().size(), true);
    return save(command, scope, checked, "STAGED");
  }

  private Prepared prepare(Command command, Scope scope, Set<String> selectedRows) {
    var reference = command.supplementRuleId() == null ? null : requireOriginal(command.supplementRuleId());
    if (reference != null && (!scope.businessUnit().equals(reference.getBusinessUnitType())
        || !scope.month().equals(reference.getPricingMonth())))
      throw new IllegalArgumentException("补充档位的业务单元和月份必须与所选规则一致");
    var parsed = parser.parse(command.bytes(), command.fileName(), scope.businessUnit());
    String fileHash = PriceLinkedImportFileDigest.sha256(command.bytes());
    String inputFingerprint;
    if (reference != null) {
      inputFingerprint = hash(List.of(fileHash, command.fileName(), scope, OriginalRangeRuleSnapshot.VERSION,
          reference.getId()));
      parsed = prepareSupplement(parsed, reference, inputFingerprint);
    } else {
      inputFingerprint = hash(List.of(fileHash, command.fileName(), scope, OriginalRangeRuleSnapshot.VERSION,
          selectedRows == null ? List.of() : selectedRows.stream().sorted().toList()));
      if (selectedRows != null) parsed = new OriginalRangeWorkbookParser.ParsedWorkbook(
        parsed.rules().stream().filter(r -> selectedRows.contains(r.sheet() + "!" + r.row())).toList(),
        parsed.errors().stream().filter(e -> e.row() == null || selectedRows.contains(e.sheet() + "!" + e.row())).toList());
    }
    List<OriginalRangeWorkbookParser.RowError> errors = new ArrayList<>(parsed.errors());
    List<PreviewRow> rows = new ArrayList<>();
    Map<String, PriceFactorContext> financial = new TreeMap<>();
    for (String org :
        parsed.rules().stream().map(r -> r.identity().orgCode()).distinct().sorted().toList()) {
      try {
        financial.put(
            org,
            finance.readDefault(
                new FinanceFactorImportContext(
                    scope.businessUnit(), org, scope.month(), null, null),
                "IMPORT_PREVIEW"));
      } catch (FinanceFactorPriceReader.MissingPublicationException waiting) {
        // 区间矩阵可以独立保存，财务行情发布后才选档并生成有效单价。
      } catch (IllegalArgumentException error) {
        errors.add(new OriginalRangeWorkbookParser.RowError(null, null, null, error.getMessage()));
      }
    }
    Map<String, PriceRangeFactorRule> previous = new LinkedHashMap<>();
    var incomingIdentities =
        parsed.rules().stream().map(r -> r.identity().identityKey()).distinct().toList();
    if (!incomingIdentities.isEmpty())
      for (var item :
          rules.selectList(
              Wrappers.lambdaQuery(PriceRangeFactorRule.class)
                  .eq(PriceRangeFactorRule::getBusinessUnitType, scope.businessUnit())
                  .eq(PriceRangeFactorRule::getPricingMonth, scope.month())
                  .in(PriceRangeFactorRule::getRuleIdentityKey, incomingIdentities)
                  .in(PriceRangeFactorRule::getRuleStatus, selectedRows != null ? List.of("PUBLISHED", "VOIDED", "STAGED") : List.of("PUBLISHED", "VOIDED")))) {
        // 不让MySQL对大JSON来源快照做文件排序；只读取本次身份并显式选最高修订。
        previous.merge(
            item.getRuleIdentityKey(),
            item,
            (left, right) -> left.getVersionNo() >= right.getVersionNo() ? left : right);
      }
    Map<String, List<Rule>> sources = new LinkedHashMap<>();
    var signatures = new com.sanhua.marketingcost.service.pricing.OriginalRuleSignature(json, calculator);
    Map<String, Long> previousIds = new TreeMap<>();
    for (Rule rule : parsed.rules()) {
      String key = rule.identity().identityKey();
      var group = sources.computeIfAbsent(key + ":" + signatures.range(rule), ignored -> new ArrayList<>());
      group.add(rule);
      var prior = previous.get(key);
      previousIds.put(key, prior == null ? 0L : ("VOIDED".equals(prior.getRuleStatus()) ? -prior.getId() : prior.getId()));
      OriginalRangeRuleCalculator.Result result = null;
      String issue = null;
      try {
        var base = financial.get(rule.identity().orgCode());
        if (base != null)
          result =
              calculator.calculate(
                  rule,
                  new PriceFactorContext(
                      rule.identity().materialCode(), base.months(), base.financial(), null));
      } catch (IllegalArgumentException error) {
        issue = error.getMessage();
        errors.add(
            new OriginalRangeWorkbookParser.RowError(
                rule.sheet(), rule.row(), rule.identity().materialCode(), issue));
      }
      rows.add(
          new PreviewRow(
              rule,
              result,
              calculator.extrapolation(rule),
              previous.containsKey(key) ? "REVISION" : "NEW",
              issue));
    }
    Map<String, Object> financeVersions = new TreeMap<>();
    financial.forEach(
        (org, context) ->
            financeVersions.put(
                org,
                List.of(
                    context.financial().batchId(),
                    context.financial().revision(),
                    context.financial().scope())));
    List<Long> voidedBands=previous.isEmpty()?List.of():bands.selectList(Wrappers.lambdaQuery(PriceRangeItem.class)
        .select(PriceRangeItem::getId).apply("row_voided=1")
        .in(PriceRangeItem::getFactorRuleId,previous.values().stream().map(PriceRangeFactorRule::getId).toList())
        .orderByAsc(PriceRangeItem::getId)).stream().map(PriceRangeItem::getId).toList();
    boolean replay = voidedBands.isEmpty() && unchangedImport(sources, previous, fileHash);
    var preview =
        new Preview(
            fileHash,
            hash(List.of(inputFingerprint, previousIds, financeVersions, voidedBands)),
            scope.month(),
            parsed.rules().size() + parsed.errors().size(),
            sources.size(),
            !parsed.rules().isEmpty() && errors.isEmpty(),
            rows.stream()
                .map(
                    row ->
                        new PreviewRow(
                            row.rule(),
                            row.result(),
                            row.extrapolation(),
                            replay ? "UNCHANGED" : row.action(),
                            row.error()))
                .toList(),
            List.copyOf(errors),
            Collections.unmodifiableMap(financial), parsed.notices());
    return new Prepared(preview, sources, previous, financial, inputFingerprint,voidedBands);
  }

  private OriginalRangeWorkbookParser.ParsedWorkbook prepareSupplement(
      OriginalRangeWorkbookParser.ParsedWorkbook parsed, PriceRangeFactorRule reference, String inputFingerprint) {
    var versions = publishedVersions(reference);
    var current = OriginalRuleGroups.ranges(versions);
    if (!containsRule(current, reference.getId())) {
      boolean replay = !current.isEmpty()
          && current.stream().allMatch(item -> inputFingerprint.equals(snapshots.read(item).inputFingerprint()));
      if (!replay)
        throw new IllegalArgumentException("区间价已有新版本，请刷新列表后从最新原稿补充");
    }
    int revision = OriginalRuleGroups.revision(reference);
    var referenced = versions.stream()
        .filter(item -> OriginalRuleGroups.revision(item) == revision)
        .toList();
    requireOneWorkbook(referenced);
    var originalRows = referenced.stream()
        .flatMap(item -> snapshots.read(item).sources().stream())
        .toList();
    return OriginalRangeSupplement.prepare(parsed, originalRows);
  }

  private List<PriceRangeFactorRule> publishedVersions(PriceRangeFactorRule reference) {
    return rules.selectList(Wrappers.lambdaQuery(PriceRangeFactorRule.class)
        .eq(PriceRangeFactorRule::getBusinessUnitType, reference.getBusinessUnitType())
        .eq(PriceRangeFactorRule::getPricingMonth, reference.getPricingMonth())
        .eq(PriceRangeFactorRule::getRuleIdentityKey, reference.getRuleIdentityKey())
        .in(PriceRangeFactorRule::getRuleStatus, List.of("PUBLISHED", "VOIDED")));
  }

  private static boolean containsRule(List<PriceRangeFactorRule> candidates, Long id) {
    return candidates.stream().anyMatch(item -> Objects.equals(item.getId(), id));
  }

  private static void requireOneWorkbook(List<PriceRangeFactorRule> rules) {
    if (rules.stream().map(PriceRangeFactorRule::getSourceUploadBatchId).distinct().count() > 1)
      throw new IllegalArgumentException("所选规则有多份原稿并行生效，请在月度价格文件夹中补充");
  }

  private boolean unchangedImport(
      Map<String, List<Rule>> sources,
      Map<String, PriceRangeFactorRule> previous,
      String fileHash) {
    var ids =
        previous.values().stream()
            .map(PriceRangeFactorRule::getSourceUploadBatchId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    var sameFile =
        ids.isEmpty()
            ? Set.<Long>of()
            : batches.selectBatchIds(ids).stream()
                .filter(batch -> fileHash.equals(batch.getFileSha256()))
                .map(FactorUploadBatch::getId)
                .collect(java.util.stream.Collectors.toSet());
    return !sources.isEmpty()
        && sources.values().stream().map(rows -> rows.getFirst().identity().identityKey())
            .allMatch(
                key -> {
                  var prior = previous.get(key);
                  return prior != null && sameFile.contains(prior.getSourceUploadBatchId());
                });
  }

  private Publication save(Command command, Scope scope, Prepared prepared) {
    return save(command, scope, prepared, "PUBLISHED");
  }

  private Publication save(Command command, Scope scope, Prepared prepared, String status) {
    LocalDateTime now = LocalDateTime.now();
    var revisions = com.sanhua.marketingcost.service.pricing.OriginalRuleRevisions.ranges(rules, scope.businessUnit(), scope.month());
    var groupRevisions = new java.util.HashMap<String, Integer>();
    Map<String, FactorUploadBatch> saved = new LinkedHashMap<>();
    String workbookGroup = UUID.randomUUID().toString();
    for (String org : organizations(prepared)) {
      var batch = new FactorUploadBatch();
      batch.setBatchNo("RANGE-ORIGINAL-" + UUID.randomUUID());
      batch.setImportType("RANGE_ORIGINAL");
      batch.setRangeWorkbookGroup(workbookGroup);
      batch.setImportPurpose("MATERIAL_RULE");
      batch.setBusinessUnitType(scope.businessUnit());
      batch.setPriceOrgCode(org);
      batch.setPriceMonth(scope.month());
      batch.setFileName(command.fileName());
      batch.setFileSha256(prepared.preview().fileSha256());
      batch.setSourceWorkbook(command.bytes());
      batch.setContentHash(prepared.preview().previewFingerprint());
      batch.setParserVersion(OriginalRangeRuleSnapshot.VERSION);
      batch.setStatus(status);
      var auth = SecurityContextHolder.getContext().getAuthentication();
      batch.setUploadedBy(auth == null ? "system" : auth.getName());
      batch.setLinkedRowCount(
          (int)
              prepared.preview().rows().stream()
                  .filter(r -> r.rule().identity().orgCode().equals(org))
                  .count());
      batch.setLinkedSheetCount(1);
      batch.setFactorRowCount(0);
      batch.setErrorCount(0);
      batch.setWarningCount(
          (int)
              prepared.preview().rows().stream()
                  .filter(r -> r.rule().identity().orgCode().equals(org))
                  .flatMap(r -> r.rule().bands().stream())
                  .filter(b -> b.problem() != null)
                  .count());
      batch.setStartedAt(now);
      batch.setFinishedAt(now);
      batch.setPublishedAt(status.equals("PUBLISHED") ? now : null);
      batch.setCreatedAt(now);
      batch.setUpdatedAt(now);
      batches.insert(batch);
      saved.put(org, batch);
    }
    for (var sourceRows : prepared.sources().values()) {
      Rule rule = sourceRows.getFirst();
      var key = rule.identity();
      var batch = saved.get(key.orgCode());
      var item = new PriceRangeFactorRule();
      item.setBusinessUnitType(key.businessUnitType());
      item.setOrgCode(key.orgCode());
      item.setMaterialCode(key.materialCode());
      item.setMaterialName(rule.materialName());
      item.setSupplierCode(key.supplierCode());
      item.setSupplierName(key.supplierName());
      item.setSpecModel(key.specification());
      item.setPriceUnit(key.unit());
      item.setFactorCode(rule.factorKey());
      item.setFactorName(rule.factorKey());
      item.setFactorUnit(rule.factorUnit().name());
      item.setPricingMonth(scope.month());
      int nextRevision = revisions.merge(key.identityKey(), 1, Integer::sum);
      int groupRevision = groupRevisions.computeIfAbsent(key.identityKey(), ignored -> nextRevision);
      item.setVersionNo(nextRevision);
      item.setRuleIdentityKey(key.identityKey());
      item.setRuleStatus(status);
      item.setSourceUploadBatchId(batch.getId());
      item.setImportBatchNo(batch.getBatchNo());
      item.setSourceFile(command.fileName());
      item.setSourceSheet(rule.sheet());
      item.setSourceInputSnapshotJson(com.sanhua.marketingcost.service.pricing.OriginalRuleGroups.withRevision(
          snapshots.write(sourceRows, prepared.inputFingerprint()), groupRevision));
      var proof = calculator.extrapolation(rule);
      item.setExtrapolationJson(proof == null ? null : serialize(proof));
      item.setEffectiveFrom(YearMonth.parse(scope.month()).atDay(1));
      item.setCurrentFlag(status.equals("PUBLISHED") ? 1 : 0);
      item.setCreatedAt(now);
      item.setUpdatedAt(now);
      rules.insert(item);
      for (var band : rule.bands()) {
        var row = new PriceRangeItem();
        row.setBusinessUnitType(key.businessUnitType());
        row.setOrgCode(key.orgCode());
        row.setMaterialCode(key.materialCode());
        row.setMaterialName(rule.materialName());
        row.setSpecModel(key.specification());
        row.setSupplierCode(key.supplierCode());
        row.setSupplierName(key.supplierName());
        row.setUnit(key.unit());
        row.setRangeBasis("FACTOR");
        row.setFactorRuleId(item.getId());
        row.setFactorCode(rule.factorKey());
        row.setImportBatchNo(batch.getBatchNo());
        row.setCurrentFlag(status.equals("PUBLISHED") ? 1 : 0);
        row.setOrderType("区间");
        row.setSourceName("供管部原稿");
        row.setRangeLow(band.bounds().low());
        row.setRangeHigh(band.bounds().high());
        row.setLowerInclusive(band.bounds().lowerInclusive() ? 1 : 0);
        row.setUpperInclusive(band.bounds().upperInclusive() ? 1 : 0);
        row.setTaxIncluded(rule.taxIncluded() ? 1 : 0);
        if (band.price() != null) {
          row.setPriceExclTax(
              rule.taxIncluded()
                  ? band.price().divide(new BigDecimal("1.13"), MathContext.DECIMAL128)
                  : band.price());
          row.setPriceInclTax(
              rule.taxIncluded() ? band.price() : band.price().multiply(new BigDecimal("1.13")));
        }
        row.setSourceSheetName(band.address().sheet());
        row.setSourceCellRef(band.address().cell());
        if (band.source() != null) {
          row.setSourceValueText(band.source().rawValue());
          row.setSourceFormulaExpr(band.source().formula());
          row.setSourceNumberFormat(band.source().numberFormat());
        }
        row.setEffectiveFrom(item.getEffectiveFrom());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        bands.insert(row);
      }
    }
    return new Publication(
        saved.values().stream().map(FactorUploadBatch::getId).toList(),
        prepared.preview().businessRowCount(),
        prepared.sources().size(),
        false);
  }

  private Scope scope(Command command) {
    if (command == null || command.bytes() == null || command.bytes().length == 0)
      throw new IllegalArgumentException("原稿文件不能为空");
    if (command.fileName() == null || command.fileName().isBlank())
      throw new IllegalArgumentException("原稿文件名不能为空");
    String current = BusinessUnitContext.getCurrentBusinessUnitType();
    String selected =
        command.businessUnitType() == null || command.businessUnitType().isBlank()
            ? current
            : command.businessUnitType().trim().toUpperCase(Locale.ROOT);
    if (selected == null || !Set.of("COMMERCIAL", "HOUSEHOLD").contains(selected))
      throw new IllegalArgumentException("业务单元无效");
    if (current != null && !BusinessUnitContext.isAdmin() && !current.equalsIgnoreCase(selected))
      throw new IllegalArgumentException("不能为其他业务单元导入原稿");
    return new Scope(selected, FactorPriceScope.month(command.pricingMonth()));
  }

  private List<String> organizations(Prepared prepared) {
    return prepared.sources().values().stream()
        .map(rows -> rows.getFirst().identity().orgCode())
        .distinct()
        .sorted()
        .toList();
  }

  private Publication complete(Publication publication, Scope scope, Prepared prepared) {
    if(pendingState!=null) pendingState.changed(new com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingState.Change(java.util.Set.of("PRICE"),scope.businessUnit(),java.util.Set.of(),java.util.Set.of()));
    var generation =
        new com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService.Generation(
            0, 0, 0);
    for (String org : organizations(prepared))
      generation = generation.plus(effectivePrices.generateScope(scope.businessUnit(), org));
    return new Publication(
        publication.batchIds(),
        publication.businessRowCount(),
        publication.ruleCount(),
        publication.replay(),
        generation);
  }

  private String serialize(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception error) {
      throw new IllegalStateException("无法序列化区间原稿", error);
    }
  }

  private String hash(Object value) {
    return PriceLinkedImportFileDigest.sha256(
        serialize(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  public record Command(
      byte[] bytes,
      String fileName,
      String businessUnitType,
      String pricingMonth,
      String expectedSha256,
      String previewFingerprint,
      Long supplementRuleId) {
    public Command(byte[] bytes, String fileName, String businessUnitType, String pricingMonth,
        String expectedSha256, String previewFingerprint) {
      this(bytes, fileName, businessUnitType, pricingMonth, expectedSha256, previewFingerprint, null);
    }
  }

  public record TemplateFile(String name, byte[] bytes) {}

  public record BandAddition(BigDecimal low, BigDecimal high, boolean includeLow, boolean includeHigh,
      BigDecimal price) {}

  public record RuleOption(Long id, String materialCode, String materialName, String supplierCode,
      String supplierName, String specModel, String orgCode, String sourceFile) {}

  public record Preview(
      String fileSha256,
      String previewFingerprint,
      String pricingMonth,
      int businessRowCount,
      int ruleCount,
      boolean canConfirm,
      List<PreviewRow> rows,
      List<OriginalRangeWorkbookParser.RowError> errors,
      Map<String, PriceFactorContext> financial,
      List<com.sanhua.marketingcost.formula.workbook.OriginalPriceTable.RowNotice> notices) {}

  public record PreviewRow(
      Rule rule,
      OriginalRangeRuleCalculator.Result result,
      OriginalRangeRuleCalculator.Extrapolation extrapolation,
      String action,
      String error) {}

  public record Publication(
      List<Long> batchIds,
      int businessRowCount,
      int ruleCount,
      boolean replay,
      com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService.Generation
          effectivePrices) {
    private Publication(List<Long> batchIds, int businessRowCount, int ruleCount, boolean replay) {
      this(batchIds, businessRowCount, ruleCount, replay, null);
    }
  }

  public record PublishedRule(
      Long id,
      String pricingMonth,
      String sourceFile,
      OriginalRangeRuleSnapshot.SavedRule saved,
      OriginalRangeRuleCalculator.Extrapolation extrapolation) {}

  private record Scope(String businessUnit, String month) {}

  private record Prepared(
      Preview preview,
      Map<String, List<Rule>> sources,
      Map<String, PriceRangeFactorRule> previous,
      Map<String, PriceFactorContext> financial,
      String inputFingerprint,List<Long> voidedBands) {}
}
