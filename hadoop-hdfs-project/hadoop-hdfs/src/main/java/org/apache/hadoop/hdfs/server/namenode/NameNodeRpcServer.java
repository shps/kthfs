/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import static org.apache.hadoop.hdfs.protocol.HdfsConstants.MAX_PATH_DEPTH;
import static org.apache.hadoop.hdfs.protocol.HdfsConstants.MAX_PATH_LENGTH;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;

import java.util.Iterator;
import java.util.SortedMap;
import org.apache.commons.logging.Log;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FsServerDefaults;
import org.apache.hadoop.fs.Options;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.UnresolvedLinkException;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.permission.PermissionStatus;
import static org.apache.hadoop.hdfs.DFSConfigKeys.*;
import org.apache.hadoop.hdfs.HDFSPolicyProvider;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.protocol.BlockListAsLongs;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.CorruptFileBlocks;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.DirectoryListing;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.protocol.UnregisteredNodeException;
import org.apache.hadoop.hdfs.protocol.UnresolvedPathException;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.DatanodeReportType;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.UpgradeAction;
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenIdentifier;
import org.apache.hadoop.hdfs.server.common.IncorrectVersionException;
import org.apache.hadoop.hdfs.server.common.UpgradeStatusReport;
import org.apache.hadoop.hdfs.server.namenode.lock.TransactionLockManager;
import org.apache.hadoop.hdfs.server.namenode.metrics.NameNodeMetrics;
import org.apache.hadoop.hdfs.server.namenode.persistance.PersistanceException;
import org.apache.hadoop.hdfs.server.namenode.persistance.RequestHandler.OperationType;
import org.apache.hadoop.hdfs.server.namenode.persistance.TransactionalRequestHandler;
import org.apache.hadoop.hdfs.server.protocol.ActiveNamenodeList;
import org.apache.hadoop.hdfs.server.protocol.DatanodeCommand;
import org.apache.hadoop.hdfs.server.protocol.DatanodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.DatanodeRegistration;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocols;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.hdfs.server.protocol.NodeRegistration;
import org.apache.hadoop.hdfs.server.protocol.ReceivedDeletedBlockInfo;
import org.apache.hadoop.hdfs.server.protocol.UpgradeCommand;
import org.apache.hadoop.io.EnumSetWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.ProtocolSignature;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.Groups;
import org.apache.hadoop.security.RefreshUserMappingsProtocol;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.AuthorizationException;
import org.apache.hadoop.security.authorize.ProxyUsers;
import org.apache.hadoop.security.authorize.RefreshAuthorizationPolicyProtocol;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.SecretManager.InvalidToken;
import org.apache.hadoop.tools.GetUserMappingsProtocol;
/**
 * This class is responsible for handling all of the RPC calls to the NameNode.
 * It is created, started, and stopped by {@link NameNode}.
 */
class NameNodeRpcServer implements NamenodeProtocols {
  
  private static final Log LOG = NameNode.LOG;
  private static final Log stateChangeLog = NameNode.stateChangeLog;
  
  // Dependencies from other parts of NN.
  private final FSNamesystem namesystem;
  protected final NameNode nn;
  private final NameNodeMetrics metrics;
  
  private final boolean serviceAuthEnabled;

  /** The RPC server that listens to requests from DataNodes */
  private final RPC.Server serviceRpcServer;
  private final InetSocketAddress serviceRPCAddress;
  
  /** The RPC server that listens to requests from clients */
  protected final RPC.Server server;
  protected final InetSocketAddress rpcAddress;
  
  /** Namenode counter that indicates the next namenode to send a block report to */
  protected volatile int nnIndex = 0;
  
