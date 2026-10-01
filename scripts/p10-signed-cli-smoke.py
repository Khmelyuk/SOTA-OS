"""Linux/JDK 21 smoke: temporary fixtures only. Run from repo root after :api:distZip."""
import base64
import hashlib
import json
import os
from pathlib import Path
import pty
import select
import signal
import sqlite3
import subprocess
import tempfile
import time
import uuid
import zipfile


def terminal(command, replies, expected_success, environment):
    pid, fd = pty.fork()
    if pid == 0:
        os.execvpe(command[0], command, environment)
    output = b''
    transcript = b''
    pending = list(replies)
    deadline = time.monotonic() + 30
    status = None
    try:
        while status is None:
            assert time.monotonic() < deadline, 'CLI timed out'
            if select.select([fd], [], [], 0.1)[0]:
                try:
                    chunk = os.read(fd, 65536)
                except OSError:
                    chunk = b''
                output += chunk
                transcript += chunk
                if pending and pending[0][0].encode() in output:
                    prompt, answer = pending.pop(0)
                    output = output.split(prompt.encode(), 1)[1]
                    os.write(fd, (answer + '\n').encode())
            ended, result = os.waitpid(pid, os.WNOHANG)
            if ended:
                status = result
        assert environment['SOTA_P10_SIGNING_PASSWORD'].encode() not in transcript
        assert b'local-fixture-passphrase' not in transcript
        assert not pending, 'CLI exited before expected prompt'
        assert (os.waitstatus_to_exitcode(status) == 0) == expected_success, 'Unexpected CLI exit status'
    finally:
        os.close(fd)
        if status is None:
            os.kill(pid, signal.SIGKILL)
            os.waitpid(pid, 0)


