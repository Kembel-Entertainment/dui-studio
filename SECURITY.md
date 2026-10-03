# Security and local data

Studio is a WIP local desktop editor. Only use trusted project folders. Java adapters declared in `dui.studio.json` (or its local profile) run in the renderer JVM with the user's process permissions. They are not sandboxed, even though their declared classpath is constrained to the selected project. They can perform side effects if authored to do so. GLSL is compiled by the local graphics driver.

Built-in file commands constrain paths to the selected project, reject traversal and symlink escapes, cap text/assets, and detect external edits before saving. Existing files are replaced using a temporary file in the same directory. These checks are not a sandbox for a project adapter and are not protection against a hostile process concurrently changing filesystem links.

The frontend has a Content Security Policy, no generic shell/filesystem plugin and no remote web content. Native preview requests use a local Java child over stdio, bounded responses and a timeout. The child is terminated when the project changes or Studio exits. The app does not call analytics/telemetry services or upload project files. Development/build steps intentionally fetch dependencies; consumer adapters can implement their own network behavior.

The selected project/Minecraft paths are stored locally in the webview's local storage. Preview frames stay in memory; explicit saves write project files. Development/test tools create ignored local fixtures, logs and PNGs. Bug reports should redact local paths and confidential source files. Do not commit credentials or confidential project settings.

Report vulnerabilities privately through [GitHub's vulnerability reporting](https://github.com/Kembel-Entertainment/dui-studio/security/advisories/new) when enabled; otherwise contact the maintainers through the organization profile. Do not put exploit details or secrets in public issue attachments. No security audit or stable support window is claimed for this WIP project.
