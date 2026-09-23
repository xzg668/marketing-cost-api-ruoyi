package com.sanhua.marketingcost.service.technicaldata;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.bom.U9BomLineKey;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSolderSource.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.SolderItem;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.FormalBomReadService;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 查询完整适用 BOM 后自动筛选焊料；所有可编辑字段之外的值均从真实来源取得。 */
@Service
public class TechnicalDataSolderSourceQuery {
  private final FormalBomReadService formalBom;
  private final MaterialMasterRawMapper materials;
  private final BomRawHierarchyMapper hierarchy;
  private final BomU9SourceMapper u9;
  private final OaMessageCodec json;

  public TechnicalDataSolderSourceQuery(FormalBomReadService formalBom, MaterialMasterRawMapper materials,
      BomRawHierarchyMapper hierarchy, BomU9SourceMapper u9, OaMessageCodec json) {
    this.formalBom = formalBom; this.materials = materials; this.hierarchy = hierarchy;
    this.u9 = u9; this.json = json;
  }

  public List<Reference> references(QuoteTechTask task, QuoteTechProduct product, String keyword) {
    String query = text(keyword, 100, "参考查询条件");
    var candidates = materials.selectOptionsByLatestBatchKeyword(query, null, organization(task), 31);
    if (candidates.size() > 30) throw new IllegalArgumentException("结果较多，请输入更具体的成品料号或型号");
    var result = new ArrayList<Reference>();
    for (var candidate : candidates) {
      var source = reference(task, product, candidate.getMaterialCode());
      if (source != null && !source.items().isEmpty()) result.add(source);
    }
    return List.copyOf(result);
  }

  public Reference require(QuoteTechTask task, QuoteTechProduct product, String materialNo, String fingerprint) {
    var source = reference(task, product, text(materialNo, 64, "参考成品料号"));
    if (source == null || source.items().isEmpty()) throw new IllegalArgumentException("参考成品当前没有可用焊料，请重新查询");
    if (!Objects.equals(source.evidence().fingerprint(), fingerprint)) throw conflict("参考 BOM 或焊料档案已变化，请重新查询；本次输入仍保留");
    return source;
  }

  public MaterialLookup material(QuoteTechTask task, String materialNo) {
    String code = text(materialNo, 64, "焊料料号");
    var rows = materials.selectByLatestBatchAndCodes(List.of(code), null, organization(task));
    if (rows.isEmpty()) return new MaterialLookup("NOT_FOUND", "未找到当前组织的有效料品档案", null);
    if (rows.size() != 1) throw new IllegalStateException("当前组织存在重复料品档案，请核实");
    var source = material(rows.getFirst());
    if (!TechnicalDataSolderRules.eligible(source.mainCategoryCode())) {
      return new MaterialLookup("NOT_SOLDER", "该料号不属于可补录焊料，焊膏／粉／剂不在此处补录", source);
    }
    try { TechnicalDataSolderRules.toKg(BigDecimal.ONE, source.unit()); }
    catch (IllegalArgumentException exception) { return new MaterialLookup("UNIT_UNSUPPORTED", exception.getMessage(), source); }
    return new MaterialLookup(source.drawingNo() == null ? "NO_DRAWING" : "FOUND",
        source.drawingNo() == null ? "料品档案未维护图号" : "料品档案自动带出", source);
  }

  public Material requireMaterial(QuoteTechTask task, String code, String fingerprint) {
    var lookup = material(task, code);
    if (!List.of("FOUND", "NO_DRAWING").contains(lookup.status())) throw new IllegalArgumentException(lookup.message());
    if (!Objects.equals(lookup.material().fingerprint(), fingerprint)) throw conflict("焊料档案已变化，请重新查询后保存");
    return lookup.material();
  }

