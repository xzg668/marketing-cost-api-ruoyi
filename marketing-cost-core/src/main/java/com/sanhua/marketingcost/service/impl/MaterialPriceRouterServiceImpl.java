package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.MaterialPriceTypeSourceCandidate;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeSourceMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 物料价格路由服务实现。
 *
 * <p>关键规则：
 * <ol>
 *   <li><b>shape 来源改用主档</b>：formAttr 优先取 {@code lp_material_master.shape_attr}（U9 ItemMaster
 *       权威源），路由表的 {@code material_shape} 仅作 fallback。修复"BOM 形态 vs 路由形态"对不齐。</li>
 *   <li><b>只认当前类型</b>：按 {@code created_at DESC, id DESC} 取一条，不按月份或路由有效期回退。</li>
 *   <li><b>不丢弃 priceType 合法但 shape 不合法的记录</b>：formAttr 兜底为 null，仍参与路由（白名单
 *       校验在调用方做），避免脏 shape 数据让取价整条丢失。</li>
 * </ol>
 *
 * <p>查询流程：
 * <ol>
 *   <li>按 {@code created_at DESC, id DESC} 取物料当前价格类型</li>
 *   <li>查主档 shape_attr 一次性缓存到本次调用</li>
 *   <li>翻译每行：
 *     <ul>
 *       <li>priceType: PriceTypeEnum.fromDbText 必须合法（4 桶或别名），否则 WARN 跳过</li>
 *       <li>formAttr: 优先 master.shape_attr → fallback row.material_shape → 都不识别则 null</li>
 *     </ul>
 *   </li>
 * </ol>
 */
@Service
public class MaterialPriceRouterServiceImpl implements MaterialPriceRouterService {

  private static final Logger log = LoggerFactory.getLogger(MaterialPriceRouterServiceImpl.class);

  private final MaterialPriceTypeMapper materialPriceTypeMapper;
  private final MaterialMasterMapper materialMasterMapper;
  private final MaterialPriceTypeSourceMapper materialPriceTypeSourceMapper;
  private final com.sanhua.marketingcost.mapper.TechnicalPriceSourceMapper technicalPrices;

  public MaterialPriceRouterServiceImpl(
      MaterialPriceTypeMapper materialPriceTypeMapper,
      MaterialMasterMapper materialMasterMapper,
      MaterialPriceTypeSourceMapper materialPriceTypeSourceMapper, com.sanhua.marketingcost.mapper.TechnicalPriceSourceMapper technicalPrices) {
    this.technicalPrices = technicalPrices;
    this.materialPriceTypeMapper = materialPriceTypeMapper;
    this.materialMasterMapper = materialMasterMapper;
    this.materialPriceTypeSourceMapper = materialPriceTypeSourceMapper;
  }

  @Override
  public Optional<PriceTypeRoute> resolve(
      String materialCode, String period, LocalDate quoteDate) {
    List<PriceTypeRoute> candidates = listCandidates(materialCode, period, quoteDate);
    return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
  }

  @Override
  public List<PriceTypeRoute> listCandidates(
      String materialCode, String period, LocalDate quoteDate) {
    var configured = configuredCandidates(materialCode, period, quoteDate);
    if (!StringUtils.hasText(materialCode)) return configured;
    if (!configured.isEmpty()) {
      var route = configured.getFirst();
      if (route.priceType() == PriceTypeEnum.MAKE || route.priceType() == PriceTypeEnum.RANGE
          || route.rawPriceType() != null && (route.rawPriceType().contains("结算") || route.rawPriceType().contains("SETTLE"))) return configured;
    }
    String code = materialCode.trim();
    String businessUnit = BusinessUnitContext.getCurrentBusinessUnitType();
    var kinds = technicalPrices.publicKinds(code, period, businessUnit);
    var result = new java.util.ArrayList<PriceTypeRoute>();
    var form = configured.isEmpty() ? MaterialFormAttrEnum.fromDbText(lookupMasterShape(code)).orElse(null) : configured.getFirst().formAttr();
    if (kinds.isEmpty() && configured.isEmpty()) {
      // 没有公共来源、也没有类型配置时，只有真实已发布补录才能补上取价来源。
      var supplemental = technicalPrices.supplemental(code, businessUnit);
      if (supplemental.size() > 1) throw new IllegalStateException(code + " 存在重复可用补录来源，请核实原办理记录");
      for (var source : supplemental) {
        var type = PriceTypeEnum.valueOf(source.priceType());
        result.add(new PriceTypeRoute(code, form, type, 1, null, null, "TECH_SUPPLEMENTAL", type.getDbText(), "TECH_SUPPLEMENTAL", source.recordId()));
      }
      return List.copyOf(result);
    }
    for (String kind : kinds) {
      var type = PriceTypeEnum.valueOf(kind);
      result.add(new PriceTypeRoute(code, form, type, result.size()+1, null, null, "PUBLIC", type.getDbText()));
    }
    if (result.isEmpty()) result.addAll(configured);
    // 这里只安排后续查询顺序。公共候选真正未命中后，Resolver 才查询补录表。
    for (var type : List.of(PriceTypeEnum.FIXED, PriceTypeEnum.LINKED)) {
      result.add(new PriceTypeRoute(code, form, type, result.size()+1, null, null, "TECH_SUPPLEMENTAL", type.getDbText(), "TECH_SUPPLEMENTAL", null));
    }
    return List.copyOf(result);
  }

