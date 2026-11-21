package dev.denrzv.codereview;

import java.lang.module.ModuleFinder;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureTest {

    private static final String ROOT = "dev.denrzv.codereview";
    private static final String DOMAIN = ROOT + ".domain..";
    private static final String APPLICATION = ROOT + ".application..";
    private static final String ORCHESTRATION = ROOT + ".orchestration..";
    private static final String WORKFLOW = ROOT + ".orchestration.workflow..";
    private static final String WORKFLOW_CONTRACT = ROOT + ".orchestration.contract..";
    private static final String ACTIVITY_CONTRACT = ROOT + ".orchestration.activity..";
    private static final String ORCHESTRATION_CLIENT = ROOT + ".orchestration.client..";
    private static final String TEMPORAL_WORKFLOW_API = "io.temporal.workflow..";
    private static final String TEMPORAL_ACTIVITY_API = "io.temporal.activity..";

    private static final Set<String> JDK_PACKAGES = ModuleFinder.ofSystem().findAll().stream()
            .flatMap(module -> module.descriptor().packages().stream())
            .collect(Collectors.toUnmodifiableSet());
    private static final DescribedPredicate<JavaClass> JDK_TYPES = DescribedPredicate.describe(
            "be a primitive or JDK type",
            type -> type.getBaseComponentType().isPrimitive()
                    || JDK_PACKAGES.contains(type.getBaseComponentType().getPackageName()));
    private static final DescribedPredicate<JavaClass> EXTERNAL_TYPES =
            resideInAnyPackage(ROOT + "..").negate();

    private static final Set<String> TEMPORAL_ACTIVITY_OPTIONS = Set.of(
            "io.temporal.activity.ActivityOptions", "io.temporal.activity.ActivityOptions$Builder",
            "io.temporal.activity.LocalActivityOptions", "io.temporal.activity.LocalActivityOptions$Builder",
            "io.temporal.activity.ActivityCancellationType", "io.temporal.common.RetryOptions", "io.temporal.common.RetryOptions$Builder");

    private static final ArchRule DOMAIN_RULE = dependenciesOf(DOMAIN,
            JDK_TYPES.or(resideInAnyPackage(DOMAIN)));
    private static final ArchRule WORKFLOW_RULE = dependenciesOf(WORKFLOW,
            resideInAnyPackage(WORKFLOW, WORKFLOW_CONTRACT, ACTIVITY_CONTRACT, DOMAIN, TEMPORAL_WORKFLOW_API)
                    .or(DescribedPredicate.describe("be a Temporal Activity option",
                            type -> TEMPORAL_ACTIVITY_OPTIONS.contains(type.getName())))
                    .or(JDK_TYPES.and(resideInAnyPackage("java.lang", "java.lang.invoke", "java.math",
                            "java.time", "java.time.temporal", "java.util", "java.util.function", "java.util.stream"))));

    private static final Set<String> NON_REPLAYABLE_METHODS = Set.of(
            System.class.getName() + ".currentTimeMillis",
            System.class.getName() + ".nanoTime",
            Instant.class.getName() + ".now",
            UUID.class.getName() + ".randomUUID");

    private static final ArchRule WORKFLOW_DIRECT_CALLS_RULE = noClasses().that().resideInAPackage(WORKFLOW)
            .should().callMethodWhere(DescribedPredicate.describe("call a wall clock or create a random UUID",
                    call -> NON_REPLAYABLE_METHODS.contains(call.getTargetOwner().getName()
                            + "." + call.getName())))
            .allowEmptyShould(true);
    private static final ArchRule WORKFLOW_LOCATION_RULE = classes().that(DescribedPredicate.describe(
                    "implement a Temporal Workflow interface",
                    type -> !type.isInterface() && type.getAllRawInterfaces().stream()
                            .anyMatch(contract -> contract.isAnnotatedWith(WorkflowInterface.class))))
            .should().resideInAPackage(WORKFLOW)
            .allowEmptyShould(true);

    @Test
    void enforcesLayerDependencies() {
        var productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);

        DOMAIN_RULE.check(productionClasses);
        WORKFLOW_RULE.check(productionClasses);
        WORKFLOW_DIRECT_CALLS_RULE.check(productionClasses);
        WORKFLOW_LOCATION_RULE.check(productionClasses);
        dependenciesOf(APPLICATION, JDK_TYPES.or(resideInAnyPackage(APPLICATION, DOMAIN)))
                .check(productionClasses);
        projectDependenciesOf(ORCHESTRATION, DOMAIN, APPLICATION).check(productionClasses);
        dependenciesOf(ACTIVITY_CONTRACT, JDK_TYPES.or(resideInAnyPackage(
                ACTIVITY_CONTRACT, DOMAIN, TEMPORAL_ACTIVITY_API))).check(productionClasses);
        dependenciesOf(WORKFLOW_CONTRACT, JDK_TYPES.or(resideInAnyPackage(
                WORKFLOW_CONTRACT, DOMAIN, TEMPORAL_WORKFLOW_API))).check(productionClasses);
        projectDependenciesOf(ORCHESTRATION_CLIENT, APPLICATION, DOMAIN, WORKFLOW_CONTRACT)
                .check(productionClasses);
        projectDependenciesOf(ROOT + ".adapter.api..", APPLICATION, WORKFLOW_CONTRACT, ORCHESTRATION_CLIENT)
                .check(productionClasses);
        projectDependenciesOf(ROOT + ".adapter.llm..", APPLICATION, DOMAIN).check(productionClasses);
        projectDependenciesOf(ROOT + ".adapter.git..", APPLICATION, DOMAIN).check(productionClasses);
        projectDependenciesOf(ROOT + ".adapter.artifacts..", APPLICATION, DOMAIN).check(productionClasses);
        projectDependenciesOf(ROOT + ".evaluation..").check(productionClasses);
    }

    @Test
    void reportsTheNonJdkDomainDependency() throws ClassNotFoundException {
        var fixture = importFixture(ROOT + ".domain.InvalidDomainDependency");

        assertThatThrownBy(() -> DOMAIN_RULE.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("InvalidDomainDependency")
                .hasMessageContaining("org.springframework.http.HttpStatus");
    }

    @Test
    void reportsTheConcreteAdapterDependencyFromAWorkflow() throws ClassNotFoundException {
        var fixture = importFixture(ROOT + ".orchestration.workflow.InvalidWorkflowDependency");

        assertThatThrownBy(() -> WORKFLOW_RULE.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("InvalidWorkflowDependency")
                .hasMessageContaining("adapter.api.ReviewController");
    }

    @Test
    void allowsActivityCancellationTypeInAWorkflow() throws ClassNotFoundException {
        WORKFLOW_RULE.check(importFixture(ROOT + ".orchestration.workflow.CancellationTypeFixture"));
    }

    @Test
    void reportsDirectClockAndRandomUuidCallsFromAWorkflow() throws ClassNotFoundException {
        var fixture = importFixture(ROOT + ".orchestration.workflow.NonReplayableCallsFixture");

        assertThatThrownBy(() -> WORKFLOW_DIRECT_CALLS_RULE.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContainingAll(NON_REPLAYABLE_METHODS.toArray(String[]::new));
    }

    @Test
    void reportsAWorkflowImplementationOutsideTheCheckedPackage() {
        var fixture = new ClassFileImporter().importClasses(MisplacedWorkflow.class, FixtureWorkflow.class);

        assertThatThrownBy(() -> WORKFLOW_LOCATION_RULE.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("MisplacedWorkflow");
    }

    @WorkflowInterface
    interface FixtureWorkflow {

        @WorkflowMethod
        void run();
    }

    static final class MisplacedWorkflow implements FixtureWorkflow {

        @Override
        public void run() {
        }
    }

    private static ArchRule projectDependenciesOf(String source, String... allowedLayers) {
        return dependenciesOf(source, EXTERNAL_TYPES.or(resideInAnyPackage(source))
                .or(resideInAnyPackage(allowedLayers)));
    }

    private static ArchRule dependenciesOf(String source, DescribedPredicate<JavaClass> allowedTargets) {
        return classes().that().resideInAPackage(source).should().onlyDependOnClassesThat(allowedTargets)
                .allowEmptyShould(true);
    }

    private static JavaClasses importFixture(String className)
            throws ClassNotFoundException {
        return new ClassFileImporter().importClasses(Class.forName(className));
    }
}
