package sjdb;

import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

public class Estimator implements PlanVisitor {

	public Estimator() {
		// empty constructor
	}

	/*
	 * Create output relation on Scan operator
	 * T(scan) = T(R)
	 */
	public void visit(Scan op) {
		Relation input = op.getRelation();
		Relation output = new Relation(input.getTupleCount());

		Iterator<Attribute> iter = input.getAttributes().iterator();
		while (iter.hasNext()) {
			output.addAttribute(new Attribute(iter.next()));
		}

		op.setOutput(output);
	}

	/*
	 * T(project) = T(R)  (no duplicate elimination)
	 * Copy only the projected attributes with their existing value counts.
	 */
	public void visit(Project op) {
		Relation input = op.getInput().getOutput();
		Relation output = new Relation(input.getTupleCount());

		for (Attribute projected : op.getAttributes()) {
			// getAttribute() finds by name equality
			Attribute attr = input.getAttribute(projected);
			output.addAttribute(new Attribute(attr));
		}

		op.setOutput(output);
	}

	/*
	 * Selection estimation:
	 *
	 * attr=val:  T(out) = T(R)/V(R,A),  V(out,A) = 1
	 * attr=attr: T(out) = T(R)/max(V(R,A),V(R,B)),
	 *            V(out,A) = V(out,B) = min(V(R,A),V(R,B))
	 */
	public void visit(Select op) {
		Relation input = op.getInput().getOutput();
		Predicate pred = op.getPredicate();

		int tuples;
		Relation output;

		if (pred.equalsValue()) {
			// attr = val
			Attribute attr = input.getAttribute(pred.getLeftAttribute());
			tuples = input.getTupleCount() / attr.getValueCount();
			output = new Relation(tuples);

			for (Attribute a : input.getAttributes()) {
				if (a.equals(pred.getLeftAttribute())) {
					// value count for selected attribute becomes 1
					output.addAttribute(new Attribute(a.getName(), 1));
				} else {
					output.addAttribute(new Attribute(a));
				}
			}
		} else {
			// attr = attr
			Attribute leftAttr  = input.getAttribute(pred.getLeftAttribute());
			Attribute rightAttr = input.getAttribute(pred.getRightAttribute());
			int vLeft  = leftAttr.getValueCount();
			int vRight = rightAttr.getValueCount();
			int vMin   = Math.min(vLeft, vRight);

			tuples = input.getTupleCount() / Math.max(vLeft, vRight);
			output = new Relation(tuples);

			for (Attribute a : input.getAttributes()) {
				if (a.equals(pred.getLeftAttribute()) || a.equals(pred.getRightAttribute())) {
					output.addAttribute(new Attribute(a.getName(), vMin));
				} else {
					output.addAttribute(new Attribute(a));
				}
			}
		}

		op.setOutput(output);
	}

	/*
	 * T(R x S) = T(R) * T(S)
	 * All attributes from both sides are copied unchanged.
	 */
	public void visit(Product op) {
		Relation left  = op.getLeft().getOutput();
		Relation right = op.getRight().getOutput();

		int tuples = left.getTupleCount() * right.getTupleCount();
		Relation output = new Relation(tuples);

		for (Attribute a : left.getAttributes()) {
			output.addAttribute(new Attribute(a));
		}
		for (Attribute a : right.getAttributes()) {
			output.addAttribute(new Attribute(a));
		}

		op.setOutput(output);
	}

	/*
	 * Join estimation (attr A from R, attr B from S):
	 * T(R ⋈ S) = T(R)*T(S) / max(V(R,A), V(S,B))
	 * V(out,A) = V(out,B) = min(V(R,A), V(S,B))
	 * Non-join attributes keep their existing value counts.
	 */
	public void visit(Join op) {
		Relation left  = op.getLeft().getOutput();
		Relation right = op.getRight().getOutput();
		Predicate pred = op.getPredicate();

		Attribute leftAttr  = left.getAttribute(pred.getLeftAttribute());
		Attribute rightAttr = right.getAttribute(pred.getRightAttribute());
		int vLeft  = leftAttr.getValueCount();
		int vRight = rightAttr.getValueCount();
		int vMin   = Math.min(vLeft, vRight);

		int tuples = (left.getTupleCount() * right.getTupleCount()) / Math.max(vLeft, vRight);
		Relation output = new Relation(tuples);

		for (Attribute a : left.getAttributes()) {
			if (a.equals(pred.getLeftAttribute())) {
				output.addAttribute(new Attribute(a.getName(), vMin));
			} else {
				output.addAttribute(new Attribute(a));
			}
		}
		for (Attribute a : right.getAttributes()) {
			if (a.equals(pred.getRightAttribute())) {
				output.addAttribute(new Attribute(a.getName(), vMin));
			} else {
				output.addAttribute(new Attribute(a));
			}
		}

		op.setOutput(output);
	}
}