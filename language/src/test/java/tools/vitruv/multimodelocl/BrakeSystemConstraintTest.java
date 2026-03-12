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

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.vitruv.multimodelocl.pipeline.ConstraintResult;
import tools.vitruv.multimodelocl.pipeline.MetamodelWrapper;
import tools.vitruv.multimodelocl.pipeline.MultiModelOCLInterface;

/**
 * Tests for brake system cross-metamodel constraints using brakesystem and cad metamodels.
 *
 * <p>Tests the oclAsType cast operation with real inheritance (Coordinate extends Parameter) to
 * verify that property access on the cast type works correctly.
 */
public class BrakeSystemConstraintTest {

  private static final Path BRAKESYSTEM_ECORE =
      Path.of("src/test/resources/test-metamodels/brakesystem.ecore");
  private static final Path CAD_ECORE = Path.of("src/test/resources/test-metamodels/cad.ecore");

  private static final Path BRAKESYSTEM_INSTANCE = Path.of("brakesystem.brakesystem");
  private static final Path CAD_INSTANCE = Path.of("brake_disc_and_caliper_plate.cad");

  @BeforeAll
  public static void setupPaths() {
    MetamodelWrapper.TEST_MODELS_PATH = Path.of("src/test/resources/test-models");
  }

  /**
   * Tests that Coordinate parameters in the caliper namespace can be filtered and cast. Verifies
   * oclIsTypeOf + oclAsType pipeline with real inheritance (Coordinate extends Parameter). The
   * caliper has Coordinates with x=165 and x=175, disk diameter=330 (radius=165). x=175 > 165 →
   * constraint should NOT be satisfied.
   */
  @Test
  public void testCaliperCoordinatesWithinDiskRadius() throws Exception {
    String constraint =
        """
context brakesystem::BrakeDisk inv coordinatesWithinRadius:
  let cadCaliper = cad::Namespace.allInstances().select(b | b.id == brakesystem::BrakeCaliper.allInstances().first().id) in
  cadCaliper.parameters.select(p | p.oclIsTypeOf(cad::Coordinate)).forAll(p | p.oclAsType(cad::Coordinate).x <= self.diameterInMM / 2)
""";

    ConstraintResult result =
        MultiModelOCLInterface.evaluateConstraint(
            constraint,
            new Path[] {BRAKESYSTEM_ECORE, CAD_ECORE},
            new Path[] {BRAKESYSTEM_INSTANCE, CAD_INSTANCE});

    assertTrue(result.isSuccess(), "Evaluation should succeed: " + result.toDetailedErrorString());
    // x=175 > 165 (radius) → not satisfied
    assertFalse(
        result.isSatisfied(),
        "Constraint should fail: caliper coordinate x=175 exceeds disk radius 165");
  }

  /**
   * Tests that oclIsTypeOf correctly filters only Coordinate instances from mixed Parameter list.
   * The caliper namespace has 4 Coordinates and 1 NumericParameter.
   */
  @Test
  public void testFilterCoordinatesFromMixedParameters() throws Exception {
    String constraint =
        """
context brakesystem::BrakeDisk inv onlyCoordinates:
  let cadCaliper = cad::Namespace.allInstances().select(b | b.id == brakesystem::BrakeCaliper.allInstances().first().id) in
  cadCaliper.parameters.select(p | p.oclIsTypeOf(cad::Coordinate)).size() == 4
""";

    ConstraintResult result =
        MultiModelOCLInterface.evaluateConstraint(
            constraint,
            new Path[] {BRAKESYSTEM_ECORE, CAD_ECORE},
            new Path[] {BRAKESYSTEM_INSTANCE, CAD_INSTANCE});

    assertTrue(result.isSuccess(), "Evaluation should succeed: " + result.toDetailedErrorString());
    assertTrue(
        result.isSatisfied(), "Should find exactly 4 Coordinate parameters in caliper namespace");
  }

  /**
   * Tests that oclIsKindOf includes both Coordinate and NumericParameter (both extend Parameter).
   * The caliper namespace has 5 parameters total (4 Coordinates + 1 NumericParameter).
   */
  @Test
  public void testFilterAllParameterSubtypes() throws Exception {
    String constraint =
        """
context brakesystem::BrakeDisk inv allSubtypes:
  let cadCaliper = cad::Namespace.allInstances().select(b | b.id == brakesystem::BrakeCaliper.allInstances().first().id) in
  cadCaliper.parameters.select(p | p.oclIsKindOf(cad::Parameter)).size() == 5
""";

    ConstraintResult result =
        MultiModelOCLInterface.evaluateConstraint(
            constraint,
            new Path[] {BRAKESYSTEM_ECORE, CAD_ECORE},
            new Path[] {BRAKESYSTEM_INSTANCE, CAD_INSTANCE});

    assertTrue(result.isSuccess(), "Evaluation should succeed: " + result.toDetailedErrorString());
    assertTrue(
        result.isSatisfied(),
        "Should find all 5 parameters (Coordinate + NumericParameter) via oclIsKindOf");
  }

  @Test
  public void testOclAsTypePropertyAccessX() throws Exception {
    String constraint =
        """
        context brakesystem::BrakeDisk inv:
          cad::Namespace.allInstances().select(b | b.id == self.id)
            .parameters.select(p | p.oclIsTypeOf(cad::Coordinate))
            .collect(p | p.oclAsType(cad::Coordinate).x).size() == 2
        """;

    ConstraintResult result =
        MultiModelOCLInterface.evaluateConstraint(
            constraint,
            new Path[] {BRAKESYSTEM_ECORE, CAD_ECORE},
            new Path[] {BRAKESYSTEM_INSTANCE, CAD_INSTANCE});

    assertTrue(result.isSuccess(), "Evaluation should succeed: " + result.toDetailedErrorString());
  }

  @Test
  public void testOriginalConstraintExact() throws Exception {
    String constraint =
        "context brakesystem::BrakeDisk inv:\n"
            + "  let cadDisk = cad::Namespace.allInstances().select(b | b.id == self.id) in\n"
            + "  let brakeCaliper = brakesystem::BrakeCaliper.allInstances().first() in\n"
            + "  let cadCaliper = cad::Namespace.allInstances().select(b | b.id == brakeCaliper.id)"
            + " in\n"
            + "  cadCaliper.parameters.select(p | p.oclIsTypeOf(cad::Coordinate)).forAll(p |"
            + " p.oclAsType(cad::Coordinate).x <= self.diameterInMM / 2)";

    ConstraintResult result =
        MultiModelOCLInterface.evaluateConstraint(
            constraint,
            new Path[] {BRAKESYSTEM_ECORE, CAD_ECORE},
            new Path[] {BRAKESYSTEM_INSTANCE, CAD_INSTANCE});

    assertTrue(result.isSuccess(), "Evaluation should succeed: " + result.toDetailedErrorString());
    // x=175 > 165 (radius) → not satisfied
    assertFalse(
        result.isSatisfied(),
        "Constraint should fail: caliper coordinate x=175 exceeds disk radius 165");
  }
}