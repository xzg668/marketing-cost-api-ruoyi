package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.PriceVariable;
import java.util.List;

public interface PriceVariableService {
  List<PriceVariable> list(String status);
}
