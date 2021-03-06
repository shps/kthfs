package org.apache.hadoop.hdfs;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockInfo;
import org.apache.hadoop.hdfs.server.namenode.INode;
import org.apache.hadoop.hdfs.server.namenode.INodeFile;
import org.apache.hadoop.hdfs.server.namenode.persistance.data_access.entity.BlockInfoDataAccess;
import org.apache.hadoop.hdfs.server.namenode.persistance.data_access.entity.InodeDataAccess;
import org.apache.hadoop.hdfs.server.namenode.persistance.storage.StorageConnector;
import org.apache.hadoop.hdfs.server.namenode.persistance.storage.StorageException;
import org.apache.hadoop.hdfs.server.namenode.persistance.storage.StorageFactory;

/**
 *
 * @author Hooman <hooman@sics.se>
 */
public class RowLevelLockMicroBenchmark {

  final static Log LOG = LogFactory.getLog(RowLevelLockMicroBenchmark.class);
  final int size;
  final int numThreads;
  final int opsPerThread;
  final String[][] files;
  LinkedList<INode> inodes = null;
  INode root = null; // root
  INode parent = null; // child of root and parent of all files
  final boolean lockOnRoot;
  final int numSharedDirs; // the number of dirs between root and parent
  final String middlePrefix = "middle";
  static final String READ_COMMIT = "rc"; // benchmark read-commit
  static final String READ = "r"; // benchmark read-lock
  static final String WRITE = "w"; // benchmark write-lock
  static final int DEFAULT_SIZE = 20000;
  static final int DEFEAULT_NUM_THREADS = 40;
  static final int DEFAULT_NUM_SHARED_DIR = 6;
  static final boolean DEFAULT_LOCK_ON_ROOT = true;
  static final String DEFAULT_BENCH_MODE = "r";

  public RowLevelLockMicroBenchmark(int size, int numThreads, int numSharedDirs,
          boolean lockOnRoot) throws StorageException {
    this.size = size;
    this.numThreads = numThreads;
    this.opsPerThread = size / numThreads;
    this.lockOnRoot = lockOnRoot;
    this.numSharedDirs = numSharedDirs;
    files = new String[numThreads][opsPerThread];
    buildData();
  }

  private void buildData() throws StorageException {
    inodes = buildDataStructures(files);
  }

  public void testReadCommit() throws InterruptedException {
    long rcTime = 0;
    Thread[] threads = new Thread[numThreads];
    CyclicBarrier barrier = new CyclicBarrier(numThreads);
    CountDownLatch latch = new CountDownLatch(numThreads);

    for (int i = 0; i < threads.length; i++) {
      Runnable rcReader = buildAReadCommitedReader(root, parent, barrier, latch, files[i]);
      threads[i] = new Thread(rcReader);
    }
    LOG.fatal("Reading data by read-commit...");
    rcTime = measureReadTime(threads, latch);
    LOG.fatal(String.format("Read-Commit time: %d", rcTime));
  }

  public void testReadLock() throws InterruptedException {
    long rTime = 0;
    CyclicBarrier barrier = new CyclicBarrier(numThreads);
    CountDownLatch latch = new CountDownLatch(numThreads);
    Thread[] threads = new Thread[numThreads];

    for (int i = 0; i < threads.length; i++) {
      Runnable rReader = buildAReadLockReader(root, parent, barrier, latch, files[i]);
      threads[i] = new Thread(rReader);
    }

    LOG.fatal("Reading data by read-lock...");
    rTime = measureReadTime(threads, latch);
    LOG.fatal(String.format("Read-lock time: %d", rTime));
  }

  public void testWriteLock() throws StorageException, InterruptedException {
    long wTime = 0;

    CyclicBarrier barrier = new CyclicBarrier(numThreads);
    CountDownLatch latch = new CountDownLatch(numThreads);
    Thread[] threads = new Thread[numThreads];

    for (int i = 0; i < threads.length; i++) {
      Runnable wReader = buildAWriteLockReader(root, parent, barrier, latch, files[i]);
      threads[i] = new Thread(wReader);
    }
    LOG.fatal("Reading data by write-lock...");
    wTime = measureReadTime(threads, latch);
    LOG.fatal(String.format("write-lock time: %d", wTime));
  }

  private long measureReadTime(Thread[] threads, CountDownLatch latch) throws InterruptedException {
    long start = System.currentTimeMillis();
    for (int i = 0; i < threads.length; i++) {
      threads[i].start();
    }
    latch.await();
    long runTime = System.currentTimeMillis() - start;
    threads = null;
    latch = null;
    System.gc();
    return runTime;
  }

