
package simpledb;

import java.io.*;
import java.util.*;
import java.lang.reflect.*;

/**
LogFile implements the recovery subsystem of SimpleDb.  This class is
able to write different log records as needed, but it is the
responsibility of the caller to ensure that write ahead logging and
two-phase locking discipline are followed.  <p>

<u> Locking note: </u>
<p>

Many of the methods here are synchronized (to prevent concurrent log
writes from happening); many of the methods in BufferPool are also
synchronized (for similar reasons.)  Problem is that BufferPool writes
log records (on page flushed) and the log file flushes BufferPool
pages (on checkpoints and recovery.)  This can lead to deadlock.  For
that reason, any LogFile operation that needs to access the BufferPool
must not be declared synchronized and must begin with a block like:

<p>
<pre>
    synchronized (Database.getBufferPool()) {
       synchronized (this) {

       ..

       }
    }
</pre>
*/

/**
<p> The format of the log file is as follows:

<ul>

<li> The first long integer of the file represents the offset of the
last written checkpoint, or -1 if there are no checkpoints

<li> All additional data in the log consists of log records.  Log
records are variable length.

<li> Each log record begins with an integer type and a long integer
transaction id.

<li> Each log record ends with a long integer file offset representing
the position in the log file where the record began.

<li> There are five record types: ABORT, COMMIT, UPDATE, BEGIN, and
CHECKPOINT

<li> ABORT, COMMIT, and BEGIN records contain no additional data

<li>UPDATE RECORDS consist of two entries, a before image and an
after image.  These images are serialized Page objects, and can be
accessed with the LogFile.readPageData() and LogFile.writePageData()
methods.  See LogFile.print() for an example.

<li> CHECKPOINT records consist of active transactions at the time
the checkpoint was taken and their first log record on disk.  The format
of the record is an integer count of the number of transactions, as well
as a long integer transaction id and a long integer first record offset
for each active transaction.

</ul>

*/

public class LogFile {

    final File logFile;
    private RandomAccessFile raf;
    Boolean recoveryUndecided; // no call to recover() and no append to log

    static final int ABORT_RECORD = 1;
    static final int COMMIT_RECORD = 2;
    static final int UPDATE_RECORD = 3;
    static final int BEGIN_RECORD = 4;
    static final int CHECKPOINT_RECORD = 5;
    static final int CLR_RECORD = 6;
    static final long NO_CHECKPOINT_ID = -1;

    final static int INT_SIZE = 4;
    final static int LONG_SIZE = 8;

    long currentOffset = -1;//protected by this
//    int pageSize;
    int totalRecords = 0; // for PatchTest //protected by this

    HashMap<Long,Long> tidToFirstLogRecord = new HashMap<Long,Long>();

    /** Constructor.
        Initialize and back the log file with the specified file.
        We're not sure yet whether the caller is creating a brand new DB,
        in which case we should ignore the log file, or whether the caller
        will eventually want to recover (after populating the Catalog).
        So we make this decision lazily: if someone calls recover(), then
        do it, while if someone starts adding log file entries, then first
        throw out the initial log file contents.

        @param f The log file's name
    */
    public LogFile(File f) throws IOException {
	    this.logFile = f;
        raf = new RandomAccessFile(f, "rw");
        recoveryUndecided = true;

        // install shutdown hook to force cleanup on close
        // Runtime.getRuntime().addShutdownHook(new Thread() {
                // public void run() { shutdown(); }
            // });

        //XXX WARNING -- there is nothing that verifies that the specified
        // log file actually corresponds to the current catalog.
        // This could cause problems since we log tableids, which may or
        // may not match tableids in the current catalog.
    }

    // we're about to append a log record. if we weren't sure whether the
    // DB wants to do recovery, we're sure now -- it didn't. So truncate
    // the log.
    void preAppend() throws IOException {
        totalRecords++;
        if(recoveryUndecided){
            recoveryUndecided = false;
            raf.seek(0);
            raf.setLength(0);
            raf.writeLong(NO_CHECKPOINT_ID);
            raf.seek(raf.length());
            currentOffset = raf.getFilePointer();
        }
    }

    public synchronized int getTotalRecords() {
        return totalRecords;
    }
    
    /** Write an abort record to the log for the specified tid, force
        the log to disk, and perform a rollback
        @param tid The aborting transaction.
    */
    public void logAbort(TransactionId tid) throws IOException {
        // must have buffer pool lock before proceeding, since this
        // calls rollback

        synchronized (Database.getBufferPool()) {

            synchronized(this) {
                preAppend();
                //Debug.log("ABORT");
                //should we verify that this is a live transaction?

                // must do this here, since rollback only works for
                // live transactions (needs tidToFirstLogRecord)
                rollback(tid);

                raf.writeInt(ABORT_RECORD);
                raf.writeLong(tid.getId());
                raf.writeLong(currentOffset);
                currentOffset = raf.getFilePointer();
                force();
                tidToFirstLogRecord.remove(tid.getId());
            }
        }
    }

