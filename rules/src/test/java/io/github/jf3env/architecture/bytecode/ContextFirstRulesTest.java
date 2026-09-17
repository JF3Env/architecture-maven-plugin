package io.github.jf3env.architecture.bytecode;

import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.accepts;
import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.compile;
import static io.github.jf3env.architecture.bytecode.CompiledArchitectureFixture.rejects;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Every context-first identity has a compiled positive control and a compiled counterexample. Each
 * fixture evaluates its named contract, not the conjunction of every production rule.
 */
class ContextFirstRulesTest {
  private static final String BASE = "com.acme";
  private static final String PLATFORM = BASE + ".platform";
  private static final String AGGREGATE_ROOT = PLATFORM + ".domain.AggregateRoot";
  private static final String INTEGRATION_EVENT = PLATFORM + ".domain.IntegrationEvent";
  private static final String UNIT_OF_WORK = PLATFORM + ".application.UnitOfWork";
  private static final BytecodePolicy POLICY = BytecodePolicy.of(BASE);
  private static final Map<String, String> PLATFORM_TYPES =
      Map.of(
          AGGREGATE_ROOT,
          "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
              + " public @interface AggregateRoot {}",
          INTEGRATION_EVENT,
          "public interface IntegrationEvent {}",
          UNIT_OF_WORK,
          "public interface UnitOfWork { void begin(); void commit(); }");
  private static final String ORDER = BASE + ".orders.domain.Order";
  private static final String ORDER_REF = BASE + ".orders.api.OrderRef";
  private static final String PLACE_ORDER = BASE + ".orders.application.PlaceOrderHandler";

  @TempDir Path temporary;

  @Test
  void classesResideInTheContextShapeOrAreTheBootstrap() throws IOException {
    var valid =
        fixture(
            Map.of(
                BASE + ".Application",
                "public final class Application { private Application() {} }",
                PLATFORM + ".domain.Money",
                "public record Money(long cents) {}",
                ORDER_REF,
                "public record OrderRef(long id) {}",
                ORDER,
                "public class Order {}",
                PLACE_ORDER,
                "public class PlaceOrderHandler {}",
                BASE + ".orders.infrastructure.wiring.OrdersProducer",
                "public class OrdersProducer {}"));
    accepts(rule(valid, "CLASSES_RESIDE_IN_CONTEXT_SHAPE"), valid);
    var stray = BASE + ".orders.Stray";
    var legacy = BASE + ".orders.services.OrderService";
    var other = BASE + ".Other";
    var invalid =
        fixture(
            Map.of(
                stray, "public class Stray {}",
                legacy, "public class OrderService {}",
                other, "public class Other {}"));
    rejects(rule(invalid, "CLASSES_RESIDE_IN_CONTEXT_SHAPE"), invalid, stray, legacy, other);
  }

  @Test
  void contextsOnlyTalkThroughTheApiOrThePlatform() throws IOException {
    var invoicing = BASE + ".billing.application.InvoicingHandler";
    var valid =
        fixture(
            Map.of(
                PLATFORM + ".domain.Money",
                "public record Money(long cents) {}",
                ORDER_REF,
                "public record OrderRef(long id) {}",
                ORDER,
                "public class Order {}",
                invoicing,
                "public class InvoicingHandler { "
                    + ORDER_REF
                    + " order; "
                    + PLATFORM
                    + ".domain.Money money; }"));
    accepts(rule(valid, "CONTEXTS_ONLY_TALK_THROUGH_API"), valid);
    var invalid =
        fixture(
            Map.of(
                ORDER,
                "public class Order {}",
                invoicing,
                "public class InvoicingHandler { " + ORDER + " order; }"));
    rejects(rule(invalid, "CONTEXTS_ONLY_TALK_THROUGH_API"), invalid, invoicing, ORDER);
  }

  @Test
  void contextsAreFreeOfCyclesEvenThroughTheirApis() throws IOException {
    var invoiceRef = BASE + ".billing.api.InvoiceRef";
    var valid =
        fixture(
            Map.of(
                ORDER_REF,
                "public record OrderRef(long id) {}",
                invoiceRef,
                "public record InvoiceRef(" + ORDER_REF + " order) {}"));
    accepts(rule(valid, "CONTEXTS_ARE_FREE_OF_CYCLES"), valid);
    var invalid =
        fixture(
            Map.of(
                ORDER_REF, "public record OrderRef(" + invoiceRef + " invoice) {}",
                invoiceRef, "public record InvoiceRef(" + ORDER_REF + " order) {}"));
    rejects(rule(invalid, "CONTEXTS_ARE_FREE_OF_CYCLES"), invalid, "orders", "billing");
  }

