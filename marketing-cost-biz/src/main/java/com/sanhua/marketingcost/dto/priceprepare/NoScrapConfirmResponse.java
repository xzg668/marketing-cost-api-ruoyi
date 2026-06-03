package com.sanhua.marketingcost.dto.priceprepare;

import com.sanhua.marketingcost.entity.MakePartNoScrapConfirmation;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NoScrapConfirmResponse {
  private Long id;
  private String businessUnitType;
  private String materialNo;
  private String materialName;
  private String effectiveFromMonth;
  private String effectiveToMonth;
  private String status;
  private String confirmReason;
  private String sourceOaNo;
  private Long sourceGapId;
  private String confirmedBy;
  private LocalDateTime confirmedAt;
  private String revokedBy;
  private LocalDateTime revokedAt;
  private String revokeReason;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public static NoScrapConfirmResponse from(MakePartNoScrapConfirmation entity) {
    if (entity == null) {
      return null;
    }
    NoScrapConfirmResponse response = new NoScrapConfirmResponse();
    response.setId(entity.getId());
    response.setBusinessUnitType(entity.getBusinessUnitType());
    response.setMaterialNo(entity.getMaterialNo());
    response.setMaterialName(entity.getMaterialName());
    response.setEffectiveFromMonth(entity.getEffectiveFromMonth());
    response.setEffectiveToMonth(entity.getEffectiveToMonth());
    response.setStatus(entity.getStatus());
    response.setConfirmReason(entity.getConfirmReason());
    response.setSourceOaNo(entity.getSourceOaNo());
    response.setSourceGapId(entity.getSourceGapId());
    response.setConfirmedBy(entity.getConfirmedBy());
    response.setConfirmedAt(entity.getConfirmedAt());
    response.setRevokedBy(entity.getRevokedBy());
    response.setRevokedAt(entity.getRevokedAt());
    response.setRevokeReason(entity.getRevokeReason());
    response.setCreatedAt(entity.getCreatedAt());
    response.setUpdatedAt(entity.getUpdatedAt());
    return response;
  }
}
