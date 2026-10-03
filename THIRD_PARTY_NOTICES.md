# Third-party notices

Studio source uses the same MIT license as DUI and dui-demo. This does not relicense its dependencies.

- [DUI](https://github.com/Kembel-Entertainment/dui): MIT. The selected source revision is recorded in `dui-version.json`.
- [Tauri](https://github.com/tauri-apps/tauri): MIT / Apache-2.0.
- [Svelte](https://github.com/sveltejs/svelte), [CodeMirror](https://github.com/codemirror/dev) and their frontend dependencies retain their respective package licenses. Exact versions are in `pnpm-lock.yaml`; use `pnpm licenses list --prod` for the installed inventory.
- Rust dependencies retain the licenses declared by their crates; exact versions/source checksums are in `src-tauri/Cargo.lock`.
- The Java renderer uses [Gson](https://github.com/google/gson) and [Error Prone annotations](https://github.com/google/error-prone) (Apache-2.0), [JOML](https://github.com/JOML-CI/JOML) (MIT), [LWJGL](https://github.com/LWJGL/lwjgl3) (BSD-3-Clause) and [GLFW](https://github.com/glfw/glfw) (Zlib).
- The local runtime script retains the selected OpenJDK distribution's `jre/legal` files. That runtime is generated locally and ignored by Git.

Minecraft fonts, textures, models and equipment are read from the user's local client JAR and are not redistributed in this source repository. No ROMs, videos, browser profiles or consumer projects are included. Consumers own their adapter and resource licenses. Review all relevant dependency notices before distributing your own compiled application.
