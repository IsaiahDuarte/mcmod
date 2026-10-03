package dev.izzy.factorycore;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  private static final ArchRule OPTIONAL_BOUNDARY =
      noClasses()
          .that()
          .resideOutsideOfPackage("..integration..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("mezz.jei..", "mekanism..", "..integration..")
          .allowEmptyShould(false);

  @Test
  void mandatoryClassesDoNotLoadOptionalIntegrations() {
    var classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.izzy.factorycore");
    assertFalse(classes.isEmpty(), "Architecture verification must inspect production bytecode");
    OPTIONAL_BOUNDARY.check(classes);
    noClasses()
        .that()
        .resideInAPackage("..core..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("net.minecraft..", "net.neoforged..", "..platform..")
        .allowEmptyShould(false)
        .check(classes);
  }

  @Test
  void optionalBoundaryDetectsAnIllegalDependency() {
    var fixture = new ClassFileImporter().importClasses(IllegalIntegration.class);
    assertTrue(OPTIONAL_BOUNDARY.evaluate(fixture).hasViolation());
  }

  private static final class IllegalIntegration {
    dev.izzy.factorycore.integration.FakeOptionalApi api;
  }
}
