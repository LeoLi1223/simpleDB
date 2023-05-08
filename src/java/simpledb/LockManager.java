package simpledb;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;

public class LockManager {
    Map<PageId, TransactionId> exclusiveLock;
    Map<PageId, HashSet<TransactionId>> sharedLock;

    public LockManager() {
        exclusiveLock = new HashMap<>();
        sharedLock = new HashMap<>();
    }

    /**
     * Try grabbing the lock on the specific page for the specific transaction.
     * @param tid the transaction asking for the lock
     * @param pid the page to be locked on
     * @param perm determine type of lock to grant. exclusive lock if READ_WRITE. shared lock if READ_ONLY.
     */
    public synchronized void acquireLock(TransactionId tid, PageId pid, Permissions perm) {
        if (perm.equals(Permissions.READ_ONLY)) {
            while (!acquireSharedLock(tid, pid)) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        } else {
            while (!acquireExclusiveLock(tid, pid)) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public synchronized void releaseLock(TransactionId tid, PageId pid) {
        sharedLock.getOrDefault(pid, new HashSet<>()).remove(tid);
        exclusiveLock.remove(pid);
        notify();
    }

    public synchronized boolean holdsSharedLock(TransactionId tid, PageId pid) {
        return sharedLock.getOrDefault(pid, new HashSet<>()).contains(tid);
    }

    public synchronized boolean holdsExclusiveLock(TransactionId tid, PageId pid) {
        return tid.equals(exclusiveLock.getOrDefault(pid, null));
    }

    private boolean acquireSharedLock(TransactionId tid, PageId pid) {
        if (exclusiveLock.containsKey(pid) && exclusiveLock.get(pid).equals(tid)) {
            return true;
        }
        if (!exclusiveLock.containsKey(pid)) {
            if (!sharedLock.containsKey(pid)) {
                sharedLock.put(pid, new HashSet<>());
            }
            sharedLock.get(pid).add(tid);
            return true;
        }
        return false;
    }

    private boolean acquireExclusiveLock(TransactionId tid, PageId pid) {
        // Not grant the exclusive lock if some txn holds the exclusive lock.
        if (exclusiveLock.containsKey(pid)) {
            return false;
        }
        // grant the exclusive lock if no transaction holds a shared lock on the page
        if (!sharedLock.containsKey(pid)) {
            exclusiveLock.put(pid, tid);
            return true;
        }
        // Now, some txn holds shared lock on the page, and no txn hold exclusive lock.
        HashSet<TransactionId> shared = sharedLock.get(pid);
        if (shared.size() == 1 && shared.contains(tid)) {
            // upgrade the shared lock to the exclusive lock if only tid has
            // the shared lock on that page
            sharedLock.get(pid).remove(tid);
            exclusiveLock.put(pid, tid);
            return true;
        }
        return false;
    }
}
