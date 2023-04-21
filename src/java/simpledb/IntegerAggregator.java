package simpledb;

import java.util.*;

/**
 * Knows how to compute some aggregate over a set of IntFields.
 */
public class IntegerAggregator implements Aggregator {

    private static final long serialVersionUID = 1L;

    private int gbfield;
    private Type gbfieldType;
    private int afield;
    private Op what;
    private Map<Field, Integer> groups;
    private Map<Field, Integer> countFields;

    private boolean open;
    private Iterator<Map.Entry<Field, Integer>> groupsItr;
    private Iterator<Map.Entry<Field, Integer>> countFieldsItr;
    /**
     * Aggregate constructor
     * 
     * @param gbfield
     *            the 0-based index of the group-by field in the tuple, or
     *            NO_GROUPING if there is no grouping
     * @param gbfieldtype
     *            the type of the group by field (e.g., Type.INT_TYPE), or null
     *            if there is no grouping
     * @param afield
     *            the 0-based index of the aggregate field in the tuple
     * @param what
     *            the aggregation operator
     */

    public IntegerAggregator(int gbfield, Type gbfieldtype, int afield, Op what) {
        // some code goes here
        this.gbfield = gbfield;
        this.gbfieldType = gbfieldtype;
        this.afield = afield;
        this.what = what;
        this.groups = new HashMap<>();
        this.countFields = new HashMap<>();
        this.open = false;
        this.groupsItr = null;
        this.countFieldsItr = null;
    }

    /**
     * Merge a new tuple into the aggregate, grouping as indicated in the
     * constructor
     * 
     * @param tup
     *            the Tuple containing an aggregate field and a group-by field
     */
    public void mergeTupleIntoGroup(Tuple tup) {
        // some code goes here
        Field groupby = gbfield == NO_GROUPING ? new IntField(NO_GROUPING): tup.getField(gbfield);
        IntField toAggr = (IntField) tup.getField(afield);
        int fieldValue = toAggr.getValue();
        if (what == Op.MIN) {
            if (!groups.containsKey(groupby)) {
                groups.put(groupby, fieldValue);
            } else {
                groups.put(groupby, Math.min(fieldValue, groups.get(groupby)));
            }
        } else if (what == Op.MAX) {
            if (!groups.containsKey(groupby)) {
                groups.put(groupby, fieldValue);
            } else {
                groups.put(groupby, Math.max(fieldValue, groups.get(groupby)));
            }
        } else if (what == Op.SUM) {
            if (!groups.containsKey(groupby)) {
                groups.put(groupby, fieldValue);
            } else {
                groups.put(groupby, fieldValue + groups.get(groupby));
            }
        } else if (what == Op.COUNT) {
            if (!groups.containsKey(groupby)) {
                groups.put(groupby, 1);
            } else {
                groups.put(groupby, 1 + groups.get(groupby));
            }
        } else if (what == Op.AVG) {
            if (!groups.containsKey(groupby)) {
                groups.put(groupby, fieldValue);
                countFields.put(groupby, 1);
            } else {
                groups.put(groupby, fieldValue + groups.get(groupby));
                countFields.put(groupby, 1 + countFields.get(groupby));
            }
        }
    }

    /**
     * Create a OpIterator over group aggregate results.
     * 
     * @return a OpIterator whose tuples are the pair (groupVal, aggregateVal)
     *         if using group, or a single (aggregateVal) if no grouping. The
     *         aggregateVal is determined by the type of aggregate specified in
     *         the constructor.
     */
    public OpIterator iterator() {
        // some code goes here
        return new OpIterator() {
            @Override
            public void open() throws DbException, TransactionAbortedException {
                open = true;
                groupsItr = groups.entrySet().iterator();
                if (what == Op.AVG) {
                    countFieldsItr = countFields.entrySet().iterator();
                }
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
                if (what == Op.AVG) {
                    Map.Entry<Field, Integer> count = countFieldsItr.next();
                    aggrVal = aggrVal / count.getValue();
                }

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
                countFieldsItr = null;
            }
        };
    }

}