  private Reference reference(QuoteTechTask task, QuoteTechProduct product, String code) {
    var org = MaterialOrganization.fromPriceOrgCode(task.getApplicableOrgCode()).toQuoteDataOrganization();
    var bom = formalBom.readReference(code, product.getAccountingMonth(), null,
        YearMonth.parse(product.getAccountingMonth()).atDay(1), org);
    if (bom == null) throw new IllegalStateException("正式 BOM 查询未返回结果");
    if (!bom.found()) {
      if (bom.gapMessage() != null && bom.gapMessage().contains("未在 lp_bom_raw_hierarchy 找到正式 BOM")) return null;
      throw new IllegalStateException("参考 BOM 尚未核实：" + bom.gapMessage());
    }
    var roots = bom.lines().stream().filter(row -> Objects.equals(row.level(), 0) && code.equals(row.materialCode())).toList();
    if (roots.size() != 1) throw new IllegalStateException("参考成品缺少唯一的有效 BOM 根节点");
    var root = hierarchy.selectById(roots.getFirst().sourceRawHierarchyId());
    if (root == null) throw new IllegalStateException("参考成品缺少根节点来源");
    if (root.getBusinessUnitType() != null && !Objects.equals(root.getBusinessUnitType(), task.getBusinessUnitType())) return null;
    var masters = new LinkedHashMap<String, MaterialMasterRaw>();
    var grouped = bom.lines().stream().collect(Collectors.groupingBy(row -> row.materialOrganizationCode(), LinkedHashMap::new, Collectors.toList()));
    for (var entry : grouped.entrySet()) {
      var codes = entry.getValue().stream().map(row -> row.materialCode()).distinct().toList();
      for (var master : materials.selectByLatestBatchAndCodes(codes, null, entry.getKey())) {
        if (masters.put(master.getOrganizationCode() + ":" + master.getMaterialCode(), master) != null) throw new IllegalStateException("存在重复有效料品档案");
      }
    }
    var top = masters.get(org.materialOrganizationCode() + ":" + code);
    if (top == null) throw new IllegalArgumentException("参考成品缺少同组织有效料品档案");
    var selected = bom.lines().stream().filter(row -> row.level() != null && row.level() > 0)
        .filter(row -> {
          var master = masters.get(row.materialOrganizationCode() + ":" + row.materialCode());
          return master != null && TechnicalDataSolderRules.eligible(master.getMainCategoryCode());
        }).toList();
    if (selected.size() > 500) throw new IllegalArgumentException("参考焊料超过 500 行，请核实 BOM 来源");
    if (selected.isEmpty()) return null;
    var ids = selected.stream().map(row -> row.sourceRawHierarchyId()).filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) throw new IllegalStateException("焊料缺少 BOM 来源节点");
    Map<Long, BomRawHierarchy> raw = hierarchy.selectBatchIds(ids).stream().collect(Collectors.toMap(BomRawHierarchy::getId, Function.identity()));
    Map<ScopedLineKey, BomU9Source> original = loadU9SourceByBusinessKey(raw.values());
    var items = new ArrayList<SolderItem>();
    for (var line : selected) {
      var node = raw.get(line.sourceRawHierarchyId());
      if (node == null || !Objects.equals(node.getMaterialCode(), line.materialCode())
          || !Objects.equals(node.getPriceOrgCode(), line.priceOrgCode())) throw new IllegalStateException("焊料 BOM 来源关联不一致");
      var master = material(masters.get(line.materialOrganizationCode() + ":" + line.materialCode()));
      var source = sourceFor(node, original);
      if (source != null && (!Objects.equals(source.getPriceOrgCode(), node.getPriceOrgCode())
          || !Objects.equals(source.getChildMaterialNo(), line.materialCode()))) throw new IllegalStateException("焊料原始 U9 行关联不一致");
      String issueUnit = source == null ? null : nullable(source.getIssueUnit());
      String unit = issueUnit == null ? master.unit() : issueUnit;
      // 历史层级未留 U9 原行时使用有效料品单位；两种来源都必须有明确重量单位。
      TechnicalDataSolderRules.toKg(BigDecimal.ONE, master.unit());
      var quantity = TechnicalDataSolderRules.quantity(TechnicalDataSolderRules.toKg(line.qtyPerTop(), unit));
      var evidence = new BomEvidence(node.getId(), node.getSourceLineKey(), line.parentCode(), line.path(), line.level(),
          line.priceOrgCode(), line.bomVersion(), line.bomPurpose(), node.getSourceImportBatchId(), node.getBuildBatchId(),
          line.qtyPerParent(), line.qtyPerTop(), unit, issueUnit == null ? "MATERIAL_MASTER" : "U9_ISSUE");
      String itemKey = "U9:" + json.canonicalHash(List.of(line.priceOrgCode(), line.path(), node.getSourceLineKey()));
      items.add(new SolderItem(itemKey, master.materialNo(), master.drawingNo(), quantity, "kg", "U9:" + node.getId(),
          master.name(), new ItemEvidence(master, evidence, quantity)));
    }
    var unsigned = new ReferenceEvidence(code, nullable(top.getMaterialName()), nullable(top.getMaterialModel()),
        org.priceOrgCode(), org.materialOrganizationCode(), product.getAccountingMonth(), "STANDARD", null);
    String fingerprint = json.canonicalHash(List.of(unsigned, items));
    var evidence = new ReferenceEvidence(code, unsigned.name(), unsigned.model(), org.priceOrgCode(),
        org.materialOrganizationCode(), product.getAccountingMonth(), "STANDARD", fingerprint);
    return new Reference(evidence, items);
  }

  private Map<ScopedLineKey, BomU9Source> loadU9SourceByBusinessKey(
      java.util.Collection<BomRawHierarchy> nodes) {
    Map<SourceScope, Set<String>> parentsByScope = new LinkedHashMap<>();
    for (BomRawHierarchy node : nodes) {
      if (!"U9".equals(node.getSourceType())
          || !StringUtils.hasText(node.getSourceImportBatchId())) continue;
      U9BomLineKey key = U9BomLineKey.from(node);
      SourceScope scope = new SourceScope(node.getSourceImportBatchId(), key.priceOrgCode());
      parentsByScope.computeIfAbsent(scope, ignored -> new LinkedHashSet<>())
          .add(key.parentMaterialNo());
    }
    Map<ScopedLineKey, BomU9Source> result = new LinkedHashMap<>();
    for (var entry : parentsByScope.entrySet()) {
      SourceScope scope = entry.getKey();
      List<BomU9Source> rows = u9.selectList(Wrappers.lambdaQuery(BomU9Source.class)
          .eq(BomU9Source::getImportBatchId, scope.importBatchId())
          .eq(BomU9Source::getPriceOrgCode, scope.priceOrgCode())
          .in(BomU9Source::getParentMaterialNo, entry.getValue()));
      if (rows == null) throw new IllegalStateException("U9 单层 BOM 查询未返回结果");
      for (BomU9Source row : rows) {
        ScopedLineKey key = new ScopedLineKey(scope, U9BomLineKey.from(row));
        if (result.putIfAbsent(key, row) != null) {
          throw new IllegalStateException("U9 单层 BOM 存在重复业务行：" + key.lineKey());
        }
      }
    }
    return result;
  }

  private static BomU9Source sourceFor(
      BomRawHierarchy node, Map<ScopedLineKey, BomU9Source> original) {
    if (!"U9".equals(node.getSourceType())
        || !StringUtils.hasText(node.getSourceImportBatchId())) return null;
    U9BomLineKey key = U9BomLineKey.from(node);
    return original.get(new ScopedLineKey(
        new SourceScope(node.getSourceImportBatchId(), key.priceOrgCode()), key));
  }

  private record SourceScope(String importBatchId, String priceOrgCode) {}

  private record ScopedLineKey(SourceScope scope, U9BomLineKey lineKey) {}

  private Material material(MaterialMasterRaw row) {
    var unsigned = new Material(row.getId(), row.getMaterialCode(), nullable(row.getMaterialName()), nullable(row.getDrawingNo()),
        row.getOrganizationCode(), nullable(row.getMainCategoryCode()), nullable(row.getUnit()), row.getImportBatchId(), null);
    return new Material(unsigned.id(), unsigned.materialNo(), unsigned.name(), unsigned.drawingNo(), unsigned.organizationCode(),
        unsigned.mainCategoryCode(), unsigned.unit(), unsigned.importBatchId(), json.canonicalHash(unsigned));
  }
  private String organization(QuoteTechTask task) { return MaterialOrganization.fromPriceOrgCode(task.getApplicableOrgCode()).getCode(); }
  private String text(String value, int max, String label) {
    String result = nullable(value);
    if (result == null || result.length() > max || result.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(label + "须为 1—" + max + " 字的文本");
    return result;
  }
  private String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }
  private TechnicalDataTaskException conflict(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, message); }
}