java_home = Path(os.environ['JAVA_HOME'])
with tempfile.TemporaryDirectory(prefix='p10-signed-cli-') as folder:
    folder = Path(folder)
    with zipfile.ZipFile('api/build/distributions/api-0.1.0-mvp.zip') as archive:
        archive.extractall(folder)
    java = [str(java_home/'bin/java'), '-cp', str(folder/'api-0.1.0-mvp/lib/*'), 'sotaos.api.cli.MainKt']
    password = 'fixture-' + uuid.uuid4().hex
    environment = dict(os.environ, SOTA_P10_SIGNING_PASSWORD=password)
    database = folder/'node.db'
    keystore = folder/'signing.p12'
    passphrase = 'local-fixture-passphrase'
    auth = [('Локальна парольна фраза: ', passphrase)]
    confirmation = auth + [('> ', 'ВИХОДЖУ З CORE chosen')]
    terminal(java + ['auth', 'create', '--handle', 'smoke', '--db', str(database)],
             [('Нова парольна фраза (12–1024 символи): ', passphrase),
              ('Повторіть парольну фразу: ', passphrase)], True, environment)
    for alias in ('exit', 'wrong'):
        subprocess.run([str(java_home/'bin/keytool'), '-genkeypair', '-alias', alias,
                        '-keyalg', 'Ed25519', '-dname', 'CN=fixture', '-validity', '1',
                        '-storetype', 'PKCS12', '-keystore', str(keystore),
                        '-storepass:env', 'SOTA_P10_SIGNING_PASSWORD', '-noprompt'],
                       env=environment, check=True, capture_output=True, timeout=90)
    certificate = subprocess.run([str(java_home/'bin/keytool'), '-exportcert', '-alias', 'exit',
                                  '-keystore', str(keystore), '-storepass:env', 'SOTA_P10_SIGNING_PASSWORD'],
                                 env=environment, check=True, capture_output=True, timeout=90).stdout
    pem = subprocess.run(['openssl', 'x509', '-inform', 'DER', '-pubkey', '-noout'],
                         input=certificate, check=True, capture_output=True, timeout=10).stdout
    public_key = b''.join(pem.splitlines()[1:-1]).decode()
    with sqlite3.connect(database) as db:
        person = db.execute('select person_id from person').fetchone()[0]
        for core in ('chosen', 'other'):
            db.execute('insert into core values (?,?,?,?)', (core, core, '2026-09-26T00:00:00Z', 'ACTIVE'))
            db.execute('insert into membership values (?,?,?,?,?,?,?,?)',
                       ('membership-'+core, person, 'CORE', core, None, '2026-09-26T00:00:00Z', None, 'ACTIVE'))
        db.commit()
        destination = folder/'archive.json'
        local = java + ['exit', 'leave', '--handle', 'smoke', '--core', 'chosen',
                        '--out', str(destination), '--db', str(database)]
        signed = local + ['--signing-keystore', str(keystore), '--signing-alias', 'exit',
                          '--node', 'source', '--governance-context', 'governance']
        terminal(local + ['--node', 'source'], [], False, environment)
        no_database = signed.copy()
        index = no_database.index('--db')
        del no_database[index:index+2]
        terminal(no_database, [], False, environment)
        # Missing actor key: no implicit enrollment and no exit mutation.
        terminal(signed, auth, False, environment)
        assert db.execute('select count(*) from exit_process').fetchone()[0] == 0
        # Trusted test bootstrap only. Real deployments use governed key provisioning.
        db.execute('insert into actor_signing_key values (?,?,?,?)', ('PERSON', person, 'exit-key', public_key))
        db.commit()
        wrong = signed.copy()
        wrong[wrong.index('--signing-alias') + 1] = 'wrong'
        terminal(wrong, auth, False, environment)
        bad_password = dict(environment, SOTA_P10_SIGNING_PASSWORD='incorrect-fixture-password')
        terminal(signed, auth, False, bad_password)
        terminal(signed, auth + [('> ', 'CANCEL')], False, environment)
        assert db.execute('select count(*) from exit_process').fetchone()[0] == 0
        # A delivery conflict leaves a resumable signed exit without terminating membership.
        destination.write_text('do not overwrite')
        terminal(signed, confirmation, False, environment)
        assert destination.read_text() == 'do not overwrite'
        assert db.execute('select stage from exit_process').fetchone()[0] == 'EXPORT_READY'
        assert db.execute('select count(*) from sync_journal').fetchone()[0] == 5
        destination.unlink()
        changed_origin = signed.copy()
        changed_origin[changed_origin.index('--node') + 1] = 'other-node'
        terminal(changed_origin, confirmation, False, environment)
        assert db.execute('select stage from exit_process').fetchone()[0] == 'EXPORT_READY'
        if destination.exists():
            destination.unlink()
        terminal(local, confirmation, False, environment)
        assert db.execute('select stage from exit_process').fetchone()[0] == 'EXPORT_READY'
        if destination.exists():
            destination.unlink()
        terminal(signed, confirmation, True, environment)
        assert dict(db.execute('select collective_id,state from membership')) == {'chosen': 'REVOKED', 'other': 'ACTIVE'}
        assert db.execute('select stage from exit_process').fetchone()[0] == 'COMPLETED'
        assert db.execute('select count(*) from sync_journal').fetchone()[0] == 6
        records = [json.loads(row[0]) for row in db.execute('select record_json from sync_journal order by position')]
        for index, record in enumerate(records):
            assert record['origin'] == 'source'
            assert record['parents'] == ([] if index == 0 else [records[index-1]['event']['id']])
            assert record['assertion'] is None
        signatures = list(db.execute('select content_hash,signature from event where signature is not null'))
        assert len(signatures) == 6
        public_path = folder/'public.pem'
        public_path.write_bytes(pem)
        for digest, signature in signatures:
            message = folder/'hash.txt'
            signature_file = folder/'signature.bin'
            message.write_text(digest)
            encoded = signature.removeprefix('ed25519:')
            signature_file.write_bytes(base64.b64decode(encoded + '=' * (-len(encoded) % 4)))
            subprocess.run(['openssl', 'pkeyutl', '-verify', '-pubin', '-inkey', str(public_path),
                            '-rawin', '-in', str(message), '-sigfile', str(signature_file)],
                           check=True, capture_output=True, timeout=10)
        data = destination.read_bytes()
        assert db.execute('select sha256 from exit_archive').fetchone()[0] == hashlib.sha256(data).hexdigest()
        assert destination.stat().st_mode & 0o777 == 0o600
        assert db.execute('select count(*) from local_credential').fetchone()[0] == 1
print('Signed P10 CLI smoke passed: actor binding, confirmation, delivery failure, resume, mode/origin guards, six signatures.')
