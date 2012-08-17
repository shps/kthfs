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
package org.apache.hadoop.hdfs.server.namenode;

import org.apache.hadoop.hdfs.server.namenode.persistance.TransactionalRequestHandler.OperationType;
import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.server.namenode.persistance.PersistanceException;
import org.apache.hadoop.hdfs.server.namenode.persistance.TransactionalRequestHandler;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests to verify safe mode correctness.
 */
public class TestSafeMode {

  /**
   * Verify that the NameNode stays in safemode when dfs.safemode.datanode.min
   * is set to a number greater than the number of live datanodes.
   */
  @Test
  public void testDatanodeThreshold() throws IOException {
    MiniDFSCluster cls = null;
    DistributedFileSystem dffs = null;
    try {
      final Configuration conf = new HdfsConfiguration();
      conf.setInt(DFSConfigKeys.DFS_NAMENODE_SAFEMODE_EXTENSION_KEY, 0);
      conf.setInt(DFSConfigKeys.DFS_NAMENODE_SAFEMODE_MIN_DATANODES_KEY, 1);

      // bring up a cluster with no datanodes
      final MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).numDataNodes(0).format(true).build();
      cls = cluster;
      cluster.waitActive();
      final DistributedFileSystem fs = (DistributedFileSystem) cluster.getFileSystem();
      dffs = fs;
      new TransactionalRequestHandler(OperationType.TEST_DATANODE_THRESHOLD) {

        @Override
        public Object performTask() throws PersistanceException, IOException {
          assertTrue("No datanode started, but we require one - safemode expected",
                  fs.setSafeMode(SafeModeAction.SAFEMODE_GET));

          String tipMsg = cluster.getNamesystem().getSafeModeTip();
          assertTrue("Safemode tip message looks right",
                  tipMsg.contains("The number of live datanodes 0 needs an "
                  + "additional 1 live"));

          // Start a datanode
          cluster.startDataNodes(conf, 1, true, null, null);

          // Wait long enough for safemode check to refire
          try {
            Thread.sleep(1000);
          } catch (InterruptedException ignored) {
          }

          // We now should be out of safe mode.
          assertFalse(
                  "Out of safe mode after starting datanode.",
                  fs.setSafeMode(SafeModeAction.SAFEMODE_GET));
          return null;
        }
      }.handle();
    } finally {
      if (dffs != null) {
        dffs.close();
      }
      if (cls != null) {
        cls.shutdown();
      }
    }
  }
}
