package com.sanhua.marketingcost.service.scrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.ScrapPrice;
import com.sanhua.marketingcost.mapper.ScrapPriceMapper;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import com.sanhua.marketingcost.service.pricing.ScrapPriceIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 列表和详情仅展示已准备月价，GET不生成记录或触发计算。 */
@Service
@Transactional(readOnly = true)
public class ScrapPriceQueryService {
  private final ScrapPriceMapper prices;

  public ScrapPriceQueryService(ScrapPriceMapper prices) {
    this.prices = prices;
  }

  public Page<ScrapPrice> list(
      String businessUnit,
      String month,
      String keyword,
      String priceType,
      String supplier,
      int page,
      int size) {
    String unit = ScrapPriceIdentity.businessUnit(businessUnit);
    String priceMonth = FactorPriceScope.month(month);
    if (page < 1 || size < 1 || size > 200) throw new IllegalArgumentException("分页参数无效");
    var query =
        Wrappers.lambdaQuery(ScrapPrice.class)
            .eq(ScrapPrice::getBusinessUnitType, unit)
            .eq(ScrapPrice::getPriceMonth, priceMonth);
    if (keyword != null && !keyword.isBlank())
      query.and(
          q ->
              q.like(ScrapPrice::getMaterialCode, keyword.trim())
                  .or()
                  .like(ScrapPrice::getMaterialName, keyword.trim()));
    if (priceType != null && !priceType.isBlank()) {
      if (!priceType.equals("FIXED") && !priceType.equals("LINKED"))
        throw new IllegalArgumentException("价格类型无效");
      query.eq(ScrapPrice::getPriceType, priceType);
    }
    if (supplier != null && !supplier.isBlank())
      query.and(
          q ->
              q.like(ScrapPrice::getSupplierCode, supplier.trim())
                  .or()
                  .like(ScrapPrice::getSupplierName, supplier.trim()));
    query.orderByAsc(ScrapPrice::getMaterialCode, ScrapPrice::getSupplierKey);
    return prices.selectPage(new Page<>(page, size), query);
  }

  public ScrapPrice detail(String businessUnit, long id) {
    var row =
        prices.selectOne(
            Wrappers.lambdaQuery(ScrapPrice.class)
                .eq(ScrapPrice::getBusinessUnitType, ScrapPriceIdentity.businessUnit(businessUnit))
                .eq(ScrapPrice::getId, id));
    if (row == null) throw new IllegalArgumentException("废料价格不存在或无权访问");
    return row;
  }
}