  @ParameterizedTest
  @CsvSource({
    "application, domain, true",
    "infrastructure, domain, true",
    "infrastructure, application, true",
    "domain, api, true",
    "api, platform, true",
    "api, domain, false",
    "domain, application, false",
    "domain, infrastructure, false",
    "application, infrastructure, false"
  })
  void layersPointInward(String originLayer, String targetLayer, boolean allowed)
      throws IOException {
    var origin = BASE + ".orders." + originLayer + ".Origin";
    var target =
        targetLayer.equals("platform")
            ? PLATFORM + ".domain.Target"
            : BASE + ".orders." + targetLayer + ".Target";
    var declarations = new HashMap<String, String>();
    declarations.put(origin, "public class Origin { " + target + " target; }");
    declarations.put(target, "public class Target {}");
    declarations.put(BASE + ".orders.api.ApiMarker", "public interface ApiMarker {}");
    declarations.put(BASE + ".orders.domain.DomainMarker", "public interface DomainMarker {}");
    declarations.put(
        BASE + ".orders.application.ApplicationMarker", "public interface ApplicationMarker {}");
    declarations.put(
        BASE + ".orders.infrastructure.InfrastructureMarker",
        "public interface InfrastructureMarker {}");
    var classes = fixture(declarations);
    if (allowed) {
      accepts(rule(classes, "LAYERS_POINT_INWARD"), classes);
    } else {
      rejects(rule(classes, "LAYERS_POINT_INWARD"), classes, origin, target);
    }
  }

  @Test
  void domainUsesJavaButRejectsTheConfiguredFrameworkSurface() throws IOException {
    var valid =
        fixture(
            Map.of(
                ORDER,
                "public class Order { java.util.UUID id; @org.jspecify.annotations.Nullable String note; }"));
    accepts(rule(valid, "DOMAIN_IS_FRAMEWORK_FREE"), valid);
    var invalid =
        fixture(Map.of(ORDER, "public class Order { jakarta.persistence.EntityManager manager; }"));
    rejects(
        rule(invalid, "DOMAIN_IS_FRAMEWORK_FREE"),
        invalid,
        ORDER,
        "jakarta.persistence.EntityManager");
    var custom = new BytecodePolicy(BASE, null, null, null, null, List.of("org.acme.forbidden.."));
    var library = "org.acme.forbidden.Library";
    var consumer =
        fixture(
            Map.of(
                library,
                "public class Library {}",
                ORDER,
                "public class Order { " + library + " library; }"));
    rejects(
        new BytecodeRuleCatalog(custom, List.of("orders")).rules().get("DOMAIN_IS_FRAMEWORK_FREE"),
        consumer,
        ORDER,
        library);
    accepts(rule(consumer, "DOMAIN_IS_FRAMEWORK_FREE"), consumer);
  }

  @Test
  void apiIsAPublishedLanguageOfRecordsInterfacesEnumsAndExceptions() throws IOException {
    var valid =
        fixture(
            Map.of(
                PLATFORM + ".domain.Money",
                "public record Money(long cents) {}",
                ORDER_REF,
                "public record OrderRef(long id, " + PLATFORM + ".domain.Money total) {}",
                BASE + ".orders.api.Orders",
                "public interface Orders { " + ORDER_REF + " place(long count); }",
                BASE + ".orders.api.OrderStatus",
                "public enum OrderStatus { OPEN }",
                BASE + ".orders.api.OrderRejectedException",
                "public class OrderRejectedException extends RuntimeException {}"));
    accepts(rule(valid, "API_IS_A_PUBLISHED_LANGUAGE"), valid);
    var service = BASE + ".orders.api.OrderService";
    var invalidShape = fixture(Map.of(service, "public class OrderService {}"));
    rejects(rule(invalidShape, "API_IS_A_PUBLISHED_LANGUAGE"), invalidShape, service);
    var leaking =
        fixture(
            Map.of(
                ORDER,
                "public class Order {}",
                ORDER_REF,
                "public record OrderRef(" + ORDER + " order) {}"));
    rejects(rule(leaking, "API_IS_A_PUBLISHED_LANGUAGE"), leaking, ORDER_REF, ORDER);
  }

