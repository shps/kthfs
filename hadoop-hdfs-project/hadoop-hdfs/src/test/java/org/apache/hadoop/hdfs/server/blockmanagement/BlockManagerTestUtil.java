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

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.lock.TransactionLockManager;
import org.apache.hadoop.hdfs.server.namenode.persistance.PersistanceException;
import org.apache.hadoop.hdfs.server.namenode.persistance.RequestHandler.OperationType;
import org.apache.hadoop.hdfs.server.namenode.persistance.TransactionalRequestHandler;
import org.apache.hadoop.util.Daemon;

public class BlockManagerTestUtil {
  public static void setNodeReplicationLimit(final BlockManager blockManager,
      final int limit) {
    blockManager.maxReplicationStreams = limit;
  }

  /** @return the datanode descriptor for the given the given storageID. */
  public static DatanodeDescriptor getDatanode(final FSNamesystem ns,
      final String storageID) {
    ns.readLock();
    try {
      return ns.getBlockManager().getDatanodeManager().getDatanodeByStorageId(storageID);
    } finally {
      ns.readUnlock();
    }
  }


  /**
   * Refresh block queue counts on the name-node.
   */
  public static void updateState(final BlockManager blockManager) throws IOException {
    blockManager.updateState(OperationType.TEST);
  }

  /**
   * @return a tuple of the replica state (number racks, number live
   * replicas, and number needed replicas) for the given block.
   * @throws IOException 
   */
  public static int[] getReplicaInfo(final FSNamesystem namesystem, final Block b) throws IOException {
    return (int[]) new TransactionalRequestHandler(OperationType.TEST) {

      @Override
      public void acquireLock() throws PersistanceException, IOException {
        TransactionLockManager tlm = new TransactionLockManager();
        tlm.addBlock(TransactionLockManager.LockType.READ, b.getBlockId()).
                addReplica(TransactionLockManager.LockType.READ).
                addCorrupt(TransactionLockManager.LockType.READ).
                addExcess(TransactionLockManager.LockType.READ).
                addUnderReplicatedBlock(TransactionLockManager.LockType.READ).
                acquire();
      }

      @Override
      public Object performTask() throws PersistanceException, IOException {
        final BlockManager bm = namesystem.getBlockManager();
        return new int[]{getNumberOfRacks(bm, b),
                  bm.countNodes(b).liveReplicas(),
                  bm.neededReplications.contains(b) ? 1 : 0};
      }
    }.handleWithReadLock(namesystem);
  }

  /**
   * @return the number of racks over which a given block is replicated
   * decommissioning/decommissioned nodes are not counted. corrupt replicas 
   * are also ignored
   * @throws IOException 
   */
  private static int getNumberOfRacks(final BlockManager blockManager,
      final Block b) throws IOException, PersistanceException {
    final Set<String> rackSet = new HashSet<String>(0);

    BlockInfo storedBlock = blockManager.getStoredBlock(b);
    for (DatanodeDescriptor cur : blockManager.getDatanodes(storedBlock)) {
      if (!cur.isDecommissionInProgress() && !cur.isDecommissioned()) {
        if (!blockManager.isItCorruptedReplica(b.getBlockId(), cur.getStorageID())) {
          String rackName = cur.getNetworkLocation();
          if (!rackSet.contains(rackName)) {
            rackSet.add(rackName);
          }
        }
      }
    }
    return rackSet.size();
  }

  /**
   * @param blockManager
   * @return replication monitor thread instance from block manager.
   */
  public static Daemon getReplicationThread(final BlockManager blockManager)
  {
    return blockManager.replicationThread;
  }

  /**
   * @param blockManager
   * @return computed block replication and block invalidation work that can be
   *         scheduled on data-nodes.
   * @throws IOException
   */
  public static int getComputedDatanodeWork(final BlockManager blockManager) throws IOException
  {
    return blockManager.computeDatanodeWork(OperationType.GET_COMPUTED_DATANODE_WORK);
  }
  
}
