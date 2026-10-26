package com.sanhua.marketingcost.formula.registry;

import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.service.FactorCanonicalKeyService;
import java.util.Map;
import org.springframework.util.StringUtils;

/** Shared financial binding policy; accepts an already captured identity and performs no I/O. */
public final class FinanceFactorBinding {
  private FinanceFactorBinding() {}

  public static Long identityId(Map<String, ?> binding) {
    Object id = binding.get("factorIdentityId");
    return id == null ? null : Long.valueOf(id.toString());
  }

  public static String canonicalKey(
      Map<String, ?> binding,
      String businessUnit,
      FactorIdentity identity,
      FactorCanonicalKeyService keys) {
    Long id = identityId(binding);
    if (id != null) {
      if (identity == null
          || !businessUnit.equals(identity.getBusinessUnitType())
          || !"ACTIVE".equals(identity.getStatus())) {
        throw new IllegalArgumentException("因素身份不存在、已停用或不属于当前业务单元：" + id);
      }
      String canonical =
          StringUtils.hasText(identity.getCanonicalFactorKey())
              ? keys.normalizeExistingKey(identity.getCanonicalFactorKey())
              : keys.build(identity.getPriceSource(), identity.getShortName());
      if (!StringUtils.hasText(canonical))
        throw new IllegalArgumentException("因素身份缺少简称或价源：" + id);
      return canonical;
    }
    String code = text(binding.get("factorCode"));
    String name = StringUtils.hasText(code) ? code : text(binding.get("shortName"));
    String key = keys.build(text(binding.get("priceSource")), name);
    if (!StringUtils.hasText(key)) throw new IllegalArgumentException("财务变量必须配置因素身份及价源");
    return key;
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }
}