  public NameNodeRpcServer(Configuration conf, NameNode nn)
      throws IOException {
    this.nn = nn;
    this.namesystem = nn.getNamesystem();
    this.metrics = NameNode.getNameNodeMetrics();
    
    int handlerCount = 
      conf.getInt(DFS_DATANODE_HANDLER_COUNT_KEY, 
                  DFS_DATANODE_HANDLER_COUNT_DEFAULT);
    InetSocketAddress socAddr = nn.getRpcServerAddress(conf);

    InetSocketAddress dnSocketAddr = nn.getServiceRpcServerAddress(conf);
    if (dnSocketAddr != null) {
      int serviceHandlerCount =
        conf.getInt(DFS_NAMENODE_SERVICE_HANDLER_COUNT_KEY,
                    DFS_NAMENODE_SERVICE_HANDLER_COUNT_DEFAULT);
      this.serviceRpcServer = RPC.getServer(NamenodeProtocols.class, this,
          dnSocketAddr.getHostName(), dnSocketAddr.getPort(), serviceHandlerCount,
          false, conf, namesystem.getDelegationTokenSecretManager());
      this.serviceRPCAddress = this.serviceRpcServer.getListenerAddress();
      nn.setRpcServiceServerAddress(conf, serviceRPCAddress);
    } else {
      serviceRpcServer = null;
      serviceRPCAddress = null;
    }
    this.server = RPC.getServer(NamenodeProtocols.class, this,
                                socAddr.getHostName(), socAddr.getPort(),
                                handlerCount, false, conf, 
                                namesystem.getDelegationTokenSecretManager());

    // set service-level authorization security policy
    if (serviceAuthEnabled =
          conf.getBoolean(
            CommonConfigurationKeys.HADOOP_SECURITY_AUTHORIZATION, false)) {
      this.server.refreshServiceAcl(conf, new HDFSPolicyProvider());
      if (this.serviceRpcServer != null) {
        this.serviceRpcServer.refreshServiceAcl(conf, new HDFSPolicyProvider());
      }
    }

    // The rpc-server port can be ephemeral... ensure we have the correct info
    this.rpcAddress = this.server.getListenerAddress(); 
    nn.setRpcServerAddress(conf, rpcAddress);
  }
  
  /**
   * Actually start serving requests.
   */
  void start() {
    server.start();  //start RPC server
    if (serviceRpcServer != null) {
      serviceRpcServer.start();      
    }
  }
  
  /**
   * Wait until the RPC server has shut down.
   */
  void join() throws InterruptedException {
    this.server.join();
  }
  
  void stop() {
    if(server != null) server.stop();
    if(serviceRpcServer != null) serviceRpcServer.stop();
  }
  
  InetSocketAddress getServiceRpcAddress() {
    return serviceRPCAddress;
  }

  InetSocketAddress getRpcAddress() {
    return rpcAddress;
  }
  
  @Override // VersionedProtocol
  public ProtocolSignature getProtocolSignature(String protocol,
      long clientVersion, int clientMethodsHash) throws IOException {
    return ProtocolSignature.getProtocolSignature(
        this, protocol, clientVersion, clientMethodsHash);
  }
  
  @Override
  public long getProtocolVersion(String protocol, 
                                 long clientVersion) throws IOException {
    if (protocol.equals(ClientProtocol.class.getName())) {
      return ClientProtocol.versionID; 
    } else if (protocol.equals(DatanodeProtocol.class.getName())){
      return DatanodeProtocol.versionID;    
    } else if (protocol.equals(RefreshAuthorizationPolicyProtocol.class.getName())){
      return RefreshAuthorizationPolicyProtocol.versionID;
    } else if (protocol.equals(RefreshUserMappingsProtocol.class.getName())){
      return RefreshUserMappingsProtocol.versionID;
    } else if (protocol.equals(GetUserMappingsProtocol.class.getName())){
      return GetUserMappingsProtocol.versionID;
    } else {
      throw new IOException("Unknown protocol to name node: " + protocol);
    }
  }
  @Override // ClientProtocol
  public Token<DelegationTokenIdentifier> getDelegationToken(Text renewer)
      throws IOException {
    return namesystem.getDelegationToken(renewer);
  }
  
  @Override // ClientProtocol
  public long renewDelegationToken(Token<DelegationTokenIdentifier> token)
      throws InvalidToken, IOException {
    return namesystem.renewDelegationToken(token);
  }

@Override // ClientProtocol
  public void cancelDelegationToken(Token<DelegationTokenIdentifier> token)
      throws IOException {
    namesystem.cancelDelegationToken(token);
  }
  
