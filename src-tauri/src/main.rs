#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
use serde::Serialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::{BufReader, Read, Write},
    path::{Path, PathBuf},
    process::{Child, ChildStdin, Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Mutex,
    },
    time::Duration,
};
use tauri::{Manager, State};

#[derive(Default)]
struct Workspace {
    root: Option<PathBuf>,
    engine: Option<Engine>,
    frame: Vec<u8>,
    revision: u64,
}
struct Engine {
    child: Child,
    input: ChildStdin,
    output: mpsc::Receiver<Result<(Value, Vec<u8>), String>>,
    next: u64,
}
impl Drop for Engine {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}
impl Engine {
    fn request(&mut self, method: &str, params: Value) -> Result<(Value, Vec<u8>), String> {
        self.next += 1;
        let mut line = serde_json::to_vec(&json!({"id":self.next,"method":method,"params":params}))
            .map_err(err)?;
        line.push(b'\n');
        self.input.write_all(&line).map_err(err)?;
        self.input.flush().map_err(err)?;
        let (response, image) = match self.output.recv_timeout(Duration::from_secs(15)) {
            Ok(result) => result?,
            Err(e) => {
                let _ = self.child.kill();
                return Err(format!(
                    "Preview engine did not respond: {e}. Reopen the project."
                ));
            }
        };
        if response["id"].as_u64() != Some(self.next) {
            return Err("Preview protocol sequence mismatch".into());
        }
        if let Some(error) = response["error"].as_str() {
            return Err(error.into());
        }
        Ok((response["result"].clone(), image))
    }
}
fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}
fn hash(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}
fn scoped(root: &Path, relative: &str, create: bool) -> Result<PathBuf, String> {
    let input = Path::new(relative);
    if input.is_absolute()
        || input
            .components()
            .any(|v| !matches!(v, std::path::Component::Normal(_)))
    {
        return Err("Use a relative project path".into());
    }
    let path = root.join(input);
    if path.exists() {
        let real = path.canonicalize().map_err(err)?;
        if !real.starts_with(root) {
            return Err("Path leaves the selected project".into());
        }
        return Ok(real);
    }
    if !create {
        return Err("File no longer exists".into());
    }
    let mut ancestor = path.parent().ok_or("Missing parent")?;
    while !ancestor.exists() {
        ancestor = ancestor.parent().ok_or("Missing ancestor")?;
    }
    if !ancestor.canonicalize().map_err(err)?.starts_with(root) {
        return Err("Path leaves the selected project".into());
    }
    Ok(path)
}
#[derive(Serialize)]
struct Entry {
    path: String,
    kind: String,
    size: u64,
}
fn scan(root: &Path, dir: &Path, entries: &mut Vec<Entry>, depth: usize) -> Result<(), String> {
    if depth > 12 || entries.len() > 12000 {
        return Ok(());
    }
    let mut files = fs::read_dir(dir)
        .map_err(err)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(err)?;
    files.sort_by_key(|e| e.file_name());
    for e in files {
        let p = e.path();
        let name = e.file_name().to_string_lossy().to_string();
        let meta = fs::symlink_metadata(&p).map_err(err)?;
        if meta.file_type().is_symlink() {
            continue;
        }
        if meta.is_dir() {
            if name.starts_with('.')
                || ["build", "target", "node_modules", "run", "dist"].contains(&name.as_str())
            {
                continue;
            }
            scan(root, &p, entries, depth + 1)?;
        } else {
            let extension = p
                .extension()
                .and_then(|s| s.to_str())
                .unwrap_or("")
                .to_lowercase();
            if [
                "html", "xml", "json", "glsl", "vsh", "fsh", "png", "jpg", "jpeg", "webp", "svg",
                "yml", "yaml",
            ]
            .contains(&extension.as_str())
            {
                entries.push(Entry {
                    path: p
                        .strip_prefix(root)
                        .map_err(err)?
                        .to_string_lossy()
                        .replace('\\', "/"),
                    kind: extension,
                    size: meta.len(),
                });
            }
        }
    }
    Ok(())
}
fn start_engine(app: &tauri::AppHandle) -> Result<Engine, String> {
    let resources = app.path().resource_dir().map_err(err)?;
    let bundled = resources.join("resources");
    let jar = std::env::var_os("DUI_STUDIO_ENGINE")
        .map(PathBuf::from)
        .unwrap_or_else(|| bundled.join("engine.jar"));
    let java = std::env::var_os("DUI_STUDIO_JAVA")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            bundled
                .join("jre/bin")
                .join(if cfg!(windows) { "java.exe" } else { "java" })
        });
    if !jar.is_file() || !java.is_file() {
        return Err(
            "Preview runtime is missing. Run the documented runtime packaging step.".into(),
        );
    }
    let mut cmd = Command::new(java);
    cmd.args([
        "-Xmx768M",
        "--enable-native-access=ALL-UNNAMED",
        "-Djava.awt.headless=true",
    ]);
    if cfg!(target_os = "macos") {
        cmd.arg("-XstartOnFirstThread");
    }
    cmd.arg("-jar")
        .arg(jar)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit());
    let mut child = cmd.spawn().map_err(err)?;
    let input = child.stdin.take().ok_or("Missing input")?;
    let mut output = BufReader::new(child.stdout.take().ok_or("Missing output")?);
    let (send, receive) = mpsc::sync_channel(1);
    std::thread::spawn(move || loop {
        let read = |output: &mut BufReader<_>, maximum: usize| -> Result<Vec<u8>, String> {
            let mut header = [0; 4];
            output.read_exact(&mut header).map_err(err)?;
            let size = u32::from_be_bytes(header) as usize;
            if size > maximum {
                return Err("Preview response exceeds budget".into());
            }
            let mut bytes = vec![0; size];
            output.read_exact(&mut bytes).map_err(err)?;
            Ok(bytes)
        };
        let result = (|| -> Result<(Value, Vec<u8>), String> {
            let response =
                serde_json::from_slice(&read(&mut output, 16 * 1024 * 1024)?).map_err(err)?;
            let image = read(&mut output, 32 * 1024 * 1024)?;
            Ok((response, image))
        })();
        let failed = result.is_err();
        if send.send(result).is_err() || failed {
            break;
        }
    });
    Ok(Engine {
        input,
        output: receive,
        child,
        next: 0,
    })
}
#[tauri::command]
async fn open_project(
    path: String,
    minecraft_jar: Option<String>,
    app: tauri::AppHandle,
    state: State<'_, Mutex<Workspace>>,
) -> Result<Value, String> {
    let root = PathBuf::from(path).canonicalize().map_err(err)?;
    if !root.is_dir() {
        return Err("Select a directory".into());
    }
    let mut entries = vec![];
    scan(&root, &root, &mut entries, 0)?;
    let mut workspace = state.lock().map_err(err)?;
    workspace.engine = None;
    workspace.frame.clear();
    workspace.root = Some(root.clone());
    let mut engine = start_engine(&app)?;
    let (profile, _) = engine.request("open", json!({"root":root,"minecraftJar":minecraft_jar}))?;
    if cfg!(debug_assertions) {
        eprintln!(
            "Studio project ready: {} / {}",
            profile["name"], profile["gpu"]
        );
    }
    workspace.engine = Some(engine);
    Ok(json!({"root":root,"files":entries,"profile":profile}))
}
#[tauri::command]
async fn list_files(state: State<'_, Mutex<Workspace>>) -> Result<Vec<Entry>, String> {
    let w = state.lock().map_err(err)?;
    let root = w.root.as_ref().ok_or("Open a project")?;
    let mut files = vec![];
    scan(root, root, &mut files, 0)?;
    Ok(files)
}
#[tauri::command]
async fn read_file(path: String, state: State<'_, Mutex<Workspace>>) -> Result<Value, String> {
    let w = state.lock().map_err(err)?;
    let root = w.root.as_ref().ok_or("Open a project")?;
    let p = scoped(root, &path, false)?;
    if fs::metadata(&p).map_err(err)?.len() > 2 * 1024 * 1024 {
        return Err("Text file exceeds 2 MiB".into());
    }
    let bytes = fs::read(p).map_err(err)?;
    let text = String::from_utf8(bytes.clone()).map_err(err)?;
    Ok(json!({"path":path,"text":text,"hash":hash(&bytes)}))
}
#[tauri::command]
async fn read_asset(
    path: String,
    state: State<'_, Mutex<Workspace>>,
) -> Result<tauri::ipc::Response, String> {
    let w = state.lock().map_err(err)?;
    let p = scoped(w.root.as_ref().ok_or("Open a project")?, &path, false)?;
    if fs::metadata(&p).map_err(err)?.len() > 32 * 1024 * 1024 {
        return Err("Asset exceeds 32 MiB".into());
    }
    Ok(tauri::ipc::Response::new(fs::read(p).map_err(err)?))
}
#[tauri::command]
async fn save_file(
    path: String,
    text: String,
    expected_hash: Option<String>,
    state: State<'_, Mutex<Workspace>>,
) -> Result<Value, String> {
    let w = state.lock().map_err(err)?;
    write_document(
        w.root.as_ref().ok_or("Open a project")?,
        &path,
        &text,
        expected_hash,
    )
}
fn write_document(
    root: &Path,
    path: &str,
    text: &str,
    expected_hash: Option<String>,
) -> Result<Value, String> {
    if text.len() > 2 * 1024 * 1024 {
        return Err("Text file exceeds 2 MiB".into());
    }
    let p = scoped(root, path, true)?;
    let current = if p.exists() {
        if fs::metadata(&p).map_err(err)?.len() > 2 * 1024 * 1024 {
            return Err("Existing text file exceeds 2 MiB; refusing to overwrite it".into());
        }
        Some(hash(&fs::read(&p).map_err(err)?))
    } else {
        None
    };
    if current != expected_hash {
        return Err("CONFLICT: The file changed outside Studio. Reload before saving.".into());
    }
    fs::create_dir_all(p.parent().ok_or("Missing parent")?).map_err(err)?;
    let mut temporary = tempfile::NamedTempFile::new_in(p.parent().unwrap()).map_err(err)?;
    temporary.write_all(text.as_bytes()).map_err(err)?;
    temporary.as_file().sync_all().map_err(err)?;
    if p.exists() {
        fs::set_permissions(
            temporary.path(),
            fs::metadata(&p).map_err(err)?.permissions(),
        )
        .map_err(err)?;
    }
    temporary.persist(&p).map_err(err)?;
    Ok(json!({"hash":hash(text.as_bytes())}))
}
#[tauri::command]
async fn preview(
    method: String,
    params: Value,
    state: State<'_, Mutex<Workspace>>,
) -> Result<Value, String> {
    if !["render", "fixtures", "describe", "refresh", "live"].contains(&method.as_str()) {
        return Err("Unknown preview operation".into());
    }
    let mut w = state.lock().map_err(err)?;
    let (result, image) = w
        .engine
        .as_mut()
        .ok_or("Open a project")?
        .request(&method, params)?;
    if !image.is_empty() {
        if cfg!(debug_assertions) && w.frame.is_empty() {
            eprintln!(
                "Studio native preview ready: {} x {} / {}",
                result["width"], result["height"], result["backend"]
            );
        }
        w.frame = image;
        w.revision = result["revision"].as_u64().unwrap_or(0);
    }
    Ok(result)
}
#[tauri::command]
fn preview_frame(
    revision: u64,
    state: State<'_, Mutex<Workspace>>,
) -> Result<tauri::ipc::Response, String> {
    let w = state.lock().map_err(err)?;
    if revision != w.revision {
        return Err("Stale preview frame".into());
    }
    Ok(tauri::ipc::Response::new(w.frame.clone()))
}
#[tauri::command]
fn set_document_dirty(value: bool, state: State<'_, AtomicBool>) {
    state.store(value, Ordering::Release);
}
#[tauri::command]
fn startup() -> Value {
    let args: Vec<String> = std::env::args().collect();
    let mut settings = serde_json::Map::new();
    for (flag, key) in [
        ("--project", "project"),
        ("--minecraft-jar", "minecraftJar"),
        ("--document", "document"),
    ] {
        if let Some(index) = args.iter().position(|s| s == flag) {
            if let Some(value) = args.get(index + 1) {
                settings.insert(key.into(), json!(value));
            }
        }
    }
    Value::Object(settings)
}
fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(Mutex::new(Workspace::default()))
        .manage(AtomicBool::new(false))
        .invoke_handler(tauri::generate_handler![
            startup,
            set_document_dirty,
            open_project,
            list_files,
            read_file,
            read_asset,
            save_file,
            preview,
            preview_frame
        ])
        .build(tauri::generate_context!())
        .expect("Desktop initialization failed")
        .run(|app, event| {
            if let tauri::RunEvent::ExitRequested { ref api, .. } = event {
                if app.state::<AtomicBool>().load(Ordering::Acquire) {
                    api.prevent_exit();
                    if let Some(window) = app.get_webview_window("main") {
                        let _ = window.close();
                    }
                }
            }
            if let tauri::RunEvent::Exit = event {
                let state = app.state::<Mutex<Workspace>>();
                if let Ok(mut w) = state.lock() {
                    w.engine = None;
                };
            }
        });
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_traversal_and_symlink_escape() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().canonicalize().unwrap();
        assert!(scoped(&root, "../outside", true).is_err());
        assert!(scoped(&root, "/absolute", true).is_err());
        assert!(scoped(&root, "ui/new.html", true)
            .unwrap()
            .starts_with(&root));
    }
    #[cfg(unix)]
    #[test]
    fn rejects_existing_and_new_symlink_escapes() {
        let dir = tempfile::tempdir().unwrap();
        let outside = tempfile::tempdir().unwrap();
        let root = dir.path().canonicalize().unwrap();
        fs::write(outside.path().join("private.html"), "private").unwrap();
        std::os::unix::fs::symlink(outside.path(), root.join("escape")).unwrap();
        assert!(scoped(&root, "escape/private.html", false).is_err());
        assert!(scoped(&root, "escape/new.html", true).is_err());
    }
    #[test]
    fn save_detects_conflicts_and_preserves_external_edits() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().canonicalize().unwrap();
        let first = write_document(&root, "ui/new.html", "initial", None).unwrap();
        let expected = first["hash"].as_str().unwrap().to_owned();
        assert_eq!(
            fs::read_to_string(root.join("ui/new.html")).unwrap(),
            "initial"
        );
        fs::write(root.join("ui/new.html"), "external edit").unwrap();
        assert!(
            write_document(&root, "ui/new.html", "studio edit", Some(expected))
                .unwrap_err()
                .starts_with("CONFLICT")
        );
        assert_eq!(
            fs::read_to_string(root.join("ui/new.html")).unwrap(),
            "external edit"
        );
        write_document(
            &root,
            "ui/new.html",
            "merged edit",
            Some(hash(b"external edit")),
        )
        .unwrap();
        assert_eq!(
            fs::read_to_string(root.join("ui/new.html")).unwrap(),
            "merged edit"
        );
    }
    #[test]
    fn oversized_external_edit_is_not_overwritten() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().canonicalize().unwrap();
        let document = root.join("menu.html");
        fs::write(&document, vec![b'x'; 2 * 1024 * 1024 + 1]).unwrap();
        assert!(write_document(&root, "menu.html", "replacement", None)
            .unwrap_err()
            .contains("exceeds 2 MiB"));
        assert_eq!(fs::metadata(document).unwrap().len(), 2 * 1024 * 1024 + 1);
    }
    #[test]
    fn scanning_excludes_runtime_media() {
        let dir = tempfile::tempdir().unwrap();
        fs::create_dir_all(dir.path().join("run")).unwrap();
        fs::write(dir.path().join("run/private.json"), "{}").unwrap();
        fs::write(dir.path().join("menu.html"), "<dui-menu/>").unwrap();
        fs::write(dir.path().join("private.mp4"), [1]).unwrap();
        let mut entries = vec![];
        scan(dir.path(), dir.path(), &mut entries, 0).unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].path, "menu.html");
    }
}
