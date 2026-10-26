package com.sanhua.marketingcost.service.jjb;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Resolve one version once; never fall back after an explicit selector fails. */
@Service
public class JjbVersionAccess {
  public record Owned(QuoteCostRunVersion version, OaForm form, OaFormItem item, String businessUnit) {}
  private final QuoteCostRunVersionMapper versions;
  private final OaFormMapper forms;
  private final OaFormItemMapper items;
  public JjbVersionAccess(QuoteCostRunVersionMapper versions, OaFormMapper forms, OaFormItemMapper items) {
    this.versions = versions; this.forms = forms; this.items = items;
  }
  public Owned read(String oaNo, Long itemId, Long versionId) {
    if (versionId == null || versionId <= 0) throw new IllegalArgumentException("成本版本 ID 必须大于0");
    var owned = own(versions.selectById(versionId));
    if (!Objects.equals(owned.version().getOaNo(), required(oaNo))
        || !Objects.equals(owned.item().getId(), itemId)) denied();
    return owned;
  }
  public Owned byRun(String runNo) {
    String unit = unit();
    var rows = versions.selectList(Wrappers.lambdaQuery(QuoteCostRunVersion.class)
        .eq(QuoteCostRunVersion::getBusinessUnitType, unit)
        .eq(QuoteCostRunVersion::getCostRunNo, required(runNo)).last("LIMIT 2"));
    if (rows.size() != 1) return denied();
    return own(rows.getFirst());
  }
  public Owned resolve(String oaNo, String product, Long itemId, Long versionId, String runNo) {
    oaNo = required(oaNo); product = required(product);
    Owned owned;
    if (versionId != null) {
      if (versionId <= 0) throw new IllegalArgumentException("成本版本 ID 必须大于0");
      owned = own(versions.selectById(versionId));
      if (StringUtils.hasText(runNo) && !runNo.trim().equals(owned.version().getCostRunNo())) return denied();
    } else if (StringUtils.hasText(runNo)) owned = byRun(runNo);
    else {
      var found = versions.selectList(Wrappers.lambdaQuery(QuoteCostRunVersion.class)
          .eq(QuoteCostRunVersion::getBusinessUnitType, unit())
          .eq(QuoteCostRunVersion::getOaNo, oaNo).eq(QuoteCostRunVersion::getProductCode, product)
          .eq(itemId != null, QuoteCostRunVersion::getOaFormItemId, itemId)
          .in(QuoteCostRunVersion::getStatus, List.of("SUCCESS", "CONFIRMED"))
          .orderByDesc(QuoteCostRunVersion::getTrialFinishedAt).orderByDesc(QuoteCostRunVersion::getId));
      if (found.isEmpty()) return denied();
      if (found.stream().map(QuoteCostRunVersion::getOaFormItemId).distinct().count() != 1)
        throw new CostVersionRequiredException();
      owned = own(found.getFirst());
      // A second product line without a success is ambiguous too.
      var siblings = items.selectList(Wrappers.lambdaQuery(OaFormItem.class)
          .eq(OaFormItem::getOaFormId, owned.form().getId()));
      final String selectedProduct = product;
      if (itemId == null && siblings.stream().filter(row -> selectedProduct.equals(QuoteProductIdentityUtils.resolveCostingCode(row))).count() > 1)
        throw new CostVersionRequiredException();
    }
    if (!oaNo.equals(owned.version().getOaNo()) || !product.equals(owned.version().getProductCode())
        || itemId != null && !itemId.equals(owned.item().getId())) return denied();
    return owned;
  }
  private Owned own(QuoteCostRunVersion v) {
    String unit = unit();
    if (v == null || !unit.equals(v.getBusinessUnitType()) || v.getOaFormItemId() == null) return denied();
    var item = items.selectById(v.getOaFormItemId());
    var form = item == null ? null : forms.selectById(item.getOaFormId());
    if (form == null || !v.getOaNo().equals(form.getOaNo())
        || !unit.equals(form.getBusinessUnitType()) || !unit.equals(item.getBusinessUnitType())) return denied();
    // The material code belongs to the saved version. Current product edits cannot rewrite history.
    return new Owned(v, form, item, unit);
  }
  private static String unit() {
    String unit = BusinessUnitContext.getCurrentBusinessUnitType();
    if (!Set.of("COMMERCIAL", "HOUSEHOLD").contains(unit == null ? "" : unit)) return denied();
    return unit;
  }
  private static String required(String value) {
    if (!StringUtils.hasText(value)) throw new IllegalArgumentException("报价、产品或核算版本标识不能为空");
    return value.trim();
  }
  private static <T> T denied() { throw new AccessDeniedException("成本版本不存在或不属于当前报价产品及业务单元"); }
}
