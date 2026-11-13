package com.sanhua.marketingcost.service.materialshape;

import com.sanhua.marketingcost.dto.quotebom.QuoteMaterialShapeScope;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomQueryException;
import com.sanhua.marketingcost.service.ingest.QuoteRequestQueryService;
import com.sanhua.marketingcost.service.pricing.MaterialPriceSourceCatalog;
import com.sanhua.marketingcost.service.pricing.MaterialPriceSourceService;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One decision per OA/month/organization/material; amounts remain in the existing costing chain. */
@Service
public class QuoteOrderMaterialShapes {
  public record Product(long itemId,String productCode,String productName,String originalShape) {}
  public record Row(String periodMonth,String organization,String materialCode,String materialName,String materialSpec,
      List<MaterialPriceSourceCatalog.Entry> sources,List<Product> products,List<String> allowedShapes,
      String selectedShape,long version,boolean legacySelection,String selectedSourceType) {}
  public record Problem(long itemId,String productCode,String message) {}
  public record View(long formId,String fingerprint,List<Row> rows,List<Problem> problems,boolean sourceSelectionSupported) {}
  public record Selection(String periodMonth,String organization,String materialCode,String selectedShape,Long expectedVersion,String selectedSourceType) {
    public Selection(String periodMonth,String organization,String materialCode,String selectedShape,Long expectedVersion) {
      this(periodMonth,organization,materialCode,selectedShape,expectedVersion,null);
    }
  }
  public record Change(String expectedFingerprint,List<Selection> selections) {}
  public record Saved(int changedMaterials,List<Long> affectedItemIds) {}
  private record Key(String month,String organization,String code) implements Comparable<Key> {
    @Override public int compareTo(Key other) {return toString().compareTo(other.toString());}
  }
  private record Scope(long itemId,String name,QuoteMaterialShapeScope bom) {}
  private record SourceScope(String month,String organization) {}
  private final QuoteRequestQueryService requests;
  private final QuoteEffectiveBomApplicationService boms;
  private final MaterialPriceSourceService sources;
  private final QuoteMaterialShapeInputs inputs;
  private final QuoteCostingWorkspaceService workspaces;
  private final QuoteCostRunVersionInvalidationService versions;
  private final JdbcTemplate jdbc;
  private final QuoteProductBomPreparationService preparations;

  public QuoteOrderMaterialShapes(QuoteRequestQueryService requests,QuoteEffectiveBomApplicationService boms,
      MaterialPriceSourceService sources,QuoteMaterialShapeInputs inputs,QuoteCostingWorkspaceService workspaces,
      QuoteCostRunVersionInvalidationService versions,JdbcTemplate jdbc,QuoteProductBomPreparationService preparations) {
    this.requests=requests;this.boms=boms;this.sources=sources;this.inputs=inputs;
    this.workspaces=workspaces;this.versions=versions;this.jdbc=jdbc;
    this.preparations=preparations;
  }

  /** Explicit preparation discovers sources only; never calculates or adopts a formal cost. */
  public View prepare(String oaNo,String actor) {return collect(oaNo,true,actor);}
  public View list(String oaNo) {return collect(oaNo,false,null);}

