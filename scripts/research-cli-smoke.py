"""Research CLI: real terminal, provider authentication, SQLite and local journal. No external requests."""
import hashlib
import json
import os
from pathlib import Path
import pty
import re
import select
import signal
import sqlite3
import tempfile
import time
import zipfile

PASSPHRASE = 'research-fixture-passphrase'
PROOF = 'Локальна парольна фраза: '


def terminal(command, replies, success=True):
    pid, fd = pty.fork()
    if pid == 0:
        os.execvpe(command[0], command, dict(os.environ))
    output, transcript = b'', b''
    pending = list(replies)
    deadline = time.monotonic() + 90
    status = None
    try:
        while status is None:
            assert time.monotonic() < deadline, 'Research CLI timed out'
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
        assert PASSPHRASE.encode() not in transcript, 'Proof leaked to terminal'
        assert not pending, 'CLI exited before expected prompt'
        assert (os.waitstatus_to_exitcode(status) == 0) == success, 'Unexpected CLI exit status'
        return transcript.decode(errors='replace')
    finally:
        os.close(fd)
        if status is None:
            os.kill(pid, signal.SIGKILL)
            os.waitpid(pid, 0)


with tempfile.TemporaryDirectory(prefix='research-cli-') as temporary:
    folder = Path(temporary)
    with zipfile.ZipFile('api/build/distributions/api-0.1.0-mvp.zip') as archive:
        archive.extractall(folder)
    java = [str(Path(os.environ['JAVA_HOME'])/'bin/java'), '-cp', str(folder/'api-0.1.0-mvp/lib/*'),
            'sotaos.api.cli.MainKt']
    database = folder/'node.db'
    common = ['--handle', 'researcher', '--db', str(database)]
    terminal(java + ['auth', 'create'] + common,
             [('Нова парольна фраза (12–1024 символи): ', PASSPHRASE),
              ('Повторіть парольну фразу: ', PASSPHRASE)])
    terminal(java + ['research', 'setup'] + common, [('> ', 'CANCEL')], False)
    with sqlite3.connect(database) as db:
        assert db.execute('SELECT COUNT(*) FROM core').fetchone()[0] == 0
    output = terminal(java + ['research', 'setup'] + common,
                      [('> ', 'СТВОРИТИ RESEARCH CORE'), (PROOF, PASSPHRASE)])
    authority = re.search(r'Research authority: ([a-f0-9-]+)', output).group(1)
    proof = [(PROOF, PASSPHRASE)]

    def command(operation, *options):
        return java + ['research', operation] + common + ['--authority', authority] + list(options)

    public = folder/'public.json'
    public.write_text(json.dumps([{'url': 'https://example.org/discussion/1', 'externalId': '1',
                                  'author': 'Навчальний автор', 'title': 'Довіра',
                                  'text': 'Довіра потребує доказів.', 'publishedAt': None}], ensure_ascii=False))
    terminal(command('import', '--file', str(public), '--password', 'forbidden'), [], False)
    terminal(command('import', '--file', str(public)), [(PROOF, 'wrong-fixture-passphrase')], False)
    output = terminal(command('import', '--file', str(public)), proof)
    source = re.search(r'Source: ([a-f0-9-]+)', output).group(1)
    duplicate = terminal(command('import', '--file', str(public)), proof)
    assert source in duplicate
    assert source in terminal(command('search', '--query', 'доказів'), proof)
    argument = folder/'argument.json'
    argument.write_text(json.dumps({'sourceId': source, 'kind': 'ARGUMENT', 'text': 'Потрібна перевірка доказів',
                                    'quote': 'Довіра'}, ensure_ascii=False))
    assert 'Argument:' in terminal(command('argument', '--file', str(argument)), proof)
    report = folder/'report.md'
    terminal(command('report', '--out', str(report)), proof)
    assert 'Дослідницький звіт SOTA' in report.read_text()
    assert 'Довіра потребує доказів.' in report.read_text()
    assert report.stat().st_mode & 0o777 == 0o600
    original = report.read_bytes()
    terminal(command('report', '--out', str(report)), proof, False)
    assert report.read_bytes() == original
    draft_file = folder/'draft.txt'
    text = 'Локальна чернетка українською. Публікація вимкнена.'
    draft_file.write_text(text)
    draft = re.search(r'Draft: ([a-f0-9-]+)', terminal(
        command('draft', '--file', str(draft_file), '--evidence', source), proof)).group(1)
    terminal(command('approve', '--draft', draft), proof + [('> ', 'CANCEL')], False)
    content_hash = hashlib.sha256(text.encode()).hexdigest()
    approved = terminal(command('approve', '--draft', draft), proof + [('> ', 'ПОГОДЖУЮ ' + content_hash)])
    assert text in approved and 'Approval:' in approved
    draft_file.write_text('Unsafe terminal text: \x1b[2J')
    unsafe_draft = re.search(r'Draft: ([a-f0-9-]+)', terminal(
        command('draft', '--file', str(draft_file), '--evidence', source), proof)).group(1)
    rejected_preview = terminal(command('approve', '--draft', unsafe_draft), proof, False)
    assert '\x1b[2J' not in rejected_preview, 'Unsafe draft reached terminal preview'
    journals = list(folder.glob('node.db.research/*/research.jsonl'))
    assert len(journals) == 1
    records = [json.loads(line) for line in journals[0].read_text().splitlines()]
    assert sum(r['operation'] == 'SOURCE' for r in records) == 1
    assert sum(r['operation'] == 'APPROVAL' for r in records) == 1
    assert PASSPHRASE not in journals[0].read_text()
    terminal(command('revoke'), [('> ', 'ВІДКЛИКАТИ RESEARCH'), *proof])
    terminal(command('search', '--query', 'доказів'), proof, False)
    with sqlite3.connect(database) as db:
        assert db.execute('SELECT state FROM authority WHERE authority_id = ?', (authority,)).fetchone()[0] == 'REVOKED'
        types = [row[0] for row in db.execute('SELECT type FROM event')]
        assert 'RESEARCH_WORKSPACE_SETUP' in types and 'RESEARCH_AUTHORITY_SURRENDERED' in types
    assert len(journals[0].read_text().splitlines()) == len(records)
print('Research CLI smoke passed: setup, proof, import, search, evidence, report, review, surrender, restart denial.')
