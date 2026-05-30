package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.U9BomByproductMaster;
import java.util.List;

public record U9BomByproductPageResponse(long total, List<U9BomByproductMaster> records) {}
