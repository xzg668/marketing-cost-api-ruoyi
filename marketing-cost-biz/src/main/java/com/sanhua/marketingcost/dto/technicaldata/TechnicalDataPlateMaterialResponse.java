package com.sanhua.marketingcost.dto.technicaldata;

import com.sanhua.marketingcost.service.platematerial.PlateMaterial;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialMode;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialSnapshot;

/** Current draft rules and saved evidence are separate; historical views have no current lookup. */
public record TechnicalDataPlateMaterialResponse(
    PlateMaterialMode mode, PlateMaterial value, PlateMaterialSnapshot.Source source,
    String revision, PlateMaterialSnapshot saved, boolean sourceChanged, boolean historical) {}
