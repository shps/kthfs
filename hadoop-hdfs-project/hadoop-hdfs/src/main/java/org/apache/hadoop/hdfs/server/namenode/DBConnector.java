package org.apache.hadoop.hdfs.server.namenode;

import se.sics.clusterj.DatanodeInfoTable;
import se.sics.clusterj.BlockTotalTable;
import com.mysql.clusterj.ClusterJException;
import se.sics.clusterj.BlockInfoTable;
import se.sics.clusterj.INodeTableSimple;
import se.sics.clusterj.LeasePathsTable;
import se.sics.clusterj.LeaseTable;
import se.sics.clusterj.ReplicaUcTable;
import se.sics.clusterj.TripletsTable;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;

import com.mysql.clusterj.ClusterJHelper;
import com.mysql.clusterj.ClusterJUserException;
import com.mysql.clusterj.Session;
import com.mysql.clusterj.SessionFactory;
import com.mysql.clusterj.Transaction;
import se.sics.clusterj.DelegationKeyTable;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DB_CONNECTOR_STRING_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DB_CONNECTOR_STRING_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DB_DATABASE_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DB_DATABASE_DEFAULT;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DB_NUM_SESSION_FACTORIES;
import org.apache.hadoop.hdfs.server.namenode.persistance.EntityManager;
import se.sics.clusterj.ExcessReplicaTable;
import se.sics.clusterj.InvalidateBlocksTable;
import se.sics.clusterj.PendingReplicationBlockTable;


/*
 * This singleton class serves sessions to the Inode/Block helper classes to
 * talk to the DB.
 *
 * Three design decisions here: 1) Serve one ClusterJ Session per Namenode
 * worker thread, because Sessions are not thread safe. 2) Have a pool of
 * ClusterJ SessionFactory instances to serve the Sessions. This will help work
 * around contention at the ClusterJ internal buffers. 3) Set the connection
 * pool size to be as many as the number of SessionFactory instances. This will
 * allow multiple simultaneous connections to exist, and the read/write locks in
 * FSNamesystem and FSDirectory will make sure this stays safe. *
 */
public class DBConnector { //TODO: [W] the methods and variables in this class should not be static

  private static int NUM_SESSION_FACTORIES;
  static SessionFactory[] sessionFactory;
  static Map<Long, Session> sessionPool = new ConcurrentHashMap<Long, Session>();
  static final Log LOG = LogFactory.getLog(DBConnector.class);
  public static final int RETRY_COUNT = 3;

  public static void setConfiguration(Configuration conf) {
    if (sessionFactory != null) {
      LOG.warn("SessionFactory is already initialized");
      return; //[W] workaround to prevent recreation of SessionFactory for the time being
    }
    NUM_SESSION_FACTORIES = conf.getInt(DFS_DB_NUM_SESSION_FACTORIES, 3);
    sessionFactory = new SessionFactory[NUM_SESSION_FACTORIES];
    LOG.info("Database connect string: " + conf.get(DFS_DB_CONNECTOR_STRING_KEY, DFS_DB_CONNECTOR_STRING_DEFAULT));
    LOG.info("Database name: " + conf.get(DFS_DB_DATABASE_KEY, DFS_DB_DATABASE_DEFAULT));
    for (int i = 0; i < NUM_SESSION_FACTORIES; i++) {
      Properties p = new Properties();
      p.setProperty("com.mysql.clusterj.connectstring", conf.get(DFS_DB_CONNECTOR_STRING_KEY, DFS_DB_CONNECTOR_STRING_DEFAULT));
      p.setProperty("com.mysql.clusterj.database", conf.get(DFS_DB_DATABASE_KEY, DFS_DB_DATABASE_DEFAULT));
      p.setProperty("com.mysql.clusterj.connection.pool.size", String.valueOf(NUM_SESSION_FACTORIES));
      sessionFactory[i] = ClusterJHelper.getSessionFactory(p);
    }
  }

  /*
   * Return a session from a random session factory in our pool.
   *
   * NOTE: Do not close the session returned by this call or you will die.
   */
  public synchronized static Session obtainSession() {
    long threadId = Thread.currentThread().getId();

    if (sessionPool.containsKey(threadId)) {
      return sessionPool.get(threadId);
    } else {
      // Pick a random sessionFactory
      Random r = new Random();
      LOG.info("New session object being obtained for threadId:" + threadId + " name:" + Thread.currentThread().getName());
      Session session = sessionFactory[r.nextInt(NUM_SESSION_FACTORIES)].getSession();
      sessionPool.put(threadId, session);
      return session;
    }
  }

  /**
   * begin a transaction.
   */
  public static void beginTransaction() {
    Session session = obtainSession();
//            session.setLockMode(LockMode.SHARED);
            session.currentTransaction().begin();
            EntityManager.getInstance().begin();
        }
        
        /**
         * Commit a transaction.
         */
        public static void commit() throws ClusterJUserException
        {
            Session session = obtainSession();
            Transaction tx = session.currentTransaction();
            if (!tx.isActive())
                throw new ClusterJUserException("The transaction is not began!");
            
            EntityManager.getInstance().commit();
            tx.commit();
            session.flush();
        }
        
        /**
         * It rolls back only when the transaction is active.
         */
        public static void safeRollback() throws ClusterJUserException
        {
            Session session = obtainSession();
            Transaction tx = session.currentTransaction();
            if (tx.isActive())
            {
                tx.rollback();
            }
            
            EntityManager.getInstance().rollback();
        }
        
        /**
         * This is called only when MiniDFSCluster wants to format the Namenode.
         */
        public static boolean formatDB()
        {
          Session session = obtainSession();
          Transaction tx = session.currentTransaction();
          try
          {
            tx.begin();
            session.deletePersistentAll(INodeTableSimple.class);
            session.deletePersistentAll(BlockInfoTable.class);
            session.deletePersistentAll(LeaseTable.class);
            session.deletePersistentAll(LeasePathsTable.class);
            session.deletePersistentAll(TripletsTable.class);
            session.deletePersistentAll(DelegationKeyTable.class);
            session.deletePersistentAll(ReplicaUcTable.class);
            session.deletePersistentAll(InvalidateBlocksTable.class);
            session.deletePersistentAll(ExcessReplicaTable.class);
            session.deletePersistentAll(PendingReplicationBlockTable.class);
            tx.commit();
            session.flush();
            return true;
          }
          catch(ClusterJException ex)
          {
            LOG.error(ex.getMessage(), ex);
            tx.rollback();
          }

          return false;
        }
        
        public static boolean checkTransactionState(boolean isTransactional)
        {
            Session session = DBConnector.obtainSession();
            boolean isActive = session.currentTransaction().isActive();
            boolean isValid = isActive == isTransactional;
            assert isValid : 
                "Current transaction's isActive value is " + isActive + 
                " but isTransactional's value is " + isTransactional;
            //TODO[Hooman]: An exception should bubble up from here..
            if (!isValid)
                LOG.error("Current transaction's isActive value is " + isActive + 
                " but isTransactional's value is " + isTransactional);
            
            return isValid;
        }
}
