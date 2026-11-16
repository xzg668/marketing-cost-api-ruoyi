package com.sanhua.marketingcost.service.pricing;

import com.alibaba.excel.EasyExcel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.MakePartPriceCalcQueryRequest;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.costing.FinanceCostingMessages;
import java.io.OutputStream;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only price variants over existing generation rows. No price, history or schema writes. */
@Service
@Transactional(readOnly = true)
public class MakePartPriceGroups {
  public record Group(long id, MakePartPriceCalcRow row, String organization, String latestGeneratedAt,
      int detailCount, long calculationCount, long quoteCount, String status) {}
  public record Page(long total, List<Group> records, Map<String,Long> statusSummary) {}
  public record Quote(String oaNo, long calculationCount, String latestGeneratedAt, long latestRowId) {}
  public record Details(Group group, List<MakePartPriceCalcRow> materials, long quoteTotal,
      List<Quote> quotations) {}
  private final NamedParameterJdbcTemplate jdbc;
  private final BeanPropertyRowMapper<MakePartPriceCalcRow> mapper = new BeanPropertyRowMapper<>(MakePartPriceCalcRow.class);
  public MakePartPriceGroups(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

  // The hint applies to this statement only; never change pooled-connection session settings.
  private static final String SELECT = "SELECT /*+ SET_VAR(group_concat_max_len=16777216) */ ";
  private static final String GROUP_COLUMNS = "g.representative_id group_id,g.organization group_org,"
      +"g.latest_generated_at group_generated,g.detail_count group_details,g.calculation_count group_calculations,"
      +"g.quote_count group_quotes,g.group_status,r.id,r.calc_batch_id,r.oa_no,r.business_unit_type,r.pricing_month,r.price_scenario_type,"
      +"r.parent_material_no,r.parent_material_name,r.drawing_no,r.item_process_type,r.child_material_no,r.child_material_name,"
      +"r.child_material_spec,r.stock_unit,r.qty_per_parent,r.gross_weight_g,r.net_weight_g,r.raw_price_type,r.raw_unit_price,"
      +"r.scrap_code,r.scrap_name,r.scrap_price_type,r.scrap_unit_price,r.no_scrap_confirmed,r.no_scrap_confirmation_id,"
      +"r.outsource_fee,r.cost_price,r.parent_total_cost_price,r.price_complete,r.status,r.remark,r.created_at ";
  private static final String GROUP_SELECT = GROUP_COLUMNS+"FROM price_groups g JOIN lp_make_part_price_calc_row r ON r.id=g.representative_id ";
  private static final String FILTER = "g.eligible=1 AND (:onlyError=0 OR g.group_status<>'OK') AND (:status IS NULL OR g.group_status=:status) ";

  public Page page(MakePartPriceCalcQueryRequest request) {
    // Materialise the fingerprint groups once for totals, status counts and the page.
    // Return scalar summary fields only; full source snapshots remain on the original detail endpoint.
    String sql=cte()+",status_counts AS (SELECT group_status,COUNT(*) n FROM price_groups WHERE eligible=1 GROUP BY group_status),"
        +"page_totals AS (SELECT COUNT(*) total FROM price_groups g WHERE "+FILTER+"),"
        +"paged_groups AS (SELECT * FROM price_groups g WHERE "+FILTER
        +"ORDER BY latest_generated_at DESC,representative_id DESC LIMIT :limit OFFSET :offset) "
        +SELECT+"t.total,(SELECT JSON_OBJECTAGG(group_status,n) FROM status_counts) summary,"+GROUP_COLUMNS
        +"FROM page_totals t LEFT JOIN paged_groups g ON 1=1 LEFT JOIN lp_make_part_price_calc_row r ON r.id=g.representative_id "
        +"ORDER BY g.latest_generated_at DESC,g.representative_id DESC";
    return jdbc.query(sql,parameters(request),rs->{
      var rows=new ArrayList<Group>();var summary=new LinkedHashMap<String,Long>();long total=0;
      while(rs.next()) {
        total=rs.getLong("total");
        if(summary.isEmpty()&&rs.getString("summary")!=null)try {
          new ObjectMapper().readTree(rs.getString("summary")).fields().forEachRemaining(e->summary.put(e.getKey(),e.getValue().asLong()));
        } catch(java.io.IOException error) {throw new IllegalStateException("制造件价格统计读取失败",error);}
        if(rs.getObject("group_id")!=null)rows.add(group(rs,rows.size()));
      }
      return new Page(total,rows,summary);
    });
  }

  public Details details(long id, int page, int size) {
    var p=parameters(null);p.put("id",id);p.put("limit",Math.max(1,Math.min(100,size)));
    p.put("offset",(long)Math.max(0,page-1)*(int)p.get("limit"));
    String chosen="g.variant IN (SELECT variant FROM variants WHERE representative_id=:id)";
    var groups=jdbc.query(cte()+SELECT+GROUP_SELECT+"WHERE "+chosen,p,this::group);
    if(groups.size()!=1)throw new AccessDeniedException("制造件价格不存在或不属于当前业务单元");
    var g=groups.getFirst();p.put("representative",g.id());
    var materials=jdbc.query(cte()+SELECT+"r.* FROM price_lines l JOIN lp_make_part_price_calc_row r ON r.id=l.id "
        +"WHERE l.occurrence_key=(SELECT occurrence_key FROM price_lines WHERE id=:representative) ORDER BY r.id",p,mapper);
    materials.forEach(r->r.setDisplayReason(FinanceCostingMessages.manufacturingGap(r)));
    var quotes=jdbc.query(cte()+SELECT+"oa_no,COUNT(*) n,MAX(latest_generated_at) latest,MAX(representative_id) row_id FROM variants "
        +"WHERE variant=(SELECT variant FROM variants WHERE representative_id=:id) AND NULLIF(oa_no,'') IS NOT NULL "
        +"GROUP BY oa_no ORDER BY latest DESC,oa_no LIMIT :limit OFFSET :offset",p,
        (rs,n)->new Quote(rs.getString("oa_no"),rs.getLong("n"),rs.getString("latest"),rs.getLong("row_id")));
    return new Details(g,materials,g.quoteCount(),quotes);
  }

  private Group group(ResultSet rs,int n) throws SQLException {
    var row=mapper.mapRow(rs,n);row.setDisplayReason(FinanceCostingMessages.manufacturingGap(row));
    if (!Objects.equals(row.getStatus(),rs.getString("group_status")))
      row.setDisplayReason("部分原料／废料明细异常，请展开查看");
    String org=rs.getString("group_org");
    return new Group(rs.getLong("group_id"),row,org.startsWith("UNKNOWN:")?"":org,
        rs.getString("group_generated"),rs.getInt("group_details"),rs.getLong("group_calculations"),
        rs.getLong("group_quotes"),rs.getString("group_status"));
  }

  private Map<String,Object> parameters(MakePartPriceCalcQueryRequest request) {
    String unit=BusinessUnitContext.getCurrentBusinessUnitType();
    if(!Set.of("COMMERCIAL","HOUSEHOLD").contains(unit==null?"":unit))throw new AccessDeniedException("请选择业务单元");
    var q=request==null?new MakePartPriceCalcQueryRequest():request;
    if(text(q.getBusinessUnitType())!=null&&!unit.equals(text(q.getBusinessUnitType())))throw new AccessDeniedException("不能读取其他业务单元的制造件价格");
    var p=new HashMap<String,Object>();p.put("unit",unit);p.put("month",text(q.getPricingMonth()));
    p.put("parent",text(q.getParentMaterialNo()));p.put("oa",text(q.getOaNo()));p.put("batch",text(q.getCalcBatchId()));
    p.put("child",text(q.getChildMaterialNo()));p.put("scrap",text(q.getScrapCode()));p.put("process",text(q.getItemProcessType()));
    p.put("onlyError",Boolean.TRUE.equals(q.getOnlyError())?1:0);
    p.put("status",Boolean.TRUE.equals(q.getOnlyError())?null:text(q.getStatus()));
    int size=q.getPageSize()==null?20:Math.max(1,Math.min(200,q.getPageSize()));
    p.put("limit",size);p.put("offset",(long)Math.max(0,(q.getPage()==null?1:q.getPage())-1)*size);
    return p;
  }
  private static String text(String value) {return value==null||value.isBlank()?null:value.trim();}

  private static String decoded(String column) {
    // Compressed or unrecognised historical evidence is kept distinct, never guessed equivalent.
    return "CASE WHEN JSON_VALID("+column+") AND JSON_EXTRACT("+column+",'$.schemaVersion')=1 "
        +"AND JSON_VALID(JSON_UNQUOTE(JSON_EXTRACT("+column+",'$.document'))) "
        +"THEN JSON_EXTRACT(JSON_UNQUOTE(JSON_EXTRACT("+column+",'$.document')),'$') ELSE NULL END";
  }
  private static String sourceBasis(String name) {
    String document=name+"_document";
    return "COALESCE(CASE WHEN JSON_TYPE(JSON_EXTRACT("+document+",'$.selection'))='OBJECT' THEN CAST(JSON_ARRAY("
        +"JSON_EXTRACT("+document+",'$.selection'),JSON_EXTRACT("+document+",'$.financialPrice'),"
        +"JSON_EXTRACT("+document+",'$.customerPrice'),JSON_EXTRACT("+document+",'$.unitPriceMultiplier'),"
        +"JSON_EXTRACT("+document+",'$.request.currency'),JSON_EXTRACT("+document+",'$.request.taxBasis'),"
        +"JSON_EXTRACT("+document+",'$.request.unit'),JSON_EXTRACT("+document+",'$.request.family')) AS CHAR) END,NULLIF("+name+"_input_snapshot_json,''),"
        +"IF("+name+"_source_price_record_id IS NOT NULL,CONCAT('SOURCE:',"+name+"_source_price_record_id),CONCAT('UNKNOWN:',id)))";
  }
  static String cte() {
    return "WITH evidence AS (SELECT r.*,b.price_org_code saved_org,"+decoded("r.raw_input_snapshot_json")+" raw_document,"
        +decoded("r.scrap_input_snapshot_json")+" scrap_document FROM lp_make_part_price_calc_row r "
        +"LEFT JOIN lp_bom_costing_row b ON b.id=r.source_costing_row_id AND CONVERT(b.business_unit_type USING utf8mb4) COLLATE utf8mb4_unicode_ci=CONVERT(r.business_unit_type USING utf8mb4) COLLATE utf8mb4_unicode_ci "
        +"WHERE r.business_unit_type=:unit AND (:month IS NULL OR r.pricing_month=:month) AND (:parent IS NULL OR r.parent_material_no=:parent)),"
        +"scoped AS (SELECT evidence.*,COALESCE(NULLIF(saved_org,''),NULLIF(JSON_UNQUOTE(JSON_EXTRACT(raw_document,'$.request.priceOrgCode')),'null'),"
        +"CONCAT('UNKNOWN:',COALESCE(source_costing_row_id,id))) organization,"
        +"SHA2(CAST(JSON_ARRAY(calc_batch_id,COALESCE(source_costing_row_id,id),oa_no,pricing_month,price_scenario_type,parent_material_no) AS CHAR),256) occurrence_key "
        +"FROM evidence),price_lines AS (SELECT scoped.*,SHA2(CAST(JSON_ARRAY(business_unit_type,pricing_month,organization,price_scenario_type,parent_material_no,"
        +"parent_material_name,drawing_no,item_process_type,child_material_no,child_material_name,child_material_spec,stock_unit,qty_per_parent,gross_weight_g,net_weight_g,"
        +"raw_price_type,raw_unit_price,raw_source_price_record_id,raw_source_price_batch_no,scrap_code,scrap_name,scrap_price_type,scrap_unit_price,"
        +"scrap_source_price_record_id,scrap_source_price_batch_no,no_scrap_confirmed,no_scrap_confirmation_id,outsource_fee,cost_price,parent_total_cost_price,price_complete,status,"
        +sourceBasis("raw")+","+sourceBasis("scrap")+") AS CHAR),256) line_hash,"
        +"IF((:oa IS NULL OR oa_no=:oa) AND (:batch IS NULL OR calc_batch_id=:batch) AND (:child IS NULL OR child_material_no=:child) "
        +"AND (:scrap IS NULL OR scrap_code=:scrap) AND (:process IS NULL OR item_process_type=:process),1,0) eligible FROM scoped),"
        +"occurrences AS (SELECT occurrence_key,MAX(oa_no) oa_no,MAX(organization) organization,MAX(id) representative_id,MAX(created_at) latest_generated_at,"
        +"COUNT(*) detail_count,MAX(eligible) eligible,COALESCE(MIN(NULLIF(COALESCE(status,'UNKNOWN'),'OK')),'OK') group_status,"
        +"GROUP_CONCAT(line_hash ORDER BY line_hash SEPARATOR '') line_hashes FROM price_lines GROUP BY occurrence_key),"
        +"variants AS (SELECT occurrences.*,SHA2(IF(LENGTH(line_hashes)=64*detail_count,line_hashes,occurrence_key),256) variant FROM occurrences),"
        +"price_groups AS (SELECT variant,MAX(representative_id) representative_id,MAX(organization) organization,MAX(latest_generated_at) latest_generated_at,"
        +"MAX(detail_count) detail_count,COUNT(*) calculation_count,COUNT(DISTINCT NULLIF(oa_no,'')) quote_count,MAX(eligible) eligible,MAX(group_status) group_status "
        +"FROM variants GROUP BY variant) ";
  }

  public int export(MakePartPriceCalcQueryRequest request,OutputStream output) {
    var p=parameters(request);long count=jdbc.queryForObject(cte()+SELECT+"COUNT(*) FROM price_groups g WHERE "+FILTER,p,Long.class);
    if(count>10000)throw new IllegalArgumentException("结果超过10000种价格，请按月份或料号筛选后导出");
    var groups=jdbc.query(cte()+SELECT+GROUP_SELECT+"WHERE "+FILTER+"ORDER BY r.pricing_month DESC,r.parent_material_no,g.representative_id DESC",p,this::group);
    var quotes=jdbc.query(cte()+SELECT+"g.representative_id price_id,v.oa_no,COUNT(*) n,MAX(v.latest_generated_at) latest FROM price_groups g "
        +"JOIN variants v ON v.variant=g.variant WHERE "+FILTER+"AND NULLIF(v.oa_no,'') IS NOT NULL GROUP BY g.representative_id,v.oa_no ORDER BY price_id,v.oa_no",p,
        (rs,n)->List.<Object>of(rs.getString("price_id"),rs.getString("oa_no"),rs.getLong("n"),Objects.toString(rs.getString("latest"),"")));
    var details=jdbc.query(cte()+SELECT+"g.representative_id price_id,r.* FROM price_groups g JOIN price_lines chosen ON chosen.id=g.representative_id "
        +"JOIN price_lines l ON l.occurrence_key=chosen.occurrence_key JOIN lp_make_part_price_calc_row r ON r.id=l.id WHERE "+FILTER+"ORDER BY price_id,r.id",p,
        (rs,n)->{
          var r=mapper.mapRow(rs,n);return Arrays.<Object>asList(rs.getString("price_id"),r.getParentMaterialNo(),r.getChildMaterialNo(),r.getChildMaterialName(),r.getStockUnit(),r.getQtyPerParent(),
              r.getGrossWeightG(),r.getNetWeightG(),r.getRawPriceType(),r.getRawUnitPrice(),r.getScrapCode(),r.getScrapUnitPrice(),r.getOutsourceFee(),r.getCostPrice(),String.valueOf(r.getId()));
        });
    var main=new ArrayList<List<Object>>();
    for(var g:groups) {
      var r=g.row();main.add(Arrays.asList(String.valueOf(g.id()),r.getPricingMonth(),g.organization(),r.getParentMaterialNo(),r.getParentMaterialName(),r.getDrawingNo(),r.getItemProcessType(),
          r.getParentTotalCostPrice(),g.latestGeneratedAt(),g.detailCount(),g.status(),g.calculationCount(),g.quoteCount()));
    }
    try(var writer=EasyExcel.write(output).build()) {
      writer.write(main,EasyExcel.writerSheet(0,"制造件价格汇总").head(head("价格记录ID","价格月份","组织","料号","名称","图号","料件类型","零件价格","最近生成时间","原料废料明细数","状态","生成次数","关联报价单数")).build());
      writer.write(quotes,EasyExcel.writerSheet(1,"关联报价单").head(head("价格记录ID","报价单号","生成次数","最近生成时间")).build());
      writer.write(details,EasyExcel.writerSheet(2,"原料废料明细").head(head("价格记录ID","制造件料号","原材料代码","原材料名称","单位","单件用量","毛重(g)","净重(g)","原料价格来源","原料单价","废料代码","废料单价","委外加工费","本行成本","原始明细ID")).build());
    }
    return groups.size();
  }
  private static List<List<String>> head(String... labels) {return Arrays.stream(labels).map(List::of).toList();}
}