  private View collect(String oaNo,boolean discover,String actor) {
    var detail=requests.getRequestDetail(oaNo); // Existing quotation visibility and business-unit authorization.
    if(!inputs.orderInstalled()) throw new IllegalStateException("整单物料形态功能尚未安装，请先完成版本升级");
    String unit=BusinessUnitContext.getCurrentBusinessUnitType();
    boolean sourceSelectionSupported=inputs.sourceChoiceInstalled();
    var scopes=new ArrayList<Scope>();var problems=new ArrayList<Problem>();
    for(var item:detail.getItems()) {
      try {
        var bom=materialScope(oaNo,item.getId(),detail.getAccountingPeriodMonth(),discover);
        scopes.add(new Scope(item.getId(),item.getProductName(),bom));
      }
      catch(RuntimeException failure) {problems.add(new Problem(item.getId(),item.getMaterialNo(),failure.getMessage()));}
    }
    var materials=new TreeMap<Key,QuoteMaterialShapeScope.Material>();
    var products=new TreeMap<Key,LinkedHashSet<Product>>();
    for(var scope:scopes) for(var material:scope.bom().materials()) {
      var key=new Key(scope.bom().periodMonth(),material.organization(),material.materialCode());
      materials.putIfAbsent(key,material);
      products.computeIfAbsent(key,k -> new LinkedHashSet<>()).add(new Product(scope.itemId(),scope.bom().productCode(),scope.name(),normalize(material.sourceShape())));
    }
    if(discover) {
      var groups=new LinkedHashMap<SourceScope,List<Key>>();
      for(var key:materials.keySet()) groups.computeIfAbsent(new SourceScope(key.month(),key.organization()),ignored -> new ArrayList<>()).add(key);
      for(var group:groups.entrySet()) {
        var manufactured=group.getValue().stream().filter(key -> products.get(key).stream()
            .anyMatch(p -> "MANUFACTURE".equals(p.originalShape()))).map(Key::code).collect(java.util.stream.Collectors.toSet());
        sources.discoverForQuotation(unit,group.getKey().month(),group.getKey().organization(),
            group.getValue().stream().map(Key::code).toList(),manufactured,actor);
      }
    }
    var rows=new ArrayList<Row>();
    for(var entry:materials.entrySet()) {
      var key=entry.getKey();var material=entry.getValue();var consumers=List.copyOf(products.get(key));
      if(consumers.stream().allMatch(p -> "PURCHASE".equals(p.originalShape()))) continue;
      var found=sources.quotationSources(unit,key.month(),key.organization(),key.code());
      if(found.stream().map(MaterialPriceSourceCatalog.Entry::sourceType).distinct().count()<2) continue;
      var allowed=new ArrayList<String>();
      if(consumers.stream().allMatch(p -> "MANUFACTURE".equals(p.originalShape()))) allowed.add("MANUFACTURE");
      if(consumers.stream().allMatch(p -> Set.of("MANUFACTURE","PURCHASE","OUTSOURCE").contains(p.originalShape()))) {
        allowed.add("PURCHASE");
      }
      if(consumers.stream().allMatch(p -> Set.of("MANUFACTURE","OUTSOURCE").contains(p.originalShape()))) allowed.add("OUTSOURCE");
      var choices=jdbc.queryForList("SELECT selected_shape,row_version,"+(sourceSelectionSupported?"selected_source_type":"NULL selected_source_type")+" FROM lp_quote_order_material_shape WHERE business_unit_type=? AND oa_form_id=? AND period_month=? AND price_org_code=? AND material_code=?",
          unit,detail.getId(),key.month(),key.organization(),key.code());
      String selected=choices.isEmpty()?null:Objects.toString(choices.getFirst().get("selected_shape"),null);
      String selectedSource=choices.isEmpty()?null:Objects.toString(choices.getFirst().get("selected_source_type"),null);
      long version=choices.isEmpty()?0:((Number)choices.getFirst().get("row_version")).longValue();
      boolean legacy=version==0 && Boolean.TRUE.equals(jdbc.queryForObject("""
          SELECT EXISTS(SELECT 1 FROM lp_quote_material_shape_selection s JOIN oa_form_item i ON i.id=s.oa_form_item_id
            WHERE i.oa_form_id=? AND s.business_unit_type=? AND s.period_month=? AND s.price_org_code=? AND s.material_code=? AND s.selected_shape IS NOT NULL)
          """,Boolean.class,detail.getId(),unit,key.month(),key.organization(),key.code()));
      rows.add(new Row(key.month(),key.organization(),key.code(),material.materialName(),material.materialSpec(),found,consumers,List.copyOf(allowed),selected,version,legacy,selectedSource));
    }
    String fingerprint=MonthlyPriceDocumentCodec.sha256(detail.getId()+"|"+scopes+"|"+rows+"|"+problems);
    return new View(detail.getId(),fingerprint,List.copyOf(rows),List.copyOf(problems),sourceSelectionSupported);
  }

  private QuoteMaterialShapeScope materialScope(String oaNo,long itemId,String month,boolean discover) {
    try {
      // The scope query validates current BOM/alternative inputs and reuses the frozen monthly BOM.
      // Opening this tab must not repeat the full product preparation when that scope is ready.
      return boms.materialShapeScope(oaNo,itemId);
    } catch(QuoteEffectiveBomQueryException failure) {
      if(!discover || !Set.of("EFFECTIVE_BOM_NOT_FOUND","MONTHLY_BOM_NOT_READY").contains(failure.getCode())) throw failure;
    }
    preparations.prepareByOaFormItem(itemId,com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingDate(),month);
    return boms.materialShapeScope(oaNo,itemId);
  }

