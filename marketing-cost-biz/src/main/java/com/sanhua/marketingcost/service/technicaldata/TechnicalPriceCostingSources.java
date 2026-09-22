package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 正式核算按实际取到的补录价格检查原任务；不参与准备阶段的价格生成。 */
@Service
public class TechnicalPriceCostingSources {
  public record Selected(EffectiveTechnicalDataInput.PriceSource price, TechnicalDataCostingSources.Source approval) {}
  record Reference(String material, String priceType, Long recordId, String batchNo) {}

  private final JdbcTemplate jdbc;
  private final TechnicalDataPriceOwnership claims;
  private final TechnicalDataSharedModuleRepository modules;
  private final TechnicalDataCostingSources approvals;

  public TechnicalPriceCostingSources(JdbcTemplate jdbc, TechnicalDataPriceOwnership claims,
      TechnicalDataSharedModuleRepository modules, TechnicalDataCostingSources approvals) {
    this.jdbc = jdbc;
    this.claims = claims;
    this.modules = modules;
    this.approvals = approvals;
  }

  public List<Selected> select(QuoteBomReadContext context) {
    var references = new ArrayList<>(jdbc.query("""
        SELECT material_code,price_type,source_price_record_id,source_price_batch_no
        FROM lp_price_prepare_item
        WHERE oa_form_item_id=? AND period_month=? AND current_flag=1 AND source_price_batch_no LIKE 'TECH:%'
        """, (row, index) -> new Reference(row.getString(1), row.getString(2), row.getObject(3, Long.class), row.getString(4)),
        context.oaFormItemId(), context.accountingMonth()));
    var manufactured = jdbc.query("""
        SELECT c.child_material_no,c.raw_price_type,c.raw_source_price_record_id,c.raw_source_price_batch_no,
          c.scrap_code,c.scrap_price_type,c.scrap_source_price_record_id,c.scrap_source_price_batch_no,c.remark
        FROM lp_price_prepare_item p
        JOIN lp_make_part_price_calc_row anchor ON anchor.id=p.result_ref_id
        JOIN lp_make_part_price_calc_row c ON c.calc_batch_id=anchor.calc_batch_id
          AND c.parent_material_no=anchor.parent_material_no AND c.source_costing_row_id=anchor.source_costing_row_id
        WHERE p.oa_form_item_id=? AND p.period_month=? AND p.current_flag=1 AND p.result_ref_type='MAKE_PART_PRICE'
        """, (row, index) -> {
          var raw = new Reference(row.getString(1), row.getString(2), row.getObject(3, Long.class), row.getString(4));
          var scrap = new Reference(row.getString(5), row.getString(6), row.getObject(7, Long.class), row.getString(8));
          if (Objects.toString(row.getString(9), "").contains("技术审批版本=")
              && raw.batchNo() == null && scrap.batchNo() == null) {
            throw error(context, "制造件旧价格结果未记录补录来源，请重新生成本报价价格");
          }
          return List.of(raw, scrap);
        }, context.oaFormItemId(), context.accountingMonth());
    manufactured.forEach(pair -> pair.stream().filter(value -> value.batchNo() != null && value.batchNo().startsWith("TECH:"))
        .forEach(references::add));
    var selected = new LinkedHashMap<String, Selected>();
    for (var reference : references) {
      var value = require(context, reference);
      var previous = selected.putIfAbsent(reference.material(), value);
      if (previous != null && !previous.price().equals(value.price())) {
        throw error(context, reference.material() + "在本报价中取到了不同补录价格来源，请重新检查价格");
      }
    }
    return selected.values().stream().sorted(Comparator.comparing(value -> value.price().materialNo())).toList();
  }

  Selected require(QuoteBomReadContext context, Reference reference) {
    if (reference.recordId() == null || reference.batchNo() == null || !reference.batchNo().matches("TECH:[0-9]+")) {
      throw error(context, reference.material() + "的补录价格来源标识不完整");
    }
    long version = Long.parseLong(reference.batchNo().substring(5));
    var claim = claims.find(reference.material());
    if (claim == null || !Objects.equals(claim.versionId(), version)) {
      throw error(context, reference.material() + "的原补录价格已变化，请重新检查价格");
    }
    String kind = switch (Objects.toString(reference.priceType(), "")) {
      case "FIXED", "固定价" -> "FIXED";
      case "LINKED", "联动价" -> "LINKED";
      default -> throw error(context, reference.material() + "的补录价格类型不明确");
    };
    String table = "FIXED".equals(kind) ? "lp_price_fixed_item" : "lp_price_linked_item";
    Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + """
         WHERE id=? AND material_code=? AND source_kind='TECH_SUPPLEMENTAL'
          AND technical_version_id=? AND technical_publication_status='AVAILABLE'
          AND business_unit_type=? AND org_code=? AND unit=?
        """ + ("LINKED".equals(kind) ? " AND deleted=0" : ""), Integer.class,
        reference.recordId(), reference.material(), version, context.businessUnitType(), context.priceOrgCode(), claim.unit());
    if (!Integer.valueOf(1).equals(active)) throw error(context, reference.material() + "的实际补录价格已失效或适用组织、单位变化");
    var owner = modules.module(claim.productId(), "PRICE");
    if (owner == null) throw error(context, reference.material() + "找不到原价格办理模块");
    var approved = approvals.requireSource(context, owner);
    return new Selected(new EffectiveTechnicalDataInput.PriceSource(reference.material(), kind, reference.recordId(),
        approved.product().getId(), approved.version().getId(), version, approved.version().getContentFingerprint()), approved);
  }

  private EffectiveTechnicalDataException error(QuoteBomReadContext context, String message) {
    return new EffectiveTechnicalDataException("TECH_PRICE_SOURCE_NOT_READY", context.oaFormItemId(),
        context.accountingMonth(), List.of("PRICE"), message);
  }
}