  @Override // ClientProtocol
  public LocatedBlocks getBlockLocations(String src, 
                                          long offset, 
                                          long length) 
      throws IOException {
    metrics.incrGetBlockLocations();
    LocatedBlocks locatedBlocks = namesystem.getBlockLocations(src, offset, length, true, true);
    LOG.info(this.getRpcAddress()+" getBlockLocation called.");
    return locatedBlocks;
  }
  
  @Override // ClientProtocol
  public FsServerDefaults getServerDefaults() throws IOException {
    return namesystem.getServerDefaults();
  }

  @Override // ClientProtocol
  public void create(String src, 
                     FsPermission masked,
                     String clientName, 
                     EnumSetWritable<CreateFlag> flag,
                     boolean createParent,
                     short replication,
                     long blockSize) throws IOException {
    String clientMachine = getClientMachine();
    if (stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug(this.nn.getId()+") "
              + "*DIR* NameNode.create: file "
                         +src+" for "+clientName+" at "+clientMachine);
    }
    if (!checkPathLength(src)) {
      throw new IOException("create: Pathname too long.  Limit "
          + MAX_PATH_LENGTH + " characters, " + MAX_PATH_DEPTH + " levels.");
    }
    namesystem.startFile(src,
        new PermissionStatus(UserGroupInformation.getCurrentUser().getShortUserName(),
            null, masked),
        clientName, clientMachine, flag.get(), createParent, replication, blockSize);
    metrics.incrFilesCreated();
    metrics.incrCreateFileOps();
  }

