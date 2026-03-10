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
package tools.vitruv.multimodelocl;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Test;
import tools.vitruv.multimodelocl.common.ErrorCollector;
import tools.vitruv.multimodelocl.evaluator.OCLElement;
import tools.vitruv.multimodelocl.evaluator.Value;
import tools.vitruv.multimodelocl.pipeline.MetamodelWrapperInterface;
import tools.vitruv.multimodelocl.symboltable.ScopeAnnotator;
import tools.vitruv.multimodelocl.symboltable.SymbolTable;
import tools.vitruv.multimodelocl.symboltable.SymbolTableBuilder;
import tools.vitruv.multimodelocl.symboltable.SymbolTableImpl;
import tools.vitruv.multimodelocl.typechecker.Type;
import tools.vitruv.multimodelocl.typechecker.TypeCheckVisitor;

/**
 * Comprehensive test suite for the {@code oclIsKindOf} type checking operation in VitruvOCL.
 *
 * @see Value Runtime collection representation
 * @see tools.vitruv.multimodelocl.evaluator.EvaluationVisitor Evaluates oclIsKindOf operations
 * @see tools.vitruv.multimodelocl.typechecker.TypeCheckVisitor Type checks oclIsKindOf expressions
 */
public class OCLIsKindOfTest extends DummyTestSpecification {

  // ==================== Integer Type Checking ====================

  /** Tests Integer is kind of Integer → {@code [true]}. */
  @Test
  public void testIntegerIsKindOfInteger() {
    assertSingleBool(compile("Set{5}.oclIsKindOf(Integer)"), true);
  }

  /** Tests Integer is NOT kind of String → {@code [false]}. */
  @Test
  public void testIntegerIsKindOfString() {
    assertSingleBool(compile("Set{5}.oclIsKindOf(String)"), false);
  }

  /** Tests Integer is NOT kind of Boolean → {@code [false]}. */
  @Test
  public void testIntegerIsKindOfBoolean() {
    assertSingleBool(compile("Set{5}.oclIsKindOf(Boolean)"), false);
  }

  // ==================== String Type Checking ====================

  /** Tests String is kind of String → {@code [true]}. */
  @Test
  public void testStringIsKindOfString() {
    assertSingleBool(compile("Set{\"hello\"}.oclIsKindOf(String)"), true);
  }

  /** Tests String is NOT kind of Integer → {@code [false]}. */
  @Test
  public void testStringIsKindOfInteger() {
    assertSingleBool(compile("Set{\"hello\"}.oclIsKindOf(Integer)"), false);
  }

  /** Tests String is NOT kind of Boolean → {@code [false]}. */
  @Test
  public void testStringIsKindOfBoolean() {
    assertSingleBool(compile("Set{\"hello\"}.oclIsKindOf(Boolean)"), false);
  }

  // ==================== Boolean Type Checking ====================

  /** Tests Boolean is kind of Boolean → {@code [true]}. */
  @Test
  public void testBooleanIsKindOfBoolean() {
    assertSingleBool(compile("Set{true}.oclIsKindOf(Boolean)"), true);
  }

  /** Tests Boolean is NOT kind of Integer → {@code [false]}. */
  @Test
  public void testBooleanIsKindOfInteger() {
    assertSingleBool(compile("Set{true}.oclIsKindOf(Integer)"), false);
  }

  /** Tests Boolean is NOT kind of String → {@code [false]}. */
  @Test
  public void testBooleanIsKindOfString() {
    assertSingleBool(compile("Set{false}.oclIsKindOf(String)"), false);
  }

  // ==================== Multiple Elements ====================

  /** Tests all Integer elements → all true: {@code Set{1,2,3}.oclIsKindOf(Integer)}. */
  @Test
  public void testMultipleIntegersIsKindOfInteger() {
    Value result = compile("Set{1, 2, 3}.oclIsKindOf(Integer)");
    assertSize(result, 3);
    for (OCLElement elem : result.getElements()) {
      assertTrue(((OCLElement.BoolValue) elem).value());
    }
  }

