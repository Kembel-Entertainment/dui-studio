"""Exercise the shipped engine over its real binary protocol, including the GPU context."""
import argparse
import json
import os
import pathlib
import struct
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--project', type=pathlib.Path, default=ROOT / 'examples/starter')
    parser.add_argument('--minecraft-jar', type=pathlib.Path)
    parser.add_argument('--all', action='store_true')
    args = parser.parse_args()
    java = ROOT / 'src-tauri/resources/jre/bin' / ('java.exe' if os.name == 'nt' else 'java')
    command = [str(java), '--enable-native-access=ALL-UNNAMED', '-Djava.awt.headless=true']
    if sys.platform == 'darwin': command += ['-XstartOnFirstThread']
    command += ['-jar', str(ROOT / 'src-tauri/resources/engine.jar')]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    counter = 0
    def request(method, params):
        nonlocal counter
        counter += 1
        process.stdin.write((json.dumps(dict(id=counter, method=method, params=params)) + '\n').encode())
        process.stdin.flush()
        def read():
            header = process.stdout.read(4)
            assert len(header) == 4, 'Engine stopped before responding'
            size = struct.unpack('>I', header)[0]
            data = process.stdout.read(size)
            assert len(data) == size
            return data
        response, image = json.loads(read()), read()
        assert response.get('id') == counter, response
        assert 'error' not in response, response
        return response['result'], image
    try:
        profile, _ = request('open', dict(root=str(args.project.resolve()), minecraftJar=str(args.minecraft_jar.resolve()) if args.minecraft_jar else None))
        print('GPU:', profile['gpu'])
        output = ROOT / 'build/preview-checks'
        output.mkdir(parents=True, exist_ok=True)
        documents = list(args.project.rglob('*.html')) if args.all else [args.project / 'ui/welcome.html']
        documents += list(args.project.rglob('*.map.json')) if args.all else []
        total = 0
        for document in documents:
            if any(part in {'build', 'run', '.git'} for part in document.parts): continue
            relative = str(document.relative_to(args.project))
            fixtures, _ = request('fixtures', dict(document=relative))
            for fixture in fixtures:
                meta, image = request('render', dict(document=relative, source=document.read_text(), fixture=fixture['id'], seconds=1.5, width=960, height=720, playing=True))
                assert image.startswith(b'\x89PNG\r\n\x1a\n'), relative
                (output / (document.stem + '-' + fixture['id'] + '.png')).write_bytes(image)
                (output / (document.stem + '-' + fixture['id'] + '.json')).write_text(json.dumps(meta, indent=2))
                warnings = meta['warnings']
                if args.minecraft_jar is None:
                    warnings = [w for w in warnings if w != 'Select a local Minecraft 26.2 JAR for native font and item fidelity.']
                assert not warnings, (relative, fixture['id'], warnings)
                total += 1
                print(relative, fixture['id'], meta['width'], meta['height'])
        for binding in profile['schema'].get('sources', []):
            document = args.project / binding['previewDocument']
            relative = str(document.relative_to(args.project))
            fixtures, _ = request('fixtures', dict(document=relative))
            fixture = next((f for f in fixtures if f['id'].endswith('revealed')), fixtures[0]) if binding['id'].endswith('gift-confetti') else fixtures[0]
            params = dict(document=relative, source=document.read_text(), fixture=fixture['id'], seconds=1.5, width=960, height=720, playing=True)
            good, _ = request('render', params)
            assert not good['warnings'], good['warnings']
            bad, _ = request('render', dict(params, sourceOverrides={binding['path']:'#error STUDIO_COMPILE_CHECK'}))
            assert bad['warnings'] and any('STUDIO_COMPILE_CHECK' in w for w in bad['warnings']), bad
            restored, _ = request('render', params)
            assert not restored['warnings'], restored['warnings']
            print('Shader source override / compile diagnostics / recovery:', binding['path'])
        print('Native preview fixtures passed:', total)
    finally:
        process.stdin.close()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired: process.kill(); process.wait()

if __name__ == '__main__': main()
