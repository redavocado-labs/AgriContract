package com.agricontract.arch;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class LayerRules {

    private static final String VALUE_ANNOTATION = "org.springframework.beans.factory.annotation.Value";
    private static final Set<String> ALLOWED_SPRING_STEREOTYPES = Set.of(
            "org.springframework.stereotype.Service", "org.springframework.stereotype.Component");
    private static final DescribedPredicate<JavaClass> SPRING_OR_TRANSACTION =
            resideInAnyPackage("org.springframework..", "jakarta.transaction..", "javax.transaction..");

    private LayerRules() {}

    public static ArchRule forService(String rootPackage, Class<?>... knownValueUsers) {
        String domain = rootPackage + ".domain..";
        String application = rootPackage + ".application..";
        String infrastructure = rootPackage + ".infrastructure..";
        Set<String> allowedValueUsers = Arrays.stream(knownValueUsers).map(Class::getName).collect(Collectors.toSet());

        return CompositeArchRule.of(noClasses().that().resideInAPackage(domain)
                        .should().dependOnClassesThat().resideInAnyPackage(application, infrastructure))
                .and(noClasses().that().resideInAPackage(application)
                        .should().dependOnClassesThat().resideInAPackage(infrastructure))
                .and(noClasses().that().resideInAPackage(domain)
                        .should().dependOnClassesThat(SPRING_OR_TRANSACTION))
                .and(classes().that().resideInAPackage(application)
                        .should(useSpringStereotypesOnly(allowedValueUsers)));
    }

    public static ArchRule sharedKernelMustNotDependOnInfrastructure(String rootPackage, String kernelPackage) {
        return noClasses().that().resideInAPackage(rootPackage + "." + kernelPackage + "..")
                .should().dependOnClassesThat().resideInAPackage(rootPackage + ".infrastructure..");
    }

    public static ArchRule noPackageCycles(String rootPackage) {
        return SlicesRuleDefinition.slices().matching(rootPackage + ".(*)..").should().beFreeOfCycles();
    }

    private static ArchCondition<JavaClass> useSpringStereotypesOnly(Set<String> allowedValueUsers) {
        return new ArchCondition<>("use only @Service/@Component (D102) and leave transactions to infrastructure (D44)") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                for (var dependency : source.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    boolean stereotype = ALLOWED_SPRING_STEREOTYPES.contains(target.getName());
                    boolean existingValueUsage = target.getName().equals(VALUE_ANNOTATION)
                            && allowedValueUsers.contains(source.getName());
                    if (SPRING_OR_TRANSACTION.test(target) && !stereotype && !existingValueUsage) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }
}
