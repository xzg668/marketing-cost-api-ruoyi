package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.BusinessUnitRepriceLockGuard;
import com.sanhua.marketingcost.service.MonthlyRepriceAuditService;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class BusinessUnitRepriceLockGuardImpl implements BusinessUnitRepriceLockGuard {

  private static final String ROLE_BU_DIRECTOR = "BU_DIRECTOR";
  private static final String PERM_MONTHLY_REPRICE_OPERATE = "price:monthly-reprice:operate";
  private static final List<String> LOCKING_STATUSES =
      List.of("CREATED", "PREPARING", "RUNNING", "WAIT_CONFIRM");

  private final OaFormMapper oaFormMapper;
  private final MonthlyRepriceBatchMapper monthlyRepriceBatchMapper;
  private final PermissionService permissionService;
  private final MonthlyRepriceAuditService auditService;

  public BusinessUnitRepriceLockGuardImpl(
      OaFormMapper oaFormMapper,
      MonthlyRepriceBatchMapper monthlyRepriceBatchMapper,
      PermissionService permissionService,
      MonthlyRepriceAuditService auditService) {
    this.oaFormMapper = oaFormMapper;
    this.monthlyRepriceBatchMapper = monthlyRepriceBatchMapper;
    this.permissionService = permissionService;
    this.auditService = auditService;
  }

  @Override
  public void assertCostRunAllowed(String oaNo) {
    if (isMonthlyRepriceOperator() || !StringUtils.hasText(oaNo)) {
      return;
    }
    String businessUnitType = resolveBusinessUnitType(oaNo.trim());
    if (!StringUtils.hasText(businessUnitType)) {
      return;
    }
    MonthlyRepriceBatch activeBatch =
        monthlyRepriceBatchMapper.selectOne(
            Wrappers.lambdaQuery(MonthlyRepriceBatch.class)
                .eq(MonthlyRepriceBatch::getBusinessUnitType, businessUnitType)
                .in(MonthlyRepriceBatch::getStatus, LOCKING_STATUSES)
                .orderByDesc(MonthlyRepriceBatch::getUpdatedAt)
                .last("LIMIT 1"));
    if (activeBatch != null) {
      auditService.recordOaCostRunBlocked(activeBatch, oaNo.trim(), currentOperator());
      throw new IllegalStateException(
          "当前业务单元正在月度调价，暂不能发起普通 OA 成本核算：" + businessUnitType);
    }
  }

  private boolean isMonthlyRepriceOperator() {
    return BusinessUnitContext.isAdmin()
        || permissionService.hasRole(ROLE_BU_DIRECTOR)
        || permissionService.hasPermi(PERM_MONTHLY_REPRICE_OPERATE);
  }

  private String resolveBusinessUnitType(String oaNo) {
    OaForm form =
        oaFormMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, oaNo).last("LIMIT 1"));
    if (form != null && StringUtils.hasText(form.getBusinessUnitType())) {
      return form.getBusinessUnitType().trim();
    }
    String currentBusinessUnitType = BusinessUnitContext.getCurrentBusinessUnitType();
    return StringUtils.hasText(currentBusinessUnitType) ? currentBusinessUnitType.trim() : null;
  }

  private String currentOperator() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null ? "system" : authentication.getName();
  }
}
