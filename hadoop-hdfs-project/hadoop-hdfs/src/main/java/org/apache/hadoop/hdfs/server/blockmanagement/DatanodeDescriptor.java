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
package org.apache.hadoop.hdfs.server.blockmanagement;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.hdfs.DeprecatedUTF8;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableUtils;

/**************************************************
 * DatanodeDescriptor tracks stats on a given DataNode, such as
 * available storage capacity, last update time, etc., and maintains a
 * set of blocks stored on the datanode.
 *
 * This data structure is internal to the namenode. It is *not* sent
 * over-the-wire to the Client or the Datanodes. Neither is it stored
 * persistently in the fsImage.
 **************************************************/
@InterfaceAudience.Private
public class DatanodeDescriptor extends DatanodeInfo {
  
  // Stores status of decommissioning.
  // If node is not decommissioning, do not use this object for anything.
  public DecommissioningStatus decommissioningStatus = new DecommissioningStatus();
  
  /** Block and targets pair */
  @InterfaceAudience.Private
  @InterfaceStability.Evolving
  public static class BlockTargetPair {
    public final Block block;
    public final DatanodeDescriptor[] targets;    

    BlockTargetPair(Block block, DatanodeDescriptor[] targets) {
      this.block = block;
      this.targets = targets;
    }
  }

  /** A BlockTargetPair queue. */
  private static class BlockQueue<E> {
    private final Queue<E> blockq = new LinkedList<E>();

    /** Size of the queue */
    synchronized int size() {return blockq.size();}

    /** Enqueue */
    synchronized boolean offer(E e) { 
      return blockq.offer(e);
    }

    /** Dequeue */
    synchronized List<E> poll(int numBlocks) {
      if (numBlocks <= 0 || blockq.isEmpty()) {
        return null;
      }

      List<E> results = new ArrayList<E>();
      for(; !blockq.isEmpty() && numBlocks > 0; numBlocks--) {
        results.add(blockq.poll());
      }
      return results;
    }

    /**
     * Returns <tt>true</tt> if the queue contains the specified element.
     */
    boolean contains(E e) {
      return blockq.contains(e);
    }
  }

  //private volatile BlockInfo blockList = null;
  private int numBlocks = 0;
  // isAlive == heartbeats.contains(this)
  // This is an optimization, because contains takes O(n) time on Arraylist
  public boolean isAlive = false;
  public boolean needKeyUpdate = false;

  // A system administrator can tune the balancer bandwidth parameter
  // (dfs.balance.bandwidthPerSec) dynamically by calling
  // "dfsadmin -setBalanacerBandwidth <newbandwidth>", at which point the
  // following 'bandwidth' variable gets updated with the new value for each
  // node. Once the heartbeat command is issued to update the value on the
  // specified datanode, this value will be set back to 0.
  private long bandwidth;

  /** A queue of blocks to be replicated by this datanode */
  private BlockQueue<BlockTargetPair> replicateBlocks = new BlockQueue<BlockTargetPair>();
  /** A queue of blocks to be recovered by this datanode */
  private BlockQueue<BlockInfoUnderConstruction> recoverBlocks =
                                new BlockQueue<BlockInfoUnderConstruction>();
  /** A set of blocks to be invalidated by this datanode */
  private Set<Block> invalidateBlocks = new TreeSet<Block>();

  /* Variables for maintaining number of blocks scheduled to be written to
   * this datanode. This count is approximate and might be slightly bigger
   * in case of errors (e.g. datanode does not report if an error occurs 
   * while writing the block).
   */
  private int currApproxBlocksScheduled = 0;
  private int prevApproxBlocksScheduled = 0;
  private long lastBlocksScheduledRollTime = 0;
  private static final int BLOCKS_SCHEDULED_ROLL_INTERVAL = 600*1000; //10min
  private int volumeFailures = 0;
  /** 
   * When set to true, the node is not in include list and is not allowed
   * to communicate with the namenode
   */
  private boolean disallowed = false;

  /** Default constructor */
  public DatanodeDescriptor() {}
  
  /** DatanodeDescriptor constructor
   * @param nodeID id of the data node
   */
  public DatanodeDescriptor(DatanodeID nodeID) {
    this(nodeID, 0L, 0L, 0L, 0L, 0, 0);
  }

  /** DatanodeDescriptor constructor
   * 
   * @param nodeID id of the data node
   * @param networkLocation location of the data node in network
   */
  public DatanodeDescriptor(DatanodeID nodeID, 
                            String networkLocation) {
    this(nodeID, networkLocation, null);
  }
  
  /** DatanodeDescriptor constructor
   * 
   * @param nodeID id of the data node
   * @param networkLocation location of the data node in network
   * @param hostName it could be different from host specified for DatanodeID
   */
  public DatanodeDescriptor(DatanodeID nodeID, 
                            String networkLocation,
                            String hostName) {
    this(nodeID, networkLocation, hostName, 0L, 0L, 0L, 0L, 0, 0);
  }
  
