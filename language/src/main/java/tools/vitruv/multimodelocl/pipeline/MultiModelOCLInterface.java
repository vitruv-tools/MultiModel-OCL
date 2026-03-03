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
package tools.vitruv.multimodelocl.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import tools.vitruv.multimodelocl.common.CompileError;
import tools.vitruv.multimodelocl.common.ErrorSeverity;
import tools.vitruv.multimodelocl.evaluator.EvaluationVisitor;
import tools.vitruv.multimodelocl.evaluator.Value;

/**
 * Main API for VitruvOCL constraint evaluation.
 *
 * <p>VitruvOCL implements semantics with unified dot-notation (no arrow operator), 1-based
 * indexing, and "everything is a collection" philosophy where single values become singletons and
 * null becomes empty collections.
 *
 * <p>Provides high-level methods for evaluating OCL constraints against Ecore models:
 *
 * <ul>
 *   <li>Single constraint evaluation with explicit file lists
 *   <li>Batch constraint evaluation from constraint files
 *   <li>Convention-over-configuration project evaluation
 * </ul>
 *
 * <p><b>Thread Safety:</b> All methods are thread-safe. Each invocation creates isolated compiler
 * and loader instances with no shared mutable state.
 *
 * <h2>Constraint Syntax</h2>
 *
 * VitruvOCL constraints must begin with {@code context} keyword:
 *
 * <pre>
 * context MetamodelName::ClassName inv constraintName:
 *   expression
 * </pre>
 *
 * <p><b>Key Syntax Differences from Standard OCL:</b>
 *
 * <ul>
 *   <li>Always use dot (.) for navigation - no arrow (->) operator
 *   <li>Use {@code !=} instead of {@code <>} for inequality
 *   <li>Fully qualified names: {@code spaceMission::Spacecraft}
 *   <li>All values are collections (singletons like {@code [5]} or empty {@code []})
 * </ul>
 *
 * <h2>Violation Reporting</h2>
 *
 * <p>When a constraint is violated, one {@link Warning} of type {@code CONSTRAINT_VIOLATION} is
 * emitted per violating instance, in the format:
 *
 * <pre>
 * [VIOLATION] constraintName @ filename :: ClassName(attr1="val1", attr2="val2")
 * </pre>
 *
 * @see ConstraintResult for single constraint evaluation results
 * @see BatchValidationResult for multi-constraint validation results
 */
public class MultiModelOCLInterface {

  /**
   * Evaluates single constraint against provided models.
   *
   * <p>Performs three-pass compilation pipeline:
   *
   * <ol>
   *   <li>Smart metamodel loading (only loads metamodels referenced in constraint)
   *   <li>Symbol table construction and type checking
   *   <li>Runtime evaluation against model instances
   * </ol>
   *
   * <p>For each instance of the context type that violates the constraint, a separate {@link
   * Warning} of type {@code CONSTRAINT_VIOLATION} is added to the result, identifying the source
   * file and the concrete instance by its attribute values.
   *
   * @param constraint OCL constraint expression (must start with {@code context})
   * @param ecoreFiles Metamodel definition files (.ecore)
   * @param xmiFiles Model instance files (any EMF-compatible extension)
   * @return Evaluation result with satisfaction status and diagnostics. Use {@code isSuccess()} to
   *     check for compilation errors, {@code isSatisfied()} to check constraint satisfaction.
   */
  public static ConstraintResult evaluateConstraint(
      String constraint, Path[] ecoreFiles, Path[] xmiFiles) {
    SmartLoader.LoadResult loadResult =
        SmartLoader.loadForConstraint(constraint, ecoreFiles, xmiFiles);

    if (loadResult.hasErrors()) {
      return new ConstraintResult(
          constraint, false, List.of(), loadResult.fileErrors, loadResult.warnings);
    }

    OCLCompiler compiler = new OCLCompiler(loadResult.wrapper, null);
    Value result = compiler.compile(constraint);

    if (result == null) {
      return new ConstraintResult(
          constraint,
          false,
          List.of(
              new CompileError(
                  1, 0, "Syntax error in constraint", ErrorSeverity.ERROR, constraint)),
          loadResult.fileErrors,
          loadResult.warnings);
    }

    List<CompileError> compilerErrors =
        compiler.hasErrors() ? compiler.getErrors().getErrors() : List.of();

    if (!compilerErrors.isEmpty()) {
      return new ConstraintResult(
          constraint, false, compilerErrors, loadResult.fileErrors, loadResult.warnings);
    }

    List<Warning> warnings = new ArrayList<>(loadResult.warnings);

    // Retrieve violating EObjects directly from the evaluator
    EvaluationVisitor evaluator = compiler.getLastEvaluator();
    List<EObject> violatingInstances =
        evaluator != null ? evaluator.getViolatingInstances() : List.of();

    boolean satisfied = violatingInstances.isEmpty();

    for (EObject instance : violatingInstances) {
      String sourceFile = loadResult.wrapper.getSourceFileForInstance(instance);
      String filename = sourceFile != null ? sourceFile : "unknown";
      String instanceLabel = describeInstance(instance);
      String constraintName = extractConstraintName(constraint);
      warnings.add(
          new Warning(
              Warning.WarningType.CONSTRAINT_VIOLATION,
              "[VIOLATION] " + constraintName + " @ " + filename + " :: " + instanceLabel));
    }

    return new ConstraintResult(
        constraint, satisfied, compilerErrors, loadResult.fileErrors, warnings);
  }

