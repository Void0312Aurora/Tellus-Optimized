# Runtime rollout gate

`TellusRuntimeRolloutGate` is a pure, caller-invoked evidence gate. It does not construct or register a runtime manager and it does not install loader or `TellusCommon` hooks.

The rollout is `ENABLED` only when all four inputs are true:

1. The caller explicitly sets the rollout flag.
2. The columns parity evidence is fresh and passed.
3. The biome parity evidence is fresh and passed.
4. The injected storage readiness signal is true.

Any missing, stale, or failed condition returns `DISABLED` with diagnostic reasons. The default manager constructor remains disabled, and existing lifecycle callbacks remain explicit and unchanged.

```java
TellusRuntimeRolloutGate.Result result = TellusRuntimeRolloutGate.evaluate(
    new TellusRuntimeRolloutGate.Request(
        explicitFlag,
        new TellusRuntimeRolloutGate.ParityEvidence(columnsFresh, columnsPass, columnsRunId),
        new TellusRuntimeRolloutGate.ParityEvidence(biomeFresh, biomePass, biomeRunId),
        storageReady));

TellusGeneratorRuntimeLifecycleManager manager =
    TellusGeneratorRuntimeLifecycleManager.fromRolloutGate(registry, result);
```

Only a `Result` with `status() == ENABLED` enables the manager. A disabled result leaves all explicit callbacks disabled and leaves the caller-owned registry unchanged. The legacy boolean and default manager constructors remain available and retain their previous behavior.

Parity identifiers are evidence labels only; the gate does not execute replay, discover manifests, access storage, or infer freshness. A caller must supply current evidence and readiness explicitly.