  private List<PriceTypeRoute> configuredCandidates(String materialCode, String period, LocalDate quoteDate) {
    if (!StringUtils.hasText(materialCode)) return Collections.emptyList();
    String code = materialCode.trim();
    // 价格类型是物料级当前路由，不按报价月份复制。价格的生效期由具体价格源处理；
    // effective_to 到期也不能让报价停止，因此这里不再用路由有效期淘汰当前类型。
    List<MaterialPriceType> rows =
        materialPriceTypeMapper.selectList(
            Wrappers.lambdaQuery(MaterialPriceType.class)
                .eq(MaterialPriceType::getMaterialCode, code)
                .orderByDesc(MaterialPriceType::getCreatedAt)
                .orderByDesc(MaterialPriceType::getId)
                .last("LIMIT 1"));
    if (rows.isEmpty()) {
      return inferredFromFormalPriceSource(code);
    }

    // 主档 shape_attr 是权威源（v1 T03 起）；查不到则 fallback 用路由表 material_shape
    String masterShape = lookupMasterShape(code);

    MaterialPriceType row = rows.getFirst();
    Optional<PriceTypeEnum> priceType = PriceTypeEnum.fromDbText(row.getPriceType());
    if (priceType.isEmpty()) {
      log.warn(
          "MaterialPriceRouter 当前 priceType 无法识别: materialCode={}, priceType={}",
          code, row.getPriceType());
      return Collections.emptyList();
    }
    MaterialFormAttrEnum formAttr =
        MaterialFormAttrEnum.fromDbText(masterShape)
            .or(() -> MaterialFormAttrEnum.fromDbText(row.getMaterialShape()))
            .orElse(null);
    return List.of(
        new PriceTypeRoute(
            code,
            formAttr,
            priceType.get(),
            row.getPriority(),
            row.getEffectiveFrom(),
            row.getEffectiveTo(),
            row.getSourceSystem(),
            row.getPriceType()));
  }

  private List<PriceTypeRoute> inferredFromFormalPriceSource(String materialCode) {
    MaterialPriceTypeSourceCandidate source =
        materialPriceTypeSourceMapper.selectLatest(
            materialCode, BusinessUnitContext.getCurrentBusinessUnitType());
    if (source == null) {
      return Collections.emptyList();
    }
    Optional<PriceTypeEnum> priceType = PriceTypeEnum.fromDbText(source.getPriceType());
    if (priceType.isEmpty()) {
      log.warn(
          "正式价格源推断出的 priceType 无法识别: materialCode={}, priceType={}",
          materialCode,
          source.getPriceType());
      return Collections.emptyList();
    }
    String masterShape = lookupMasterShape(materialCode);
    MaterialFormAttrEnum formAttr =
        MaterialFormAttrEnum.fromDbText(masterShape).orElse(null);
    return List.of(
        new PriceTypeRoute(
            materialCode,
            formAttr,
            priceType.get(),
            1,
            source.getEffectiveFrom(),
            source.getEffectiveTo(),
            "PRICE_SOURCE_INFERRED:" + source.getSourceSystem(),
            source.getPriceType()));
  }

  /** 一次性查主档 shape_attr；查不到返 null（让 fallback 链兜底） */
  private String lookupMasterShape(String materialCode) {
    MaterialMaster master =
        materialMasterMapper.selectOne(
            Wrappers.lambdaQuery(MaterialMaster.class)
                .eq(MaterialMaster::getMaterialCode, materialCode)
                .last("LIMIT 1"));
    return master == null ? null : master.getShapeAttr();
  }

}