  /**
   * Evaluates multiple constraints, deduplicating and reporting duplicates as warnings.
   *
   * <p>Each constraint is evaluated independently. Duplicate constraints (exact string match) are
   * detected and marked with warnings but not re-evaluated.
   *
   * @param constraints List of constraint expressions (each must start with {@code context})
   * @param ecoreFiles Metamodel definition files
   * @param xmiFiles Model instance files
   * @return Aggregated batch validation result containing individual constraint results
   */
  public static BatchValidationResult evaluateConstraints(
      List<String> constraints, Path[] ecoreFiles, Path[] xmiFiles) {
    List<ConstraintResult> results = new ArrayList<>();
    Set<String> seenConstraints = new HashSet<>();

    for (String constraint : constraints) {
      if (seenConstraints.contains(constraint)) {
        ConstraintResult duplicate =
            new ConstraintResult(
                constraint,
                false,
                List.of(),
                List.of(),
                List.of(
                    new Warning(
                        Warning.WarningType.DUPLICATE_CONSTRAINT,
                        "Constraint specified multiple times")));
        results.add(duplicate);
        continue;
      }

      seenConstraints.add(constraint);
      results.add(evaluateConstraint(constraint, ecoreFiles, xmiFiles));
    }

    return new BatchValidationResult(results);
  }

  /**
   * Evaluates constraints from file.
   *
   * <p>Parses constraint file format:
   *
   * <pre>
   * -- Comments start with double dash
   * context Type1 inv:
   *   expression1
   *
   * context Type2 inv:
   *   expression2
   * </pre>
   *
   * @param constraintsFile File containing constraint definitions
   * @param ecoreFiles Metamodel definition files
   * @param xmiFiles Model instance files
   * @return Batch validation result for all constraints in file
   * @throws IOException If constraint file cannot be read
   */
  public static BatchValidationResult evaluateConstraints(
      Path constraintsFile, Path[] ecoreFiles, Path[] xmiFiles) throws IOException {
    List<String> constraints = parseConstraintsFile(constraintsFile);
    return evaluateConstraints(constraints, ecoreFiles, xmiFiles);
  }

  /**
   * Evaluates project using convention-over-configuration directory structure.
   *
   * <p>Expected structure:
   *
   * <pre>
   * projectDir/
   *   model/src/main/
   *     constraints.ocl       - Constraint definitions
   *     ecore/               - All .ecore files
   *     instances/           - All model instance files
   * </pre>
   *
   * <p>Automatically discovers all metamodels and instances in respective directories. Instance
   * files can have any EMF-compatible extension (.xmi, .spacemission, .satellitesystem, etc.).
   *
   * @param projectDir Root directory of the project
   * @return Batch validation result for all constraints
   * @throws IOException If files cannot be read or required directories don't exist
   */
  public static BatchValidationResult evaluateProject(Path projectDir) throws IOException {
    Path mainDir = projectDir.resolve("model/src/main");
    Path constraintsFile = mainDir.resolve("constraints.ocl");
    Path ecoreDir = mainDir.resolve("ecore");
    Path instancesDir = mainDir.resolve("instances");

    Path[] ecoreFiles = collectFiles(ecoreDir, ".ecore");
    Path[] xmiFiles = collectAllFiles(instancesDir);

    return evaluateConstraints(constraintsFile, ecoreFiles, xmiFiles);
  }

  /**
   * Evaluates project with custom constraint file location.
   *
   * <p>Uses convention-over-configuration for metamodels and instances under {@code
   * resourcesDir/model/src/main/}, but allows the constraints file to be located anywhere.
   *
   * @param constraintsFile Path to constraints file
   * @param resourcesDir Root directory containing model/src/main/ecore and model/src/main/instances
   * @return Batch validation result
   * @throws IOException If files cannot be read
   */
  public static BatchValidationResult evaluateProject(Path constraintsFile, Path resourcesDir)
      throws IOException {
    Path mainDir = resourcesDir.resolve("model/src/main");
    Path ecoreDir = mainDir.resolve("ecore");
    Path instancesDir = mainDir.resolve("instances");

    Path[] ecoreFiles = collectFiles(ecoreDir, ".ecore");
    Path[] xmiFiles = collectAllFiles(instancesDir);

    return evaluateConstraints(constraintsFile, ecoreFiles, xmiFiles);
  }

