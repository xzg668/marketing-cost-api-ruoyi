package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialSpecialPriceConfiguration {
  public record Input(String materialCode,String materialName,String materialSpec,String materialModel,
      String priceOrgCode,String priceType,String refreshMode,boolean enabled,String reminderText,Long expectedVersion) {}
  private final JdbcTemplate jdbc;
  public MaterialSpecialPriceConfiguration(JdbcTemplate jdbc) { this.jdbc=jdbc; }
  public List<Map<String,Object>> list(String search) {
    return jdbc.queryForList("""
        SELECT id,material_code materialCode,material_name materialName,material_spec materialSpec,material_model materialModel,
          price_org_code priceOrgCode,price_type priceType,price_refresh_mode refreshMode,rule_enabled enabled,
          reminder_text reminderText,rule_version version,rule_updated_by updatedBy,updated_at updatedAt
        FROM lp_material_price_type WHERE business_unit_type=? AND special_rule=1
          AND (material_code LIKE ? OR material_name LIKE ? OR material_spec LIKE ? OR material_model LIKE ?)
        ORDER BY material_code,price_org_code LIMIT 500
        """,unit(),pattern(search),pattern(search),pattern(search),pattern(search));
  }
  public List<Map<String,Object>> material(String code) {
    return jdbc.queryForList("""
        SELECT DISTINCT material_code materialCode,material_name materialName,material_spec materialSpec,material_model materialModel
        FROM lp_material_master_raw WHERE material_code=? AND active_flag=1 AND (organization_code=? OR (?='COMMERCIAL' AND organization_code IN ('210','220')))
        ORDER BY material_name,material_spec,material_model LIMIT 20
        """,required(code,"料号",64),unit(),unit());
  }
  @Transactional(rollbackFor=Exception.class)
  public void save(Long id,Input input,String actor) {
    if(input==null)throw new IllegalArgumentException("请填写物料取价配置");
    String code=required(input.materialCode(),"料号",64),org=required(input.priceOrgCode(),"组织",32);
    if(!Set.of("ALL","210","220").contains(org))throw new IllegalArgumentException("请选择全部组织、210或220取价组织");
    if(!MaterialSpecialPricePolicy.TYPES.containsKey(input.priceType())||!Set.of("CURRENT_MONTH","LATEST_VALID").contains(input.refreshMode()))
      throw new IllegalArgumentException("请选择限定价格类型和更新方式");
    String name=text(input.materialName(),"名称",128),spec=text(input.materialSpec(),"规格",128),model=text(input.materialModel(),"型号",128);
    String reminder=text(input.reminderText(),"提醒说明",500),unit=unit();
    if(id==null) {
      jdbc.update("""
          INSERT INTO lp_material_price_type(business_unit_type,price_org_code,material_code,material_name,material_spec,material_model,
            price_type,period,priority,source,source_system,special_rule,price_refresh_mode,rule_enabled,reminder_text,rule_version,rule_updated_by,
            priority_updated_by,priority_updated_at,created_at,updated_at)
          VALUES(?,?,?,?,?,?,?,NULL,1,'MANUAL','SPECIAL_POLICY',1,?,?,?,1,?,NULL,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
          """,unit,org,code,name,spec,model,input.priceType(),input.refreshMode(),input.enabled()?1:0,reminder,actor);
    } else {
      if(input.expectedVersion()==null)throw new IllegalArgumentException("缺少配置版本，请刷新");
      int changed=jdbc.update("""
          UPDATE lp_material_price_type SET price_org_code=?,material_code=?,material_name=?,material_spec=?,material_model=?,price_type=?,
            price_refresh_mode=?,rule_enabled=?,reminder_text=?,rule_version=rule_version+1,rule_updated_by=?,
            priority_updated_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6)
          WHERE id=? AND business_unit_type=? AND special_rule=1 AND rule_version=?
          """,org,code,name,spec,model,input.priceType(),input.refreshMode(),input.enabled()?1:0,reminder,actor,id,unit,input.expectedVersion());
      if(changed!=1)throw new org.springframework.dao.OptimisticLockingFailureException("配置已被修改或无权修改，请刷新");
    }
  }
  private static String unit() {
    String unit=BusinessUnitContext.getCurrentBusinessUnitType();
    if(!Set.of("COMMERCIAL","HOUSEHOLD").contains(Objects.toString(unit,"")))throw new org.springframework.security.access.AccessDeniedException("请先选择业务单元");
    return unit;
  }
  private static String pattern(String value) { return "%"+Objects.toString(value,"").trim()+"%"; }
  private static String required(String value,String label,int max) { String result=text(value,label,max);if(result==null||result.isBlank())throw new IllegalArgumentException(label+"不能为空");return result; }
  private static String text(String value,String label,int max) { if(value==null)return null;String result=value.trim();if(result.length()>max)throw new IllegalArgumentException(label+"过长");return result; }
}