  @Test
  void integrationEventsArePublicRecordsInApiEvents() throws IOException {
    var placed = BASE + ".orders.api.events.OrderPlaced";
    var valid =
        fixture(
            Map.of(
                placed,
                "public record OrderPlaced(long id) implements " + INTEGRATION_EVENT + " {}"));
    accepts(rule(valid, "INTEGRATION_EVENTS_ARE_PUBLIC_RECORDS"), valid);
    var internal = BASE + ".orders.domain.OrderPlaced";
    var invalid =
        fixture(Map.of(internal, "class OrderPlaced implements " + INTEGRATION_EVENT + " {}"));
    rejects(rule(invalid, "INTEGRATION_EVENTS_ARE_PUBLIC_RECORDS"), invalid, internal);
  }

  @Test
  void thePlatformDependsOnNoContext() throws IOException {
    var money = PLATFORM + ".domain.Money";
    var valid =
        fixture(
            Map.of(
                money,
                "public record Money(long cents) {}",
                ORDER,
                "public class Order { " + money + " total; }"));
    accepts(rule(valid, "PLATFORM_DEPENDS_ON_NO_CONTEXT"), valid);
    var invalid =
        fixture(
            Map.of(
                ORDER_REF,
                "public record OrderRef(long id) {}",
                money,
                "public record Money(" + ORDER_REF + " order) {}"));
    rejects(rule(invalid, "PLATFORM_DEPENDS_ON_NO_CONTEXT"), invalid, money, ORDER_REF);
  }

  @Test
  void transactionsBelongToTheApplicationLayer() throws IOException {
    var valid =
        fixture(
            Map.of(
                PLACE_ORDER,
                "public class PlaceOrderHandler { " + UNIT_OF_WORK + " unitOfWork; }",
                PLATFORM + ".infrastructure.DirectUnitOfWork",
                "public class DirectUnitOfWork implements "
                    + UNIT_OF_WORK
                    + " { public void begin() {} public void commit() {} }",
                BASE + ".orders.infrastructure.wiring.OrdersProducer",
                "public class OrdersProducer { " + UNIT_OF_WORK + " unitOfWork; }"));
    accepts(rule(valid, "TRANSACTIONS_BELONG_TO_APPLICATION"), valid);
    var resource = BASE + ".orders.infrastructure.inbound.rest.OrderResource";
    var invalid =
        fixture(
            Map.of(
                ORDER,
                "public class Order { " + UNIT_OF_WORK + " unitOfWork; }",
                resource,
                "public class OrderResource { @jakarta.transaction.Transactional public void place() {} }"));
    rejects(rule(invalid, "TRANSACTIONS_BELONG_TO_APPLICATION"), invalid, ORDER, resource);
  }

  @Test
  void persistenceIsTheOnlyJpaUserOutsideTheCompositionRoot() throws IOException {
    var entity = BASE + ".orders.infrastructure.outbound.persistence.entities.OrderEntity";
    var valid =
        fixture(
            Map.of(
                entity,
                "@jakarta.persistence.Entity public class OrderEntity { @jakarta.persistence.Id private long id; }",
                BASE + ".orders.infrastructure.wiring.OrdersProducer",
                "public class OrdersProducer { jakarta.persistence.EntityManager manager; }"));
    accepts(rule(valid, "PERSISTENCE_IS_THE_ONLY_JPA_USER"), valid);
    var invalid =
        fixture(
            Map.of(
                PLACE_ORDER,
                "public class PlaceOrderHandler { jakarta.persistence.EntityManager manager; }"));
    rejects(rule(invalid, "PERSISTENCE_IS_THE_ONLY_JPA_USER"), invalid, PLACE_ORDER);
  }