  /** DatanodeDescriptor constructor
   * 
   * @param nodeID id of the data node
   * @param capacity capacity of the data node
   * @param dfsUsed space used by the data node
   * @param remaining remaining capacity of the data node
   * @param bpused space used by the block pool corresponding to this namenode
   * @param xceiverCount # of data transfers at the data node
   */
  public DatanodeDescriptor(DatanodeID nodeID, 
                            long capacity,
                            long dfsUsed,
                            long remaining,
                            long bpused,
                            int xceiverCount,
                            int failedVolumes) {
    super(nodeID);
    updateHeartbeat(capacity, dfsUsed, remaining, bpused, xceiverCount, 
        failedVolumes);
  }

  /** DatanodeDescriptor constructor
   * 
   * @param nodeID id of the data node
   * @param networkLocation location of the data node in network
   * @param capacity capacity of the data node, including space used by non-dfs
   * @param dfsUsed the used space by dfs datanode
   * @param remaining remaining capacity of the data node
   * @param bpused space used by the block pool corresponding to this namenode
   * @param xceiverCount # of data transfers at the data node
   */
  public DatanodeDescriptor(DatanodeID nodeID,
                            String networkLocation,
                            String hostName,
                            long capacity,
                            long dfsUsed,
                            long remaining,
                            long bpused,
                            int xceiverCount,
                            int failedVolumes) {
    super(nodeID, networkLocation, hostName);
    updateHeartbeat(capacity, dfsUsed, remaining, bpused, xceiverCount, 
        failedVolumes);
  }

  public void resetBlocks() {
    this.capacity = 0;
    this.remaining = 0;
    this.blockPoolUsed = 0;
    this.dfsUsed = 0;
    this.xceiverCount = 0;
    this.invalidateBlocks.clear();
    this.volumeFailures = 0;
  }

  public int numBlocks() {
    return numBlocks;
  }

  public void increamentBlocks() {
    numBlocks++;
  }
  
  public void decrementBlocks() {
    numBlocks--;
  }
  /**
   * Updates stats from datanode heartbeat.
   */
  public void updateHeartbeat(long capacity, long dfsUsed, long remaining,
      long blockPoolUsed, int xceiverCount, int volFailures) {
    this.capacity = capacity;
    this.dfsUsed = dfsUsed;
    this.remaining = remaining;
    this.blockPoolUsed = blockPoolUsed;
    this.lastUpdate = System.currentTimeMillis();
    this.xceiverCount = xceiverCount;
    this.volumeFailures = volFailures;
    rollBlocksScheduled(lastUpdate);
  }

  /**
   * Store block replication work.
   */
  void addBlockToBeReplicated(Block block, DatanodeDescriptor[] targets) {
    assert(block != null && targets != null && targets.length > 0);
    replicateBlocks.offer(new BlockTargetPair(block, targets));
  }

  /**
   * Store block recovery work.
   */
  void addBlockToBeRecovered(BlockInfoUnderConstruction block) {
    if(recoverBlocks.contains(block)) {
      // this prevents adding the same block twice to the recovery queue
      BlockManager.LOG.info("Block " + block +
                            " is already in the recovery queue.");
      return;
    }
    recoverBlocks.offer(block);
  }

  /**
   * Store block invalidation work.
   */
  void addBlocksToBeInvalidated(List<Block> blocklist) {
    assert(blocklist != null && blocklist.size() > 0);
    synchronized (invalidateBlocks) {
      for(Block blk : blocklist) {
        invalidateBlocks.add(blk);
      }
    }
  }

  /**
   * The number of work items that are pending to be replicated
   */
  int getNumberOfBlocksToBeReplicated() {
    return replicateBlocks.size();
  }

  /**
   * The number of block invalidation items that are pending to 
   * be sent to the datanode
   */
  int getNumberOfBlocksToBeInvalidated() {
    synchronized (invalidateBlocks) {
      return invalidateBlocks.size();
    }
  }
  
  public List<BlockTargetPair> getReplicationCommand(int maxTransfers) {
    return replicateBlocks.poll(maxTransfers);
  }

  public BlockInfoUnderConstruction[] getLeaseRecoveryCommand(int maxTransfers) {
    List<BlockInfoUnderConstruction> blocks = recoverBlocks.poll(maxTransfers);
    if(blocks == null)
      return null;
    return blocks.toArray(new BlockInfoUnderConstruction[blocks.size()]);
  }

  /**
   * Remove the specified number of blocks to be invalidated
   */
  public Block[] getInvalidateBlocks(int maxblocks) {
    return getBlockArray(invalidateBlocks, maxblocks); 
  }

  static private Block[] getBlockArray(Collection<Block> blocks, int max) {
    Block[] blockarray = null;
    synchronized(blocks) {
      int available = blocks.size();
      int n = available;
      if (max > 0 && n > 0) {
        if (max < n) {
          n = max;
        }
        // allocate the properly sized block array ... 
        blockarray = new Block[n];

        // iterate tree collecting n blocks... 
        Iterator<Block> e = blocks.iterator();
        int blockCount = 0;

        while (blockCount < n && e.hasNext()) {
          // insert into array ... 
          blockarray[blockCount++] = e.next();

          // remove from tree via iterator, if we are removing 
          // less than total available blocks
          if (n < available){
            e.remove();
          }
        }
        assert(blockarray.length == n);
        
        // now if the number of blocks removed equals available blocks,
        // them remove all blocks in one fell swoop via clear
        if (n == available) { 
          blocks.clear();
        }
      }
    }
    return blockarray;
  }

