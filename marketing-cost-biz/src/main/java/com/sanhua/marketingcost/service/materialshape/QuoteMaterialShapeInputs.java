package com.sanhua.marketingcost.service.materialshape;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomShapeDecision;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Public source classification and order-wide choices, with legacy item choices as fallback. */
@Service
public class QuoteMaterialShapeInputs implements com.sanhua.marketingcost.service.QuotationMaterialShapeInputs {
  public record Source(String organization,String materialCode,String sourceType,String shape,long version) {}
  public record Choice(String organization,String materialCode,String selectedShape,long version,String selectedSourceType) {
    public Choice(String organization,String materialCode,String selectedShape,long version) {this(organization,materialCode,selectedShape,version,null);}
    @Override public String toString() {
      return "Choice[organization="+organization+", materialCode="+materialCode+", selectedShape="+selectedShape+", version="+version
          +(selectedSourceType==null?"":", selectedSourceType="+selectedSourceType)+"]";
    }
  }
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
    public List<Source> priceSources(String org,String code) {
      var local=sources(org,code).stream().filter(s -> !"MAKE".equals(s.sourceType())).toList();
      return local.isEmpty() ? sources("210".equals(org)?"220":"210",code).stream()
          .filter(s -> !"MAKE".equals(s.sourceType())).toList() : local;
    }
    public boolean multiple(String org,String code) { return sources(org,code).stream().map(Source::sourceType).distinct().count()>1; }
    public String selectionProblem(String org,String code,String shape) {
      if(!multiple(org,code)) return "料号"+code+"未进入公共多源价格表，不能调整形态";
      var choice=choice(org,code);
      if(choice!=null && choice.selectedSourceType()!=null) {
        String selected=choice.selectedSourceType();
        if("MANUFACTURE".equals(shape)) return "MAKE".equals(selected) && sources(org,code).stream().anyMatch(s->"MAKE".equals(s.sourceType()))
            ? null : "料号"+code+"的自制形态只能选用自制计算来源";
        if("MAKE".equals(selected)) return "料号"+code+"的采购或委外形态不能选用自制计算来源";
        return priceSources(org,code).stream().anyMatch(s->selected.equals(s.sourceType()))
            ? null : "料号"+code+"本单选用的"+selected+"价格来源已不可用，请重新选择";
      }
      if("MANUFACTURE".equals(shape)) return null;
      var rows=priceSources(org,code);
      if(rows.isEmpty()) return "料号"+code+"按原取价条件未找到价格来源，请补齐价格";
      if(rows.stream().anyMatch(s -> s.shape()==null || s.shape().isBlank()))
        return "料号"+code+"存在多个价格来源，请先由报价员在公共多源价格表填写各来源的形态属性后再报价";
      if(rows.stream().noneMatch(s -> shape.equals(s.shape())))
        return "料号"+code+"的公共多源价格表没有本次选用形态对应的来源，请核对形态属性";
      return null;
    }
    public String priceScope(String org,String code,String shape) {
      var rows=priceSources(org,code);
      if(rows.isEmpty()) return null;
      var choice=choice(org,code);String selected=choice==null?null:choice.selectedSourceType();
      var types=selectionProblem(org,code,shape)!=null ? List.<String>of() : selected!=null ? List.of(selected) : rows.stream()
          .filter(s -> shape.equals(s.shape())).map(Source::sourceType).toList();
      String revision=MonthlyPriceDocumentCodec.sha256(sources.stream().filter(s -> code.equals(s.materialCode()))
          .sorted(Comparator.comparing(Source::organization).thenComparing(Source::sourceType)).map(s -> s.organization()+":"+s.sourceType()+"="+s.shape()+":"+s.version())
          .collect(java.util.stream.Collectors.joining("|")));
      return selected==null || types.isEmpty()?MaterialShapePriceScope.of(shape,types,revision)
          // Choice versions invalidate costing inputs, but must not replace this source's adopted monthly price.
          : MaterialShapePriceScope.pinned(shape,selected,MonthlyPriceDocumentCodec.sha256(
              String.join("|","ORDER_PRICE_SOURCE_V1",org,code,shape,selected)));
    }
    public String fingerprint() {
      return MonthlyPriceDocumentCodec.sha256(sources.toString()+choices.toString());
    }
  }
  private final JdbcTemplate jdbc;
  @org.springframework.beans.factory.annotation.Autowired
  private com.sanhua.marketingcost.service.pricing.MaterialPriceSourceService publicSources;
  public QuoteMaterialShapeInputs(JdbcTemplate jdbc) { this.jdbc=jdbc; }

  private record ReadKey(String unit,String month,Long itemId) {}
  private static final class ReadSnapshot {
    final Catalog base;
    final List<Choice> legacy, order;
    Map<String,String> original;
    ReadSnapshot(Catalog base,List<Choice> legacy,List<Choice> order) {
      this.base=base;this.legacy=legacy;this.order=order;
    }
    Catalog project(Collection<String> materialCodes) {
      if(materialCodes==null) return base;
      var codes=new HashSet<>(materialCodes);
      var merged=new TreeMap<String,Choice>();
      legacy.forEach(c -> merged.put(c.organization()+"|"+c.materialCode(),c));
      order.stream().filter(c -> codes.contains(c.materialCode()))
          .forEach(c -> merged.put(c.organization()+"|"+c.materialCode(),c));
      return new Catalog(base.sources().stream().filter(s -> codes.contains(s.materialCode())).toList(),List.copyOf(merged.values()));
    }
  }
  private static final class ReadTransaction implements TransactionSynchronization {
    final QuoteMaterialShapeInputs owner;
    final Map<ReadKey,ReadSnapshot> snapshots=new LinkedHashMap<>();
    Boolean installed, orderInstalled, sourceChoiceInstalled;
    ReadTransaction(QuoteMaterialShapeInputs owner) {this.owner=owner;}
    @Override public void afterCompletion(int status) {snapshots.clear();installed=null;orderInstalled=null;sourceChoiceInstalled=null;}
  }
  /** Mutable classifications are reused only in one read-only transaction, never across refreshes or saves. */
  private ReadTransaction readonlyReads() {
    if(!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !TransactionSynchronizationManager.isSynchronizationActive()) return null;
    for(var sync:TransactionSynchronizationManager.getSynchronizations())
      if(sync instanceof ReadTransaction reads && reads.owner==this) return reads;
    var reads=new ReadTransaction(this);TransactionSynchronizationManager.registerSynchronization(reads);return reads;
  }
  private ReadSnapshot readonlySnapshot(String unit,String month,Long itemId) {
    var reads=readonlyReads();if(reads==null) return null;
    var key=new ReadKey(unit,month,itemId);
    if(reads.snapshots.containsKey(key)) return reads.snapshots.get(key);
    if(reads.snapshots.size()>=64) return null;
    var base=readUncached(unit,month,itemId,null);
    var legacy=itemId==null || !installed()?List.<Choice>of():jdbc.query("""
        SELECT price_org_code,material_code,selected_shape,row_version FROM lp_quote_material_shape_selection
        WHERE business_unit_type=? AND period_month=? AND oa_form_item_id=? ORDER BY price_org_code,material_code
        """,(r,n)->new Choice(r.getString(1),r.getString(2),r.getString(3),r.getLong(4)),unit,month,itemId);
    var order=itemId==null || !orderInstalled()?List.<Choice>of():jdbc.query("""
        SELECT s.price_org_code,s.material_code,s.selected_shape,s.row_version,
        """+sourceChoiceColumn()+"""
         FROM lp_quote_order_material_shape s
        JOIN oa_form_item i ON i.oa_form_id=s.oa_form_id AND i.business_unit_type=s.business_unit_type
        WHERE i.id=? AND s.business_unit_type=? AND s.period_month=?
        """,(r,n)->new Choice(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5)),itemId,unit,month);
    var snapshot=new ReadSnapshot(base,legacy,order);reads.snapshots.put(key,snapshot);return snapshot;
  }

  public void checkSelection(String unit,String month,String organization,String code,String shape,String actor) {
    if(shape==null) return;
    var found=new ArrayList<>(publicSources.discoverForQuotation(unit,month,organization,code,actor));
    if(found.stream().noneMatch(s -> !"MAKE".equals(s.sourceType())))
      found.addAll(publicSources.discoverForQuotation(unit,month,"210".equals(organization)?"220":"210",code,actor));
    var catalog=new Catalog(found.stream().map(s -> new Source(s.organization(),s.materialCode(),s.sourceType(),
        s.materialAttribute(),s.version())).toList(),List.of());
    String problem=catalog.selectionProblem(organization,code,shape);
    if(problem!=null) throw new IllegalArgumentException(problem);
  }

  /** Called only by write-side BOM preparation, never by a GET preview. */
  public void discoverSelected(String unit,String month,Long itemId) {
    if(!installed()) return;
    for(var row:read(unit,month,itemId).choices()) {
      if(!Set.of("PURCHASE","OUTSOURCE").contains(Objects.toString(row.selectedShape(),""))) continue;
      String org=row.organization(),code=row.materialCode();
      var found=publicSources.discoverForQuotation(unit,month,org,code,"QUOTE_DISCOVERY");
      if(found.stream().noneMatch(s -> !"MAKE".equals(s.sourceType())))
        publicSources.discoverForQuotation(unit,month,"210".equals(org)?"220":"210",code,"QUOTE_DISCOVERY");
    }
  }

  /** Old schemas may still serve unchanged quotations; installation is verified before release. */
  public boolean installed() {
    var reads=readonlyReads();if(reads!=null && reads.installed!=null) return reads.installed;
    boolean installed=Objects.equals(4,jdbc.queryForObject("""
        SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
        AND table_name IN ('lp_material_price_source_row','lp_material_price_attribute','lp_quote_material_shape_selection','lp_quote_outsource_scrap_basis')
        """,Integer.class));
    if(reads!=null) reads.installed=installed;
    return installed;
  }
  public boolean orderInstalled() {
    var reads=readonlyReads();if(reads!=null && reads.orderInstalled!=null) return reads.orderInstalled;
    boolean installed=Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)=1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='lp_quote_order_material_shape'",Boolean.class));
    if(reads!=null) reads.orderInstalled=installed;
    return installed;
  }
  public boolean sourceChoiceInstalled() {
    var reads=readonlyReads();if(reads!=null && reads.sourceChoiceInstalled!=null) return reads.sourceChoiceInstalled;
    boolean installed=Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT COUNT(*)=1 FROM information_schema.columns WHERE table_schema=DATABASE()
          AND table_name='lp_quote_order_material_shape' AND column_name='selected_source_type'
        """,Boolean.class));
    if(reads!=null) reads.sourceChoiceInstalled=installed;
    return installed;
  }
  private String sourceChoiceColumn() {return sourceChoiceInstalled()?"s.selected_source_type":"NULL";}
  public Catalog read(String unit,String month,Long itemId) {
    return read(unit,month,itemId,null);
  }
  public Catalog read(String unit,String month,Long itemId,Collection<String> materialCodes) {
    if(materialCodes!=null && materialCodes.isEmpty()) return new Catalog(List.of(),List.of());
    var snapshot=readonlySnapshot(unit,month,itemId);
    return snapshot==null?readUncached(unit,month,itemId,materialCodes):snapshot.project(materialCodes);
  }
  private Catalog readUncached(String unit,String month,Long itemId,Collection<String> materialCodes) {
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
    List<Choice> choices=itemId==null ? List.of() : jdbc.query("""
        SELECT price_org_code,material_code,selected_shape,row_version FROM lp_quote_material_shape_selection
        WHERE business_unit_type=? AND period_month=? AND oa_form_item_id=? ORDER BY price_org_code,material_code
        """,(r,n)->new Choice(r.getString(1),r.getString(2),r.getString(3),r.getLong(4)),unit,month,itemId);
    if(itemId!=null && orderInstalled()) {
      var relevant=new HashSet<>(codes);
      if(materialCodes==null) {
        relevant.addAll(jdbc.queryForList("""
            SELECT n.material_code FROM lp_quote_effective_bom_node n
            JOIN lp_quote_costing_workspace w ON w.current_bom_build_batch_id=n.build_batch_id
            WHERE w.oa_form_item_id=? AND w.period_month=? AND w.business_unit_type=?
            UNION SELECT material_code FROM lp_price_prepare_item
            WHERE oa_form_item_id=? AND period_month=? AND business_unit_type=? AND current_flag=1
            """,String.class,itemId,month,unit,itemId,month,unit));
        choices.forEach(c -> relevant.add(c.materialCode()));
      }
      var merged=new TreeMap<String,Choice>();
      choices.forEach(c -> merged.put(c.organization()+"|"+c.materialCode(),c));
      jdbc.query("""
          SELECT s.price_org_code,s.material_code,s.selected_shape,s.row_version,
          """+sourceChoiceColumn()+"""
           FROM lp_quote_order_material_shape s
          JOIN oa_form_item i ON i.oa_form_id=s.oa_form_id AND i.business_unit_type=s.business_unit_type
          WHERE i.id=? AND s.business_unit_type=? AND s.period_month=?
          """,(org.springframework.jdbc.core.RowCallbackHandler)r -> {
        var choice=new Choice(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5));
        if(relevant.contains(choice.materialCode())) merged.put(choice.organization()+"|"+choice.materialCode(),choice);
      },itemId,unit,month);
      choices=List.copyOf(merged.values());
    }
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
      boolean nativePurchase=Set.of("PURCHASE","采购件","采购").contains(Objects.toString(decision.sourceMaterialShape(),"").trim());
      String problem=nativePurchase && !"PURCHASE".equals(choice.selectedShape())
          ? "料号"+code+"的原始采购件不能改成委外加工件或制造件"
          : "MANUFACTURE".equals(choice.selectedShape())
          && !Set.of("MANUFACTURE","制造件","自制件").contains(Objects.toString(decision.sourceMaterialShape(),""))
          ? "料号"+code+"的原始采购件或委外加工件不能改成制造件"
          : catalog.selectionProblem(org,code,choice.selectedShape());
      if(problem!=null) {
        result.put(code,EffectiveBomShapeDecision.blocked(code,problem));
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
    items=items.stream().filter(item -> item.purpose()==MonthlyQuotationPrices.Purpose.MATERIAL
        && item.conditions().materialShapeScope()==null).toList();
    if(items.isEmpty()) return Map.of();
    var catalog=read(context.businessUnitType(),context.month().toString(),context.oaFormItemId(),items.stream().map(MonthlyQuotationPrices.Item::materialCode).toList());
    if(catalog.sources().isEmpty()) return Map.of();
    var snapshot=readonlySnapshot(context.businessUnitType(),context.month().toString(),context.oaFormItemId());
    Map<String,String> original=snapshot==null?null:snapshot.original;
    if(original==null) {
      var loaded=new HashMap<String,String>();
      for(var row:jdbc.queryForList("""
        SELECT n.price_org_code,n.material_code,n.effective_material_shape FROM lp_quote_effective_bom_node n
        JOIN lp_quote_costing_workspace w ON w.current_bom_build_batch_id=n.build_batch_id
        WHERE w.oa_form_item_id=? AND w.period_month=? AND w.business_unit_type=?
        """,context.oaFormItemId(),context.month().toString(),context.businessUnitType())) {
        loaded.put(row.get("price_org_code")+"|"+row.get("material_code"),Objects.toString(row.get("effective_material_shape"),""));
      }
      original=Map.copyOf(loaded);if(snapshot!=null) snapshot.original=original;
    }
    var result=new HashMap<MonthlyQuotationPrices.Item,String>();
    for(var item:items) {
      if(item.purpose()!=MonthlyQuotationPrices.Purpose.MATERIAL || item.conditions().materialShapeScope()!=null) continue;
      var choice=catalog.choice(item.organization(),item.materialCode());
      String shape=choice==null || choice.selectedShape()==null
          ? original.get(item.organization()+"|"+item.materialCode()) : choice.selectedShape();
      if(!Set.of("PURCHASE","OUTSOURCE").contains(Objects.toString(shape,""))) continue;
      if(!catalog.multiple(item.organization(),item.materialCode())) continue;
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
    var sources=catalog.sources().stream().filter(s -> codes.contains(s.materialCode())
        && (s.version()>0 || catalog.choices().stream().anyMatch(c -> c.materialCode().equals(s.materialCode()) && c.selectedShape()!=null))).toList();
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
