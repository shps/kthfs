
package org.apache.hadoop.hdfs.server.namenode;

import com.mysql.clusterj.ClusterJException;
import com.mysql.clusterj.Query;
import com.mysql.clusterj.Session;
import com.mysql.clusterj.Transaction;
import com.mysql.clusterj.query.QueryBuilder;
import com.mysql.clusterj.query.QueryDomainType;
import java.io.IOException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hdfs.protocol.proto.HdfsProtos.DatanodeInfoProto.AdminState;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor;
import se.sics.clusterj.DatanodeInfoTable;

/** This class provides the CRUD methods for DatanodeInfo table
 *  It also provides helper methods for conversion to/from HDFS data structures to ClusterJ data structures
 */
public class DatanodeHelper {
  private static Log LOG = LogFactory.getLog(DatanodeHelper.class);
  static final int RETRY_COUNT = 3; 
  
  /*
   * Registers a datanode i.e. inserts its entry in the database
   */
  // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  public static void registerDatanode(DatanodeDescriptor datanode, boolean isTransactional) throws IOException
  {
    Session session = DBConnector.obtainSession();
    DBConnector.checkTransactionState(isTransactional);
    
    DatanodeInfoTable dn = session.newInstance(DatanodeInfoTable.class);
    dn.setStorageId(datanode.getStorageID());
    dn.setHostname(datanode.getHostName());
    dn.setInfoPort(datanode.getInfoPort());
    dn.setIpcPort(datanode.getIpcPort());
    dn.setLocalPort(datanode.getPort());
    dn.setLocation(datanode.getNetworkLocation());
    dn.setStatus(datanode.getAdminState().ordinal());
    dn.setHost(datanode.getHost());
    
    if(isTransactional)
    {
      updateDatanodeInfoInternal(session, dn);
    }
    else
    {
      updateDatanodeWithTransaction(session, dn);
    }
  }
  // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  public static void removeDatanode(String storageId, boolean isTransactional)
  {
    Session session = DBConnector.obtainSession();
    DBConnector.checkTransactionState(isTransactional);

    DatanodeInfoTable dn = getDatanodeInternal(storageId);
    if(isTransactional)
    {
      removeDatanodeInternal(session, dn);
    }
    else
    {
      removeDatanodeWithTransaction(session, dn);
    }
  }
  
  // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  private static void removeDatanodeWithTransaction(Session session, DatanodeInfoTable datanode)
  {
    Transaction tx = session.currentTransaction();
    
    int tries = RETRY_COUNT;
    boolean done = false;

    while (done == false && tries > 0) 
    {
      try 
      {
        tx.begin();
        removeDatanodeInternal(session, datanode);
        tx.commit();
        done = true;
      }
      catch (ClusterJException e) 
      {
        tx.rollback();
        LOG.error("removeDatanodeWithTransaction() failed " + e.getMessage(), e);
        tries--;
      }
    }
  }
  // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  private static void updateDatanodeWithTransaction(Session session, DatanodeInfoTable datanode) throws IOException 
  {
    Transaction tx = session.currentTransaction();
    
    int tries = RETRY_COUNT;
    boolean done = false;

    while (done == false && tries > 0) 
    {
      try 
      {
        tx.begin();
        updateDatanodeInfoInternal(session, datanode);
        tx.commit();
        done = true;
      }
      catch (ClusterJException e) 
      {
        tx.rollback();
        LOG.error("updateDatanodeWithTransaction() failed " + e.getMessage(), e);
        tries--;
      }
    }
  }
  
  /*
   * This method is used whenever the name node unregisters or re-registers or changes any state of a datanode
   */
  // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  public static void updateDatanodeInfo(DatanodeDescriptor datanode, boolean isTransactional) throws IOException
  {
    Session session = DBConnector.obtainSession();
    DBConnector.checkTransactionState(isTransactional);
    
    DatanodeInfoTable dn = getDatanodeInternal(datanode.getStorageID());
    
    dn.setHostname(datanode.getHostName());
    dn.setInfoPort(datanode.getInfoPort());
    dn.setIpcPort(datanode.getIpcPort());
    dn.setLocalPort(datanode.getPort());
    dn.setLocation(datanode.getNetworkLocation());
    dn.setStatus(datanode.getAdminState().ordinal());
    dn.setHost(datanode.getHost());
    
    if(isTransactional)
    {
      updateDatanodeInfoInternal(session, dn);
    }
    else
    {
      updateDatanodeWithTransaction(session, dn);
    }
  }
  
