package io.github.jf3env.architecture.bytecode;

import java.util.Set;

final class TestPolicies {
  private TestPolicies() {}

  static BytecodePolicy reference() {
    return new BytecodePolicy(
        "com.ai.label",
        new DomainPolicy(
            "workspace",
            "assets",
            "com.ai.label.domain.workspace.aggregate.Workspace",
            "com.ai.label.domain.workspace.WorkspaceRepository",
            Set.of("AssetFolderService", "WorkspaceConfigurationService"),
            "AssetRepository"));
  }

  static BytecodePolicy orders(String base) {
    return new BytecodePolicy(
        base,
        new DomainPolicy(
            "orders",
            "assets",
            base + ".domain.orders.aggregate.Order",
            base + ".domain.orders.OrderRepository",
            Set.of("OrderService"),
            "AssetRepository"));
  }
}