  private Runnable buildAReadCommitedReader(final INode root, final INode parent,
          final CyclicBarrier barrier, final CountDownLatch latch, final String[] files) {
    Runnable rcReader = new Runnable() {

      @Override
      public void run() {
        try {
          barrier.await();
          StorageConnector connector = StorageFactory.getConnector();
          connector.readCommitted();
          for (int i = 0; i < files.length; i++) {
            connector.beginTransaction();

            if (lockOnRoot) {
              INode readParent = readParent(root, parent);
            }
            INode readFile = readINode(parent.getId(), files[i]);
            assert readFile != null && readFile.getName().equals(files[i]);
            readBlocks(readFile.getId()); // Just to simulate the way getBlockLocations acquire locks, this will return an empty list.
            Thread.sleep(10); // simulating of getBlockLocation operation process
            connector.commit();
          }
          latch.countDown();
        } catch (InterruptedException ex) {
          Logger.getLogger(TestTransactionalOperations.class.getName()).log(Level.SEVERE, null, ex);
          assert false : ex.getMessage();
        } catch (BrokenBarrierException ex) {
          Logger.getLogger(TestTransactionalOperations.class.getName()).log(Level.SEVERE, null, ex);
          assert false : ex.getMessage();
        } catch (StorageException ex) {
          Logger.getLogger(TestTransactionalOperations.class.getName()).log(Level.SEVERE, null, ex);
          assert false : ex.getMessage();
        }
      }
    };
    return rcReader;
  }

  private Runnable buildAReadLockReader(final INode root, final INode parent,
          final CyclicBarrier barrier, final CountDownLatch latch, final String[] files) {
    Runnable rReader = new Runnable() {

      @Override
      public void run() {
        try {
          barrier.await();
          StorageConnector connector = StorageFactory.getConnector();
          connector.readLock(); // taking read-lock
          for (int i = 0; i < files.length; i++) {
            connector.beginTransaction();

            if (lockOnRoot) {
              INode readParent = readParent(root, parent);
            }
            INode readFile = readINode(parent.getId(), files[i]);
            assert readFile != null && readFile.getName().equals(files[i]);
            readBlocks(readFile.getId()); // Just to simulate the way getBlockLocations acquire locks, this will return an empty list.
            Thread.sleep(10); // simulating of getBlockLocation operation process
            connector.commit();
          }
          latch.countDown();
        } catch (InterruptedException ex) {
          Logger.getLogger(TestTransactionalOperations.class.getName()).log(Level.SEVERE, null, ex);
          assert false : ex.getMessage();
        } catch (BrokenBarrierException ex) {
          Logger.getLogger(TestTransactionalOperations.class.getName()).log(Level.SEVERE, null, ex);
          assert false : ex.getMessage();
        } catch (StorageException ex) {
          Logger.getLogger(TestTransactionalOperations.class.getName()).log(Level.SEVERE, null, ex);
          assert false : ex.getMessage();
        }
      }
    };
    return rReader;
  }

  private Runnable buildAWriteLockReader(final INode root, final INode parent,
          final CyclicBarrier barrier, final CountDownLatch latch, final String[] files) {
    Runnable wReader = new Runnable() {

      @Override
      public void run() {
        try {
          barrier.await();
          StorageConnector connector = StorageFactory.getConnector();
          connector.readLock(); // taking read-lock
          for (int i = 0; i < files.length; i++) {
            connector.beginTransaction();
            if (lockOnRoot) {
              INode readParent = readParent(root, parent);
            }
            connector.writeLock(); // taking write-lock
            INode readFile = readINode(parent.getId(), files[i]);
            assert readFile != null && readFile.getName().equals(files[i]);
            readBlocks(readFile.getId()); // Just to simulate the way getBlockLocations acquire locks, this will return an empty list.
            Thread.sleep(10); // simulating of getBlockLocation operation process
            connector.commit();
          }
          latch.countDown();
        } catch (InterruptedException ex) {
          Logger.getLogger(TestTransactionalOperations.class.getName()).log(Level.SEVERE, null, ex);
          assert false : ex.getMessage();
        } catch (BrokenBarrierException ex) {
          Logger.getLogger(TestTransactionalOperations.class.getName()).log(Level.SEVERE, null, ex);
          assert false : ex.getMessage();
        } catch (StorageException ex) {
          Logger.getLogger(TestTransactionalOperations.class.getName()).log(Level.SEVERE, null, ex);
          assert false : ex.getMessage();
        }
      }
    };
    return wReader;
  }

  private INode readINode(long parentId, String name) throws StorageException {
    InodeDataAccess da = (InodeDataAccess) StorageFactory.getDataAccess(InodeDataAccess.class);
    return da.findInodeByNameAndParentId(name, parentId);
  }

