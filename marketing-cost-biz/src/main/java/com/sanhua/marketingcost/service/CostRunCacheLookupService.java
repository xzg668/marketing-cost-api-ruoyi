package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.DepartmentFundRate;
import com.sanhua.marketingcost.entity.ManufactureRate;
import com.sanhua.marketingcost.entity.OtherExpenseRate;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.entity.QualityLossRate;
import com.sanhua.marketingcost.entity.ThreeExpenseRate;
import java.util.List;

/**
 * T19：试算路径专用缓存查询。
 *
 * <p>原 trial 直接 mapper.selectOne 查 5 张 rate 表 + ProductProperty，每次试算都打 SQL。
 * 把这些"按 (businessUnit) / (productCode) 取最新一条"的精确查询抽到这里，加 @Cacheable
 * 让重复试算（5 分钟 TTL 内）命中缓存，第二次 SQL 数显著下降。
 *
 * <p>语义跟 admin service 的 list 方法（LIKE 模糊 + 列表）不同，所以单独成 service。
 * admin 改这些表会触发 @CacheEvict 清除（业务保证）。
 */
public interface CostRunCacheLookupService {

  /** 按 businessUnit 精确取最新 1 条 quality_loss_rate；无返 null */
  QualityLossRate findQualityLossRate(String businessUnit);

  /** 按 businessUnit 精确取最新 1 条 manufacture_rate；无返 null */
  ManufactureRate findManufactureRate(String businessUnit);

  /** 按 businessUnit 精确取最新 1 条 three_expense_rate；无返 null */
  ThreeExpenseRate findThreeExpenseRate(String businessUnit);

  /** 按 businessUnit 精确取最新 1 条 department_fund_rate；无返 null */
  DepartmentFundRate findDepartmentFundRate(String businessUnit);

  /** 按 productCode 取该料号所有 other_expense_rate（id ASC）；无返空 list */
  List<OtherExpenseRate> findOtherExpenseRates(String productCode);

  /** 按 parentCode 取最新 1 条 product_property；无返 null */
  ProductProperty findProductProperty(String parentCode);
}