  @Test
  void restTalksOnlyToTheApplicationAndPublishedLanguage() throws IOException {
    var resource = BASE + ".orders.infrastructure.inbound.rest.OrderResource";
    var dto = BASE + ".orders.infrastructure.inbound.rest.dto.OrderDto";
    var valid =
        fixture(
            Map.of(
                ORDER_REF,
                "public record OrderRef(long id) {}",
                PLACE_ORDER,
                "public class PlaceOrderHandler {}",
                "com.fasterxml.jackson.annotation.JsonProperty",
                "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)"
                    + " public @interface JsonProperty { String value(); }",
                dto,
                "public record OrderDto("
                    + "@com.fasterxml.jackson.annotation.JsonProperty(\"order_id\") long id) {}",
                resource,
                "public class OrderResource { "
                    + PLACE_ORDER
                    + " handler; "
                    + ORDER_REF
                    + " ref; "
                    + dto
                    + " dto; java.util.UUID id; }"));
    accepts(rule(valid, "REST_TALKS_ONLY_TO_APPLICATION"), valid);
    var adapter = BASE + ".orders.infrastructure.outbound.persistence.OrderAdapter";
    var invalid =
        fixture(
            Map.of(
                ORDER,
                "public class Order {}",
                adapter,
                "public class OrderAdapter {}",
                "com.fasterxml.jackson.databind.ObjectMapper",
                "public class ObjectMapper {}",
                resource,
                "public class OrderResource { "
                    + ORDER
                    + " order; "
                    + adapter
                    + " adapter; com.fasterxml.jackson.databind.ObjectMapper json; }"));
    rejects(
        rule(invalid, "REST_TALKS_ONLY_TO_APPLICATION"),
        invalid,
        resource,
        ORDER,
        adapter,
        "com.fasterxml.jackson.databind.ObjectMapper");
  }

  @Test
  void outboundAdaptersDoNotDependOnTheApplication() throws IOException {
    var adapter = BASE + ".orders.infrastructure.outbound.persistence.OrderAdapter";
    var valid =
        fixture(
            Map.of(
                ORDER,
                "public class Order {}",
                adapter,
                "public class OrderAdapter { " + ORDER + " order; }"));
    accepts(rule(valid, "OUTBOUND_DOES_NOT_DEPEND_ON_APPLICATION"), valid);
    var invalid =
        fixture(
            Map.of(
                PLACE_ORDER,
                "public class PlaceOrderHandler {}",
                adapter,
                "public class OrderAdapter { " + PLACE_ORDER + " handler; }"));
    rejects(
        rule(invalid, "OUTBOUND_DOES_NOT_DEPEND_ON_APPLICATION"), invalid, adapter, PLACE_ORDER);
  }

  @Test
  void handlersDoNotReturnAggregateRootsEvenInsideGenerics() throws IOException {
    var valid =
        fixture(
            Map.of(
                ORDER_REF,
                "public record OrderRef(long id) {}",
                PLACE_ORDER,
                "public class PlaceOrderHandler { public "
                    + ORDER_REF
                    + " place() { return new "
                    + ORDER_REF
                    + "(1); } }"));
    accepts(rule(valid, "HANDLERS_DO_NOT_RETURN_AGGREGATES"), valid);
    var invalid =
        fixture(
            Map.of(
                ORDER, "@" + AGGREGATE_ROOT + " public class Order {}",
                PLACE_ORDER,
                    "public class PlaceOrderHandler { public java.util.Optional<"
                        + ORDER
                        + "> find() { return java.util.Optional.empty(); } }"));
    rejects(rule(invalid, "HANDLERS_DO_NOT_RETURN_AGGREGATES"), invalid, PLACE_ORDER, ORDER);
  }

  @Test
  void everyContextDeclaresExactlyOneProducerInItsWiringPackage() throws IOException {
    var ordersProducer = BASE + ".orders.infrastructure.wiring.OrdersProducer";
    var billingProducer = BASE + ".billing.infrastructure.wiring.BillingProducer";
    var producer =
        "public class %s { @jakarta.enterprise.inject.Produces public String label() { return \"\"; } }";
    var valid =
        fixture(
            Map.of(
                ordersProducer, producer.formatted("OrdersProducer"),
                billingProducer, producer.formatted("BillingProducer")));
    accepts(rule(valid, "ONE_PRODUCER_PER_CONTEXT"), valid);
    var extra = BASE + ".orders.infrastructure.wiring.ExtraProducer";
    var duplicated =
        fixture(
            Map.of(
                ordersProducer, producer.formatted("OrdersProducer"),
                extra, producer.formatted("ExtraProducer")));
    rejects(
        rule(duplicated, "ONE_PRODUCER_PER_CONTEXT"),
        duplicated,
        "context orders declares 2 producers",
        extra);
    var misplaced = BASE + ".orders.application.OrdersProducer";
    var outsideWiring = fixture(Map.of(misplaced, producer.formatted("OrdersProducer")));
    rejects(
        rule(outsideWiring, "ONE_PRODUCER_PER_CONTEXT"),
        outsideWiring,
        misplaced,
        "<context>.infrastructure.wiring");
    var missing =
        fixture(
            Map.of(
                ordersProducer,
                producer.formatted("OrdersProducer"),
                BASE + ".billing.domain.Invoice",
                "public class Invoice {}"));
    rejects(
        rule(missing, "ONE_PRODUCER_PER_CONTEXT"), missing, "context billing declares 0 producers");
  }

