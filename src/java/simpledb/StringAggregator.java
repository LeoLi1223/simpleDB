package simpledb;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Knows how to compute some aggregate over a set of StringFields.
 */
public class StringAggregator implements Aggregator {

    private static final long serialVersionUID = 1L;

    private int gbfield;
    private Type gbfieldType;
    private int afield;
    private Op what;
    private Map<Field, Integer> groups;

    private boolean open;
    private Iterator<Map.Entry<Field, Integer>> groupsItr;
    /**
     * Aggregate constructor
     * @param gbfield the 0-based index of the group-by field in the tuple, or NO_GROUPING if there is no grouping
     * @param gbfieldtype the type of the group by field (e.g., Type.INT_TYPE), or null if there is no grouping
     * @param afield the 0-based index of the aggregate field in the tuple
     * @param what aggregation operator to use -- only supports COUNT
     * @throws IllegalArgumentException if what != COUNT
     */

    public StringAggregator(int gbfield, Type gbfieldtype, int afield, Op what) {
        // some code goes here
        if (what != Op.COUNT) {
            throw new IllegalArgumentException("StringAggregator only support the COUNT operation");
        }
        this.gbfield = gbfield;
        this.gbfieldType = gbfieldtype;
        this.afield = afield;
        this.what = what;
        this.groups = new HashMap<>();

        this.open = false;
        this.groupsItr = null;
    }

    /**
     * Merge a new tuple into the aggregate, grouping as indicated in the constructor
     * @param tup the Tuple containing an aggregate field and a group-by field
     */
    public void mergeTupleIntoGroup(Tuple tup) {
        // some code goes here
        Field groupby = gbfield == NO_GROUPING ? new IntField(NO_GROUPING): tup.getField(gbfield);
        StringField toAggr = (StringField) tup.getField(afield);
        if (what == Op.COUNT) {
            if (!groups.containsKey(groupby)) {
                groups.put(groupby, 1);
            } else {
                groups.put(groupby, 1 + groups.get(groupby));
            }
        }
    }

    /**
     * Create a OpIterator over group aggregate results.
     *
     * @return a OpIterator whose tuples are the pair (groupVal,
     *   aggregateVal) if using group, or a single (aggregateVal) if no
     *   grouping. The aggregateVal is determined by the type of
     *   aggregate specified in the constructor.
     */
    public OpIterator iterator() {
        // some code goes here
        return new OpIterator() {
            @Override
            public void open() throws DbException, TransactionAbortedException {
                open = true;
                groupsItr = groups.entrySet().iterator();
            }

            @Override
            public boolean hasNext() throws DbException, TransactionAbortedException {
                if (!open) throw new IllegalStateException("Not open yet");
                return groupsItr.hasNext();
            }

            @Override
            public Tuple next() throws DbException, TransactionAbortedException, NoSuchElementException {
                if (!open) throw new IllegalStateException("Not open yet");
                Map.Entry<Field, Integer> nextGroup = groupsItr.next();
                Field groupVal = nextGroup.getKey();
                int aggrVal = nextGroup.getValue();

                Tuple next = new Tuple(getTupleDesc());
                if (gbfield == NO_GROUPING) {
                    next.setField(0, new IntField(aggrVal));
                } else {
                    next.setField(0, groupVal);
                    next.setField(1, new IntField(aggrVal));
                }
                return next;
            }

            @Override
            public void rewind() throws DbException, TransactionAbortedException {
                if (!open) throw new IllegalStateException("Not open yet");
                close();
                open();
            }

            @Override
            public TupleDesc getTupleDesc() {
                Type[] types;
                if (gbfield == NO_GROUPING) {
                    types = new Type[]{Type.INT_TYPE};
                } else {
                    types = new Type[]{gbfieldType, Type.INT_TYPE};
                }
                return new TupleDesc(types);
            }

            @Override
            public void close() {
                open = false;
                groupsItr = null;
            }
        };
    }

}