  @Override // ClientProtocol
  public LocatedBlock append(String src, String clientName) 
      throws IOException {
    String clientMachine = getClientMachine();
    if (stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*DIR* NameNode.append: file "
          +src+" for "+clientName+" at "+clientMachine);
    }
    LocatedBlock info = namesystem.appendFile(src, clientName, clientMachine);
    metrics.incrFilesAppended();
    return info;
  }

  @Override // ClientProtocol
  public boolean recoverLease(String src, String clientName) throws IOException {
    String clientMachine = getClientMachine();
    return namesystem.recoverLease(src, clientName, clientMachine);
  }

  @Override // ClientProtocol
  public boolean setReplication(String src, short replication) 
    throws IOException {  
    return namesystem.setReplication(src, replication);
  }
    
  @Override // ClientProtocol
  public void setPermission(String src, FsPermission permissions)
      throws IOException {
    namesystem.setPermission(src, permissions);
  }

  @Override // ClientProtocol
  public void setOwner(String src, String username, String groupname)
      throws IOException {
    namesystem.setOwner(src, username, groupname);
  }

  @Override // ClientProtocol
  public LocatedBlock addBlock(String src,
                               String clientName,
                               ExtendedBlock previous,
                               DatanodeInfo[] excludedNodes)
      throws IOException {
    if(stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*BLOCK* NameNode.addBlock: file "
          +src+" for "+clientName);
    }
    HashMap<Node, Node> excludedNodesSet = null;
    if (excludedNodes != null) {
      excludedNodesSet = new HashMap<Node, Node>(excludedNodes.length);
      for (Node node:excludedNodes) {
        excludedNodesSet.put(node, node);
      }
    }
    LocatedBlock locatedBlock = 
      namesystem.getAdditionalBlockWithTransaction(src, clientName, previous, excludedNodesSet);
    if (locatedBlock != null) {
      metrics.incrAddBlockOps();
      LOG.debug("addBlock after B:" + previous  + " succeed,  " + locatedBlock.toString() + " Excludeds:" + excludedNodes);
    } else {
      LOG.debug("addBlock after B:" + previous  + " failed.");
    }
    return locatedBlock;
  }

  @Override // ClientProtocol
  public LocatedBlock getAdditionalDatanode(final String src, final ExtendedBlock blk,
      final DatanodeInfo[] existings, final DatanodeInfo[] excludes,
      final int numAdditionalNodes, final String clientName
      ) throws IOException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("getAdditionalDatanode: src=" + src
          + ", blk=" + blk
          + ", existings=" + Arrays.asList(existings)
          + ", excludes=" + Arrays.asList(excludes)
          + ", numAdditionalNodes=" + numAdditionalNodes
          + ", clientName=" + clientName);
    }

    metrics.incrGetAdditionalDatanodeOps();

    HashMap<Node, Node> excludeSet = null;
    if (excludes != null) {
      excludeSet = new HashMap<Node, Node>(excludes.length);
      for (Node node : excludes) {
        excludeSet.put(node, node);
      }
    }
    return namesystem.getAdditionalDatanode(src, blk,
        existings, excludeSet, numAdditionalNodes, clientName);
  }

  /**
   * The client needs to give up on the block.
   */
  public void abandonBlock(ExtendedBlock b, String src, String holder)
      throws IOException {
    if(stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*BLOCK* NameNode.abandonBlock: "
          +b+" of file "+src);
    }
    if (!namesystem.abandonBlock(b, src, holder)) {
      throw new IOException("Cannot abandon block during write to " + src);
    }
  }

  @Override // ClientProtocol
  public boolean complete(String src, String clientName, ExtendedBlock last)
      throws IOException {
    if(stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*DIR* NameNode.complete: "
          + src + " for " + clientName);
    }
    boolean succeed = namesystem.completeFile(src, clientName, last);
    
    if (succeed)
      LOG.debug("complete for " + src + " was succeed");
    else
      LOG.debug("complete for " + src + " was failed");
    
    return succeed;
  }

  /**
   * The client has detected an error on the specified located blocks 
   * and is reporting them to the server.  For now, the namenode will 
   * mark the block as corrupt.  In the future we might 
   * check the blocks are actually corrupt. 
   */
  @Override
  public void reportBadBlocks(LocatedBlock[] blocks) throws IOException {
    stateChangeLog.info("*DIR* NameNode.reportBadBlocks");
    for (int i = 0; i < blocks.length; i++) {
      ExtendedBlock blk = blocks[i].getBlock();
      DatanodeInfo[] nodes = blocks[i].getLocations();
      for (int j = 0; j < nodes.length; j++) {
        DatanodeInfo dn = nodes[j];
        namesystem.getBlockManager().findAndMarkBlockAsCorrupt(blk, dn);
      }
    }
  }

  @Override // ClientProtocol
  public LocatedBlock updateBlockForPipeline(ExtendedBlock block, String clientName)
      throws IOException {
    return namesystem.updateBlockForPipeline(block, clientName);
  }

  @Override // ClientProtocol
  public void updatePipeline(String clientName, ExtendedBlock oldBlock,
      ExtendedBlock newBlock, DatanodeID[] newNodes)
      throws IOException {
    namesystem.updatePipeline(clientName, oldBlock, newBlock, newNodes);
  }
  
  @Override // DatanodeProtocol
  public void commitBlockSynchronization(ExtendedBlock block,
      long newgenerationstamp, long newlength,
      boolean closeFile, boolean deleteblock, DatanodeID[] newtargets)
      throws IOException {
    namesystem.commitBlockSynchronization(block,
        newgenerationstamp, newlength, closeFile, deleteblock, newtargets);
  }
  
  @Override // ClientProtocol
  public long getPreferredBlockSize(String filename) 
      throws IOException {
    return namesystem.getPreferredBlockSize(filename);
  }
    
  @Deprecated
  @Override // ClientProtocol
  public boolean rename(String src, String dst) throws IOException {
    if(stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*DIR* NameNode.rename: " + src + " to " + dst);
    }
    if (!checkPathLength(dst)) {
      throw new IOException("rename: Pathname too long.  Limit "
          + MAX_PATH_LENGTH + " characters, " + MAX_PATH_DEPTH + " levels.");
    }
    boolean ret = namesystem.renameTo(src, dst);
    if (ret) {
      metrics.incrFilesRenamed();
    }
    return ret;
  }
  
  @Override // ClientProtocol
  public void concat(String trg, String[] src) throws IOException {
    namesystem.concat(trg, src);
  }
  
  @Override // ClientProtocol
  public void rename(String src, String dst, Options.Rename... options)
      throws IOException {
    if(stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*DIR* NameNode.rename: " + src + " to " + dst + " with options");
    }
    if (!checkPathLength(dst)) {
      throw new IOException("rename: Pathname too long.  Limit "
          + MAX_PATH_LENGTH + " characters, " + MAX_PATH_DEPTH + " levels.");
    }
    namesystem.renameTo(src, dst, options);
    metrics.incrFilesRenamed();
  }

  @Deprecated
  @Override // ClientProtocol
  public boolean delete(String src) throws IOException {
    return delete(src, true);
  }

  @Override // ClientProtocol
  public boolean delete(String src, boolean recursive) throws IOException {
    if (stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*DIR* Namenode.delete: src=" + src
          + ", recursive=" + recursive);
    }
    
    boolean ret = namesystem.deleteWithTransaction(src, recursive);
    if (ret) 
      metrics.incrDeleteFileOps();
    return ret;
  }

  /**
   * Check path length does not exceed maximum.  Returns true if
   * length and depth are okay.  Returns false if length is too long 
   * or depth is too great.
   */
  private boolean checkPathLength(String src) {
    Path srcPath = new Path(src);
    return (src.length() <= MAX_PATH_LENGTH &&
            srcPath.depth() <= MAX_PATH_DEPTH);
  }
    
  @Override // ClientProtocol
  public boolean mkdirs(String src, FsPermission masked, boolean createParent)
      throws IOException {
    if(stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*DIR* NameNode.mkdirs: " + src);
    }
    if (!checkPathLength(src)) {
      throw new IOException("mkdirs: Pathname too long.  Limit " 
                            + MAX_PATH_LENGTH + " characters, " + MAX_PATH_DEPTH + " levels.");
    }
    return namesystem.mkdirs(src,
        new PermissionStatus(UserGroupInformation.getCurrentUser().getShortUserName(),
            null, masked), createParent);
  }

  @Override // ClientProtocol
  public void renewLease(String clientName) throws IOException {
    namesystem.renewLease(clientName);        
  }

  @Override // ClientProtocol
  public DirectoryListing getListing(String src, byte[] startAfter,
      boolean needLocation)
  throws IOException {
    DirectoryListing files = namesystem.getListing(
        src, startAfter, needLocation);
    if (files != null) {
      metrics.incrGetListingOps();
      metrics.incrFilesInGetListingOps(files.getPartialListing().length);
    }
    return files;
  }

  @Override // ClientProtocol
  public HdfsFileStatus getFileInfo(String src)  throws IOException {
    metrics.incrFileInfoOps();
    return namesystem.getFileInfo(src, true);
  }

  @Override // ClientProtocol
  public HdfsFileStatus getFileLinkInfo(String src) throws IOException { 
    metrics.incrFileInfoOps();
    return namesystem.getFileInfo(src, false);
  }
  
  @Override
  public long[] getStats() {
    return namesystem.getStats();
  }

  //TODO: kamal, tx
  @Override // ClientProtocol
  public DatanodeInfo[] getDatanodeReport(DatanodeReportType type)
      throws IOException {
    DatanodeInfo results[] = namesystem.datanodeReport(type);
    if (results == null ) {
      throw new IOException("Cannot find datanode report");
    }
    return results;
  }
    
  @Override // ClientProtocol
  public boolean setSafeMode(SafeModeAction action) throws IOException {
    return namesystem.setSafeMode(action);
  }

  @Override // ClientProtocol
  public boolean restoreFailedStorage(String arg)
          throws AccessControlException {
    /**
     * TODO[H]: We have the metadata on the database. What do we need to restore
     * then? *
     */
//    return namesystem.restoreFailedStorage(arg);
    return true;
  }

  @Override // ClientProtocol
  public void saveNamespace() throws IOException {
    //[H] This operation seems useless in KTHFS.
    namesystem.saveNamespace();
  }

  @Override // ClientProtocol
  public void refreshNodes() throws IOException {
    namesystem.getBlockManager().getDatanodeManager().refreshNodes(
        new HdfsConfiguration());
  }
    
  @Override // ClientProtocol
  public void finalizeUpgrade() throws IOException {
    namesystem.finalizeUpgrade();
  }

  @Override // ClientProtocol
  public UpgradeStatusReport distributedUpgradeProgress(UpgradeAction action)
      throws IOException {
    return namesystem.distributedUpgradeProgress(action);
  }

  @Override // ClientProtocol
  public void metaSave(String filename) throws IOException {
    namesystem.metaSave(filename);
  }

  @Override // ClientProtocol
  public CorruptFileBlocks listCorruptFileBlocks(String path, String cookie)
      throws IOException {
      
    Collection<FSNamesystem.CorruptFileBlockInfo> fbs =
      namesystem.listCorruptFileBlocks(path, cookie);
    
    String[] files = new String[fbs.size()];
    String lastCookie = "";
    int i = 0;
    for(FSNamesystem.CorruptFileBlockInfo fb: fbs) {
      files[i++] = fb.path;
      lastCookie = fb.block.getBlockName();
    }
    return new CorruptFileBlocks(files, lastCookie);
  }

  /**
   * Tell all datanodes to use a new, non-persistent bandwidth value for
   * dfs.datanode.balance.bandwidthPerSec.
   * @param bandwidth Blanacer bandwidth in bytes per second for all datanodes.
   * @throws IOException
   */
  @Override // ClientProtocol
  public void setBalancerBandwidth(long bandwidth) throws IOException {
    namesystem.getBlockManager().getDatanodeManager().setBalancerBandwidth(bandwidth);
  }
  
  @Override // ClientProtocol
  public ContentSummary getContentSummary(String path) throws IOException {
    return namesystem.getContentSummary(path);
  }

  @Override // ClientProtocol
  public void setQuota(String path, long namespaceQuota, long diskspaceQuota) 
      throws IOException {
    namesystem.setQuota(path, namespaceQuota, diskspaceQuota);
  }
  
  @Override // ClientProtocol
  public void fsync(String src, String clientName) throws IOException {
    namesystem.fsync(src, clientName);
  }

  @Override // ClientProtocol
  public void setTimes(String src, long mtime, long atime) 
      throws IOException {
    namesystem.setTimes(src, mtime, atime);
  }

  @Override // ClientProtocol
  public void createSymlink(String target, String link, FsPermission dirPerms,
      boolean createParent) throws IOException {
    metrics.incrCreateSymlinkOps();
    /* We enforce the MAX_PATH_LENGTH limit even though a symlink target 
     * URI may refer to a non-HDFS file system. 
     */
    if (!checkPathLength(link)) {
      throw new IOException("Symlink path exceeds " + MAX_PATH_LENGTH +
                            " character limit");
                            
    }
    if ("".equals(target)) {
      throw new IOException("Invalid symlink target");
    }
    final UserGroupInformation ugi = UserGroupInformation.getCurrentUser();
    namesystem.createSymlink(target, link,
      new PermissionStatus(ugi.getShortUserName(), null, dirPerms), createParent);
  }

  @Override // ClientProtocol
  public String getLinkTarget(String path) throws IOException {
    metrics.incrGetLinkTargetOps();
    /* Resolves the first symlink in the given path, returning a
     * new path consisting of the target of the symlink and any 
     * remaining path components from the original path.
     */
    try {
      HdfsFileStatus stat = namesystem.getFileInfo(path, false);
      if (stat != null) {
        // NB: getSymlink throws IOException if !stat.isSymlink() 
        return stat.getSymlink();
      }
    } catch (UnresolvedPathException e) {
      return e.getResolvedPath().toString();
    } catch (UnresolvedLinkException e) {
      // The NameNode should only throw an UnresolvedPathException
      throw new AssertionError("UnresolvedLinkException thrown");
    }
    return null;
  }


  @Override // DatanodeProtocol
  public DatanodeRegistration registerDatanode(DatanodeRegistration nodeReg)
      throws IOException {
    verifyVersion(nodeReg.getVersion());
    namesystem.registerDatanode(nodeReg);
      
    return nodeReg;
  }

  @Override // DatanodeProtocol
  public DatanodeCommand[] sendHeartbeat(DatanodeRegistration nodeReg,
      long capacity, long dfsUsed, long remaining, long blockPoolUsed,
      int xmitsInProgress, int xceiverCount, int failedVolumes)
      throws IOException {
    verifyRequest(nodeReg);
    return namesystem.handleHeartbeat(nodeReg, capacity, dfsUsed, remaining,
        blockPoolUsed, xceiverCount, xmitsInProgress, failedVolumes);
  }

  @Override // DatanodeProtocol
  public DatanodeCommand blockReport(DatanodeRegistration nodeReg,
      String poolId, long[] blocks) throws IOException {
    verifyRequest(nodeReg);
    BlockListAsLongs blist = new BlockListAsLongs(blocks);
    if(stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*BLOCK* NameNode.blockReport: "
           + "from " + nodeReg.getName() + " " + blist.getNumberOfBlocks()
           + " blocks");
    }

    namesystem.getBlockManager().processReport(nodeReg, poolId, blist);
    /**TODO[H]: Status of the upgrade should be stored somewhere other than FSImage?**/
    return null;
  }

  @Override // DatanodeProtocol
  public void blockReceivedAndDeleted(DatanodeRegistration nodeReg, String poolId,
      ReceivedDeletedBlockInfo[] receivedAndDeletedBlocks) throws IOException {
    verifyRequest(nodeReg);
    if(stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*BLOCK* NameNode.blockReceivedAndDeleted: "
          +"from "+nodeReg.getName()+" "+receivedAndDeletedBlocks.length
          +" blocks. DN: "+ nodeReg.storageID+" first block id "+ receivedAndDeletedBlocks[0].getBlock().getBlockId());
    }
    
    namesystem.getBlockManager().blockReceivedAndDeleted(
        nodeReg, poolId, receivedAndDeletedBlocks);
  }

  @Override // DatanodeProtocol
  public void errorReport(DatanodeRegistration nodeReg,
                          int errorCode, String msg) throws IOException { 
    String dnName = (nodeReg == null ? "unknown DataNode" : nodeReg.getName());

    if (errorCode == DatanodeProtocol.NOTIFY) {
      LOG.info("Error report from " + dnName + ": " + msg);
      return;
    }
    verifyRequest(nodeReg);

    if (errorCode == DatanodeProtocol.DISK_ERROR) {
      LOG.warn("Disk error on " + dnName + ": " + msg);
    } else if (errorCode == DatanodeProtocol.FATAL_DISK_ERROR) {
      LOG.warn("Fatal disk error on " + dnName + ": " + msg);
      namesystem.getBlockManager().getDatanodeManager().removeDatanode(nodeReg);            
    } else {
      LOG.info("Error report from " + dnName + ": " + msg);
    }
  }
    
  @Override // DatanodeProtocol, NamenodeProtocol
  public NamespaceInfo versionRequest() throws IOException {
    return namesystem.getNamespaceInfo();
  }

  @Override // DatanodeProtocol
  public UpgradeCommand processUpgradeCommand(UpgradeCommand comm) throws IOException {
    return namesystem.processDistributedUpgradeCommand(comm);
  }

  /** 
   * Verify request.
   * 
   * Verifies correctness of the datanode version, registration ID, and 
   * if the datanode does not need to be shutdown.
   * 
   * @param nodeReg data node registration
   * @throws IOException
   */
  void verifyRequest(NodeRegistration nodeReg) throws IOException {
    verifyVersion(nodeReg.getVersion());
    String registrationId = namesystem.getRegistrationID();
    if (!registrationId.equals(nodeReg.getRegistrationID())) {
      LOG.warn("Invalid registrationID - expected: "
          + registrationId + " received: "
          + nodeReg.getRegistrationID());
      throw new UnregisteredNodeException(nodeReg);
    }
  }

  @Override // RefreshAuthorizationPolicyProtocol
  public void refreshServiceAcl() throws IOException {
    if (!serviceAuthEnabled) {
      throw new AuthorizationException("Service Level Authorization not enabled!");
    }

    this.server.refreshServiceAcl(new Configuration(), new HDFSPolicyProvider());
    if (this.serviceRpcServer != null) {
      this.serviceRpcServer.refreshServiceAcl(new Configuration(), new HDFSPolicyProvider());
    }
  }

  @Override // RefreshAuthorizationPolicyProtocol
  public void refreshUserToGroupsMappings() throws IOException {
    LOG.info("Refreshing all user-to-groups mappings. Requested by user: " + 
             UserGroupInformation.getCurrentUser().getShortUserName());
    Groups.getUserToGroupsMappingService().refresh();
  }

  @Override // RefreshAuthorizationPolicyProtocol
  public void refreshSuperUserGroupsConfiguration() {
    LOG.info("Refreshing SuperUser proxy group mapping list ");

    ProxyUsers.refreshSuperUserGroupsConfiguration();
  }
  
  @Override // GetUserMappingsProtocol
  public String[] getGroupsForUser(String user) throws IOException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Getting groups for user " + user);
    }
    return UserGroupInformation.createRemoteUser(user).getGroupNames();
  }


  /**
   * Verify version.
   * 
   * @param version
   * @throws IOException
   */
  void verifyVersion(int version) throws IOException {
    if (version != HdfsConstants.LAYOUT_VERSION)
      throw new IncorrectVersionException(version, "data node");
  }

  private static String getClientMachine() {
    String clientMachine = Server.getRemoteAddress();
    if (clientMachine == null) {
      clientMachine = "";
    }
    LOG.debug("[thesis] clientMachine: "+clientMachine);
    return clientMachine;
  }
  
  /**
 * // ClientProtocol
 * Ping to see if we have a connection with the NN
 * 
 * @throws IOException
 */
  @Override
  public void ping() throws IOException {

  }

  private TransactionalRequestHandler selectAllNameNodesHandler = new TransactionalRequestHandler(OperationType.SELECT_ALL_NAMENODES) {

    @Override
    public void acquireLock() throws PersistanceException, IOException {
      TransactionLockManager tlm = new TransactionLockManager();
      tlm.addLeaderLock(TransactionLockManager.LockType.READ_COMMITTED).
              acquire();
    }

    @Override
    public Object performTask() throws PersistanceException, IOException {
      return nn.getLeaderAlgo().selectAll();
    }
  };
  /**
   * //DatanodeProtocol
   * The datanodes periodically asks the leader namenode for the list of actively running namenodes
   */
  @Override
  public ActiveNamenodeList sendActiveNamenodes() throws IOException {
      
    return new ActiveNamenodeList((SortedMap<Long, InetSocketAddress>) selectAllNameNodesHandler.handle());
  }

  /**
   * The BPOfferService that corresponds to the leader Namenode asks it which 'namenode' to send the block reports to
   * This is a feature added to do load balancing of block reports among namenodes
   */
  @Override
  public String getNextNamenodeToSendBlockReport() throws IOException {
    // Use the modulo to roundrobin b/w namenodes
    nnIndex++;
    // TODO[Hooman]: What if totalNamenodes is null?
    Collection<InetSocketAddress> totalNamenodes = ((SortedMap<Long, InetSocketAddress>) selectAllNameNodesHandler.handle()).values();
    nnIndex = nnIndex % totalNamenodes.size();
    Iterator<InetSocketAddress> iter = totalNamenodes.iterator();
    int count = nnIndex;
    while(iter.hasNext()) {
      if(count == 0) {
        break;
      }
      else {
        // skip this namenode
        iter.next();
        count--;
      }
    }
    // Convert to string format to be passed over RPC
    if(!iter.hasNext()) {
      throw new IOException("Something went wrong [nnIndex: "+nnIndex+", size: "+totalNamenodes.size()+", count: "+count+"]. Expecting namenode entry");
    }
    InetSocketAddress ipAddr = iter.next();
    String ip_port = ipAddr.getAddress().getHostAddress()+":"+ipAddr.getPort();

    // TODO Jude - if i am the leader and I am in safe-mode, then send the block reports
    // only to me - these are the initial block reports needed to leave safe mode.
//    if (nn.isInSafeMode() && nn.isLeader()) {
//        ip_port = // my ip-port
//    }
    
    return ip_port;
  }

}
