package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.CmsPlanCostRaw;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CmsPlanCostRawMapper extends BaseMapper<CmsPlanCostRaw> {
  @Insert({
    "INSERT INTO cms_plan_cost_raw (",
    "  import_batch_id, row_no, first_unit_code, first_unit_name, parent_code, parent_name,",
    "  parent_spec, parent_type, unit, working_hours, effective_date, effective_period,",
    "  main_material_cost, aux_material_cost, salary_cost, fund_cost, loss_cost, total_plan_cost,",
    "  business_status, unapproved_items, description, oa_no, business_unit_type",
    ") VALUES (",
    "  #{row.importBatchId}, #{row.rowNo}, #{row.firstUnitCode}, #{row.firstUnitName}, #{row.parentCode}, #{row.parentName},",
    "  #{row.parentSpec}, #{row.parentType}, #{row.unit}, #{row.workingHours}, #{row.effectiveDate}, #{row.effectivePeriod},",
    "  #{row.mainMaterialCost}, #{row.auxMaterialCost}, #{row.salaryCost}, #{row.fundCost}, #{row.lossCost}, #{row.totalPlanCost},",
    "  #{row.businessStatus}, #{row.unapprovedItems}, #{row.description}, #{row.oaNo}, #{row.businessUnitType}",
    ") ON DUPLICATE KEY UPDATE",
    "  import_batch_id = VALUES(import_batch_id),",
    "  row_no = VALUES(row_no),",
    "  first_unit_code = VALUES(first_unit_code),",
    "  first_unit_name = VALUES(first_unit_name),",
    "  parent_name = VALUES(parent_name),",
    "  parent_spec = VALUES(parent_spec),",
    "  parent_type = VALUES(parent_type),",
    "  unit = VALUES(unit),",
    "  working_hours = VALUES(working_hours),",
    "  effective_date = VALUES(effective_date),",
    "  main_material_cost = VALUES(main_material_cost),",
    "  aux_material_cost = VALUES(aux_material_cost),",
    "  salary_cost = VALUES(salary_cost),",
    "  fund_cost = VALUES(fund_cost),",
    "  loss_cost = VALUES(loss_cost),",
    "  total_plan_cost = VALUES(total_plan_cost),",
    "  business_status = VALUES(business_status),",
    "  unapproved_items = VALUES(unapproved_items),",
    "  description = VALUES(description),",
    "  oa_no = VALUES(oa_no)"
  })
  int upsert(@Param("row") CmsPlanCostRaw row);
}
