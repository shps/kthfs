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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSClientAdapter;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.TestFileCreation;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfoUnderConstruction;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.BlockUCState;
import org.apache.hadoop.hdfs.server.namenode.lock.TransactionLockManager;
import org.apache.hadoop.hdfs.server.namenode.persistance.PersistanceException;
import org.apache.hadoop.hdfs.server.namenode.persistance.RequestHandler.OperationType;
import org.apache.hadoop.hdfs.server.namenode.persistance.TransactionalRequestHandler;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocols;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestBlockUnderConstruction {

  static final String BASE_DIR = "/test/TestBlockUnderConstruction";
  static final int BLOCK_SIZE = 8192; // same as TestFileCreation.blocksize
  static final int NUM_BLOCKS = 5;  // number of blocks to write
  private static MiniDFSCluster cluster;
  private static DistributedFileSystem hdfs;

  @BeforeClass
  public static void setUp() throws Exception {
    Configuration conf = new HdfsConfiguration();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(3).build();
    cluster.waitActive();
    hdfs = (DistributedFileSystem) cluster.getFileSystem();
  }

  @AfterClass
  public static void tearDown() throws Exception {
    if (hdfs != null) {
      hdfs.close();
    }
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  void writeFile(Path file, FSDataOutputStream stm, int size)
          throws IOException {
    long blocksBefore = stm.getPos() / BLOCK_SIZE;

    TestFileCreation.writeFile(stm, BLOCK_SIZE);
    int blocksAfter = 0;
    // wait until the block is allocated by DataStreamer
    BlockLocation[] locatedBlocks;
    while (blocksAfter <= blocksBefore) {
      locatedBlocks = DFSClientAdapter.getDFSClient(hdfs).getBlockLocations(
              file.toString(), 0L, BLOCK_SIZE * NUM_BLOCKS);
      blocksAfter = locatedBlocks == null ? 0 : locatedBlocks.length;
    }
  }

  private void verifyFileBlocks(final String file,
          final boolean isFileOpen) throws IOException {
    new TransactionalRequestHandler(OperationType.VERIFY_FILE_BLOCKS) {

      @Override
      public Object performTask() throws PersistanceException, IOException {
        FSNamesystem ns = cluster.getNamesystem();
        INodeFile inode = ns.dir.getFileINode(file);
        assertTrue("File does not exist: " + inode.toString(), inode != null);
        assertTrue("File " + inode.toString()
                + " isUnderConstruction = " + inode.isUnderConstruction()
                + " expected to be " + isFileOpen,
                inode.isUnderConstruction() == isFileOpen);
        List<BlockInfo> blocks = inode.getBlocks();
        assertTrue("File does not have blocks: " + inode.toString(),
                blocks != null && !blocks.isEmpty());

        int idx = 0;
        BlockInfo curBlock;
        // all blocks but the last two should be regular blocks
        for (; idx < blocks.size() - 2; idx++) {
          curBlock = blocks.get(idx);
          assertTrue("Block is not complete: " + curBlock,
                  curBlock.isComplete());
          assertTrue("Block is not in BlocksMap: " + curBlock,
                  ns.getBlockManager().getStoredBlock(curBlock).equals(curBlock));
        }

        // the penultimate block is either complete or
        // committed if the file is not closed
        if (idx > 0) {
          curBlock = blocks.get(idx - 1); // penultimate block
          assertTrue("Block " + curBlock
                  + " isUnderConstruction = " + inode.isUnderConstruction()
                  + " expected to be " + isFileOpen,
                  (isFileOpen && curBlock.isComplete())
                  || (!isFileOpen && !curBlock.isComplete()
                  == (curBlock.getBlockUCState()
                  == BlockUCState.COMMITTED)));
          assertTrue("Block is not in BlocksMap: " + curBlock,
                  ns.getBlockManager().getStoredBlock(curBlock).equals(curBlock));
        }

        // The last block is complete if the file is closed.
        // If the file is open, the last block may be complete or not. 
        curBlock = blocks.get(idx); // last block
        if (!isFileOpen) {
          assertTrue("Block " + curBlock + ", isFileOpen = " + isFileOpen,
                  curBlock.isComplete());
        }
        assertTrue("Block is not in BlocksMap: " + curBlock,
                ns.getBlockManager().getStoredBlock(curBlock).equals(curBlock));
        return null;
      }

      @Override
      public void acquireLock() throws PersistanceException, IOException {
        TransactionLockManager lm = new TransactionLockManager();
        lm.addINode(TransactionLockManager.INodeResolveType.ONLY_PATH,
                TransactionLockManager.INodeLockType.READ,
                new String[]{file});
        lm.addBlock(TransactionLockManager.LockType.READ);
        lm.acquire();
      }
    }.handle();
  }

  @Test
  public void testBlockCreation() throws IOException {
    Path file1 = new Path(BASE_DIR, "file1.dat");
    FSDataOutputStream out = TestFileCreation.createFile(hdfs, file1, 3);

    for (int idx = 0; idx < NUM_BLOCKS; idx++) {
      // write one block
      writeFile(file1, out, BLOCK_SIZE);
      // verify consistency
      verifyFileBlocks(file1.toString(), true);
    }

    // close file
    out.close();
    // verify consistency
    verifyFileBlocks(file1.toString(), false);
  }

  /**
   * Test NameNode.getBlockLocations(..) on reading un-closed files.
   */
  @Test
  public void testGetBlockLocations() throws IOException {
    final NamenodeProtocols namenode = cluster.getNameNodeRpc();
    final Path p = new Path(BASE_DIR, "file2.dat");
    final String src = p.toString();
    final FSDataOutputStream out = TestFileCreation.createFile(hdfs, p, 3);

    // write a half block
    int len = BLOCK_SIZE >>> 1;
    writeFile(p, out, len);

    for (int i = 1; i < NUM_BLOCKS;) {
      // verify consistency
      final LocatedBlocks lb = namenode.getBlockLocations(src, 0, len);
      final List<LocatedBlock> blocks = lb.getLocatedBlocks();
      assertEquals(i, blocks.size());
      final Block b = blocks.get(blocks.size() - 1).getBlock().getLocalBlock();
      assertTrue(b instanceof BlockInfoUnderConstruction);

      if (++i < NUM_BLOCKS) {
        // write one more block
        writeFile(p, out, BLOCK_SIZE);
        len += BLOCK_SIZE;
      }
    }
    // close file
    out.close();
  }
}
