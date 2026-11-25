package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.service.pricing.settlement.SettlementScope;
import java.util.*;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 原件和结算规则的导入边界。预览只读，确认在一个数据库事务内追加修订。 */
@Service
public class SettlementPriceImportService {
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingState pendingState;

  @org.springframework.beans.factory.annotation.Autowired
  private com.sanhua.marketingcost.service.pricing.monthly.VoidedSourceMonthlyPrices voidedMonthlyPrices;

  public record Preview(SettlementWorkbookParser.ParsedWorkbook workbook, String fingerprint) {}
  public record ImportResult(int inserted, int revised, int unchanged, int sourceRows, List<Long> ruleIds, SettlementEffectivePriceService.Generation effectivePrices) {
    public ImportResult { ruleIds = List.copyOf(ruleIds); }
  }
  public record OriginalFile(String fileName, byte[] bytes) {}
  private final SettlementWorkbookParser parser;
  private final SettlementPriceRepository prices;
  private final SettlementRuleRevisionFactory revisions;
  private final SettlementWorkbookStore files;
  private final TransactionTemplate transaction;
  private final SettlementEffectivePriceService effectivePrices;

  public SettlementPriceImportService(SettlementWorkbookParser parser, SettlementPriceRepository prices,
      SettlementRuleRevisionFactory revisions, SettlementWorkbookStore files,
      PlatformTransactionManager transactions, SettlementEffectivePriceService effectivePrices) {
    this.effectivePrices = effectivePrices;
    this.parser = parser;
    this.prices = prices;
    this.revisions = revisions;
    this.files = files;
    this.transaction = new TransactionTemplate(transactions);
    this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public Preview preview(byte[] bytes, String fileName, SettlementScope scope) {
    var book = parser.parse(bytes, SettlementScope.required(fileName, "原件文件名", 255));
    return new Preview(book, fingerprint(book, scope, heads(book, scope)));
  }

  public ImportResult confirm(byte[] bytes, String fileName, SettlementScope scope, String expectedFingerprint, String actor) {
    var book = parser.parse(bytes, SettlementScope.required(fileName, "原件文件名", 255));
    String operator = SettlementScope.required(actor, "导入人", 64);
    try {
      return transaction.execute(status -> {
        effectivePrices.lock(scope.businessUnitType());
        var current = heads(book, scope);
        if (!fingerprint(book, scope, current).equals(expectedFingerprint)) throw conflict();
        // 不变内容的归档可共享；数据库回滚时可留下未引用原件，绝不发布部分价格。
        files.save(scope.businessUnitType(), bytes);
        String batchToken = UUID.randomUUID().toString();
        int inserted = 0, revised = 0, unchanged = 0;
        List<Long> ids = new ArrayList<>();
        for (var rule : book.rules().stream().sorted(Comparator.comparing(SettlementWorkbookParser.Rule::materialCode)
            .thenComparing(r -> r.priceType().name())).toList()) {
          var old = current.get(new SettlementPriceRepository.RuleKey(rule.materialCode(), rule.priceType()));
          boolean withdrawn = old != null && old.getVoidedAt() != null;
          boolean sameMonth = old != null && old.getPriceMonth().equals(scope.priceMonth());
          var candidate = revisions.fromImport(rule, book, scope, withdrawn ? null : old, operator);
          candidate.setImportBatchToken(batchToken);
          if (sameMonth && withdrawn) candidate.setRevisionNo(old.getRevisionNo() + 1);
          if (sameMonth && !withdrawn && old.getContentHash().equals(candidate.getContentHash())) {
            unchanged++;
            ids.add(old.getId());
          } else {
            ids.add(prices.insert(candidate).getId());
            if (sameMonth) revised++; else inserted++;
          }
        }
        if(pendingState!=null) pendingState.changed(new com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingState.Change(java.util.Set.of("PRICE"),scope.businessUnitType(),book.rules().stream().map(SettlementWorkbookParser.Rule::materialCode).collect(java.util.stream.Collectors.toSet()),java.util.Set.of()));
        return new ImportResult(inserted, revised, unchanged, book.sourceRows().size(), ids,
            effectivePrices.generate(scope.businessUnitType(), operator));
      });
    } catch (DuplicateKeyException | ConcurrencyFailureException changed) { throw conflict(); }
  }

  public SettlementPriceRepository.ImportPage imports(String unit, int page, int size) {
    if (page < 1 || page > 1_000_000 || size < 1 || size > 100)
      throw new IllegalArgumentException("请填写有效页码，每页1至100条");
    return prices.imports(unit, page, size);
  }

  public void voidRow(String unit, long id, String actor) {
    String operator=SettlementScope.required(actor,"操作人",64);
    transaction.executeWithoutResult(status->{
      effectivePrices.lock(unit);
      var rule=prices.byId(unit,id).orElseThrow(SettlementPriceImportService::missingImport);
      if(rule.getVoidedAt()!=null)return;
      prices.voidRules(unit,List.of(id),operator);
      voidedMonthlyPrices.settlementRow(unit,id);
      if(pendingState!=null)pendingState.changed(new com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingState.Change(
          Set.of("PRICE"),unit,Set.of(rule.getMaterialCode()),Set.of()));
    });
  }

  public void voidImport(String unit, long id, String actor) {
    String operator = SettlementScope.required(actor, "操作人", 64);
    try {
      transaction.executeWithoutResult(status -> {
        effectivePrices.lock(unit);
        var original = prices.byId(unit, id).orElseThrow(SettlementPriceImportService::missingImport);
        var rows = prices.lockImport(unit, original).stream().filter(row -> row.getVoidedAt() == null).toList();
        if (rows.isEmpty()) return;
        var ids = rows.stream().map(SettlementPriceRecord::getId).toList();
        prices.voidRules(unit, ids, operator);
        voidedMonthlyPrices.settlement(unit, ids);
        if (pendingState != null) pendingState.changed(new com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingState.Change(
            Set.of("PRICE"), unit, rows.stream().map(SettlementPriceRecord::getMaterialCode).collect(java.util.stream.Collectors.toSet()), Set.of()));
      });
    } catch (ConcurrencyFailureException changed) { throw conflict(); }
  }

  private static org.springframework.web.server.ResponseStatusException missingImport() {
    return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "结算价格导入记录不存在");
  }

  public OriginalFile original(String businessUnit, long id) {
    var row = prices.byId(businessUnit, id).orElseThrow(() -> new IllegalArgumentException("结算价格不存在"));
    return new OriginalFile(row.getSourceFileName(), files.read(businessUnit, row.getSourceFileSha256()));
  }

  private Map<SettlementPriceRepository.RuleKey, SettlementPriceRecord> heads(SettlementWorkbookParser.ParsedWorkbook book, SettlementScope scope) {
    return prices.importHeads(scope, book.rules().stream().map(SettlementWorkbookParser.Rule::materialCode).distinct().toList());
  }

  private String fingerprint(SettlementWorkbookParser.ParsedWorkbook book, SettlementScope scope,
      Map<SettlementPriceRepository.RuleKey, SettlementPriceRecord> heads) {
    Map<String, Object> values = new TreeMap<>();
    values.put("scope", scope);
    values.put("file", book.sha256());
    var versions = heads.values().stream().sorted(Comparator.comparing(SettlementPriceRecord::getId))
        .map(row -> Map.of("id", row.getId(), "hash", row.getContentHash(), "voided", row.getVoidedAt() != null)).toList();
    values.put("versions", versions);
    return revisions.hash(values);
  }

  private OptimisticLockingFailureException conflict() {
    return new OptimisticLockingFailureException("结算原稿或重量已变化，请重新预览后导入");
  }
}
