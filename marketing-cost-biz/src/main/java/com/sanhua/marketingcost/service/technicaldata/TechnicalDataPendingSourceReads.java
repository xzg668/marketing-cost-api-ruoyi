package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.security.QuoteVisibilityContext;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Independent read-only previews; fixed concurrency, stable row order and request-local authorization. */
@Component
public final class TechnicalDataPendingSourceReads {
  private final ThreadPoolExecutor workers;
  public TechnicalDataPendingSourceReads() {
    var sequence=new AtomicInteger();
    workers=new ThreadPoolExecutor(4,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),work -> {
      var thread=new Thread(work,"technical-source-preview-"+sequence.incrementAndGet());thread.setDaemon(true);return thread;
    },new ThreadPoolExecutor.CallerRunsPolicy());
  }
  public <T,R> List<R> map(List<T> inputs,Function<T,R> read) {
    if(inputs.size()<2) return inputs.stream().map(read).toList();
    var authentication=SecurityContextHolder.getContext().getAuthentication();
    var logging=org.slf4j.MDC.getCopyOfContextMap();
    var pending=new ArrayList<CompletableFuture<R>>();
    for(var input:inputs) {
      var scoped=QuoteVisibilityContext.capture(() -> {
        var previous=SecurityContextHolder.getContext();var previousLog=org.slf4j.MDC.getCopyOfContextMap();
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(authentication);SecurityContextHolder.setContext(context);
        if(logging==null) org.slf4j.MDC.clear();else org.slf4j.MDC.setContextMap(logging);
        try {return read.apply(input);}
        finally {SecurityContextHolder.setContext(previous);if(previousLog==null) org.slf4j.MDC.clear();else org.slf4j.MDC.setContextMap(previousLog);}
      });
      pending.add(CompletableFuture.supplyAsync(scoped,workers));
    }
    // Wait for every preview, including failure paths, before returning the request's results.
    CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).handle((ignored,failure)->null).join();
    return pending.stream().map(CompletableFuture::join).toList();
  }
  @jakarta.annotation.PreDestroy public void close() {workers.shutdown();}
}
