package com.sanhua.marketingcost.service.scrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.entity.ScrapPrice;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.mapper.ScrapPriceMapper;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import com.sanhua.marketingcost.service.pricing.ScrapPriceIdentity;
import com.sanhua.marketingcost.service.scrap.ScrapPriceWorkbookParser.Parsed;
import com.sanhua.marketingcost.service.scrap.ScrapPriceWorkbookParser.Problem;
import com.sanhua.marketingcost.service.scrap.ScrapPriceWorkbookParser.SourceRow;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 预检只读；确认重新比对文件与当前记录，在一个事务内增量写入。 */
@Service
public class ScrapPriceImportService {
  private final ScrapPriceWorkbookParser parser;
  private final ScrapPriceMapper prices;
  private final ScrapPriceRuleCodec rules;
  private final TransactionTemplate transaction;
  private final FactorIdentityMapper identities;
  private final ScrapPriceGenerationService generation;
  private final FactorUploadBatchMapper batches;

  public ScrapPriceImportService(
      ScrapPriceWorkbookParser parser,
      ScrapPriceMapper prices,
      ScrapPriceRuleCodec rules,
      PlatformTransactionManager transactions,
      FactorIdentityMapper identities,
      ScrapPriceGenerationService generation,
      FactorUploadBatchMapper batches) {
    this.parser = parser;
    this.prices = prices;
    this.rules = rules;
    this.transaction = new TransactionTemplate(transactions);
    this.transaction.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.identities = identities;
    this.generation = generation;
    this.batches = batches;
  }

  public record RowPreview(int row, ScrapPriceRule rule, String action, Long existingId) {}

  public record Preview(
      String priceMonth,
      String fileName,
      String fileSha256,
      String previewFingerprint,
      List<RowPreview> rows,
      List<Problem> errors,
      int inserted,
      int updated,
      int unchanged,
      int duplicates,
      boolean canConfirm) {}

  public record ImportResult(
      String priceMonth,
      int inserted,
      int updated,
      int unchanged,
      int duplicates,
      String importBatchNo) {}

  public record Edit(long expectedVersion, ScrapPriceRule rule) {}

  public Preview preview(byte[] file, String name, String businessUnit, String month) {
    String unit = ScrapPriceIdentity.businessUnit(businessUnit);
    String priceMonth = FactorPriceScope.month(month);
    return preview(parser.parse(file, name), unit, priceMonth, scope(unit));
  }

  public ImportResult confirm(
      byte[] file,
      String name,
      String businessUnit,
      String month,
      String fingerprint,
      String actor) {
    String unit = ScrapPriceIdentity.businessUnit(businessUnit);
    String priceMonth = FactorPriceScope.month(month);
    String operator = ScrapPriceRule.required(actor, "导入人", 64);
    ScrapPriceRule.required(fingerprint, "预检版本", 64);
    Parsed parsed = parser.parse(file, name);
    if (!parsed.errors().isEmpty()) {
      var first = parsed.errors().getFirst();
      throw new IllegalArgumentException("整批未导入，第" + first.row() + "行：" + first.message());
    }
    try {
      return transaction.execute(
          status -> {
            identities.lockForFinancePublication(unit);
            var existing = prices.lockScope(unit);
            Preview checked = preview(parsed, unit, priceMonth, existing);
            if (!checked.previewFingerprint().equals(fingerprint))
              throw new IllegalArgumentException("文件、月份或价格记录已变化，请重新预检");
            Map<String, ScrapPrice> current = monthRows(existing, priceMonth);
            String batch = "SCR-" + UUID.randomUUID();
            LocalDateTime now = LocalDateTime.now();
            for (SourceRow source : parsed.rows()) {
              ScrapPriceRule rule = source.rule();
              ScrapPrice prior = current.get(rule.identity(unit, priceMonth).materialSupplierKey());
              if (unchanged(prior, rule)) continue;
              ScrapPrice row = prior == null ? new ScrapPrice() : prior;
              explicitRule(row, rule, unit, priceMonth, "IMPORT", operator, now);
              row.setImportBatchNo(batch);
              row.setSourceFileName(parsed.fileName());
              row.setSourceFileSha256(parsed.sha256());
              row.setSourceSheet("废料清单");
              row.setSourceRow(source.row());
              row.setSourceJson(source.evidenceJson());
              row.setImportedBy(operator);
              row.setImportedAt(now);
              save(row);
            }
            if (checked.inserted() + checked.updated() > 0)
              archive(
                  file,
                  parsed,
                  checked.previewFingerprint(),
                  batch,
                  unit,
                  priceMonth,
                  operator,
                  now);
            generation.refreshInTransaction(unit, priceMonth, operator);
            return new ImportResult(
                priceMonth,
                checked.inserted(),
                checked.updated(),
                checked.unchanged(),
                parsed.duplicates(),
                checked.inserted() + checked.updated() == 0 ? null : batch);
          });
    } catch (DuplicateKeyException | PessimisticLockingFailureException conflict) {
      throw new IllegalArgumentException("废料价格正在被其他操作更新，整批未导入，请重新预检", conflict);
    }
  }

