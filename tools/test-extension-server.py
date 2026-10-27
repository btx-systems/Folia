#!/usr/bin/env python3
"""Isolated Folia integration tests. Requires an already accepted Minecraft eula.txt.

No production world is touched. Every run gets a fresh temporary directory. A tiny
localhost-only offline-mode client verifies player login/save/reload without mods.
Packet IDs are read from this checkout's generated protocol definitions.
"""
import argparse
import json
import os
from pathlib import Path
import re
import select
import shutil
import socket
import struct
import subprocess
import tempfile
import time
import uuid

REPO = Path(__file__).resolve().parents[1]
JAVA = REPO / 'gradlew'


def varint(value):
    value &= 0xFFFFFFFF
    result = bytearray()
    while value & ~0x7F:
        result.append((value & 0x7F) | 0x80)
        value >>= 7
    result.append(value)
    return bytes(result)


def string(value):
    encoded = value.encode('utf-8')
    return varint(len(encoded)) + encoded


def take_varint(data, offset=0):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
    raise ValueError('Invalid VarInt')


def exact(sock, count):
    result = bytearray()
    while len(result) < count:
        block = sock.recv(count - len(result))
        if not block:
            raise EOFError('Server closed the connection')
        result.extend(block)
    return bytes(result)


def packet(sock):
    prefix = bytearray()
    for _ in range(5):
        byte = exact(sock, 1)
        prefix.extend(byte)
        if not byte[0] & 0x80:
            length, _ = take_varint(prefix)
            if length > 32 * 1024 * 1024:
                raise ValueError('Oversized packet')
            data = exact(sock, length)
            kind, offset = take_varint(data)
            return kind, data[offset:]
    raise ValueError('Invalid packet framing')


def send(sock, kind, data=b''):
    payload = varint(kind) + data
    sock.sendall(varint(len(payload)) + payload)


def handshake(sock, port, protocol, state):
    send(sock, 0, varint(protocol) + string('localhost') + struct.pack('>H', port) + varint(state))


def protocol_version(port):
    with socket.create_connection(('127.0.0.1', port), timeout=5) as sock:
        handshake(sock, port, -1, 1)
        send(sock, 0)
        kind, data = packet(sock)
        assert kind == 0
        length, start = take_varint(data)
        return json.loads(data[start:start + length])['version']['protocol']


def game_packets(direction):
    source = (REPO / 'folia-server/src/minecraft/java/net/minecraft/network/protocol/game/GameProtocols.java').read_text()
    if direction == 'CLIENTBOUND':
        source = source.split('CLIENTBOUND_TEMPLATE =', 1)[1]
    else:
        source = source.split('SERVERBOUND_TEMPLATE =', 1)[1].split('CLIENTBOUND_TEMPLATE =', 1)[0]
    names = re.findall(r'(?:addPacket|withBundlePacket)\(\s*\w+\.([A-Z_]+)', source)
    return {name: index for index, name in enumerate(names)}


