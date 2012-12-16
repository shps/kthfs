/*
 * Copyright 2012 Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

/**
 *
 * @author salman
 */
public class Leader implements Comparable<Leader>
{

    public static enum Finder implements org.apache.hadoop.hdfs.server.namenode.FinderType<Leader>
    {
        ById, ByCounter, All;

        @Override
        public Class getType()
        {
            return Leader.class;
        }
    }

    public static enum Counter implements org.apache.hadoop.hdfs.server.namenode.CounterType<Leader>
    {
        AllById;
        @Override
        public Class getType()
        {
            return Leader.class;
        }
    }
    
    private long id;
    private long counter;
    private long timeStamp;
    private String hostName;
    private int avgRequestProcessingLatency;
    private int partitionVal;

    public Leader(long id, long counter, long timeStamp, String hostName, int avgRequestProcessingLatency, int partitionVal)
    {
        this.id = id;
        this.counter = counter;
        this.timeStamp = timeStamp;
        this.hostName = hostName;
        this.avgRequestProcessingLatency = avgRequestProcessingLatency;
        this.partitionVal = partitionVal;
        
        if(partitionVal != 0)
        {
            throw new IllegalStateException("Leader.java: partition_val has to be zero");
        }
    }

    public Leader(long id, long counter, long timeStamp, String hostName)
    {
        this.id = id;
        this.counter = counter;
        this.timeStamp = timeStamp;
        this.hostName = hostName;
        this.avgRequestProcessingLatency = 0;
        this.partitionVal = 0;
    }
    
    
    public long getId()
    {
        return id;
    }

    public void setId(long id)
    {
        this.id = id;
    }

    public long getCounter()
    {
        return counter;
    }

    public void setCounter(long counter)
    {
        this.counter = counter;
    }

    public long getTimeStamp()
    {
        return timeStamp;
    }

    public void setTimeStamp(long timeStamp)
    {
        this.timeStamp = timeStamp;
    }

    public String getHostName()
    {
        return hostName;
    }

    public void setHostName(String hostName)
    {
        this.hostName = hostName;
    }

    public int getAvgRequestProcessingLatency()
    {
        return avgRequestProcessingLatency;
    }

    public void setAvgRequestProcessingLatency(int avgRequestProcessingLatency)
    {
        this.avgRequestProcessingLatency = avgRequestProcessingLatency;
    }

    public int getPartitionVal()
    {
        return partitionVal;
    }

    public void setPartitionVal(int partitionVal)
    {
        this.partitionVal = partitionVal;
    }

    
    
    @Override
    public int compareTo(Leader l)
    {
        //comparing (sorting) based on the
        //id of the Namenode

        if (this.id < l.getId())
        {
            return -1;
        } else if (this.id == l.getId())
        {
            return 0;
        } else if (this.id > l.getId())
        {
            return 1;
        } else
        {
            throw new IllegalStateException("Leader.java: compareTo(...) is confused.");
        }
    }

    @Override
    public boolean equals(Object obj)
    {
        if (obj instanceof Leader)
        {
            Leader l = (Leader) obj;
            //both are equal if all the fields match
            if (this.id == l.getId()
                    && this.counter == l.getCounter()
                    && this.hostName == l.getHostName()
                    && this.timeStamp == l.getTimeStamp())
            {
                return true;
            } else
            {
                return false;
            }
        } else
        {
            throw new ClassCastException("Leader.java: equals(...) can not compare the objects");
        }
    }

    @Override
    public int hashCode()
    {
        int hash = 1;
        hash = hash * 31 + this.hostName.hashCode();
        hash = hash * 31 + (new Long(id)).hashCode();
        hash = hash * 31 + (new Long(counter)).hashCode();
        hash = hash * 31 + (new Long(timeStamp)).hashCode();
        return hash;
    }

    @Override
    public String toString()
    {
        return this.id + ", " + hostName + ", " + counter + ", " + timeStamp;
    }
}