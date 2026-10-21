package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.pricing.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 把已提交价格来源接到现有固定价、联动计算和结果存储，逐次核验当前适用条件。 */
@Service
public class TechnicalPriceSourceResolverImpl implements TechnicalPriceSourceResolver {
  private final PriceFixedItemMapper fixed;
  private final PriceLinkedItemMapper linked;
  private final PriceLinkedCalcItemMapper calculations;
  private final PriceLinkedCalcServiceImpl calculator;
  private final OaFormMapper forms;
  private final OaFormItemMapper formItems;
  private final MaterialMasterRawMapper materials;
  private final JdbcTemplate jdbc;
  private final TechnicalPriceSourceMapper sources;

  public TechnicalPriceSourceResolverImpl(
      PriceFixedItemMapper fixed,
      PriceLinkedItemMapper linked,
      PriceLinkedCalcItemMapper calculations,
      PriceLinkedCalcServiceImpl calculator,
      OaFormMapper forms,
      OaFormItemMapper formItems,
      MaterialMasterRawMapper materials,
      JdbcTemplate jdbc,
      TechnicalPriceSourceMapper sources) {
    this.fixed = fixed;
    this.linked = linked;
    this.calculations = calculations;
    this.calculator = calculator;
    this.forms = forms;
    this.formItems = formItems;
    this.materials = materials;
    this.jdbc = jdbc;
    this.sources = sources;
  }

  @Override
  @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
  public PriceResolveResult resolve(
      CostRunPartItemDto item, PriceTypeRoute route, CostRunContext context) {
    if (context == null || context.getBusinessUnitType() == null)
      return PriceResolveResult.miss("TECH_PRICE_CONTEXT_MISSING", "补录取价缺少本次业务单元或来源标识");
    Long sourceId = route.sourceRecordId();
    if (sourceId == null) {
      var found = sources.supplemental(item.getPartCode(), context.getBusinessUnitType());
      if (found.size() > 1)
        return PriceResolveResult.miss("TECH_PRICE_SOURCE_CONFLICT", "同一料号存在重复可用补录来源，请核实");
      if (found.isEmpty() || !found.getFirst().priceType().equals(route.priceType().name()))
        return PriceResolveResult.miss("当前无可用补录" + route.priceType().getDbText());
      sourceId = found.getFirst().recordId();
    }
    if (route.priceType() == PriceTypeEnum.FIXED) {
      var row = fixed.selectById(sourceId);
      if (row == null) return PriceResolveResult.miss("补录固定价来源不存在");
      String issue =
          applicable(
              item,
              context,
              row.getMaterialCode(),
              row.getOrgCode(),
              row.getUnit(),
              row.getBusinessUnitType(),
              row.getSourceKind(),
              row.getTechnicalVersionId(),
              row.getTechnicalPublicationStatus());
      if (issue != null) return PriceResolveResult.miss("TECH_PRICE_NOT_APPLICABLE", issue);
      if (row.getFixedPrice() == null || row.getFixedPrice().signum() <= 0)
        return PriceResolveResult.miss("TECH_PRICE_INVALID", "补录固定价不是有效正数");
      return PriceResolveResult.hit(
          row.getFixedPrice(),
          "补录固定价",
          "技术提交版本=" + row.getTechnicalVersionId(),
          row.getId(),
          PriceResolveEvidenceFactory.create(
              row.getId(),
              "TECH:" + row.getTechnicalVersionId(),
              null,
              null,
              null,
              null,
              row.getEffectiveFrom(),
              null,
              context.getPriceAsOfTime() == null
                  ? null
                  : context.getPriceAsOfTime().toLocalDate()));
    }
    var row = linked.selectById(sourceId);
    if (row == null) return PriceResolveResult.miss("补录公式来源不存在");
    String issue =
        applicable(
            item,
            context,
            row.getMaterialCode(),
            row.getOrgCode(),
            row.getUnit(),
            row.getBusinessUnitType(),
            row.getSourceKind(),
            row.getTechnicalVersionId(),
            row.getTechnicalPublicationStatus());
    if (issue != null) return PriceResolveResult.miss("TECH_PRICE_NOT_APPLICABLE", issue);
    // 每次先固定并计算当前输入，再按完整指纹复用；发布变化不依赖手工刷新。
    var calculated = calculate(row, context);
    if (usable(calculated) && !context.isPriceCheckOnly()) {
      if (calculated.getCalcFingerprint() == null || calculated.getInputSnapshotJson() == null)
        throw new IllegalStateException("技术公式计算缺完整输入依据");
      var reusable = existing(calculated, false);
      if (reusable != null) {
        calculated = reusable;
      } else {
        try {
          calculations.insert(calculated);
        } catch (DuplicateKeyException exception) {
          var concurrent = existing(calculated, true);
          if (concurrent == null) throw exception;
          calculated = concurrent;
        }
      }
    }
    if (!usable(calculated)) {
      return PriceResolveResult.miss(
          "TECH_FORMULA_NOT_USABLE", Objects.toString(calculated.getCalcMessage(), "补录公式未取得有效单价"));
    }
    return PriceResolveResult.hit(
        calculated.getPartUnitPrice(),
        "补录联动价",
        "技术提交版本=" + row.getTechnicalVersionId(),
        calculated.getId(),
        new PriceResolveEvidence(
            row.getId(),
            "TECH:" + row.getTechnicalVersionId(),
            row.getSupplierName(),
            row.getSupplierCode(),
            null,
            null,
            row.getEffectiveFrom(),
            null,
            false,
            null,
            calculated.getInputSnapshotJson()));
  }

