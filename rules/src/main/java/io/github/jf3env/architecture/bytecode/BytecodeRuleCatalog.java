package io.github.jf3env.architecture.bytecode;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Map;

/** The original 43-rule catalog, constructed anew for each consumer and evaluation. */
public final class BytecodeRuleCatalog {
  private static final String ENTITY = "jakarta.persistence.Entity";
  private static final String MAPPER = "org.mapstruct.Mapper";
  private final BytecodePolicy policy;
  private final DomainPackageConvention convention;

  public BytecodeRuleCatalog(BytecodePolicy policy) {
    this.policy = policy;
    convention = new DomainPackageConvention(policy.basePackage());
  }

  public Map<String, ArchRule> rules() {
    var rules = new RuleInventory();
    var base = policy.basePackage();
    rules.add(
        "LAYER_COMMUNICATION",
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Infrastructure")
            .definedBy("..infra..")
            .layer("Persistence")
            .definedBy("..persistence..")
            .layer("Domain")
            .definedBy("..domain..")
            .ensureAllClassesAreContainedInArchitecture()
            .whereLayer("Infrastructure")
            .mayOnlyAccessLayers("Domain")
            .whereLayer("Persistence")
            .mayOnlyAccessLayers("Domain")
            .whereLayer("Domain")
            .mayNotAccessAnyLayer());
    rules.add(
        "CLASSES_ARE_GROUPED_BY_LAYER_AND_DOMAIN",
        classes()
            .should()
            .resideInAnyPackage(
                base + ".domain.*..", base + ".persistence.*..", base + ".infra.*.."));
    rules.add(
        "APPLICATION_LAYER_IS_ABSENT", noClasses().should().resideInAPackage("..application.."));
    rules.add(
        "DOMAINS_ARE_FREE_OF_CYCLES",
        slices().matching(base + ".domain.(*)..").should().beFreeOfCycles());
    rules.add(
        "SUBPACKAGES_DO_NOT_ACCESS_ANCESTOR_PACKAGES",
        classes().that().resideInAPackage(base + "..").should(notAccessAncestorPackages()));
    rules.add(
        "DOMAIN_ROOT_CLASSES_ARE_INTERFACES",
        classes()
            .that()
            .resideInAPackage(base + ".domain.*")
            .and()
            .areTopLevelClasses()
            .and()
            .doNotHaveSimpleName("package-info")
            .should()
            .beInterfaces());
    rules.add(
        "DOMAIN_SERVICES_RESIDE_IN_OWN_SERVICE_PACKAGES",
        classes()
            .that()
            .resideInAPackage(base + ".domain..")
            .and()
            .haveSimpleNameEndingWith("Service")
            .should(serviceLocation()));
    rules.add(
        "SERVICE_CAPABILITIES_DECLARE_TYPE_ROLES",
        classes()
            .that()
            .resideInAPackage(base + ".domain.*.services..")
            .and()
            .areTopLevelClasses()
            .and()
            .doNotHaveSimpleName("package-info")
            .should(serviceTypeRole()));
    rules.add(
        "DOMAIN_COMPONENTS_FOLLOW_STRUCTURAL_OWNERS",
        classes().should(new StructuralOwnershipCondition(base)));
    rules.add(
        "DOMAIN_INTERFACES_RESIDE_AT_THE_DOMAIN_ROOT",
        classes()
            .that()
            .resideInAPackage(base + ".domain..")
            .and()
            .doNotHaveSimpleName("package-info")
            .and()
            .areInterfaces()
            .and()
            .areTopLevelClasses()
            .should()
            .resideInAnyPackage(base + ".domain.*", base + ".domain.*.services..factory"));
    rules.add(
        "DOMAIN_REPOSITORIES_ARE_INTERFACES",
        classes()
            .that()
            .resideInAPackage("..domain..")
            .and()
            .haveSimpleNameEndingWith("Repository")
            .should()
            .beInterfaces());
    rules.add(
        "ASSET_DOMAIN_PACKAGES_ARE_WORKSPACE_OWNED",
        noClasses()
            .should()
            .resideInAPackage("..domain." + policy.domain().forbiddenDomain() + ".."));
    rules.add(
        "ASSET_CHANGING_SERVICES_USE_WORKSPACE_AUTHORITY",
        classes()
            .that(authorityService())
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName(policy.domain().aggregate())
            .andShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName(policy.domain().repository()));
    rules.add(
        "WORKSPACE_ASSET_SERVICES_HAVE_NO_ASSET_PERSISTENCE_REPOSITORY",
        noClasses()
            .that()
            .resideInAPackage(base + ".domain." + policy.domain().authorityDomain() + ".services.*")
            .and()
            .haveSimpleNameEndingWith("Service")
            .should()
            .dependOnClassesThat()
            .haveSimpleName(policy.domain().forbiddenRepository()));
    rules.add("REST_DOES_NOT_BYPASS_DOMAIN_SERVICES", restDoesNotBypassDomainServices());
    rules.add(
        "REST_DOES_NOT_ACCESS_ROOT_REPOSITORIES",
        noClasses()
            .that()
            .resideInAPackage("..infra..rest..")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository"));
    rules.add(
        "DOMAIN_SERVICES_ARE_CONSTRUCTED_BY_INFRASTRUCTURE",
        constructors()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("..domain..")
            .and()
            .areDeclaredInClassesThat()
            .haveSimpleNameEndingWith("Service")
            .should()
            .onlyBeCalled()
            .byClassesThat()
            .resideInAPackage("..infra.."));
    rules.add(
        "PERSISTENCE_DOES_NOT_DEPEND_ON_DOMAIN_SERVICE_GATES",
        noClasses()
            .that()
            .resideInAPackage("..persistence..")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Service"));
    rules.add(
        "TRANSACTION_ANNOTATIONS_BELONG_TO_PERSISTENCE",
        noClasses()
            .that()
            .resideOutsideOfPackage("..persistence..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("jakarta.transaction.Transactional"));
    rules.add(
        "DOMAIN_IS_FRAMEWORK_FREE",
        classes()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "lombok..", "org.jspecify..", "..domain.."));
    rules.add(
        "DOMAIN_PACKAGES_ARE_NULL_MARKED",
        classes().that().resideInAPackage("..domain..").should(resideInNullMarkedPackage()));
    rules.add(
        "DOMAIN_TYPES_ARE_NOT_RECORDS",
        noClasses().that().resideInAPackage("..domain..").should().beRecords());
    rules.add(
        "DOMAIN_TYPES_DECLARE_THEIR_ROLE",
        classes()
            .that()
            .resideInAPackage("..domain..")
            .and()
            .areNotInterfaces()
            .and()
            .areNotAssignableTo(Throwable.class)
            .should()
            .resideInAnyPackage(
                "..domain.*.aggregate..",
                "..domain.*.entities..",
                "..domain.*.projection..",
                "..domain.*.repository..",
                "..domain.*.services..",
                "..domain.*.value..",
                "..domain.*"));
    rules.add(
        "DOMAIN_EXCEPTIONS_LIVE_IN_EXCEPTIONS_PACKAGES",
        classes()
            .that()
            .resideInAPackage("..domain..")
            .and()
            .areAssignableTo(Throwable.class)
            .should()
            .resideInAPackage("..exceptions.."));
    rules.add(
        "AGGREGATE_ROOTS_HAVE_PRIVATE_STATE",
        fields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("..domain.*.aggregate..")
            .and()
            .doNotHaveModifier(JavaModifier.STATIC)
            .and()
            .doNotHaveModifier(JavaModifier.SYNTHETIC)
            .should()
            .bePrivate());
    rules.add(
        "AGGREGATE_ROOTS_HAVE_NO_PUBLIC_SETTERS",
        methods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("..domain.*.aggregate..")
            .and()
            .haveNameMatching("set[A-Z].*")
            .should()
            .notBePublic());
    rules.add(
        "DOMAIN_STATE_IS_PRIVATE",
        fields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("..domain..")
            .and()
            .areDeclaredInClassesThat()
            .haveSimpleNameNotEndingWith("Builder")
            .and()
            .doNotHaveModifier(JavaModifier.STATIC)
            .and()
            .doNotHaveModifier(JavaModifier.SYNTHETIC)
            .should()
            .bePrivate());
    rules.add(
        "ONLY_AGGREGATES_REASSIGN_DOMAIN_STATE",
        fields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("..domain..")
            .and()
            .areDeclaredInClassesThat()
            .resideOutsideOfPackage("..domain.*.aggregate..")
            .and()
            .areDeclaredInClassesThat()
            .haveSimpleNameNotEndingWith("Builder")
            .and()
            .doNotHaveModifier(JavaModifier.STATIC)
            .should()
            .beFinal());
    rules.add(
        "DOMAIN_SERVICES_DO_NOT_EXPOSE_AGGREGATES",
        noMethods()
            .that()
            .arePublic()
            .and()
            .areDeclaredInClassesThat()
            .resideInAPackage("..domain..")
            .and()
            .areDeclaredInClassesThat()
            .haveSimpleNameEndingWith("Service")
            .should(exposeAggregateRoot()));
    rules.add(
        "TRANSFER_OBJECT_PACKAGES_CONTAIN_ONLY_TRANSFER_OBJECTS",
        classes().that().resideInAPackage("..dto..").should().haveSimpleNameEndingWith("Dto"));
    rules.add(
        "TRANSFER_OBJECTS_BELONG_TO_DTO_PACKAGES",
        classes().that().haveSimpleNameEndingWith("Dto").should().resideInAPackage("..dto.."));
    rules.add(
        "JPA_ENTITIES_FOLLOW_ENTITY_CONVENTIONS",
        classes()
            .that()
            .areAnnotatedWith(ENTITY)
            .should()
            .haveSimpleNameEndingWith("Entity")
            .andShould()
            .resideInAPackage("..persistence..entities.."));
    rules.add(
        "ENTITY_PACKAGES_CONTAIN_ONLY_ENTITIES",
        classes()
            .that()
            .resideInAPackage("..persistence..entities..")
            .should()
            .haveSimpleNameEndingWith("Entity"));
    rules.add(
        "REST_TRANSFER_OBJECTS_DO_NOT_LEAK_INNER_LAYERS",
        noClasses()
            .that()
            .resideInAPackage("..infra..dto..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..domain..", "..persistence.."));
    rules.add(
        "ENTITY_REPRESENTATIONS_DO_NOT_LEAK_INNER_LAYERS",
        noClasses()
            .that()
            .resideInAPackage("..persistence..entities..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..domain..", "..infra.."));
    rules.add(
        "MAPPERS_USE_MAPSTRUCT",
        classes()
            .that()
            .areInterfaces()
            .and()
            .resideInAPackage("..mappers..")
            .should()
            .beAnnotatedWith(MAPPER)
            .andShould()
            .haveSimpleNameEndingWith("Mapper"));
    rules.add(
        "MAPSTRUCT_MAPPERS_HAVE_A_MAPPER_PACKAGE",
        classes().that().areAnnotatedWith(MAPPER).should().resideInAPackage("..mappers.."));
    rules.add(
        "MAPSTRUCT_MAPPERS_ARE_INTERFACES",
        classes().that().areAnnotatedWith(MAPPER).should().beInterfaces());
    rules.add(
        "MAPSTRUCT_MAPPERS_HAVE_GENERATED_IMPLEMENTATIONS",
        classes().that().areAnnotatedWith(MAPPER).should(haveGeneratedMapStructImplementation()));
    rules.add(
        "BOUNDARY_CARRIERS_ARE_ONLY_CONSTRUCTED_BY_MAPSTRUCT",
        constructors()
            .that()
            .areDeclaredInClassesThat(boundaryCarrier())
            .should()
            .onlyBeCalled()
            .byClassesThat(mapstructGeneratedMapperImpl()));
    rules.add(
        "BOUNDARY_CARRIER_SETTERS_ARE_ONLY_CALLED_BY_MAPSTRUCT",
        methods()
            .that()
            .areDeclaredInClassesThat(boundaryCarrier())
            .and()
            .haveNameMatching("set[A-Z].*")
            .should()
            .onlyBeCalled()
            .byClassesThat(mapstructGeneratedMapperImpl()));
    rules.add(
        "BOUNDARY_CARRIER_STATE_IS_PRIVATE",
        fields()
            .that()
            .areDeclaredInClassesThat(boundaryCarrier())
            .and()
            .doNotHaveModifier(JavaModifier.STATIC)
            .should()
            .bePrivate());
    rules.add(
        "MAPSTRUCT_MAPPER_METHODS_ARE_ABSTRACT",
        methods()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(MAPPER)
            .should()
            .haveModifier(JavaModifier.ABSTRACT));
    return rules.require(new BytecodeContracts().requiredRules());
  }

  private DescribedPredicate<JavaClass> authorityService() {
    return new DescribedPredicate<>("an explicitly configured authority service") {
      @Override
      public boolean test(JavaClass type) {
        return policy.domain().services().contains(type.getSimpleName());
      }
    };
  }

  private ArchCondition<JavaClass> serviceLocation() {
    return new ArchCondition<>("reside in domain.<area>.services.<service-name>") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        events.add(
            new SimpleConditionEvent(
                type,
                convention.isServiceLocation(type.getPackageName()),
                type.getName() + " must reside in domain.<area>.services.<service-name>"));
      }
    };
  }

  private ArchCondition<JavaClass> serviceTypeRole() {
    return new ArchCondition<>("use a capability role package matching the type suffix") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        convention
            .serviceTypeViolation(
                type.getPackageName(), type.getSimpleName(), type.isAssignableTo(Exception.class))
            .ifPresent(
                message ->
                    events.add(
                        SimpleConditionEvent.violated(type, type.getName() + ": " + message)));
      }
    };
  }

  private DescribedPredicate<JavaClass> boundaryCarrier() {
    return new DescribedPredicate<>("a JPA entity or REST DTO") {
      @Override
      public boolean test(JavaClass type) {
        return type.isAnnotatedWith(ENTITY)
            || type.getPackageName().startsWith(policy.basePackage() + ".infra")
                && type.getPackageName().contains(".dto");
      }
    };
  }

  private DescribedPredicate<JavaClass> mapstructGeneratedMapperImpl() {
    return new DescribedPredicate<>("a generated MapStruct mapper implementation") {
      @Override
      public boolean test(JavaClass type) {
        return type.getRawInterfaces().stream()
            .filter(mapper -> mapper.isAnnotatedWith(MAPPER))
            .anyMatch(mapper -> type.getName().equals(mapper.getName() + "Impl"));
      }
    };
  }

  private ArchCondition<JavaClass> haveGeneratedMapStructImplementation() {
    return new ArchCondition<>("have a generated MapStruct implementation") {
      @Override
      public void check(JavaClass mapper, ConditionEvents events) {
        var implementation = mapper.getName() + "Impl";
        var generated =
            mapper.getAllSubclasses().stream()
                .anyMatch(type -> type.getName().equals(implementation));
        events.add(
            new SimpleConditionEvent(
                mapper,
                generated,
                mapper.getDescription() + " has no generated implementation " + implementation));
      }
    };
  }

  private ArchCondition<JavaClass> notAccessAncestorPackages() {
    return new ArchCondition<>("not access classes from ancestor packages") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        var origin = type.getPackageName();
        type.getDirectDependenciesFromSelf().stream()
            .filter(
                dependency -> {
                  var target = dependency.getTargetClass().getPackageName();
                  return !target.isEmpty()
                      && origin.startsWith(target + ".")
                      && !convention.isOwnRootContractAccess(
                          origin,
                          type.getSimpleName(),
                          target,
                          dependency.getTargetClass().isInterface());
                })
            .forEach(
                dependency ->
                    events.add(
                        SimpleConditionEvent.violated(
                            type,
                            type.getDescription()
                                + " accesses ancestor package through "
                                + dependency.getDescription())));
      }
    };
  }

  private ArchCondition<JavaClass> resideInNullMarkedPackage() {
    return new ArchCondition<>("reside in a package annotated with @NullMarked") {
      @Override
      public void check(JavaClass type, ConditionEvents events) {
        events.add(
            new SimpleConditionEvent(
                type,
                type.getPackage().isAnnotatedWith("org.jspecify.annotations.NullMarked"),
                type.getDescription() + " resides in an unmarked package"));
      }
    };
  }

  private ArchCondition<JavaMethod> exposeAggregateRoot() {
    return new ArchCondition<>("expose an aggregate root") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        var exposes =
            method.getReturnType().getAllInvolvedRawTypes().stream()
                .anyMatch(type -> type.getPackageName().contains(".aggregate"));
        events.add(
            new SimpleConditionEvent(
                method, exposes, method.getDescription() + " exposes an aggregate root"));
      }
    };
  }

  private ArchRule restDoesNotBypassDomainServices() {
    var exceptions = JavaClass.Predicates.resideOutsideOfPackage("..domain..exceptions..");
    var inner =
        JavaClass.Predicates.resideInAnyPackage(
                "..domain.*.aggregate..",
                "..domain.*.repository..",
                "..domain.*.gateway..",
                "..domain.*.index..",
                "..domain.*.projection..",
                "..domain.*.entities..",
                "..domain.*.value..")
            .and(exceptions);
    return noClasses()
        .that()
        .resideInAPackage("..infra..rest..")
        .should()
        .dependOnClassesThat(inner);
  }
}