  @Test
  void domainRepositoriesAreInterfaces() throws IOException {
    var repository = BASE + ".orders.domain.OrderRepository";
    var valid = fixture(Map.of(repository, "public interface OrderRepository {}"));
    accepts(rule(valid, "DOMAIN_REPOSITORIES_ARE_INTERFACES"), valid);
    var invalid = fixture(Map.of(repository, "public class OrderRepository {}"));
    rejects(rule(invalid, "DOMAIN_REPOSITORIES_ARE_INTERFACES"), invalid, repository);
  }

  @Test
  void domainPackagesAreNullMarked() throws IOException {
    var valid =
        fixture(
            Map.of(
                ORDER,
                "public class Order {}",
                BASE + ".orders.domain.package-info",
                "@org.jspecify.annotations.NullMarked",
                PLATFORM + ".domain.package-info",
                "@org.jspecify.annotations.NullMarked"));
    accepts(rule(valid, "DOMAIN_PACKAGES_ARE_NULL_MARKED"), valid);
    var invalid = fixture(Map.of(ORDER, "public class Order {}"));
    rejects(rule(invalid, "DOMAIN_PACKAGES_ARE_NULL_MARKED"), invalid, ORDER, "unmarked");
  }

  @Test
  void aggregateRootsHavePrivateStateAndNoPublicSetters() throws IOException {
    var valid =
        fixture(
            Map.of(
                ORDER,
                "@"
                    + AGGREGATE_ROOT
                    + " public class Order { private long count; private void setCount(long value) { count = value; } }"));
    accepts(rule(valid, "AGGREGATE_ROOTS_HAVE_PRIVATE_STATE"), valid);
    accepts(rule(valid, "AGGREGATE_ROOTS_HAVE_NO_PUBLIC_SETTERS"), valid);
    var invalid =
        fixture(
            Map.of(
                ORDER,
                "@"
                    + AGGREGATE_ROOT
                    + " public class Order { long count; public void setCount(long value) { count = value; } }"));
    rejects(rule(invalid, "AGGREGATE_ROOTS_HAVE_PRIVATE_STATE"), invalid, ORDER + ".count");
    rejects(rule(invalid, "AGGREGATE_ROOTS_HAVE_NO_PUBLIC_SETTERS"), invalid, "setCount");
  }

  @Test
  void domainStateIsPrivateAndOnlyAggregatesReassignIt() throws IOException {
    var total = BASE + ".orders.domain.Total";
    var valid =
        fixture(
            Map.of(
                ORDER,
                "@" + AGGREGATE_ROOT + " public class Order { private long count; }",
                total,
                "public record Total(long cents) {}",
                BASE + ".orders.domain.OrderPolicy",
                "public class OrderPolicy { private final long limit = 1; }"));
    accepts(rule(valid, "DOMAIN_STATE_IS_PRIVATE"), valid);
    accepts(rule(valid, "ONLY_AGGREGATES_REASSIGN_DOMAIN_STATE"), valid);
    var policy = BASE + ".orders.domain.OrderPolicy";
    var invalid = fixture(Map.of(policy, "public class OrderPolicy { public long limit; }"));
    rejects(rule(invalid, "DOMAIN_STATE_IS_PRIVATE"), invalid, policy + ".limit");
    rejects(rule(invalid, "ONLY_AGGREGATES_REASSIGN_DOMAIN_STATE"), invalid, policy + ".limit");
  }

