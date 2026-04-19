package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunProgressResponse;
import com.sanhua.marketingcost.dto.CostRunTrialResponse;
import java.util.concurrent.CompletableFuture;

public interface CostRunTrialService {
  CompletableFuture<CostRunTrialResponse> run(String oaNo);

  CostRunProgressResponse progress(String oaNo);
}
