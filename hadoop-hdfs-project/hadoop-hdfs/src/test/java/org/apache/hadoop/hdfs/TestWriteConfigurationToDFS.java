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
package org.apache.hadoop.hdfs;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;

import java.io.OutputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Test;

/**
 * Regression test for HDFS-1542, a deadlock between the main thread
 * and the DFSOutputStream.DataStreamer thread caused because
 * Configuration.writeXML holds a lock on itself while writing to DFS.
 */
public class TestWriteConfigurationToDFS {
  public static final Log LOG = LogFactory.getLog(TestWriteConfigurationToDFS.class);
  // The time constraint is not applicable for KTHFS.
  @Test(timeout=600000)
  public void testWriteConf() throws Exception {
    Configuration conf = new HdfsConfiguration();
    conf.setInt(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, 4096);
    LOG.info("Setting conf in: " + System.identityHashCode(conf));
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    FileSystem fs = null;
    OutputStream os = null;
    try {
      fs = cluster.getFileSystem();
      Path filePath = new Path("/testWriteConf.xml");
      os = fs.create(filePath);
      StringBuilder longString = new StringBuilder();
      for (int i = 0; i < 100000; i++) {
        longString.append("hello");
      } // 500KB
      conf.set("foobar", longString.toString());
      conf.writeXml(os);
      os.close();
      os = null;
      fs.close();
      fs = null;
    } finally {
      IOUtils.cleanup(null, os, fs);
      cluster.shutdown();
    }
  }
}