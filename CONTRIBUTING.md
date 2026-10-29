# Contributing

- Keep changes focused and use the formatting rules in `.editorconfig`.
- Never commit credentials, signing keys, provisioning profiles, or production values.
- Validate chart changes with `helm lint devops/helm/chatz` and render with `helm template`.
- Add Android and iOS build/test jobs when their native project files are added.
- Use reviewed, immutable container image tags for deployments.
