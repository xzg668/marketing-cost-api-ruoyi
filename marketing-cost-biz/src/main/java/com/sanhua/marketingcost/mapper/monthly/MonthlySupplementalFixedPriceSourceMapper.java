package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository.Flow;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataSharedModuleRepository.Owner;
import java.util.*;
import org.apache.ibatis.annotations.*;

/** Actual claim ownership and complete current/final technical captures, bounded by owner products. */
@Mapper
public interface MonthlySupplementalFixedPriceSourceMapper {
  record Ownership(String materialCode,long moduleId,long productId,long taskId,long quoteItemId,
      String productMaterialNo,String moduleType,String taskStatus,String moduleStatus,Long assigneeId,
      String assigneeName,Long sourceVersionId,String sourceVersionStatus,String businessUnit,String organization,
      String originalMonth,Long originalFormId,Long flowId,Integer taskActive,Integer productActive,
      String productStatus,Long effectiveVersionId,Integer schemaVersion,String claimOrganization,
      String claimBusinessUnit,String unit,String currency) {
    public Owner owner(){return new Owner(moduleId,productId,taskId,quoteItemId,productMaterialNo,moduleType,
        taskStatus,moduleStatus,assigneeId,assigneeName,sourceVersionId,sourceVersionStatus,businessUnit,organization,originalMonth);}
    public QuoteTechTask task(){var value=new QuoteTechTask();value.setId(taskId);value.setOaFormId(originalFormId);
      value.setOaFlowId(flowId);value.setActiveFlag(taskActive);value.setTaskStatus(taskStatus);
      value.setBusinessUnitType(businessUnit);value.setApplicableOrgCode(organization);value.setAccountingMonth(originalMonth);return value;}
    public QuoteTechProduct product(){var value=new QuoteTechProduct();value.setId(productId);value.setTaskId(taskId);
      value.setOaFormItemId(quoteItemId);value.setMaterialNo(productMaterialNo);value.setAccountingMonth(originalMonth);
      value.setActiveFlag(productActive);value.setProductStatus(productStatus);value.setEffectiveVersionId(effectiveVersionId);
      value.setContentSchemaVersion(schemaVersion);return value;}
  }

  @Select("""
    SELECT f.id FROM oa_form f JOIN oa_form_item i ON i.oa_form_id=f.id
    WHERE i.id=#{item} AND f.oa_no=#{oa} AND f.business_unit_type=#{unit}
    """)
  Long currentForm(@Param("item") Long item,@Param("oa") String oa,@Param("unit") String unit);

  @Select("""
    <script>SELECT c.material_code,m.id module_id,p.id product_id,t.id task_id,p.oa_form_item_id quote_item_id,
      p.material_no product_material_no,m.module_type,t.task_status,m.module_status,m.assignee_user_id assignee_id,
      m.assignee_name,m.current_version_id source_version_id,v.version_status source_version_status,
      t.business_unit_type business_unit,t.applicable_org_code organization,p.accounting_month original_month,
      t.oa_form_id original_form_id,t.oa_flow_id flow_id,t.active_flag task_active,p.active_flag product_active,
      p.product_status,p.effective_version_id,p.content_schema_version schema_version,
      c.organization_code claim_organization,c.business_unit_type claim_business_unit,c.price_unit unit,c.currency
      FROM lp_quote_tech_price_claim c JOIN lp_quote_tech_module m ON m.id=c.owner_module_id
      JOIN lp_quote_tech_product p ON p.id=m.product_id JOIN lp_quote_tech_task t ON t.id=p.task_id
      LEFT JOIN lp_quote_tech_data_version v ON v.id=m.current_version_id
      WHERE c.business_unit_type=#{unit} AND c.material_code IN
      <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
      ORDER BY c.material_code</script>
    """)
  List<Ownership> owners(@Param("unit") String unit,@Param("codes") Collection<String> codes);

