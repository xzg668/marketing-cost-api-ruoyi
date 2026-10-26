package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.entity.monthly.HouseholdInquiryBatchRow;
import com.sanhua.marketingcost.entity.monthly.HouseholdInquiryPriceRow;
import com.sanhua.marketingcost.mapper.monthly.HouseholdInquiryStorageMapper;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.PriceFact;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.SourceEvidence;
import java.util.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Repository;

/** Source storage only; preview parsing and atomic publication are separate application operations. */
@Repository
public class HouseholdInquiryStorage {
  private final HouseholdInquiryStorageMapper mapper;
  private final MonthlyPriceDocumentCodec codec;
  public HouseholdInquiryStorage(HouseholdInquiryStorageMapper mapper,MonthlyPriceDocumentCodec codec) {
    this.mapper=mapper; this.codec=codec;
  }

  public HouseholdInquiryBatchRow insertBatch(HouseholdInquiryBatchRow input) {
    MonthlyPriceStorage.requireWriteTransaction(); MonthlyPriceStorage.requireBusinessUnit(input.getBusinessUnitType());
    if (!"PREVIEW".equals(input.getStatus())) throw new IllegalArgumentException("A new import must start as PREVIEW");
    codec.validateObject(input.getPreviewJson());
    var row=new HouseholdInquiryBatchRow(); BeanUtils.copyProperties(input,row);
    row.setPreviewJson(codec.wrap(input.getPreviewJson())); mapper.insertBatch(row);
    return batch(input.getBusinessUnitType(),input.getPreviewId()).orElseThrow();
  }

  public Optional<HouseholdInquiryBatchRow> batch(String unit,String previewId) {
    MonthlyPriceStorage.requireBusinessUnit(unit);
    var stored=mapper.batchByPreview(unit,previewId);
    if (stored==null) return Optional.empty();
    var row=new HouseholdInquiryBatchRow(); BeanUtils.copyProperties(stored,row);
    row.setPreviewJson(codec.unwrap(row.getPreviewJson()));
    if (row.getResultJson()!=null) row.setResultJson(codec.unwrap(row.getResultJson()));
    return Optional.of(row);
  }

  public HouseholdInquiryPriceRow insertVersion(HouseholdInquiryPriceRow input) {
    MonthlyPriceStorage.requireWriteTransaction(); MonthlyPriceStorage.requireBusinessUnit(input.getBusinessUnitType());
    if (input.getMaterialCode()==null || input.getMaterialCode().isBlank() || input.getMaterialCode().length()>64
        || !input.getMaterialCode().equals(input.getMaterialCode().trim())) throw new IllegalArgumentException("Exact source material code required");
    PriceFact.household(input.getUnitPrice(),input.getHouseholdRate(),SourceEvidence.ofJson(input.getReferenceJson()));
    codec.validateSourceRows(input.getSourceRowsJson());
    var row=new HouseholdInquiryPriceRow(); BeanUtils.copyProperties(input,row);
    row.setReferenceJson(codec.wrap(input.getReferenceJson())); mapper.insertPrice(row);
    return decode(Objects.requireNonNull(mapper.stagedPriceById(input.getBusinessUnitType(),row.getId()),"Inserted source version missing"));
  }

  public void retire(String unit,long priceId) {
    MonthlyPriceStorage.requireWriteTransaction(); MonthlyPriceStorage.requireBusinessUnit(unit);
    if (mapper.retire(unit,priceId)!=1) throw new IllegalStateException("STALE_HOUSEHOLD_CURRENT");
  }

  public List<HouseholdInquiryPriceRow> current(String unit,Collection<String> materials) {
    MonthlyPriceStorage.requireBusinessUnit(unit);
    var unique=new ArrayList<>(new LinkedHashSet<>(materials)); var result=new ArrayList<HouseholdInquiryPriceRow>();
    for (int start=0;start<unique.size();start+=256) {
      mapper.current(unit,unique.subList(start,Math.min(start+256,unique.size()))).forEach(row -> result.add(decode(row)));
    }
    return List.copyOf(result);
  }

  public List<HouseholdInquiryPriceRow> history(String unit,String material) {
    MonthlyPriceStorage.requireBusinessUnit(unit);
    return mapper.history(unit,material).stream().map(this::decode).toList();
  }

  public void completePublication(String unit,long batchId,String resultJson) {
    MonthlyPriceStorage.requireWriteTransaction(); MonthlyPriceStorage.requireBusinessUnit(unit); codec.validateObject(resultJson);
    if (mapper.completePublication(unit,batchId,codec.wrap(resultJson))!=1) throw new IllegalStateException("STALE_PREVIEW");
  }

  private HouseholdInquiryPriceRow decode(HouseholdInquiryPriceRow stored) {
    // MyBatis may return the same cached row again within this transaction.
    var row=new HouseholdInquiryPriceRow(); BeanUtils.copyProperties(stored,row);
    row.setReferenceJson(codec.unwrap(row.getReferenceJson()));
    PriceFact.household(row.getUnitPrice(),row.getHouseholdRate(),SourceEvidence.ofJson(row.getReferenceJson()));
    return row;
  }
}
