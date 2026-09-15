package io.github.jf3env.architecture.bytecode;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
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
import io.github.jf3env.architecture.ContextShape;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The context-first catalog, constructed anew for each consumer and its derived bounded contexts.
 *
 * <p>Every rule checks a property of the dependency graph or of visibility over the shape {@code
 * <base>.<context>.{api,domain,application,infrastructure}} plus the shared platform. The
 * composition root {@code infrastructure.wiring} is the one place allowed to reference a context's
 * whole graph, so the transaction and JPA ownership rules exempt it.
 */
public final class BytecodeRuleCatalog {
  private static final String ENTITY = "jakarta.persistence.Entity";
  private static final String MAPPER = "org.mapstruct.Mapper";
  private static final String PRODUCES = "jakarta.enterprise.inject.Produces";
  private static final String TRANSACTIONAL = "jakarta.transaction.Transactional";
  private final BytecodePolicy policy;
  private final ContextShape shape;
  private final List<String> contexts;

  public BytecodeRuleCatalog(BytecodePolicy policy, List<String> contexts) {
    this.policy = policy;
    this.shape = policy.shape();
    this.contexts = List.copyOf(contexts);
  }

  public Map<String, ArchRule> rules() {
    var rules = new RuleInventory();
    var base = policy.basePackage();
    var platform = shape.platform() + "..";
    var api = base + ".*.api..";
    var domain = base + ".*.domain..";
    var application = base + ".*.application..";
    var infrastructure = base + ".*.infrastructure..";
    var rest = base + ".*.infrastructure.inbound.rest..";
    var inbound = base + ".*.infrastructure.inbound..";
    var outbound = base + ".*.infrastructure.outbound..";
    var persistence = base + ".*.infrastructure.outbound.persistence..";
    var entities = base + ".*.infrastructure.outbound.persistence..entities..";
    var wiring = base + ".*.infrastructure.wiring..";
    var marker = policy.aggregateRootAnnotation();
    rules.add(
        "CLASSES_RESIDE_IN_CONTEXT_SHAPE",
        classes()
            .that(not(bootstrap()).and(not(packageInfo())))
            .should()
            .resideInAnyPackage(platform, api, domain, application, infrastructure));
    rules.add(
        "CONTEXTS_ONLY_TALK_THROUGH_API",
        slices()
            .matching(base + ".(*)..")
            .should()
            .notDependOnEachOther()
            .ignoreDependency(resideInAPackage(platform), alwaysTrue())
            .ignoreDependency(alwaysTrue(), resideInAPackage(api))
            .ignoreDependency(alwaysTrue(), resideInAPackage(platform)));
    rules.add(
        "CONTEXTS_ARE_FREE_OF_CYCLES",
        slices().matching(base + ".(*)..").should().beFreeOfCycles());
    rules.add(
        "LAYERS_POINT_INWARD",
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("api")
            .definedBy(contextLayer(api, platform))
            .layer("domain")
            .definedBy(contextLayer(domain, platform))
            .layer("application")
            .definedBy(contextLayer(application, platform))
            .layer("infrastructure")
            .definedBy(contextLayer(infrastructure, platform))
            .whereLayer("infrastructure")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("application")
            .mayOnlyBeAccessedByLayers("infrastructure")
            .whereLayer("domain")
            .mayOnlyBeAccessedByLayers("application", "infrastructure"));
    rules.add(
        "DOMAIN_IS_FRAMEWORK_FREE",
        noClasses()
            .that()
            .resideInAPackage(domain)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(policy.frameworkPackages().toArray(String[]::new)));
    rules.add(
        "API_IS_A_PUBLISHED_LANGUAGE",
        classes()
            .that()
            .resideInAPackage(api)
            .and(not(packageInfo()))
            .should()
            .beRecords()
            .orShould()
            .beInterfaces()
            .orShould()
            .beEnums()
            .orShould()
            .beAssignableTo(RuntimeException.class)
            .andShould()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "org.jspecify..", api, shape.platform() + ".domain.."));
    rules.add(
        "INTEGRATION_EVENTS_ARE_PUBLIC_RECORDS",
        classes()
            .that()
            .implement(policy.integrationEventType())
            .should()
            .beRecords()
            .andShould()
            .bePublic()
            .andShould()
            .resideInAPackage(base + ".*.api.events.."));
    rules.add(
        "PLATFORM_DEPENDS_ON_NO_CONTEXT",
        noClasses()
            .that()
            .resideInAPackage(platform)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                contexts.stream()
                    .map(context -> base + "." + context + "..")
                    .toArray(String[]::new)));
    rules.add(
        "TRANSACTIONS_BELONG_TO_APPLICATION",
        classes()
            .that(dependOn(assignableTo(policy.unitOfWorkType()).or(name(TRANSACTIONAL))))
            .and()
            .resideOutsideOfPackage(platform)
            .and()
            .resideOutsideOfPackage(wiring)
            .should()
            .resideInAPackage(application));
    rules.add(
        "PERSISTENCE_IS_THE_ONLY_JPA_USER",
        classes()
            .that(dependOn(resideInAnyPackage("jakarta.persistence..")))
            .and()
            .resideOutsideOfPackage(wiring)
            .should()
            .resideInAPackage(persistence));
    rules.add(
        "REST_TALKS_ONLY_TO_APPLICATION",
        classes()
            .that()
            .resideInAPackage(rest)
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                "java..",
                "jakarta.ws.rs..",
                "jakarta.inject..",
                "jakarta.enterprise..",
                rest,
                application,
                api,
                platform,
                "org.mapstruct..",
                "lombok..",
                "org.jspecify.."));
    rules.add(
        "OUTBOUND_DOES_NOT_DEPEND_ON_APPLICATION",
        noClasses()
            .that()
            .resideInAPackage(outbound)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(application));
    rules.add(
        "HANDLERS_DO_NOT_RETURN_AGGREGATES",
        methods()
            .that()
            .arePublic()
            .and()
            .areDeclaredInClassesThat()
            .resideInAPackage(application)
            .should(notExposeAggregateRoots(marker)));
    rules.add(
        "ONE_PRODUCER_PER_CONTEXT",
        classes().that().resideInAPackage(base + "..").should(declareOneProducerPerContext()));
    rules.add(
        "DOMAIN_REPOSITORIES_ARE_INTERFACES",
        classes()
            .that()
            .resideInAPackage(domain)
            .and()
            .haveSimpleNameEndingWith("Repository")
            .should()
            .beInterfaces());
    rules.add(
        "DOMAIN_PACKAGES_ARE_NULL_MARKED",
        classes().that().resideInAPackage(domain).should(resideInNullMarkedPackage()));
    rules.add(
        "AGGREGATE_ROOTS_HAVE_PRIVATE_STATE",
        fields()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(marker)
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
            .areAnnotatedWith(marker)
            .and()
            .haveNameMatching("set[A-Z].*")
            .should()
            .notBePublic());
    rules.add(
        "DOMAIN_STATE_IS_PRIVATE",
        fields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage(domain)
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
            .resideInAPackage(domain)
            .and()
            .areDeclaredInClassesThat()
            .areNotAnnotatedWith(marker)
            .and()
            .areDeclaredInClassesThat()
            .haveSimpleNameNotEndingWith("Builder")
            .and()
            .doNotHaveModifier(JavaModifier.STATIC)
            .should()
            .beFinal());
    rules.add(
        "TRANSFER_OBJECT_PACKAGES_CONTAIN_ONLY_TRANSFER_OBJECTS",
        classes()
            .that()
            .resideInAPackage("..dto..")
            .and(not(packageInfo()))
            .should()
            .haveSimpleNameEndingWith("Dto"));
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
            .resideInAPackage(entities));
    rules.add(
        "ENTITY_PACKAGES_CONTAIN_ONLY_ENTITIES",
        classes()
            .that()
            .resideInAPackage(entities)
            .and(not(packageInfo()))
            .should()
            .haveSimpleNameEndingWith("Entity"));
    rules.add(
        "REST_TRANSFER_OBJECTS_DO_NOT_LEAK_INNER_LAYERS",
        noClasses()
            .that()
            .resideInAPackage(rest)
            .and()
            .resideInAPackage("..dto..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(domain, outbound));
    rules.add(
        "ENTITY_REPRESENTATIONS_DO_NOT_LEAK_INNER_LAYERS",
        noClasses()
            .that()
            .resideInAPackage(entities)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(domain, application, api, inbound));
    rules.add(
        "MAPPERS_USE_MAPSTRUCT",
        classes()
            .that()
            .areInterfaces()
            .and()
            .resideInAPackage("..mappers..")
            .and(not(packageInfo()))
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
        "MAPSTRUCT_MAPPER_METHODS_ARE_ABSTRACT",
        methods()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(MAPPER)
            .should()
            .haveModifier(JavaModifier.ABSTRACT));
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
    return rules.require(new BytecodeContracts().requiredRules());
  }

  private DescribedPredicate<JavaClass> bootstrap() {
    var bootstrap = policy.basePackage() + "." + ContextShape.BOOTSTRAP_TYPE;
    return new DescribedPredicate<>("the bootstrap type " + bootstrap) {
      @Override
      public boolean test(JavaClass type) {
        return type.getName().equals(bootstrap) || type.getName().startsWith(bootstrap + "$");
      }
    };
  }

  private static DescribedPredicate<JavaClass> dependOn(
      DescribedPredicate<? super JavaClass> target) {
    return new DescribedPredicate<>("depend on classes that " + target.getDescription()) {
      @Override
      public boolean test(JavaClass type) {
        return type.getDirectDependenciesFromSelf().stream()
            .map(dependency -> dependency.getTargetClass())
            .anyMatch(target::test);
      }
    };
  }

  /** The platform is a shared kernel every layer may use; it belongs to no context layer. */
  private static DescribedPredicate<JavaClass> contextLayer(String layer, String platform) {
    return resideInAPackage(layer)
        .and(not(resideInAPackage(platform)))
        .as("reside in a package '" + layer + "' outside the platform");
  }

  private static DescribedPredicate<JavaClass> packageInfo() {
    return simpleName(ContextShape.PACKAGE_INFO);
  }

  private ArchCondition<JavaMethod> notExposeAggregateRoots(String marker) {
    return new ArchCondition<>("not expose an aggregate root annotated with " + marker) {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        var exposed =
            method.getReturnType().getAllInvolvedRawTypes().stream()
                .filter(type -> type.isAnnotatedWith(marker))
                .map(JavaClass::getName)
                .sorted()
                .toList();
        events.add(
            new SimpleConditionEvent(
                method,
                exposed.isEmpty(),
                method.getDescription() + " exposes the aggregate root " + exposed));
      }
    };
  }

  /**
   * Every context declares exactly one producer, and every producer lives in its wiring package.
   */
  private ArchCondition<JavaClass> declareOneProducerPerContext() {
    return new ArchCondition<>(
        "declare exactly one CDI producer per context, in infrastructure.wiring") {
      private final Map<String, List<String>> producers = new TreeMap<>();

      @Override
      public void init(Collection<JavaClass> all) {
        producers.clear();
        contexts.forEach(context -> producers.put(context, new ArrayList<>()));
        for (var type : all) {
          if (!hasProducedBeans(type)) continue;
          shape
              .segmentOf(type.getPackageName())
              .filter(producers::containsKey)
              .ifPresent(context -> producers.get(context).add(type.getName()));
        }
      }

      @Override
      public void check(JavaClass type, ConditionEvents events) {
        if (!hasProducedBeans(type)) return;
        if (!type.isTopLevelClass()) {
          events.add(
              SimpleConditionEvent.violated(
                  type, type.getName() + ": a producer must be a top-level class"));
        }
        if (!shape.isWiringPackage(type.getPackageName())) {
          events.add(
              SimpleConditionEvent.violated(
                  type,
                  type.getName()
                      + ": classes declaring @Produces members reside in <context>.infrastructure.wiring"));
        }
      }

      @Override
      public void finish(ConditionEvents events) {
        producers.forEach(
            (context, found) -> {
              if (found.size() != 1) {
                events.add(
                    SimpleConditionEvent.violated(
                        context,
                        "context "
                            + context
                            + " declares "
                            + found.size()
                            + " producers; exactly one class with @Produces members is required in "
                            + policy.basePackage()
                            + "."
                            + context
                            + ".infrastructure.wiring: "
                            + found));
              }
            });
      }
    };
  }

  private static boolean hasProducedBeans(JavaClass type) {
    return type.getMethods().stream().anyMatch(method -> method.isAnnotatedWith(PRODUCES))
        || type.getFields().stream().anyMatch(field -> field.isAnnotatedWith(PRODUCES));
  }

  private DescribedPredicate<JavaClass> boundaryCarrier() {
    var prefix = policy.basePackage() + ".";
    return new DescribedPredicate<>("a JPA entity or REST DTO") {
      @Override
      public boolean test(JavaClass type) {
        var packageName = type.getPackageName();
        return type.isAnnotatedWith(ENTITY)
            || packageName.startsWith(prefix)
                && packageName.contains(".infrastructure.inbound.")
                && packageName.contains(".dto");
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
}
