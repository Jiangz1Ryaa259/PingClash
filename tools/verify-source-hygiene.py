"""Reject local build/signing files in every reachable Git revision.

This is a regression guard, not a substitute for secret scanning or key rotation
after a confirmed disclosure. The public androidTest DER fixture is synthetic.
"""
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args])


def main():
    if git('rev-parse', '--is-shallow-repository').strip() == b'true':
        raise RuntimeError('Full reachable history is required; fetch-depth must be 0')
    forbidden = {'.jks', '.keystore', '.p12', '.pfx'}
    paths = set(git('log', '--all', '--format=', '--name-only').decode('utf-8', errors='replace').splitlines())
    bad = [name for name in paths if pathlib.PurePosixPath(name).name.lower() == 'local.properties'
           or pathlib.PurePosixPath(name).suffix.lower() in forbidden
           or re.search(r'(^|/)(store-password|signing-password|keystore-password)\.', name, re.I)]
    if bad:
        # Never include file contents, credential fragments or personal paths.
        raise RuntimeError('Forbidden local/signing file found in repository history')
    objects = {line.split(b' ', 1)[0] for line in git('rev-list', '--all', '--objects').splitlines()}
    pem = re.compile(rb'-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----')
    count = 0
    with subprocess.Popen(['git', '-C', str(ROOT), 'cat-file', '--batch'], stdin=subprocess.PIPE, stdout=subprocess.PIPE) as process:
        for oid in objects:
            process.stdin.write(oid + b'\n')
            process.stdin.flush()
            header = process.stdout.readline().split()
            if len(header) != 3:
                raise RuntimeError('Git object inspection failed')
            data = process.stdout.read(int(header[2]))
            process.stdout.read(1)
            if header[1] == b'blob':
                count += 1
                if pem.search(data) or data.startswith(bytes.fromhex('feedfeed')):
                    raise RuntimeError('Private signing/key material found in repository history')
        process.stdin.close()
        process.wait()
        if process.returncode:
            raise RuntimeError('Git object inspection failed')
    print(f'Source hygiene: {count} historical blobs checked; no forbidden signing/local files or private PEM keys')


if __name__ == '__main__':
    main()
