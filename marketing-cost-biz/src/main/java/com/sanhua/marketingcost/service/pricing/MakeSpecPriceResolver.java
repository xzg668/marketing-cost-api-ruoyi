package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.MakePartSpec;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.entity.PriceScrap;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MakePartSpecMapper;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.PriceScrapService;
import com.sanhua.marketingcost.util.CmsFieldNormalizeUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 自制件取价 Resolver。
 *
 * <p>取价算法：
 * <pre>
 *   material_cost = blank_weight × raw_unit_price / 1000   (g → kg 换算)
 *   unit_price    = material_cost + process_fee + outsource_fee
 * </pre>
 *
 * <p>raw_unit_price 来源（按优先级）：
 * <ol>
 *   <li>spec.raw_unit_price 非空 → 直接用（手填覆盖）</li>
 *   <li>spec.raw_unit_price 为空 → 缺价，不按 0 兜底，也不拿 raw_material_code 递归取价</li>
 * </ol>
 *
 * <p>废料抵扣（T6 CMS 新口径）：
 * <ol>
 *   <li>spec.raw_material_code → 查 lp_material_scrap_ref 当前 CMS 映射</li>
 *   <li>映射 scrap_code → 查 lp_price_scrap 当前废料价，不按月份过滤</li>
 *   <li>缺映射或缺价必须写入 remark，不能静默当作正常结果</li>
 * </ol>
 * 抵扣额：{@code (blank - net) × scrap_price / 1000}（g→kg 换算）
 */
@Deprecated
public class MakeSpecPriceResolver implements PriceResolver {

  private static final Logger log = LoggerFactory.getLogger(MakeSpecPriceResolver.class);
  private static final BigDecimal WEIGHT_DIVISOR = new BigDecimal("1000");

  private final MakePartSpecMapper makePartSpecMapper;
  private final MaterialScrapRefMapper materialScrapRefMapper;
  private final PriceScrapService priceScrapService;

  public MakeSpecPriceResolver(
      MakePartSpecMapper makePartSpecMapper,
      MaterialScrapRefMapper materialScrapRefMapper,
      PriceScrapService priceScrapService) {
    this.makePartSpecMapper = makePartSpecMapper;
    this.materialScrapRefMapper = materialScrapRefMapper;
    this.priceScrapService = priceScrapService;
  }

  @Override
  public PriceTypeEnum priceType() {
    return PriceTypeEnum.MAKE;
  }

