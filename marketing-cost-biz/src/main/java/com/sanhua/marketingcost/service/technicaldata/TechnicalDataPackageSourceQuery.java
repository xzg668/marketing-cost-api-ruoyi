package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPackageReferenceResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPackageReferenceResponse.*;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.TechnicalDataPackageReferenceMapper;
import com.sanhua.marketingcost.service.FormalBomReadService;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 搜索与保存都读取正式有效 BOM；不复制其他技术草稿，不修改参考 BOM。 */
@Service
public class TechnicalDataPackageSourceQuery {
  private final TechnicalDataPackageReferenceMapper search;
  private final FormalBomReadService formalBom;
  private final BomRawHierarchyMapper hierarchy;
  private final OaMessageCodec json;

  public TechnicalDataPackageSourceQuery(TechnicalDataPackageReferenceMapper search,
      FormalBomReadService formalBom, BomRawHierarchyMapper hierarchy, OaMessageCodec json) {
    this.search = search; this.formalBom = formalBom; this.hierarchy = hierarchy; this.json = json;
  }

  public TechnicalDataPackageReferenceResponse references(QuoteTechTask task, QuoteTechProduct product, String keyword) {
    var grouped = new LinkedHashMap<String, List<Source>>();
    String query = keyword(keyword);
    for (String code : productCodes(task, product, query)) {
      for (Source source : forProduct(task, product, code)) {
        var e = source.evidence();
        if (!matches(query, e.topProductCode(), e.topProductName(), e.topProductModel(), e.topProductSpecification(),
            e.parentMaterialNo(), e.parentName(), e.parentModel(), e.parentSpecification())) continue;
        grouped.computeIfAbsent(e.structureFingerprint(), ignored -> new ArrayList<>()).add(source);
      }
    }
    return new TechnicalDataPackageReferenceResponse(grouped.entrySet().stream().map(entry -> {
      var first = entry.getValue().getFirst(); var e = first.evidence();
      return new Component(entry.getKey(), e.parentMaterialNo(), e.parentName(), e.parentModel(),
          e.parentSpecification(), first.children().size(), List.copyOf(entry.getValue()));
    }).toList());
  }

  public List<ChildOption> children(QuoteTechTask task, QuoteTechProduct product, String keyword) {
    String query = keyword(keyword);
    var result = new ArrayList<ChildOption>();
    for (String code : productCodes(task, product, query)) {
      for (Source source : forProduct(task, product, code)) {
        for (Child child : source.children()) {
          if (matches(query, child.materialNo(), child.name(), child.model(), child.specification())) {
            result.add(new ChildOption(source.evidence(), child));
          }
        }
      }
    }
    if (result.size() > 300) throw new IllegalArgumentException("包装子件结果较多，请输入更具体的料号、型号或规格");
    return List.copyOf(result);
  }

