# ChatZ

ChatZ is a starter repository for a local-network chat client. It contains an Android Kotlin engine, a Swift Network-framework counterpart, and a Helm/GitOps example for a future backend.

> The supplied source was only `LocalChatEngine.kt`. No existing Swift files or backend service were available, so `ios/LocalChatEngine.swift` is a new counterpart, not a change to an existing iOS app. The mobile peer-to-peer client is not itself a Kubernetes workload. The deployment chart is an example for a separately built ChatZ backend and will not work until its image and health endpoint exist.

## Repository layout

- `android/` — improved Android local-chat engine.
- `ios/` — Swift counterpart using Bonjour and Apple's Network framework.
- `devops/helm/chatz/` — configurable backend Helm chart.
- `devops/kubernetes/` — namespace and deployment notes.
- `devops/gitops/` — Argo CD and Flux examples.
- `devops/ci/` — Jenkins, Azure Pipelines, and GitLab CI chart validation.
- `.github/workflows/ci.yml` — GitHub Actions chart validation.

## Local-network behavior

The engines exchange UTF-8 text messages delimited by newlines and advertise/discover `_localchat._tcp`. Keep messages to one line and at most 64 KiB. Network callbacks run on background queues; dispatch to the UI thread before updating UI.

On Android, request the platform's local-network permissions appropriate to the target SDK and declare the service in the app manifest. On iOS, add `NSLocalNetworkUsageDescription` and the `_localchat._tcp` entry to `NSBonjourServices` in the app's `Info.plist`. Verify permission prompts and discovery on physical devices.

## Helm quick start

The chart uses a deliberately non-existent example image by default. Replace it with a real, versioned backend image before installing:

```powershell
helm lint .\devops\helm\chatz
helm template chatz .\devops\helm\chatz --namespace chatz
helm upgrade --install chatz .\devops\helm\chatz --namespace chatz --create-namespace --set image.repository=YOUR_REGISTRY/chatz-backend --set image.tag=YOUR_TAG
```

The backend must listen on the configured container port and implement the configured HTTP health endpoint. Do not expose a peer-to-peer mobile client as a Kubernetes service.

## GitOps setup

The Argo CD and Flux examples point to the public ChatZ repository. Change the repository URL if you fork it. Configure cluster access and image-pull credentials through your platform's secret manager; never commit credentials to this repository.

## Publish to GitHub

After installing Git and the GitHub CLI and authenticating with `gh auth login`, create the public repository from this directory:

```powershell
gh repo create ChatZ --public --source . --remote origin --push
```

The GitOps examples are configured for this repository. Update their repository URL if you publish under a different owner or fork.

## CI

The pipeline examples validate the Helm chart. Azure Pipelines, GitLab CI, and GitHub Actions have repository-root entry points; configure Jenkins to use `devops/ci/jenkins/Jenkinsfile`. These pipelines do not build mobile apps because the supplied code has no Gradle project, Xcode project, or Swift package. Add platform build/test jobs after integrating the code into those app projects.

CI checks the chart; Argo CD or Flux performs the continuous delivery reconciliation. No cluster credentials are placed in the CI configuration.

## Security notes

- Use private registries and immutable image tags for releases.
- Set resource requests/limits and run the backend as a non-root user.
- Keep secrets out of values files and source control.
- The example chart enables a restrictive pod security context; adapt it only when the backend requires it.
