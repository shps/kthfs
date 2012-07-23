/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.hdfs;

import static org.apache.hadoop.hdfs.server.common.Util.fileAsURI;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.security.PrivilegedExceptionAction;
import java.util.Map.Entry;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.BlockListAsLongs;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.DatanodeReportType;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.StartupOption;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.DataNodeTestUtils;
import org.apache.hadoop.hdfs.server.datanode.DataStorage;
import org.apache.hadoop.hdfs.server.datanode.FSDatasetInterface;
import org.apache.hadoop.hdfs.server.datanode.SimulatedFSDataset;
import org.apache.hadoop.hdfs.server.namenode.*;
import org.apache.hadoop.hdfs.server.namenode.persistance.PersistanceException;
import org.apache.hadoop.hdfs.server.namenode.persistance.TransactionalRequestHandler;
import org.apache.hadoop.hdfs.server.namenode.persistance.storage.StorageConnector;
import org.apache.hadoop.hdfs.server.namenode.persistance.storage.StorageException;
import org.apache.hadoop.hdfs.server.namenode.persistance.storage.StorageFactory;
import org.apache.hadoop.hdfs.server.protocol.DatanodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.DatanodeRegistration;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocols;
import org.apache.hadoop.hdfs.tools.DFSAdmin;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.net.DNSToSwitchMapping;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.net.StaticMapping;
import org.apache.hadoop.security.RefreshUserMappingsProtocol;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.ProxyUsers;
import org.apache.hadoop.security.authorize.RefreshAuthorizationPolicyProtocol;
import org.apache.hadoop.tools.GetUserMappingsProtocol;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.ToolRunner;

/**
 * This class creates a single-process DFS cluster for junit testing. The data
 * directories for non-simulated DFS are under the testing directory. For
 * simulated data nodes, no underlying fs storage is used.
 */
@InterfaceAudience.LimitedPrivate({"HBase", "HDFS", "Hive", "MapReduce", "Pig"})
@InterfaceStability.Unstable
public class MiniDFSCluster {

  private static final String NAMESERVICE_ID_PREFIX = "nameserviceId";
  private static final Log LOG = LogFactory.getLog(MiniDFSCluster.class);
  private static int nnIndex = 0;
  private Configuration clientConf;

  static {
    DefaultMetricsSystem.setMiniClusterMode(true);
  }

  /**
   * Class to construct instances of MiniDFSClusters with specific options.
   */
  public static class Builder {

    private int wNameNodePort = 0;
    private int wNameNodeHttpPort = 0;
    private int rNameNodePort = 0;
    private int rNameNodeHttpPort = 0;
    private final Configuration conf;
    private int numWNameNodes = 1;
    private int numRNameNodes = 0;
    private int numDataNodes = 1;
    private boolean format = true;
    private boolean manageNameDfsDirs = true;
    private boolean manageDataDfsDirs = true;
    private StartupOption option = null;
    private String[] racks = null;
    private String[] hosts = null;
    private long[] simulatedCapacities = null;
    private String clusterId = null;
    private boolean waitSafeMode = true;
    private boolean setupHostsFile = false;
    private boolean federation = false;

    public Builder(Configuration conf) {
      this.conf = conf;
    }

    /**
     * default false - non federated cluster
     *
     * @param val
     * @return Builder object
     */
    public Builder federation(boolean val) {
      this.federation = val;
      return this;
    }

    /**
     * Default: 0
     */
    public Builder wNameNodePort(int val) {
      this.wNameNodePort = val;
      return this;
    }

    /**
     * Default: 0
     */
    public Builder wNameNodeHttpPort(int val) {
      this.wNameNodeHttpPort = val;
      return this;
    }

    /**
     * Default: 0
     */
    public Builder rNameNodePort(int val) {
      this.rNameNodePort = val;
      return this;
    }

    /**
     * Default: 0
     */
    public Builder rNameNodeHttpPort(int val) {
      this.rNameNodeHttpPort = val;
      return this;
    }

    /**
     * Default: 1
     */
    public Builder numWNameNodes(int val) {
      this.numWNameNodes = val;
      return this;
    }

    /**
     * Default: 1
     */
    public Builder numRNameNodes(int val) {
      this.numRNameNodes = val;
      return this;
    }

    /**
     * Default: 1
     */
    public Builder numDataNodes(int val) {
      this.numDataNodes = val;
      return this;
    }

    /**
     * Default: true
     */
    public Builder format(boolean val) {
      this.format = val;
      return this;
    }

    /**
     * Default: true
     */
    public Builder manageNameDfsDirs(boolean val) {
      this.manageNameDfsDirs = val;
      return this;
    }

    /**
     * Default: true
     */
    public Builder manageDataDfsDirs(boolean val) {
      this.manageDataDfsDirs = val;
      return this;
    }

    /**
     * Default: null
     */
    public Builder startupOption(StartupOption val) {
      this.option = val;
      return this;
    }

    /**
     * Default: null
     */
    public Builder racks(String[] val) {
      this.racks = val;
      return this;
    }

    /**
     * Default: null
     */
    public Builder hosts(String[] val) {
      this.hosts = val;
      return this;
    }

    /**
     * Default: null
     */
    public Builder simulatedCapacities(long[] val) {
      this.simulatedCapacities = val;
      return this;
    }

    /**
     * Default: true
     */
    public Builder waitSafeMode(boolean val) {
      this.waitSafeMode = val;
      return this;
    }

    /**
     * Default: null
     */
    public Builder clusterId(String cid) {
      this.clusterId = cid;
      return this;
    }

    /**
     * Default: false When true the hosts file/include file for the cluster is
     * setup
     */
    public Builder setupHostsFile(boolean val) {
      this.setupHostsFile = val;
      return this;
    }

    /**
     * Construct the actual MiniDFSCluster
     */
    public MiniDFSCluster build() throws IOException {
      return new MiniDFSCluster(this);
    }
  }

  /**
   * Used by builder to create and return an instance of MiniDFSCluster
   */
  private MiniDFSCluster(Builder builder) throws IOException {
    LOG.info("starting cluster with " + builder.numWNameNodes + " writing namenodes and " + builder.numRNameNodes + " reading namenodes for each.");
    writingNameNodes = new NameNodeInfo[builder.numWNameNodes];
    readingNameNodes = new HashMap<Integer, NameNodeInfo[]>();
    // try to determine if in federation mode
    if (builder.numWNameNodes > 1) {
      builder.federation = true;
    }

    initMiniDFSCluster(builder.wNameNodePort,
            builder.wNameNodeHttpPort,
            builder.rNameNodePort,
            builder.rNameNodeHttpPort,
            builder.conf,
            builder.numRNameNodes,
            builder.numDataNodes,
            builder.format,
            builder.manageNameDfsDirs,
            builder.manageDataDfsDirs,
            builder.option,
            builder.racks,
            builder.hosts,
            builder.simulatedCapacities,
            builder.clusterId,
            builder.waitSafeMode,
            builder.setupHostsFile,
            builder.federation);
  }

  public class DataNodeProperties {

    DataNode datanode;
    Configuration conf;
    String[] dnArgs;

    DataNodeProperties(DataNode node, Configuration conf, String[] args) {
      this.datanode = node;
      this.conf = conf;
      this.dnArgs = args;
    }
  }
  private Configuration conf;
  private NameNodeInfo[] writingNameNodes;
  private HashMap<Integer, NameNodeInfo[]> readingNameNodes;
  private int numDataNodes;
  private ArrayList<DataNodeProperties> dataNodes =
          new ArrayList<DataNodeProperties>();
  private File base_dir;
  private File data_dir;
  private boolean federation = false;
  private boolean waitSafeMode = true;

  /**
   * Stores the information related to a namenode in the cluster
   */
  static class NameNodeInfo {

    final NameNode nameNode;
    final Configuration conf;

    NameNodeInfo(NameNode nn, Configuration conf) {
      this.nameNode = nn;
      this.conf = conf;
    }
  }

  /**
   * This null constructor is used only when wishing to start a data node
   * cluster without a name node (ie when the name node is started elsewhere).
   */
  public MiniDFSCluster() {
    writingNameNodes = new NameNodeInfo[0]; // No namenode in the cluster
  }

  /**
   * Modify the config and start up the servers with the given operation.
   * Servers will be started on free ports. <p> The caller must manage the
   * creation of NameNode and DataNode directories and have already set {@link DFSConfigKeys#DFS_NAMENODE_NAME_DIR_KEY}
   * and
   * {@link DFSConfigKeys#DFS_DATANODE_DATA_DIR_KEY} in the given conf.
   *
   * @param conf the base configuration to use in starting the servers. This
   * will be modified as necessary.
   * @param numDataNodes Number of DataNodes to start; may be zero
   * @param nameNodeOperation the operation with which to start the servers. If
   * null or StartupOption.FORMAT, then StartupOption.REGULAR will be used.
   */
  @Deprecated // in 22 to be removed in 24. Use MiniDFSCluster.Builder instead
  public MiniDFSCluster(Configuration conf,
          int numRNamenodes,
          int numDataNodes,
          StartupOption nameNodeOperation) throws IOException {
    this(0, 0, conf, numRNamenodes, numDataNodes, false, false, false, nameNodeOperation,
            null, null, null);
  }

  /**
   * Modify the config and start up the servers. The rpc and info ports for
   * servers are guaranteed to use free ports. <p> NameNode and DataNode
   * directory creation and configuration will be managed by this class.
   *
   * @param conf the base configuration to use in starting the servers. This
   * will be modified as necessary.
   * @param numDataNodes Number of DataNodes to start; may be zero
   * @param format if true, format the NameNode and DataNodes before starting up
   * @param racks array of strings indicating the rack that each DataNode is on
   */
  @Deprecated // in 22 to be removed in 24. Use MiniDFSCluster.Builder instead
  public MiniDFSCluster(Configuration conf,
          int numRNamenodes,
          int numDataNodes,
          boolean format,
          String[] racks) throws IOException {
    this(0, 0, conf, numRNamenodes, numDataNodes, format, true, true, null, racks, null, null);
  }

