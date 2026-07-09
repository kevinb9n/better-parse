# TODO

## Future improvements not yet discussed

- Decide whether JitPack should also cover Apple and Windows native KMP publications; the current Linux-hosted JitPack setup only publishes JVM, JS, Linux native, and multiplatform metadata artifacts.
- Add a custom-parser hook for completion introspection, analogous to `LiftToSyntaxTreeTransformer`,
  so parser types outside the built-in combinators can expose precise continuations.
