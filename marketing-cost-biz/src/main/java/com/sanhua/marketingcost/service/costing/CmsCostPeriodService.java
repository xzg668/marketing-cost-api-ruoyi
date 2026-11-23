package com.sanhua.marketingcost.service.costing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.AuxCostItemDto;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceRefreshRequest;
import com.sanhua.marketingcost.dto.CostAmountComposition;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.AuxCostItemMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.service.CmsAuxSubjectSourceEffectiveService;
import com.sanhua.marketingcost.service.CostBusinessRuleProvider;
import com.sanhua.marketingcost.service.CmsSalaryCostSourceEffectiveService;
import com.sanhua.marketingcost.service.QuotationMaterialShapeInputs;
import com.sanhua.marketingcost.service.jjb.JjbVersionAccess;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CmsCostPeriodService {
  public static final String DIRECT = "DIRECT_LABOR";
  static final String INDIRECT = "INDIRECT_LABOR";

  private record Expense(String code, String type, String subject, String label,
      MonthlyProductCost.OrdinaryDetail detail) {
    boolean auxiliary() { return "AUX_SUBJECT".equals(type); }
  }

  public record State(String materialCode, int costYear, String currentPeriod, String currentAmount,
      String expenseName, Integer latestMonth) {}
  public record Preview(String period, CostAmountComposition composition, List<CmsCostPeriodPreview.Amount> costs,
      String costPreviewNotice, String token) {}
  public record Selection(State source, Preview preview) {}
  public record SaveRequest(String period, String expectedPeriod, String previewToken) {}
  public record Change(State source, boolean changed, String oaNo, Long itemId, String pricingMonth) {}
  private record Scope(JjbVersionAccess.Owned owned, YearMonth pricingMonth, MonthlyProductCost.Result calculation, Expense expense) {
    String materialCode() { return owned.version().getProductCode(); }
  }

  private final JjbVersionAccess access;
  private final MonthlyCostVersionService versions;
  private final CmsSalaryCostSourceEffectiveService salary;
  private final CmsAuxSubjectSourceEffectiveService auxiliary;
  private final AuxCostItemMapper auxRows;
  private final CostBusinessRuleProvider rules;
  private final CmsCostSourceEffectiveMapper sources;
  private final CmsShapeCostAdjustment shapes;
  private final QuotationMaterialShapeInputs quoteShapes;
  private final CmsCompositionReader details;
  private final OaMessageCodec json;

  public CmsCostPeriodService(JjbVersionAccess access, MonthlyCostVersionService versions,
      CmsSalaryCostSourceEffectiveService salary, CmsAuxSubjectSourceEffectiveService auxiliary,
      AuxCostItemMapper auxRows, CostBusinessRuleProvider rules, CmsCostSourceEffectiveMapper sources,
      CmsShapeCostAdjustment shapes,
      QuotationMaterialShapeInputs quoteShapes, CmsCompositionReader details, OaMessageCodec json) {
    this.access = access;
    this.versions = versions;
    this.salary = salary;
    this.auxiliary = auxiliary;
    this.auxRows = auxRows;
    this.rules = rules;
    this.sources = sources;
    this.shapes = shapes;
    this.quoteShapes = quoteShapes;
    this.details = details;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public Selection select(String run, String costCode, String period) {
    Scope scope = scope(run, costCode);
    if (scope == null) return null;
    State source = state(scope, current(scope, false));
    if (period == null) return new Selection(source, null);
    String month = parsePeriod(period, scope);
    return new Selection(source, Objects.equals(source.currentPeriod(), month) ? null : preview(scope, month));
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public Change save(String run, String costCode, SaveRequest request, String operator) {
    Scope scope = scope(run, costCode);
    if (scope == null) throw new IllegalArgumentException("此成本项目不可调整 CMS 采用期间");
    if (request == null) throw new IllegalArgumentException("请选择 CMS 采用期间");
    String period = parsePeriod(request.period(), scope);
    State current = state(scope, current(scope, true));
    if (!Objects.equals(current.currentPeriod(), request.expectedPeriod())) {
      throw new IllegalArgumentException("当前生效期间已变为" + Objects.toString(current.currentPeriod(), "无") + "，请重新确认");
    }
    if (period.equals(current.currentPeriod())) return change(scope, current, false);
    var proposed = preview(scope, period);
    if (!proposed.token().equals(request.previewToken())) {
      throw new IllegalArgumentException("所选期间的 CMS 数据或核算依据已变化，请重新试算后确认");
    }
    var update = refreshRequest(scope, period);
    String unit = scope.owned().businessUnit();
    var saved = scope.expense().auxiliary()
        ? auxiliary.refreshSource(update, operator, unit) : salary.refreshSource(update, operator, unit);
    return change(scope, state(scope, saved), true);
  }

  private Scope scope(String run, String costCode) {
    var owned = access.byRun(run);
    var version = owned.version();
    if (version.getRepriceNo() != null || !Set.of("SUCCESS", "CONFIRMED").contains(version.getStatus())
        || !version.getProductCode().equals(QuoteProductIdentityUtils.resolveCostingCode(owned.item()))) return null;
    var snapshot = versions.read(owned.businessUnit(), version.getId()).snapshot();
    if (snapshot == null) return null;
    var calculation = snapshot.calculation();
    var expense = expense(costCode, calculation);
    if (expense == null) return null;
    return new Scope(owned, YearMonth.parse(version.getPricingMonth()), calculation, expense);
  }

  private Expense expense(String code, MonthlyProductCost.Result calculation) {
    var technical = calculation.technicalInput();
    if (DIRECT.equals(code) || INDIRECT.equals(code)) {
      if (technical != null && technical.salaryRequired()) return null;
      return DIRECT.equals(code) ? new Expense(code, "SALARY_DIRECT", null, "直接人工工资", null)
          : new Expense(code, "SALARY_INDIRECT", null, "辅助人工工资", null);
    }
    if (code == null || !code.startsWith("AUX_") || technical != null && technical.auxiliaryRequired()) return null;
    String subject = code.substring("AUX_".length());
    var rows = calculation.ordinaryInputs().details().stream().filter(item -> code.equals(item.costCode())).toList();
    if (rows.size() != 1) return null;
    var detail = rows.getFirst();
    if (detail.amount() == null || detail.composition() == null || detail.composition().sources().stream().noneMatch(
        source -> "cms_product_subject_cost_raw".equals(source.table()) && subject.equals(source.subjectCode()))) return null;
    return new Expense(code, "AUX_SUBJECT", subject, detail.costName(), detail);
  }

  private CmsEffectiveSourceRefreshRequest refreshRequest(Scope scope, String period) {
    var request = new CmsEffectiveSourceRefreshRequest();
    request.setCostYear(scope.pricingMonth().getYear());
    request.setSourceType(scope.expense().type());
    request.setSubjectCode(scope.expense().subject());
    request.setParentCode(scope.materialCode());
    request.setNewPeriod(period);
    request.setRefreshReason("见机表调整" + scope.expense().label() + "采用期间");
    return request;
  }

  private CmsCostSourceEffective current(Scope scope, boolean lock) {
    var query = Wrappers.lambdaQuery(CmsCostSourceEffective.class)
        .eq(CmsCostSourceEffective::getCostYear, scope.pricingMonth().getYear())
        .eq(CmsCostSourceEffective::getSourceType, scope.expense().type())
        .eq(scope.expense().auxiliary(), CmsCostSourceEffective::getSubjectCode, scope.expense().subject())
        .eq(CmsCostSourceEffective::getParentCode, scope.materialCode())
        .eq(CmsCostSourceEffective::getBusinessUnitType, scope.owned().businessUnit());
    return sources.selectOne(lock ? query.last("FOR UPDATE") : query);
  }

  private Preview preview(Scope scope, String period) {
    String unit = scope.owned().businessUnit();
    var expense = scope.expense();
    var candidate = switch (expense.type()) {
      case "SALARY_DIRECT" -> salary.previewDirectSource(scope.materialCode(), period, unit);
      case "SALARY_INDIRECT" -> salary.previewIndirectSource(scope.materialCode(), period, unit);
      default -> auxiliary.previewSource(refreshRequest(scope, period), unit);
    };
    var context = new CostRunContext();
    context.setOaFormItemId(scope.owned().item().getId());
    context.setPricingMonth(scope.pricingMonth().toString());
    context.setBusinessUnitType(unit);
    var exclusions = quoteShapes.cmsExclusions(context);
    var item = new CostRunCostItemDto();
    BigDecimal proposed;
    if (expense.auxiliary()) {
      var row = new AuxCostItemDto();
      row.setSource("CMS_EFFECTIVE");
      row.setSourceTable(candidate.getSourceTable());
      row.setSourceRowIds(candidate.getSourceRowIds());
      row.setSourcePeriod(period);
      row.setMaterialCode(scope.materialCode());
      row.setAuxSubjectCode(expense.subject());
      row.setUnitPrice(candidate.getAmountYuan());
      SinteredBaseSurfaceCosts.apply(List.of(row), unit, auxRows);
      row = shapes.auxiliary(row, unit, exclusions);
      item.setComposition(CmsCostCompositions.auxiliary(row));
      BigDecimal rate = rules.decimalValue(CostBusinessRuleProvider.CMS_AUX_UPLIFT_RATE,
          scope.pricingMonth().toString(), unit, new BigDecimal("1.05"));
      proposed = row.getUnitPrice().multiply(rate).setScale(6, RoundingMode.HALF_UP);
    } else {
      var adopted = shapes.salary(candidate, unit, exclusions);
      item.setComposition(CmsCostCompositions.labor(List.of(adopted)));
      proposed = adopted.getAmountYuan();
    }
    details.complete(List.of(item), unit);
    var composition = item.getComposition();
    var cost = CmsCostPeriodPreview.calculate(scope.calculation(), expense.code(), expense.label(), proposed,
        expense.detail() == null ? null : expense.detail().amount());
    String notice = cost.unavailableFee() == null ? null
        : "旧版本缺少" + cost.unavailableFee() + "的计算依据。保存后按当前资料重新核算见机表。";
    var token = json.canonicalHash(new Preview(period, composition, cost.amounts(), notice, null));
    return new Preview(period, composition, cost.amounts(), notice, token);
  }

  private static State state(Scope scope, CmsCostSourceEffective source) {
    return new State(scope.materialCode(), scope.pricingMonth().getYear(), source == null ? null : source.getPeriod(),
        source == null ? null : CmsCostCompositions.number(source.getAmountYuan()),
        scope.expense().label(), scope.expense().auxiliary() ? scope.pricingMonth().getMonthValue() : null);
  }

  private static Change change(Scope scope, State source, boolean changed) {
    return new Change(source, changed, scope.owned().form().getOaNo(), scope.owned().item().getId(),
        scope.pricingMonth().toString());
  }

  private static String parsePeriod(String value, Scope scope) {
    int year = scope.pricingMonth().getYear();
    YearMonth period = null;
    try {
      if (value != null) period = YearMonth.parse(value);
    } catch (DateTimeParseException ignored) {
      // The same validation message covers malformed values and other years.
    }
    if (period == null || period.getYear() != year) {
      throw new IllegalArgumentException("请选择" + year + "年度内的有效月份");
    }
    if (scope.expense().auxiliary() && period.isAfter(scope.pricingMonth())) {
      throw new IllegalArgumentException("CMS 辅料来源期间不能晚于本单核算月 " + scope.pricingMonth());
    }
    return value;
  }
}
