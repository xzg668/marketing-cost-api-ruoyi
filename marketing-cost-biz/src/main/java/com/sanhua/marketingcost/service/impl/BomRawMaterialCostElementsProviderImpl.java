package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.system.SysDictData;
import com.sanhua.marketingcost.mapper.SysDictDataMapper;
import com.sanhua.marketingcost.service.BomRawMaterialCostElementsProvider;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * T11 增强 · {@link BomRawMaterialCostElementsProvider} 实现。
 *
 * <p>用 Spring Cache（CacheConfig 注册的 Caffeine 实例），cache 名：
 * {@code bomRawMaterialCostElements}。
 *
 * <p>cache 失效策略：复用 CacheConfig 默认 10 分钟 TTL（同 LEAF_ROLLUP 字典 cache 配置）。
 * 业务"修字典 → 等几分钟 → 重跑 flatten" 的体验足够，不实现"字典写后立即清 cache"。
 */
@Service
public class BomRawMaterialCostElementsProviderImpl implements BomRawMaterialCostElementsProvider {

  /** 字典类型固定值，与 V44__bom_raw_material_cost_elements_dict.sql 对齐 */
  private static final String DICT_TYPE = "bom_raw_material_cost_elements";

  private final SysDictDataMapper sysDictDataMapper;

  public BomRawMaterialCostElementsProviderImpl(SysDictDataMapper sysDictDataMapper) {
    this.sysDictDataMapper = sysDictDataMapper;
  }

  @Override
  @Cacheable("bomRawMaterialCostElements")
  public Set<String> getCostElementCodes() {
    // 拉字典启用项（status='0'）；@TableLogic 自动加 deleted=0
    List<SysDictData> all = sysDictDataMapper.selectList(
        Wrappers.<SysDictData>lambdaQuery()
            .eq(SysDictData::getDictType, DICT_TYPE)
            .eq(SysDictData::getStatus, "0"));
    Set<String> codes = new HashSet<>();
    for (SysDictData d : all) {
      String v = d.getDictValue();
      if (v != null && !v.isEmpty()) codes.add(v);
    }
    return codes.isEmpty() ? Collections.emptySet() : codes;
  }

  @Override
  public boolean isRawMaterial(String costElementCode) {
    // null / 空都视作不命中（字典里不会有 NULL 条目，避免"NULL == NULL"语义混乱）
    if (costElementCode == null || costElementCode.isEmpty()) return false;
    return getCostElementCodes().contains(costElementCode);
  }
}