  @Test
  void domainTypesAreConstructedByTheirDomainOrPublishedToTheCompositionRoot() throws IOException {
    var factory = BASE + ".orders.domain.OrderFactory";
    var line = BASE + ".orders.domain.OrderLine";
    var producer = BASE + ".orders.infrastructure.wiring.OrdersProducer";
    var order =
        "public class Order { Order() {} public static Builder builder() { return new Builder(); }"
            + " public static class Builder { public Order build() { return new Order(); } } }";
    var valid =
        fixture(
            Map.of(
                ORDER,
                order,
                line,
                "public record OrderLine(long quantity) {}",
                BASE + ".orders.domain.command.PlaceOrderCommand",
                "public class PlaceOrderCommand {}",
                factory,
                "public class OrderFactory { public Order create() { return Order.builder().build(); } }",
                PLACE_ORDER,
                "public class PlaceOrderHandler { public Object place("
                    + factory
                    + " orders) { orders.create(); return new "
                    + line
                    + "(1); } public Object command() { return new "
                    + BASE
                    + ".orders.domain.command.PlaceOrderCommand(); } }",
                producer,
                "public class OrdersProducer { public "
                    + factory
                    + " orders() { return new "
                    + factory
                    + "(); } }"));
    accepts(rule(valid, "DOMAIN_TYPES_ARE_CONSTRUCTED_BY_THEIR_DOMAIN"), valid);
    var policy = BASE + ".orders.domain.OrderPolicy";
    var invoice = BASE + ".billing.domain.InvoicePolicy";
    var invalid =
        fixture(
            Map.of(
                ORDER,
                order.replace(" Order() {}", " public Order() {}"),
                policy,
                "public class OrderPolicy {}",
                factory,
                "public class OrderFactory {}",
                PLACE_ORDER,
                "public class PlaceOrderHandler {"
                    + " public Object built() { return "
                    + ORDER
                    + ".builder().build(); }"
                    + " public java.util.function.Supplier<"
                    + ORDER
                    + "> referenced() { return "
                    + ORDER
                    + "::new; }"
                    + " public Object assembled() { return new "
                    + factory
                    + "(); } }",
                producer,
                "public class OrdersProducer { public Object policy() { return new "
                    + policy
                    + "(); } public Object order() { return "
                    + ORDER
                    + ".builder().build(); } }",
                BASE + ".orders.infrastructure.outbound.persistence.OrderAdapter",
                "public class OrderAdapter { public Object order() { return "
                    + ORDER
                    + ".builder().build(); } }",
                invoice,
                "public class InvoicePolicy { public Object order() { return new "
                    + policy
                    + "(); } }"));
    rejects(
        rule(invalid, "DOMAIN_TYPES_ARE_CONSTRUCTED_BY_THEIR_DOMAIN"),
        invalid,
        "PlaceOrderHandler.built()",
        "PlaceOrderHandler.referenced()",
        "PlaceOrderHandler.assembled()",
        "OrderAdapter.order()",
        "InvoicePolicy.order()",
        "call a factory published by orders.domain");
  }

  @Test
  void transferObjectsAndTheirPackagesCoincide() throws IOException {
    var dto = BASE + ".orders.infrastructure.inbound.rest.dto.OrderDto";
    var valid = fixture(Map.of(dto, "public record OrderDto(long id) {}"));
    accepts(rule(valid, "TRANSFER_OBJECT_PACKAGES_CONTAIN_ONLY_TRANSFER_OBJECTS"), valid);
    accepts(rule(valid, "TRANSFER_OBJECTS_BELONG_TO_DTO_PACKAGES"), valid);
    var helper = BASE + ".orders.infrastructure.inbound.rest.dto.Helper";
    var stray = BASE + ".orders.infrastructure.inbound.rest.StrayDto";
    var invalid =
        fixture(
            Map.of(
                helper, "public class Helper {}",
                stray, "public record StrayDto(long id) {}"));
    rejects(
        rule(invalid, "TRANSFER_OBJECT_PACKAGES_CONTAIN_ONLY_TRANSFER_OBJECTS"), invalid, helper);
    rejects(rule(invalid, "TRANSFER_OBJECTS_BELONG_TO_DTO_PACKAGES"), invalid, stray);
  }

  @Test
  void jpaEntitiesFollowTheEntityConventionsAndStayInsideTheirPackages() throws IOException {
    var entity = BASE + ".orders.infrastructure.outbound.persistence.entities.OrderEntity";
    var valid =
        fixture(
            Map.of(
                entity,
                "@jakarta.persistence.Entity public class OrderEntity { @jakarta.persistence.Id private long id; }"));
    accepts(rule(valid, "JPA_ENTITIES_FOLLOW_ENTITY_CONVENTIONS"), valid);
    accepts(rule(valid, "ENTITY_PACKAGES_CONTAIN_ONLY_ENTITIES"), valid);
    var misplaced = BASE + ".orders.domain.OrderRow";
    var helper = BASE + ".orders.infrastructure.outbound.persistence.entities.Helper";
    var invalid =
        fixture(
            Map.of(
                misplaced,
                    "@jakarta.persistence.Entity public class OrderRow { @jakarta.persistence.Id private long id; }",
                helper, "public class Helper {}"));
    rejects(rule(invalid, "JPA_ENTITIES_FOLLOW_ENTITY_CONVENTIONS"), invalid, misplaced);
    rejects(rule(invalid, "ENTITY_PACKAGES_CONTAIN_ONLY_ENTITIES"), invalid, helper);
  }

