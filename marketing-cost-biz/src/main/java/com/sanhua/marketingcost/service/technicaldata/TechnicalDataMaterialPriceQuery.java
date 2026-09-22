package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.Manufacturing;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.Solder;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkContext;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 原材料、废料和焊料使用同一次取价检查；需求随当前明细重建，不另存一套缺价表。 */
@Service
public class TechnicalDataMaterialPriceQuery {
  public record PriceRequirement(String materialNo, String role, String status,
      BigDecimal unitPrice, String priceType, String message) {}
  private static final Logger log = LoggerFactory.getLogger(TechnicalDataMaterialPriceQuery.class);
  private final MakePartMaterialPriceResolveService prices;

  public TechnicalDataMaterialPriceQuery(MakePartMaterialPriceResolveService prices) {
    this.prices = prices;
  }

  public List<PriceRequirement> check(ElectronicDrawingWorkContext context, Manufacturing content) {
    if (content == null || content.items() == null) return List.of();
    var demands = new LinkedHashMap<String, String>();
    for (var row : content.items()) {
      demands.put(row.rawMaterialNo(), "RAW");
      if (row.evidence() != null && "MATCHED".equals(row.evidence().scrapStatus())) {
        for (var scrap : row.evidence().scrapMappings()) demands.putIfAbsent(scrap.materialNo(), "SCRAP");
      }
    }
    return check(context.oaNo(), context.oaFormItemId(), context.accountingMonth(), context.businessUnitType(), demands);
  }

  public List<PriceRequirement> check(QuoteTechTask task, QuoteTechProduct product, Solder content) {
    if (content == null || content.items() == null) return List.of();
    var demands = new LinkedHashMap<String, String>();
    content.items().forEach(row -> demands.put(row.materialNo(), "SOLDER"));
    return check(task.getOaNo(), product.getOaFormItemId(), product.getAccountingMonth(), task.getBusinessUnitType(), demands);
  }

  private List<PriceRequirement> check(String oaNo, Long itemId, String month, String businessUnit, Map<String, String> demands) {
    LocalDateTime now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    return demands.entrySet().stream().map(entry -> {
      try {
        var result = prices.calculateMaterialUnitPrice(entry.getKey(), month, now.toLocalDate(), now, oaNo, businessUnit, null);
        if (result == null) throw new IllegalStateException("取价服务未返回结果");
        return new PriceRequirement(entry.getKey(), entry.getValue(), result.getStatus(),
            result.getUnitPrice(), result.getPriceType(), result.getRemark());
      } catch (RuntimeException exception) {
        log.warn("technical material price check failed: itemId={} month={} material={}", itemId, month, entry.getKey(), exception);
        return new PriceRequirement(entry.getKey(), entry.getValue(), "ERROR", null, null, "取价检查失败，请重新检查");
      }
    }).toList();
  }
}