    /** Write a commit record to disk for the specified tid,
        and force the log to disk.

        @param tid The committing transaction.
    */
    public synchronized void logCommit(TransactionId tid) throws IOException {
        preAppend();
        Debug.log("COMMIT " + tid.getId());
        //should we verify that this is a live transaction?

        raf.writeInt(COMMIT_RECORD);
        raf.writeLong(tid.getId());
        raf.writeLong(currentOffset);
        currentOffset = raf.getFilePointer();
        force();
        tidToFirstLogRecord.remove(tid.getId());
    }

    /** Write an UPDATE record to disk for the specified tid and page
        (with provided         before and after images.)
        @param tid The transaction performing the write
        @param before The before image of the page
        @param after The after image of the page

        @see simpledb.Page#getBeforeImage
    */
    public  synchronized void logWrite(TransactionId tid, Page before,
                                       Page after)
        throws IOException  {
        Debug.log("WRITE, offset = " + raf.getFilePointer());
        preAppend();
        /* update record conists of

           record type
           transaction id
           before page data (see writePageData)
           after page data
           start offset
        */
        raf.writeInt(UPDATE_RECORD);
        raf.writeLong(tid.getId());

        writePageData(raf,before);
        writePageData(raf,after);
        raf.writeLong(currentOffset);
        currentOffset = raf.getFilePointer();

        Debug.log("WRITE OFFSET = " + currentOffset);
    }

    void writePageData(RandomAccessFile raf, Page p) throws IOException{
        PageId pid = p.getId();
        int pageInfo[] = pid.serialize();

        //page data is:
        // page class name
        // id class name
        // id class bytes
        // id class data
        // page class bytes
        // page class data

        String pageClassName = p.getClass().getName();
        String idClassName = pid.getClass().getName();

        raf.writeUTF(pageClassName);
        raf.writeUTF(idClassName);

        raf.writeInt(pageInfo.length);
        for (int i = 0; i < pageInfo.length; i++) {
            raf.writeInt(pageInfo[i]);
        }
        byte[] pageData = p.getPageData();
        raf.writeInt(pageData.length);
        raf.write(pageData);
        //        Debug.log ("WROTE PAGE DATA, CLASS = " + pageClassName + ", table = " +  pid.getTableId() + ", page = " + pid.pageno());
    }

    Page readPageData(RandomAccessFile raf) throws IOException {
        PageId pid;
        Page newPage = null;

        String pageClassName = raf.readUTF();
        String idClassName = raf.readUTF();

        try {
            Class<?> idClass = Class.forName(idClassName);
            Class<?> pageClass = Class.forName(pageClassName);

            Constructor<?>[] idConsts = idClass.getDeclaredConstructors();
            int numIdArgs = raf.readInt();
            Object idArgs[] = new Object[numIdArgs];
            for (int i = 0; i<numIdArgs;i++) {
                idArgs[i] = new Integer(raf.readInt());
            }
            pid = (PageId)idConsts[0].newInstance(idArgs);

            Constructor<?>[] pageConsts = pageClass.getDeclaredConstructors();
            int pageSize = raf.readInt();

            byte[] pageData = new byte[pageSize];
            raf.read(pageData); //read before image

            Object[] pageArgs = new Object[2];
            pageArgs[0] = pid;
            pageArgs[1] = pageData;

            newPage = (Page)pageConsts[0].newInstance(pageArgs);

            //            Debug.log("READ PAGE OF TYPE " + pageClassName + ", table = " + newPage.getId().getTableId() + ", page = " + newPage.getId().pageno());
        } catch (ClassNotFoundException e){
            e.printStackTrace();
            throw new IOException();
        } catch (InstantiationException e) {
            e.printStackTrace();
            throw new IOException();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            throw new IOException();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            throw new IOException();
        }
        return newPage;

    }

    /** Write a BEGIN record for the specified transaction
        @param tid The transaction that is beginning

    */
    public synchronized  void logXactionBegin(TransactionId tid)
        throws IOException {
        Debug.log("BEGIN");
        if(tidToFirstLogRecord.get(tid.getId()) != null){
            System.err.printf("logXactionBegin: already began this tid\n");
            throw new IOException("double logXactionBegin()");
        }
        preAppend();
        raf.writeInt(BEGIN_RECORD);
        raf.writeLong(tid.getId());
        raf.writeLong(currentOffset);
        tidToFirstLogRecord.put(tid.getId(), currentOffset);
        currentOffset = raf.getFilePointer();

        Debug.log("BEGIN OFFSET = " + currentOffset);
    }

