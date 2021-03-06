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

/**
 * A immutable object that stores the number of live replicas and
 * the number of decommissined Replicas.
 */
public class NumberReplicas {
  private int liveReplicas;
  private int decommissionedReplicas;
  private int corruptReplicas;
  private int excessReplicas;

  NumberReplicas() {
    initialize(0, 0, 0, 0);
  }
  
  NumberReplicas(int live, int decommissioned, int corrupt, int excess) {
    initialize(live, decommissioned, corrupt, excess);
  }

  void initialize(int live, int decommissioned, int corrupt, int excess) {
    liveReplicas = live;
    decommissionedReplicas = decommissioned;
    corruptReplicas = corrupt;
    excessReplicas = excess;
  }

  public int liveReplicas() {
    return liveReplicas;
  }
  public int decommissionedReplicas() {
    return decommissionedReplicas;
  }
  public int corruptReplicas() {
    return corruptReplicas;
  }
  public int excessReplicas() {
    return excessReplicas;
  }
  
    /**
     * @return total number of replicas
     */
    int getTotal() {
      return liveReplicas + decommissionedReplicas + 
           + corruptReplicas + excessReplicas;
    }
  
} 
