# NPC Mind — Forge 1.20.1

**NPC Mind** is a reusable Minecraft Forge 1.20.1 library for NPCs and game agents. It provides memory, observations, planning, safe tool execution, navigation, tasks, combat, crafting, farming, building, containers, and persistent state without forcing a standalone player companion experience.

## Included

- Reusable `CompanionEntity` base NPC with persistent state and inventory.
- `AgentEngine` for asynchronous observe → plan → execute → verify cycles.
- `ToolRegistry`, `AITool`, and `ToolContract` for registering and validating AI tools.
- Bounded episodic `AgentMemory` and persistent `MemoryStore` backed by world NBT.
- Safe server-thread tool execution through `AgentToolRuntime`.
- Deterministic navigation, tasks, survival reflexes, combat, resource planning, crafting, farming, building, containers, exploration, and structure search.
- Optional Groq-compatible reasoning with explicit API configuration and request/token budgets.

## Removed from the original project

NPC Mind does not include the old player-facing commands, automatic player companion spawning, client HUD/model/voice code, push-to-talk networking, or the end-game dragon quest. A host mod decides what NPCs to create and how players interact with them.

NPC Mind also does not register a client renderer. A host mod must provide a renderer for its NPC subtype; otherwise the server entity can exist while remaining invisible on the client. This keeps the library independent from a specific player skin or presentation layer.

## Requirements

- Minecraft 1.20.1
- Forge 47.4.10 or newer in the 47.x line
- Java 17

## Building

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew clean build
```

The library jar is written to `build/libs/npc-mind-1.0.0.jar` after a successful build. The reobfuscated jar is intended for a normal Forge installation.

## Using it from another mod

Declare NPC Mind as a required Forge dependency, then extend the reusable base NPC or integrate your own NPC with its public runtime:

```java
public final class MyNpc extends CompanionEntity {
    public MyNpc(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }
}
```

To expose a custom capability to the reasoning layer:

```java
public final class ScanOreTool implements AITool {
    @Override public String name() { return "scan_ore"; }
    @Override public String description() { return "Inspect loaded blocks near the NPC and report matching ore."; }
    @Override public String schema() { return "keyword:string, radius:number (optional)"; }

    @Override
    public String execute(CompanionEntity npc, ServerPlayer player, String arguments) {
        // Inspect the live server here. Never create an unobserved result.
        return "SUCCESS ...";
    }
}
```

The built-in registry and contract are the safety boundary: malformed JSON, unknown arguments, wrong primitive types, and non-finite numbers are rejected before execution. A host mod can add its own tool registration layer around `AITool` and `ToolRegistry`.

### Host-owned interaction and speech

The base `CompanionEntity` returns `InteractionResult.PASS` for direct item hand-off by default. This prevents a generic library NPC from consuming a player's held item or imposing a UI policy. A host NPC can opt in explicitly:

```java
@Override
protected boolean allowDirectItemTransfer() { return true; }
```

For translated chat, a host can install `CompanionSpeech` once during common setup. The default remains a plain server message, so the library works without a client UI:

```java
CompanionEntity.setSpeechSink((npc, player, text) ->
    player.sendSystemMessage(Component.translatable("mymod.npc.speech", text)));
```

The library does not ship a hard-coded natural-language phrase layer. Host mods should route their own localized input to `AITool` and `AgentEngine`, keeping language policy outside the reusable AI runtime.

## Reasoning configuration

NPC Mind does not require remote reasoning for deterministic tools. If the host server enables Groq-compatible planning, configure:

```toml
groqApiKey = "gsk_..."
groqModel = "openai/gpt-oss-120b"
groqBaseUrl = "https://api.groq.com/openai/v1"
groqMaxRequestsPerMinute = 6
groqMaxRequestsPerDay = 500
groqMaxTokensPerDay = 200000
```

The API key stays in the local Forge config and is never written to the repository. Deterministic tools remain usable when the provider is unavailable or budget-limited.

## Package map

- `agent/` — planner orchestration, memory, decisions, and tool runtime.
- `tool/` — tool contracts, registry, validation, and reusable Minecraft tools.
- `memory/` and `state/` — persistent memory, skills, permissions, goals, and world state.
- `task/`, `autonomy/`, and `advanced/` — deterministic execution, navigation, survival, combat, and observations.
- `entity/` — reusable base NPC and inventory/state integration.
- `core/`, `knowledge/`, `crafting/`, and `security/` — snapshots, context, recipes, inventory operations, and action safety.

## License

MIT. See [LICENSE](LICENSE).
