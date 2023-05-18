package simpledb;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LockManager {
    private Map<PageId, TransactionId> exclusiveLock;
    private Map<PageId, HashSet<TransactionId>> sharedLock;
    private Map<TransactionId, Set<TransactionId>> waitfor; // wait-for graph in the adjacency list form

    public LockManager() {
        exclusiveLock = new ConcurrentHashMap<>();
        sharedLock = new ConcurrentHashMap<>();
        waitfor = new ConcurrentHashMap<>();
    }

    /**
     * Try grabbing the lock on the specific page for the specific transaction or pass if the lock is already held.
     * @param tid the transaction asking for the lock
     * @param pid the page to be locked on
     * @param perm determine type of lock to grant. exclusive lock if READ_WRITE. shared lock if READ_ONLY.
     */
    public synchronized void acquireLock(TransactionId tid, PageId pid, Permissions perm) throws TransactionAbortedException {
        // detect deadlock whenever attempting to acquire a lock (design decision)
        // create an empty set to store wait-for tids.
        HashSet<TransactionId> waitFor = new HashSet<>();
        // always wait for the txn holding the exclusive lock. Should not be null!
        TransactionId ex = exclusiveLockedOn(pid);
        if (ex != null) {
            waitFor.add(ex);
        }
        if (perm.equals(Permissions.READ_WRITE)) {
            // wait for txn hold shared lock on pid when write is permitted.
            waitFor.addAll(sharedLockedOn(pid));
        }
        waitFor.remove(tid); // self loop is not considered.
        // update wait-for graph
        addWaitFor(tid, waitFor);
        // if there is a cycle in wait-for graph, abort the current txn. (design decision)
        System.out.println("before-hasDeadLock() wait-for graph: " + this.waitfor);
        if (hasDeadLock()) {
            System.out.println("after-deadlock() wait-for graph: " + this.waitfor);
            // do not call transactionComplete(tid, commit=False) in lab3
            throw new TransactionAbortedException();
        }

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
        System.out.println("--RELEASE Thread: " + Thread.currentThread().getId() + " Transaction: " + tid.getId() + "\nPage: " + pid + "\nShared: " + sharedLock + "\nExclusive: " + exclusiveLock + "\n");
        notifyAll();
    }

    /**
     * Release all locks acquired by the tid
     * @param tid by which locks to be released acquired
     */
    public synchronized void releaseAll(TransactionId tid) {
        Set<PageId> lockOnTid = getLockOnTid(tid);
        for (PageId pid : lockOnTid) {
            sharedLock.getOrDefault(pid, new HashSet<>()).remove(tid);
            exclusiveLock.remove(pid);
        }
        // update wait-for graph
        for (TransactionId txnid : waitfor.keySet()) {
            waitfor.get(txnid).remove(tid);
        }
        waitfor.remove(tid);
    }

    /**
     * Check if there is a cycle in the wait-for graph (in the form of an adjacency list)
     * @return True if a cycle exists in the wait-for graph. False otherwise.
     */
    private synchronized boolean hasDeadLock() {
        Set<TransactionId> visited = new HashSet<>();
        LinkedList<TransactionId> onPath = new LinkedList<>();
        // account for all vertices (transactions) in wait-for graph
        for (TransactionId tid : waitfor.keySet()) {
            if (traverse(tid, visited, onPath)) return true;
        }
        return false;
    }

    private synchronized boolean traverse(TransactionId tid, Set<TransactionId> visited, List<TransactionId> onPath) {
        if (onPath.contains(tid)) {
            return true;
        }
        if (visited.contains(tid)) {
            return false;
        }
        visited.add(tid);
        onPath.add(tid);
        for (TransactionId t: waitfor.get(tid)) {
            if (traverse(t, visited, onPath)) return true;
        }

        onPath.remove(tid);
        return false;
    }

    /**
     * add all tids that the current tid waits for to the wait-for graph.
     * @param tid current tid to add
     * @param waitFor a collection of tids that the current tid waits for.
     */
    private synchronized void addWaitFor(TransactionId tid, Collection<TransactionId> waitFor) {
        if (!this.waitfor.containsKey(tid)) {
            this.waitfor.put(tid, new HashSet<>());
        }
        this.waitfor.get(tid).addAll(waitFor);
    }

    public synchronized boolean holdsSharedLock(TransactionId tid, PageId pid) {
        return sharedLock.getOrDefault(pid, new HashSet<>()).contains(tid);
    }

    public synchronized boolean holdsExclusiveLock(TransactionId tid, PageId pid) {
        return tid.equals(exclusiveLock.getOrDefault(pid, null));
    }

    public synchronized HashSet<TransactionId> sharedLockedOn(PageId pid) {
        return this.sharedLock.getOrDefault(pid, new HashSet<>());
    }

    /**
     * return tid holding the exclusive lock on the pid or null if no such tid.
     * @param pid pid that exclusive lock on
     * @return if pid is locked exclusively, return the tid holding the lock. Otherwise, return null
     */
    public synchronized TransactionId exclusiveLockedOn(PageId pid) {
        return this.exclusiveLock.get(pid);
    }

    public synchronized Set<PageId> getLockOnTid(TransactionId tid) {
        Set<PageId> toRet = new HashSet<>();
        for (PageId pid : sharedLock.keySet()) {
            if (sharedLock.get(pid).contains(tid)) {
                toRet.add(pid);
            }
        }
        for (PageId pid : exclusiveLock.keySet()) {
            if (tid.equals(exclusiveLock.get(pid))) {
                toRet.add(pid);
            }
        }
        return toRet;
    }

    private synchronized boolean acquireSharedLock(TransactionId tid, PageId pid) {
        // the txn has already holds lock on the pid
        if (holdsSharedLock(tid, pid) || holdsExclusiveLock(tid, pid)) {
            return true;
        }
        if (exclusiveLockedOn(pid) != null) {
            return false;
        }

        // Now, no txn holds exclusive lock on the pid
        if (!sharedLock.containsKey(pid)) {
            sharedLock.put(pid, new HashSet<>());
        }
        sharedLock.get(pid).add(tid);
        return true;
    }

    private synchronized boolean acquireExclusiveLock(TransactionId tid, PageId pid) {
        // If the txn already holds the exclusive lock on the pid, pass
        if (holdsExclusiveLock(tid, pid)) {
            return true;
        }
        // Not grant the exclusive lock if some else txn holds the exclusive lock.
        if (exclusiveLock.containsKey(pid)) {
            return false;
        }
        // grant the exclusive lock if no transaction holds a shared lock on the page
        if (sharedLockedOn(pid).isEmpty()) {
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
        // multiple txn hold shared lock on the pid, then cannot grant exclusive lock.
        return false;
    }
}
