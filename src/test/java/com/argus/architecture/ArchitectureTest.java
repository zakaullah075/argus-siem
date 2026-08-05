package com.argus.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * Conventions enforced as tests rather than documented in a wiki nobody reads.
 * <p>
 * A rule that only exists in a style guide is a rule that erodes: the next
 * person under deadline pressure will not know it exists. Encoded here, a
 * violation fails the build before review.
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.argus");
    }

    @Test
    void controllersMustNotTouchRepositoriesDirectly() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("business logic belongs in a service; a controller reaching "
                        + "past it is how transaction boundaries get lost");

        rule.check(classes);
    }

    @Test
    void repositoriesMustNotDependOnServices() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Repository")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Service")
                .because("dependencies point downward; the reverse creates cycles");

        rule.check(classes);
    }

    @Test
    void entitiesMustNotLeakThroughControllers() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().areAnnotatedWith("jakarta.persistence.Entity")
                .because("returning entities couples the public API to the database "
                        + "schema, so a column rename breaks every client");

        rule.check(classes);
    }

    @Test
    void noFieldInjection() {
        ArchRule rule = noFields()
                .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                .because("field injection hides dependencies and makes a class "
                        + "untestable without a Spring context");

        rule.check(classes);
    }

    @Test
    void servicesMustBeAnnotated() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Service")
                .and().areNotInterfaces()
                .should().beAnnotatedWith("org.springframework.stereotype.Service")
                .orShould().beAnnotatedWith("org.springframework.stereotype.Component");

        rule.check(classes);
    }

    /**
     * allowEmptyShould because the codebase currently has no non-final statics at
     * all — which is the desired state. ArchUnit fails empty rules by default to
     * catch matchers that silently match nothing, so the intent is stated here
     * rather than left ambiguous.
     */
    @Test
    void mutableStaticsMustBePrivate() {
        ArchRule rule = fields()
                .that().areStatic()
                .and().areNotFinal()
                .should().bePrivate()
                .because("mutable shared statics are a data race waiting for a "
                        + "second thread")
                .allowEmptyShould(true);

        rule.check(classes);
    }

    @Test
    void noPrintlnDebuggingLeftBehind() {
        ArchRule rule = noClasses()
                .should().callMethod(System.class, "currentTimeMillis")
                .because("time should come from Instant.now so it can be controlled "
                        + "in tests");

        rule.check(classes);
    }

    /**
     * Scoped to non-controller classes on purpose. Packaging is by feature, so a
     * feature's controller lives beside its service — banning the web layer from
     * the whole package would ban the controller from its own feature. What must
     * hold is that the detection logic itself stays callable from a consumer, a
     * scheduler or a test, not only from an HTTP request.
     */
    @Test
    void domainLogicMustNotDependOnWebLayer() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..rules..")
                .or().resideInAPackage("..alerts..")
                .and().haveSimpleNameNotEndingWith("Controller")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.web..");

        rule.check(classes);
    }

    /**
     * Every controller must sit in a feature package, never in a package that
     * groups controllers together. A `management` package holding four unrelated
     * controllers existed once: it is layer-shaped thinking inside a
     * feature-shaped codebase, and it meant changing one feature touched two
     * directories.
     */
    @Test
    void controllersLiveInsideTheirFeature() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .should().resideInAnyPackage(
                        "..controller..", "..controllers..", "..web..",
                        "..management..", "..api..")
                .because("a controller belongs beside the service it drives");

        rule.check(classes);
    }

    /**
     * A response type that imports its entity cannot be moved or reused without
     * dragging persistence along. Mapping belongs in a mapper.
     */
    @Test
    void dtosMustNotDependOnEntities() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..dto..")
                .should().dependOnClassesThat().areAnnotatedWith("jakarta.persistence.Entity")
                .because("the outward-facing type must not know the storage type");

        rule.check(classes);
    }

    @Test
    void mappersMustNotDependOnRepositoriesOrServices() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Mapper")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Service")
                .because("a mapper converts; anything more belongs in a service")
                .allowEmptyShould(true);

        rule.check(classes);
    }

    @Test
    void noCyclesBetweenFeaturePackages() {
        ArchRule rule = com.tngtech.archunit.library.dependencies.SlicesRuleDefinition
                .slices()
                .matching("com.argus.(*)..")
                .should().beFreeOfCycles()
                .because("a cycle between features means neither can be understood, "
                        + "tested or extracted independently");

        rule.check(classes);
    }
}