  /*
   * Gets the datanode object from database via the storageId
   */
  // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  private static DatanodeInfoTable getDatanodeInternal(String storageId)
  {
    Session session = DBConnector.obtainSession();
    return session.find(DatanodeInfoTable.class, storageId);
  }
  
  /*
   * Gets the datanode descriptor object via storage id
   */
  // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  public static DatanodeDescriptor getDatanodeDescriptorByStorageId(String storageId)
  {
    DatanodeInfoTable dn = getDatanodeInternal(storageId);
    return convertToHDFSDatanod(dn);
  }

  /*
   * Gets the datanode descriptor object via hostname
   */
  // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  public static DatanodeDescriptor getDatanodeDescriptorByHostname(String hostname)
  {
    Session session = DBConnector.obtainSession();
    DatanodeInfoTable dn = getDatanodeByParameter(session,new String [] { "hostname"}, new Object [] {hostname});
    
    return convertToHDFSDatanod(dn);
  }

  /*
   * Gets the datanode descriptor object via name (i.e. hostname:port)
   */
  // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  public static DatanodeDescriptor getDatanodeDescriptorByName(String name)
  {
    Session session = DBConnector.obtainSession();
    DatanodeInfoTable dn = getDatanodeByParameter(session, new String [] {"hostname", "localPort"}, name.split(":"));
    return convertToHDFSDatanod(dn);
  }
  
  /*
   * Converts a DatanodeInfoTable object to HDFS DatanodeDescriptor object
   */
  // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  private static DatanodeDescriptor convertToHDFSDatanod(DatanodeInfoTable dn)
  {
    DatanodeDescriptor datanode = new DatanodeDescriptor();
    datanode.setHostName(dn.getHostname());
    datanode.setInfoPort(dn.getInfoPort());
    datanode.setIpcPort(dn.getIpcPort());
    datanode.setNetworkLocation(dn.getLocation());
    datanode.setStorageID(dn.getStorageId());
    
    // Setting the decomissioning status
    if(dn.getStatus() == AdminState.DECOMMISSIONED.ordinal())
    {
      datanode.setDecommissioned();
    }
    else if(dn.getStatus() == AdminState.DECOMMISSION_INPROGRESS.ordinal())
    {
      datanode.startDecommission();
    }
    // Will also set the local port
     datanode.setName(dn.getHost()+":"+dn.getLocalPort());
     return datanode;
  }
  ///////////////////////////////////////////////////////////////////// 
  /////////////////// Internal functions/////////////////////////////
  ///////////////////////////////////////////////////////////////////// 
 // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  private static void updateDatanodeInfoInternal(Session session, DatanodeInfoTable datanode)
 {
   session.savePersistent(datanode);
 }
  
 // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 private static void removeDatanodeInternal(Session session, DatanodeInfoTable datanode)
 {
   session.deletePersistent(datanode);
 }
  // ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 private static DatanodeInfoTable getDatanodeByParameter(Session session, String [] fields, Object [] values) 
  {
    assert fields.length == values.length;
    
    QueryBuilder qb = session.getQueryBuilder();
    QueryDomainType<DatanodeInfoTable> dobj = qb.createQueryDefinition(DatanodeInfoTable.class);
    
    // Setting the fields
    for(int i=0; i<fields.length; i++)
    {
      dobj.where(dobj.get(fields[i]).equal(dobj.param("param_"+i)));
    }
    
    Query<DatanodeInfoTable> query = session.createQuery(dobj);
    
    // Setting the values
    for(int i=0; i<values.length; i++)
    {
      query.setParameter("param_"+i, values[i]);
    }
    return query.getResultList().get(0);
  }
}