  public ScrapPrice edit(long id, String businessUnit, Edit request, String actor) {
    String unit = ScrapPriceIdentity.businessUnit(businessUnit);
    String operator = ScrapPriceRule.required(actor, "修改人", 64);
    if (request == null || request.rule() == null || request.expectedVersion() < 1)
      throw new IllegalArgumentException("编辑必须提供规则和当前版本");
    try {
      return transaction.execute(
          status -> {
            identities.lockForFinancePublication(unit);
            var existing = prices.lockScope(unit);
            ScrapPrice row =
                existing.stream()
                    .filter(item -> item.getId() == id)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("废料价格不存在或无权访问"));
            if (row.getRowVersion() != request.expectedVersion())
              throw new IllegalArgumentException("废料价格已被修改，请刷新后重试");
            ScrapPriceRule rule = request.rule();
            if (!row.getMaterialCode().equals(rule.materialCode()))
              throw new IllegalArgumentException("编辑不能变更废料料号，请通过导入新增料号");
            var identity = rule.identity(unit, row.getPriceMonth());
            for (ScrapPrice other : existing) {
              if (!other.getId().equals(row.getId())
                  && other.getPriceMonth().equals(row.getPriceMonth())
                  && ScrapRuleMonthPolicy.identity(other)
                      .materialSupplierKey()
                      .equals(identity.materialSupplierKey()))
                throw new IllegalArgumentException("该月份已存在相同料号和供应商的废料价格");
            }
            if (unchanged(row, rule)) {
              generation.refreshInTransaction(unit, row.getPriceMonth(), operator);
              return prices.selectById(row.getId());
            }
            ScrapRuleMonthPolicy.recordIdentityChange(row, identity);
            explicitRule(
                row, rule, unit, row.getPriceMonth(), "MANUAL", operator, LocalDateTime.now());
            save(row);
            generation.refreshInTransaction(unit, row.getPriceMonth(), operator);
            return prices.selectById(row.getId());
          });
    } catch (DuplicateKeyException | PessimisticLockingFailureException conflict) {
      throw new IllegalArgumentException("废料价格正在被其他操作更新，请刷新后重试", conflict);
    }
  }

  private Preview preview(Parsed parsed, String unit, String month, List<ScrapPrice> existing) {
    Map<String, ScrapPrice> current = monthRows(existing, month);
    var rows = new ArrayList<RowPreview>();
    var versions = new TreeMap<String, String>();
    int inserted = 0, updated = 0, unchanged = 0;
    for (SourceRow source : parsed.rows()) {
      String key = source.rule().identity(unit, month).materialSupplierKey();
      ScrapPrice prior = current.get(key);
      String action;
      if (prior == null) {
        action = "INSERT";
        inserted++;
      } else if (unchanged(prior, source.rule())) {
        action = "UNCHANGED";
        unchanged++;
      } else {
        action = "UPDATE";
        updated++;
      }
      versions.put(key, prior == null ? "ABSENT" : prior.getId() + ":" + prior.getRowVersion());
      rows.add(
          new RowPreview(
              source.row(), source.rule(), action, prior == null ? null : prior.getId()));
    }
    String fingerprint =
        ScrapPriceIdentity.hash(
            rules.encode(
                List.of(
                    "SCRAP_PREVIEW_V1",
                    unit,
                    month,
                    parsed.fileName(),
                    parsed.sha256(),
                    versions)));
    return new Preview(
        month,
        parsed.fileName(),
        parsed.sha256(),
        fingerprint,
        List.copyOf(rows),
        parsed.errors(),
        inserted,
        updated,
        unchanged,
        parsed.duplicates(),
        parsed.errors().isEmpty() && !rows.isEmpty());
  }

  private Map<String, ScrapPrice> monthRows(List<ScrapPrice> existing, String month) {
    var current = new TreeMap<String, ScrapPrice>();
    existing.stream()
        .filter(row -> month.equals(row.getPriceMonth()))
        .forEach(row -> current.put(ScrapRuleMonthPolicy.identity(row).materialSupplierKey(), row));
    return current;
  }

  private boolean unchanged(ScrapPrice prior, ScrapPriceRule rule) {
    // 对沿用行显式导入，即使金额未变也建立该月规则，后续旧月修改才有正确截止点。
    return prior != null
        && !"CARRY_FORWARD".equals(prior.getOriginType())
        && Objects.equals(prior.getContentHash(), rules.hash(rule));
  }

  private List<ScrapPrice> scope(String unit) {
    return prices.selectList(
        Wrappers.lambdaQuery(ScrapPrice.class).eq(ScrapPrice::getBusinessUnitType, unit));
  }

  private void explicitRule(
      ScrapPrice row,
      ScrapPriceRule rule,
      String unit,
      String month,
      String origin,
      String actor,
      LocalDateTime now) {
    boolean inserted = row.getId() == null;
    rules.write(rule, row);
    row.setBusinessUnitType(unit);
    row.setPriceMonth(month);
    row.setOriginType(origin);
    row.setRuleSourceMonth(month);
    row.setSourceRuleId(null);
    row.setSourceRuleRevision(null);
    row.setRuleRevision(inserted ? 1 : row.getRuleRevision() + 1);
    row.setRowVersion(inserted ? 1L : row.getRowVersion() + 1);
    if (inserted) {
      row.setCreatedBy(actor);
      row.setCreatedAt(now);
    }
    row.setUpdatedBy(actor);
    row.setUpdatedAt(now);
    row.setFinanceOrgCode("210");
    row.setFinanceMonth(null);
    row.setFinanceBatchId(null);
    row.setFinanceRevision(null);
    row.setSchemeCode(null);
    row.setInputFingerprint(null);
    row.setCalculationJson(null);
    row.setCalculatedAt(null);
    row.setUnitPrice(rule.fixedPrice());
    row.setCalcStatus("FIXED".equals(rule.priceType()) ? "READY" : "WAITING_BASE");
    row.setCalcMessage("FIXED".equals(rule.priceType()) ? null : "等待商用财务基价计算");
  }

  private void save(ScrapPrice row) {
    if (row.getId() == null) prices.insert(row);
    else if (prices.updateById(row) != 1) throw new IllegalStateException("废料价格保存失败");
  }

  /** 复用现有原稿文件批次，不登记因素、不建立财务发布，也不参与普通原稿的月度边界。 */
  private void archive(
      byte[] file,
      Parsed parsed,
      String fingerprint,
      String batchNo,
      String unit,
      String month,
      String actor,
      LocalDateTime now) {
    var batch = new FactorUploadBatch();
    batch.setBatchNo(batchNo);
    batch.setImportType("SCRAP_PRICE");
    batch.setImportPurpose("MATERIAL_RULE");
    batch.setBusinessUnitType(unit);
    batch.setPriceMonth(month);
    batch.setFileName(parsed.fileName());
    batch.setFileSha256(parsed.sha256());
    batch.setContentHash(fingerprint);
    batch.setSourceWorkbook(file);
    batch.setParserVersion("SCRAP_PRICE_V1");
    batch.setUploadedBy(actor);
    batch.setStatus("SUCCESS");
    batch.setStartedAt(now);
    batch.setFinishedAt(now);
    batch.setCreatedAt(now);
    batch.setUpdatedAt(now);
    batches.insert(batch);
  }
}