  /**
   * Modify the config and start up the servers. The rpc and info ports for
   * servers are guaranteed to use free ports. <p> NameNode and DataNode
   * directory creation and configuration will be managed by this class.
   *
   * @param conf the base configuration to use in starting the servers. This
   * will be modified as necessary.
   * @param numDataNodes Number of DataNodes to start; may be zero
   * @param format if true, format the NameNode and DataNodes before starting up
   * @param racks array of strings indicating the rack that each DataNode is on
   * @param hosts array of strings indicating the hostname for each DataNode
   */
  @Deprecated // in 22 to be removed in 24. Use MiniDFSCluster.Builder instead
  public MiniDFSCluster(Configuration conf,
          int numRNamenodes,
          int numDataNodes,
          boolean format,
          String[] racks, String[] hosts) throws IOException {
    this(0, 0, conf, numRNamenodes, numDataNodes, format, true, true, null, racks, hosts, null);
  }

  /**
   * NOTE: if possible, the other constructors that don't have nameNode port
   * parameter should be used as they will ensure that the servers use free
   * ports. <p> Modify the config and start up the servers.
   *
   * @param nameNodePort suggestion for which rpc port to use. caller should use
   * getNameNodePort() to get the actual port used.
   * @param conf the base configuration to use in starting the servers. This
   * will be modified as necessary.
   * @param numDataNodes Number of DataNodes to start; may be zero
   * @param format if true, format the NameNode and DataNodes before starting up
   * @param manageDfsDirs if true, the data directories for servers will be
   * created and {@link DFSConfigKeys#DFS_NAMENODE_NAME_DIR_KEY} and
   *          {@link DFSConfigKeys#DFS_DATANODE_DATA_DIR_KEY} will be set in the conf
   * @param operation the operation with which to start the servers. If null or
   * StartupOption.FORMAT, then StartupOption.REGULAR will be used.
   * @param racks array of strings indicating the rack that each DataNode is on
   */
  @Deprecated // in 22 to be removed in 24. Use MiniDFSCluster.Builder instead
  public MiniDFSCluster(int wNameNodePort, int rNameNodePort,
          Configuration conf,
          int numRNamenodes,
          int numDataNodes,
          boolean format,
          boolean manageDfsDirs,
          StartupOption operation,
          String[] racks) throws IOException {
    this(wNameNodePort, rNameNodePort, conf, numRNamenodes, numDataNodes, format, manageDfsDirs, manageDfsDirs,
            operation, racks, null, null);
  }

  /**
   * NOTE: if possible, the other constructors that don't have nameNode port
   * parameter should be used as they will ensure that the servers use free
   * ports. <p> Modify the config and start up the servers.
   *
   * @param nameNodePort suggestion for which rpc port to use. caller should use
   * getNameNodePort() to get the actual port used.
   * @param conf the base configuration to use in starting the servers. This
   * will be modified as necessary.
   * @param numDataNodes Number of DataNodes to start; may be zero
   * @param format if true, format the NameNode and DataNodes before starting up
   * @param manageDfsDirs if true, the data directories for servers will be
   * created and {@link DFSConfigKeys#DFS_NAMENODE_NAME_DIR_KEY} and
   *          {@link DFSConfigKeys#DFS_DATANODE_DATA_DIR_KEY} will be set in the conf
   * @param operation the operation with which to start the servers. If null or
   * StartupOption.FORMAT, then StartupOption.REGULAR will be used.
   * @param racks array of strings indicating the rack that each DataNode is on
   * @param simulatedCapacities array of capacities of the simulated data nodes
   */
  @Deprecated // in 22 to be removed in 24. Use MiniDFSCluster.Builder instead
  public MiniDFSCluster(int wNameNodePort, int rNameNodePort,
          Configuration conf,
          int numRNamenodes,
          int numDataNodes,
          boolean format,
          boolean manageDfsDirs,
          StartupOption operation,
          String[] racks,
          long[] simulatedCapacities) throws IOException {
    this(wNameNodePort, rNameNodePort, conf, numRNamenodes, numDataNodes, format, manageDfsDirs, manageDfsDirs,
            operation, racks, null, simulatedCapacities);
  }

  /**
   * NOTE: if possible, the other constructors that don't have nameNode port
   * parameter should be used as they will ensure that the servers use free
   * ports. <p> Modify the config and start up the servers.
   *
   * @param nameNodePort suggestion for which rpc port to use. caller should use
   * getNameNodePort() to get the actual port used.
   * @param conf the base configuration to use in starting the servers. This
   * will be modified as necessary.
   * @param numDataNodes Number of DataNodes to start; may be zero
   * @param format if true, format the NameNode and DataNodes before starting up
   * @param manageNameDfsDirs if true, the data directories for servers will be
   * created and {@link DFSConfigKeys#DFS_NAMENODE_NAME_DIR_KEY} and
   *          {@link DFSConfigKeys#DFS_DATANODE_DATA_DIR_KEY} will be set in the conf
   * @param manageDataDfsDirs if true, the data directories for datanodes will
   * be created and {@link DFSConfigKeys#DFS_DATANODE_DATA_DIR_KEY} set to same
   * in the conf
   * @param operation the operation with which to start the servers. If null or
   * StartupOption.FORMAT, then StartupOption.REGULAR will be used.
   * @param racks array of strings indicating the rack that each DataNode is on
   * @param hosts array of strings indicating the hostnames of each DataNode
   * @param simulatedCapacities array of capacities of the simulated data nodes
   */
  @Deprecated // in 22 to be removed in 24. Use MiniDFSCluster.Builder instead
  public MiniDFSCluster(int wNameNodePort, int rNameNodePort,
          Configuration conf,
          int numRNamenodes,
          int numDataNodes,
          boolean format,
          boolean manageNameDfsDirs,
          boolean manageDataDfsDirs,
          StartupOption operation,
          String[] racks, String hosts[],
          long[] simulatedCapacities) throws IOException {
    this.writingNameNodes = new NameNodeInfo[1]; // Single namenode in the cluster
    this.readingNameNodes = new HashMap<Integer, NameNodeInfo[]>();
    initMiniDFSCluster(wNameNodePort, 0, rNameNodePort, 0, conf, numRNamenodes, numDataNodes, format,
            manageNameDfsDirs, manageDataDfsDirs, operation, racks, hosts,
            simulatedCapacities, null, true, false, false);
  }

