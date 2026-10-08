import hashlib
import http.client
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import time
import uuid
import zipfile

# Run from repository root after ./gradlew :api:distZip, with JAVA_HOME set to JDK 21.
java_home = Path(os.environ['JAVA_HOME'])
with tempfile.TemporaryDirectory(prefix='sota-https-cli-') as folder:
    folder = Path(folder)
    with zipfile.ZipFile('api/build/distributions/api-0.1.0-mvp.zip') as archive:
        archive.extractall(folder)
    libraries = folder / 'api-0.1.0-mvp' / 'lib'
    java = [str(java_home / 'bin/java'), '-cp', str(libraries / '*'), 'sotaos.api.cli.MainKt']
    env = dict(os.environ, SOTA_P09_TLS_PASSWORD=uuid.uuid4().hex, SOTA_P09_PEER_TOKEN='smoke-only-unknown')
    env.pop('SOTA_P09_TLS_PASSWORD_FILE', None)
    env.pop('SOTA_P09_PEER_TOKEN_FILE', None)
    keystore, certificate, database = folder/'server.p12', folder/'public.pem', folder/'node.db'
    keytool = str(java_home/'bin/keytool')
    subprocess.run([keytool, '-genkeypair', '-alias', 'p09', '-keyalg', 'EC', '-groupname', 'secp256r1',
                    '-validity', '1', '-dname', 'CN=localhost', '-ext', 'SAN=dns:localhost',
                    '-storetype', 'PKCS12', '-keystore', str(keystore),
                    '-storepass:env', 'SOTA_P09_TLS_PASSWORD', '-noprompt'],
                   env=env, check=True, capture_output=True, timeout=30)
    subprocess.run([keytool, '-exportcert', '-rfc', '-alias', 'p09', '-keystore', str(keystore),
                    '-storepass:env', 'SOTA_P09_TLS_PASSWORD', '-file', str(certificate)],
                   env=env, check=True, capture_output=True, timeout=30)
    subprocess.run(java + ['init', '--db', str(database)], env=env, check=True, capture_output=True, timeout=30)
    context = ssl.create_default_context(cafile=str(certificate))
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        port = reservation.getsockname()[1]
    command = java + ['sync', 'run', '--peer', 'smoke-peer', '--endpoint', f'https://localhost:{port}/p09',
                      '--truststore', str(keystore), '--interval-seconds', '1', '--max-backoff-seconds', '2', '--db', str(database), '--node', 'smoke-node', '--actor', 'smoke-person',
                      '--governance-context', 'peer-governance', '--keystore', str(keystore), '--port', str(port)]
    invalid = command.copy()
    invalid[invalid.index('--peer') + 1] = 'smoke-node'
    failed = subprocess.run(invalid, env=env, capture_output=True, timeout=15)
    assert failed.returncode != 0, 'Self-sync configuration must fail startup.'
    for value in ('0', '-1', '3601', 'invalid'):
        failed = subprocess.run(command + ['--status-interval-seconds', value], env=env,
                                capture_output=True, timeout=15)
        assert failed.returncode != 0
        assert b'--status-interval-seconds must be' in failed.stderr
    for mode in ('serve', 'once'):
        failed = subprocess.run(java + ['sync', mode, '--status-interval-seconds', '1'],
                                env=env, capture_output=True, timeout=15)
        assert failed.returncode != 0
        assert b'Status output requires sync run' in failed.stderr
    config = folder/'node.conf'
    flags = command[len(java)+2:]
    settings = {key.removeprefix('--'): value for key, value in zip(flags[::2], flags[1::2])}
    for key in ('db', 'keystore', 'truststore'):
        settings[key] = Path(settings[key]).name
    settings['status-interval-seconds'] = '1'
    config.write_text('version=1\n' + '\n'.join(key+'='+value for key, value in settings.items()))
    configured_command = java + ['sync', 'run', '--config', str(config)]
    for extra in (['--node', 'override'], ['--db', str(database)]):
        failed = subprocess.run(configured_command + extra, env=env, capture_output=True, timeout=15)
        assert failed.returncode != 0
        assert b'cannot be combined' in failed.stderr
    invalid_config = folder/'invalid.conf'
    invalid_config.write_text(config.read_text() + '\ntoken=fixture-secret-notallowed')
    failed = subprocess.run(java + ['sync', 'run', '--config', str(invalid_config)],
                            env=env, capture_output=True, timeout=15)
    assert failed.returncode != 0
    assert b'fixture-secret-notallowed' not in failed.stdout + failed.stderr
    json_config = folder/'metrics.conf'
    json_config.write_text(config.read_text() + '\nmetrics-format=json')
    for extra in (['--metrics-format', 'json'], ['--metrics-format', 'invalid', '--status-interval-seconds', '1']):
        failed = subprocess.run(command + extra, env=env, capture_output=True, timeout=15)
        assert failed.returncode != 0
        assert b'--metrics-format json requires' in failed.stderr
    password_file = folder/'tls.password'
    token_file = folder/'peer.token'
    password_file.write_text(env['SOTA_P09_TLS_PASSWORD'] + '\n')
    token_file.write_text(env['SOTA_P09_PEER_TOKEN'] + '\n')
    password_file.chmod(0o600)
    token_file.chmod(0o600)
    file_env = dict(env)
    del file_env['SOTA_P09_TLS_PASSWORD']
    del file_env['SOTA_P09_PEER_TOKEN']
    file_env.update(SOTA_P09_TLS_PASSWORD_FILE=str(password_file), SOTA_P09_PEER_TOKEN_FILE=str(token_file))
    before = hashlib.sha256(database.read_bytes()).digest()
    check_command = java + ['sync', 'check', '--config', str(config)]
    with socket.socket() as occupied:
        occupied.bind(('127.0.0.1', port))
        checked = subprocess.run(check_command, env=file_env, capture_output=True, timeout=15)
        assert checked.returncode == 0, 'Preflight must not bind the occupied listener port'
    assert hashlib.sha256(database.read_bytes()).digest() == before
    ambiguous = dict(file_env, SOTA_P09_PEER_TOKEN='must-not-fallback')
    failed = subprocess.run(check_command, env=ambiguous, capture_output=True, timeout=15)
    assert failed.returncode != 0
    assert b'must-not-fallback' not in failed.stdout + failed.stderr
    assert hashlib.sha256(database.read_bytes()).digest() == before
    reload_config = folder/'reload.conf'
    reload_base = json_config.read_text() + '\nreload-config=true'
    reload_config.write_text(reload_base)
    for interval in (None, '1', '3600', 'config', 'json', 'files', 'reload'):
        configured = (configured_command if interval == 'config' else
                      command + (['--status-interval-seconds', interval] if interval else []))
        if interval == 'files':
            configured = configured_command
        if interval == 'json':
            configured = java + ['sync', 'run', '--config', str(json_config)]
        if interval == 'reload':
            configured = java + ['sync', 'run', '--config', str(reload_config)]
        with (folder/'host.log').open('w') as log, (folder/'host.err').open('w') as errors:
            process = subprocess.Popen(configured, env=file_env if interval == 'files' else env,
                                       stdout=log, stderr=errors)
            try:
                deadline = time.monotonic() + 30
                while True:
                    if process.poll() is not None:
                        raise RuntimeError('Host stopped before readiness: ' + (folder/'host.log').read_text())
                    connection = http.client.HTTPSConnection('localhost', port, context=context, timeout=2)
                    try:
                        connection.request('POST', '/p09', '{}', {'Authorization': 'Bearer unknown-smoke-token',
                                                               'Content-Type': 'application/json'})
                        response = connection.getresponse()
                        assert response.status == 401, response.status
                        assert response.read() == b'Request rejected'
                        break
                    except OSError:
                        if time.monotonic() >= deadline:
                            raise
                        time.sleep(0.1)
                    finally:
                        connection.close()
                connection = http.client.HTTPSConnection('localhost', port, context=context, timeout=2)
                try:
                    connection.request('GET', '/p09')
                    response = connection.getresponse()
                    assert response.status == 405, response.status
                    response.read()
                finally:
                    connection.close()
                if interval:
                    deadline = time.monotonic() + 10
                    while True:
                        output = (folder/'host.log').read_text()
                        ready = ('phase=BACKOFF' in output if interval in ('1', 'config', 'files') else 'P09 status:' in output)
                        if interval in ('json', 'reload'):
                            ready = output.count('\n') >= 2 and '"405":1' in output
                        if ready:
                            break
                        assert time.monotonic() < deadline, 'Missing periodic status'
                        time.sleep(0.1)
                if interval == 'reload':
                    def replace_and_wait(content, message):
                        staged = folder/'reload.new'
                        staged.write_text(content)
                        staged.replace(reload_config)
                        deadline = time.monotonic() + 10
                        while message not in (folder/'host.err').read_text():
                            assert process.poll() is None, 'Reload stopped the host'
                            assert time.monotonic() < deadline, 'Reload result not observed'
                            time.sleep(0.1)
                    replace_and_wait(reload_base.replace('node=smoke-node', 'node=changed-node')
                                     .replace('\ninterval-seconds=1', '\ninterval-seconds=3'),
                                     'configuration reload rejected')
                    replace_and_wait(reload_base.replace('\ninterval-seconds=1', '\ninterval-seconds=3')
                                     .replace('max-backoff-seconds=2', 'max-backoff-seconds=4'),
                                     'scheduling configuration applied')
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                    raise
                assert process.returncode in (-15, 143, 0), process.returncode
                output = (folder/'host.log').read_text()
                errors = (folder/'host.err').read_text()
                assert 'did not' not in output + errors
                assert env['SOTA_P09_PEER_TOKEN'] not in output + errors
                assert env['SOTA_P09_TLS_PASSWORD'] not in output + errors
                if interval in ('json', 'reload'):
                    samples = [json.loads(line) for line in output.splitlines()]
                    assert len(samples) >= 2
                    final = samples[-1]
                    assert final['version'] == 1
                    assert final['inbound']['inFlight'] == 0
                    assert final['inbound']['responses']['401'] >= 1
                    assert final['inbound']['responses']['405'] == 1
                    assert final['outbound']['phases']['STOPPED'] == 1
                    assert 'smoke-peer' not in output
                elif interval:
                    assert 'phase=STOPPED' in output
                    assert output.strip().endswith('nextAttempt=NONE')
                    assert 'sent=UNKNOWN received=UNKNOWN' in output
                    assert 'successes=0' in output
                    assert 'failedTotal=' in output
                    assert 'durationNanosTotal=' in output
                else:
                    assert 'P09 status:' not in output + errors
    import sqlite3
    with sqlite3.connect(database) as db:
        assert db.execute('SELECT count(*) FROM sync_journal').fetchone()[0] == 0
    print('CLI status smoke passed: validation, periodic output, secrets excluded, SIGTERM, restart, default quiet mode, configuration file, inbound JSON metrics, file secrets, read-only preflight, live scheduling reload.')
