package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 基价齐全后绑定财务因素。每次发布、每份文件只解析一次，原稿版本不随基价修订。 */
@Component
public class OriginalLinkedPublicationSource {
  public static final String VERSION = "ORIGINAL_WORKBOOK_V1";
  private final OriginalLinkedWorkbookParser parser;
  private final OriginalLinkedRuleSnapshot snapshots;
  private final FactorUploadBatchMapper batches;
  private final FactorIdentityMapper identities;

  public OriginalLinkedPublicationSource(
      OriginalLinkedWorkbookParser parser,
      OriginalLinkedRuleSnapshot snapshots,
      FactorUploadBatchMapper batches,
      FactorIdentityMapper identities) {
    this.parser = parser;
    this.snapshots = snapshots;
    this.batches = batches;
    this.identities = identities;
  }

  public static final class BindingBatch {
    private final Map<Long, Map<String, OriginalLinkedWorkbookParser.Rule>> rules = new HashMap<>();
    private final Map<String, Set<String>> catalogs = new HashMap<>();
  }

  public OriginalLinkedWorkbookParser.Rule read(PriceLinkedItem item, BindingBatch batch) {
    // 先校验已保存来源身份，不能通过重新解析文件掩盖损坏的原稿记录。
    var original = snapshots.read(item);
    if (item.getSourceUploadBatchId() == null) return original;
    var parsed =
        batch.rules.computeIfAbsent(
            item.getSourceUploadBatchId(),
            id -> {
              var source = batches.findSourceWorkbook(id);
              if (source == null) throw new IllegalArgumentException("原稿来源批次不存在：" + id);
              byte[] bytes = source.getSourceWorkbook();
              // 早期已验收原稿没有整份文件列，其已绑定的公式快照仍为权威来源。
              if (bytes == null) {
                if (VERSION.equals(source.getParserVersion()))
                  throw new IllegalArgumentException("原稿工作簿来源缺失：批次=" + id);
                return Map.of();
              }
              if (!PriceLinkedImportFileDigest.sha256(bytes).equals(source.getFileSha256()))
                throw new IllegalArgumentException("原稿工作簿与来源摘要不一致：批次=" + id);
              var workbook =
                  parser.parseWithCatalog(
                      bytes,
                      source.getFileName(),
                      item.getBusinessUnitType(),
                      org ->
                          batch.catalogs.computeIfAbsent(
                              item.getBusinessUnitType() + ":" + org,
                              ignored ->
                                  Set.copyOf(
                                      identities.findFinanceManagedKeys(
                                          item.getBusinessUnitType(), org))));
              if (!workbook.errors().isEmpty()) {
                var error = workbook.errors().getFirst();
                throw new IllegalArgumentException(
                    "原稿财务因素绑定失败：批次="
                        + id
                        + "，"
                        + error.sheet()
                        + "!"
                        + error.row()
                        + "，物料="
                        + error.materialCode()
                        + "："
                        + error.message());
              }
              Map<String, OriginalLinkedWorkbookParser.Rule> values = new LinkedHashMap<>();
              for (var rule : workbook.rules())
                values.putIfAbsent(rule.identity().identityKey(), rule);
              return values;
            });
    if (parsed.isEmpty()) return original;
    var resolved = parsed.get(item.getRuleIdentityKey());
    if (resolved == null) throw new IllegalArgumentException("原稿工作簿中找不到已发布规则：" + item.getId());
    return resolved;
  }
}
