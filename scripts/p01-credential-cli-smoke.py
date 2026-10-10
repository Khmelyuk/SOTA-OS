"""P01 local credential CLI lifecycle. Temporary database; run after :api:distZip."""
import os
from pathlib import Path
import pty
import select
import signal
import sqlite3
import tempfile
import time
import zipfile

secrets = ('initial-fixture-passphrase', 'replacement-fixture-passphrase', 'wrong-fixture-passphrase')


def terminal(command, replies, expected_success, environment):
    pid, fd = pty.fork()
    if pid == 0:
        os.execvpe(command[0], command, environment)
    output = b''
    transcript = b''
    pending = list(replies)
    deadline = time.monotonic() + 60
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
        assert all(secret.encode() not in transcript for secret in secrets)
        assert not pending, 'CLI exited before expected prompt'
        assert (os.waitstatus_to_exitcode(status) == 0) == expected_success, 'Unexpected CLI exit status'
    finally:
        os.close(fd)
        if status is None:
            os.kill(pid, signal.SIGKILL)
            os.waitpid(pid, 0)


with tempfile.TemporaryDirectory(prefix='p01-cli-') as directory:
    folder = Path(directory)
    with zipfile.ZipFile('api/build/distributions/api-0.1.0-mvp.zip') as archive:
        archive.extractall(folder)
    java = [str(Path(os.environ['JAVA_HOME'])/'bin/java'), '-cp', str(folder/'api-0.1.0-mvp/lib/*'),
            'sotaos.api.cli.MainKt']
    database = folder/'node.db'
    environment = dict(os.environ)
    old, new, wrong = secrets
    def command(operation):
        return java + ['auth', operation, '--handle', 'owner', '--db', str(database)]
    def replacement(value):
        return [('Нова парольна фраза (12–1024 символи): ', value), ('Повторіть парольну фразу: ', value)]
    proof = 'Локальна парольна фраза: '
    confirm = ('> ', 'ВІДКЛИКАТИ owner')
    terminal(command('create'), replacement(old), True, environment)
    terminal(command('rotate') + ['--password', wrong], [], False, environment)
    terminal(command('rotate'), [(proof, wrong)] + replacement(new), False, environment)
    terminal(command('rotate'), [(proof, old)] + replacement(new), True, environment)
    terminal(command('revoke'), [confirm, (proof, old)], False, environment)
    terminal(command('revoke'), [('> ', 'CANCEL')], False, environment)
    terminal(command('revoke'), [confirm, (proof, new)], True, environment)
    terminal(command('revoke'), [confirm, (proof, new)], False, environment)
    with sqlite3.connect(database) as db:
        revision, revoked = db.execute('SELECT revision, revoked_at FROM local_credential').fetchone()
        assert revision == 3 and revoked is not None
        assert db.execute('SELECT COUNT(*) FROM person').fetchone()[0] == 1
        assert db.execute('SELECT COUNT(*) FROM identity').fetchone()[0] == 1
        assert db.execute('SELECT COUNT(*) FROM authentication_binding').fetchone()[0] == 1
        assert [row[0] for row in db.execute('SELECT operation FROM local_credential_audit ORDER BY audit_id')] == [
            'ENROLL', 'ROTATE', 'REVOKE']
    print('P01 CLI smoke passed: rotate, old-proof denial, confirmation, revoke, restart, secret exclusion, Person isolation.')
