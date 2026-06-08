# Database Query Optimiser — SJDB

**COMP3211: Advanced Databases**  
University of Southampton

## Overview

A query optimiser built on top of the SJDB (Simple Java DataBase) framework. Given a canonical query plan — a naïve tree of Cartesian Products and Selects — the system rewrites it into an efficient left-deep join tree, minimising intermediate result sizes using cost estimation.

## Components

### Estimator
Implements the `PlanVisitor` interface to annotate each operator in a query plan with its estimated output size, using standard relational algebra statistics:

| Operator | Tuple count formula |
|---|---|
| Scan | T(R) |
| Project | T(R) (no duplicate elimination) |
| Select attr=val | T(R) / V(R, A) |
| Select attr=attr | T(R) / max(V(R,A), V(R,B)) |
| Product | T(R) × T(S) |
| Join | T(R) × T(S) / max(V(R,A), V(S,B)) |

### Optimiser
Converts a canonical plan into an optimised plan in four steps:

1. **Extract** all `Scan` operators and `Select` predicates from the canonical plan tree
2. **Push down** `attr=val` selections onto their individual scans (reducing early)
3. **Greedy join ordering** — at each step, pick the pair of sub-plans with the smallest combined output; use a `Join` if an `attr=attr` predicate connects them, otherwise a `Product`
4. **Wrap** the final plan in a `Project` if the original query projected specific attributes

## Project Structure

```
sjdb/
├── src/sjdb/
│   ├── Optimiser.java        # Query plan rewriter (greedy left-deep join tree)
│   ├── Estimator.java        # Cost/size estimator for each operator
│   ├── SJDB.java             # Entry point — parses catalogue + query, runs optimiser
│   ├── Catalogue.java        # Stores relation metadata (tuple counts, value counts)
│   ├── CatalogueParser.java  # Parses catalogue files
│   ├── QueryParser.java      # Parses query files into canonical plan trees
│   ├── Operator.java         # Abstract base for all plan operators
│   ├── Scan.java             # Leaf operator — full table scan
│   ├── Select.java           # Filter operator
│   ├── Project.java          # Projection operator
│   ├── Join.java             # Equi-join operator
│   ├── Product.java          # Cartesian product operator
│   ├── Relation.java         # Relation with tuple count and attribute list
│   ├── Attribute.java        # Attribute with name and distinct value count
│   ├── Predicate.java        # Join/selection predicate (attr=val or attr=attr)
│   └── Inspector.java        # Pretty-prints a query plan tree
└── data/
    ├── cat.txt               # Sample catalogue (Person, Project, Department)
    ├── q1.txt – q5.txt       # Sample queries of increasing complexity
```

## Catalogue Format

```
RelationName:tupleCount:attr1,valueCount:attr2,valueCount:...
```

Example (`cat.txt`):
```
Person:400:persid,400:persname,350:age,47
Project:40:projid,40:projname,35:dept,5
Department:5:deptid,5:deptname,5:manager,5
```

## Running

Compile and run from the `sjdb/` directory:

```bash
javac -d bin src/sjdb/*.java
java -cp bin sjdb.SJDB data/cat.txt data/q3.txt
```

The output shows both the canonical plan and the optimised plan with estimated tuple counts at each step.

## Report

See [Report.pdf](Report.pdf) for the full write-up covering the estimation formulas, optimisation strategy, and results.
