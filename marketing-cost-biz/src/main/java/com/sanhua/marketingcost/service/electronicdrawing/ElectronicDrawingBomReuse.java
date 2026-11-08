package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationCodec;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationSummary;
import com.sanhua.marketingcost.mapper.DrawingVerificationResultMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

/** 最新原始图库与历史原始图库比较后，沿用已确认的完整树和未受影响的财务修正。 */
@Service
public class ElectronicDrawingBomReuse {
  public record Plan(QuoteBomSupplementVersion previous, DrawingDetailComparison.Result comparison,
      List<ElectronicDrawingSourceNode> originalRows, List<ElectronicDrawingSourceNode> savedRows) {
    public boolean unchanged() { return comparison.unchanged(); }
    public boolean reuseCompleteTree() {
      if (unchanged()) return true;
      if (!comparison.added().isEmpty() || !comparison.removed().isEmpty()
          || comparison.matches().stream().anyMatch(DrawingDetailComparison.Match::contentChanged)) return false;
      // 财务曾把件数改成采购单位用量时，新图库件数需重新换算，不能直接缩放旧数值。
      return comparison.matches().stream().filter(DrawingDetailComparison.Match::quantityChanged)
          .allMatch(match -> savedRows.stream().filter(row -> row.getSourceSequence().equals(match.previous().getSourceSequence()))
              .allMatch(row -> row.getQuantityUnit() == null));
    }
  }

  private final QuoteBomSupplementVersionMapper versions;
  private final QuoteBomSupplementDetailMapper details;
  private final DrawingVerificationResultMapper results;
  private final ElectronicDrawingSourceNodeRepository nodes;
  private final DrawingVerificationCodec codec;
  private final OaFormItemMapper items;
  private final OaFormMapper forms;
  private final QuoteBomContextResolver contexts;
  private final ApprovedElectronicBomRawSnapshotPublisher publisher;

  public ElectronicDrawingBomReuse(QuoteBomSupplementVersionMapper versions,
      QuoteBomSupplementDetailMapper details, DrawingVerificationResultMapper results,
      ElectronicDrawingSourceNodeRepository nodes, DrawingVerificationCodec codec,
      OaFormItemMapper items, OaFormMapper forms, QuoteBomContextResolver contexts,
      ApprovedElectronicBomRawSnapshotPublisher publisher) {
    this.versions = versions;
    this.details = details;
    this.results = results;
    this.nodes = nodes;
    this.codec = codec;
    this.items = items;
    this.forms = forms;
    this.contexts = contexts;
    this.publisher = publisher;
  }