  /** Tests Integer elements checked against String → all false. */
  @Test
  public void testMultipleIntegersIsKindOfString() {
    Value result = compile("Set{1, 2, 3}.oclIsKindOf(String)");
    assertSize(result, 3);
    for (OCLElement elem : result.getElements()) {
      assertFalse(((OCLElement.BoolValue) elem).value());
    }
  }

  /** Tests all String elements → all true. */
  @Test
  public void testMultipleStringsIsKindOfString() {
    Value result = compile("Set{\"a\", \"b\", \"c\"}.oclIsKindOf(String)");
    assertSize(result, 3);
    for (OCLElement elem : result.getElements()) {
      assertTrue(((OCLElement.BoolValue) elem).value());
    }
  }

  /** Tests Boolean Set with duplicates: {true,false,true} → 2 elements, both true. */
  @Test
  public void testMultipleBooleansIsKindOfBoolean() {
    Value result = compile("Set{true, false, true}.oclIsKindOf(Boolean)");
    assertSize(result, 2);
    for (OCLElement elem : result.getElements()) {
      assertTrue(((OCLElement.BoolValue) elem).value());
    }
  }

  // ==================== Empty Collection ====================

  /** Tests empty collection → empty result. */
  @Test
  public void testEmptyCollectionIsKindOf() {
    assertSize(compile("Set{}.oclIsKindOf(Integer)"), 0);
  }

  // ==================== Sequence Preservation ====================

  /** Tests Sequence order preserved: {@code Sequence{1,2,3}.oclIsKindOf(Integer)} → all true. */
  @Test
  public void testSequencePreservesOrder() {
    Value result = compile("Sequence{1, 2, 3}.oclIsKindOf(Integer)");
    assertSize(result, 3);
    for (OCLElement elem : result.getElements()) {
      assertTrue(((OCLElement.BoolValue) elem).value());
    }
  }

  // ==================== Type Checking ====================

  /** Tests type checker infers Collection(Boolean) as result type. */
  @Test
  public void testTypeCheckReturnsBoolean() {
    ParseTree tree = parse("Set{5}.oclIsKindOf(Integer)");
    MetamodelWrapperInterface dummySpec = buildDummySpec();
    SymbolTable symbolTable = new SymbolTableImpl(dummySpec);
    ScopeAnnotator scopeAnnotator = new ScopeAnnotator();
    ErrorCollector errors = new ErrorCollector();

    new SymbolTableBuilder(symbolTable, dummySpec, errors, scopeAnnotator).visit(tree);
    assertFalse(errors.hasErrors(), "Pass 1 should not have errors");

    TypeCheckVisitor typeChecker =
        new TypeCheckVisitor(symbolTable, dummySpec, errors, scopeAnnotator);
    Type resultType = typeChecker.visit(tree);

    assertFalse(typeChecker.hasErrors());
    assertTrue(resultType.isCollection());
    assertEquals(Type.BOOLEAN, resultType.getElementType());
  }

  /** Tests type checker preserves Sequence collection kind. */
  @Test
  public void testTypeCheckPreservesCollectionKind() {
    ParseTree tree = parse("Sequence{1, 2}.oclIsKindOf(Integer)");
    MetamodelWrapperInterface dummySpec = buildDummySpec();
    SymbolTable symbolTable = new SymbolTableImpl(dummySpec);
    ScopeAnnotator scopeAnnotator = new ScopeAnnotator();
    ErrorCollector errors = new ErrorCollector();

    new SymbolTableBuilder(symbolTable, dummySpec, errors, scopeAnnotator).visit(tree);
    assertFalse(errors.hasErrors(), "Pass 1 should not have errors");

    TypeCheckVisitor typeChecker =
        new TypeCheckVisitor(symbolTable, dummySpec, errors, scopeAnnotator);
    Type resultType = typeChecker.visit(tree);

    assertFalse(typeChecker.hasErrors());
    assertTrue(resultType.isCollection());
    assertTrue(resultType.isOrdered());
    assertEquals(Type.BOOLEAN, resultType.getElementType());
  }

