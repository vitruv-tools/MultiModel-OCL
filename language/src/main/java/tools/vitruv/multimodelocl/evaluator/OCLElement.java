/*******************************************************************************
 * Copyright (c) 2026 Max Oesterle
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Max Oesterle - initial API and implementation
 *******************************************************************************/
package tools.vitruv.multimodelocl.evaluator;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;

/**
 * OCL Element - represents a single element in an OCL collection.
 *
 * <p>This is a sealed interface to ensure exhaustiveness in pattern matching.
 */
public sealed interface OCLElement
    permits OCLElement.IntValue,
        OCLElement.BoolValue,
        OCLElement.StringValue,
        OCLElement.ObjectRef,
        OCLElement.FloatValue,
        OCLElement.DoubleValue,
        OCLElement.NestedCollection,
        OCLElement.MetaclassValue,
        OCLElement.CastedMetaclassValue {

  /** Returns the EClass for metamodel elements, null for primitives. */
  default EClass getEClass() {
    return null;
  }

  /**
   * Try to get boolean value, returns null if not a BoolValue. Avoids instanceof checks in calling
   * code.
   */
  default Boolean tryGetBool() {
    return null;
  }

  /**
   * Try to get int value, returns null if not an IntValue. Avoids instanceof checks in calling
   * code.
   */
  default Integer tryGetInt() {
    return null;
  }

  /**
   * Try to get string value, returns null if not a StringValue. Avoids instanceof checks in calling
   * code.
   */
  default String tryGetString() {
    return null;
  }

  /**
   * Try to get float value, returns null if not a FloatValue. Represents EFloat attributes from EMF
   * metamodels. Avoids instanceof checks in calling code.
   */
  default Float tryGetFloat() {
    return null;
  }

  /**
   * Try to get double value, returns null if not a DoubleValue. Avoids instanceof checks in calling
   * code.
   */
  default Double tryGetDouble() {
    return null;
  }

  /**
   * Try to get EObject instance, returns null if not a MetaclassValue. Avoids instanceof checks in
   * calling code.
   */
  default EObject tryGetInstance() {
    return null;
  }

  /** Integer value element. Example: In Set{1, 2, 3}, each number is an IntValue */
  record IntValue(int value) implements OCLElement {
    @Override
    public String toString() {
      return String.valueOf(value);
    }

    @Override
    public Integer tryGetInt() {
      return value;
    }
  }

  /**
   * Float value element. Represents EFloat attributes from EMF metamodels (e.g., Coordinate.x).
   * Kept separate from DoubleValue to preserve the distinction between EFloat and EDouble in EMF.
   */
  record FloatValue(float value) implements OCLElement {
    @Override
    public String toString() {
      return String.valueOf(value);
    }

    @Override
    public Float tryGetFloat() {
      return value;
    }
  }

  /** Double value element. Represents EDouble attributes and OCL Real literals. */
  record DoubleValue(double value) implements OCLElement {
    @Override
    public String toString() {
      return String.valueOf(value);
    }

    @Override
    public Double tryGetDouble() {
      return value;
    }
  }

  /** Boolean value element. Example: In Set{true, false}, each boolean is a BoolValue */
  record BoolValue(boolean value) implements OCLElement {
    @Override
    public String toString() {
      return String.valueOf(value);
    }

    @Override
    public Boolean tryGetBool() {
      return value;
    }
  }

  /** String value element. Example: In Set{"hello", "world"}, each string is a StringValue */
  record StringValue(String value) implements OCLElement {
    @Override
    public String toString() {
      return "\"" + value + "\"";
    }

    @Override
    public String tryGetString() {
      return value;
    }
  }

  /**
   * Object reference element (for EObjects from VSUM). Stores the object ID (OID) as a string.
   *
   * <p>Example: Person objects from a metamodel
   */
  record ObjectRef(String oid) implements OCLElement {
    @Override
    public String toString() {
      return "@" + oid;
    }
  }

  /**
   * Nested collection element. Used for nested collection types like Bag{Set{Integer}}.
   *
   * <p>Example: - {{int}} (Bag of Sets) - [[int]] (Sequence of Sequences) - {¡int!} (Set of
   * Singletons)
   */
  record NestedCollection(Value value) implements OCLElement {
    @Override
    public String toString() {
      return value.toString();
    }
  }

  /**
   * Metaclass value element (for EObjects from metamodels). Used for cross-metamodel constraints
   * with ~ operator.
   */
  record MetaclassValue(org.eclipse.emf.ecore.EObject instance) implements OCLElement {
    @Override
    public EClass getEClass() {
      return instance.eClass();
    }

    @Override
    public String toString() {
      return instance.eClass().getName() + "@" + System.identityHashCode(instance);
    }

    @Override
    public EObject tryGetInstance() {
      return instance;
    }
  }

  /**
   * Compares two OCL elements for semantic equality. Used by merge operations and equality
   * checking.
   */
  static boolean semanticEquals(OCLElement a, OCLElement b) {
    if (a == null && b == null) return true;
    if (a == null || b == null) return false;

    // Exact type matches
    if (a instanceof IntValue ia && b instanceof IntValue ib) {
      return ia.value == ib.value;
    }
    if (a instanceof FloatValue fa && b instanceof FloatValue fb) {
      return Float.compare(fa.value, fb.value) == 0;
    }
    if (a instanceof DoubleValue da && b instanceof DoubleValue db) {
      return Double.compare(da.value, db.value) == 0;
    }
    if (a instanceof BoolValue ba && b instanceof BoolValue bb) {
      return ba.value == bb.value;
    }
    if (a instanceof StringValue sa && b instanceof StringValue sb) {
      return sa.value.equals(sb.value);
    }
    if (a instanceof ObjectRef oa && b instanceof ObjectRef ob) {
      return oa.oid.equals(ob.oid);
    }
    if (a instanceof NestedCollection na && b instanceof NestedCollection nb) {
      return Value.semanticEquals(na.value, nb.value);
    }
    if (a instanceof MetaclassValue ma && b instanceof MetaclassValue mb) {
      return ma.instance.equals(mb.instance);
    }

    // Numeric type coercion: Float/Double/Int cross-comparisons
    double aNum = toDouble(a);
    double bNum = toDouble(b);
    if (!Double.isNaN(aNum) && !Double.isNaN(bNum)) {
      return Double.compare(aNum, bNum) == 0;
    }

    return false;
  }

  /**
   * Converts a numeric OCLElement to double for cross-type arithmetic. Returns NaN if the element
   * is not a numeric type.
   */
  private static double toDouble(OCLElement elem) {
    if (elem instanceof IntValue iv) return iv.value;
    if (elem instanceof FloatValue fv) return fv.value;
    if (elem instanceof DoubleValue dv) return dv.value;
    return Double.NaN;
  }

  /**
   * Compares two OCL elements for ordering. Used for normalizing unordered collections (Sets, Bags)
   * and comparison operators (<, >, <=, >=).
   *
   * <p>Numeric types (Int, Float, Double) are compared by value regardless of their concrete type,
   * so that e.g. FloatValue(165.0) < IntValue(500) works correctly.
   *
   * <p>Type order for heterogeneous non-numeric collections: Integers/Floats/Doubles &lt; Booleans
   * &lt; Strings &lt; ObjectRefs &lt; MetaclassValues &lt; NestedCollections
   */
  static int compare(OCLElement a, OCLElement b) {
    if (a == b) return 0;
    if (a == null) return -1;
    if (b == null) return 1;

    // Numeric comparison: handle Int/Float/Double mixing BEFORE type order check
    // so that e.g. 165.0f < 500 works correctly regardless of concrete numeric type
    double aNum = toDouble(a);
    double bNum = toDouble(b);
    if (!Double.isNaN(aNum) && !Double.isNaN(bNum)) {
      return Double.compare(aNum, bNum);
    }

    // Non-numeric: compare by type order first (for heterogeneous collection sorting)
    int typeA = getTypeOrder(a);
    int typeB = getTypeOrder(b);
    if (typeA != typeB) {
      return Integer.compare(typeA, typeB);
    }

    // Same non-numeric type: compare values
    if (a instanceof BoolValue ba && b instanceof BoolValue bb) {
      return Boolean.compare(ba.value, bb.value);
    }
    if (a instanceof StringValue sa && b instanceof StringValue sb) {
      return sa.value.compareTo(sb.value);
    }
    if (a instanceof ObjectRef oa && b instanceof ObjectRef ob) {
      return oa.oid.compareTo(ob.oid);
    }
    if (a instanceof NestedCollection na && b instanceof NestedCollection nb) {
      return Value.compare(na.value, nb.value);
    }
    if (a instanceof MetaclassValue ma && b instanceof MetaclassValue mb) {
      return Integer.compare(
          System.identityHashCode(ma.instance), System.identityHashCode(mb.instance));
    }

    return 0;
  }

  /**
   * Returns a numeric order for element types. Used for sorting heterogeneous collections. Numeric
   * types (Int, Float, Double) share the lowest order so they sort together.
   */
  static int getTypeOrder(OCLElement elem) {
    if (elem instanceof IntValue) return 0;
    if (elem instanceof FloatValue) return 0; // numeric — same tier as Int
    if (elem instanceof DoubleValue) return 0; // numeric — same tier as Int
    if (elem instanceof BoolValue) return 1;
    if (elem instanceof StringValue) return 2;
    if (elem instanceof ObjectRef) return 3;
    if (elem instanceof MetaclassValue) return 4;
    if (elem instanceof NestedCollection) return 5;
    return 6;
  }

  /**
   * A metaclass value that has been cast to a specific target type via oclAsType. getEClass()
   * returns the cast target type, not the runtime type of the instance. This allows property access
   * on the target type's features after casting.
   */
  record CastedMetaclassValue(EObject instance, EClass castedTo) implements OCLElement {
    @Override
    public EClass getEClass() {
      // Return the cast target type, not instance.eClass()
      // This ensures property access uses the superclass features
      return castedTo;
    }

    @Override
    public String toString() {
      return "("
          + castedTo.getName()
          + ") "
          + instance.eClass().getName()
          + "@"
          + System.identityHashCode(instance);
    }

    @Override
    public EObject tryGetInstance() {
      return instance;
    }
  }
}