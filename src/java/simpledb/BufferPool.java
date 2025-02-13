package simpledb;

import java.io.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BufferPool manages the reading and writing of pages into memory from
 * disk. Access methods call into it to retrieve pages, and it fetches
 * pages from the appropriate location.
 * <p>
 * The BufferPool is also responsible for locking;  when a transaction fetches
 * a page, BufferPool checks that the transaction has the appropriate
 * locks to read/write the page.
 * 
 * @Threadsafe, all fields are final
 */
public class BufferPool {
    /** Bytes per page, including header. */
    private static final int DEFAULT_PAGE_SIZE = 4096;

    private static int pageSize = DEFAULT_PAGE_SIZE;
    
    /** Default number of pages passed to the constructor. This is used by
    other classes. BufferPool should use the numPages argument to the
    constructor instead. */
    public static final int DEFAULT_PAGES = 50;

    private ConcurrentHashMap<PageId, Page> pidToPage;
    private int numPages;
    private ArrayList<PageId> lruList;
    private LockManager lockManager;
    /**
     * Creates a BufferPool that caches up to numPages pages.
     *
     * @param numPages maximum number of pages in this buffer pool.
     */
    public BufferPool(int numPages) {
        // some code goes here
        this.numPages = numPages;
        pidToPage = new ConcurrentHashMap<>();
        // the first PageId is least recently used.
        lruList = new ArrayList<>();
        lockManager = new LockManager();
    }
    
    public static int getPageSize() {
      return pageSize;
    }
    
    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void setPageSize(int pageSize) {
    	BufferPool.pageSize = pageSize;
    }
    
    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void resetPageSize() {
    	BufferPool.pageSize = DEFAULT_PAGE_SIZE;
    }

