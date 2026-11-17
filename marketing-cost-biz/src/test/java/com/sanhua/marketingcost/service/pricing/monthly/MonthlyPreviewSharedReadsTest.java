package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingSourceReads;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class MonthlyPreviewSharedReadsTest {
  final MonthlyPriceStorageMapper mapper=mock(MonthlyPriceStorageMapper.class);
  final MonthlyPriceStorage storage=new MonthlyPriceStorage(mapper,
      new MonthlyPriceDocumentCodec(new ObjectMapper()),mock(MonthlyPriceLinkMapper.class));
  final MonthlyPriceFamilyKey family=new MonthlyPriceFamilyKey(YearMonth.of(2026,11),"COMMERCIAL","A",null,"KG","CNY",TaxBasis.EXCLUDED,null);
  static <T> T transaction(boolean readOnly,Supplier<T> work) {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
    try{return work.get();}finally{TransactionSynchronizationManager.clear();}
  }
  @Test void oneListReadsSharedMissingFamilyOnceAndNextListReadsFresh() {
    var reads=new TechnicalDataPendingSourceReads();
    try {
      for(int turn=0;turn<2;turn++) {
        var result=reads.map(java.util.stream.IntStream.range(0,24).boxed().toList(),
            item->transaction(true,()->storage.readFamilies(List.of(family))));
        assertThat(result).hasSize(24).allSatisfy(value->assertThat(value).isEmpty());
        verify(mapper,times(turn+1)).pricesByFamilies(eq("COMMERCIAL"),any());
      }
    } finally {reads.close();}
  }
  @Test void outsideListAndWriteTransactionsAlwaysReadFresh() {
    for(int i=0;i<2;i++)transaction(true,()->storage.readFamilies(List.of(family)));
    var reads=new TechnicalDataPendingSourceReads();
    try {reads.map(List.of(1,2),i->transaction(false,()->storage.readFamilies(List.of(family))));}
    finally{reads.close();}
    verify(mapper,times(4)).pricesByFamilies(eq("COMMERCIAL"),any());
  }
  @Test void failedReadIsRetriedAndDoesNotLeakToTheNextRequest() {
    when(mapper.pricesByFamilies(anyString(),any())).thenThrow(new IllegalStateException("source failed")).thenReturn(List.of());
    var reads=new TechnicalDataPendingSourceReads();
    try {
      reads.map(List.of(1),i->transaction(true,()->{
        assertThatThrownBy(()->storage.readFamilies(List.of(family))).hasMessage("source failed");
        return storage.readFamilies(List.of(family));
      }));
      reads.map(List.of(1),i->transaction(true,()->storage.readFamilies(List.of(family))));
      verify(mapper,times(3)).pricesByFamilies(eq("COMMERCIAL"),any());
    } finally{reads.close();}
  }
  @Test void selectedMonthSeparatesOrganizationAndRefreshesOnNextRequest() {
    var months=mock(com.sanhua.marketingcost.mapper.MaterialPriceMonthMapper.class);
    var selector=new com.sanhua.marketingcost.service.pricing.MaterialPriceMonthSelector(months);
    when(months.findLatestMonth("COMMERCIAL","210","2026-11")).thenReturn("2026-10","2026-11");
    when(months.findLatestMonth("COMMERCIAL","220","2026-11")).thenReturn("2026-09");
    var reads=new TechnicalDataPendingSourceReads();
    try {
      for(String expected:List.of("2026-10","2026-11")) {
        reads.map(List.of(1,2,3,4),i->transaction(true,()->{
          assertThat(selector.select("COMMERCIAL","210","2026-11").sourceMonth()).isEqualTo(expected);
          assertThat(selector.select("COMMERCIAL","220","2026-11").sourceMonth()).isEqualTo("2026-09");
          return true;
        }));
      }
      verify(months,times(2)).findLatestMonth("COMMERCIAL","210","2026-11");
      verify(months,times(2)).findLatestMonth("COMMERCIAL","220","2026-11");
    } finally{reads.close();}
  }
}