    /** Checkpoint the log and write a checkpoint record. */
    public void logCheckpoint() throws IOException {
        //make sure we have buffer pool lock before proceeding
        synchronized (Database.getBufferPool()) {
            synchronized (this) {
                //Debug.log("CHECKPOINT, offset = " + raf.getFilePointer());
                preAppend();
                long startCpOffset, endCpOffset;
                Set<Long> keys = tidToFirstLogRecord.keySet();
                Iterator<Long> els = keys.iterator();
                force();
                Database.getBufferPool().flushAllPages();
                startCpOffset = raf.getFilePointer();
                raf.writeInt(CHECKPOINT_RECORD);
                raf.writeLong(-1); //no tid , but leave space for convenience

                //write list of outstanding transactions
                raf.writeInt(keys.size());
                while (els.hasNext()) {
                    Long key = els.next();
                    Debug.log("WRITING CHECKPOINT TRANSACTION ID: " + key);
                    raf.writeLong(key);
                    //Debug.log("WRITING CHECKPOINT TRANSACTION OFFSET: " + tidToFirstLogRecord.get(key));
                    raf.writeLong(tidToFirstLogRecord.get(key));
                }

                //once the CP is written, make sure the CP location at the
                // beginning of the log file is updated
                endCpOffset = raf.getFilePointer();
                raf.seek(0);
                raf.writeLong(startCpOffset);
                raf.seek(endCpOffset);
                raf.writeLong(currentOffset);
                currentOffset = raf.getFilePointer();
                //Debug.log("CP OFFSET = " + currentOffset);
            }
        }

        logTruncate();
    }

    /** Truncate any unneeded portion of the log to reduce its space
        consumption */
    public synchronized void logTruncate() throws IOException {
        preAppend();
        raf.seek(0);
        long cpLoc = raf.readLong();

        long minLogRecord = cpLoc;

        if (cpLoc != -1L) {
            raf.seek(cpLoc);
            int cpType = raf.readInt();
            @SuppressWarnings("unused")
            long cpTid = raf.readLong();

            if (cpType != CHECKPOINT_RECORD) {
                throw new RuntimeException("Checkpoint pointer does not point to checkpoint record");
            }

            int numOutstanding = raf.readInt();

            for (int i = 0; i < numOutstanding; i++) {
                @SuppressWarnings("unused")
                long tid = raf.readLong();
                long firstLogRecord = raf.readLong();
                if (firstLogRecord < minLogRecord) {
                    minLogRecord = firstLogRecord;
                }
            }
        }

        // we can truncate everything before minLogRecord
        File newFile = new File("logtmp" + System.currentTimeMillis());
        RandomAccessFile logNew = new RandomAccessFile(newFile, "rw");
        logNew.seek(0);
        logNew.writeLong((cpLoc - minLogRecord) + LONG_SIZE);

        raf.seek(minLogRecord);

        //have to rewrite log records since offsets are different after truncation
        while (true) {
            try {
                int type = raf.readInt();
                long record_tid = raf.readLong();
                long newStart = logNew.getFilePointer();

                Debug.log("NEW START = " + newStart);

                logNew.writeInt(type);
                logNew.writeLong(record_tid);

                switch (type) {
                case UPDATE_RECORD:
                    Page before = readPageData(raf);
                    Page after = readPageData(raf);

                    writePageData(logNew, before);
                    writePageData(logNew, after);
                    break;
                case CHECKPOINT_RECORD:
                    int numXactions = raf.readInt();
                    logNew.writeInt(numXactions);
                    while (numXactions-- > 0) {
                        long xid = raf.readLong();
                        long xoffset = raf.readLong();
                        logNew.writeLong(xid);
                        logNew.writeLong((xoffset - minLogRecord) + LONG_SIZE);
                    }
                    break;
                case BEGIN_RECORD:
                    tidToFirstLogRecord.put(record_tid,newStart);
                    break;
                }

                //all xactions finish with a pointer
                logNew.writeLong(newStart);
                raf.readLong();

            } catch (EOFException e) {
                break;
            }
        }

        Debug.log("TRUNCATING LOG;  WAS " + raf.length() + " BYTES ; NEW START : " + minLogRecord + " NEW LENGTH: " + (raf.length() - minLogRecord));

        raf.close();
        logFile.delete();
        newFile.renameTo(logFile);
        raf = new RandomAccessFile(logFile, "rw");
        raf.seek(raf.length());
        newFile.delete();

        currentOffset = raf.getFilePointer();
        //print();
    }

