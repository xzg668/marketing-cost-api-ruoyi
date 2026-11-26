package com.sanhua.marketingcost.service.quotebom;

import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 按精确型号和同组织可用 U9 BOM 查找料号。已给的正式料号和原 OA 字段始终保留。 */
@Service
public class QuoteModelBomProducts {
  public static class Unresolved extends QuoteIngestException {
    public Unresolved(String message) { super(message); }
  }
  public record Candidate(String materialCode, String name, String specification, String material, String model) {}
  public record Match(String model, String month, String organization, List<Candidate> candidates, String selected, boolean saved) {}
  private final JdbcTemplate jdbc;
  private final LiveU9BomGateway live;

  public QuoteModelBomProducts(JdbcTemplate jdbc, LiveU9BomGateway live) {
    this.jdbc = jdbc;
    this.live = live;
  }

  public static boolean modelOnly(OaFormItem item) {
    return item != null && !StringUtils.hasText(item.getMaterialNo()) && StringUtils.hasText(item.getSunlModel());
  }

  public Match inspect(OaForm form, OaFormItem item, String month) {
    if (!modelOnly(item)) return null;
    var org = MaterialOrganization.quoteDataForQuoteProduct(form.getProcessCode(), form.getOaNo(),
        item.getBusinessUnitType(), item.getProductName(), item.getSunlModel(), null);
    String model = item.getSunlModel().trim();
    String saved = AdoptedModelProduct.find(jdbc, item.getId(), month, org.materialOrganizationCode(), model);
    var rows = jdbc.query("""
        SELECT material_code,material_name,material_spec,global_seg_4_material,material_model
        FROM lp_material_master_raw WHERE organization_code=? AND active_flag=1
        AND LEFT(TRIM(material_model),8)=LEFT(?,8) AND TRIM(material_model)=? ORDER BY material_code
        """, (rs, i) -> new Candidate(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
        org.materialOrganizationCode(), model, model);
    if (saved != null) {
      var prior = rows.stream().filter(row -> row.materialCode().equals(saved)).findFirst();
      if (prior.isPresent() && available(form, item, month, org, saved))
        return new Match(model, month, org.materialOrganizationCode(), List.of(prior.get()), saved, true);
    }
    var candidates = new ArrayList<Candidate>();
    for (var row : rows) {
      if (available(form, item, month, org, row.materialCode())) candidates.add(row);
    }
    String prior = saved;
    String selected = candidates.stream().anyMatch(row -> row.materialCode().equals(prior)) ? prior
        : candidates.size() == 1 ? candidates.getFirst().materialCode() : null;
    return new Match(model, month, org.materialOrganizationCode(), List.copyOf(candidates), selected,
        selected != null && Objects.equals(prior, selected));
  }

  private boolean available(OaForm form, OaFormItem item, String month, QuoteDataOrganization org, String code) {
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    var context = new QuoteBomReadContext(form.getId(), item.getId(), form.getOaNo(), month,
        form.getBusinessUnitType(), code, item.getProductName(), item.getSpec(), item.getSunlModel(),
        org.priceOrgCode(), org.materialOrganizationCode(), now.toLocalDate(), now);
    String key = U9MonthlySnapshotIdentity.from(context).identityKey();
    if (Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM lp_quote_bom_monthly_snapshot s WHERE s.snapshot_identity_key=?
          AND s.active_flag=1 AND s.sync_status='SUCCESS' AND EXISTS
          (SELECT 1 FROM lp_quote_bom_monthly_snapshot_detail d WHERE d.monthly_snapshot_id=s.id))
        """, Boolean.class, key))) return true;
    var result = live.readLive(context);
    if (result != null && result.status() == CurrentU9BomResult.Status.AVAILABLE) return true;
    if (result != null && (result.status() == CurrentU9BomResult.Status.NOT_FOUND
        || result.status() == CurrentU9BomResult.Status.ORGANIZATION_MISMATCH)) return false;
    throw new Unresolved("料号 " + code + " 的 U9 BOM 尚未核实：" + (result == null ? "查询无响应" : result.message()));
  }

  public OaFormItem resolve(OaForm form, OaFormItem item, String month) {
    return modelOnly(item) ? project(item, inspect(form, item, month)) : item;
  }

  public OaFormItem project(OaFormItem item, Match match) {
    if (match == null || match.candidates().isEmpty()) return item;
    if (match.selected() == null) throw new Unresolved("该型号对应多个有有效 U9 BOM 的料号，请财务在单产品核算工作台选择一个");
    return AdoptedModelProduct.withCode(item, match.selected());
  }

  public void bindFirst(Long itemId, Match match) {
    jdbc.update("""
        INSERT INTO lp_quote_model_bom_choice(oa_form_item_id,period_month,material_org,quote_model,material_code)
        VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE material_code=lp_quote_model_bom_choice.material_code
        """, itemId, match.month(), match.organization(), match.model(), match.selected());
    String adopted = jdbc.queryForObject("""
        SELECT material_code FROM lp_quote_model_bom_choice WHERE oa_form_item_id=? AND period_month=?
          AND material_org=? AND quote_model=? FOR UPDATE
        """,String.class,itemId,match.month(),match.organization(),match.model());
    if (!match.selected().equals(adopted)) throw new Unresolved("型号对应料号已变化，请在核算工作台核实后重试");
  }

  /** 调用方持有报价行锁，并在同一事务核实客户端看到的型号、月份和候选。 */
  public void adopt(Long itemId, Match match, String code) {
    if (match == null || code == null || match.candidates().stream().noneMatch(row -> code.equals(row.materialCode())))
      throw new QuoteIngestException("所选料号已不适用于本次报价，请刷新");
    if (match.saved() && !code.equals(match.selected())) throw new QuoteIngestException("本行已采用其他料号，请刷新");
    jdbc.update("""
        INSERT INTO lp_quote_model_bom_choice(oa_form_item_id,period_month,material_org,quote_model,material_code)
        VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE material_code=VALUES(material_code)
        """, itemId, match.month(), match.organization(), match.model(), code);
  }
}
