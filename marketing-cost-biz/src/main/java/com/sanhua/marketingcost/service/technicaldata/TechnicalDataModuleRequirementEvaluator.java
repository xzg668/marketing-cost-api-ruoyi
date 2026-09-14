package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPublishRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TechnicalDataModuleRequirementEvaluator {

  public List<TechnicalDataModuleRequirement> evaluate(
      TechnicalDataTaskPublishRequest.Product product) {
    if (product == null) throw new IllegalArgumentException("产品不能为空");
    boolean newProduct = yes(product.newProduct());
    return List.of(
        profile(product, newProduct),
        packaging(product, newProduct),
        auxiliary(product),
        salary(product));
  }

  private TechnicalDataModuleRequirement profile(
      TechnicalDataTaskPublishRequest.Product product, boolean newProduct) {
    List<String> codes = new ArrayList<>();
    List<String> reasons = new ArrayList<>();
    add(codes, reasons, newProduct, "NEW_PRODUCT", "新品需要技术确认基本信息");
    add(codes, reasons, !StringUtils.hasText(product.sourceModel()),
        "MODEL_MISSING", "OA产品型号缺失");
    add(codes, reasons, !StringUtils.hasText(product.sourceProductProperty()),
        "PROPERTY_MISSING", "OA产品属性缺失");
    return decision("PROFILE", codes, reasons,
        "PROFILE_COMPLETE", "OA产品型号、属性完整且不是新品");
  }

  private TechnicalDataModuleRequirement packaging(
      TechnicalDataTaskPublishRequest.Product product, boolean newProduct) {
    List<String> codes = new ArrayList<>();
    List<String> reasons = new ArrayList<>();
    add(codes, reasons, newProduct, "NEW_PRODUCT", "新品需要确认包装方案");
    add(codes, reasons, yes(product.nonStandardPackage()),
        "NON_STANDARD_PACKAGE", "产品使用非标包装");
    add(codes, reasons, !yes(product.validPackageSource()),
        "PACKAGE_SOURCE_MISSING", "未找到有效包装来源");
    return decision("PACKAGE", codes, reasons,
        "PACKAGE_SOURCE_AVAILABLE", "已有有效标准包装来源");
  }

  private TechnicalDataModuleRequirement auxiliary(
      TechnicalDataTaskPublishRequest.Product product) {
    List<String> codes = new ArrayList<>();
    List<String> reasons = new ArrayList<>();
    add(codes, reasons, !yes(product.validCmsAuxiliarySource()),
        "CMS_AUX_SOURCE_MISSING", "当前产品和月份无可用CMS辅料来源");
    add(codes, reasons, yes(product.auxiliaryRequested()),
        "QUOTE_AUX_REQUESTED", "报价发起人明确要求补充辅料");
    return decision("AUXILIARY", codes, reasons,
        "CMS_AUX_SOURCE_AVAILABLE", "当前产品和月份已有可用CMS辅料来源");
  }

  private TechnicalDataModuleRequirement salary(
      TechnicalDataTaskPublishRequest.Product product) {
    List<String> codes = new ArrayList<>();
    List<String> reasons = new ArrayList<>();
    add(codes, reasons, !yes(product.validCmsSalarySource()),
        "CMS_SALARY_SOURCE_MISSING", "当前产品和月份无可用CMS工资来源");
    add(codes, reasons, yes(product.salaryRequested()),
        "QUOTE_SALARY_REQUESTED", "报价发起人明确要求补充工资");
    return decision("SALARY", codes, reasons,
        "CMS_SALARY_SOURCE_AVAILABLE", "当前产品和月份已有可用CMS工资来源");
  }

  private TechnicalDataModuleRequirement decision(
      String moduleType,
      List<String> codes,
      List<String> reasons,
      String optionalCode,
      String optionalReason) {
    return codes.isEmpty()
        ? new TechnicalDataModuleRequirement(moduleType, false, optionalCode, optionalReason)
        : new TechnicalDataModuleRequirement(
            moduleType, true, String.join("+", codes), String.join("；", reasons));
  }

  private void add(
      List<String> codes,
      List<String> reasons,
      boolean matched,
      String code,
      String reason) {
    if (!matched) return;
    codes.add(code);
    reasons.add(reason);
  }

  private boolean yes(Boolean value) {
    return Boolean.TRUE.equals(value);
  }
}