    public synchronized void logCLR(long tid, Page page, long undoNextLogOffset) throws IOException {
        preAppend();

        raf.writeInt(CLR_RECORD); // type
        raf.writeLong(tid); // tid associated with the log record being undone
        writePageData(raf, page);
        raf.writeLong(undoNextLogOffset); // offset of the next log record to be undone
        raf.writeLong(currentOffset); // starting offset of the record
        currentOffset = raf.getFilePointer();

//        Debug.log("CLR OFFSET = " + currentOffset);
    }

    /** Rollback the specified transaction, setting the state of any
        of pages it updated to their pre-updated state.  To preserve
        transaction semantics, this should not be called on
        transactions that have already committed (though this may not
        be enforced by this method.)

        @param tid The transaction to rollback
    */
    public void rollback(TransactionId tid)
        throws NoSuchElementException, IOException {
        synchronized (Database.getBufferPool()) {
            synchronized(this) {
                preAppend();
                // undo all log records associated with the txn
                Set<Long> uncommitted = new HashSet<>();
                uncommitted.add(tid.getId());
                undo(uncommitted);
            }
        }
    }

    private void undo(Set<Long> uncommitted) throws IOException {
        long startOffset = raf.length();
        for (Long tid : uncommitted) {
            startOffset = Math.min(startOffset, tidToFirstLogRecord.get(tid));
        }
        raf.seek(startOffset);

        Stack<Long> undoOffsets = new Stack<>();

        while (true) {
            try {
                int type = raf.readInt();
                long tid = raf.readLong();

                switch (type) {
                    case UPDATE_RECORD:
                        if (uncommitted.contains(tid)) {
                            undoOffsets.push(raf.getFilePointer() - INT_SIZE - LONG_SIZE);
                        }
                        readPageData(raf);
                        readPageData(raf);
                        break;

                    case CHECKPOINT_RECORD:
                        int numTxn = raf.readInt();
                        raf.skipBytes(2 * numTxn * LONG_SIZE); // skip txn records
                        break;

                    case CLR_RECORD:
                        undoOffsets.pop();
                        break;

                    default:
                        break;
                }
                raf.skipBytes(LONG_SIZE); // skip start offset block
            } catch (EOFException e) { // reaching the end of the log file.
                break;
            }
        }

        // Now the stack contains all offsets of the log records to be undone.
        while (!undoOffsets.isEmpty()) {
            long off = undoOffsets.pop();
            raf.seek(off);
            raf.readInt(); // type which must be UPDATE_RECORD
            long tid = raf.readLong(); // txn id
            Page before = readPageData(raf);

            HeapFile f = (HeapFile) Database.getCatalog().getDatabaseFile(before.getId().getTableId());
            Database.getBufferPool().discardPage(before.getId());
            f.writePage(before);

            raf.seek(raf.length());
            if (!undoOffsets.isEmpty()) {
                logCLR(tid, before, undoOffsets.peek());
            } else {
                logCLR(tid, before, -1L);
            }
        }
    }

    /** Shutdown the logging system, writing out whatever state
        is necessary so that start up can happen quickly (without
        extensive recovery.)
    */
    public synchronized void shutdown() {
        try {
            logCheckpoint();  //simple way to shutdown is to write a checkpoint record
            raf.close();
        } catch (IOException e) {
            System.out.println("ERROR SHUTTING DOWN -- IGNORING.");
            e.printStackTrace();
        }
    }

