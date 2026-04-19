package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.OaFormDetailDto;
import com.sanhua.marketingcost.dto.OaFormListItemDto;
import java.time.LocalDate;
import java.util.List;

public interface OaFormService {
  List<OaFormListItemDto> listForms(String oaNo, String formType, String customer, String status,
      LocalDate startDate, LocalDate endDate);

  OaFormDetailDto getDetailByOaNo(String oaNo);
}
