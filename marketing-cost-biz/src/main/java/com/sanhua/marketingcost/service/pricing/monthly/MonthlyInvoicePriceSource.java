package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Latest successful batch per material, then highest applicable untaxed invoice amount. */
@Component
public final class MonthlyInvoicePriceSource {
  private final JdbcTemplate jdbc;
  private final MonthlyPriceDocumentCodec codec;
  public MonthlyInvoicePriceSource(JdbcTemplate jdbc,MonthlyPriceDocumentCodec codec){this.jdbc=jdbc;this.codec=codec;}

  public Candidates read(Context context,Set<MonthlyPriceRequest> requests) {
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();
    var groups=new TreeMap<String,List<MonthlyPriceRequest>>();
    for(var request:requests) {
      if(!request.family().businessUnitType().equals(context.businessUnitType()) || !request.family().month().equals(context.month()))
        throw new IllegalArgumentException("发票取价范围与报价不一致");
      groups.computeIfAbsent(request.priceOrgCode(),ignored->new ArrayList<>()).add(request);
    }
    for(var group:groups.entrySet()) {
      var codes=group.getValue().stream().map(r->r.family().materialCode()).distinct().toList();
      var byCode=new HashMap<String,List<Map<String,Object>>>();
      for(int start=0;start<codes.size();start+=256) {
        var batch=codes.subList(start,Math.min(start+256,codes.size()));
        var args=new ArrayList<Object>(List.of(context.businessUnitType(),group.getKey()));args.addAll(batch);
        var rows=jdbc.queryForList("""
            SELECT p.*,b.file_name,b.created_at imported_at,b.created_by imported_by
            FROM lp_invoice_price p JOIN lp_invoice_import_batch b ON b.id=p.batch_id
            WHERE p.business_unit_type=? AND p.org_code=? AND p.material_code IN (
            """+String.join(",",Collections.nCopies(batch.size(),"?"))+") AND p.batch_id=(SELECT MAX(v.batch_id) FROM lp_invoice_price v WHERE v.business_unit_type=p.business_unit_type AND v.org_code=p.org_code AND v.material_code=p.material_code) ORDER BY p.unit_price DESC,p.document_no,p.supplier_code",args.toArray());
        rows.forEach(row->byCode.computeIfAbsent(row.get("material_code").toString(),ignored->new ArrayList<>()).add(row));
      }
      for(var request:group.getValue()) {
        var family=request.family();
        var applicable=byCode.getOrDefault(family.materialCode(),List.of()).stream()
            .filter(row->family.unit().equals(row.get("pricing_unit")) && family.currency().equals(row.get("currency")))
            .filter(row->family.requestedSupplierCode()==null || family.requestedSupplierCode().equals(row.get("supplier_code")))
            .filter(row->family.taxBasis()==TaxBasis.EXCLUDED).toList();
        if(applicable.isEmpty()) {
          problems.put(request,new Problem(MonthlyAdoptionRetryPolicy.Failure.MISSING,"没有适用的发票价格"));continue;
        }
        var selected=new LinkedHashMap<>(applicable.getFirst());
        selected.put("supplierCode",selected.get("supplier_code"));selected.put("supplierName",selected.get("supplier_name"));
        var snapshot=Map.of("snapshotSchema",1,"sourceKind","INVOICE","source",Map.of("selected",selected,
            "candidates",applicable,"selectionRule","LATEST_IMPORT_THEN_HIGHEST"));
        var fact=PriceFact.normal((BigDecimal)selected.get("unit_price"),SourceEvidence.ofJson(codec.unwrap(codec.encode(snapshot))),Set.of());
        var coverage=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of(request.priceOrgCode()),QuantityInterval.all(),ConditionDomain.any()));
        values.put(request,new Candidate(new Selection(null,coverage,fact),null));
      }
    }
    return new Candidates(values,problems);
  }
}
