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
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.namenode.persistance.EntityManager;
import org.apache.hadoop.hdfs.server.namenode.persistance.LightWeightRequestHandler;
import org.apache.hadoop.hdfs.server.namenode.persistance.PersistanceException;
import org.apache.hadoop.hdfs.server.namenode.persistance.RequestHandler.OperationType;
import org.apache.hadoop.hdfs.server.namenode.persistance.TransactionalRequestHandler;
import org.apache.hadoop.hdfs.server.namenode.persistance.data_access.entity.InvalidateBlockDataAccess;
import org.apache.hadoop.hdfs.server.namenode.persistance.storage.StorageFactory;

/**
 * Keeps a Collection for every named machine containing blocks that have
 * recently been invalidated and are thought to live on the machine in question.
 */
@InterfaceAudience.Private
class InvalidateBlocks {

  private final DatanodeManager datanodeManager;

  InvalidateBlocks(final DatanodeManager datanodeManager) {
    this.datanodeManager = datanodeManager;
  }

  /**
   * @return the number of blocks to be invalidated .
   */
  synchronized int numBlocks(OperationType opType) throws IOException {
    return (Integer) new LightWeightRequestHandler(opType) {

      @Override
      public Object performTask() throws PersistanceException, IOException {
        InvalidateBlockDataAccess da = (InvalidateBlockDataAccess) StorageFactory.getDataAccess(InvalidateBlockDataAccess.class);
        return da.countAll();
      }
    }.handle();
  }

  /**
   * Does this contain the block which is associated with the storage?
   */
  synchronized boolean contains(final String storageID, final Block block) throws PersistanceException {
    return EntityManager.find(InvalidatedBlock.Finder.ByPrimaryKey, block.getBlockId(), storageID) != null;
  }

  /**
   * Add a block to the block collection which will be invalidated on the
   * specified datanode.
   */
  synchronized void add(final Block block, final DatanodeInfo datanode,
          final boolean log) throws PersistanceException {

    EntityManager.add(new InvalidatedBlock(datanode.getStorageID(), block.getBlockId(),
            block.getGenerationStamp(), block.getNumBytes()));

    if (log) {
      NameNode.stateChangeLog.info("BLOCK* " + getClass().getSimpleName()
              + ": add " + block + " to " + datanode.getName());
    }
  }

  /**
   * Remove a storage from the invalidatesSet
   */
  synchronized void remove(final String storageID, TransactionalRequestHandler.OperationType opType) throws IOException {

    List<InvalidatedBlock> invBlocks = findInvBlocksbyStorageId(storageID, opType);
    if (invBlocks != null) {
      for (InvalidatedBlock invBlock : invBlocks) {
        if (invBlock != null) {
          removeInvBlock(invBlock, opType);
        }
      }
    }
  }

  /**
   * Remove the block from the specified storage.
   */
  synchronized void remove(final String storageID, final Block block) throws PersistanceException {
    EntityManager.remove(new InvalidatedBlock(storageID, block.getBlockId(),
            block.getGenerationStamp(), block.getNumBytes()));
  }

  /**
   * Print the contents to out.
   */
  synchronized void dump(final PrintWriter out) throws PersistanceException {
    List<InvalidatedBlock> invBlocks = (List<InvalidatedBlock>) EntityManager.findList(InvalidatedBlock.Finder.All);
    HashSet<String> storageIds = new HashSet<String>();
    for (InvalidatedBlock ib : invBlocks) {
      storageIds.add(ib.getStorageId());
    }

    final int size = storageIds.size();
    out.println("Metasave: Blocks " + invBlocks.size()
            + " waiting deletion from " + size + " datanodes.");
    if (size == 0) {
      return;
    }

    //FIXME [H]: To dump properly it needs to get the block using blockid. Indeed, this is inefficient.
    for (String sId : storageIds) {
      HashSet<InvalidatedBlock> invSet = new HashSet<InvalidatedBlock>();
      for (InvalidatedBlock ib : invBlocks) {
        if (ib.getStorageId().equals(sId)) {
          invSet.add(ib);
        }
      }
      if (invBlocks.size() > 0) {
        out.println(datanodeManager.getDatanodeByStorageId(sId).getName() + invBlocks);
      }
    }
  }