  private void initMiniDFSCluster(int wNameNodePort, int wNameNodeHttpPort,
          int rNameNodePort, int rNameNodeHttpPort,
          Configuration conf,
          int numRNamenodes, int numDataNodes, boolean format, boolean manageNameDfsDirs,
          boolean manageDataDfsDirs, StartupOption operation, String[] racks,
          String[] hosts, long[] simulatedCapacities, String clusterId,
          boolean waitSafeMode, boolean setupHostsFile, boolean federation)
          throws IOException {
    this.conf = conf;
    base_dir = new File(getBaseDirectory());
    data_dir = new File(base_dir, "data");
    this.federation = federation;
    this.waitSafeMode = waitSafeMode;

    // use alternate RPC engine if spec'd
    String rpcEngineName = System.getProperty("hdfs.rpc.engine");
    if (rpcEngineName != null && !"".equals(rpcEngineName)) {

      LOG.info("HDFS using RPCEngine: " + rpcEngineName);
      try {
        Class<?> rpcEngine = conf.getClassByName(rpcEngineName);
        setRpcEngine(conf, NamenodeProtocols.class, rpcEngine);
        setRpcEngine(conf, NamenodeProtocol.class, rpcEngine);
        setRpcEngine(conf, ClientProtocol.class, rpcEngine);
        setRpcEngine(conf, DatanodeProtocol.class, rpcEngine);
        setRpcEngine(conf, RefreshAuthorizationPolicyProtocol.class, rpcEngine);
        setRpcEngine(conf, RefreshUserMappingsProtocol.class, rpcEngine);
        setRpcEngine(conf, GetUserMappingsProtocol.class, rpcEngine);
      } catch (ClassNotFoundException e) {
        throw new RuntimeException(e);
      }

      // disable service authorization, as it does not work with tunnelled RPC
      conf.setBoolean(CommonConfigurationKeys.HADOOP_SECURITY_AUTHORIZATION,
              false);
    }

    int replication = conf.getInt(DFSConfigKeys.DFS_REPLICATION_KEY, 3);
    conf.setInt(DFSConfigKeys.DFS_REPLICATION_KEY, Math.min(replication, numDataNodes));
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_SAFEMODE_EXTENSION_KEY, 0);
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_INTERVAL_KEY, 3); // 3 second
    conf.setClass(DFSConfigKeys.NET_TOPOLOGY_NODE_SWITCH_MAPPING_IMPL_KEY,
            StaticMapping.class, DNSToSwitchMapping.class);

    Collection<String> nameserviceIds = DFSUtil.getNameServiceIds(conf);
    if (nameserviceIds.size() > 1) {
      federation = true;
    }

    if (!federation) {
      /*
       * [thesis] For testing
       */
      if (format) {
        StorageConnector connector = StorageFactory.getConnector();
        connector.setConfiguration(conf);
        try {
          assert (connector.formatStorage());
        } catch (StorageException ex) {
          Logger.getLogger(MiniDFSCluster.class.getName()).log(Level.SEVERE, null, ex);
        }
      }
      //conf.set(DFSConfigKeys.DFS_DB_DATABASE_KEY, "test");
      //conf.set(name, value);

      if (numRNamenodes > 0) {
        createReadingNameNodes(0, numRNamenodes, conf, manageDataDfsDirs, format, operation, clusterId, rNameNodePort);
      }

      conf.set(DFSConfigKeys.FS_DEFAULT_NAME_KEY, "127.0.0.1:" + wNameNodePort);
      conf.set(DFSConfigKeys.DFS_NAMENODE_HTTP_ADDRESS_KEY, "127.0.0.1:"
              + wNameNodeHttpPort);
      NameNode wNN = createWritingNameNode(0, conf, manageNameDfsDirs,
              format, operation, clusterId);
      writingNameNodes[0] = new NameNodeInfo(wNN, conf);
      FileSystem.setDefaultUri(conf, getWritingURI(0).toString());

    } else {
      if (nameserviceIds.isEmpty()) {
        for (int i = 0; i < writingNameNodes.length; i++) {
          nameserviceIds.add(NAMESERVICE_ID_PREFIX + i);
        }
      }
      initFederationConf(conf, nameserviceIds, wNameNodePort);
      createFederationNamenodes(conf, nameserviceIds, manageNameDfsDirs, format,
              StartupOption.READER, clusterId);
    }

    if (format) {
      //DBAdmin.truncateAllTables(conf.get(DFSConfigKeys.DFS_DB_DATABASE_KEY, DFSConfigKeys.DFS_DB_DATABASE_DEFAULT));
      if (data_dir.exists() && !FileUtil.fullyDelete(data_dir)) {
        throw new IOException("Cannot remove data directory: " + data_dir);
      }

    }

    // Start the DataNodes
    startDataNodes(conf, numDataNodes, manageDataDfsDirs, operation, racks,
            hosts, simulatedCapacities, setupHostsFile);
    waitClusterUp();
    //make sure ProxyUsers uses the latest conf
    ProxyUsers.refreshSuperUserGroupsConfiguration(conf);

    // update reader and writer confs so that the DistributedFileSystem is aware of all reader/writer namenodes
    updateClientConfs(conf, 0);
  }

  private void updateClientConfs(Configuration conf, int wIndex) {
    // Getting URI of reader NNs
    String readerNNURIs = "";
    NameNodeInfo[] nnInfos = readingNameNodes.get(wIndex);

    // Handle the case if there are no reader NNs
    if (nnInfos != null && nnInfos.length > 0) {
      for (int i = 0; i < nnInfos.length; i++) {
        readerNNURIs += getReadingURI(wIndex, i).toString() + ",";
      }
      // Remove the last comma
      readerNNURIs = readerNNURIs.substring(0, readerNNURIs.length() - 1);
      LOG.info("ReaderNN  URIs: " + readerNNURIs);
    }

    // Getting URI of writer NNs
    String writerNNURIs = "";
    for (int i = 0; i < writingNameNodes.length; i++) {
      writerNNURIs += getWritingURI(i).toString() + ",";
    }
    writerNNURIs = writerNNURIs.substring(0, writerNNURIs.length() - 1);
    LOG.info("WriterNN  URIs: " + writerNNURIs);


    /*
     * // Setting the configurations for all reader/writer namenodes if (nnInfos
     * != null && nnInfos.length > 0) { for (int i = 0; i < nnInfos.length; i++)
     * { nnInfos[i].conf.set(DFSConfigKeys.DFS_READ_NAMENODES_RPC_ADDRESS_KEY,
     * readerNNURIs);
     * nnInfos[i].conf.set(DFSConfigKeys.DFS_WRITE_NAMENODES_RPC_ADDRESS_KEY,
     * writerNNURIs); } } for (int i = 0; i < writingNameNodes.length; i++) {
     * writingNameNodes[i].conf.set(DFSConfigKeys.DFS_READ_NAMENODES_RPC_ADDRESS_KEY,
     * readerNNURIs);
     * writingNameNodes[i].conf.set(DFSConfigKeys.DFS_WRITE_NAMENODES_RPC_ADDRESS_KEY,
     * writerNNURIs); }
     */
    conf.set(DFSConfigKeys.DFS_READ_NAMENODES_RPC_ADDRESS_KEY, readerNNURIs);
    conf.set(DFSConfigKeys.DFS_WRITE_NAMENODES_RPC_ADDRESS_KEY, writerNNURIs);
    clientConf = conf;
  }

  /**
   * Initialize configuration for federated cluster
   */
  private static void initFederationConf(Configuration conf,
          Collection<String> nameserviceIds, int nnPort) {
    String nameserviceIdList = "";
    for (String nameserviceId : nameserviceIds) {
      // Create comma separated list of nameserviceIds
      if (nameserviceIdList.length() > 0) {
        nameserviceIdList += ",";
      }
      nameserviceIdList += nameserviceId;
      initFederatedNamenodeAddress(conf, nameserviceId, nnPort);
      nnPort = nnPort == 0 ? 0 : nnPort + 2;
    }
    conf.set(DFSConfigKeys.DFS_FEDERATION_NAMESERVICES, nameserviceIdList);
  }

  /*
   * For federated namenode initialize the address:port
   */
  private static void initFederatedNamenodeAddress(Configuration conf,
          String nameserviceId, int nnPort) {
    // Set nameserviceId specific key
    String key = DFSUtil.getNameServiceIdKey(
            DFSConfigKeys.DFS_NAMENODE_HTTP_ADDRESS_KEY, nameserviceId);
    conf.set(key, "127.0.0.1:0");

    key = DFSUtil.getNameServiceIdKey(
            DFSConfigKeys.DFS_NAMENODE_RPC_ADDRESS_KEY, nameserviceId);
    conf.set(key, "127.0.0.1:" + nnPort);
  }

  private void createFederationNamenodes(Configuration conf,
          Collection<String> nameserviceIds, boolean manageNameDfsDirs,
          boolean format, StartupOption operation, String clusterId)
          throws IOException {
    // Create namenodes in the cluster
    int nnCounter = 0;
    for (String nameserviceId : nameserviceIds) {
      createFederatedNameNode(nnCounter++, conf, numDataNodes, manageNameDfsDirs,
              format, operation, clusterId, nameserviceId);
    }
  }

  private NameNode createWritingNameNode(int wNNIndex, Configuration conf,
          boolean manageNameDfsDirs, boolean format,
          StartupOption operation, String clusterId)
          throws IOException {
    if (manageNameDfsDirs) {
      conf.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY,
              fileAsURI(new File(base_dir, "wName" + (2 * wNNIndex + 1))) + ","
              + fileAsURI(new File(base_dir, "wName" + (2 * wNNIndex + 2))));
      conf.set(DFSConfigKeys.DFS_NAMENODE_CHECKPOINT_DIR_KEY,
              fileAsURI(new File(base_dir, "wNamesecondary" + (2 * wNNIndex + 1))) + ","
              + fileAsURI(new File(base_dir, "wNamesecondary" + (2 * wNNIndex + 2))));
    }

    // Format and clean out DataNode directories
    if (format) {
      DFSTestUtil.formatNameNode(conf);
    }
    if (operation == StartupOption.UPGRADE) {
      operation.setClusterId(clusterId);
    }

    // Start the NameNode
    String[] args = (operation == null
            || operation == StartupOption.FORMAT
            || operation == StartupOption.REGULAR)
            ? new String[]{} : new String[]{operation.getName()};
    return NameNode.createNameNode(args, conf);
  }

  private NameNode[] createReadingNameNodes(int wNNIndex, int numRNamenodes, Configuration conf,
          boolean manageNameDfsDirs, boolean format,
          StartupOption operation, String clusterId, int nnPort)
          throws IOException {

    Configuration[] confs = new Configuration[numRNamenodes];

    for (int i = 0; i < numRNamenodes; i++) {
      confs[i] = new Configuration(conf);

      if (manageNameDfsDirs) {
        confs[i].set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY,
                fileAsURI(new File(base_dir, "rName" + (2 * wNNIndex + 1) + "_" + i)) + ","
                + fileAsURI(new File(base_dir, "rName" + (2 * wNNIndex + 2) + "_" + i)));
        confs[i].set(DFSConfigKeys.DFS_NAMENODE_CHECKPOINT_DIR_KEY,
                fileAsURI(new File(base_dir, "rNamesecondary" + (2 * wNNIndex + 1) + "_" + i)) + ","
                + fileAsURI(new File(base_dir, "rNamesecondary" + (2 * wNNIndex + 2) + "_" + i)));
      }

      confs[i].set(DFSConfigKeys.FS_DEFAULT_NAME_KEY, "127.0.0.1:0");
      confs[i].set(DFSConfigKeys.DFS_NAMENODE_HTTP_ADDRESS_KEY, "127.0.0.1:" + nnPort);
      nnPort = nnPort == 0 ? 0 : nnPort + 2;
    }


    // Format and clean out DataNode directories
    if (format) {
      for (int i = 0; i < numRNamenodes; i++) {
        DFSTestUtil.formatNameNode(confs[i]);
      }
    }

    if (operation == StartupOption.UPGRADE) {
      operation.setClusterId(clusterId);
    }

    String[] args = new String[]{StartupOption.READER.getName()};

    NameNode[] namenodes = new NameNode[numRNamenodes];
    final NameNodeInfo[] rnnis = new NameNodeInfo[numRNamenodes];
    readingNameNodes.put(wNNIndex, rnnis);

    for (int i = 0; i < numRNamenodes; i++) {
      namenodes[i] = NameNode.createNameNode(args, confs[i]);
      rnnis[i] = new NameNodeInfo(namenodes[i], confs[i]);
      FileSystem.setDefaultUri(confs[i], getReadingURI(0, i).toString());
    }

    return namenodes;
  }

  private void createFederatedNameNode(int nnIndex, Configuration conf,
          int numDataNodes, boolean manageNameDfsDirs, boolean format,
          StartupOption operation, String clusterId, String nameserviceId)
          throws IOException {
    conf.set(DFSConfigKeys.DFS_FEDERATION_NAMESERVICE_ID, nameserviceId);
    NameNode nn = createWritingNameNode(nnIndex, conf, manageNameDfsDirs,
            format, operation, clusterId);
    conf.set(DFSUtil.getNameServiceIdKey(
            DFSConfigKeys.DFS_NAMENODE_RPC_ADDRESS_KEY, nameserviceId), NameNode.getHostPortString(nn.getNameNodeAddress()));
    conf.set(DFSUtil.getNameServiceIdKey(
            DFSConfigKeys.DFS_NAMENODE_HTTP_ADDRESS_KEY, nameserviceId), NameNode.getHostPortString(nn.getHttpAddress()));
    DFSUtil.setGenericConf(conf, nameserviceId,
            DFSConfigKeys.DFS_NAMENODE_HTTP_ADDRESS_KEY);
    writingNameNodes[nnIndex] = new NameNodeInfo(nn, new Configuration(conf));
  }

  private void setRpcEngine(Configuration conf, Class<?> protocol, Class<?> engine) {
    conf.setClass("rpc.engine." + protocol.getName(), engine, Object.class);
  }

  /**
   * @return URI of the namenode from a single writing namenode MiniDFSCluster
   */
  public URI getWritingURI() {
    checkSingleWNameNode();
    return getWritingURI(0);
  }

  /**
   * @return URI of the given writing namenode in MiniDFSCluster
   */
  public URI getWritingURI(int nnIndex) {
    InetSocketAddress addr = writingNameNodes[nnIndex].nameNode.getNameNodeAddress();
    String hostPort = NameNode.getHostPortString(addr);
    URI uri = null;
    try {
      uri = new URI("hdfs://" + hostPort);
    } catch (URISyntaxException e) {
      NameNode.LOG.warn("unexpected URISyntaxException: " + e);
    }
    return uri;
  }

  /**
   * @return URI of the namenode from a single reading namenode MiniDFSCluster
   */
  public URI getReadingURI() {
    checkSingleRNameNode(0);
    return getReadingURI(0, 0);
  }

  /**
   * @return URI of the given reading namenode in MiniDFSCluster
   */
  public URI getReadingURI(int wIndex, int rIndex) {
    InetSocketAddress addr = readingNameNodes.get(wIndex)[rIndex].nameNode.getNameNodeAddress();
    String hostPort = NameNode.getHostPortString(addr);
    URI uri = null;
    try {
      uri = new URI("hdfs://" + hostPort);
    } catch (URISyntaxException e) {
      NameNode.LOG.warn("unexpected URISyntaxException: " + e);
    }
    return uri;
  }

  /**
   * @return Configuration of for the given namenode
   */
  public Configuration getConfiguration(int nnIndex) {
    return writingNameNodes[nnIndex].conf;
  }

  /**
   * wait for the given namenode to get out of safemode.
   */
  public void waitNameNodeUp(int nnIndex) {
    while (!isNameNodeUp(nnIndex)) {
      try {
        LOG.warn("Waiting for namenode at " + nnIndex + " to start...");
        Thread.sleep(1000);
      } catch (InterruptedException e) {
      }
    }
  }

  /**
   * wait for the cluster to get out of safemode.
   */
  public void waitClusterUp() {
    if (numDataNodes > 0) {
      while (!isClusterUp()) {
        try {
          LOG.warn("Waiting for the Mini HDFS Cluster to start...");
          Thread.sleep(1000);
        } catch (InterruptedException e) {
        }
      }
    }
  }

  /**
   * Modify the config and start up additional DataNodes. The info port for
   * DataNodes is guaranteed to use a free port.
   *
   * Data nodes can run with the name node in the mini cluster or a real name
   * node. For example, running with a real name node is useful when running
   * simulated data nodes with a real name node. If minicluster's name node is
   * null assume that the conf has been set with the right address:port of the
   * name node.
   *
   * @param conf the base configuration to use in starting the DataNodes. This
   * will be modified as necessary.
   * @param numDataNodes Number of DataNodes to start; may be zero
   * @param manageDfsDirs if true, the data directories for DataNodes will be
   * created and {@link DFSConfigKeys#DFS_DATANODE_DATA_DIR_KEY} will be set in
   * the conf
   * @param operation the operation with which to start the DataNodes. If null
   * or StartupOption.FORMAT, then StartupOption.REGULAR will be used.
   * @param racks array of strings indicating the rack that each DataNode is on
   * @param hosts array of strings indicating the hostnames for each DataNode
   * @param simulatedCapacities array of capacities of the simulated data nodes
   *
   * @throws IllegalStateException if NameNode has been shutdown
   */
  public synchronized void startDataNodes(Configuration conf, int numDataNodes,
          boolean manageDfsDirs, StartupOption operation,
          String[] racks, String[] hosts,
          long[] simulatedCapacities) throws IOException {
    startDataNodes(conf, numDataNodes, manageDfsDirs, operation, racks,
            hosts, simulatedCapacities, false);
  }

  /**
   * Modify the config and start up additional DataNodes. The info port for
   * DataNodes is guaranteed to use a free port.
   *
   * Data nodes can run with the name node in the mini cluster or a real name
   * node. For example, running with a real name node is useful when running
   * simulated data nodes with a real name node. If minicluster's name node is
   * null assume that the conf has been set with the right address:port of the
   * name node.
   *
   * @param conf the base configuration to use in starting the DataNodes. This
   * will be modified as necessary.
   * @param numDataNodes Number of DataNodes to start; may be zero
   * @param manageDfsDirs if true, the data directories for DataNodes will be
   * created and {@link DFSConfigKeys#DFS_DATANODE_DATA_DIR_KEY} will be set in
   * the conf
   * @param operation the operation with which to start the DataNodes. If null
   * or StartupOption.FORMAT, then StartupOption.REGULAR will be used.
   * @param racks array of strings indicating the rack that each DataNode is on
   * @param hosts array of strings indicating the hostnames for each DataNode
   * @param simulatedCapacities array of capacities of the simulated data nodes
   * @param setupHostsFile add new nodes to dfs hosts files
   *
   * @throws IllegalStateException if NameNode has been shutdown
   */
  public synchronized void startDataNodes(Configuration conf, int numDataNodes,
          boolean manageDfsDirs, StartupOption operation,
          String[] racks, String[] hosts,
          long[] simulatedCapacities,
          boolean setupHostsFile) throws IOException {
    startDataNodes(conf, numDataNodes, manageDfsDirs, operation, racks, hosts,
            simulatedCapacities, setupHostsFile, false);
  }

  /**
   * Modify the config and start up additional DataNodes. The info port for
   * DataNodes is guaranteed to use a free port.
   *
   * Data nodes can run with the name node in the mini cluster or a real name
   * node. For example, running with a real name node is useful when running
   * simulated data nodes with a real name node. If minicluster's name node is
   * null assume that the conf has been set with the right address:port of the
   * name node.
   *
   * @param conf the base configuration to use in starting the DataNodes. This
   * will be modified as necessary.
   * @param numDataNodes Number of DataNodes to start; may be zero
   * @param manageDfsDirs if true, the data directories for DataNodes will be
   * created and {@link DFSConfigKeys#DFS_DATANODE_DATA_DIR_KEY} will be set in
   * the conf
   * @param operation the operation with which to start the DataNodes. If null
   * or StartupOption.FORMAT, then StartupOption.REGULAR will be used.
   * @param racks array of strings indicating the rack that each DataNode is on
   * @param hosts array of strings indicating the hostnames for each DataNode
   * @param simulatedCapacities array of capacities of the simulated data nodes
   * @param setupHostsFile add new nodes to dfs hosts files
   * @param checkDataNodeAddrConfig if true, only set DataNode port addresses if
   * not already set in config
   *
   * @throws IllegalStateException if NameNode has been shutdown
   */
  public synchronized void startDataNodes(Configuration conf, int numDataNodes,
          boolean manageDfsDirs, StartupOption operation,
          String[] racks, String[] hosts,
          long[] simulatedCapacities,
          boolean setupHostsFile,
          boolean checkDataNodeAddrConfig) throws IOException {
    int curDatanodesNum = dataNodes.size();
    // for mincluster's the default initialDelay for BRs is 0
    if (conf.get(DFSConfigKeys.DFS_BLOCKREPORT_INITIAL_DELAY_KEY) == null) {
      conf.setLong(DFSConfigKeys.DFS_BLOCKREPORT_INITIAL_DELAY_KEY, 0);
    }
    // If minicluster's name node is null assume that the conf has been
    // set with the right address:port of the name node.
    //
    if (racks != null && numDataNodes > racks.length) {
      throw new IllegalArgumentException("The length of racks [" + racks.length
              + "] is less than the number of datanodes [" + numDataNodes + "].");
    }
    if (hosts != null && numDataNodes > hosts.length) {
      throw new IllegalArgumentException("The length of hosts [" + hosts.length
              + "] is less than the number of datanodes [" + numDataNodes + "].");
    }
    //Generate some hostnames if required
    if (racks != null && hosts == null) {
      hosts = new String[numDataNodes];
      for (int i = curDatanodesNum; i < curDatanodesNum + numDataNodes; i++) {
        hosts[i - curDatanodesNum] = "host" + i + ".foo.com";
      }
    }

    if (simulatedCapacities != null
            && numDataNodes > simulatedCapacities.length) {
      throw new IllegalArgumentException("The length of simulatedCapacities ["
              + simulatedCapacities.length
              + "] is less than the number of datanodes [" + numDataNodes + "].");
    }

    String[] dnArgs = (operation == null
            || operation != StartupOption.ROLLBACK)
            ? null : new String[]{operation.getName()};


    for (int i = curDatanodesNum; i < curDatanodesNum + numDataNodes; i++) {
      Configuration dnConf = new HdfsConfiguration(conf);
      // Set up datanode address
      setupDatanodeAddress(dnConf, setupHostsFile, checkDataNodeAddrConfig);
      if (manageDfsDirs) {
        File dir1 = getStorageDir(i, 0);
        File dir2 = getStorageDir(i, 1);
        dir1.mkdirs();
        dir2.mkdirs();
        if (!dir1.isDirectory() || !dir2.isDirectory()) {
          throw new IOException("Mkdirs failed to create directory for DataNode "
                  + i + ": " + dir1 + " or " + dir2);
        }
        String dirs = fileAsURI(dir1) + "," + fileAsURI(dir2);
        dnConf.set(DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY, dirs);
        conf.set(DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY, dirs);
      }
      if (simulatedCapacities != null) {
        dnConf.setBoolean(SimulatedFSDataset.CONFIG_PROPERTY_SIMULATED, true);
        dnConf.setLong(SimulatedFSDataset.CONFIG_PROPERTY_CAPACITY,
                simulatedCapacities[i - curDatanodesNum]);
      }
      LOG.info("Starting DataNode " + i + " with "
              + DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY + ": "
              + dnConf.get(DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY));
      if (hosts != null) {
        dnConf.set(DFSConfigKeys.DFS_DATANODE_HOST_NAME_KEY, hosts[i - curDatanodesNum]);
        LOG.info("Starting DataNode " + i + " with hostname set to: "
                + dnConf.get(DFSConfigKeys.DFS_DATANODE_HOST_NAME_KEY));
      }
      if (racks != null) {
        String name = hosts[i - curDatanodesNum];
        LOG.info("Adding node with hostname : " + name + " to rack "
                + racks[i - curDatanodesNum]);
        StaticMapping.addNodeToRack(name,
                racks[i - curDatanodesNum]);
      }
      Configuration newconf = new HdfsConfiguration(dnConf); // save config
      if (hosts != null) {
        NetUtils.addStaticResolution(hosts[i - curDatanodesNum], "localhost");
      }
      DataNode dn = DataNode.instantiateDataNode(dnArgs, dnConf);
      if (dn == null) {
        throw new IOException("Cannot start DataNode in "
                + dnConf.get(DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY));
      }
      //since the HDFS does things based on IP:port, we need to add the mapping
      //for IP:port to rackId
      String ipAddr = dn.getSelfAddr().getAddress().getHostAddress();
      if (racks != null) {
        int port = dn.getSelfAddr().getPort();
        LOG.info("Adding node with IP:port : " + ipAddr + ":" + port
                + " to rack " + racks[i - curDatanodesNum]);
        StaticMapping.addNodeToRack(ipAddr + ":" + port,
                racks[i - curDatanodesNum]);
      }
      dn.runDatanodeDaemon();
      System.out.println("StartDN: listener-port: " + dn.ipcServer.getListenerAddress().getPort() + ", own-port: " + dn.getIpcPort());
      dataNodes.add(new DataNodeProperties(dn, newconf, dnArgs));
    }
    curDatanodesNum += numDataNodes;
    this.numDataNodes += numDataNodes;
    waitActive();
  }

  /**
   * Modify the config and start up the DataNodes. The info port for DataNodes
   * is guaranteed to use a free port.
   *
   * @param conf the base configuration to use in starting the DataNodes. This
   * will be modified as necessary.
   * @param numDataNodes Number of DataNodes to start; may be zero
   * @param manageDfsDirs if true, the data directories for DataNodes will be
   * created and {@link DFSConfigKeys#DFS_DATANODE_DATA_DIR_KEY} will be set in
   * the conf
   * @param operation the operation with which to start the DataNodes. If null
   * or StartupOption.FORMAT, then StartupOption.REGULAR will be used.
   * @param racks array of strings indicating the rack that each DataNode is on
   *
   * @throws IllegalStateException if NameNode has been shutdown
   */
  public void startDataNodes(Configuration conf, int numDataNodes,
          boolean manageDfsDirs, StartupOption operation,
          String[] racks) throws IOException {
    startDataNodes(conf, numDataNodes, manageDfsDirs, operation, racks, null,
            null, false);
  }

  /**
   * Modify the config and start up additional DataNodes. The info port for
   * DataNodes is guaranteed to use a free port.
   *
   * Data nodes can run with the name node in the mini cluster or a real name
   * node. For example, running with a real name node is useful when running
   * simulated data nodes with a real name node. If minicluster's name node is
   * null assume that the conf has been set with the right address:port of the
   * name node.
   *
   * @param conf the base configuration to use in starting the DataNodes. This
   * will be modified as necessary.
   * @param numDataNodes Number of DataNodes to start; may be zero
   * @param manageDfsDirs if true, the data directories for DataNodes will be
   * created and {@link DFSConfigKeys#DFS_DATANODE_DATA_DIR_KEY} will be set in
   * the conf
   * @param operation the operation with which to start the DataNodes. If null
   * or StartupOption.FORMAT, then StartupOption.REGULAR will be used.
   * @param racks array of strings indicating the rack that each DataNode is on
   * @param simulatedCapacities array of capacities of the simulated data nodes
   *
   * @throws IllegalStateException if NameNode has been shutdown
   */
  public void startDataNodes(Configuration conf, int numDataNodes,
          boolean manageDfsDirs, StartupOption operation,
          String[] racks,
          long[] simulatedCapacities) throws IOException {
    startDataNodes(conf, numDataNodes, manageDfsDirs, operation, racks, null,
            simulatedCapacities, false);

  }

  /**
   * Finalize the namenode. Block pools corresponding to the namenode are
   * finalized on the datanode.
   */
  private void finalizeNamenode(NameNode nn, Configuration conf) throws Exception {
    if (nn == null) {
      throw new IllegalStateException("Attempting to finalize "
              + "Namenode but it is not running");
    }
    ToolRunner.run(new DFSAdmin(conf), new String[]{"-finalizeUpgrade"});
  }

  /**
   * Finalize cluster for the namenode at the given index
   *
   * @see MiniDFSCluster#finalizeCluster(Configuration)
   * @param nnIndex
   * @param conf
   * @throws Exception
   */
  public void finalizeCluster(int nnIndex, Configuration conf) throws Exception {
    finalizeNamenode(writingNameNodes[nnIndex].nameNode, writingNameNodes[nnIndex].conf);
  }

  /**
   * If the NameNode is running, attempt to finalize a previous upgrade. When
   * this method return, the NameNode should be finalized, but DataNodes may not
   * be since that occurs asynchronously.
   *
   * @throws IllegalStateException if the Namenode is not running.
   */
  public void finalizeCluster(Configuration conf) throws Exception {
    for (NameNodeInfo nnInfo : writingNameNodes) {
      if (nnInfo == null) {
        throw new IllegalStateException("Attempting to finalize "
                + "Namenode but it is not running");
      }
      finalizeNamenode(nnInfo.nameNode, nnInfo.conf);
    }
  }

  public int getNumNameNodes() {
    return writingNameNodes.length;
  }

  /**
   * Gets the started NameNode. May be null.
   */
  public NameNode getNameNode() {
    checkSingleWNameNode();
    return getNameNode(0);
  }

  /**
   * Get an instance of the NameNode's RPC handler.
   */
  public NamenodeProtocols getNameNodeRpc() {
    checkSingleWNameNode();
    return getNameNode(0).getRpcServer();
  }

  /**
   * Gets the NameNode for the index. May be null.
   */
  public NameNode getNameNode(int nnIndex) {
    return writingNameNodes[nnIndex].nameNode;
  }

  /**
   * Gets the Reader NameNode for the index. May be null.
   */
  public NameNode getReaderNameNode(int index) {
    return readingNameNodes.get(0)[index].nameNode;
  }

  /**
   * Return the {@link FSNamesystem} object.
   *
   * @return {@link FSNamesystem} object.
   */
  public FSNamesystem getNamesystem() {
    checkSingleWNameNode();
    return NameNodeAdapter.getNamesystem(writingNameNodes[0].nameNode);
  }

  public FSNamesystem getNamesystem(int nnIndex) {
    return NameNodeAdapter.getNamesystem(writingNameNodes[nnIndex].nameNode);
  }

  /**
   * Gets a list of the started DataNodes. May be empty.
   */
  public ArrayList<DataNode> getDataNodes() {
    ArrayList<DataNode> list = new ArrayList<DataNode>();
    for (int i = 0; i < dataNodes.size(); i++) {
      DataNode node = dataNodes.get(i).datanode;
      list.add(node);
    }
    return list;
  }

  /**
   * @return the datanode having the ipc server listen port
   */
  public DataNode getDataNode(int ipcPort) {
    for (DataNode dn : getDataNodes()) {
      System.out.println("ipcPort to find: " + ipcPort + ", dn-ipc-port: " + dn.ipcServer.getListenerAddress().getPort() + ", localPort: " + dn.getDatanodeId().getPort());
      if (dn.ipcServer.getListenerAddress().getPort() == ipcPort) {
        return dn;
      }
    }
    return null;
  }

  /**
   * Gets the rpc port used by the NameNode, because the caller supplied port is
   * not necessarily the actual port used. Assumption: cluster has a single
   * namenode
   */
  public int getNameNodePort() {
    checkSingleWNameNode();
    return getNameNodePort(0);
  }

  /**
   * Gets the rpc port used by the NameNode at the given index, because the
   * caller supplied port is not necessarily the actual port used.
   */
  public int getNameNodePort(int nnIndex) {
    return writingNameNodes[nnIndex].nameNode.getNameNodeAddress().getPort();
  }

  /**
   * Shutdown all the nodes in the cluster.
   */
  public void shutdown() {
    LOG.info("Shutting down the Mini HDFS Cluster");
    shutdownDataNodes();
    for (NameNodeInfo nnInfo : writingNameNodes) {
      NameNode nameNode = nnInfo.nameNode;
      if (nameNode != null) {
        nameNode.stop();
        nameNode.join();
        nameNode = null;
      }
      StorageConnector connector = StorageFactory.getConnector();
      connector.stopStorage();
    }

    final Set<Entry<Integer, NameNodeInfo[]>> entrySet = readingNameNodes.entrySet();

    for (Entry<Integer, NameNodeInfo[]> en : entrySet) {
      for (NameNodeInfo nnInfo : en.getValue()) {
        NameNode nameNode = nnInfo.nameNode;
        if (nameNode != null) {
          nameNode.stop();
          nameNode.join();
          nameNode = null;
        }
      }
    }
  }

  /**
   * Shutdown all DataNodes started by this class. The NameNode is left running
   * so that new DataNodes may be started.
   */
  public void shutdownDataNodes() {
    for (int i = dataNodes.size() - 1; i >= 0; i--) {
      LOG.info("Shutting down DataNode " + i);
      DataNode dn = dataNodes.remove(i).datanode;
      dn.shutdown();
      numDataNodes--;
    }
  }

  /**
   * Shutdown all the namenodes.
   */
  public synchronized void shutdownNameNodes() {
    for (int i = 0; i < writingNameNodes.length; i++) {
      shutdownNameNode(i);
    }
  }

  /**
   * Shutdown the namenode at a given index.
   */
  public synchronized void shutdownNameNode(int nnIndex) {
    NameNode nn = writingNameNodes[nnIndex].nameNode;
    if (nn != null) {
      LOG.info("Shutting down the namenode");
      nn.stop();
      nn.join();
      Configuration conf = writingNameNodes[nnIndex].conf;
      writingNameNodes[nnIndex] = new NameNodeInfo(null, conf);
    }
  }

  /**
   * Restart the namenode.
   */
  public synchronized void restartNameNode() throws IOException {
    checkSingleWNameNode();
    restartNameNode(true);
  }

  /**
   * Restart the namenode. Optionally wait for the cluster to become active.
   */
  public synchronized void restartNameNode(boolean waitActive)
          throws IOException {
    checkSingleWNameNode();
    restartNameNode(0, waitActive);
  }

  /**
   * Restart the namenode at a given index.
   */
  public synchronized void restartNameNode(int nnIndex) throws IOException {
    restartNameNode(nnIndex, true);
  }

  /**
   * Restart the namenode at a given index. Optionally wait for the cluster to
   * become active.
   */
  public synchronized void restartNameNode(int nnIndex, boolean waitActive)
          throws IOException {
    Configuration conf = writingNameNodes[nnIndex].conf;
    shutdownNameNode(nnIndex);
    NameNode nn = NameNode.createNameNode(new String[]{}, conf);
    writingNameNodes[nnIndex] = new NameNodeInfo(nn, conf);
    if (waitActive) {
      waitClusterUp();
      LOG.info("Restarted the namenode");
      waitActive();
      LOG.info("Cluster is active");
    }
  }

  /**
   * Return the contents of the given block on the given datanode.
   *
   * @param block block to be corrupted
   * @throws IOException on error accessing the file for the given block
   */
  public int corruptBlockOnDataNodes(ExtendedBlock block) throws IOException {
    int blocksCorrupted = 0;
    File[] blockFiles = getAllBlockFiles(block);
    for (File f : blockFiles) {
      if (corruptBlock(f)) {
        blocksCorrupted++;
      }
    }
    return blocksCorrupted;
  }

  public String readBlockOnDataNode(int i, ExtendedBlock block)
          throws IOException {
    assert (i >= 0 && i < dataNodes.size()) : "Invalid datanode " + i;
    File blockFile = getBlockFile(i, block);
    if (blockFile != null && blockFile.exists()) {
      return DFSTestUtil.readFile(blockFile);
    }
    return null;
  }

  /**
   * Corrupt a block on a particular datanode.
   *
   * @param i index of the datanode
   * @param blk name of the block
   * @throws IOException on error accessing the given block or if the contents
   * of the block (on the same datanode) differ.
   * @return true if a replica was corrupted, false otherwise Types: delete,
   * write bad data, truncate
   */
  public static boolean corruptReplica(int i, ExtendedBlock blk)
          throws IOException {
    File blockFile = getBlockFile(i, blk);
    return corruptBlock(blockFile);
  }

  /*
   * Corrupt a block on a particular datanode
   */
  public static boolean corruptBlock(File blockFile) throws IOException {
    if (blockFile == null || !blockFile.exists()) {
      return false;
    }
    // Corrupt replica by writing random bytes into replica
    Random random = new Random();
    RandomAccessFile raFile = new RandomAccessFile(blockFile, "rw");
    FileChannel channel = raFile.getChannel();
    String badString = "BADBAD";
    int rand = random.nextInt((int) channel.size() / 2);
    raFile.seek(rand);
    raFile.write(badString.getBytes());
    raFile.close();
    LOG.warn("Corrupting the block " + blockFile);
    return true;
  }

  /*
   * Shutdown a particular datanode
   */
  public synchronized DataNodeProperties stopDataNode(int i) {
    if (i < 0 || i >= dataNodes.size()) {
      return null;
    }
    DataNodeProperties dnprop = dataNodes.remove(i);
    DataNode dn = dnprop.datanode;
    LOG.info("MiniDFSCluster Stopping DataNode "
            + dn.getMachineName()
            + " from a total of " + (dataNodes.size() + 1)
            + " datanodes.");
    dn.shutdown();
    numDataNodes--;
    return dnprop;
  }

  /*
   * Shutdown a datanode by name.
   */
  public synchronized DataNodeProperties stopDataNode(String name) {
    int i;
    for (i = 0; i < dataNodes.size(); i++) {
      DataNode dn = dataNodes.get(i).datanode;
      // get BP registration
      DatanodeRegistration dnR =
              DataNodeTestUtils.getDNRegistrationByMachineName(dn, name);
      LOG.info("for name=" + name + " found bp=" + dnR
              + "; with dnMn=" + dn.getMachineName());
      if (dnR != null) {
        break;
      }
    }
    return stopDataNode(i);
  }

  /**
   * Restart a datanode
   *
   * @param dnprop datanode's property
   * @return true if restarting is successful
   * @throws IOException
   */
  public boolean restartDataNode(DataNodeProperties dnprop) throws IOException {
    return restartDataNode(dnprop, false);
  }

  /**
   * Restart a datanode, on the same port if requested
   *
   * @param dnprop the datanode to restart
   * @param keepPort whether to use the same port
   * @return true if restarting is successful
   * @throws IOException
   */
  public synchronized boolean restartDataNode(DataNodeProperties dnprop,
          boolean keepPort) throws IOException {
    Configuration conf = dnprop.conf;
    String[] args = dnprop.dnArgs;
    Configuration newconf = new HdfsConfiguration(conf); // save cloned config
    if (keepPort) {
      InetSocketAddress addr = dnprop.datanode.getSelfAddr();
      conf.set("dfs.datanode.address", addr.getAddress().getHostAddress() + ":"
              + addr.getPort());
    }
    dataNodes.add(new DataNodeProperties(DataNode.createDataNode(args, conf),
            newconf, args));
    numDataNodes++;
    return true;
  }

  /*
   * Restart a particular datanode, use newly assigned port
   */
  public boolean restartDataNode(int i) throws IOException {
    return restartDataNode(i, false);
  }

  /*
   * Restart a particular datanode, on the same port if keepPort is true
   */
  public synchronized boolean restartDataNode(int i, boolean keepPort)
          throws IOException {
    DataNodeProperties dnprop = stopDataNode(i);
    if (dnprop == null) {
      return false;
    } else {
      return restartDataNode(dnprop, keepPort);
    }
  }

  /*
   * Restart all datanodes, on the same ports if keepPort is true
   */
  public synchronized boolean restartDataNodes(boolean keepPort)
          throws IOException {
    for (int i = dataNodes.size() - 1; i >= 0; i--) {
      if (!restartDataNode(i, keepPort)) {
        return false;
      }
      LOG.info("Restarted DataNode " + i);
    }
    return true;
  }

  /*
   * Restart all datanodes, use newly assigned ports
   */
  public boolean restartDataNodes() throws IOException {
    return restartDataNodes(false);
  }

  /**
   * Returns true if the NameNode is running and is out of Safe Mode or if
   * waiting for safe mode is disabled.
   */
  public boolean isNameNodeUp(int nnIndex) {
    try {
      return (Boolean)isNameNodeUpHandler.handle();
    } catch (IOException ex) {
      LOG.error(ex);
      return false;
    }
  }
  TransactionalRequestHandler isNameNodeUpHandler = new TransactionalRequestHandler() {

    @Override
    public Object performTask() throws PersistanceException, IOException {
      NameNode nameNode = writingNameNodes[nnIndex].nameNode;
      if (nameNode == null) {
        return false;
      }
      long[] sizes;
      try {
        sizes = nameNode.getRpcServer().getStats();
      } catch (IOException ioe) {
        // This method above should never throw.
        // It only throws IOE since it is exposed via RPC
        throw new AssertionError("Unexpected IOE thrown: "
                + StringUtils.stringifyException(ioe));
      }
      boolean isUp = false;
      synchronized (this) {
        isUp = ((!nameNode.isInSafeMode() || !waitSafeMode) && sizes[0] != 0);
      }
      return isUp;
    }
  };

  /**
   * Returns true if all the NameNodes are running and is out of Safe Mode.
   */
  public boolean isClusterUp() {
    for (int index = 0; index < writingNameNodes.length; index++) {
      if (!isNameNodeUp(index)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns true if there is at least one DataNode running.
   */
  public boolean isDataNodeUp() {
    if (dataNodes == null || dataNodes.size() == 0) {
      return false;
    }
    for (DataNodeProperties dn : dataNodes) {
      if (dn.datanode.isDatanodeUp()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Get a client handle to the DFS cluster with a single namenode.
   */
  public FileSystem getWritingFileSystem() throws IOException {
    checkSingleWNameNode();
    return getWritingFileSystem(0);
  }

  /**
   * Get a client handle to the DFS cluster for the namenode at given index.
   */
  public FileSystem getWritingFileSystem(int nnIndex) throws IOException {
    //return FileSystem.get(getWritingURI(nnIndex), writingNameNodes[nnIndex].conf);
    return FileSystem.get(getWritingURI(nnIndex), clientConf);
  }

  /**
   * Get a client handle to the DFS cluster with a single namenode.
   */
  public FileSystem getReadingFileSystem() throws IOException {
    checkSingleRNameNode(0);
    return getReadingFileSystem(0, 0);
  }

  /**
   * Get a client handle to the DFS cluster for the namenode at given index.
   */
  public FileSystem getReadingFileSystem(int wIndex, int rIndex) throws IOException {
    //return FileSystem.get(getReadingURI(wIndex, rIndex), readingNameNodes.get(wIndex)[rIndex].conf);
    return FileSystem.get(getReadingURI(wIndex, rIndex), clientConf);
  }

  //TODO:kamal, extra file system creation
//  /**
//   * Get another FileSystem instance that is different from FileSystem.get(conf).
//   * This simulating different threads working on different FileSystem instances.
//   */
//  public FileSystem getNewFileSystemInstance(int nnIndex) throws IOException {
//    return FileSystem.newInstance(getWritingURI(nnIndex), readingNameNodes[nnIndex].conf);
//  }
  /**
   * @return a http URL
   */
  public String getHttpUri(int nnIndex) throws IOException {
    return "http://"
            + writingNameNodes[nnIndex].conf.get(DFSConfigKeys.DFS_NAMENODE_HTTP_ADDRESS_KEY);
  }

  /**
   * @return a {@link HftpFileSystem} object.
   */
  public HftpFileSystem getHftpFileSystem(int nnIndex) throws IOException {
    String uri = "hftp://"
            + writingNameNodes[nnIndex].conf.get(DFSConfigKeys.DFS_NAMENODE_HTTP_ADDRESS_KEY);
    try {
      return (HftpFileSystem) FileSystem.get(new URI(uri), conf);
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  /**
   * @return a {@link HftpFileSystem} object as specified user.
   */
  public HftpFileSystem getHftpFileSystemAs(final String username,
          final Configuration conf, final int nnIndex, final String... groups)
          throws IOException, InterruptedException {
    final UserGroupInformation ugi = UserGroupInformation.createUserForTesting(
            username, groups);
    return ugi.doAs(new PrivilegedExceptionAction<HftpFileSystem>() {

      @Override
      public HftpFileSystem run() throws Exception {
        return getHftpFileSystem(nnIndex);
      }
    });
  }

  /**
   * Get the directories where the namenode stores its image.
   */
  public Collection<URI> getNameDirs(int nnIndex) {
    return FSNamesystem.getNamespaceDirs(writingNameNodes[nnIndex].conf);
  }

  /**
   * Get the directories where the namenode stores its edits.
   */
  public Collection<URI> getNameEditsDirs(int nnIndex) {
    return FSNamesystem.getNamespaceEditsDirs(writingNameNodes[nnIndex].conf);
  }

  /**
   * Wait until the given namenode gets registration from all the datanodes
   */
  public void waitActive(int nnIndex) throws IOException {
    if (writingNameNodes.length == 0 || writingNameNodes[nnIndex] == null) {
      return;
    }
    InetSocketAddress addr = writingNameNodes[nnIndex].nameNode.getServiceRpcAddress();
    DFSClient client = new DFSClient(addr, conf);

    // ensure all datanodes have registered and sent heartbeat to the namenode
    while (shouldWait(client.datanodeReport(DatanodeReportType.LIVE), addr)) {
      try {
        LOG.info("Waiting for cluster to become active");
        Thread.sleep(100);
      } catch (InterruptedException e) {
      }
    }

    client.close();
  }

  /**
   * Wait until the cluster is active and running.
   */
  public void waitActive() throws IOException {
    for (int index = 0; index < writingNameNodes.length; index++) {
      int failedCount = 0;
      while (true) {
        try {
          waitActive(index);
          break;
        } catch (IOException e) {
          failedCount++;
          // Cached RPC connection to namenode, if any, is expected to fail once
          if (failedCount > 1) {
            LOG.info("Tried waitActive() " + failedCount
                    + " time(s) and failed, giving up.  "
                    + StringUtils.stringifyException(e));
            throw e;
          }
        }
      }
    }
  }

  private synchronized boolean shouldWait(DatanodeInfo[] dnInfo,
          InetSocketAddress addr) {
    // If a datanode failed to start, then do not wait
    for (DataNodeProperties dn : dataNodes) {
      // the datanode thread communicating with the namenode should be alive
      if (!dn.datanode.isBPServiceAlive(addr)) {
        LOG.warn("BPOfferService failed to start in datanode " + dn.datanode
                + " for namenode at " + addr);
        return false;
      }
    }

    // Wait for expected number of datanodes to start
    if (dnInfo.length != numDataNodes) {
      return true;
    }

    // if one of the data nodes is not fully started, continue to wait
    for (DataNodeProperties dn : dataNodes) {
      if (!dn.datanode.isDatanodeFullyStarted()) {
        return true;
      }
    }

    // make sure all datanodes have sent first heartbeat to namenode,
    // using (capacity == 0) as proxy.
    for (DatanodeInfo dn : dnInfo) {
      if (dn.getCapacity() == 0) {
        return true;
      }
    }

    // If datanode dataset is not initialized then wait
    for (DataNodeProperties dn : dataNodes) {
      if (dn.datanode.data == null) {
        return true;
      }
    }
    return false;
  }

  public void formatDataNodeDirs() throws IOException {
    base_dir = new File(getBaseDirectory());
    data_dir = new File(base_dir, "data");
    if (data_dir.exists() && !FileUtil.fullyDelete(data_dir)) {
      throw new IOException("Cannot remove data directory: " + data_dir);
    }
  }

  /**
   *
   * @param dataNodeIndex - data node whose block report is desired - the index
   * is same as for getDataNodes()
   * @return the block report for the specified data node
   */
  public Iterable<Block> getBlockReport(String bpid, int dataNodeIndex) {
    if (dataNodeIndex < 0 || dataNodeIndex > dataNodes.size()) {
      throw new IndexOutOfBoundsException();
    }
    return dataNodes.get(dataNodeIndex).datanode.getFSDataset().getBlockReport(
            bpid);
  }

  /**
   *
   * @return block reports from all data nodes BlockListAsLongs is indexed in
   * the same order as the list of datanodes returned by getDataNodes()
   */
  public Iterable<Block>[] getAllBlockReports(String bpid) {
    int numDataNodes = dataNodes.size();
    Iterable<Block>[] result = new BlockListAsLongs[numDataNodes];
    for (int i = 0; i < numDataNodes; ++i) {
      result[i] = getBlockReport(bpid, i);
    }
    return result;
  }

  /**
   * This method is valid only if the data nodes have simulated data
   *
   * @param dataNodeIndex - data node i which to inject - the index is same as
   * for getDataNodes()
   * @param blocksToInject - the blocks
   * @throws IOException if not simulatedFSDataset if any of blocks already
   * exist in the data node
   *
   */
  public void injectBlocks(int dataNodeIndex, Iterable<Block> blocksToInject) throws IOException {
    if (dataNodeIndex < 0 || dataNodeIndex > dataNodes.size()) {
      throw new IndexOutOfBoundsException();
    }
    FSDatasetInterface dataSet = dataNodes.get(dataNodeIndex).datanode.getFSDataset();
    if (!(dataSet instanceof SimulatedFSDataset)) {
      throw new IOException("injectBlocks is valid only for SimilatedFSDataset");
    }
    String bpid = getNamesystem().getBlockPoolId();
    SimulatedFSDataset sdataset = (SimulatedFSDataset) dataSet;
    sdataset.injectBlocks(bpid, blocksToInject);
    dataNodes.get(dataNodeIndex).datanode.scheduleAllBlockReport(0);
  }

  /**
   * Multiple-NameNode version of {@link #injectBlocks(Iterable[])}.
   */
  public void injectBlocks(int nameNodeIndex, int dataNodeIndex,
          Iterable<Block> blocksToInject) throws IOException {
    if (dataNodeIndex < 0 || dataNodeIndex > dataNodes.size()) {
      throw new IndexOutOfBoundsException();
    }
    FSDatasetInterface dataSet = dataNodes.get(dataNodeIndex).datanode.getFSDataset();
    if (!(dataSet instanceof SimulatedFSDataset)) {
      throw new IOException("injectBlocks is valid only for SimilatedFSDataset");
    }
    String bpid = getNamesystem(nameNodeIndex).getBlockPoolId();
    SimulatedFSDataset sdataset = (SimulatedFSDataset) dataSet;
    sdataset.injectBlocks(bpid, blocksToInject);
    dataNodes.get(dataNodeIndex).datanode.scheduleAllBlockReport(0);
  }

  /**
   * This method is valid only if the data nodes have simulated data
   *
   * @param blocksToInject - blocksToInject[] is indexed in the same order as
   * the list of datanodes returned by getDataNodes()
   * @throws IOException if not simulatedFSDataset if any of blocks already
   * exist in the data nodes Note the rest of the blocks are not injected.
   */
  public void injectBlocks(Iterable<Block>[] blocksToInject)
          throws IOException {
    if (blocksToInject.length > dataNodes.size()) {
      throw new IndexOutOfBoundsException();
    }
    for (int i = 0; i < blocksToInject.length; ++i) {
      injectBlocks(i, blocksToInject[i]);
    }
  }

  /**
   * Set the softLimit and hardLimit of client lease periods
   */
  public void setLeasePeriod(long soft, long hard) {
    NameNodeAdapter.setLeasePeriod(getNamesystem(), soft, hard);
  }

  /**
   * Returns the current set of datanodes
   */
  DataNode[] listDataNodes() {
    DataNode[] list = new DataNode[dataNodes.size()];
    for (int i = 0; i < dataNodes.size(); i++) {
      list[i] = dataNodes.get(i).datanode;
    }
    return list;
  }

  /**
   * Access to the data directory used for Datanodes
   */
  public String getDataDirectory() {
    return data_dir.getAbsolutePath();
  }

  public static String getBaseDirectory() {
    return System.getProperty("test.build.data", "build/test/data") + "/dfs/";
  }

  /**
   * Get a storage directory for a datanode. There are two storage directories
   * per datanode: <ol> <li><base directory>/data/data<2*dnIndex + 1></li>
   * <li><base directory>/data/data<2*dnIndex + 2></li> </ol>
   *
   * @param dnIndex datanode index (starts from 0)
   * @param dirIndex directory index (0 or 1). Index 0 provides access to the
   * first storage directory. Index 1 provides access to the second storage
   * directory.
   * @return Storage directory
   */
  public static File getStorageDir(int dnIndex, int dirIndex) {
    return new File(getBaseDirectory() + "data/data" + (2 * dnIndex + 1 + dirIndex));
  }

  /**
   * Get current directory corresponding to the datanode
   *
   * @param storageDir
   * @return current directory
   */
  public static String getDNCurrentDir(File storageDir) {
    return storageDir + "/" + Storage.STORAGE_DIR_CURRENT + "/";
  }

  /**
   * Get directory corresponding to block pool directory in the datanode
   *
   * @param storageDir
   * @return current directory
   */
  public static String getBPDir(File storageDir, String bpid) {
    return getDNCurrentDir(storageDir) + bpid + "/";
  }

  /**
   * Get directory relative to block pool directory in the datanode
   *
   * @param storageDir
   * @return current directory
   */
  public static String getBPDir(File storageDir, String bpid, String dirName) {
    return getBPDir(storageDir, bpid) + dirName + "/";
  }

  /**
   * Get finalized directory for a block pool
   *
   * @param storageDir storage directory
   * @param bpid Block pool Id
   * @return finalized directory for a block pool
   */
  public static File getRbwDir(File storageDir, String bpid) {
    return new File(getBPDir(storageDir, bpid, Storage.STORAGE_DIR_CURRENT)
            + DataStorage.STORAGE_DIR_RBW);
  }

  /**
   * Get finalized directory for a block pool
   *
   * @param storageDir storage directory
   * @param bpid Block pool Id
   * @return finalized directory for a block pool
   */
  public static File getFinalizedDir(File storageDir, String bpid) {
    return new File(getBPDir(storageDir, bpid, Storage.STORAGE_DIR_CURRENT)
            + DataStorage.STORAGE_DIR_FINALIZED);
  }

  /**
   * Get file correpsonding to a block
   *
   * @param storageDir storage directory
   * @param blk block to be corrupted
   * @return file corresponding to the block
   */
  public static File getBlockFile(File storageDir, ExtendedBlock blk) {
    return new File(getFinalizedDir(storageDir, blk.getBlockPoolId()),
            blk.getBlockName());
  }

  /**
   * Get all files related to a block from all the datanodes
   *
   * @param block block for which corresponding files are needed
   */
  public File[] getAllBlockFiles(ExtendedBlock block) {
    if (dataNodes.size() == 0) {
      return new File[0];
    }
    ArrayList<File> list = new ArrayList<File>();
    for (int i = 0; i < dataNodes.size(); i++) {
      File blockFile = getBlockFile(i, block);
      if (blockFile != null) {
        list.add(blockFile);
      }
    }
    return list.toArray(new File[list.size()]);
  }

  /**
   * Get files related to a block for a given datanode
   *
   * @param dnIndex Index of the datanode to get block files for
   * @param block block for which corresponding files are needed
   */
  public static File getBlockFile(int dnIndex, ExtendedBlock block) {
    // Check for block file in the two storage directories of the datanode
    for (int i = 0; i <= 1; i++) {
      File storageDir = MiniDFSCluster.getStorageDir(dnIndex, i);
      File blockFile = getBlockFile(storageDir, block);
      if (blockFile.exists()) {
        return blockFile;
      }
    }
    return null;
  }

  /**
   * Throw an exception if the MiniDFSCluster is not started with a single
   * writing namenode
   */
  private void checkSingleWNameNode() {
    if (writingNameNodes.length != 1) {
      throw new IllegalArgumentException("WritingNamenode index is needed");
    }
  }

  /**
   * Throw an exception if the MiniDFSCluster is not started with a single
   * reading namenode
   */
  private void checkSingleRNameNode(int wIndex) {
    if (readingNameNodes.get(wIndex).length != 1) {
      throw new IllegalArgumentException("ReadingNamenode index is needed");
    }
  }

  /**
   * Add a namenode to a federated cluster and start it. Configuration of
   * datanodes in the cluster is refreshed to register with the new namenode.
   *
   * @return newly started namenode
   */
  public NameNode addNameNode(Configuration conf, int namenodePort)
          throws IOException {
    if (!federation) {
      throw new IOException("cannot add namenode to non-federated cluster");
    }

    int nnIndex = writingNameNodes.length;
    int numNameNodes = writingNameNodes.length + 1;
    NameNodeInfo[] newlist = new NameNodeInfo[numNameNodes];
    System.arraycopy(writingNameNodes, 0, newlist, 0, writingNameNodes.length);
    writingNameNodes = newlist;
    String nameserviceId = NAMESERVICE_ID_PREFIX + (nnIndex + 1);

    String nameserviceIds = conf.get(DFSConfigKeys.DFS_FEDERATION_NAMESERVICES);
    nameserviceIds += "," + nameserviceId;
    conf.set(DFSConfigKeys.DFS_FEDERATION_NAMESERVICES, nameserviceIds);

    initFederatedNamenodeAddress(conf, nameserviceId, namenodePort);
    createFederatedNameNode(nnIndex, conf, numDataNodes, true, true, null,
            null, nameserviceId);

    // Refresh datanodes with the newly started namenode
    for (DataNodeProperties dn : dataNodes) {
      DataNode datanode = dn.datanode;
      datanode.refreshNamenodes(conf);
    }

    // Wait for new namenode to get registrations from all the datanodes
    waitActive(nnIndex);
    return writingNameNodes[nnIndex].nameNode;
  }

  private int getFreeSocketPort() {
    int port = 0;
    try {
      ServerSocket s = new ServerSocket(0);
      port = s.getLocalPort();
      s.close();
      return port;
    } catch (IOException e) {
      // Could not get a free port. Return default port 0.
    }
    return port;
  }

  private void setupDatanodeAddress(Configuration conf, boolean setupHostsFile,
          boolean checkDataNodeAddrConfig) throws IOException {
    if (setupHostsFile) {
      String hostsFile = conf.get(DFSConfigKeys.DFS_HOSTS, "").trim();
      if (hostsFile.length() == 0) {
        throw new IOException("Parameter dfs.hosts is not setup in conf");
      }
      // Setup datanode in the include file, if it is defined in the conf
      String address = "127.0.0.1:" + getFreeSocketPort();
      if (checkDataNodeAddrConfig) {
        conf.setIfUnset("dfs.datanode.address", address);
      } else {
        conf.set("dfs.datanode.address", address);
      }
      addToFile(hostsFile, address);
      LOG.info("Adding datanode " + address + " to hosts file " + hostsFile);
    } else {
      if (checkDataNodeAddrConfig) {
        conf.setIfUnset("dfs.datanode.address", "127.0.0.1:0");
        conf.setIfUnset("dfs.datanode.http.address", "127.0.0.1:0");
        conf.setIfUnset("dfs.datanode.ipc.address", "127.0.0.1:0");
      } else {
        conf.set("dfs.datanode.address", "127.0.0.1:0");
        conf.set("dfs.datanode.http.address", "127.0.0.1:0");
        conf.set("dfs.datanode.ipc.address", "127.0.0.1:0");
      }
    }
  }

  private void addToFile(String p, String address) throws IOException {
    File f = new File(p);
    f.createNewFile();
    PrintWriter writer = new PrintWriter(new FileWriter(f, true));
    try {
      writer.println(address);
    } finally {
      writer.close();
    }
  }

  public Configuration getClientConf() {
    return clientConf;
  }
}