  /**
   * Parses constraint file, extracting individual constraint definitions.
   *
   * <p>Splits file by {@code context} keyword after removing comment lines (starting with {@code
   * --}).
   *
   * @param file Constraint file to parse
   * @return List of constraint expressions, each starting with {@code context}
   * @throws IOException If file cannot be read
   */
  private static List<String> parseConstraintsFile(Path file) throws IOException {
    String content = Files.readString(file);
    List<String> constraints = new ArrayList<>();

    // Remove comments
    String[] lines = content.split("\n");
    StringBuilder cleaned = new StringBuilder();
    for (String line : lines) {
      String trimmed = line.trim();
      if (!trimmed.startsWith("--") && !trimmed.isEmpty()) {
        cleaned.append(line).append("\n");
      }
    }

    // Split by "context" keyword
    String[] parts = cleaned.toString().split("(?=context\\s)");
    for (String part : parts) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty() && trimmed.startsWith("context")) {
        constraints.add(trimmed);
      }
    }

    return constraints;
  }

  /**
   * Recursively collects files with given extensions from directory.
   *
   * <p>Extensions are case-insensitive. Returns empty array if directory doesn't exist (allowing
   * graceful handling of optional directories).
   *
   * @param directory Directory to search recursively
   * @param extensions File extensions to match (e.g., ".ecore", ".xmi", ".spacemission")
   * @return Array of matching file paths, empty if directory doesn't exist
   * @throws IOException If directory traversal fails
   */
  private static Path[] collectFiles(Path directory, String... extensions) throws IOException {
    if (!Files.exists(directory) || !Files.isDirectory(directory)) {
      return new Path[0];
    }

    try (Stream<Path> stream = Files.walk(directory)) {
      List<Path> files =
          stream
              .filter(Files::isRegularFile)
              .filter(
                  p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    for (String ext : extensions) {
                      if (name.endsWith(ext)) {
                        return true;
                      }
                    }
                    return false;
                  })
              .collect(Collectors.toList());

      return files.toArray(new Path[0]);
    }
  }

  /**
   * Produces a human-readable label for an EObject, using its EClass name and up to 3 primitive
   * attribute values as identifiers.
   *
   * <p>Example output: {@code Spacecraft(name="Apollo", serialNumber="SC-004")}
   *
   * @param instance The model instance to describe
   * @return Human-readable instance label
   */
  private static String describeInstance(EObject instance) {
    StringBuilder sb = new StringBuilder(instance.eClass().getName()).append("(");

    List<String> parts = new ArrayList<>();
    for (EStructuralFeature feature : instance.eClass().getEAllStructuralFeatures()) {
      if (feature.isMany()) continue;
      Object value = instance.eGet(feature);
      if (value instanceof String || value instanceof Integer || value instanceof Boolean) {
        parts.add(feature.getName() + "=\"" + value + "\"");
      }
      if (parts.size() >= 3) break;
    }

    sb.append(String.join(", ", parts)).append(")");
    return sb.toString();
  }

  /**
   * Extracts the constraint name (inv name) from the constraint source string.
   *
   * <p>Example: {@code "context spaceMission::Spacecraft inv serialInclusion:\n ..."} → {@code
   * "serialInclusion"}. Falls back to {@code "unnamed"} if no name is found.
   *
   * @param constraint The OCL constraint source string
   * @return The invariant name, or "unnamed" if not found
   */
  private static String extractConstraintName(String constraint) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("inv\\s+(\\w+)\\s*:").matcher(constraint);
    return m.find() ? m.group(1) : "unnamed";
  }

  /**
   * Recursively collects all files from a directory regardless of extension.
   *
   * <p>Returns empty array if directory doesn't exist (allowing graceful handling of optional
   * directories).
   *
   * @param directory Directory to search recursively
   * @return Array of all file paths, empty if directory doesn't exist
   * @throws IOException If directory traversal fails
   */
  private static Path[] collectAllFiles(Path directory) throws IOException {
    if (!Files.exists(directory) || !Files.isDirectory(directory)) {
      return new Path[0];
    }

    try (Stream<Path> stream = Files.walk(directory)) {
      return stream.filter(Files::isRegularFile).collect(Collectors.toList()).toArray(new Path[0]);
    }
  }
}