  /**
   * @return a list of the storage IDs.
   */
  List<String> getStorageIDs(OperationType opType) throws IOException {
    LightWeightRequestHandler getAllInvBlocksHandler = new LightWeightRequestHandler(opType) {
      @Override
      public Object performTask() throws PersistanceException, IOException {
        InvalidateBlockDataAccess da = (InvalidateBlockDataAccess) StorageFactory.getDataAccess(InvalidateBlockDataAccess.class);
        return da.findAllInvalidatedBlocks();
      }
    };
    List<InvalidatedBlock> invBlocks = (List<InvalidatedBlock>) getAllInvBlocksHandler.handle();
    HashSet<String> storageIds = new HashSet<String>();
    for (InvalidatedBlock ib : invBlocks) {
      storageIds.add(ib.getStorageId());
    }

    return new ArrayList<String>(storageIds);
  }

  /**
   * Invalidate work for the storage.
   */
  int invalidateWork(final String storageId, TransactionalRequestHandler.OperationType opType) throws IOException {
    final DatanodeDescriptor dn = datanodeManager.getDatanodeByStorageId(storageId);
    if (dn == null) {
      
      List<InvalidatedBlock> invBlocks = findInvBlocksbyStorageId(storageId, opType);
      
      if (invBlocks != null) {
        for (InvalidatedBlock ib : invBlocks) {
          removeInvBlock(ib, opType);
        }
      }

      return 0;
    }
    final List<Block> toInvalidate = invalidateWork(storageId, dn, opType);
    if (toInvalidate == null) {
      return 0;
    }

    if (NameNode.stateChangeLog.isInfoEnabled()) {
      NameNode.stateChangeLog.info("BLOCK* " + getClass().getSimpleName()
              + ": ask " + dn.getName() + " to delete " + toInvalidate);
    }
    return toInvalidate.size();
  }
  
  private void removeInvBlock(final InvalidatedBlock ib, TransactionalRequestHandler.OperationType opType) throws IOException {
    TransactionalRequestHandler removeInvBlockHandler = new TransactionalRequestHandler(opType) {

      @Override
      public Object performTask() throws PersistanceException, IOException {
        EntityManager.remove(ib);
        return null;
      }

      @Override
      public void acquireLock() throws PersistanceException, IOException {
        // No need to acquire lock
      }
    };
    removeInvBlockHandler.setParams(ib).handle();
  }
  
  private List<InvalidatedBlock> findInvBlocksbyStorageId(final String sid, TransactionalRequestHandler.OperationType opType) throws IOException {
    return (List<InvalidatedBlock>) new LightWeightRequestHandler(opType) {
      @Override
      public Object performTask() throws PersistanceException, IOException {
        InvalidateBlockDataAccess da = (InvalidateBlockDataAccess) StorageFactory.getDataAccess(InvalidateBlockDataAccess.class);
        return da.findInvalidatedBlockByStorageId(sid);
      }
    }.handle();
  }

  private synchronized List<Block> invalidateWork(
          final String storageId, final DatanodeDescriptor dn, TransactionalRequestHandler.OperationType opType) throws IOException {
    final List<InvalidatedBlock> invBlocks = findInvBlocksbyStorageId(storageId, opType);
    if (invBlocks == null || invBlocks.isEmpty()) {
      return null;
    }

    // # blocks that can be sent in one message is limited
    final int limit = datanodeManager.blockInvalidateLimit;
    final List<Block> toInvalidate = new ArrayList<Block>(limit);
    final Iterator<InvalidatedBlock> it = invBlocks.iterator();
    for (int count = 0; count < limit && it.hasNext(); count++) {
      InvalidatedBlock invBlock = it.next();
      toInvalidate.add(new Block(invBlock.getBlockId(),
              invBlock.getNumBytes(), invBlock.getGenerationStamp()));
      removeInvBlock(invBlock, opType);
    }

    dn.addBlocksToBeInvalidated(toInvalidate);
    return toInvalidate;
  }
}
