package com.sanhua.marketingcost.dto;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorIdentityDto {
  private Long id;
  private String businessUnitType;
  private String factorSeqNo;
  private String factorName;
  private String shortName;
  private String priceSource;
  private String identityHash;
  private String status;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
