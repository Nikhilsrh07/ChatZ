# Kubernetes

The Helm chart deploys a future ChatZ backend, not the Android/iOS peer-to-peer client. Set a real image, port, health endpoint, and resource profile before applying it. For production, use a managed secret store for registry credentials and application secrets.

The chart creates its own namespace resources only when instructed; `namespace.yaml` is an optional standalone namespace manifest. Prefer GitOps reconciliation over manual production `helm upgrade` commands.