  /** Serialization for FSEditLog */
  public void readFieldsFromFSEditLog(DataInput in) throws IOException {
    this.name = DeprecatedUTF8.readString(in);
    this.storageID = DeprecatedUTF8.readString(in);
    this.infoPort = in.readShort() & 0x0000ffff;

    this.capacity = in.readLong();
    this.dfsUsed = in.readLong();
    this.remaining = in.readLong();
    this.blockPoolUsed = in.readLong();
    this.lastUpdate = in.readLong();
    this.xceiverCount = in.readInt();
    this.location = Text.readString(in);
    this.hostName = Text.readString(in);
    setAdminState(WritableUtils.readEnum(in, AdminStates.class));
  }
  
  /**
   * @return Approximate number of blocks currently scheduled to be written 
   * to this datanode.
   */
  public int getBlocksScheduled() {
    return currApproxBlocksScheduled + prevApproxBlocksScheduled;
  }
  
  /**
   * Increments counter for number of blocks scheduled. 
   */
  public void incBlocksScheduled() {
    currApproxBlocksScheduled++;
  }
  
  /**
   * Decrements counter for number of blocks scheduled.
   */
  void decBlocksScheduled() {
    if (prevApproxBlocksScheduled > 0) {
      prevApproxBlocksScheduled--;
    } else if (currApproxBlocksScheduled > 0) {
      currApproxBlocksScheduled--;
    } 
    // its ok if both counters are zero.
  }
  
  /**
   * Adjusts curr and prev number of blocks scheduled every few minutes.
   */
  private void rollBlocksScheduled(long now) {
    if ((now - lastBlocksScheduledRollTime) > 
        BLOCKS_SCHEDULED_ROLL_INTERVAL) {
      prevApproxBlocksScheduled = currApproxBlocksScheduled;
      currApproxBlocksScheduled = 0;
      lastBlocksScheduledRollTime = now;
    }
  }
  
  @Override
  public int hashCode() {
    // Super implementation is sufficient
    return super.hashCode();
  }
  
  @Override
  public boolean equals(Object obj) {
    // Sufficient to use super equality as datanodes are uniquely identified
    // by DatanodeID
    return (this == obj) || super.equals(obj);
  }

  /** Decommissioning status */
  public class DecommissioningStatus {
    private int underReplicatedBlocks;
    private int decommissionOnlyReplicas;
    private int underReplicatedInOpenFiles;
    private long startTime;
    
    synchronized void set(int underRep,
        int onlyRep, int underConstruction) {
      if (isDecommissionInProgress() == false) {
        return;
      }
      underReplicatedBlocks = underRep;
      decommissionOnlyReplicas = onlyRep;
      underReplicatedInOpenFiles = underConstruction;
    }

    /** @return the number of under-replicated blocks */
    public synchronized int getUnderReplicatedBlocks() {
      if (isDecommissionInProgress() == false) {
        return 0;
      }
      return underReplicatedBlocks;
    }
    /** @return the number of decommission-only replicas */
    public synchronized int getDecommissionOnlyReplicas() {
      if (isDecommissionInProgress() == false) {
        return 0;
      }
      return decommissionOnlyReplicas;
    }
    /** @return the number of under-replicated blocks in open files */
    public synchronized int getUnderReplicatedInOpenFiles() {
      if (isDecommissionInProgress() == false) {
        return 0;
      }
      return underReplicatedInOpenFiles;
    }
    /** Set start time */
    public synchronized void setStartTime(long time) {
      startTime = time;
    }
    /** @return start time */
    public synchronized long getStartTime() {
      if (isDecommissionInProgress() == false) {
        return 0;
      }
      return startTime;
    }
  }  // End of class DecommissioningStatus

  /**
   * Set the flag to indicate if this datanode is disallowed from communicating
   * with the namenode.
   */
  public void setDisallowed(boolean flag) {
    disallowed = flag;
  }
  /** Is the datanode disallowed from communicating with the namenode? */
  public boolean isDisallowed() {
    return disallowed;
  }

  /**
   * @return number of failed volumes in the datanode.
   */
  public int getVolumeFailures() {
    return volumeFailures;
  }

  /**
   * @param nodeReg DatanodeID to update registration for.
   */
  public void updateRegInfo(DatanodeID nodeReg) {
    super.updateRegInfo(nodeReg);
  }

  /**
   * @return Blanacer bandwidth in bytes per second for this datanode.
   */
  public long getBalancerBandwidth() {
    return this.bandwidth;
  }

  /**
   * @param bandwidth Blanacer bandwidth in bytes per second for this datanode.
   */
  public void setBalancerBandwidth(long bandwidth) {
    this.bandwidth = bandwidth;
  }


}
