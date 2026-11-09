package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.dto.AuxCostItemDto;
import com.sanhua.marketingcost.mapper.AuxCostItemMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Pattern;

/** Removes only surface-treatment paths inside named sintered bases, before the CMS uplift. */
public final class SinteredBaseSurfaceCosts {
  private static final Pattern NODE=Pattern.compile("(?:^|→)([^()→;]+)\\(×[^()]+\\)");
  private static final Pattern AMOUNT=Pattern.compile("\\((-?\\d+(?:\\.\\d+)?)\\)$");
  private SinteredBaseSurfaceCosts() {}

  public static void apply(List<? extends AuxCostItemDto> rows,String unit,AuxCostItemMapper mapper) {
    var surfaces=rows.stream().filter(r->"CMS_EFFECTIVE".equals(r.getSource())
        && "cms_product_subject_cost_raw".equals(r.getSourceTable())
        && "0202".equals(r.getAuxSubjectCode()) && r.getExcludedSurfaceAmount()==null
        && r.getSourceRowIds()!=null && !r.getSourceRowIds().isBlank()).toList();
    if(surfaces.isEmpty())return;
    var ids=new TreeSet<Long>();surfaces.forEach(r->ids.addAll(ids(r)));
    var paths=new HashMap<Long,AuxCostItemMapper.SurfacePath>();
    var idList=new ArrayList<>(ids);
    for(int i=0;i<idList.size();i+=256) for(var path:mapper.surfacePaths(unit,idList.subList(i,Math.min(i+256,idList.size())))) paths.put(path.id(),path);
    var codes=new TreeSet<String>();paths.values().forEach(p->segments(p.path()).forEach(s->codes.addAll(nodes(s))));
    var bases=new HashSet<String>();var codeList=new ArrayList<>(codes);
    for(int i=0;i<codeList.size();i+=256)bases.addAll(mapper.sinteredBaseCodes(unit,codeList.subList(i,Math.min(i+256,codeList.size()))));
    for(var row:surfaces) {
      var excluded=BigDecimal.ZERO;var evidence=new ArrayList<String>();
      for(long id:ids(row)) {
        var source=paths.get(id);
        if(source==null || !Objects.equals(source.parentCode(),row.getMaterialCode())
            || !Objects.equals(source.period(),row.getSourcePeriod()))
          throw new IllegalStateException("表面处理来源路径缺失或不匹配："+row.getMaterialCode()+" #"+id);
        var selected=segments(source.path()).stream().filter(s->nodes(s).stream().anyMatch(bases::contains)).toList();
        if(selected.isEmpty())continue;
        // CMS paths are in cents and already include path quantities; never multiply quantities again.
        var cents=selected.stream().map(SinteredBaseSurfaceCosts::cents).reduce(BigDecimal.ZERO,BigDecimal::add);
        var amount=cents.movePointLeft(2).setScale(6,RoundingMode.HALF_UP);
        excluded=excluded.add(amount);
        selected.forEach(s->evidence.add("CMS #"+id+"："+s));
      }
      if(excluded.signum()==0)continue;
      if(row.getUnitPrice()==null || excluded.compareTo(row.getUnitPrice())>0)
        throw new IllegalStateException("烧结基座表面处理剔除金额超过来源金额："+row.getMaterialCode());
      row.setUnitPrice(row.getUnitPrice().subtract(excluded));row.setExcludedSurfaceAmount(excluded);
      row.setExclusionEvidence("已剔除烧结基座子树表面处理 "+excluded.toPlainString()+" 元（上浮前）；"+String.join("；",evidence));
    }
  }
  private static List<Long> ids(AuxCostItemDto row) {
    return Arrays.stream(row.getSourceRowIds().replace("[", "").replace("]", "").split(",")).map(String::trim).map(Long::valueOf).distinct().toList();
  }
  private static List<String> segments(String path) {
    return path==null?List.of():Arrays.stream(path.split("[;；]")).map(String::trim).filter(s->!s.isEmpty()).toList();
  }
  private static List<String> nodes(String path) {
    var result=new ArrayList<String>();var matcher=NODE.matcher(path);
    while(matcher.find())result.add(matcher.group(1).trim());return result;
  }
  private static BigDecimal cents(String path) {
    var match=AMOUNT.matcher(path);
    if(!match.find())throw new IllegalStateException("烧结基座表面处理路径缺少金额："+path);
    return new BigDecimal(match.group(1));
  }
}
