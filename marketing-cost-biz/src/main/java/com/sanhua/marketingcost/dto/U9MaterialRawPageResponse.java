package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import java.util.List;

public record U9MaterialRawPageResponse(long total, List<MaterialMasterRaw> records) {}
