package com.sanhua.marketingcost.dto.materialshape;

import com.sanhua.marketingcost.entity.MaterialQuoteShapePolicy;
import java.time.LocalDateTime;
import lombok.Data;

/** 料品报价形态规则响应，JSON 配置返回规范化后的持久化内容。 */
@Data
public class MaterialQuoteShapePolicyResponse {

  private Long id;
  private String materialOrgCode;
  private String materialCode;
  private String materialName;
  private String materialSpec;
  private String materialModel;
  private String policyMode;
  private String fixedTargetShape;
  private String conditionConfigJson;
  private String actionConfigJson;
  private String effectiveFromMonth;
  private String effectiveToMonth;
  private Integer enabled;
  private String remark;
  private LocalDateTime createdAt;
  private Long createdBy;
  private LocalDateTime updatedAt;
  private Long updatedBy;

  public static MaterialQuoteShapePolicyResponse from(
      MaterialQuoteShapePolicy entity) {
    if (entity == null) {
      return null;
    }
    MaterialQuoteShapePolicyResponse response =
        new MaterialQuoteShapePolicyResponse();
    response.setId(entity.getId());
    response.setMaterialOrgCode(entity.getMaterialOrgCode());
    response.setMaterialCode(entity.getMaterialCode());
    response.setMaterialName(entity.getMaterialName());
    response.setMaterialSpec(entity.getMaterialSpec());
    response.setMaterialModel(entity.getMaterialModel());
    response.setPolicyMode(entity.getPolicyMode());
    response.setFixedTargetShape(entity.getFixedTargetShape());
    response.setConditionConfigJson(entity.getConditionConfigJson());
    response.setActionConfigJson(entity.getActionConfigJson());
    response.setEffectiveFromMonth(entity.getEffectiveFromMonth());
    response.setEffectiveToMonth(entity.getEffectiveToMonth());
    response.setEnabled(entity.getEnabled());
    response.setRemark(entity.getRemark());
    response.setCreatedAt(entity.getCreatedAt());
    response.setCreatedBy(entity.getCreatedBy());
    response.setUpdatedAt(entity.getUpdatedAt());
    response.setUpdatedBy(entity.getUpdatedBy());
    return response;
  }
}