  @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  @com.sanhua.marketingcost.service.reprice.QuotationWrite(holdUntilCompletion=true)
  public Saved change(String oaNo,Change change,String actor) {
    if(change==null || change.selections()==null || change.selections().isEmpty() || change.selections().size()>1000)
      throw new IllegalArgumentException("请选择1至1000个多源物料");
    var detail=requests.getRequestDetail(oaNo);
    if(detail.getOaWorkflow()!=null && !detail.getOaWorkflow().canCost()) throw new IllegalArgumentException("当前OA流程状态不允许调整核算形态");
    String unit=BusinessUnitContext.getCurrentBusinessUnitType();
    jdbc.queryForObject("SELECT id FROM oa_form WHERE id=? AND business_unit_type=? FOR UPDATE",Long.class,detail.getId(),unit);
    var current=collect(oaNo,false,null);
    if(!Objects.equals(current.fingerprint(),change.expectedFingerprint())) throw new IllegalArgumentException("本单物料、来源或形态已变化，请刷新后重新选择");
    var byKey=new HashMap<Key,Row>();current.rows().forEach(r -> byKey.put(new Key(r.periodMonth(),r.organization(),r.materialCode()),r));
    var selected=new TreeMap<Key,Selection>();
    for(var selection:change.selections()) {
      if(selection==null || selection.expectedVersion()==null || selection.expectedVersion()<0)
        throw new IllegalArgumentException("缺少物料选择版本");
      var key=new Key(selection.periodMonth(),selection.organization(),selection.materialCode());var row=byKey.get(key);
      if(row==null) throw new IllegalArgumentException("料号"+selection.materialCode()+"不在本单可调整形态的多源物料中；原采购件不能调整形态");
      String chosen=selection.selectedSourceType()==null || selection.selectedSourceType().isBlank()?null:selection.selectedSourceType().trim();
      if(chosen==null && selection.selectedShape()!=null && Objects.equals(row.selectedShape(),selection.selectedShape())) chosen=row.selectedSourceType();
      var normalized=new Selection(selection.periodMonth(),selection.organization(),selection.materialCode(),selection.selectedShape(),selection.expectedVersion(),chosen);
      if(selected.putIfAbsent(key,normalized)!=null) throw new IllegalArgumentException("批量选择包含重复料号");
      if(row.version()!=selection.expectedVersion()) throw new IllegalArgumentException("形态已被其他人修改，请刷新");
      if(chosen!=null && !current.sourceSelectionSupported()) throw new IllegalArgumentException("当前版本尚未支持本单价格源选择，请先完成升级");
      if(chosen!=null && selection.selectedShape()==null) throw new IllegalArgumentException("选用价格源时请同时选择本单采用形态");
      if(selection.selectedShape()!=null) {
        if(!row.allowedShapes().contains(selection.selectedShape())) throw new IllegalArgumentException("料号"+key.code()+"不允许所选形态；原采购件不能改成委外加工件或自制件，原委外加工件不能改成自制件");
        var catalog=new QuoteMaterialShapeInputs.Catalog(row.sources().stream().map(s -> new QuoteMaterialShapeInputs.Source(s.organization(),s.materialCode(),s.sourceType(),s.materialAttribute(),s.version())).toList(),
            chosen==null?List.of():List.of(new QuoteMaterialShapeInputs.Choice(key.organization(),key.code(),selection.selectedShape(),row.version(),chosen)));
        String problem=catalog.selectionProblem(key.organization(),key.code(),selection.selectedShape());
        if(problem!=null) throw new IllegalArgumentException(problem);
      }
    }
    var affected=new TreeMap<Long,Row>();int changed=0;
    for(var entry:selected.entrySet()) {
      var key=entry.getKey();var selection=entry.getValue();var row=byKey.get(key);
      if(row.version()>0 && Objects.equals(row.selectedShape(),selection.selectedShape()) && Objects.equals(row.selectedSourceType(),selection.selectedSourceType())) continue;
      var parameters=new ArrayList<Object>();
      if(row.version()==0) {
        Collections.addAll(parameters,unit,detail.getId(),key.month(),key.organization(),key.code(),selection.selectedShape(),actor);
        if(current.sourceSelectionSupported()) parameters.add(selection.selectedSourceType());
        jdbc.update("INSERT INTO lp_quote_order_material_shape(business_unit_type,oa_form_id,period_month,price_org_code,material_code,selected_shape,row_version,updated_by"
            +(current.sourceSelectionSupported()?",selected_source_type":"")+") VALUES(?,?,?,?,?,?,1,?"
            +(current.sourceSelectionSupported()?",?":"")+")",parameters.toArray());
      } else {
        parameters.add(selection.selectedShape());if(current.sourceSelectionSupported()) parameters.add(selection.selectedSourceType());
        Collections.addAll(parameters,actor,unit,detail.getId(),key.month(),key.organization(),key.code(),selection.expectedVersion());
        if(jdbc.update("UPDATE lp_quote_order_material_shape SET selected_shape=?,"+(current.sourceSelectionSupported()?"selected_source_type=?,":"")+"""
          row_version=row_version+1,updated_by=?,updated_at=CURRENT_TIMESTAMP(6)
          WHERE business_unit_type=? AND oa_form_id=? AND period_month=? AND price_org_code=? AND material_code=? AND row_version=?
          """,parameters.toArray())!=1) throw new IllegalArgumentException("形态已被其他人修改，请刷新");
      }
      changed++;row.products().forEach(p -> affected.put(p.itemId(),row));
    }
    for(var entry:affected.entrySet()) {
      long itemId=entry.getKey();var row=entry.getValue();
      String product=row.products().stream().filter(p -> p.itemId()==itemId).findFirst().orElseThrow().productCode();
      workspaces.lockOrCreate(oaNo,itemId,product,row.periodMonth(),unit);
      workspaces.markItemStale(itemId,row.periodMonth(),"MATERIAL_SHAPE_CHANGED");
      versions.invalidateProductAfterBomChange(oaNo,itemId,product,row.periodMonth());
    }
    return new Saved(changed,List.copyOf(affected.keySet()));
  }

  private static String normalize(String shape) {
    return switch(Objects.toString(shape,"").trim()) {
      case "制造件","自制件","MANUFACTURE" -> "MANUFACTURE";
      case "采购件","PURCHASE" -> "PURCHASE";
      case "委外加工件","OUTSOURCE" -> "OUTSOURCE";
      default -> "UNKNOWN";
    };
  }
}
