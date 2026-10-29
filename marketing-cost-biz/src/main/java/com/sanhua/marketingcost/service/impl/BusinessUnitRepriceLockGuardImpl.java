package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.BusinessUnitRepriceLockGuard;
import com.sanhua.marketingcost.service.reprice.MonthlyQuotationControl;
import org.springframework.stereotype.Service;

/** Legacy quotation entry points delegate to the same current control, without role bypasses. */
@Service
public class BusinessUnitRepriceLockGuardImpl implements BusinessUnitRepriceLockGuard {
  private final OaFormMapper forms;
  private final MonthlyQuotationControl control;

  public BusinessUnitRepriceLockGuardImpl(OaFormMapper forms, MonthlyQuotationControl control) {
    this.forms = forms;
    this.control = control;
  }

  @Override public void assertCostRunAllowed(String oaNo) {
    var form = forms.selectOne(Wrappers.<OaForm>lambdaQuery().eq(OaForm::getOaNo, oaNo));
    if (form == null) throw new IllegalArgumentException("报价单不存在");
    control.requireAllowed(form.getBusinessUnitType());
  }
}
