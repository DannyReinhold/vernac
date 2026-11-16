#!/usr/bin/env python3
# Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
"""Synthetic version snapshots. Only the isolated acceptance runtime may capture them."""
import hashlib
import json
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
FILES = ('database.sql', 'expectations.json', 'test-runtime.json')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(directory):
    directory = pathlib.Path(directory).resolve()
    manifest = json.loads((directory / 'manifest.json').read_text())
    if (manifest.get('format') != 1 or manifest.get('synthetic_only') is not True
            or not re.fullmatch(r'\d+\.\d+\.\d+', manifest.get('version', ''))
            or manifest.get('state') not in ('CANDIDATE', 'FROZEN')
            or not re.fullmatch('[0-9a-f]{40}', manifest.get('source_sha', ''))
            or set(manifest.get('files', {})) != set(FILES)):
        raise ValueError('Invalid upgrade baseline manifest')
    if tuple(map(int,manifest['version'].split('.'))) < (0,6,0):
        raise ValueError('Unsupported upgrade baseline before 0.6.0')
    for name, expected in manifest['files'].items():
        path = directory / name
        if path.is_symlink() or not path.is_file() or digest(path) != expected:
            raise ValueError('Upgrade baseline file changed: ' + name)
    # Previously released migrations must still exist unchanged in the new tree.
    migrations = manifest.get('migration_files', {})
    if not migrations:
        raise ValueError('Missing baseline migration inventory')
    for name, expected in migrations.items():
        path = ROOT / name
        if not path.resolve().is_relative_to(ROOT) or not path.is_file() or digest(path) != expected:
            raise ValueError('Historical migration changed or disappeared: ' + name)
    if not manifest.get('history') or any(not row['success'] for row in manifest['history']):
        raise ValueError('Missing or failed baseline Flyway history')
    return manifest


def history(database, credentials, username):
    def query(sql):
        result = subprocess.run(['psql', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1',
                                 '-d', database, '-U', username, '-c', sql],
                                env=credentials, check=True, capture_output=True, text=True)
        return json.loads(result.stdout)
    schemas = query("SELECT coalesce(json_agg(table_schema ORDER BY table_schema),'[]') FROM information_schema.tables WHERE table_name='flyway_schema_history'")
    rows = []
    for schema in schemas:
        quoted = '"' + schema.replace('"', '""') + '"'
        values = query('SELECT coalesce(json_agg(h ORDER BY installed_rank),\'[]\') FROM (SELECT installed_rank,version,description,type,script,checksum,success FROM ' + quoted + '.flyway_schema_history) h')
        rows.extend(dict(schema=schema, **row) for row in values)
    return rows


def check_history(old, current):
    indexed = {(row['schema'], row['installed_rank']): row for row in current}
    if any(indexed.get((row['schema'], row['installed_rank'])) != row for row in old):
        raise ValueError('Historical Flyway history changed during startup')
    if any(not row['success'] for row in current):
        raise ValueError('Failed migration after startup')


def capture(directory, database, credentials, username, runtime, expected):
    directory = pathlib.Path(directory).resolve()
    # Never overwrite an existing version snapshot, even a candidate.
    directory.mkdir(parents=True, exist_ok=False)
    try:
        with (directory / 'database.sql').open('w') as out:
            subprocess.run(['pg_dump', '--no-owner', '--no-acl', '--format=plain',
                            '-d', database, '-U', username], env=credentials, check=True,
                           stdout=out, stderr=subprocess.PIPE, text=True)
        for name, value in [('expectations.json', expected), ('test-runtime.json', runtime)]:
            (directory / name).write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')
        migration_files = {p.relative_to(ROOT).as_posix(): digest(p)
                           for base in ('saas', 'examples/saas-document-workspace')
                           for p in sorted((ROOT / base).rglob('V*__*.sql')) if 'target' not in p.parts}
        manifest = dict(format=1, version='0.6.0', state='CANDIDATE', synthetic_only=True,
                        source_sha=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                        application_jar_sha256=digest(ROOT/'examples/saas-document-workspace/target/saas-document-workspace-1.0.0.jar'),
                        postgres=subprocess.check_output(['pg_dump', '--version'], text=True).strip(),
                        files={name: digest(directory / name) for name in FILES},
                        migration_files=migration_files, history=history(database, credentials, username))
        (directory / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        read(directory)
    except Exception:
        # Failed capture is not a usable baseline; caller must inspect/remove explicitly.
        raise


def rebase_test_sessions(database, credentials, username):
    """Translate the synthetic JDBC-session wall clock only, before application startup.

    Spring Session uses the system clock, independently of the controlled business
    clock. Retain relative session ages and inactivity intervals; never extend TTLs
    or change authentication attributes. The checked-in dump remains immutable.
    """
    import time
    import uuid

    def sql(statement):
        return subprocess.run(['psql', '-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1',
                               '-d', database, '-U', username, '-c', statement],
                              env=credentials, check=True, capture_output=True, text=True).stdout

    reference = json.loads(sql('SELECT json_build_object(\'reference\',max(last_access_time)) '
                               'FROM identity_sessions.spring_session'))['reference']
    if reference is None:
        raise ValueError('Upgrade baseline has no JDBC sessions')
    now = time.time_ns() // 1_000_000
    reference = int(reference)
    delta = now - reference
    primary, session = str(uuid.uuid4()), str(uuid.uuid4())
    # Seed the expired negative control BEFORE translating time, so it must remain
    # expired through the same translation as the valid baseline sessions.
    # IDs are generated UUID literals, not fixture-provided SQL. All work is confined
    # to the new disposable database, before the application/migration connection opens.
    sql(f'''BEGIN;
        INSERT INTO identity_sessions.spring_session
            (primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name)
        SELECT '{primary}','{session}',{reference}-max_inactive_interval*1000-60000,
               {reference}-max_inactive_interval*1000-60000,max_inactive_interval,{reference}-60000,principal_name
          FROM identity_sessions.spring_session ORDER BY last_access_time DESC,primary_id LIMIT 1;
        INSERT INTO identity_sessions.spring_session_attributes
            (session_primary_id,attribute_name,attribute_bytes)
        SELECT '{primary}',attribute_name,attribute_bytes
          FROM identity_sessions.spring_session_attributes
         WHERE session_primary_id=(SELECT primary_id FROM identity_sessions.spring_session
                                    WHERE primary_id<>'{primary}'
                                    ORDER BY last_access_time DESC,primary_id LIMIT 1);
        UPDATE identity_sessions.spring_session
           SET creation_time=creation_time+({delta}),
               last_access_time=last_access_time+({delta}),
               expiry_time=expiry_time+({delta});
        COMMIT;''')
    return {'expiredSession': session}
