package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPreparationEvidence;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceStorage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 当前档案仅用于补齐展示，不改变审核指纹或已保存的取价依据。 */
@Service
public class SupplierPriceReviewMaterials {
  public record Material(String scopeKey, String materialName, String specification,
      String model, String unit, String materialShape) {}

  private final MaterialMasterRawMapper mapper;
  private final PricePrepareItemMapper items;
  private final MonthlyPriceStorage monthlyPrices;
  private final MonthlyPriceDocumentCodec codec;
  private final MonthlyPreparationEvidence evidence;

  public SupplierPriceReviewMaterials(MaterialMasterRawMapper mapper, PricePrepareItemMapper items,
      MonthlyPriceStorage monthlyPrices, MonthlyPriceDocumentCodec codec) {
    this.mapper = mapper;
    this.items = items;
    this.monthlyPrices = monthlyPrices;
    this.codec = codec;
    this.evidence = new MonthlyPreparationEvidence(codec);
  }

  public void restoreHighest(QuotePricePrepareWorkbenchResponse response) {
    var scenario = response.getOaScenario();
    if (scenario == null || scenario.getBatch() == null) return;
    // 不依赖页面200/500行分页，读取当前已校验归属的完整准备批次。
    restoreHighest(response, items.selectList(Wrappers.lambdaQuery(PricePrepareItem.class)
        .eq(PricePrepareItem::getPrepareNo, scenario.getBatch().getPrepareNo())
        .eq(PricePrepareItem::getOaNo, response.getOaNo())
        .eq(PricePrepareItem::getOaFormItemId, response.getOaFormItemId())
        .eq(PricePrepareItem::getPeriodMonth, response.getPeriodMonth())));
  }

  /** 只恢复本报价、本产品、同月、同取价组织曾实际采用的确认，不把别人的审核复制给当前产品。 */
  public void restoreHighest(QuotePricePrepareWorkbenchResponse response, List<PricePrepareItem> rows) {
    Map<Long, Set<String>> organizations = new LinkedHashMap<>();
    String unit = null;
    for (var row : rows) {
      if (!Objects.equals(row.getOaNo(), response.getOaNo())
          || !Objects.equals(row.getOaFormItemId(), response.getOaFormItemId())
          || !Objects.equals(row.getPeriodMonth(), response.getPeriodMonth())) continue;
      if (row.getBusinessUnitType() != null) unit = row.getBusinessUnitType();
      if (row.getMonthlyPriceId() != null) organizations
          .computeIfAbsent(row.getMonthlyPriceId(), ignored -> new LinkedHashSet<>()).add(row.getPriceOrgCode());
      var inputs = evidence.read(row);
      if (inputs == null) continue;
      for (var price : inputs.prices()) {
        if (!price.request().family().month().toString().equals(response.getPeriodMonth())) continue;
        unit = price.request().family().businessUnitType();
        if (price.selection().monthlyPriceId() != null) organizations
            .computeIfAbsent(price.selection().monthlyPriceId(), ignored -> new LinkedHashSet<>())
            .add(price.request().priceOrgCode());
      }
    }
    if (organizations.isEmpty() || unit == null) return;
    Map<String, SupplierPriceReview> reviews = new LinkedHashMap<>();
    for (var review : response.getSupplierPriceReviews()) reviews.put(review.scopeKey(), review);
    for (var stored : monthlyPrices.readByIds(unit, organizations.keySet())) {
      var original = codec.decode(codec.wrap(stored.firstUseJson()), JsonNode.class);
      for (var node : original.path("supplierReviews")) {
        var review = codec.decode(codec.wrap(node.toString()), SupplierPriceReview.class);
        var scope = review.scope();
        if (SupplierPriceSelectionService.FALLBACK_HIGH.equals(review.status())
            && Objects.equals(scope.businessUnitType(), stored.family().businessUnitType())
            && Objects.equals(scope.oaNo(), response.getOaNo())
            && Objects.equals(scope.oaFormItemId(), response.getOaFormItemId())
            && Objects.equals(scope.periodMonth(), response.getPeriodMonth())
            && Objects.equals(scope.materialCode(), stored.family().materialCode())
            && organizations.get(stored.id()).contains(scope.orgCode())) reviews.putIfAbsent(review.scopeKey(), review);
      }
    }
    response.setSupplierPriceReviews(new ArrayList<>(reviews.values()));
  }

  public List<Material> describe(List<SupplierPriceReview> reviews) {
    if (reviews == null || reviews.isEmpty()) return List.of();
    Map<String, LinkedHashSet<String>> codesByOrganization = new LinkedHashMap<>();
    for (var review : reviews) codesByOrganization
        .computeIfAbsent(organization(review), ignored -> new LinkedHashSet<>())
        .add(review.scope().materialCode());
    Map<String, Map<String, MaterialMasterRaw>> materials = new LinkedHashMap<>();
    codesByOrganization.forEach((organization, codes) -> {
      Map<String, MaterialMasterRaw> byCode = new LinkedHashMap<>();
      for (var row : mapper.selectByLatestBatchAndCodes(codes, null, organization)) {
        byCode.put(row.getMaterialCode(), row);
      }
      materials.put(organization, byCode);
    });
    List<Material> result = new ArrayList<>();
    for (var review : reviews) {
      var row = materials.get(organization(review)).get(review.scope().materialCode());
      result.add(new Material(review.scopeKey(), row == null ? null : row.getMaterialName(),
          row == null ? null : row.getMaterialSpec(), row == null ? null : row.getMaterialModel(),
          row == null ? null : row.getUnit(), row == null ? null : row.getShapeAttr()));
    }
    return result;
  }

  private String organization(SupplierPriceReview review) {
    return MaterialOrganization.fromPriceOrgCode(review.scope().orgCode()).getCode();
  }
}
