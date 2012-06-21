package org.apache.hadoop.hdfs.server.namenode.persistance.storage.derby;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.hadoop.hdfs.server.namenode.LeasePath;
import org.apache.hadoop.hdfs.server.namenode.persistance.storage.LeasePathStorage;

/**
 *
 * @author Hooman <hooman@sics.se>
 */
public class LeasePathDerby extends LeasePathStorage {

  private DerbyConnector connector = DerbyConnector.INSTANCE;

  @Override
  protected TreeSet<LeasePath> findByHolderId(int holderId) {
    String query = String.format("select * from %s where %s=?", TABLE_NAME, HOLDER_ID);
    Connection conn = connector.obtainSession();
    try {
      PreparedStatement s = conn.prepareStatement(query);
      s.setLong(1, holderId);
      ResultSet rSet = s.executeQuery();
      TreeSet<LeasePath> lpSet = syncLeasePathInstances(rSet, false);
    } catch (SQLException ex) {
      Logger.getLogger(LeasePathDerby.class.getName()).log(Level.SEVERE, null, ex);
    }

    return new TreeSet<LeasePath>();
  }

  @Override
  protected TreeSet<LeasePath> findByPrefix(String prefix) {
    String query = String.format("select * from %s where %s like ?", TABLE_NAME, PATH);
    Connection conn = connector.obtainSession();
    TreeSet<LeasePath> lpSet = null;
    try {
      PreparedStatement s = conn.prepareStatement(query);
      s.setString(1, prefix + "%");
      ResultSet rSet = s.executeQuery();
      lpSet = syncLeasePathInstances(rSet, false);
    } catch (SQLException ex) {
      Logger.getLogger(LeasePathDerby.class.getName()).log(Level.SEVERE, null, ex);
    }

    return lpSet;
  }

  @Override
  protected TreeSet<LeasePath> findAll() {
    String query = String.format("select * from %s", TABLE_NAME);
    Connection conn = connector.obtainSession();
    try {
      PreparedStatement s = conn.prepareStatement(query);
      ResultSet rSet = s.executeQuery();
      return syncLeasePathInstances(rSet, true);
    } catch (SQLException ex) {
      Logger.getLogger(LeasePathDerby.class.getName()).log(Level.SEVERE, null, ex);
    }

    return null;
  }

  @Override
  protected LeasePath findByPKey(String path) {
    String query = String.format("select * from %s where %s=?", TABLE_NAME, PATH);
    LeasePath result = null;
    Connection conn = connector.obtainSession();
    try {
      PreparedStatement s = conn.prepareStatement(query);
      ResultSet rSet = s.executeQuery();
      if (rSet.next()) {
        result = new LeasePath(rSet.getString(PATH), rSet.getInt(HOLDER_ID));
      }
    } catch (SQLException ex) {
      Logger.getLogger(LeasePathDerby.class.getName()).log(Level.SEVERE, null, ex);
    }

    return result;
  }

  @Override
  public void commit() {
    String insert = String.format("insert into %s values(?,?)", TABLE_NAME);
    String delete = String.format("delete from %s where %s=?", TABLE_NAME, HOLDER_ID);

    Connection conn = connector.obtainSession();
    try {
      PreparedStatement insrt = conn.prepareStatement(insert);
      for (LeasePath l : newLPaths.values()) {
        insrt.setLong(1, l.getHolderId());
        insrt.setString(2, l.getPath());
        insrt.addBatch();
      }
      insrt.executeBatch();

      PreparedStatement dlt = conn.prepareStatement(delete);
      for (LeasePath l : removedLPaths.values()) {
        dlt.setString(1, l.getPath());
        dlt.addBatch();
      }
      dlt.executeBatch();
    } catch (SQLException ex) {
      Logger.getLogger(LeasePathDerby.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

  private TreeSet<LeasePath> syncLeasePathInstances(ResultSet rSet, boolean allRead) throws SQLException {
    TreeSet<LeasePath> finalList = new TreeSet<LeasePath>();

    while (rSet.next()) {
      LeasePath lPath = new LeasePath(rSet.getString(PATH), rSet.getInt(HOLDER_ID));
      if (!removedLPaths.containsKey(lPath)) {
        if (this.leasePaths.containsKey(lPath)) {
          lPath = this.leasePaths.get(lPath);
        } else {
          this.leasePaths.put(lPath, lPath);
          this.pathToLeasePath.put(lPath.getPath(), lPath);
        }
        finalList.add(lPath);
        if (allRead) {
          if (holderLeasePaths.containsKey(lPath.getHolderId())) {
            holderLeasePaths.get(lPath.getHolderId()).add(lPath);
          } else {
            TreeSet<LeasePath> lSet = new TreeSet<LeasePath>();
            lSet.add(lPath);
            holderLeasePaths.put(lPath.getHolderId(), lSet);
          }
        }
      }
    }

    return finalList;
  }
}
