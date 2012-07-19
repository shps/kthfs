package org.apache.hadoop.hdfs.server.namenode.persistance;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import org.apache.hadoop.hdfs.server.namenode.CounterType;
import org.apache.hadoop.hdfs.server.namenode.FinderType;
import org.apache.hadoop.hdfs.server.namenode.persistance.context.TransactionContext;
import org.apache.hadoop.hdfs.server.namenode.persistance.context.entity.EntityContext;
import org.apache.hadoop.hdfs.server.namenode.persistance.storage.StorageConnector;
import org.apache.hadoop.hdfs.server.namenode.persistance.storage.StorageException;
import org.apache.hadoop.hdfs.server.namenode.persistance.storage.StorageFactory;

/**
 *
 * @author kamal hakimzadeh <kamal@sics.se>
 */
public class EntityManager {

  public static void toBeThrown(IOException ex) {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  public static boolean shouldThrow() {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  public static IOException getException() {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  private EntityManager() {
  }
  private static ThreadLocal<TransactionContext> contexts = new ThreadLocal<TransactionContext>();
  private static StorageConnector connector = StorageFactory.getConnector();

  private static TransactionContext context() {
    TransactionContext context = contexts.get();

    if (context == null) {
      Map<Class, EntityContext> storageMap = StorageFactory.createEntityContexts();
      context = new TransactionContext(connector, storageMap);
      contexts.set(context);
    }
    return context;
  }

  public static void aboutToStart() {
    context().aboutToStart();
  }

  public static boolean shouldRetry() {
    return context().shouldRetry();
  }

  public static void setRollbackOnly() {
    context().setNotSuccessfull();
  }

  public static void setRollbackAndRetry() {
    context().setShouldRetry();
  }

  public static boolean shouldRollback() {
    return context().wasNotSuccessfull();
  }

  public static void begin() {
    context().begin();
  }

  public static void commit() throws StorageException {
    context().commit();
  }

  public static void rollback() {
    context().rollback();
  }

  public static <T> void remove(T obj) throws PersistanceException {
    context().remove(obj);
  }

  public static void removeAll(Class type) throws PersistanceException {
    context().removeAll(type);
  }

  public static <T> Collection<T> findList(FinderType<T> finder, Object... params) throws PersistanceException {
    return context().findList(finder, params);
  }

  public static <T> T find(FinderType<T> finder, Object... params) throws PersistanceException {
    return context().find(finder, params);
  }

  public static int count(CounterType counter, Object... params) throws PersistanceException {
    return context().count(counter, params);
  }

  public static <T> void update(T entity) throws PersistanceException {
    context().update(entity);
  }

  public static <T> void add(T entity) throws PersistanceException {
    context().add(entity);
  }
}
