package com.sanhua.marketingcost.worker;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Registers the same submitted-material publication and per-product review used by the API. */
@Configuration
@Import({
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingState.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingInputWrites.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalBomApplicationService.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalBomContextQuery.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalBomAbsenceQuery.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalBomReferenceQuery.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalBomValidation.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalBomWorkbook.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalBomSavedSourceQuery.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalBomPreparationSource.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalBomCostingInputs.class,
        com.sanhua.marketingcost.service.technicaldata.TechnicalBomModuleCoverage.class,
    com.sanhua.marketingcost.service.costing.QuoteCostingPreparationService.class,
    com.sanhua.marketingcost.service.impl.QuoteBatchCostRunServiceImpl.class,
    com.sanhua.marketingcost.service.impl.BusinessUnitRepriceLockGuardImpl.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataOaSubmissionLifecycle.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingProductQuery.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingSourceReads.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataGapStatusQuery.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataParticipantVersions.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataManufacturingApplicationService.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataPackageApplicationService.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataAuxiliaryApplicationService.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataSolderApplicationService.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataSalaryApplicationService.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataNetLossApplicationService.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataPlateMaterialProfile.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataPlateMaterialPublication.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataMaterialPriceQuery.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataManufacturingPrices.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataManufacturingReuseQuery.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataBomPriceCheck.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataAuxiliarySourceQuery.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataAttachmentStore.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataSolderSourceQuery.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataAuxiliaryUploadParser.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataSalaryUploadParser.class
})
class QuotePreparationWorkerConfiguration {}
