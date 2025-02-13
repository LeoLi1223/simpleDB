package simpledb;

import java.util.*;

/**
 * The Aggregation operator that computes an aggregate (e.g., sum, avg, max,
 * min). Note that we only support aggregates over a single column, grouped by a
 * single column.
 */
public class Aggregate extends Operator {

    private static final long serialVersionUID = 1L;

    private int gbField;
    private int aField;
    private Aggregator.Op op;
    private OpIterator child;
    private Aggregator aggregator;
    private boolean merged;
    /**
     * Constructor.
     * 
     * Implementation hint: depending on the type of afield, you will want to
     * construct an {@link IntegerAggregator} or {@link StringAggregator} to help
     * you with your implementation of readNext().
     * 
     * 
     * @param child
     *            The OpIterator that is feeding us tuples.
     * @param afield
     *            The column over which we are computing an aggregate.
     * @param gfield
     *            The column over which we are grouping the result, or -1 if
     *            there is no grouping
     * @param aop
     *            The aggregation operator to use
     */
    public Aggregate(OpIterator child, int afield, int gfield, Aggregator.Op aop) {
	    // some code goes here
        this.child = child;
        this.aField = afield;
        this.gbField = gfield;
        this.op = aop;

        Type aType = child.getTupleDesc().getFieldType(afield);
        Type gbFieldType = gfield == -1 ? null : child.getTupleDesc().getFieldType(gfield);
        if (aType.getLen() == 4) { // INT_TYPE
            this.aggregator = new IntegerAggregator(gfield, gbFieldType, afield, aop);
        } else {
            this.aggregator = new StringAggregator(gfield, gbFieldType, afield, aop);
        }
        this.merged = false;

    }

    /**
     * @return If this aggregate is accompanied by a groupby, return the groupby
     *         field index in the <b>INPUT</b> tuples. If not, return
     *         {@link simpledb.Aggregator#NO_GROUPING}
     * */
    public int groupField() {
	    // some code goes here
	    return this.gbField;
    }

    /**
     * @return If this aggregate is accompanied by a group by, return the name
     *         of the groupby field in the <b>OUTPUT</b> tuples. If not, return
     *         null;
     * */
    public String groupFieldName() {
        // some code goes here
        if (this.gbField == -1) {
            return null;
        }
        return child.getTupleDesc().getFieldName(gbField);
    }

    /**
     * @return the aggregate field
     * */
    public int aggregateField() {
        // some code goes here
        return aField;
    }

    /**
     * @return return the name of the aggregate field in the <b>OUTPUT</b>
     *         tuples
     * */
    public String aggregateFieldName() {
        // some code goes here
        return nameOfAggregatorOp(this.op) + " (" + child.getTupleDesc().getFieldName(aField) + ")";
    }

    /**
     * @return return the aggregate operator
     * */
    public Aggregator.Op aggregateOp() {
        // some code goes here
        return op;
    }

    public static String nameOfAggregatorOp(Aggregator.Op aop) {
	return aop.toString();
    }

    public void open() throws NoSuchElementException, DbException,
	    TransactionAbortedException {
	    // some code goes here
        super.open();

        if (!merged) {
            child.open();
            while (child.hasNext()) {
                Tuple next = child.next();
                aggregator.mergeTupleIntoGroup(next);
            }
            child.close();
        }

        this.merged = true;
        aggregator.iterator().open();
    }

    /**
     * Returns the next tuple. If there is a group by field, then the first
     * field is the field by which we are grouping, and the second field is the
     * result of computing the aggregate. If there is no group by field, then
     * the result tuple should contain one field representing the result of the
     * aggregate. Should return null if there are no more tuples.
     */
    protected Tuple fetchNext() throws TransactionAbortedException, DbException {
        // some code goes here
        try {
            return aggregator.iterator().next();
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    public void rewind() throws DbException, TransactionAbortedException {
        // some code goes here
        close();
        open();
    }

    /**
     * Returns the TupleDesc of this Aggregate. If there is no group by field,
     * this will have one field - the aggregate column. If there is a group by
     * field, the first field will be the group by field, and the second will be
     * the aggregate value column.
     * 
     * The name of an aggregate column should be informative. For example:
     * "aggName(aop) (child_td.getFieldName(afield))" where aop and afield are
     * given in the constructor, and child_td is the TupleDesc of the child
     * iterator.
     */
    public TupleDesc getTupleDesc() {
        // some code goes here
        TupleDesc td = aggregator.iterator().getTupleDesc();
        Type[] types;
        String[] fieldNames;
        if (td.numFields() == 1) {
            types = new Type[]{td.getFieldType(0)};
            fieldNames = new String[]{aggregateFieldName()};
        } else {
            types = new Type[]{td.getFieldType(0), td.getFieldType(1)};
            fieldNames = new String[]{groupFieldName(), aggregateFieldName()};
        }
        td = new TupleDesc(types, fieldNames);
        return td;
    }

    public void close() {
	    // some code goes here
        super.close();
        aggregator.iterator().close();
    }

    @Override
    public OpIterator[] getChildren() {
        // some code goes here
        return new OpIterator[]{this.child};
    }

    @Override
    public void setChildren(OpIterator[] children) {
	    // some code goes here
        this.child = children[0];
    }
    
}
