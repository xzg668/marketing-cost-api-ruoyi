package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAccessTicketExchangeRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAccessTicketExchangeResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAccessTicketIssueRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAccessTicketIssueResponse;

public interface TechnicalDataAccessTicketService {
  TechnicalDataAccessTicketIssueResponse issue(
      Long taskId, TechnicalDataAccessTicketIssueRequest request, TechnicalDataActor actor);

  TechnicalDataAccessTicketIssueResponse issueForAssignee(Long taskId, TechnicalDataActor actor);

  TechnicalDataAccessTicketExchangeResponse exchange(
      TechnicalDataAccessTicketExchangeRequest request);
}