def player_client(port, log, phase):
    protocol = protocol_version(port)
    outbound = game_packets('SERVERBOUND')
    inbound = game_packets('CLIENTBOUND')
    sock = socket.create_connection(('127.0.0.1', port), timeout=5)
    sock.settimeout(10)
    try:
        handshake(sock, port, protocol, 2)
        send(sock, 0, string('ExtensionTest') + uuid.UUID(int=1).bytes)
        state = 'login'
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            contents = log.read_text(errors='replace')
            if 'EXTENSION_SMOKE_FAIL' in contents:
                raise AssertionError(str(log))
            if f'EXTENSION_SMOKE_PASS {phase}' in contents:
                return
            if not select.select([sock], [], [], 0.2)[0]:
                continue
            kind, data = packet(sock)
            if state == 'login':
                if kind == 0:
                    raise AssertionError('Login rejected: ' + data.hex()[:300])
                if kind == 2:
                    send(sock, 3)
                    state = 'configuration'
                elif kind == 3:
                    raise AssertionError('Test server must disable compression')
                elif kind == 5:
                    send(sock, 4, data + b'\0')  # no requested cookie
            elif state == 'configuration':
                if kind == 2:
                    raise AssertionError('Configuration rejected: ' + data.hex()[:300])
                if kind == 15:
                    send(sock, 7, varint(0))  # request complete registries
                elif kind == 3:
                    send(sock, 3)
                    state = 'play'
                elif kind in (4, 5):
                    send(sock, kind, data)
                elif kind == 0:
                    send(sock, 1, data + b'\0')
            elif kind == inbound.get('CLIENTBOUND_PLAYER_POSITION'):
                teleport, start = take_varint(data)
                # 26.3 acknowledges position and rotation as well as the teleport ID.
                # Initial login uses an absolute position; ignore the velocity triple.
                send(sock, outbound['SERVERBOUND_ACCEPT_TELEPORTATION'],
                     varint(teleport) + data[start:start + 24] + data[start + 48:start + 56])
            elif kind == inbound.get('CLIENTBOUND_KEEP_ALIVE'):
                send(sock, outbound['SERVERBOUND_KEEP_ALIVE'], data)
        raise TimeoutError('Player did not join: ' + str(log))
    finally:
        sock.close()


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def run_server(root, phase):
    port = free_port()
    properties = root / 'server.properties'
    properties.write_text('server-ip=127.0.0.1\nserver-port=' + str(port) + '\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\nenforce-secure-profile=false\nlevel-name=world\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-players=20\nnetwork-compression-threshold=-1\n')
    log = root / (phase + '.log')
    env = dict(os.environ)
    env['JAVA_TOOL_OPTIONS'] = f'-Dfolia.extensions.smoke={phase} -Dterminal.jline=false -Dterminal.ansi=false -Dpaper.disablePluginRemapping=true -XX:ActiveProcessorCount=4'
    command = [str(JAVA), '--offline', ':folia-server:runDevServer', f'-Ppaper.runWorkDir={root}', '-Ppaper.runMemoryGb=2', '--console=plain']
    with log.open('w') as output:
        process = subprocess.Popen(command, cwd=REPO, env=env, stdout=output, stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
        try:
            if phase.startswith('player-'):
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise AssertionError('Server exited before login: ' + str(log))
                    if 'Done (' in log.read_text(errors='replace'):
                        break
                    time.sleep(0.2)
                else:
                    raise TimeoutError('Server did not start: ' + str(log))
                player_client(port, log, phase)
            status = process.wait(timeout=120)
        except BaseException:
            if process.poll() is None:
                try:
                    process.communicate(b'stop\n', timeout=70)
                except subprocess.TimeoutExpired:
                    process.terminate()
            raise
    contents = log.read_text(errors='replace')
    assert status == 0 and f'EXTENSION_SMOKE_PASS {phase}' in contents and 'EXTENSION_SMOKE_FAIL' not in contents, str(log)
    print('PASS', root.name, phase, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--eula-file', type=Path, required=True)
    parser.add_argument('--modes', default='packed,encoded,serialized,vanilla,players')
    args = parser.parse_args()
    if 'eula=true' not in args.eula_file.read_text():
        parser.error('Supply an already accepted eula.txt; this harness does not accept terms for you.')
    subprocess.run([str(JAVA), '--offline', ':extension-examples:jar'], cwd=REPO, check=True)
    jars = list((REPO / 'examples/plugin-extensions/build/libs').glob('extension-examples-*.jar'))
    assert len(jars) == 1, 'Expected one example plugin jar'
    base = Path(tempfile.mkdtemp(prefix='folia-extension-tests-'))
    print('Test artifacts:', base, flush=True)
    results = []
    for mode in args.modes.split(','):
        root = base / mode
        (root / 'config').mkdir(parents=True)
        (root / 'plugins').mkdir()
        shutil.copy2(args.eula_file, root / 'eula.txt')
        shutil.copy2(jars[0], root / 'plugins/extension-examples.jar')
        generator = '' if mode == 'packed' else ':' + ('encoded' if mode == 'players' else mode)
        (root / 'bukkit.yml').write_text('settings:\n  allow-end: false\n' + ('' if mode == 'vanilla' else f'worlds:\n  world:\n    generator: ExtensionExamples{generator}\n'))
        if mode != 'vanilla':
            (root / 'config/plugin-storage.yml').write_text('world-id: smoke-world\nworld-provider: example:files\nplayer-provider: example:files\nplayer-scope: smoke-players\n')
        if mode == 'vanilla':
            run_server(root, 'vanilla')
            assert list((root / 'world').rglob('*.mca')) and (root / 'world/level.dat').is_file()
        else:
            write = 'player-write' if mode == 'players' else 'write'
            read = 'player-read' if mode == 'players' else 'read'
            run_server(root, write)
            assert not list((root / 'world').rglob('*.mca')) and not (root / 'world/level.dat').exists()
            assert 'smoke-world' in (root / 'world/.plugin-storage-cache').read_text()
            shutil.rmtree(root / 'world')  # only this harness's disposable cache
            run_server(root, read)
        results.append(mode)
    (base / 'results.json').write_text(json.dumps({'passed': results}, indent=2) + '\n')


if __name__ == '__main__':
    main()
