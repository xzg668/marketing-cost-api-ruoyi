package com.sanhua.marketingcost.service.reprice;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** The fence surrounds transaction commit; worker and direct HTTP service calls share it. */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 2)
public class QuotationWriteAspect {
  private final MonthlyQuotationControl control;
  private final OaFormMapper forms;
  private final OaFormItemMapper items;

  public QuotationWriteAspect(MonthlyQuotationControl control, OaFormMapper forms, OaFormItemMapper items) {
    this.control = control;
    this.forms = forms;
    this.items = items;
  }

  @Around("@annotation(policy)")
  public Object gate(ProceedingJoinPoint operation, QuotationWrite policy) throws Throwable {
    Object first = operation.getArgs()[0];
    if (first instanceof QuoteCuAdjustmentCalcRequest cost && cost.reprice() != null)
      return operation.proceed(); // Only the explicit saved-task calculation uses this internal branch.
    String oa = first instanceof String value ? value
        : first instanceof ProductCostingRequest request ? request.oaNo()
        : first instanceof PricePrepareGenerateRequest request ? request.getOaNo()
        : first instanceof QuoteCuAdjustmentCalcRequest request ? request.form().getOaNo() : null;
    OaForm form;
    if (first instanceof Long itemId) {
      var item = items.selectById(itemId);
      form = item == null ? null : forms.selectById(item.getOaFormId());
    } else {
      form = oa == null ? null : forms.selectOne(Wrappers.<OaForm>lambdaQuery().eq(OaForm::getOaNo, oa));
    }
    if (form == null) throw new IllegalArgumentException("报价单不存在，不能执行报价写入");
    String unit = form.getBusinessUnitType();
    String caller = BusinessUnitContext.getCurrentBusinessUnitType();
    if (caller != null && !caller.isBlank() && !caller.equals(unit))
      throw new AccessDeniedException("当前业务单元无权操作该报价单");
    if (!policy.holdUntilCompletion()) {
      control.requireAllowed(unit);
      return operation.proceed();
    }
    try {
      return control.ordinaryWrite(unit, () -> {
        try { return operation.proceed(); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new CheckedOperation(failure); }
      });
    } catch (CheckedOperation failure) { throw failure.getCause(); }
  }

  private static class CheckedOperation extends RuntimeException {
    CheckedOperation(Throwable cause) { super(cause); }
  }
}