  /** 发布前也走同一计算器，失败结果用于显示发布原因，不把审批状态当作计算成功。 */
  public PriceLinkedCalcItem calculate(PriceLinkedItem source, CostRunContext context) {
    if (context.getPricingMonth() == null || context.getPriceAsOfTime() == null) {
      throw new IllegalArgumentException("补录公式计算缺少月份或取价时间");
    }
    var value = calculationScope(source, context);
    if (CostRunContext.SCENE_MONTHLY_REPRICE.equals(context.getScene())) {
      calculator.calculateMonthlyAdjustItemForEnsure(
          value, source, context.getSupplierPriceReviewContext());
    } else {
      if (context.getOaNo() == null) throw new IllegalArgumentException("补录公式计算缺少报价来源");
      var formsFound =
          forms.selectList(
              Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, context.getOaNo()));
      if (formsFound.size() != 1) throw new IllegalArgumentException("本次报价来源不唯一");
      calculator.calculateQuoteItemForEnsure(
          value,
          source,
          formsFound.getFirst(),
          context.getPriceVariableOverrides(),
          value.getFactorSource(),
          context.getSupplierPriceReviewContext());
    }
    value.setSourcePriceRecordId(source.getId());
    value.setSourcePriceBatchNo("TECH:" + source.getTechnicalVersionId());
    value.setSourceEffectiveFrom(source.getEffectiveFrom());
    value.setSourceEffectiveTo(null);
    return value;
  }

  private boolean usable(PriceLinkedCalcItem row) {
    return "OK".equals(row.getCalcStatus())
        && row.getPartUnitPrice() != null
        && row.getPartUnitPrice().signum() > 0;
  }

  private PriceLinkedCalcItem calculationScope(PriceLinkedItem source, CostRunContext context) {
    var value = new PriceLinkedCalcItem();
    value.setOaNo(Objects.toString(context.getOaNo(), ""));
    value.setBusinessUnitType(context.getBusinessUnitType());
    value.setPricingMonth(context.getPricingMonth());
    value.setPriceAsOfTime(context.getPriceAsOfTime());
    value.setItemCode(source.getMaterialCode());
    value.setBomQty(BigDecimal.ONE);
    value.setSourceKind("TECH_SUPPLEMENTAL");
    value.setPriceOrgCode(source.getOrgCode());
    if (CostRunContext.SCENE_MONTHLY_REPRICE.equals(context.getScene())) {
      value.setCalcScene("MONTHLY_ADJUST");
      value.setFactorSource("MONTHLY_FACTOR");
    } else {
      value.setCalcScene("QUOTE");
      value.setFactorSource(
          "FINANCE_QUOTE_BASE".equals(context.getPriceScenarioType())
              ? "FINANCE_QUOTE_BASE"
              : "OA_LOCKED");
    }
    return value;
  }

  private PriceLinkedCalcItem existing(PriceLinkedCalcItem row, boolean concurrentWrite) {
    var query =
        Wrappers.lambdaQuery(PriceLinkedCalcItem.class)
            .eq(PriceLinkedCalcItem::getSourceKind, "TECH_SUPPLEMENTAL")
            .eq(PriceLinkedCalcItem::getPriceOrgCode, row.getPriceOrgCode())
            .eq(PriceLinkedCalcItem::getCalcFingerprint, row.getCalcFingerprint())
            .eq(PriceLinkedCalcItem::getBusinessUnitType, row.getBusinessUnitType())
            .eq(PriceLinkedCalcItem::getItemCode, row.getItemCode())
            .eq(PriceLinkedCalcItem::getCalcScene, row.getCalcScene())
            .eq(PriceLinkedCalcItem::getFactorSource, row.getFactorSource())
            .eq(PriceLinkedCalcItem::getPricingMonth, row.getPricingMonth());
    if ("QUOTE".equals(row.getCalcScene())) query.eq(PriceLinkedCalcItem::getOaNo, row.getOaNo());
    query.eq(PriceLinkedCalcItem::getPriceAsOfTime, row.getPriceAsOfTime());
    // 并发插入后使用当前读，不能继续从事务开始时的旧快照查找对方刚提交的结果。
    if (concurrentWrite) query.last("FOR UPDATE");
    var values = calculations.selectList(query);
    if (values.size() > 1) throw new IllegalStateException("本次补录公式结果存在重复记录");
    return values.isEmpty() ? null : values.getFirst();
  }

  private String applicable(
      CostRunPartItemDto item,
      CostRunContext context,
      String code,
      String org,
      String unit,
      String businessUnit,
      String kind,
      Long version,
      String status) {
    if (!"TECH_SUPPLEMENTAL".equals(kind)
        || !"AVAILABLE".equals(status)
        || version == null
        || !Objects.equals(code, item.getPartCode())
        || !Objects.equals(businessUnit, context.getBusinessUnitType())) return "补录来源与本次料号或业务单元不符";
    Integer active =
        jdbc.queryForObject(
            """
SELECT COUNT(*) FROM lp_quote_tech_price_claim c JOIN lp_quote_tech_module m ON m.id=c.owner_module_id
JOIN lp_quote_tech_data_version v ON v.id=m.current_version_id
WHERE c.material_code=? AND v.id=? AND v.version_status IN ('SUBMITTED','APPROVED') AND m.module_status IN ('SUBMITTED','APPROVED')
AND c.organization_code=? AND c.business_unit_type=? AND c.price_unit=? AND c.currency='CNY'
""",
            Integer.class,
            code,
            version,
            org,
            businessUnit,
            unit);
    if (active == null || active != 1) return "补录来源已退回、失效或尚未成功提交";
    String requestedOrg =
        context.getPriceOrgCode() != null ? context.getPriceOrgCode() : item.getPriceOrgCode();
    if (requestedOrg == null) requestedOrg = quotationOrganization(context);
    if (!Objects.equals(requestedOrg, org)) return "补录价格组织与本次报价组织不符";
    var masters =
        materials.selectByLatestBatchAndCodes(
            List.of(code), null, MaterialOrganization.fromPriceOrgCode(org).getCode());
    if (masters.size() != 1 || !Objects.equals(unit, masters.getFirst().getUnit()))
      return "料品采购单位已变化或不唯一，请核实原补录价格";
    return null;
  }

  private String quotationOrganization(CostRunContext context) {
    var found =
        forms.selectList(Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, context.getOaNo()));
    if (found.size() != 1) return null;
    var form = found.getFirst();
    var query = Wrappers.lambdaQuery(OaFormItem.class).eq(OaFormItem::getOaFormId, form.getId());
    if (context.getOaFormItemId() != null) query.eq(OaFormItem::getId, context.getOaFormItemId());
    var orgs =
        formItems.selectList(query).stream()
            .map(
                row ->
                    MaterialOrganization.quoteDataForQuoteProduct(
                            form.getProcessCode(),
                            form.getOaNo(),
                            row.getBusinessUnitType(),
                            row.getProductName(),
                            row.getSunlModel(),
                            row.getMaterialNo())
                        .priceOrgCode())
            .distinct()
            .toList();
    return orgs.size() == 1 ? orgs.getFirst() : null;
  }
}