  public Plan compare(ElectronicDrawingWorkContext context, String drawing,
      DrawingVerificationSummary latest) {
    var currentItem = items.selectById(context.oaFormItemId());
    var currentForm = forms.selectById(context.oaFormId());
    var currentScope = contexts.resolveWithExistingCostPeriod(currentForm, currentItem, context.accountingMonth());
    var month = YearMonth.parse(context.accountingMonth());
    var candidates = versions.selectList(Wrappers.<QuoteBomSupplementVersion>lambdaQuery()
        .eq(QuoteBomSupplementVersion::getQuoteProductCode, context.quoteProductCode())
        .eq(QuoteBomSupplementVersion::getElectronicDrawingNo, drawing)
        .eq(QuoteBomSupplementVersion::getMaterialOrgCode, context.materialOrgCode())
        .eq(QuoteBomSupplementVersion::getSupplementScope, "NON_BARE_FULL_BOM")
        .eq(QuoteBomSupplementVersion::getBomSource, ElectronicDrawingSourceKind.PDF)
        .eq(QuoteBomSupplementVersion::getVersionStatus, "APPROVED")
        .eq(QuoteBomSupplementVersion::getActiveFlag, 1)
        .isNotNull(QuoteBomSupplementVersion::getDrawingResultId)
        .le(QuoteBomSupplementVersion::getEffectiveFrom, month.atEndOfMonth())
        .and(q -> q.isNull(QuoteBomSupplementVersion::getEffectiveTo)
            .or().ge(QuoteBomSupplementVersion::getEffectiveTo, month.atDay(1)))
        .ge(QuoteBomSupplementVersion::getReuseValidUntil, month.atDay(1))
        .orderByDesc(QuoteBomSupplementVersion::getReviewedAt)
        .orderByDesc(QuoteBomSupplementVersion::getId));
    for (var candidate : candidates) {
      var originalItem = items.selectById(candidate.getOaFormItemId());
      var originalForm = originalItem == null ? null : forms.selectById(originalItem.getOaFormId());
      if (originalForm == null || !Objects.equals(originalForm.getBusinessUnitType(), context.businessUnitType())) continue;
      var originalScope = contexts.resolveWithExistingCostPeriod(originalForm, originalItem, context.accountingMonth());
      if (!Objects.equals(originalScope.productCode(), currentScope.productCode())
          || !Objects.equals(originalScope.organization(), currentScope.organization())
          || !Objects.equals(originalScope.customerKey(), currentScope.customerKey())
          || !Objects.equals(originalScope.packageMethod(), currentScope.packageMethod())) continue;
      var result = results.findById(candidate.getDrawingResultId());
      if (result == null) throw new IllegalStateException("已审核 BOM 缺少原始图库结果，不能确认明细是否变化");
      var original = DrawingJsonNodes.forProduct(codec.parse(result.getRawSummaryJson()).summary(), drawing);
      var latestRows = DrawingJsonNodes.forProduct(latest, drawing);
      var saved = nodes.findByVersionId(candidate.getId());
      if (saved.isEmpty()) throw new IllegalStateException("已审核 BOM 缺少已保存节点，不能复用补录资料");
      // 最新适用批准版本是比较基准；不能跳过变化，倒找某个内容碰巧相同的更老版本。
      return new Plan(candidate, DrawingDetailComparison.compare(original, latestRows), original, saved);
    }
    return null;
  }

  public List<ElectronicDrawingSourceNode> retainedRows(Plan plan,
      List<ElectronicDrawingSourceNode> incoming) {
    if (plan == null) return incoming;
    if (plan.unchanged()) return unchangedRows(plan);
    var original = bySequence(plan.originalRows());
    var saved = bySequence(plan.savedRows());
    var matches = new LinkedHashMap<String, DrawingDetailComparison.Match>();
    var oldToNew = new LinkedHashMap<String, String>();
    for (var match : plan.comparison().matches()) {
      matches.put(match.current().getSourceSequence(), match);
      oldToNew.put(match.previous().getSourceSequence(), match.current().getSourceSequence());
    }
    var occupied = new java.util.HashSet<>(bySequence(incoming).keySet());
    for (var row : plan.savedRows()) {
      if (original.containsKey(row.getSourceSequence())) continue;
      String sequence = "SAVED:" + row.getSourceSequence();
      while (!occupied.add(sequence)) sequence = "SAVED:" + sequence;
      oldToNew.put(row.getSourceSequence(), sequence);
    }
    var output = new LinkedHashMap<String, ElectronicDrawingSourceNode>();
    for (var row : incoming) {
      var match = matches.get(row.getSourceSequence());
      boolean retain = match != null && !match.contentChanged();
      var previous = retain ? saved.get(match.previous().getSourceSequence()) : null;
      if (retain && previous == null) continue; // 财务已经删除，原始明细未变化时不补回。
      var next = copyNode(previous == null ? row : previous);
      next.setSourceSequence(row.getSourceSequence());
      next.setParentSourceSequence(previous == null ? row.getParentSourceSequence()
          : oldToNew.get(previous.getParentSourceSequence()));
      if (previous != null && match.quantityChanged()) {
        next.setQty(row.getQty());
        next.setQuantityUnit(null);
      }
      output.put(next.getSourceSequence(), next);
    }
    // 图库没有该行而已确认树中存在的财务补录，只有其父项及直接图库关系未变才沿用。
    boolean added;
    do {
      added = false;
      for (var row : plan.savedRows()) {
        String sequence = oldToNew.get(row.getSourceSequence());
        if (original.containsKey(row.getSourceSequence()) || output.containsKey(sequence)) continue;
        String oldParent = row.getParentSourceSequence();
        String parent = oldToNew.get(oldParent);
        if (oldParent != null && (!output.containsKey(parent)
            || output.get(parent).getCorrectedFromSourceNodeId() == null
            || changedChildren(plan, oldParent, parent))) continue;
        var retained = copyNode(row);
        retained.setSourceSequence(sequence);
        retained.setParentSourceSequence(parent);
        output.put(sequence, retained);
        added = true;
      }
    } while (added);
    for (var row : output.values()) {
      if (row.getParentSourceSequence() != null && !output.containsKey(row.getParentSourceSequence()))
        throw new IllegalArgumentException("变化的图库明细依赖已删除或待重新确认的父项，请核实该分支");
    }
    return List.copyOf(output.values());
  }

