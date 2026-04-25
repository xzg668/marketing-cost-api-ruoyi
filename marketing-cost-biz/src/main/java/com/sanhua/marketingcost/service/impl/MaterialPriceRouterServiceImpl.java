package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 物料价格路由服务实现。
 *
 * <p>查询逻辑：
 * <ol>
 *   <li>按 (materialCode, period) 拉所有候选记录</li>
 *   <li>用 quoteDate 过滤生效期：null 边界视为开放</li>
 *   <li>按 priority 升序排，priority=null 视为 Integer.MAX_VALUE 排在最后</li>
 *   <li>把 material_shape / price_type 翻译成枚举；未识别的桶丢弃并 WARN（避免脏数据触发空指针）</li>
 * </ol>
 */
@Service
public class MaterialPriceRouterServiceImpl implements MaterialPriceRouterService {

  private static final Logger log = LoggerFactory.getLogger(MaterialPriceRouterServiceImpl.class);

  private final MaterialPriceTypeMapper materialPriceTypeMapper;

  public MaterialPriceRouterServiceImpl(MaterialPriceTypeMapper materialPriceTypeMapper) {
    this.materialPriceTypeMapper = materialPriceTypeMapper;
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
    List<MaterialPriceType> rows =
        materialPriceTypeMapper.selectList(
            Wrappers.lambdaQuery(MaterialPriceType.class)
                .eq(MaterialPriceType::getMaterialCode, code)
                .eq(MaterialPriceType::getPeriod, periodValue));
    if (rows.isEmpty()) {
      return Collections.emptyList();
    }
    List<PriceTypeRoute> candidates = new ArrayList<>(rows.size());
    for (MaterialPriceType row : rows) {
      // 过滤生效期：quoteDate 为 null 时不限制
      if (quoteDate != null) {
        if (row.getEffectiveFrom() != null && quoteDate.isBefore(row.getEffectiveFrom())) {
          continue;
        }
        if (row.getEffectiveTo() != null && quoteDate.isAfter(row.getEffectiveTo())) {
          continue;
        }
      }
      Optional<MaterialFormAttrEnum> formAttr =
          MaterialFormAttrEnum.fromDbText(row.getMaterialShape());
      Optional<PriceTypeEnum> priceType = PriceTypeEnum.fromDbText(row.getPriceType());
      if (formAttr.isEmpty() || priceType.isEmpty()) {
        // 数据库里有未在枚举里登记的取值（脏数据）；记 WARN 后丢弃，不阻塞主流程
        log.warn(
            "MaterialPriceRouter 跳过未识别记录: materialCode={}, materialShape={}, priceType={}",
            code, row.getMaterialShape(), row.getPriceType());
        continue;
      }
      candidates.add(
          new PriceTypeRoute(
              code,
              formAttr.get(),
              priceType.get(),
              row.getPriority(),
              row.getEffectiveFrom(),
              row.getEffectiveTo(),
              row.getSourceSystem()));
    }
    // priority 升序；null 视作最低优先级
    candidates.sort(
        Comparator.comparingInt(
            r -> r.priority() == null ? Integer.MAX_VALUE : r.priority()));
    return candidates;
  }
}
