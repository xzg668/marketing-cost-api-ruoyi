package com.sanhua.marketingcost.service.pricing;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Immutable input reads shared only by one pending-list request, never by costing or a later refresh. */
public final class QuotationPreviewReads implements AutoCloseable {
  private static final ThreadLocal<QuotationPreviewReads> CURRENT = new ThreadLocal<>();
  private record Key(Object owner, Object input) {}
  private final ConcurrentHashMap<Key, CompletableFuture<Object>> values = new ConcurrentHashMap<>();

  public <T> T run(Supplier<T> operation) {
    var previous=CURRENT.get();CURRENT.set(this);
    try{return operation.get();}
    finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
  }

  /** Callers must supply a complete query key and a transitively immutable result. Failures are not retained. */
  @SuppressWarnings("unchecked")
  public static <T> T read(Object owner,Object input,Supplier<T> load) {
    var scope=CURRENT.get();
    if(scope==null || !TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly())return load.get();
    var key=new Key(owner,input);var existing=scope.values.get(key);
    if(existing==null) {
      if(scope.values.size()>=2048)return load.get();
      var created=new CompletableFuture<Object>();existing=scope.values.putIfAbsent(key,created);
      if(existing==null) {
        try{T value=load.get();created.complete(value);return value;}
        catch(RuntimeException|Error failure){created.completeExceptionally(failure);scope.values.remove(key,created);throw failure;}
      }
    }
    try{return (T)existing.join();}
    catch(CompletionException failure) {
      if(failure.getCause() instanceof RuntimeException cause)throw cause;
      if(failure.getCause() instanceof Error cause)throw cause;
      throw failure;
    }
  }

  @Override public void close(){values.clear();}
}