    /**
     * Retrieve the specified page with the associated permissions.
     * Will acquire a lock and may block if that lock is held by another
     * transaction.
     * <p>
     * The retrieved page should be looked up in the buffer pool.  If it
     * is present, it should be returned.  If it is not present, it should
     * be added to the buffer pool and returned.  If there is insufficient
     * space in the buffer pool, a page should be evicted and the new page
     * should be added in its place.
     *
     * @param tid the ID of the transaction requesting the page
     * @param pid the ID of the requested page
     * @param perm the requested permissions on the page
     */
    public Page getPage(TransactionId tid, PageId pid, Permissions perm)
        throws TransactionAbortedException, DbException {
        // some code goes here
        // try acquiring lock
        // request for the specific lock on pid
        lockManager.acquireLock(tid, pid, perm); // possibly wait on lockManager
//        System.out.println("--ACQUIRE Thread: " + Thread.currentThread().getId() + " Transaction: " + tid.getId() + "\nPage: " + pid + "\nPerm: " + perm + "\n");

        synchronized (this) {
            if (pidToPage.containsKey(pid)) {
                // remove original node
                lruList.remove(pid);
                // move to the end
                lruList.add(lruList.size(), pid);
                return pidToPage.get(pid);
            }
            if (pidToPage.size() == numPages) {
                // evict lru pid
                try {
                    evictPage();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            DbFile file = Database.getCatalog().getDatabaseFile(pid.getTableId());
            Page page = file.readPage(pid);
            pidToPage.put(pid, page);
            lruList.remove(pid);
            lruList.add(lruList.size(), pid);
            return page;
        }
    }

    /**
     * Releases the lock on a page.
     * Calling this is very risky, and may result in wrong behavior. Think hard
     * about who needs to call this and why, and why they can run the risk of
     * calling it.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param pid the ID of the page to unlock
     */
    public void releasePage(TransactionId tid, PageId pid) {
        // some code goes here
        // not necessary for lab1|lab2
        lockManager.releaseLock(tid, pid);
    }

    /**
     * Release all locks associated with a given transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     */
    public void transactionComplete(TransactionId tid) throws IOException {
        // some code goes here
        // not necessary for lab1|lab2
        transactionComplete(tid, true);
    }

    /** Return true if the specified transaction has a lock on the specified page */
    public boolean holdsLock(TransactionId tid, PageId pid, Permissions perm) {
        // some code goes here
        // not necessary for lab1|lab2
        if (perm.equals(Permissions.READ_ONLY)) {
            return lockManager.holdsSharedLock(tid, pid) || lockManager.holdsExclusiveLock(tid, pid);
        } else {
            return lockManager.holdsExclusiveLock(tid, pid);
        }
    }

    /**
     * Commit or abort a given transaction; release all locks associated to
     * the transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param commit a flag indicating whether we should commit or abort
     */
    public synchronized void transactionComplete(TransactionId tid, boolean commit)
        throws IOException {
        // some code goes here
        // not necessary for lab1|lab2
        if (commit) {
            // flush all dirty pages associated with the tid
//            flushPages(tid);
            for (Page p : pidToPage.values()) {
                TransactionId dirtier = p.isDirty();
                if (dirtier != null) {
                    Database.getLogFile().logWrite(tid, p.getBeforeImage(), p);
                    Database.getLogFile().force();
                }
                // use current page contents as the before-image
                // for the next transaction that modifies this page.
                p.setBeforeImage();
            }
        } else {
            // revert dirty pages of the tid by discarding and re-reading
            Set<PageId> pidToDiscard = new HashSet<>();
            for (PageId pageId : pidToPage.keySet()) {
                if (tid.equals(pidToPage.get(pageId).isDirty())) {
                    pidToDiscard.add(pageId);
                }
            }
            for (PageId pid : pidToDiscard) {
                discardPage(pid);
                // re-read the page once the transaction starts again.
            }
        }
        // release associated locks
        lockManager.releaseAll(tid);
    }

    /**
     * Add a tuple to the specified table on behalf of transaction tid.  Will
     * acquire a write lock on the page the tuple is added to and any other 
     * pages that are updated (Lock acquisition is not needed for lab2). 
     * May block if the lock(s) cannot be acquired.
     * 
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and adds versions of any pages that have 
     * been dirtied to the cache (replacing any existing versions of those pages) so 
     * that future requests see up-to-date pages. 
     *
     * @param tid the transaction adding the tuple
     * @param tableId the table to add the tuple to
     * @param t the tuple to add
     */
    public void insertTuple(TransactionId tid, int tableId, Tuple t)
        throws DbException, IOException, TransactionAbortedException {
        // some code goes here
        // not necessary for lab1
        ArrayList<Page> pages = Database.getCatalog().getDatabaseFile(tableId).insertTuple(tid, t);
        for (Page page: pages) {
            page.markDirty(true, tid);
            pidToPage.put(page.getId(), page);
            lruList.remove(page.getId());
            lruList.add(lruList.size(), (page.getId()));
        }
    }

    /**
     * Remove the specified tuple from the buffer pool.
     * Will acquire a write lock on the page the tuple is removed from and any
     * other pages that are updated. May block if the lock(s) cannot be acquired.
     *
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and adds versions of any pages that have 
     * been dirtied to the cache (replacing any existing versions of those pages) so 
     * that future requests see up-to-date pages. 
     *
     * @param tid the transaction deleting the tuple.
     * @param t the tuple to delete
     */
    public void deleteTuple(TransactionId tid, Tuple t)
        throws DbException, IOException, TransactionAbortedException {
        // some code goes here
        // not necessary for lab1
        int tableId = t.getRecordId().getPageId().getTableId();
        ArrayList<Page> pages = Database.getCatalog().getDatabaseFile(tableId).deleteTuple(tid, t);
        for (Page page: pages) {
            page.markDirty(true, tid);
            pidToPage.put(page.getId(), page);
            lruList.remove(page.getId());
            lruList.add(lruList.size(), page.getId());
        }
    }

    /**
     * Flush all dirty pages to disk.
     * NB: Be careful using this routine -- it writes dirty data to disk so will
     *     break simpledb if running in NO STEAL mode.
     */
    public synchronized void flushAllPages() throws IOException {
        // some code goes here
        // not necessary for lab1
        for (PageId pid: pidToPage.keySet()) {
            flushPage(pid);
        }
    }

    /** Remove the specific page id from the buffer pool.
        Needed by the recovery manager to ensure that the
        buffer pool doesn't keep a rolled back page in its
        cache.
        
        Also used by B+ tree files to ensure that deleted pages
        are removed from the cache so they can be reused safely
    */
    public synchronized void discardPage(PageId pid) {
        // some code goes here
        // not necessary for lab1
        pidToPage.remove(pid);
        lruList.remove(pid);
    }

    /**
     * Flushes a certain page to disk
     * @param pid an ID indicating the page to flush
     */
    private synchronized void flushPage(PageId pid) throws IOException {
        // some code goes here
        // not necessary for lab1
        if (pidToPage.containsKey(pid)) {
            Page page = pidToPage.get(pid);
            if (page.isDirty() != null) {
                int tableId = pid.getTableId();
                // append an update record to the log, with
                // a before-image and after-image.
                TransactionId dirtier = page.isDirty();
                if (dirtier != null && !lockManager.getLockOnTid(dirtier).isEmpty()){
                    Database.getLogFile().logWrite(dirtier, page.getBeforeImage(), page);
                    Database.getLogFile().force();
                }

                Database.getCatalog().getDatabaseFile(tableId).writePage(page);
                page.markDirty(false, null);
            }
        }
    }

    /** Write all pages of the specified transaction to disk.
     */
    public synchronized void flushPages(TransactionId tid) throws IOException {
        // some code goes here
        // not necessary for lab1|lab2
        for (PageId pageId : pidToPage.keySet()) {
            if (tid.equals(pidToPage.get(pageId).isDirty())) {
                flushPage(pageId);
            }
        }
    }

    /**
     * Discards a page from the buffer pool.
     * Flushes the page to disk to ensure dirty pages are updated on disk.
     */
    private synchronized void evictPage() throws DbException, IOException {
        // some code goes here
        // not necessary for lab1
        PageId toEvict = null;

//        lab3: No steal
//        for (int i = 0; i < lruList.size(); i++) {
//            if (pidToPage.get(lruList.get(i)).isDirty() == null) {
//                toEvict = lruList.get(i);
////                lruList.remove(i);
//                break;
//            }
//        }

        if (lruList.isEmpty()) {
            throw new DbException("Cannot evict any page because all pages are dirty.");
        }
        toEvict = lruList.get(0);
        flushPage(toEvict);
        discardPage(toEvict);
    }

}
