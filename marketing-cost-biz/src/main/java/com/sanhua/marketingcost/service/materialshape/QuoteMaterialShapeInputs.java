package com.sanhua.marketingcost.service.materialshape;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomShapeDecision;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Public source classification and per-product selection; never reads or writes a price amount. */
@Service
public class QuoteMaterialShapeInputs implements com.sanhua.marketingcost.service.QuotationMaterialShapeInputs {
  public record Source(String organization,String materialCode,String sourceType,String shape,long version) {}
  public record Choice(String organization,String materialCode,String selectedShape,long version) {}
  public record Catalog(List<Source> sources,List<Choice> choices) {
    public Catalog { sources=List.copyOf(sources);choices=List.copyOf(choices); }
    public List<Source> sources(String org,String code) {
      return sources.stream().filter(s -> org.equals(s.organization()) && code.equals(s.materialCode())).toList();
    }
    public Choice choice(String org,String code) {
      return choices.stream().filter(c -> org.equals(c.organization()) && code.equals(c.materialCode())).findFirst().orElse(null);
    }
    public Set<String> shapes(String org,String code) {
      var shapes=new LinkedHashSet<String>();
      sources(org,code).stream().map(Source::shape).filter(Objects::nonNull).forEach(shapes::add);
      return Set.copyOf(shapes);
    }
    public String priceScope(String org,String code,String shape) {
      var rows=sources(org,code);
      if(rows.isEmpty() || rows.stream().allMatch(s -> s.version()==0 || s.shape()==null)) return null;
      var types=rows.stream().filter(s -> shape.equals(s.shape())).map(Source::sourceType).toList();
      String revision=MonthlyPriceDocumentCodec.sha256(sources.stream().filter(s -> code.equals(s.materialCode()))
          .sorted(Comparator.comparing(Source::organization).thenComparing(Source::sourceType)).map(s -> s.organization()+":"+s.sourceType()+"="+s.shape()+":"+s.version())
          .collect(java.util.stream.Collectors.joining("|")));
      return MaterialShapePriceScope.of(shape,types,revision);
    }
    public String fingerprint() {
      return MonthlyPriceDocumentCodec.sha256(sources.toString()+choices.toString());
    }
  }
  private final JdbcTemplate jdbc;
  public QuoteMaterialShapeInputs(JdbcTemplate jdbc) { this.jdbc=jdbc; }

