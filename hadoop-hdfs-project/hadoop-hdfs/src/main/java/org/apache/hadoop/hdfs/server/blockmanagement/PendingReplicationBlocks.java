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
package org.apache.hadoop.hdfs.server.blockmanagement;

import java.io.IOException;
import static org.apache.hadoop.hdfs.server.common.Util.now;

import java.io.PrintWriter;
import java.sql.Time;
import java.util.Collection;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.server.namenode.lock.TransactionLockAcquirer;
import org.apache.hadoop.hdfs.server.namenode.lock.TransactionLockManager.LockType;
import org.apache.hadoop.hdfs.server.namenode.persistance.EntityManager;
import org.apache.hadoop.hdfs.server.namenode.persistance.LightWeightRequestHandler;
import org.apache.hadoop.hdfs.server.namenode.persistance.PersistanceException;
import org.apache.hadoop.hdfs.server.namenode.persistance.RequestHandler.OperationType;
import org.apache.hadoop.hdfs.server.namenode.persistance.TransactionalRequestHandler;
import org.apache.hadoop.hdfs.server.namenode.persistance.data_access.entity.PendingBlockDataAccess;
import org.apache.hadoop.hdfs.server.namenode.persistance.storage.StorageFactory;

/**
 * *************************************************
 * PendingReplicationBlocks does the bookkeeping of all blocks that are getting
 * replicated.
 *
 * It does the following: 1) record blocks that are getting replicated at this
 * instant. 2) a coarse grain timer to track age of replication request 3) a
 * thread that periodically identifies replication-requests that never made it.
 *
 **************************************************
 */
class PendingReplicationBlocks {

  private static final Log LOG = BlockManager.LOG;
  //
  // It might take anywhere between 5 to 10 minutes before
  // a request is timed out.
  //
  //private long timeout = 5 * 60 * 1000;
  private static long timeout = 2 * 60 * 1000;

  PendingReplicationBlocks(long timeoutPeriod) {
    if (timeoutPeriod > 0) {
      this.timeout = timeoutPeriod;
    }
  }

  /**
   * Add a block to the list of pending Replications
   */
  void add(Block block, int numReplicas) throws PersistanceException {
    PendingBlockInfo found = EntityManager.find(PendingBlockInfo.Finder.ByPKey, block.getBlockId());
    if (found == null) {
      found = new PendingBlockInfo(block.getBlockId(), now(), numReplicas);
      EntityManager.add(found);
    } else {
      found.incrementReplicas(numReplicas);
      found.setTimeStamp(now());
      EntityManager.update(found);
    }
  }

  /**
   * One replication request for this block has finished. Decrement the number
   * of pending replication requests for this block.
   */
  void remove(Block block) throws PersistanceException {
    PendingBlockInfo found = EntityManager.find(PendingBlockInfo.Finder.ByPKey, block.getBlockId());
    if (found != null && !isTimedout(found)) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Removing pending replication for " + block);
      }
      found.decrementReplicas();
      if (found.getNumReplicas() <= 0) {
        EntityManager.remove(found);
      } else {
        EntityManager.update(found);
      }
    }
  }

  /**
   * The total number of blocks that are undergoing replication
   */
  int size(OperationType opType) throws IOException {
    return (Integer) new LightWeightRequestHandler(opType) {
      @Override
      public Object performTask() throws PersistanceException, IOException {
        PendingBlockDataAccess da = (PendingBlockDataAccess) StorageFactory.getDataAccess(PendingBlockDataAccess.class);
        return da.countValidPendingBlocks(now() - timeout);
      }
    }.handle();
  }

  private boolean isTimedout(PendingBlockInfo pendingBlock) {
    if (now() - timeout > pendingBlock.getTimeStamp()) {
      return true;
    }

    return false;
  }

  /**
   * How many copies of this block is pending replication?
   */
  int getNumReplicas(Block block) throws PersistanceException {
    PendingBlockInfo found = EntityManager.find(PendingBlockInfo.Finder.ByPKey, block.getBlockId());
    if (found != null && !isTimedout(found)) {
      return found.getNumReplicas();
    }
    return 0;
  }

  /**
   * Returns a list of blocks that have timed out their replication requests.
   * Returns null if no blocks have timed out.
   */
  List<PendingBlockInfo> getTimedOutBlocks(TransactionalRequestHandler.OperationType opType) throws IOException {
    return (List<PendingBlockInfo>) new LightWeightRequestHandler(opType) {
      @Override
      public Object performTask() throws PersistanceException, IOException {
        long timeLimit = getTimeLimit();
        PendingBlockDataAccess da = (PendingBlockDataAccess) StorageFactory.getDataAccess(PendingBlockDataAccess.class);
        List<PendingBlockInfo> timedoutPendings = (List<PendingBlockInfo>) da.findByTimeLimit(timeLimit);
        if (timedoutPendings == null || timedoutPendings.size() <= 0) {
          return null;
        }

        return timedoutPendings;
      }
    }.handle();
  }

  private static long getTimeLimit() {
    return now() - timeout;
  }

  public static boolean isTimedOut(PendingBlockInfo block) {
    long timeLimit = getTimeLimit();
    if (block.getTimeStamp() < timeLimit) {
      return true;
    }
    return false;
  }

  /**
   * Iterate through all items and print them.
   */
  void metaSave(PrintWriter out) throws PersistanceException {
    List<PendingBlockInfo> pendingBlocks = (List<PendingBlockInfo>) EntityManager.findList(PendingBlockInfo.Finder.All);
    if (pendingBlocks != null) {
      out.println("Metasave: Blocks being replicated: "
              + pendingBlocks.size());
      for (PendingBlockInfo pendingBlock : pendingBlocks) {
        if (!isTimedout(pendingBlock)) {
          BlockInfo bInfo = EntityManager.find(BlockInfo.Finder.ById, pendingBlock.getBlockId());
          out.println(bInfo
                  + " StartTime: " + new Time(pendingBlock.getTimeStamp())
                  + " NumReplicaInProgress: "
                  + pendingBlock.getNumReplicas());
        }
      }
    }
  }
}