  private boolean changedChildren(Plan plan, String previousParent, String currentParent) {
    return plan.comparison().added().stream().anyMatch(row -> Objects.equals(currentParent, row.getParentSourceSequence()))
        || plan.comparison().removed().stream().anyMatch(row -> Objects.equals(previousParent, row.getParentSourceSequence()))
        || plan.comparison().matches().stream().anyMatch(match -> match.contentChanged()
            && Objects.equals(previousParent, match.previous().getParentSourceSequence()));
  }

  private List<ElectronicDrawingSourceNode> unchangedRows(Plan plan) {
    var sequences = new LinkedHashMap<String, String>();
    for (var match : plan.comparison().matches())
      sequences.put(match.previous().getSourceSequence(), match.current().getSourceSequence());
    for (var row : plan.savedRows()) {
      if (sequences.containsKey(row.getSourceSequence())) continue;
      String key = "SAVED:" + row.getSourceSequence();
      while (sequences.containsValue(key)) key = "SAVED:" + key;
      sequences.put(row.getSourceSequence(), key);
    }
    return plan.savedRows().stream().map(row -> {
      var copy = copyNode(row);
      copy.setSourceSequence(sequences.get(row.getSourceSequence()));
      copy.setParentSourceSequence(sequences.get(row.getParentSourceSequence()));
      return copy;
    }).toList();
  }

