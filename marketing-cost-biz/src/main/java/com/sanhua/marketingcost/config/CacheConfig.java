package com.sanhua.marketingcost.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

  @Bean
  public CacheManager cacheManager() {
    // T11：新增 bomLeafRollupCodes / bomLeafRollupKeywords 两个 cache，
    //   供 BomLeafRollupCodesProvider 缓存字典 dict_type='bom_leaf_rollup_codes' 的两路值集
    //   ——拍平一次循环数千节点，每节点都查字典撑不住
    // T11 增强：bomRawMaterialCostElements cache，供 BomRawMaterialCostElementsProvider
    //   缓存字典 dict_type='bom_raw_material_cost_elements' 的"原材料 cost_element_code 白名单"
    //   作为 LEAF_ROLLUP_TO_PARENT 命中的前置硬条件
    CaffeineCacheManager manager = new CaffeineCacheManager(
        "manufactureRates",
        "threeExpenseRates",
        "qualityLossRates",
        "departmentFundRates",
        "otherExpenseRates",
        "bomLeafRollupCodes",
        "bomLeafRollupKeywords",
        "bomRawMaterialCostElements",
        // T19：试算高频查询补缓存
        "financeBasePrice",
        "productProperty",
        "materialMaster");
    // T19：TTL 10min → 5min，缩短脏数据窗口（业务可能在 5 分钟内手动改 rate 表）
    manager.setCaffeine(Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .maximumSize(200));
    return manager;
  }
}