  @Select("""
    <script>SELECT p.* FROM lp_price_fixed_item p JOIN lp_quote_tech_price_claim c ON c.material_code=p.material_code
      JOIN lp_quote_tech_module m ON m.id=c.owner_module_id AND m.current_version_id=p.technical_version_id
      WHERE p.source_kind='TECH_SUPPLEMENTAL' AND p.business_unit_type=#{unit} AND c.material_code IN
      <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach> ORDER BY p.id DESC</script>
    """)
  List<PriceFixedItem> fixed(@Param("unit") String unit,@Param("codes") Collection<String> codes);

  final class CurrentMaterial extends MaterialMasterRaw {
    private String requestedMaterialCode;
    public String getRequestedMaterialCode(){return requestedMaterialCode;}
    public void setRequestedMaterialCode(String code){requestedMaterialCode=code;}
    public MaterialMasterRaw reference(){var value=new MaterialMasterRaw();org.springframework.beans.BeanUtils.copyProperties(this,value);return value;}
  }
  @Select("""
    <script>SELECT r.*,requested.material_code requested_material_code FROM (
      <foreach collection="codes" item="code" separator=" UNION ALL ">
        SELECT CONVERT(#{code} USING utf8mb4) COLLATE utf8mb4_0900_ai_ci material_code
      </foreach>) requested JOIN lp_material_master_raw r ON r.material_code=requested.material_code
      WHERE r.active_flag=1 AND r.organization_code=#{organization} ORDER BY requested.material_code,r.id</script>
    """)
  List<CurrentMaterial> materials(@Param("organization") String organization,@Param("codes") Collection<String> codes);

  @Select("<script>SELECT * FROM lp_quote_tech_module WHERE product_id IN <foreach collection='products' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY product_id,id</script>")
  List<QuoteTechModule> modules(@Param("products") Collection<Long> products);
  @Select("<script>SELECT * FROM lp_quote_tech_data_version WHERE id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY product_id,id</script>")
  List<QuoteTechDataVersion> versions(@Param("ids") Collection<Long> ids);
  @Select("<script>SELECT * FROM lp_quote_tech_package_item WHERE version_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY version_id,line_no,id</script>")
  List<QuoteTechPackageItem> packages(@Param("ids") Collection<Long> ids);
  @Select("<script>SELECT * FROM lp_quote_tech_aux_item WHERE version_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY version_id,line_no,id</script>")
  List<QuoteTechAuxItem> auxiliary(@Param("ids") Collection<Long> ids);
  @Select("<script>SELECT * FROM lp_quote_tech_salary_item WHERE version_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY version_id,line_no,id</script>")
  List<QuoteTechSalaryItem> salary(@Param("ids") Collection<Long> ids);

  @Select("""
    <script>SELECT id,source_system,environment,oa_form_id,accounting_month,external_document_id document_id,
      external_flow_id,finance_sequence,finance_ready,finance_user_id,finance_confirmed_fingerprint confirmed_fingerprint
      FROM lp_oa_technical_flow WHERE id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach> ORDER BY id</script>
    """)
  List<Flow> flows(@Param("ids") Collection<Long> ids);
  record Submission(long flowId,long taskId,long recipientId,long submissionId,long versionId,String fingerprint) {
    public String basis(){return recipientId+":"+submissionId+":"+versionId+":"+fingerprint;}
  }
  @Select("""
    <script>SELECT t.oa_flow_id flow_id,t.id task_id,r.id recipient_id,s.id submission_id,
      s.technical_version_id version_id,s.content_fingerprint fingerprint
      FROM lp_quote_tech_task t JOIN lp_quote_tech_oa_recipient r ON r.task_id=t.id AND r.active_flag=1
      JOIN lp_quote_tech_submission s ON s.id=r.latest_submission_id
      WHERE t.active_flag=1 AND r.todo_status IN ('SUBMITTED','DONE') AND s.submission_status IN ('SENT','APPROVED')
      AND t.oa_flow_id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      ORDER BY t.oa_flow_id,t.id,r.id</script>
    """)
  List<Submission> submissions(@Param("ids") Collection<Long> ids);
}