  /** 相同图库沿用完整 BOM，包括技术补录和财务修正，不从原始图库重新组一棵缺料的树。 */
  public void copyApprovedBom(Plan plan, QuoteBomSupplementVersion target,
      ElectronicDrawingWorkContext context, LocalDateTime now) {
    if (plan == null || !plan.reuseCompleteTree()) return;
    var oldRows = details.selectList(Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
        .eq(QuoteBomSupplementDetail::getSupplementVersionId, plan.previous().getId())
        .orderByAsc(QuoteBomSupplementDetail::getLineNo));
    if (oldRows.isEmpty() || plan.previous().getCompositionFingerprint() == null)
      throw new IllegalStateException("已审核 BOM 完整明细或组树依据缺失，不能复用");
    var identities = new LinkedHashMap<Long, Long>();
    for (var copy : nodes.findByVersionId(target.getId()))
      if (copy.getId() == null || copy.getCorrectedFromSourceNodeId() == null
          || identities.putIfAbsent(copy.getCorrectedFromSourceNodeId(), copy.getId()) != null)
        throw new IllegalStateException("复用图库节点保存不完整");
    if (identities.size() != plan.savedRows().size()) throw new IllegalStateException("复用图库节点数量不一致");
    var quantityFactors = quantityFactors(plan, oldRows);
    var copies = new ArrayList<QuoteBomSupplementDetail>();
    for (var old : oldRows) {
      var copy = new QuoteBomSupplementDetail();
      BeanUtils.copyProperties(old, copy, "id");
      copy.setSupplementVersionId(target.getId());
      copy.setPreparationId(target.getPreparationId());
      copy.setOaNo(target.getOaNo());
      copy.setOaFormItemId(target.getOaFormItemId());
      copy.setCreatedAt(now);
      copy.setUpdatedAt(now);
      if (!quantityFactors.isEmpty()) {
        var factor = BigDecimal.ONE;
        for (var entry : quantityFactors.entrySet()) {
          if (old.getPath().startsWith(entry.getKey())) factor = factor.multiply(entry.getValue());
        }
        copy.setQtyPerTop(old.getQtyPerTop().multiply(factor));
        var ownFactor = quantityFactors.get(old.getPath());
        if (ownFactor != null) copy.setQtyPerParent(old.getQtyPerParent().multiply(ownFactor));
      }
      if (old.getSourceElectronicNodeId() != null) {
        Long node = identities.get(old.getSourceElectronicNodeId());
        if (node == null) throw new IllegalStateException("完整 BOM 的补录节点与图库来源不一致");
        copy.setSourceElectronicNodeId(node);
      }
      // 路径是已确认制造件输入的绑定依据，复用时不因数据库新行 ID 改写它。
      copies.add(copy);
    }
    if (details.insertElectronicDrawingHybridBatch(copies) != copies.size())
      throw new IllegalStateException("复用完整 BOM 明细保存不完整");
    target.setCompositionFingerprint(quantityFactors.isEmpty() ? plan.previous().getCompositionFingerprint()
        : changedQuantityFingerprint(plan.previous().getCompositionFingerprint(), quantityFactors));
    target.setVersionStatus("APPROVED");
    target.setReviewedAt(now);
    target.setReviewerUserId(0L);
    target.setReviewerName("系统");
    target.setReviewComment((plan.unchanged() ? "最新图库原始明细一致" : "图库仅用量变化，按新用量计算原完整树")
        + "，沿用已审核完整 BOM 版本 " + plan.previous().getId());
    target.setReuseValidUntil(plan.previous().getReuseValidUntil());
    if (versions.updateById(target) != 1) throw new IllegalStateException("完整 BOM 复用版本保存失败");
    publisher.publish(new ApprovedElectronicBomRawSnapshotPublisher.PublicationContext(
        target.getId(), context.quoteProductCode(), context.temporaryProductKey(), context.priceOrgCode(),
        context.businessUnitType(), context.accountingMonth()));
  }

  private Map<String, BigDecimal> quantityFactors(Plan plan, List<QuoteBomSupplementDetail> rows) {
    var factors = new java.util.TreeMap<String, BigDecimal>();
    var saved = bySequence(plan.savedRows());
    for (var match : plan.comparison().matches()) {
      if (!match.quantityChanged()) continue;
      var source = saved.get(match.previous().getSourceSequence());
      if (source == null) continue;
      var parents = rows.stream().filter(row -> "E_DRAWING".equals(row.getNodeSourceType())
          && Objects.equals(row.getSourceElectronicNodeId(), source.getId())).toList();
      // 原完整树已排除/覆盖的节点不参与用量计算，仅记录本次原始明细变化。
      if (parents.isEmpty()) continue;
      if (parents.size() != 1) throw new IllegalStateException("用量变化的图库节点未对应唯一已确认 BOM 节点");
      factors.put(parents.getFirst().getPath(), match.current().getQty().divide(source.getQty(),
          16, RoundingMode.HALF_UP));
    }
    return factors;
  }

  private String changedQuantityFingerprint(String original, Map<String, BigDecimal> factors) {
    try {
      return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
          .digest((original + ":" + factors).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private ElectronicDrawingSourceNode copyNode(ElectronicDrawingSourceNode source) {
    var copy = new ElectronicDrawingSourceNode();
    BeanUtils.copyProperties(source, copy, "id", "supplementVersionId", "createdAt", "updatedAt");
    copy.setCorrectedFromSourceNodeId(source.getId());
    return copy;
  }

  private Map<String, ElectronicDrawingSourceNode> bySequence(List<ElectronicDrawingSourceNode> rows) {
    var result = new LinkedHashMap<String, ElectronicDrawingSourceNode>();
    for (var row : rows) if (row.getSourceSequence() == null || result.putIfAbsent(row.getSourceSequence(), row) != null)
      throw new IllegalStateException("图库来源行标识不完整或重复");
    return result;
  }
}