    /** Recover the database system by ensuring that the updates of
        committed transactions are installed and that the
        updates of uncommitted transactions are not installed.
    */
    public void recover() throws IOException {
        synchronized (Database.getBufferPool()) {
            synchronized (this) {
                recoveryUndecided = false;
                // some code goes here
                raf.seek(0);
                long cpOffset = raf.readLong();

                Set<Long> uncommitted = new HashSet<>();
                if (cpOffset != NO_CHECKPOINT_ID) {
                    raf.seek(cpOffset);
                    raf.skipBytes(INT_SIZE + LONG_SIZE); // skip type + tid blocks
                    int numTxn = raf.readInt();
                    for (int i = 0; i < numTxn; i++) {
                        long tid = raf.readLong(); // active txn id
                        long off = raf.readLong(); // first record offset for the active tid
                        tidToFirstLogRecord.put(tid, off);
                        uncommitted.add(tid);
                    }
                    raf.skipBytes(LONG_SIZE); // skip start offset block
                }

                // redo: scan from the last checkpoint
                while (true) {
                    try {
                        int type = raf.readInt(); // log record type
                        long tid = raf.readLong(); // log record tid
                        switch (type) {
                            case UPDATE_RECORD:
                                readPageData(raf);
                                Page after = readPageData(raf);

                                HeapFile f = (HeapFile) Database.getCatalog().getDatabaseFile(after.getId().getTableId());
                                Database.getBufferPool().discardPage(after.getId());
                                f.writePage(after);
                                break;

                            case BEGIN_RECORD: // a new txn starts.
                                long offset = raf.readLong();
                                tidToFirstLogRecord.put(tid, offset);
                                uncommitted.add(tid);
                                raf.seek(raf.getFilePointer() - LONG_SIZE);
                                break;

                            case COMMIT_RECORD:
                                uncommitted.remove(tid);
                                break;

                            case CLR_RECORD:
                                Page page = readPageData(raf);

                                HeapFile file = (HeapFile) Database.getCatalog().getDatabaseFile(page.getId().getTableId());
                                Database.getBufferPool().discardPage(page.getId());
                                file.writePage(page);

                                raf.skipBytes(LONG_SIZE); // skip undoNextOffset block
                                break;

                            default:
                                break;
                        }
                        raf.skipBytes(LONG_SIZE); // skip start offset block
                    } catch (EOFException e) { // reaching the end of the file, stop scanning
                        break;
                    }
                }

                // undo
                undo(uncommitted);
            }
        }
    }

    /** Print out a human readable represenation of the log */
    public void print() throws IOException {
        synchronized (Database.getBufferPool()) {
            synchronized (this) {
                // some code goes here
                raf.seek(0);
                long cpOffset = raf.readLong();

                if (cpOffset != NO_CHECKPOINT_ID) {
                    StringBuilder sb = new StringBuilder("CHECKPOINT_RECORD active transactions: ");
                    raf.seek(cpOffset);
                    raf.skipBytes(INT_SIZE + LONG_SIZE); // skip type + tid blocks
                    int numTxn = raf.readInt();
                    for (int i = 0; i < numTxn; i++) {
                        long tid = raf.readLong(); // active txn id
                        sb.append(tid);
                        sb.append(" ");
                    }
                    raf.skipBytes(LONG_SIZE); // skip start offset block
                    System.out.println(sb);
                }

                while (true) {
                    try {
                        StringBuilder sb = new StringBuilder();
                        int type = raf.readInt(); // log record type
                        long tid = raf.readLong(); // log record tid
                        switch (type) {
                            case UPDATE_RECORD:
                                sb.append("UPDATE_RECORD ");
                                sb.append(tid);
                                sb.append(" ");
                                readPageData(raf);
                                sb.append("before-image");
                                sb.append(" ");
                                readPageData(raf);
                                sb.append("after-image");
                                sb.append(" ");
                                break;

                            case BEGIN_RECORD: // a new txn starts.
                                sb.append("BEGIN_RECORD ");
                                sb.append(tid);
                                sb.append(" ");
                                break;

                            case COMMIT_RECORD:
                                sb.append("COMMIT_RECORD ");
                                sb.append(tid);
                                sb.append(" ");
                                break;

                            case ABORT_RECORD:
                                sb.append("ABORT_RECORD ");
                                sb.append(tid);
                                sb.append(" ");
                                break;

                            case CHECKPOINT_RECORD:
                                sb.append("CHECKPOINT_RECORD active transactions: ");
                                raf.seek(cpOffset);
                                raf.skipBytes(INT_SIZE + LONG_SIZE); // skip type + tid blocks
                                int numTxn = raf.readInt();
                                for (int i = 0; i < numTxn; i++) {
                                    long t = raf.readLong(); // active txn id
                                    sb.append(t);
                                    sb.append(" ");
                                }
                                break;

                            case CLR_RECORD:
                                sb.append("CLR_RECORD ");
                                sb.append(tid);
                                sb.append(" ");
                                HeapPage page = (HeapPage) readPageData(raf);
                                sb.append(page.toString());
                                sb.append(" ");

                                long nextUndoOffset = raf.readLong();
                                sb.append(nextUndoOffset);
                                sb.append(" ");
                                break;

                            default:
                                break;
                        }
                        sb.append(raf.readLong());
                        System.out.println(sb);
                    } catch (EOFException e) { // reaching the end of the file, stop scanning
                        break;
                    }
                }
            }
        }
    }

    public  synchronized void force() throws IOException {
        raf.getChannel().force(true);
    }

}