  public Source require(QuoteTechTask task, QuoteTechProduct product, Long parentId, String fingerprint) {
    if (parentId == null || parentId <= 0 || fingerprint == null) throw new IllegalArgumentException("请选择明确的包装来源");
    var parent = hierarchy.selectById(parentId);
    if (parent == null || !Objects.equals(parent.getPriceOrgCode(), task.getApplicableOrgCode())
        || parent.getBusinessUnitType() != null && !Objects.equals(parent.getBusinessUnitType(), task.getBusinessUnitType())) {
      throw new IllegalArgumentException("包装来源不存在或不属于本次组织");
    }
    var source = forProduct(task, product, parent.getTopProductCode()).stream()
        .filter(row -> Objects.equals(row.evidence().parentNodeId(), parentId)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("包装来源已失效或不再是当前 BOM 的包装节点，请重新选择"));
    if (!Objects.equals(source.evidence().fingerprint(), fingerprint)) {
      throw new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, "来源 BOM 或料品资料已变化，请重新查询；本次输入仍保留");
    }
    return source;
  }

  public List<Source> forProduct(QuoteTechTask task, QuoteTechProduct product, String code) {
    return forProduct(code, product.getAccountingMonth(), task.getBusinessUnitType(), task.getApplicableOrgCode());
  }

  public List<Source> forProduct(String code, String month, String businessUnit, String priceOrg) {
    var organization = MaterialOrganization.fromPriceOrgCode(priceOrg).toQuoteDataOrganization();
    var bom = formalBom.read(code, month, null, YearMonth.parse(month).atDay(1), organization);
    if (bom == null) throw new IllegalStateException("正式 BOM 查询未返回结果");
    if (!bom.found()) {
      if (bom.gapMessage() != null && bom.gapMessage().contains("未在 lp_bom_raw_hierarchy 找到正式 BOM")) return List.of();
      throw new IllegalStateException("正式 BOM 来源尚未核实：" + bom.gapMessage());
    }
    var lines = bom.lines();
    var roots = lines.stream().filter(row -> Objects.equals(row.level(), 0) && Objects.equals(code, row.materialCode())).toList();
    if (roots.size() != 1) throw new IllegalStateException("包装参考成品缺少唯一 BOM 根节点");
    var root = roots.getFirst();
    var result = new ArrayList<Source>();
    for (var parent : lines) {
      if (!"包装组件".equals(parent.mainCategoryName())
          || !Objects.equals(parent.priceOrgCode(), organization.priceOrgCode())
          || !Objects.equals(parent.materialOrganizationCode(), organization.materialOrganizationCode())) continue;
      var raw = hierarchy.selectById(parent.sourceRawHierarchyId());
      // 早期 U9 数据只存价格组织；已有组织映射仍能明确其业务单元。
      if (raw == null || raw.getBusinessUnitType() != null && !Objects.equals(raw.getBusinessUnitType(), businessUnit)) continue;
      var children = lines.stream().filter(child -> immediateChild(parent, child))
          .map(child -> new Child(child.sourceRawHierarchyId(), child.materialCode(), child.materialName(),
              first(child.materialModel(), child.drawingNo()), child.materialSpec(), child.qtyPerParent(), child.unit(), child.path()))
          .sorted(Comparator.comparing(Child::path)).toList();
      if (children.isEmpty()) continue;
      String structure = json.canonicalHash(List.of(organization, parent.materialCode(),
          Objects.toString(parent.bomVersion(), ""), Objects.toString(parent.bomPurpose(), ""),
          children.stream().map(child -> List.of(Objects.toString(child.materialNo(), ""), Objects.toString(child.model(), ""),
              Objects.toString(child.name(), ""), Objects.toString(child.specification(), ""),
              Objects.toString(child.quantity(), ""), Objects.toString(child.unit(), ""))).toList()));
      var unsigned = new Evidence(parent.sourceRawHierarchyId(), code, root.materialName(), first(root.materialModel(), root.drawingNo()), root.materialSpec(),
          parent.materialCode(), parent.materialName(), first(parent.materialModel(), parent.drawingNo()), parent.materialSpec(),
          parent.qtyPerTop(), organization.priceOrgCode(), organization.materialOrganizationCode(), parent.bomVersion(), parent.bomPurpose(),
          raw.getBuildBatchId(), parent.path(), structure, null);
      String fingerprint = json.canonicalHash(List.of(unsigned, children));
      var evidence = new Evidence(unsigned.parentNodeId(), code, unsigned.topProductName(), unsigned.topProductModel(), unsigned.topProductSpecification(),
          unsigned.parentMaterialNo(), unsigned.parentName(), unsigned.parentModel(), unsigned.parentSpecification(),
          unsigned.parentQuantity(), unsigned.priceOrgCode(), unsigned.materialOrganizationCode(), unsigned.bomVersion(),
          unsigned.bomPurpose(), unsigned.buildBatchId(), unsigned.parentPath(), structure, fingerprint);
      result.add(new Source(evidence, children));
    }
    return List.copyOf(result);
  }

  private List<String> productCodes(QuoteTechTask task, QuoteTechProduct product, String query) {
    var organization = MaterialOrganization.fromPriceOrgCode(task.getApplicableOrgCode());
    var codes = search.searchProducts(query, organization.getPriceOrgCode(), organization.getCode(),
        task.getBusinessUnitType(), YearMonth.parse(product.getAccountingMonth()).atDay(1));
    if (codes.size() > 100) throw new IllegalArgumentException("结果较多，请输入更具体的成品、组件料号或型号");
    return codes;
  }

  private boolean immediateChild(QuoteBomSourceLineDto parent, QuoteBomSourceLineDto child) {
    if (parent.path() == null || child.path() == null || child.level() == null || parent.level() == null) return false;
    int end = child.path().lastIndexOf('/', child.path().length() - 2);
    return child.level() == parent.level() + 1 && Objects.equals(child.parentCode(), parent.materialCode())
        && end >= 0 && child.path().substring(0, end + 1).equals(parent.path())
        && Objects.equals(parent.priceOrgCode(), child.priceOrgCode());
  }

  private String keyword(String value) {
    if (value == null || value.isBlank() || value.trim().length() > 100) throw new IllegalArgumentException("请输入 1—100 字的查询条件");
    return value.trim();
  }
  private boolean matches(String query, String... values) {
    for (String value : values) if (value != null && value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) return true;
    return false;
  }
  private String first(String first, String second) { return first == null || first.isBlank() ? second : first; }
}
