package simpledb;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

import static simpledb.Permissions.READ_ONLY;

/**
 * HeapFile is an implementation of a DbFile that stores a collection of tuples
 * in no particular order. Tuples are stored on pages, each of which is a fixed
 * size, and the file is simply a collection of those pages. HeapFile works
 * closely with HeapPage. The format of HeapPages is described in the HeapPage
 * constructor.
 * 
 * @see simpledb.HeapPage#HeapPage
 * @author Sam Madden
 */
public class HeapFile implements DbFile {
    private File file;
    private TupleDesc td;

    /**
     * Constructs a heap file backed by the specified file.
     * 
     * @param f
     *            the file that stores the on-disk backing store for this heap
     *            file.
     */
    public HeapFile(File f, TupleDesc td) {
        // some code goes here
        this.file = f;
        this.td = td;
    }

    /**
     * Returns the File backing this HeapFile on disk.
     * 
     * @return the File backing this HeapFile on disk.
     */
    public File getFile() {
        // some code goes here
        return this.file;
    }

    /**
     * Returns an ID uniquely identifying this HeapFile. Implementation note:
     * you will need to generate this tableid somewhere to ensure that each
     * HeapFile has a "unique id," and that you always return the same value for
     * a particular HeapFile. We suggest hashing the absolute file name of the
     * file underlying the heapfile, i.e. f.getAbsoluteFile().hashCode().
     * 
     * @return an ID uniquely identifying this HeapFile.
     */
    public int getId() {
        // some code goes here
        return this.file.getAbsolutePath().hashCode();
    }

    /**
     * Returns the TupleDesc of the table stored in this DbFile.
     * 
     * @return TupleDesc of this DbFile.
     */
    public TupleDesc getTupleDesc() {
        // some code goes here
        return this.td;
    }

    // see DbFile.java for javadocs
    public Page readPage(PageId pid) {
        // some code goes here
        // pgNo starts with 0
        if (pid.getPageNumber() > numPages()) {
            throw new IllegalArgumentException("The page does not exist in this file.");
        }
        int offset = BufferPool.getPageSize() * pid.getPageNumber();

        try {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            raf.seek(offset);
            byte[] buf = new byte[BufferPool.getPageSize()];
            raf.read(buf);

            return new HeapPage(new HeapPageId(pid.getTableId(), pid.getPageNumber()), buf);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    // see DbFile.java for javadocs
    public void writePage(Page page) throws IOException {
        // some code goes here
        // not necessary for lab1
    }

    /**
     * Returns the number of pages in this HeapFile.
     */
    public int numPages() {
        // some code goes here
        return (int) Math.ceil((int) this.file.length() / (double) BufferPool.getPageSize());
    }

    // see DbFile.java for javadocs
    public ArrayList<Page> insertTuple(TransactionId tid, Tuple t)
            throws DbException, IOException, TransactionAbortedException {
        // some code goes here
        return null;
        // not necessary for lab1
    }

    // see DbFile.java for javadocs
    public ArrayList<Page> deleteTuple(TransactionId tid, Tuple t) throws DbException,
            TransactionAbortedException {
        // some code goes here
        return null;
        // not necessary for lab1
    }

    // see DbFile.java for javadocs
    public DbFileIterator iterator(TransactionId tid) {
        // some code goes here
        return new AbstractDbFileIterator() {
            @Override
            protected Tuple readNext() throws DbException, TransactionAbortedException {
                if (pageItr == null) {
                    return null;
                }
                if (pageItr.hasNext()) {
                    return pageItr.next();
                }
                pageNum++;
                if (pageNum < numPages()) {
                    HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, new HeapPageId(getId(), pageNum), READ_ONLY);
                    pageItr = page.iterator();
                    return pageItr.next();
                }
                return null;
            }

            @Override
            public void open() throws DbException, TransactionAbortedException {
                HeapPage page = (HeapPage) Database.getBufferPool().getPage(tid, new HeapPageId(getId(), 0), READ_ONLY);
                pageItr = page.iterator();
                pageNum = 0;
            }

            @Override
            public void rewind() throws DbException, TransactionAbortedException {
                throw new DbException("rewind() is not supported");
            }
        };
    }

}