  @Override
  public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
    String rawCode = item.getPartCode();
    if (rawCode == null || rawCode.isBlank()) {
      return PriceResolveResult.miss("partCode 为空");
    }
    String code = rawCode.trim();
    MakePartSpec spec = lookupSpec(code);
    if (spec == null) {
      return PriceResolveResult.miss("lp_make_part_spec 无该料号: " + code);
    }
    BigDecimal materialCost = computeMaterialCost(spec);
    if (materialCost == null) {
      return PriceResolveResult.miss(missingRawUnitPriceRemark(spec));
    }
    // T6：废料抵扣走 CMS 原材料->回收料号映射，历史 recycle_code/recycle_unit_price 不再参与取价。
    ScrapDeduction scrap = computeScrapDeduction(spec, materialCost);
    BigDecimal unitPrice = scrap.adjustedMaterialCost
        .add(nz(spec.getProcessFee()))
        .add(nz(spec.getOutsourceFee()));
    String source = PriceTypeEnum.MAKE.getDbText();
    String remark = scrap.note == null ? "" : scrap.note;
    return new PriceResolveResult(unitPrice, source, remark);
  }

  /**
   * 算材料成本：blank × raw_price / 1000（g→kg）。
   *
   * <p>raw_price 只认 spec.raw_unit_price。字段为空时返回 null，让部品明细标缺价。
   * raw_unit_price 明确填 0 时按 0 价命中，不再继续用 raw_material_code 查其它价格。
   */
  private BigDecimal computeMaterialCost(MakePartSpec spec) {
    BigDecimal rawPrice = spec.getRawUnitPrice();
    if (rawPrice == null) {
      return null;
    }
    BigDecimal blank = nz(spec.getBlankWeight());
    return blank.multiply(rawPrice).divide(WEIGHT_DIVISOR, 8, RoundingMode.HALF_UP);
  }

  private String missingRawUnitPriceRemark(MakePartSpec spec) {
    String materialCode = traceValue(spec.getMaterialCode());
    String rawMaterialCode = traceValue(spec.getRawMaterialCode());
    return "自制件原料单价缺失(raw_unit_price为空, material_code=" + materialCode
        + ", raw_material_code=" + rawMaterialCode
        + ")，未按0计算，未递归查raw_material_code";
  }

  /**
   * 算废料抵扣：scrap_weight = blank - net；scrap_price 只来自 CMS 回收料号当前价。
   *
   * <p>单废料：material_cost - scrap_deduction。
   * 多废料：sum(material_cost - each_scrap_deduction)，这是当前业务先确认的临时口径。
   */
  private ScrapDeduction computeScrapDeduction(MakePartSpec spec, BigDecimal materialCost) {
    BigDecimal blank = nz(spec.getBlankWeight());
    BigDecimal net = spec.getNetWeight() == null ? blank : spec.getNetWeight();
    BigDecimal scrapWeight = blank.subtract(net);
    if (scrapWeight.signum() <= 0) {
      return new ScrapDeduction(materialCost, null);
    }
    String rawMaterialCode = CmsFieldNormalizeUtils.normalizeToNull(spec.getRawMaterialCode());
    if (rawMaterialCode == null) {
      return new ScrapDeduction(materialCost, "缺CMS废料映射(raw_material_code=空)");
    }
    List<MaterialScrapRef> mappings = listCurrentScrapMappings(rawMaterialCode);
    if (mappings.isEmpty()) {
      log.warn("自制件 CMS 废料映射缺失: code={}, raw_material_code={}",
          spec.getMaterialCode(), rawMaterialCode);
      return new ScrapDeduction(materialCost,
          "缺CMS废料映射(raw_material_code=" + rawMaterialCode + ")");
    }
    Map<String, MaterialScrapRef> mappingByScrapCode = new LinkedHashMap<>();
    for (MaterialScrapRef mapping : mappings) {
      String scrapCode = CmsFieldNormalizeUtils.normalizeToNull(mapping.getScrapCode());
      if (scrapCode != null) {
        mappingByScrapCode.putIfAbsent(scrapCode, mapping);
      }
    }
    List<String> scrapCodes = List.copyOf(mappingByScrapCode.keySet());
    if (scrapCodes.isEmpty()) {
      return new ScrapDeduction(materialCost,
          "缺CMS废料映射(raw_material_code=" + rawMaterialCode + ")");
    }
    Map<String, PriceScrap> currentPrices = priceScrapService.getCurrentByScrapCodes(scrapCodes);
    BigDecimal adjustedMaterialCost = BigDecimal.ZERO;
    List<String> traceNotes = new ArrayList<>();
    List<String> missingPriceNotes = new ArrayList<>();
    for (String scrapCode : scrapCodes) {
      PriceScrap scrapRow = currentPrices.get(scrapCode);
      BigDecimal price = scrapRow == null ? null : scrapRow.getRecyclePrice();
      BigDecimal deduction = price == null
          ? BigDecimal.ZERO
          : scrapWeight.multiply(price).divide(WEIGHT_DIVISOR, 8, RoundingMode.HALF_UP);
      adjustedMaterialCost = adjustedMaterialCost.add(materialCost.subtract(deduction));
      // remark 落部品明细和导出，保留业务核对所需字段即可，避免把完整 CMS 追溯信息塞进结果表。
      traceNotes.add(formatScrapTrace(
          rawMaterialCode, scrapCode, price, scrapWeight, deduction));
      if (price == null) {
        log.warn("自制件废料价缺失: code={}, raw_material_code={}, scrap_code={}",
            spec.getMaterialCode(), rawMaterialCode, scrapCode);
        missingPriceNotes.add("缺废料价(scrap_code=" + scrapCode + ")");
      }
    }
    String note = String.join(";", traceNotes);
    if (!missingPriceNotes.isEmpty()) {
      note = note + ";" + String.join(";", missingPriceNotes);
    }
    return new ScrapDeduction(adjustedMaterialCost, note);
  }

  private String formatScrapTrace(
      String rawMaterialCode,
      String scrapCode,
      BigDecimal price,
      BigDecimal scrapWeight,
      BigDecimal deduction) {
    return "CMS废料("
        + "raw=" + rawMaterialCode
        + ",scrap=" + scrapCode
        + ",price=" + (price == null ? "缺失" : formatDecimal(price))
        + ",weight=" + formatDecimal(scrapWeight)
        + ",deduct=" + formatDecimal(deduction)
        + ")";
  }

  private String traceValue(String value) {
    return value == null || value.isBlank() ? "空" : value;
  }

  private static String formatDecimal(BigDecimal value) {
    return nz(value).stripTrailingZeros().toPlainString();
  }

  private List<MaterialScrapRef> listCurrentScrapMappings(String rawMaterialCode) {
    var query = Wrappers.lambdaQuery(MaterialScrapRef.class)
        .eq(MaterialScrapRef::getMaterialCode, rawMaterialCode)
        .orderByDesc(MaterialScrapRef::getId);
    String businessUnitType = BusinessUnitContext.getCurrentBusinessUnitType();
    if (businessUnitType != null && !businessUnitType.isBlank()) {
      query.eq(MaterialScrapRef::getBusinessUnitType, businessUnitType.trim());
    }
    List<MaterialScrapRef> rows = materialScrapRefMapper.selectList(query);
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    return rows;
  }

  /** 废料计算结果：adjustedMaterialCost=扣废料后的材料金额；note=remark（缺映射/缺价时填）。 */
  private record ScrapDeduction(BigDecimal adjustedMaterialCost, String note) {}

  /**
   * 查 spec：先 (code) 取最新一条；不做 period 严格过滤
   * （v1 数据所有期间放宽，按 effective_from / id 倒排取首条）。
   */
  private MakePartSpec lookupSpec(String code) {
    List<MakePartSpec> rows =
        makePartSpecMapper.selectList(
            Wrappers.lambdaQuery(MakePartSpec.class)
                .eq(MakePartSpec::getMaterialCode, code)
                .orderByDesc(MakePartSpec::getEffectiveFrom)
                .orderByDesc(MakePartSpec::getId)
                .last("LIMIT 1"));
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
