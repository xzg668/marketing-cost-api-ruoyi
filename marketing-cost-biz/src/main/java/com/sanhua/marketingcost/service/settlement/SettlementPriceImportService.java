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

/** 原件、规则和基价的发布边界。预览只读，确认在一个数据库事务内追加修订。 */
@Service
public class SettlementPriceImportService {
  public record Preview(SettlementWorkbookParser.ParsedWorkbook workbook, String fingerprint) {}
  public record ImportResult(int inserted, int revised, int unchanged, int sourceRows, List<Long> ruleIds, long baseId) {
    public ImportResult { ruleIds = List.copyOf(ruleIds); }
  }
  public record OriginalFile(String fileName, byte[] bytes) {}
  private final SettlementWorkbookParser parser;
  private final SettlementPriceRepository prices;
  private final SettlementBasePriceRepository bases;
  private final SettlementRuleRevisionFactory revisions;
  private final SettlementWorkbookStore files;
  private final TransactionTemplate transaction;

  public SettlementPriceImportService(SettlementWorkbookParser parser, SettlementPriceRepository prices,
      SettlementBasePriceRepository bases, SettlementRuleRevisionFactory revisions, SettlementWorkbookStore files,
      PlatformTransactionManager transactions) {
    this.parser = parser;
    this.prices = prices;
    this.bases = bases;
    this.revisions = revisions;
    this.files = files;
    this.transaction = new TransactionTemplate(transactions);
    this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public Preview preview(byte[] bytes, String fileName, SettlementScope scope) {
    var book = parser.parse(bytes, SettlementScope.required(fileName, "原件文件名", 255));
    return new Preview(book, fingerprint(book, scope, heads(book, scope), bases.latest(scope).orElse(null)));
  }

  public ImportResult confirm(byte[] bytes, String fileName, SettlementScope scope, String expectedFingerprint, String actor) {
    var book = parser.parse(bytes, SettlementScope.required(fileName, "原件文件名", 255));
    String operator = SettlementScope.required(actor, "导入人", 64);
    try {
      return transaction.execute(status -> {
        var current = heads(book, scope);
        var base = bases.latest(scope).orElse(null);
        if (!fingerprint(book, scope, current, base).equals(expectedFingerprint)) throw conflict();
        // 不变内容的归档可共享；数据库回滚时可留下未引用原件，绝不发布部分价格。
        files.save(scope.businessUnitType(), bytes);
        int inserted = 0, revised = 0, unchanged = 0;
        List<Long> ids = new ArrayList<>();
        for (var rule : book.rules().stream().sorted(Comparator.comparing(SettlementWorkbookParser.Rule::materialCode)
            .thenComparing(r -> r.priceType().name())).toList()) {
          var old = current.get(new SettlementPriceRepository.RuleKey(rule.materialCode(), rule.priceType()));
          var candidate = revisions.fromImport(rule, book, scope, old, operator);
          boolean sameMonth = old != null && old.getPriceMonth().equals(scope.priceMonth());
          if (sameMonth && old.getContentHash().equals(candidate.getContentHash())) {
            unchanged++;
            ids.add(old.getId());
          } else {
            ids.add(prices.insert(candidate).getId());
            if (sameMonth) revised++; else inserted++;
          }
        }
        if (base == null) base = bases.insert(scope, 1,
            SettlementBasePriceService.normalizePrice(book.referenceCu(), "CNY/KG"),
            SettlementBasePriceService.normalizePrice(book.referenceZn(), "CNY/KG"),
            "IMPORT_REFERENCE", book.sha256(), "原稿参考铜锌基价首次建立", operator);
        return new ImportResult(inserted, revised, unchanged, book.sourceRows().size(), ids, base.id());
      });
    } catch (DuplicateKeyException | ConcurrencyFailureException changed) { throw conflict(); }
  }

  public OriginalFile original(String businessUnit, long id) {
    var row = prices.byId(businessUnit, id).orElseThrow(() -> new IllegalArgumentException("结算价格不存在"));
    return new OriginalFile(row.getSourceFileName(), files.read(businessUnit, row.getSourceFileSha256()));
  }

  private Map<SettlementPriceRepository.RuleKey, SettlementPriceRecord> heads(SettlementWorkbookParser.ParsedWorkbook book, SettlementScope scope) {
    return prices.importHeads(scope, book.rules().stream().map(SettlementWorkbookParser.Rule::materialCode).distinct().toList());
  }

  private String fingerprint(SettlementWorkbookParser.ParsedWorkbook book, SettlementScope scope,
      Map<SettlementPriceRepository.RuleKey, SettlementPriceRecord> heads, SettlementBasePrice base) {
    Map<String, Object> values = new TreeMap<>();
    values.put("scope", scope);
    values.put("file", book.sha256());
    values.put("baseId", base == null ? null : base.id());
    var versions = heads.values().stream().sorted(Comparator.comparing(SettlementPriceRecord::getId))
        .map(row -> Map.of("id", row.getId(), "hash", row.getContentHash())).toList();
    values.put("versions", versions);
    return revisions.hash(values);
  }

  private OptimisticLockingFailureException conflict() {
    return new OptimisticLockingFailureException("结算原稿或重量、基价已变化，请重新预览后导入");
  }
}