  /** Old schemas may still serve unchanged quotations; installation is verified before release. */
  public boolean installed() {
    return Objects.equals(4,jdbc.queryForObject("""
        SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
        AND table_name IN ('lp_material_price_source_row','lp_material_price_attribute','lp_quote_material_shape_selection','lp_quote_outsource_scrap_basis')
        """,Integer.class));
  }
  public Catalog read(String unit,String month,Long itemId) {
    return read(unit,month,itemId,null);
  }
  public Catalog read(String unit,String month,Long itemId,Collection<String> materialCodes) {
    if(!installed()) return new Catalog(List.of(),List.of());
    var codes=materialCodes==null ? List.<String>of() : materialCodes.stream().filter(Objects::nonNull).distinct().sorted().toList();
    if(materialCodes!=null && codes.isEmpty()) return new Catalog(List.of(),List.of());
    String restriction=materialCodes==null ? "" : " AND s.material_code IN ("+String.join(",",Collections.nCopies(codes.size(),"?"))+")";
    var parameters=new ArrayList<Object>();parameters.add(unit);parameters.add(month);parameters.addAll(codes);
    var sources=jdbc.query("""
        SELECT s.price_org_code,s.material_code,s.price_source_type,
          CASE WHEN s.auto_attribute IS NOT NULL THEN s.auto_attribute ELSE a.material_attribute END shape,
          COALESCE(a.row_version,0) row_version
        FROM lp_material_price_source_row s LEFT JOIN lp_material_price_attribute a
          ON a.business_unit_type=s.business_unit_type AND a.price_month=s.price_month
          AND a.price_org_code=s.price_org_code AND a.material_code=s.material_code AND a.price_source_type=s.price_source_type
        WHERE s.business_unit_type=? AND s.price_month=?
        """+restriction+" ORDER BY s.price_org_code,s.material_code,s.price_source_type",
        (r,n)->new Source(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5)),parameters.toArray());
    var choices=itemId==null ? List.<Choice>of() : jdbc.query("""
        SELECT price_org_code,material_code,selected_shape,row_version FROM lp_quote_material_shape_selection
        WHERE business_unit_type=? AND period_month=? AND oa_form_item_id=? ORDER BY price_org_code,material_code
        """,(r,n)->new Choice(r.getString(1),r.getString(2),r.getString(3),r.getLong(4)),unit,month,itemId);
    return new Catalog(sources,choices);
  }

  public Map<String,EffectiveBomShapeDecision> apply(String unit,String month,Long itemId,
      Map<String,EffectiveBomShapeDecision> original,Map<String,String> organizations) {
    var catalog=read(unit,month,itemId,original.keySet());
    if(catalog.choices().isEmpty()) return original;
    var result=new LinkedHashMap<>(original);
    original.forEach((code,decision)->{
      String org=organizations.get(code);
      if(org==null || decision.resolutionSource()==MaterialQuoteShapeSource.STRUCTURE_ROOT) return;
      var choice=catalog.choice(org,code);
      if(choice==null || choice.selectedShape()==null) return;
      if(catalog.sources(org,code).isEmpty() || !catalog.shapes(org,code).contains(choice.selectedShape())) {
        result.put(code,EffectiveBomShapeDecision.blocked(code,"料号"+code+"选用的形态已无对应公共价格来源，请重新选择或补齐来源属性"));
        return;
      }
      result.put(code,new EffectiveBomShapeDecision(code,decision.sourceMaterialShape(),
          QuoteMaterialShape.valueOf(choice.selectedShape()),MaterialQuoteShapeSource.QUOTATION_SELECTION,
          null,MonthlyPriceDocumentCodec.sha256(choice.toString()),null,null,null,null,null,null));
    });
    return Map.copyOf(result);
  }

  public Map<MonthlyQuotationPrices.Item,String> priceScopes(MonthlyMaterialPrices.Context context,
      Collection<MonthlyQuotationPrices.Item> items) {
    var catalog=read(context.businessUnitType(),context.month().toString(),context.oaFormItemId(),items.stream().map(MonthlyQuotationPrices.Item::materialCode).toList());
    if(catalog.sources().isEmpty()) return Map.of();
    var original=new HashMap<String,String>();
    for(var row:jdbc.queryForList("""
        SELECT n.price_org_code,n.material_code,n.effective_material_shape FROM lp_quote_effective_bom_node n
        JOIN lp_quote_costing_workspace w ON w.current_bom_build_batch_id=n.build_batch_id
        WHERE w.oa_form_item_id=? AND w.period_month=? AND w.business_unit_type=?
        """,context.oaFormItemId(),context.month().toString(),context.businessUnitType())) {
      original.put(row.get("price_org_code")+"|"+row.get("material_code"),Objects.toString(row.get("effective_material_shape"),""));
    }
    var result=new HashMap<MonthlyQuotationPrices.Item,String>();
    for(var item:items) {
      if(item.purpose()!=MonthlyQuotationPrices.Purpose.MATERIAL || item.conditions().materialShapeScope()!=null) continue;
      var choice=catalog.choice(item.organization(),item.materialCode());
      String shape=choice==null || choice.selectedShape()==null
          ? original.get(item.organization()+"|"+item.materialCode()) : choice.selectedShape();
      if(!Set.of("PURCHASE","OUTSOURCE").contains(Objects.toString(shape,""))) continue;
      String scope=catalog.priceScope(item.organization(),item.materialCode(),shape);
      if(scope!=null) result.put(item,scope);
    }
    return Map.copyOf(result);
  }

  @Override public String revision(com.sanhua.marketingcost.entity.OaFormItem item,String month) {
    var catalog=read(item.getBusinessUnitType(),month,item.getId());
    if(catalog.sources().isEmpty() && catalog.choices().isEmpty()) return null;
    var codes=new HashSet<>(jdbc.queryForList("""
        SELECT n.material_code FROM lp_quote_effective_bom_node n
        JOIN lp_quote_costing_workspace w ON w.current_bom_build_batch_id=n.build_batch_id
        WHERE w.oa_form_item_id=? AND w.period_month=? AND w.business_unit_type=?
        UNION SELECT material_code FROM lp_price_prepare_item
        WHERE oa_form_item_id=? AND period_month=? AND business_unit_type=? AND current_flag=1
        """,String.class,item.getId(),month,item.getBusinessUnitType(),item.getId(),month,item.getBusinessUnitType()));
    catalog.choices().forEach(c -> codes.add(c.materialCode()));
    var sources=catalog.sources().stream().filter(s -> codes.contains(s.materialCode()) && s.version()>0).toList();
    if(sources.isEmpty() && catalog.choices().isEmpty()) return null;
    return new Catalog(sources,catalog.choices()).fingerprint();
  }

  @Override public Map<String,java.math.BigDecimal> cmsExclusions(com.sanhua.marketingcost.dto.CostRunContext context) {
    if(context.getOaFormItemId()==null || !installed()) return Map.of();
    var result=new LinkedHashMap<String,java.math.BigDecimal>();
    jdbc.query("""
        SELECT n.material_code,n.qty_per_top FROM lp_quote_effective_bom_node n
        JOIN lp_quote_costing_workspace w ON w.current_bom_build_batch_id=n.build_batch_id
        WHERE w.oa_form_item_id=? AND w.period_month=? AND w.business_unit_type=?
          AND n.shape_resolution_source='QUOTATION_SELECTION'
          AND n.source_material_shape IN ('MANUFACTURE','制造件','自制件')
          AND n.effective_material_shape IN ('PURCHASE','OUTSOURCE')
        """,(org.springframework.jdbc.core.RowCallbackHandler)r -> result.merge(r.getString(1),r.getBigDecimal(2),java.math.BigDecimal::add),
        context.getOaFormItemId(),context.getPricingMonth(),context.getBusinessUnitType());
    return Map.copyOf(result);
  }
}
