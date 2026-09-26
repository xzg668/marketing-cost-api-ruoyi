package com.sanhua.marketingcost.worker;

import com.sanhua.marketingcost.config.AsyncConfig;
import com.sanhua.marketingcost.config.CacheConfig;
import com.sanhua.marketingcost.config.ElectronicDrawingBomProperties;
import com.sanhua.marketingcost.config.LinkedParserProperties;
import com.sanhua.marketingcost.config.MetaObjectHandlerConfig;
import com.sanhua.marketingcost.config.MybatisPlusConfig;
import com.sanhua.marketingcost.config.PasswordEncodingConfig;
import com.sanhua.marketingcost.service.impl.BusinessUnitRepriceLockGuardImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceBatchServiceImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceConfirmServiceImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceOperationServiceImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceQueryServiceImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceStartServiceImpl;
import com.sanhua.marketingcost.service.impl.QuoteBatchCostRunServiceImpl;
import com.sanhua.marketingcost.service.technicaldata.EffectiveTechnicalDataQueryServiceImpl;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataVersionContentCodec;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataQuoteSourceReader;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataSourceCheckService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataSalarySourceQuery;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataSourceCheckStore;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataModuleRequirementEvaluator;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataSourceSnapshotFactory;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataRequirementRefreshService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataOaUserDirectory;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataAssigneeResolver;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataAuditLogService;
import com.sanhua.marketingcost.integration.oa.OaInboxProcessor;
import com.sanhua.marketingcost.integration.oa.OaOutboxProcessor;
import com.sanhua.marketingcost.integration.oa.OaWorkflowNotificationController;
import com.sanhua.marketingcost.integration.oa.OaWorkflowNotificationHandler;
import com.sanhua.marketingcost.integration.oa.OaWorkflowNotificationService;
import java.util.Map;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = SecurityAutoConfiguration.class)
@ComponentScan(
    basePackages = {
        "com.sanhua.marketingcost.worker",
        "com.sanhua.marketingcost.service",
        "com.sanhua.marketingcost.integration",
        "com.sanhua.marketingcost.formula"
    },
    excludeFilters = {
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = {
                BusinessUnitRepriceLockGuardImpl.class,
                MonthlyRepriceBatchServiceImpl.class,
                MonthlyRepriceConfirmServiceImpl.class,
                MonthlyRepriceOperationServiceImpl.class,
                MonthlyRepriceQueryServiceImpl.class,
                MonthlyRepriceStartServiceImpl.class,
                QuoteBatchCostRunServiceImpl.class,
                com.sanhua.marketingcost.service.quoteconfirmation.QuoteMaterialConfirmationService.class,
                // 核算进程只写来源复查的待发送记录，OA 收发与审批由业务后端处理。
                OaInboxProcessor.class,
                OaOutboxProcessor.class,
                OaWorkflowNotificationController.class,
                OaWorkflowNotificationHandler.class,
                OaWorkflowNotificationService.class
            }),
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "com\\.sanhua\\.marketingcost\\.service\\.technicaldata\\..*")
    })
@MapperScan("com.sanhua.marketingcost.mapper")
@EnableScheduling
@Import({
    AsyncConfig.class,
    CacheConfig.class,
    ElectronicDrawingBomProperties.class,
    EffectiveTechnicalDataQueryServiceImpl.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataCostingSources.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalPriceCostingSources.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalBomContributions.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalManufacturingInputs.class,
    com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingPreparationSource.class,
    com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository.class,
    com.sanhua.marketingcost.integration.oa.OaMessageCodec.class,
    LinkedParserProperties.class,
    MetaObjectHandlerConfig.class,
    MybatisPlusConfig.class,
    PasswordEncodingConfig.class,
    TechnicalDataVersionContentCodec.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataManufacturingBomSource.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataManufacturingSourceQuery.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataPackageSourceQuery.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataPackageSourceCheck.class,
    com.sanhua.marketingcost.service.technicaldata.MybatisQuoteTechnicalDataRepository.class,
    com.sanhua.marketingcost.service.technicaldata.MybatisTechnicalDataTaskRepository.class,
    TechnicalDataQuoteSourceReader.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataSharedModuleQuery.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataDependencies.class,
    TechnicalDataSourceCheckService.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataPublicSourceCheck.class,
    TechnicalDataSalarySourceQuery.class,
    TechnicalDataSourceCheckStore.class,
    TechnicalDataModuleRequirementEvaluator.class,
    TechnicalDataSourceSnapshotFactory.class,
    TechnicalDataRequirementRefreshService.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataReadPolicy.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataSharedModules.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataSharedModuleRepository.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataPriceApplicationService.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataPriceRequirements.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataPriceReferences.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataPriceOwnership.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataPricePublication.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataAuxiliaryClassificationService.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataAuxiliaryClassificationRepository.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalDataAuxiliarySubjects.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalPriceCorrectionService.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalPriceCorrectionImportService.class,
    com.sanhua.marketingcost.service.technicaldata.TechnicalPriceCorrectionWorkbook.class,
    com.sanhua.marketingcost.service.technicaldata.SecurityTechnicalDataActorProvider.class,
    TechnicalDataOaUserDirectory.class,
    TechnicalDataAssigneeResolver.class,
    TechnicalDataAuditLogService.class
})
public class CostRunWorkerApplication {

  public static void main(String[] args) {
    buildApplication().run(args);
  }

  static SpringApplication buildApplication() {
    return new SpringApplicationBuilder(CostRunWorkerApplication.class)
        .web(WebApplicationType.NONE)
        .properties(Map.of("spring.application.name", "sales-cost-worker"))
        .build();
  }
}
