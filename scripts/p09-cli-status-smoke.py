import http.client
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
    for interval in (None, '1', '3600'):
        configured = command + (['--status-interval-seconds', interval] if interval else [])
        with (folder/'host.log').open('w') as log:
            process = subprocess.Popen(configured, env=env, stdout=log, stderr=subprocess.STDOUT)
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
                        ready = ('phase=BACKOFF' in output if interval == '1' else 'P09 status:' in output)
                        if ready:
                            break
                        assert time.monotonic() < deadline, 'Missing periodic status'
                        time.sleep(0.1)
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
                assert 'did not' not in output
                assert env['SOTA_P09_PEER_TOKEN'] not in output
                assert env['SOTA_P09_TLS_PASSWORD'] not in output
                if interval:
                    assert 'phase=STOPPED' in output
                    assert output.strip().endswith('nextAttempt=NONE')
                    assert 'sent=UNKNOWN received=UNKNOWN' in output
                else:
                    assert 'P09 status:' not in output
    import sqlite3
    with sqlite3.connect(database) as db:
        assert db.execute('SELECT count(*) FROM sync_journal').fetchone()[0] == 0
    print('CLI status smoke passed: validation, periodic output, secrets excluded, SIGTERM, restart, default quiet mode.')
