package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 物料价格路由服务实现 —— v1.1 (T03) 修复版。
 *
 * <p>v1.1 关键变更：
 * <ol>
 *   <li><b>shape 来源改用主档</b>：formAttr 优先取 {@code lp_material_master.shape_attr}（U9 ItemMaster
 *       权威源），路由表的 {@code material_shape} 仅作 fallback。修复"BOM 形态 vs 路由形态"对不齐。</li>
 *   <li><b>winner-first 排序</b>：路由表查询按 {@code priority DESC, effective_from DESC, id DESC}
 *       排序，{@code listCandidates} 第 1 条即 winner。{@code resolve()} 直接取首条。</li>
 *   <li><b>不丢弃 priceType 合法但 shape 不合法的记录</b>：formAttr 兜底为 null，仍参与路由（白名单
 *       校验在调用方做），避免脏 shape 数据让取价整条丢失。</li>
 * </ol>
 *
 * <p>查询流程：
 * <ol>
 *   <li>查路由表所有候选记录（已按 winner-first 排序）</li>
 *   <li>用 quoteDate 过滤生效期：null 边界视为开放</li>
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

  public MaterialPriceRouterServiceImpl(
      MaterialPriceTypeMapper materialPriceTypeMapper,
      MaterialMasterMapper materialMasterMapper) {
    this.materialPriceTypeMapper = materialPriceTypeMapper;
    this.materialMasterMapper = materialMasterMapper;
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
    if (!StringUtils.hasText(materialCode) || !StringUtils.hasText(period)) {
      return Collections.emptyList();
    }
    String code = materialCode.trim();
    String periodValue = period.trim();

    // 一次性按 winner-first 排序：
    //   priority ASC（业务约定：数值小者优先级高，1 是最高），null 视为最低排最后
    //   effective_from DESC（生效日期新者优先，null 视为最旧）
    //   id DESC（同优先级同生效日时最新写入的赢，作为 tiebreaker）
    // 用 .last() 注入完整 ORDER BY，避免 lambda orderBy* 对 null 的默认处理偏离语义
    List<MaterialPriceType> rows =
        materialPriceTypeMapper.selectList(
            Wrappers.lambdaQuery(MaterialPriceType.class)
                .eq(MaterialPriceType::getMaterialCode, code)
                .eq(MaterialPriceType::getPeriod, periodValue)
                .last("ORDER BY IFNULL(priority, 999999) ASC, "
                    + "IFNULL(effective_from, '1970-01-01') DESC, id DESC"));
    if (rows.isEmpty()) {
      return Collections.emptyList();
    }

    // 主档 shape_attr 是权威源（v1 T03 起）；查不到则 fallback 用路由表 material_shape
    String masterShape = lookupMasterShape(code);

    List<PriceTypeRoute> candidates = new ArrayList<>(rows.size());
    for (MaterialPriceType row : rows) {
      // 生效期过滤：quoteDate 为 null 时不限制
      if (quoteDate != null) {
        if (row.getEffectiveFrom() != null && quoteDate.isBefore(row.getEffectiveFrom())) {
          continue;
        }
        if (row.getEffectiveTo() != null && quoteDate.isAfter(row.getEffectiveTo())) {
          continue;
        }
      }

      // priceType 必须合法（PriceTypeEnum.fromDbText 已含双别名兼容），否则跳过 + WARN
      Optional<PriceTypeEnum> priceType = PriceTypeEnum.fromDbText(row.getPriceType());
      if (priceType.isEmpty()) {
        log.warn(
            "MaterialPriceRouter 跳过未识别 priceType 记录: materialCode={}, priceType={}",
            code, row.getPriceType());
        continue;
      }

      // formAttr 优先主档；主档无则路由表 material_shape；都不识别则 null（不阻塞路由）
      MaterialFormAttrEnum formAttr =
          MaterialFormAttrEnum.fromDbText(masterShape)
              .or(() -> MaterialFormAttrEnum.fromDbText(row.getMaterialShape()))
              .orElse(null);

      candidates.add(
          new PriceTypeRoute(
              code,
              formAttr,
              priceType.get(),
              row.getPriority(),
              row.getEffectiveFrom(),
              row.getEffectiveTo(),
              row.getSourceSystem()));
    }
    return candidates;
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
