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

  @Test
  public void testArnesConstraintIsNotVacuouslyTrue() throws Exception {
    String constraint =
        "context brakesystem::BrakeDisk inv:\n"
            + "  let cadDisk=cad::Namespace.allInstances().select(b|b.id == self.id) in\n"
            + "  let brakeCaliper=brakesystem::BrakeCaliper.allInstances().first() in\n"
            + "  let cadCaliper=cad::Namespace.allInstances().select(b|b.id == brakeCaliper.id)"
            + " in\n"
            + "  cadCaliper.parameters.select(p|p.oclIsTypeOf(cad::Coordinate)).forAll(p|p.oclAsType(cad::Coordinate).x"
            + " >= 500)";

    ConstraintResult result =
        MultiModelOCLInterface.evaluateConstraint(
            constraint,
            new Path[] {BRAKESYSTEM_ECORE, CAD_ECORE},
            new Path[] {BRAKESYSTEM_INSTANCE, CAD_INSTANCE});

    assertTrue(result.isSuccess(), "Evaluation should succeed: " + result.toDetailedErrorString());
    assertFalse(
        result.isSatisfied(), "Bug reproduced: forAll vacuously true. cadCaliper was empty.");
  }

  @Test
  public void testFirstReturnsUsableIdForNamespaceLookup() throws Exception {
    String constraint =
        "context brakesystem::BrakeDisk inv:\n"
            + "  let brakeCaliper=brakesystem::BrakeCaliper.allInstances().first() in\n"
            + "  let cadCaliper=cad::Namespace.allInstances().select(b|b.id == brakeCaliper.id)"
            + " in\n"
            + "  cadCaliper.size() == 1";

    ConstraintResult result =
        MultiModelOCLInterface.evaluateConstraint(
            constraint,
            new Path[] {BRAKESYSTEM_ECORE, CAD_ECORE},
            new Path[] {BRAKESYSTEM_INSTANCE, CAD_INSTANCE});

    assertTrue(result.isSuccess(), "Evaluation should succeed: " + result.toDetailedErrorString());
    assertTrue(result.isSatisfied(), "Exactly 1 Namespace with the caliper id should be found.");
  }

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
    assertFalse(
        result.isSatisfied(),
        "Constraint should fail: caliper coordinate x=175 exceeds disk radius 165");
  }

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
    assertFalse(
        result.isSatisfied(),
        "Constraint should fail: caliper coordinate x=175 exceeds disk radius 165");
  }
}