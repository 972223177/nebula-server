# Nebula Chat Server

A real-time gRPC bidirectional streaming chat server built with Kotlin.

## Module Dependency

```
proto  ←  common  ←  repository  ←  service  ←  gateway  ←  server
```

- **proto**: Protocol buffer definitions and generated stubs
- **common**: Shared utilities, error codes, logging
- **repository**: Data access and persistence layer
- **service**: Business logic layer
- **gateway**: gRPC server and request handling
- **server**: Application entry point

## Build

```bash
./gradlew build
```

## License

MIT
