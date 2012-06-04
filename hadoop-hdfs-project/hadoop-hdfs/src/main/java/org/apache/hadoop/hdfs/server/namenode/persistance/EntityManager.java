package org.apache.hadoop.hdfs.server.namenode.persistance;

import java.io.IOException;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.blockmanagement.Replica;
import org.apache.hadoop.hdfs.server.namenode.Lease;
import org.apache.hadoop.hdfs.server.namenode.LeasePath;

/**
 *
 * @author kamal hakimzadeh <kamal@sics.se>
 */
public class EntityManager {

  private static EntityManager instance;

  private EntityManager() {
  }

  public synchronized static EntityManager getInstance() {
    if (instance == null) {
      instance = new EntityManager();
    }

    return instance;
  }
  ThreadLocal<TransactionContext> contexts = new ThreadLocal<TransactionContext>();

  private TransactionContext context() {
    TransactionContext context = contexts.get();

    if (context == null) {
      context = new TransactionContext();
      contexts.set(context);
    }
    return context;
  }

  public void persist(Object o) {
    try {
      context().persist(o);
    } catch (TransactionContextException ex) {
      Logger.getLogger(EntityManager.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

  public void begin() {
    context().begin();
  }

  public void commit() {
    try {
      context().commit();
    } catch (TransactionContextException ex) {
      Logger.getLogger(EntityManager.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

  public void rollback() {
    context().rollback();
  }

  public void remove(Object obj) {
    try {
      context().remove(obj);
    } catch (TransactionContextException ex) {
      Logger.getLogger(EntityManager.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

  public List<Replica> findReplicasByBlockId(long id) {
    try {
      return context().findReplicasByBlockId(id);
    } catch (TransactionContextException ex) {
      Logger.getLogger(EntityManager.class.getName()).log(Level.SEVERE, null, ex);
    }
    return null;
  }

  public List<BlockInfo> findBlocksByInodeId(long id) {
    try {
      try {
        return context().findBlocksByInodeId(id);
      } catch (IOException ex) {
        Logger.getLogger(EntityManager.class.getName()).log(Level.SEVERE, null, ex);
      }
    } catch (TransactionContextException ex) {
      Logger.getLogger(EntityManager.class.getName()).log(Level.SEVERE, null, ex);
    }
    return null;
  }

  public BlockInfo findBlockById(long blockId) throws IOException {
    try {
      return context().findBlockById(blockId);
    } catch (TransactionContextException ex) {
      Logger.getLogger(EntityManager.class.getName()).log(Level.SEVERE, null, ex);
    }
    return null;
  }

  public List<BlockInfo> findAllBlocks() throws IOException {
    try {
      return context().findAllBlocks();
    } catch (TransactionContextException ex) {
      Logger.getLogger(EntityManager.class.getName()).log(Level.SEVERE, null, ex);
    }

    return null;
  }

  public List<BlockInfo> findBlocksByStorageId(String name) throws IOException {
    try {
      return context().findBlocksByStorageId(name);
    } catch (TransactionContextException ex) {
      Logger.getLogger(EntityManager.class.getName()).log(Level.SEVERE, null, ex);
    }

    return null;
  }

  public TreeSet<LeasePath> findLeasePathsByHolder(int holderId) {
    try {
      return context().findLeasePathsByHolderID(holderId);
    } catch (TransactionContextException ex) {
      Logger.getLogger(EntityManager.class.getName()).log(Level.SEVERE, null, ex);
    }

    return null;
  }

  public LeasePath findLeasePathByPath(String path) {
    try {
      return context().findLeasePathByPath(path);
    } catch (TransactionContextException ex) {
      Logger.getLogger(EntityManager.class.getName()).log(Level.SEVERE, null, ex);
    }

    return null;
  }

  public TreeSet<LeasePath> findLeasePathsByPrefix(String prefix) {
    try {
      return context().findLeasePathsByPrefix(prefix);
    } catch (TransactionContextException ex) {
      Logger.getLogger(EntityManager.class.getName()).log(Level.SEVERE, null, ex);
    }

    return null;
  }

  public Lease findLeaseByHolderId(int holderId) {
    try {
      return context().findLeaseByHolderId(holderId);
    } catch (TransactionContextException ex) {
      Logger.getLogger(EntityManager.class.getName()).log(Level.SEVERE, null, ex);
    }

    return null;
  }

  public Lease findLeaseByHolder(String holder) {
    try {
      return context().findLeaseByHolder(holder);
    } catch (TransactionContextException ex) {
      Logger.getLogger(EntityManager.class.getName()).log(Level.SEVERE, null, ex);
    }

    return null;
  }

  /**
   * Finds the hard-limit expired leases. i.e. All leases older than the given time limit.
   * @param timeLimit
   * @return 
   */
  public SortedSet<Lease> findAllExpiredLeases(long timeLimit) {
    try {
      return context().findAllExpiredLeases(timeLimit);
    } catch (TransactionContextException ex) {
      Logger.getLogger(EntityManager.class.getName()).log(Level.SEVERE, null, ex);
    }

    return null;
  }

  public SortedSet<Lease> findAllLeases() {
    try {
      return context().findAllLeases();
    } catch (TransactionContextException ex) {
      Logger.getLogger(EntityManager.class.getName()).log(Level.SEVERE, null, ex);
    }

    return null;
  }
}
