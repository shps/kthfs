package org.apache.hadoop.hdfs.server.blockmanagement;

import java.util.Collections;
import java.util.Comparator;

public class UnderReplicatedBlock {
  public static enum Order implements Comparator<UnderReplicatedBlock> {

    ByLevel() {

      @Override
      public int compare(UnderReplicatedBlock o1, UnderReplicatedBlock o2) {
        if (o1.getLevel() < o2.level) {
          return -1;
        } else {
          return 1;
        }
      }
    };
    
    @Override
    public abstract int compare(UnderReplicatedBlock o1, UnderReplicatedBlock o2);

    public Comparator acsending() {
      return this;
    }

    public Comparator descending() {
      return Collections.reverseOrder(this);
    }
  }

  int level;
  long blockId;

  public UnderReplicatedBlock(int level, long blockId) {
    this.level = level;
    this.blockId = blockId;
  }

  public long getBlockId() {
    return blockId;
  }

  public int getLevel() {
    return level;
  }

  public void setLevel(int level) {
    this.level = level;
  }

  @Override
  public String toString() {
    return "UnderReplicatedBlock{" + "level=" + level + ", blockId=" + blockId + '}';
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    final UnderReplicatedBlock other = (UnderReplicatedBlock) obj;
    if (this.blockId != other.blockId) {
      return false;
    }
    return true;
  }

  @Override
  public int hashCode() {
    int hash = 7;
    hash = 37 * hash + (int) (this.blockId ^ (this.blockId >>> 32));
    return hash;
  }

}