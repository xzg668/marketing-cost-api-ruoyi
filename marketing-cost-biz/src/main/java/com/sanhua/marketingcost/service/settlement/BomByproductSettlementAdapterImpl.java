package com.sanhua.marketingcost.service.settlement;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.U9BomByproductMaster;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import com.sanhua.marketingcost.mapper.U9BomByproductMasterMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 副产品结算候选读取适配层。
 *
 * <p>这里只做数据库读取和轻量归一：U9 副产品档案说明“可能要补行”，
 * lp_material_scrap_ref 说明“该副产品已被下层原材料废料抵减”。真正是否输出
 * {@code BYPRODUCT_EXTRA} 由统一引擎结合规则表判断。
 */
@Component
public class BomByproductSettlementAdapterImpl implements BomByproductSettlementAdapter {

  private static final String BOM_PURPOSE_MAIN_MANUFACTURING = "主制造";
  private static final String SHAPE_MANUFACTURED = "制造件";
  private static final String SHAPE_PURCHASED = "采购件";

  private final U9BomByproductMasterMapper byproductMapper;
  private final MaterialScrapRefMapper materialScrapRefMapper;

  public BomByproductSettlementAdapterImpl(
      U9BomByproductMasterMapper byproductMapper,
      MaterialScrapRefMapper materialScrapRefMapper) {
    this.byproductMapper = byproductMapper;
    this.materialScrapRefMapper = materialScrapRefMapper;
  }

  @Override
  public BomByproductSettlementReadResult read(
      List<BomSettlementNode> nodes,
      LocalDate asOfDate,
      String businessUnitType,
      String bomPurpose) {
    List<String> warnings = new ArrayList<>();
    List<BomSettlementNode> safeNodes = nodes == null ? List.of() : nodes;
    LocalDate effectiveDate = asOfDate == null ? LocalDate.now() : asOfDate;
    String requestedPurpose = StringUtils.hasText(bomPurpose) ? bomPurpose : BOM_PURPOSE_MAIN_MANUFACTURING;

    Set<String> manufacturedCodes = manufacturedCodes(safeNodes);
    if (manufacturedCodes.isEmpty()) {
      return new BomByproductSettlementReadResult(List.of(), List.of(), warnings);
    }

    List<U9BomByproductMaster> byproductRows;
    try {
      byproductRows = byproductMapper.selectList(
          Wrappers.<U9BomByproductMaster>lambdaQuery()
              .in(U9BomByproductMaster::getParentMaterialNo, manufacturedCodes)
              .eq(U9BomByproductMaster::getBomPurpose, requestedPurpose)
              .le(U9BomByproductMaster::getEffectiveFrom, effectiveDate)
              .ge(U9BomByproductMaster::getEffectiveTo, effectiveDate));
    } catch (BadSqlGrammarException ex) {
      warnings.add("BYPRODUCT_TABLE_UNAVAILABLE: 副产品档案表不可用，本次不生成副产品附加候选");
      return new BomByproductSettlementReadResult(List.of(), List.of(), warnings);
    }
    if (byproductRows.isEmpty()) {
      return new BomByproductSettlementReadResult(List.of(), List.of(), warnings);
    }

    Set<String> byproductCodes = new LinkedHashSet<>();
    for (U9BomByproductMaster row : byproductRows) {
      if (StringUtils.hasText(row.getByproductMaterialNo())) {
        byproductCodes.add(row.getByproductMaterialNo());
      }
    }
    Set<String> lowerRawMaterialCodes = lowerRawMaterialCodes(safeNodes, manufacturedCodes);
    List<BomSettlementScrapRef> scrapRefs = readScrapRefs(
        lowerRawMaterialCodes, byproductCodes, businessUnitType, effectiveDate);

    return new BomByproductSettlementReadResult(
        byproductRows.stream().map(this::toByproduct).toList(),
        scrapRefs,
        warnings);
  }

  private static Set<String> manufacturedCodes(List<BomSettlementNode> nodes) {
    Set<String> codes = new LinkedHashSet<>();
    for (BomSettlementNode node : nodes) {
      if (node != null
          && StringUtils.hasText(node.materialCode())
          && (SHAPE_MANUFACTURED.equals(node.shapeAttr())
              || SHAPE_MANUFACTURED.equals(node.productionCategory()))) {
        codes.add(node.materialCode());
      }
    }
    return codes;
  }

  private static Set<String> lowerRawMaterialCodes(
      List<BomSettlementNode> nodes, Set<String> manufacturedCodes) {
    Set<String> codes = new LinkedHashSet<>();
    for (BomSettlementNode parent : nodes) {
      if (parent == null || !manufacturedCodes.contains(parent.materialCode())) {
        continue;
      }
      for (BomSettlementNode node : nodes) {
        if (node != null
            && StringUtils.hasText(node.path())
            && !node.path().equals(parent.path())
            && node.path().startsWith(parent.path())
            && node.leaf()
            && (SHAPE_PURCHASED.equals(node.shapeAttr())
                || SHAPE_PURCHASED.equals(node.productionCategory()))
            && StringUtils.hasText(node.materialCode())) {
          codes.add(node.materialCode());
        }
      }
    }
    return codes;
  }

  private List<BomSettlementScrapRef> readScrapRefs(
      Set<String> materialCodes,
      Set<String> scrapCodes,
      String businessUnitType,
      LocalDate effectiveDate) {
    if (materialCodes.isEmpty() || scrapCodes.isEmpty()) {
      return List.of();
    }
    List<MaterialScrapRef> rows = materialScrapRefMapper.selectList(
        Wrappers.<MaterialScrapRef>lambdaQuery()
            .in(MaterialScrapRef::getMaterialCode, materialCodes)
            .in(MaterialScrapRef::getScrapCode, scrapCodes)
            .le(MaterialScrapRef::getEffectiveFrom, effectiveDate)
            .and(wrapper -> wrapper
                .ge(MaterialScrapRef::getEffectiveTo, effectiveDate)
                .or()
                .isNull(MaterialScrapRef::getEffectiveTo))
            .and(StringUtils.hasText(businessUnitType), wrapper -> wrapper
                .eq(MaterialScrapRef::getBusinessUnitType, businessUnitType)
                .or()
                .isNull(MaterialScrapRef::getBusinessUnitType)));
    return rows.stream()
        .map(row -> new BomSettlementScrapRef(
            row.getMaterialCode(),
            row.getScrapCode(),
            row.getBusinessUnitType(),
            row.getEffectiveFrom(),
            row.getEffectiveTo()))
        .toList();
  }

  private BomSettlementByproduct toByproduct(U9BomByproductMaster row) {
    return new BomSettlementByproduct(
        row.getId(),
        row.getParentMaterialNo(),
        row.getByproductMaterialNo(),
        row.getByproductMaterialName(),
        null,
        row.getOutputQty(),
        row.getUnit(),
        row.getBomPurpose(),
        row.getVersionNo(),
        row.getEffectiveFrom(),
        row.getEffectiveTo(),
        null);
  }
}