  // ==================== Mixed Type Collections ====================

  /** Tests mixed types checking for Integer: {1,"hello",true} → {true,false,false}. */
  @Test
  public void testMixedTypesInCollection() {
    Value result = compile("Sequence{1, \"hello\", true}.oclIsKindOf(Integer)");
    assertSize(result, 3);
    List<OCLElement> elements = result.getElements();
    assertTrue(((OCLElement.BoolValue) elements.get(0)).value());
    assertFalse(((OCLElement.BoolValue) elements.get(1)).value());
    assertFalse(((OCLElement.BoolValue) elements.get(2)).value());
  }

  /** Tests mixed types checking for String: {1,"hello",true,"world"} → {false,true,false,true}. */
  @Test
  public void testMixedTypesCheckingForString() {
    Value result = compile("Sequence{1, \"hello\", true, \"world\"}.oclIsKindOf(String)");
    assertSize(result, 4);
    List<OCLElement> elements = result.getElements();
    assertFalse(((OCLElement.BoolValue) elements.get(0)).value());
    assertTrue(((OCLElement.BoolValue) elements.get(1)).value());
    assertFalse(((OCLElement.BoolValue) elements.get(2)).value());
    assertTrue(((OCLElement.BoolValue) elements.get(3)).value());
  }

  /** Tests {1,"test",true}.oclIsKindOf(Boolean) → exactly one true. */
  @Test
  public void testAllDifferentTypesCheckBoolean() {
    Value result = compile("Set{1, \"test\", true}.oclIsKindOf(Boolean)");
    assertSize(result, 3);
    int trueCount = 0;
    for (OCLElement elem : result.getElements()) {
      if (((OCLElement.BoolValue) elem).value()) trueCount++;
    }
    assertEquals(1, trueCount, "Exactly one element should be Boolean");
  }

  /** Tests all Strings checked against Integer → all false. */
  @Test
  public void testEmptyResultFromMixedCollection() {
    Value result = compile("Set{\"hello\", \"world\", \"test\"}.oclIsKindOf(Integer)");
    assertSize(result, 3);
    for (OCLElement elem : result.getElements()) {
      assertFalse(((OCLElement.BoolValue) elem).value());
    }
  }

  /**
   * Tests flatten then oclIsKindOf on nested mixed collection →
   * {true,true,false,false,false,false}.
   */
  @Test
  public void testNestedCollectionsWithMixedTypes() {
    Value result =
        compile(
            "Sequence{Set{1, 2}, Set{\"a\", \"b\"}, Set{true,"
                + " false}}.flatten().oclIsKindOf(Integer)");
    assertSize(result, 6);
    List<OCLElement> elements = result.getElements();
    assertTrue(((OCLElement.BoolValue) elements.get(0)).value());
    assertTrue(((OCLElement.BoolValue) elements.get(1)).value());
    assertFalse(((OCLElement.BoolValue) elements.get(2)).value());
    assertFalse(((OCLElement.BoolValue) elements.get(3)).value());
    assertFalse(((OCLElement.BoolValue) elements.get(4)).value());
    assertFalse(((OCLElement.BoolValue) elements.get(5)).value());
  }

  // ==================== Entry Point Override ====================

  /** Overrides parse entry point to use {@code infixedExpCS()} for oclIsKindOf expressions. */
  @Override
  protected ParseTree parse(String input) {
    CommonTokenStream tokens = new CommonTokenStream(new OCLLexer(CharStreams.fromString(input)));
    return new OCLParser(tokens).infixedExpCS();
  }
}