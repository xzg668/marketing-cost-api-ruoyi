package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CmsCostSourceEffectivePageResponse {
  private long total;
  private List<CmsCostSourceEffective> records;
}
