package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.system.SysDictData;
import com.sanhua.marketingcost.mapper.SysDictDataMapper;
import com.sanhua.marketingcost.service.BomLeafRollupCodesProvider;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * T11 · {@link BomLeafRollupCodesProvider} 实现。
 *
 * <p>用 Spring Cache（CacheConfig 注册的 Caffeine 实例），cache 名：
 * <ul>
 *   <li>{@code bomLeafRollupCodes}    —— 编码白名单</li>
 *   <li>{@code bomLeafRollupKeywords} —— 名称关键词</li>
 * </ul>
 *
 * <p>cache 失效策略：5 分钟 TTL。设计文档 §6.3 决定不实现"字典写后立即清 cache"
 * 的复杂联动 —— 业务"修字典 → 等几分钟 → 重跑 flatten" 的体验足够。
 */
@Service
public class BomLeafRollupCodesProviderImpl implements BomLeafRollupCodesProvider {

  /** NAME: 前缀长度，去前缀拿关键词时复用 */
  private static final String NAME_PREFIX = "NAME:";

  /** 字典类型固定值，与 V43__bom_leaf_rollup_dict.sql 对齐 */
  private static final String DICT_TYPE = "bom_leaf_rollup_codes";

  private final SysDictDataMapper sysDictDataMapper;

  public BomLeafRollupCodesProviderImpl(SysDictDataMapper sysDictDataMapper) {
    this.sysDictDataMapper = sysDictDataMapper;
  }

  @Override
  @Cacheable("bomLeafRollupCodes")
  public Set<String> getCategoryCodes() {
    // 拉字典启用项（status='0'）；@TableLogic 自动加 deleted=0
    List<SysDictData> all = loadAllEnabledEntries();
    Set<String> codes = new HashSet<>();
    for (SysDictData d : all) {
      String v = d.getDictValue();
      // 编码白名单 = 不以 NAME: 开头的 dict_value
      if (v != null && !v.isEmpty() && !v.startsWith(NAME_PREFIX)) {
        codes.add(v);
      }
    }
    return codes.isEmpty() ? Collections.emptySet() : codes;
  }

  @Override
  @Cacheable("bomLeafRollupKeywords")
  public Set<String> getNameKeywords() {
    List<SysDictData> all = loadAllEnabledEntries();
    Set<String> keywords = new HashSet<>();
    for (SysDictData d : all) {
      String v = d.getDictValue();
      if (v != null && v.startsWith(NAME_PREFIX) && v.length() > NAME_PREFIX.length()) {
        // 去 NAME: 前缀，拿到真正的关键词
        keywords.add(v.substring(NAME_PREFIX.length()));
      }
    }
    return keywords.isEmpty() ? Collections.emptySet() : keywords;
  }

  @Override
  public boolean matches(String materialCategory1, String materialName) {
    // 编码命中（两侧都非空才能比较，避免 null 命中 null 的语义混乱）
    if (materialCategory1 != null && !materialCategory1.isEmpty()) {
      if (getCategoryCodes().contains(materialCategory1)) {
        return true;
      }
    }
    // 名称兜底命中：任一关键词被 material_name contains 即算
    if (materialName != null && !materialName.isEmpty()) {
      for (String kw : getNameKeywords()) {
        if (materialName.contains(kw)) {
          return true;
        }
      }
    }
    return false;
  }

  /** 拉所有 status='0' 的字典条目（@TableLogic 自动过 deleted=1） */
  private List<SysDictData> loadAllEnabledEntries() {
    return sysDictDataMapper.selectList(
        Wrappers.<SysDictData>lambdaQuery()
            .eq(SysDictData::getDictType, DICT_TYPE)
            .eq(SysDictData::getStatus, "0"));
  }
}