  @Test
  void transferObjectsAndEntitiesDoNotLeakInnerLayers() throws IOException {
    var dto = BASE + ".orders.infrastructure.inbound.rest.dto.OrderDto";
    var entity = BASE + ".orders.infrastructure.outbound.persistence.entities.OrderEntity";
    var valid =
        fixture(
            Map.of(
                ORDER_REF, "public record OrderRef(long id) {}",
                dto, "public record OrderDto(" + ORDER_REF + " ref) {}",
                entity,
                    "@jakarta.persistence.Entity public class OrderEntity { @jakarta.persistence.Id private long id; }"));
    accepts(rule(valid, "REST_TRANSFER_OBJECTS_DO_NOT_LEAK_INNER_LAYERS"), valid);
    accepts(rule(valid, "ENTITY_REPRESENTATIONS_DO_NOT_LEAK_INNER_LAYERS"), valid);
    var invalid =
        fixture(
            Map.of(
                ORDER,
                "public class Order {}",
                PLACE_ORDER,
                "public class PlaceOrderHandler {}",
                dto,
                "public record OrderDto(" + ORDER + " order) {}",
                entity,
                "@jakarta.persistence.Entity public class OrderEntity { @jakarta.persistence.Id private long id; "
                    + PLACE_ORDER
                    + " handler; }"));
    rejects(rule(invalid, "REST_TRANSFER_OBJECTS_DO_NOT_LEAK_INNER_LAYERS"), invalid, dto, ORDER);
    rejects(
        rule(invalid, "ENTITY_REPRESENTATIONS_DO_NOT_LEAK_INNER_LAYERS"),
        invalid,
        entity,
        PLACE_ORDER);
  }

  @Test
  void mappersAreMapStructInterfacesInMapperPackagesWithGeneratedImplementations()
      throws IOException {
    var mapper = BASE + ".orders.infrastructure.inbound.rest.mappers.OrderRestMapper";
    var valid =
        fixture(
            Map.of(
                mapper,
                "@org.mapstruct.Mapper public interface OrderRestMapper { String dto(long id); }",
                mapper + "Impl",
                "public class OrderRestMapperImpl implements OrderRestMapper { public String dto(long id) { return \"\"; } }"));
    for (var identity :
        List.of(
            "MAPPERS_USE_MAPSTRUCT",
            "MAPSTRUCT_MAPPERS_HAVE_A_MAPPER_PACKAGE",
            "MAPSTRUCT_MAPPERS_ARE_INTERFACES",
            "MAPSTRUCT_MAPPERS_HAVE_GENERATED_IMPLEMENTATIONS",
            "MAPSTRUCT_MAPPER_METHODS_ARE_ABSTRACT")) {
      accepts(rule(valid, identity), valid);
    }
    var handwritten = BASE + ".orders.infrastructure.inbound.rest.mappers.OrderConverter";
    var misplaced = BASE + ".orders.infrastructure.inbound.rest.OrderMapper";
    var abstractMapper = BASE + ".orders.infrastructure.inbound.rest.mappers.AbstractMapper";
    var invalid =
        fixture(
            Map.of(
                handwritten, "public interface OrderConverter {}",
                misplaced,
                    "@org.mapstruct.Mapper public interface OrderMapper { default String dto(long id) { return \"\"; } }",
                abstractMapper, "@org.mapstruct.Mapper public abstract class AbstractMapper {}"));
    rejects(rule(invalid, "MAPPERS_USE_MAPSTRUCT"), invalid, handwritten);
    rejects(rule(invalid, "MAPSTRUCT_MAPPERS_HAVE_A_MAPPER_PACKAGE"), invalid, misplaced);
    rejects(rule(invalid, "MAPSTRUCT_MAPPERS_ARE_INTERFACES"), invalid, abstractMapper);
    rejects(rule(invalid, "MAPSTRUCT_MAPPERS_HAVE_GENERATED_IMPLEMENTATIONS"), invalid, misplaced);
    rejects(rule(invalid, "MAPSTRUCT_MAPPER_METHODS_ARE_ABSTRACT"), invalid, misplaced + ".dto");
  }

