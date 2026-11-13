package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.dto.AuxCostItemDto;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import java.math.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Deduct only the changed material's OWN CMS paths from the actual captured source IDs. */
@Service
public class CmsShapeCostAdjustment {
  private static final Pattern NODE=Pattern.compile("(?:^|→)([^()→;；]+)\\(×[^()]+\\)");
  private static final Pattern VALUE=Pattern.compile("\\((-?\\d+(?:\\.\\d+)?)\\)$");
  private final JdbcTemplate jdbc;
  public CmsShapeCostAdjustment(JdbcTemplate jdbc) { this.jdbc=jdbc; }
  public record Adjustment(BigDecimal amount,List<Map<String,String>> rows) {}

  public CmsCostSourceEffective salary(CmsCostSourceEffective source,String unit,Map<String,BigDecimal> changes) {
    if(changes.isEmpty()) return source;
    var adjustment=read(source.getSourceTable(),source.getSourceRowIds(),source.getParentCode(),
        source.getPeriod(),unit,changes,null);
    if(adjustment.amount().signum()==0) return source;
    if(source.getAmountYuan()==null || adjustment.amount().compareTo(source.getAmountYuan())>0)
      throw new IllegalStateException("料号"+source.getParentCode()+"的CMS工资形态扣减超过已采用来源金额");
    var copy=new CmsCostSourceEffective();BeanUtils.copyProperties(source,copy);
    copy.setAmountYuan(source.getAmountYuan().subtract(adjustment.amount()));
    copy.setShapeExcludedAmount(adjustment.amount());copy.setShapeExclusionRows(adjustment.rows());
    return copy;
  }

  public AuxCostItemDto auxiliary(AuxCostItemDto source,String unit,Map<String,BigDecimal> changes) {
    if(changes.isEmpty() || !"CMS_EFFECTIVE".equals(source.getSource())) return source;
    var adjustment=read(source.getSourceTable(),source.getSourceRowIds(),source.getMaterialCode(),
        source.getSourcePeriod(),unit,changes,source.getExclusionEvidence());
    if(adjustment.amount().signum()==0) return source;
    if(source.getUnitPrice()==null || adjustment.amount().compareTo(source.getUnitPrice())>0)
      throw new IllegalStateException("料号"+source.getMaterialCode()+"的CMS辅料形态扣减超过已采用来源金额");
    var copy=new AuxCostItemDto();BeanUtils.copyProperties(source,copy);
    copy.setUnitPrice(source.getUnitPrice().subtract(adjustment.amount()));
    copy.setShapeExcludedAmount(adjustment.amount());copy.setShapeExclusionRows(adjustment.rows());
    copy.setExclusionEvidence(Objects.toString(source.getExclusionEvidence(),"")+"；形态切换按料号剔除CMS辅料 "
        +adjustment.amount().toPlainString()+" 元（上浮前）");
    return copy;
  }

  private Adjustment read(String table,String rowIds,String parent,String period,String unit,
      Map<String,BigDecimal> changes,String alreadyExcluded) {
    if(!Set.of("cms_product_subject_cost_raw","cms_workshop_labor_raw").contains(Objects.toString(table,"")))
      throw new IllegalStateException("料号"+String.join("、",changes.keySet())+"缺少可追溯CMS工资／辅料明细来源");
    var ids=rowIds==null ? List.<Long>of() : Arrays.stream(rowIds.replace("[","").replace("]","").split(","))
        .map(String::trim).filter(s -> !s.isBlank()).map(Long::valueOf).distinct().toList();
    if(ids.isEmpty()) throw new IllegalStateException("料号"+String.join("、",changes.keySet())+"缺少CMS原始明细ID，无法核对形态扣减");
    var total=BigDecimal.ZERO;var details=new ArrayList<Map<String,String>>();
    for(int start=0;start<ids.size();start+=256) {
      var batch=ids.subList(start,Math.min(start+256,ids.size()));
      var args=new ArrayList<Object>();args.add(unit);args.addAll(batch);
      String money=table.equals("cms_workshop_labor_raw") ? "working_cost_yuan" : "material_price_yuan";
      var rows=jdbc.queryForList("SELECT id,parent_code,period,path,"+money+" amount_yuan FROM "+table
          +" WHERE business_unit_type=? AND id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")",args.toArray());
      if(rows.size()!=batch.size()) throw new IllegalStateException("料号"+parent+"的CMS来源明细缺失，无法核对形态扣减");
      for(var row:rows) {
        if(!Objects.equals(parent,row.get("parent_code")) || !Objects.equals(period,row.get("period")))
          throw new IllegalStateException("料号"+parent+"的CMS来源明细归属不一致");
        String raw=Objects.toString(row.get("path"),"");
        var paths=Arrays.stream(raw.split("[;；]")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if(paths.isEmpty()) throw new IllegalStateException("料号"+parent+"的CMS明细缺少路径，无法核对形态扣减");
        BigDecimal all=BigDecimal.ZERO,selected=BigDecimal.ZERO;
        var selectedPaths=new ArrayList<String>();
        for(String path:paths) {
          var value=VALUE.matcher(path);
          if(!value.find()) throw new IllegalStateException("料号"+parent+"的CMS路径缺少工资工时／辅料金额，无法核对形态扣减");
          var contribution=new BigDecimal(value.group(1));all=all.add(contribution);
          var material=NODE.matcher(path);String owner=null;
          while(material.find()) owner=material.group(1).trim();
          if(owner!=null && changes.containsKey(owner)
              && (alreadyExcluded==null || !alreadyExcluded.contains(path))) {
            selected=selected.add(contribution);selectedPaths.add(path);
          }
        }
        if(selectedPaths.isEmpty()) continue;
        var amount=(BigDecimal)row.get("amount_yuan");
        if(amount==null || all.signum()==0 && amount.signum()!=0)
          throw new IllegalStateException("料号"+parent+"的CMS路径缺少可分摊金额");
        // Salary path values are working minutes, auxiliary values are cents. Both include path
        // quantities already. Use their share of the captured row, never multiply BOM quantities again.
        BigDecimal excluded=all.signum()==0 ? BigDecimal.ZERO
            : amount.multiply(selected).divide(all,6,RoundingMode.HALF_UP);
        total=total.add(excluded);
        var detail=new LinkedHashMap<String,String>();
        detail.put("sourceTable",table);detail.put("sourceId",row.get("id").toString());
        detail.put("parentCode",parent);detail.put("period",period);detail.put("path",String.join("；",selectedPaths));
        detail.put("excludedAmountYuan",excluded.toPlainString());detail.put("originalAmountYuan",amount.toPlainString());
        detail.put("changedMaterials",changes.toString());
        detail.put("note","形态切换：仅剔除路径中直接归属改动料号的CMS工资／辅料；路径已含用量");
        details.add(Collections.unmodifiableMap(detail));
      }
    }
    return new Adjustment(total,List.copyOf(details));
  }
}
