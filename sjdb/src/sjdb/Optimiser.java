package sjdb;

import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;

/**
 * Query optimiser that converts a canonical plan (Project over Selects over
 * Cartesian Products over Scans) into an optimised left-deep join tree.
 *
 * Strategy:
 *  1. Extract all Scan operators and all Select predicates from the canonical plan.
 *  2. Push attr=val selections down onto their individual Scans.
 *  3. Greedily build a left-deep join tree:
 *       - At each step consider all pairs of remaining sub-plans.
 *       - If a join predicate (attr=attr) exists between two sub-plans, form a Join.
 *       - Otherwise form a Product.
 *       - Pick the pair whose combined output is smallest (via the Estimator).
 *  4. Wrap the final plan in a Project if needed.
 */
public class Optimiser {

	private Catalogue catalogue;
	private Estimator estimator;

	public Optimiser(Catalogue catalogue) {
		this.catalogue = catalogue;
		this.estimator = new Estimator();
	}

	/**
	 * Optimise the given canonical query plan.
	 */
	public Operator optimise(Operator plan) {
		// --- 1. Collect scans and predicates from the canonical plan ---
		List<Scan>      scans      = new ArrayList<>();
		List<Predicate> predicates = new ArrayList<>();
		List<Attribute> projectAtts = null;

		collectParts(plan, scans, predicates, projectAtts);

		// Detect a top-level Project
		List<Attribute> projectedAttributes = null;
		if (plan instanceof Project) {
			projectedAttributes = ((Project) plan).getAttributes();
		}

		// --- 2. Build fresh Scan operators and push attr=val selects down ---
		List<Operator> subplans = new ArrayList<>();
		List<Predicate> joinPredicates = new ArrayList<>();

		for (Scan oldScan : scans) {
			// Create a fresh Scan over the same NamedRelation
			Operator current = new Scan((NamedRelation) oldScan.getRelation());
			current.accept(estimator);

			// Apply any attr=val predicates whose attribute lives on this scan
			List<Predicate> remaining = new ArrayList<>();
			for (Predicate p : predicates) {
				if (p.equalsValue() && hasAttribute(current, p.getLeftAttribute())) {
					current = new Select(current, p);
					current.accept(estimator);
				} else {
					remaining.add(p);
				}
			}
			predicates = remaining;
			subplans.add(current);
		}

		// Remaining predicates after attr=val pushdown are join predicates
		joinPredicates.addAll(predicates);

		// --- 3. Greedy left-deep join / product construction ---
		while (subplans.size() > 1) {
			int    bestI    = 0;
			int    bestJ    = 1;
			Predicate bestPred = findJoinPredicate(subplans.get(0), subplans.get(1), joinPredicates);
			int    bestSize = estimateCombined(subplans.get(0), subplans.get(1), bestPred);

			for (int i = 0; i < subplans.size(); i++) {
				for (int j = i + 1; j < subplans.size(); j++) {
					Predicate pred = findJoinPredicate(subplans.get(i), subplans.get(j), joinPredicates);
					int size = estimateCombined(subplans.get(i), subplans.get(j), pred);
					if (size < bestSize) {
						bestSize = size;
						bestI    = i;
						bestJ    = j;
						bestPred = pred;
					}
				}
			}

			// Combine bestI and bestJ
			Operator left  = subplans.get(bestI);
			Operator right = subplans.get(bestJ);
			Operator combined;

			if (bestPred != null) {
				// Ensure the predicate attributes are in the correct left/right order
				Predicate orderedPred = orderPredicate(left, right, bestPred);
				combined = new Join(left, right, orderedPred);
				combined.accept(estimator);
				joinPredicates.remove(bestPred);
			} else {
				combined = new Product(left, right);
				combined.accept(estimator);
			}

			// Remove the two sub-plans (remove higher index first to preserve lower index)
			subplans.remove(Math.max(bestI, bestJ));
			subplans.remove(Math.min(bestI, bestJ));
			subplans.add(0, combined);

			// Push any now-applicable attr=attr predicates that are not join predicates
			// (shouldn't normally occur with standard canonical queries, but handle anyway)
		}

		Operator result = subplans.get(0);

		// --- 4. Wrap in Project if needed ---
		if (projectedAttributes != null) {
			result = new Project(result, projectedAttributes);
			result.accept(estimator);
		}

		return result;
	}

	// -----------------------------------------------------------------------
	// Helpers
	// -----------------------------------------------------------------------

	/**
	 * Walk the canonical plan tree and collect Scans and Select predicates.
	 */
	private void collectParts(Operator op, List<Scan> scans, List<Predicate> predicates,
	                           List<Attribute> projectAtts) {
		if (op instanceof Scan) {
			scans.add((Scan) op);
		} else if (op instanceof Select) {
			predicates.add(((Select) op).getPredicate());
			collectParts(((Select) op).getInput(), scans, predicates, projectAtts);
		} else if (op instanceof Project) {
			collectParts(((Project) op).getInput(), scans, predicates, projectAtts);
		} else if (op instanceof Product) {
			collectParts(((Product) op).getLeft(),  scans, predicates, projectAtts);
			collectParts(((Product) op).getRight(), scans, predicates, projectAtts);
		} else if (op instanceof Join) {
			predicates.add(((Join) op).getPredicate());
			collectParts(((Join) op).getLeft(),  scans, predicates, projectAtts);
			collectParts(((Join) op).getRight(), scans, predicates, projectAtts);
		}
	}

	/**
	 * Return true if operator op's output contains the given attribute.
	 */
	private boolean hasAttribute(Operator op, Attribute attr) {
		return op.getOutput().getAttributes().contains(attr);
	}

	/**
	 * Find a join predicate (attr=attr) that references one attribute from
	 * each of the two sub-plans, or null if none exists.
	 */
	private Predicate findJoinPredicate(Operator left, Operator right, List<Predicate> predicates) {
		for (Predicate p : predicates) {
			if (!p.equalsValue()) {
				boolean leftHasLeft   = hasAttribute(left,  p.getLeftAttribute());
				boolean rightHasRight = hasAttribute(right, p.getRightAttribute());
				boolean leftHasRight  = hasAttribute(left,  p.getRightAttribute());
				boolean rightHasLeft  = hasAttribute(right, p.getLeftAttribute());

				if ((leftHasLeft && rightHasRight) || (leftHasRight && rightHasLeft)) {
					return p;
				}
			}
		}
		return null;
	}

	/**
	 * Ensure the predicate's left attribute belongs to the left operator.
	 * Swap if necessary.
	 */
	private Predicate orderPredicate(Operator left, Operator right, Predicate pred) {
		if (hasAttribute(left, pred.getLeftAttribute())) {
			return pred;
		}
		// Swap left and right attributes
		return new Predicate(pred.getRightAttribute(), pred.getLeftAttribute());
	}

	/**
	 * Estimate the output tuple count if left and right are combined using
	 * the given predicate (or a product if pred is null).
	 * Uses temporary operators + Estimator to compute sizes.
	 */
	private int estimateCombined(Operator left, Operator right, Predicate pred) {
		Operator temp;
		if (pred != null) {
			Predicate orderedPred = orderPredicate(left, right, pred);
			temp = new Join(left, right, orderedPred);
		} else {
			temp = new Product(left, right);
		}
		temp.accept(estimator);
		return temp.getOutput().getTupleCount();
	}
}
