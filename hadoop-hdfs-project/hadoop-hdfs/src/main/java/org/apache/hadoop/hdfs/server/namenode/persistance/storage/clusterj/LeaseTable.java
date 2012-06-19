package org.apache.hadoop.hdfs.server.namenode.persistance.storage.clusterj;

import com.mysql.clusterj.annotation.*;


/**
 * @author wmalik
 *
 * This is a ClusterJ interface for interacting with the "Lease" table
 *
 */
@PersistenceCapable(table="Lease")
public interface LeaseTable {
	
	@PrimaryKey
	@Column(name = "holder")
	String getHolder();
	void setHolder(String holder);
	
	
	@Column(name = "lastUpdate")
	long getLastUpdate();
	void setLastUpdate(long last_upd);
	
	
	@Column(name = "holderID")
	int getHolderID();
	void setHolderID(int holder_id);

}