  private INode readParent(INode root, INode parent) throws StorageException {
    INode readRoot = readINode(root.getParentId(), root.getName());
    assert readRoot.equals(root);
    long pid = readRoot.getId();
    for (int i = 0; i < numSharedDirs; i++) {
      INode middle = readINode(pid, middlePrefix + i);
      pid = middle.getId();
    }
    INode readParent = readINode(pid, parent.getName());
    assert readParent.equals(parent);
    return readParent;
  }

  private Collection<BlockInfo> readBlocks(long inodeId) throws StorageException {
    BlockInfoDataAccess da = (BlockInfoDataAccess) StorageFactory.getDataAccess(BlockInfoDataAccess.class);
    return da.findByInodeId(inodeId);
  }

  private LinkedList<INode> buildDataStructures(String[][] files) throws StorageException {
    Random rand = new Random();
    String prefix = "t";
    LinkedList<INode> inodes = new LinkedList<INode>();
    root = createInodeFile(0L, "root", -1L);
    inodes.add(root);
    int inodeId = 1;
    long pid = root.getId();
    for (int i = 0; i < numSharedDirs; i++) {
      inodes.add(createInodeFile(inodeId, middlePrefix + i, pid));
      pid = inodeId;
      inodeId = inodeId + 1;
    }
    parent = createInodeFile(inodeId, "parent", pid);
    inodes.add(parent);
    for (int i = 0; i < files.length; i++) {
      String threadFiles[] = files[i];
      for (int j = 0; j < threadFiles.length; j++) {
        threadFiles[j] = i + prefix + j;
        inodes.add(createInodeFile(rand.nextLong(), threadFiles[j], parent.getId()));
      }
    }

    HdfsConfiguration conf = new HdfsConfiguration();
    StorageFactory.setConfiguration(conf);
    StorageFactory.getConnector().formatStorage();
    System.out.println("Building the data...");
    StorageFactory.getConnector().beginTransaction();
    InodeDataAccess da = (InodeDataAccess) StorageFactory.getDataAccess(InodeDataAccess.class);
    da.prepare(new LinkedList<INode>(), inodes, new LinkedList<INode>());
    StorageFactory.getConnector().commit();

    return inodes;
  }

  private INodeFile createInodeFile(long id, String name, long pid) {
    PermissionStatus defaultPermission = new PermissionStatus("", "", FsPermission.getDefault());
    INodeFile file = new INodeFile(false, defaultPermission, (short) 2, 0, 0, 0);
    file.setParentId(pid);
    file.setName(name);
    file.setId(id);
    return file;
  }

  public static void main(String[] args) throws StorageException, InterruptedException {
    String benchMode = null;
    int size = DEFAULT_SIZE;
    int numThreads = DEFEAULT_NUM_THREADS;
    int numSharedDirs = DEFAULT_NUM_SHARED_DIR;
    boolean lockOnRoot = DEFAULT_LOCK_ON_ROOT;

    if (args == null || args.length == 0) {
//      printMenu();
      benchMode = DEFAULT_BENCH_MODE;
    } else {
      // TODO: make it to enter arguments in any order.
      if (args[0].equals("-l")) {
        benchMode = args[1];
      } else {
        printMenu();
      }

      if (args[2].equals("-s")) {
        size = Integer.valueOf(args[3]);
      }

      if (args[4].equals("-t")) {
        numThreads = Integer.valueOf(args[5]);
      }

      if (args[6].equals("-d")) {
        numSharedDirs = Integer.valueOf(args[7]);
      }

      if (args[8].equals("-r")) {
        lockOnRoot = Boolean.valueOf(args[9]);
      }
    }

    System.out.println(String.format("Starting to run operation %s with parameters: \nsize=%d,\nthreads=%d\nsharedDirs=%d\nlockOnRoot=%s",
            benchMode, size, numThreads, numSharedDirs, Boolean.toString(lockOnRoot)));
    RowLevelLockMicroBenchmark bm = new RowLevelLockMicroBenchmark(size, numThreads, numSharedDirs, lockOnRoot);

    if (benchMode.equals(READ_COMMIT)) {
      bm.testReadCommit();
    } else if (benchMode.equals(READ)) {
      bm.testReadLock();
    } else if (benchMode.equals(WRITE)) {
      bm.testWriteLock();
    } else {
      printMenu();
    }

  }

  private static void printMenu() {
    System.err.println("-l [rc | r | w] -s [size] -t [num of threads] -d [num of shared directories] -r [lock on root: true or false]");
    System.exit(-1);
    // TODO
  }
}
