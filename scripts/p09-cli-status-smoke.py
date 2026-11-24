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
    env.pop('SOTA_P09_CANDIDATE_TLS_PASSWORD', None)
    env.pop('SOTA_P09_CANDIDATE_TLS_PASSWORD_FILE', None)
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
    certificate_prefix = 'P09 TLS certificate '
    diagnostics = [json.loads(line[len(certificate_prefix):]) for line in checked.stdout.decode().splitlines()
                   if line.startswith(certificate_prefix)]
    assert len(diagnostics) == 1
    diagnostic = diagnostics[0]
    assert diagnostic['alias'] == 'p09'
    assert diagnostic['sha256'] == hashlib.sha256(ssl.PEM_cert_to_DER_cert(certificate.read_text())).hexdigest()
    assert diagnostic['state'] == 'RENEW_SOON'
    assert 0 < diagnostic['secondsRemaining'] <= 86400
    assert all(diagnostic[field].endswith('Z') for field in ('notBefore', 'notAfter', 'observedAt'))
    assert b'P09 TLS renewal:' in checked.stdout
    assert env['SOTA_P09_TLS_PASSWORD'].encode() not in checked.stdout + checked.stderr
    assert env['SOTA_P09_PEER_TOKEN'].encode() not in checked.stdout + checked.stderr
    compare_command = java + ['sync', 'compare', '--config', str(config), '--candidate', str(config)]
    with socket.socket() as occupied:
        occupied.bind(('127.0.0.1', port))
        compared = subprocess.run(compare_command, env=file_env, capture_output=True, timeout=15)
        assert compared.returncode == 0, 'Comparison must not bind the occupied listener port'
    comparison_prefix = 'P09 TLS comparison '
    comparison = json.loads(compared.stdout.decode().splitlines()[0][len(comparison_prefix):])
    for field in ('serverLeaves', 'clientTrust'):
        assert comparison[field] == {'added': [], 'removed': [], 'retained': [diagnostic['sha256']]}
    assert comparison['currentRecovery'] == 'LOCAL_MATERIAL_VALID'
    replacement = folder/'replacement.p12'
    replacement_pem = folder/'replacement.pem'
    subprocess.run([keytool, '-genkeypair', '-alias', 'replacement', '-keyalg', 'EC', '-groupname', 'secp256r1',
                    '-validity', '2', '-dname', 'CN=localhost', '-ext', 'SAN=dns:localhost',
                    '-storetype', 'PKCS12', '-keystore', str(replacement),
                    '-storepass:env', 'SOTA_P09_TLS_PASSWORD', '-noprompt'],
                   env=env, check=True, capture_output=True, timeout=30)
    subprocess.run([keytool, '-exportcert', '-rfc', '-alias', 'replacement', '-keystore', str(replacement),
                    '-storepass:env', 'SOTA_P09_TLS_PASSWORD', '-file', str(replacement_pem)],
                   env=env, check=True, capture_output=True, timeout=30)
    staged_config = folder/'staged.conf'
    staged_settings = dict(settings, keystore=replacement.name, truststore=replacement.name)
    staged_config.write_text('version=1\n' + '\n'.join(key+'='+value for key, value in staged_settings.items()))
    replacement_hash = hashlib.sha256(ssl.PEM_cert_to_DER_cert(replacement_pem.read_text())).hexdigest()
    compared_paths = (database, keystore, replacement, config, staged_config, password_file, token_file)
    snapshots = {path: hashlib.sha256(path.read_bytes()).digest() for path in compared_paths}
    compared = subprocess.run(compare_command[:-1] + [str(staged_config)], env=file_env,
                              capture_output=True, timeout=15)
    assert compared.returncode == 0
    comparison = json.loads(compared.stdout.decode().splitlines()[0][len(comparison_prefix):])
    for field in ('serverLeaves', 'clientTrust'):
        assert comparison[field] == {'added': [replacement_hash], 'removed': [diagnostic['sha256']], 'retained': []}
    assert comparison['candidate']['serverLeaves'][0]['sha256'] == replacement_hash
    assert env['SOTA_P09_TLS_PASSWORD'].encode() not in compared.stdout + compared.stderr
    assert env['SOTA_P09_PEER_TOKEN'].encode() not in compared.stdout + compared.stderr
    assert {path: hashlib.sha256(path.read_bytes()).digest() for path in compared_paths} == snapshots
    reencrypted = folder/'reencrypted.p12'
    candidate_password = uuid.uuid4().hex
    independent_env = dict(env, SOTA_P09_CANDIDATE_TLS_PASSWORD=candidate_password)
    subprocess.run([keytool, '-importkeystore', '-srckeystore', str(keystore), '-srcstoretype', 'PKCS12',
                    '-srcstorepass:env', 'SOTA_P09_TLS_PASSWORD', '-srcalias', 'p09',
                    '-destkeystore', str(reencrypted), '-deststoretype', 'PKCS12',
                    '-deststorepass:env', 'SOTA_P09_CANDIDATE_TLS_PASSWORD',
                    '-destkeypass:env', 'SOTA_P09_CANDIDATE_TLS_PASSWORD', '-noprompt'],
                   env=independent_env, check=True, capture_output=True, timeout=30)
    password_config = folder/'password-change.conf'
    password_settings = dict(settings, keystore=reencrypted.name, truststore=reencrypted.name)
    password_config.write_text('version=1\n' + '\n'.join(key+'='+value for key, value in password_settings.items()))
    candidate_password_file = folder/'candidate.password'
    candidate_password_file.write_text(candidate_password + '\r\n')
    candidate_password_file.chmod(0o600)
    independent_files = dict(file_env, SOTA_P09_CANDIDATE_TLS_PASSWORD_FILE=str(candidate_password_file))
    password_paths = compared_paths + (reencrypted, password_config, candidate_password_file)
    password_snapshot = {path: hashlib.sha256(path.read_bytes()).digest() for path in password_paths}
    password_command = compare_command[:-1] + [str(password_config)]
    for selected_env in (independent_env, independent_files):
        with socket.socket() as occupied:
            occupied.bind(('127.0.0.1', port))
            compared = subprocess.run(password_command, env=selected_env, capture_output=True, timeout=15)
        assert compared.returncode == 0
        comparison = json.loads(compared.stdout.decode().splitlines()[0][len(comparison_prefix):])
        for field in ('serverLeaves', 'clientTrust'):
            assert comparison[field] == {'added': [], 'removed': [], 'retained': [diagnostic['sha256']]}
        for secret in (env['SOTA_P09_TLS_PASSWORD'], candidate_password, env['SOTA_P09_PEER_TOKEN']):
            assert secret.encode() not in compared.stdout + compared.stderr
    invalid_password_cases = [
        (password_command, file_env),
        (password_command, dict(env, SOTA_P09_CANDIDATE_TLS_PASSWORD='wrong-candidate-fixture')),
        (password_command, dict(independent_env, SOTA_P09_TLS_PASSWORD='wrong-current-fixture')),
        (compare_command, dict(file_env, SOTA_P09_CANDIDATE_TLS_PASSWORD='')),
        (compare_command, dict(independent_files, SOTA_P09_CANDIDATE_TLS_PASSWORD=env['SOTA_P09_TLS_PASSWORD'])),
        (compare_command, dict(file_env, SOTA_P09_CANDIDATE_TLS_PASSWORD_FILE=str(folder/'missing.password'))),
    ]
    for selected_command, selected_env in invalid_password_cases:
        rejected = subprocess.run(selected_command, env=selected_env, capture_output=True, timeout=15)
        assert rejected.returncode != 0 and comparison_prefix.encode() not in rejected.stdout
        for name in ('SOTA_P09_TLS_PASSWORD', 'SOTA_P09_CANDIDATE_TLS_PASSWORD', 'SOTA_P09_PEER_TOKEN'):
            if selected_env.get(name):
                assert selected_env[name].encode() not in rejected.stdout + rejected.stderr
    # Activation/recovery keep the ordinary runtime source names; candidate variables apply only to compare.
    candidate_check = java + ['sync', 'check', '--config', str(password_config)]
    rejected = subprocess.run(candidate_check, env=independent_files, capture_output=True, timeout=15)
    assert rejected.returncode != 0
    activated_env = dict(file_env, SOTA_P09_TLS_PASSWORD_FILE=str(candidate_password_file))
    for selected_command, selected_env in ((candidate_check, activated_env), (check_command, file_env)):
        checked_password = subprocess.run(selected_command, env=selected_env, capture_output=True, timeout=15)
        assert checked_password.returncode == 0
        assert candidate_password.encode() not in checked_password.stdout + checked_password.stderr
    assert {path: hashlib.sha256(path.read_bytes()).digest() for path in password_paths} == password_snapshot
    drift_config = folder/'drift.conf'
    drift_config.write_text(staged_config.read_text().replace('node=smoke-node', 'node=another-node'))
    for command_suffix in ([str(drift_config)], [str(staged_config), '--db', str(database)]):
        rejected = subprocess.run(compare_command[:-1] + command_suffix, env=file_env,
                                  capture_output=True, timeout=15)
        assert rejected.returncode != 0 and comparison_prefix.encode() not in rejected.stdout
    for state, start in (('EXPIRED', '-2d'), ('NOT_YET_VALID', '+1d')):
        invalid_store = folder / (state.lower() + '.p12')
        subprocess.run([keytool, '-genkeypair', '-alias', 'p09', '-keyalg', 'EC', '-groupname', 'secp256r1',
                        '-validity', '1', '-startdate', start, '-dname', 'CN=localhost',
                        '-storetype', 'PKCS12', '-keystore', str(invalid_store),
                        '-storepass:env', 'SOTA_P09_TLS_PASSWORD', '-noprompt'],
                       env=env, check=True, capture_output=True, timeout=30)
        invalid_settings = dict(settings, keystore=invalid_store.name)
        invalid_dates = folder / (state.lower() + '.conf')
        invalid_dates.write_text('version=1\n' + '\n'.join(key+'='+value for key, value in invalid_settings.items()))
        rejected = subprocess.run(java + ['sync', 'check', '--config', str(invalid_dates)],
                                  env=file_env, capture_output=True, timeout=15)
        assert rejected.returncode != 0
        rows = [json.loads(line[len(certificate_prefix):]) for line in rejected.stdout.decode().splitlines()
                if line.startswith(certificate_prefix)]
        assert len(rows) == 1 and rows[0]['state'] == state
        assert b'P09 local configuration' not in rejected.stdout
        assert b'P09 TLS renewal:' not in rejected.stdout
        assert hashlib.sha256(database.read_bytes()).digest() == before
        for current_path, candidate_path in ((config, invalid_dates), (invalid_dates, config)):
            rejected = subprocess.run(java + ['sync', 'compare', '--config', str(current_path),
                                             '--candidate', str(candidate_path)],
                                      env=file_env, capture_output=True, timeout=15)
            assert rejected.returncode != 0 and comparison_prefix.encode() not in rejected.stdout
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
    print('CLI status smoke passed: validation, periodic output, secrets excluded, SIGTERM, restart, default quiet mode, configuration file, inbound JSON metrics, file secrets, read-only certificate diagnostics and bundle comparison, independent TLS passwords, invalid certificate denial, live scheduling reload.')