  @Test
  void boundaryCarriersAreHandledOnlyByGeneratedMappers() throws IOException {
    var dto = BASE + ".orders.infrastructure.inbound.rest.dto.OrderDto";
    var mapper = BASE + ".orders.infrastructure.inbound.rest.mappers.OrderRestMapper";
    var carrier =
        "public class OrderDto { private long id; public void setId(long id) { this.id = id; } }";
    var valid =
        fixture(
            Map.of(
                dto,
                carrier,
                mapper,
                "@org.mapstruct.Mapper public interface OrderRestMapper { "
                    + dto
                    + " dto(long id); }",
                mapper + "Impl",
                "public class OrderRestMapperImpl implements OrderRestMapper { public "
                    + dto
                    + " dto(long id) { var dto = new "
                    + dto
                    + "(); dto.setId(id); return dto; } }"));
    for (var identity :
        List.of(
            "BOUNDARY_CARRIERS_ARE_ONLY_CONSTRUCTED_BY_MAPSTRUCT",
            "BOUNDARY_CARRIER_SETTERS_ARE_ONLY_CALLED_BY_MAPSTRUCT",
            "BOUNDARY_CARRIER_STATE_IS_PRIVATE")) {
      accepts(rule(valid, identity), valid);
    }
    var resource = BASE + ".orders.infrastructure.inbound.rest.OrderResource";
    var invalid =
        fixture(
            Map.of(
                dto,
                "public class OrderDto { public long id; public void setId(long id) { this.id = id; } }",
                resource,
                "public class OrderResource { public "
                    + dto
                    + " read() { var dto = new "
                    + dto
                    + "(); dto.setId(1); return dto; } }"));
    rejects(
        rule(invalid, "BOUNDARY_CARRIERS_ARE_ONLY_CONSTRUCTED_BY_MAPSTRUCT"), invalid, resource);
    rejects(
        rule(invalid, "BOUNDARY_CARRIER_SETTERS_ARE_ONLY_CALLED_BY_MAPSTRUCT"), invalid, resource);
    rejects(rule(invalid, "BOUNDARY_CARRIER_STATE_IS_PRIVATE"), invalid, dto + ".id");
  }

  @Test
  void packageMetadataMayLiveInEveryBoundaryPackage() throws IOException {
    var dto = BASE + ".orders.infrastructure.inbound.rest.dto.OrderDto";
    var entity = BASE + ".orders.infrastructure.outbound.persistence.entities.OrderEntity";
    var mapper = BASE + ".orders.infrastructure.inbound.rest.mappers.OrderRestMapper";
    var marked = "@org.jspecify.annotations.NullMarked";
    var classes =
        fixture(
            Map.of(
                dto,
                "public record OrderDto(long id) {}",
                BASE + ".orders.infrastructure.inbound.rest.dto.package-info",
                marked,
                entity,
                "@jakarta.persistence.Entity public class OrderEntity { @jakarta.persistence.Id private long id; }",
                BASE + ".orders.infrastructure.outbound.persistence.entities.package-info",
                marked,
                mapper,
                "@org.mapstruct.Mapper public interface OrderRestMapper { String dto(long id); }",
                mapper + "Impl",
                "public class OrderRestMapperImpl implements OrderRestMapper { public String dto(long id) { return \"\"; } }",
                BASE + ".orders.infrastructure.inbound.rest.mappers.package-info",
                marked));
    for (var identity :
        List.of(
            "TRANSFER_OBJECT_PACKAGES_CONTAIN_ONLY_TRANSFER_OBJECTS",
            "ENTITY_PACKAGES_CONTAIN_ONLY_ENTITIES",
            "MAPPERS_USE_MAPSTRUCT")) {
      accepts(rule(classes, identity), classes);
    }
  }

  private JavaClasses fixture(Map<String, String> declarations) throws IOException {
    var merged = new HashMap<>(PLATFORM_TYPES);
    merged.putAll(declarations);
    return compile(temporary, merged);
  }

  private static ArchRule rule(JavaClasses classes, String identity) {
    var rule =
        new BytecodeRuleCatalog(POLICY, BoundedContexts.derive(POLICY, classes))
            .rules()
            .get(identity);
    if (rule == null) throw new IllegalArgumentException("Unknown identity " + identity);
    return rule;
  }
}
