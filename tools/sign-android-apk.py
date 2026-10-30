#!/usr/bin/env python3
"""Sign an aligned release APK with the permanent, pinned VoiceScribe key.

Credentials are read from the environment, never from command-line arguments.
This script works in CI and locally with the same SDK apksigner JAR.
"""
import argparse
import base64
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--apksigner', required=True, type=Path)
    parser.add_argument('--keystore', type=Path)
    args = parser.parse_args()
    pin_file = Path(__file__).resolve().parents[1] / 'android/signing-certificate.sha256'
    expected = pin_file.read_text().strip().lower()
    if not re.fullmatch(r'[0-9a-f]{64}', expected):
        raise SystemExit('Invalid public signing certificate pin')
    if not os.environ.get('VOICESCRIBE_KEYSTORE_PASSWORD'):
        raise SystemExit('Missing VOICESCRIBE_KEYSTORE_PASSWORD; no fallback key is generated')
    if not args.input.is_file() or not args.apksigner.is_file():
        raise SystemExit('Release APK or Android SDK apksigner JAR is missing')
    if args.input.resolve() == args.output.resolve():
        raise SystemExit('Input and output must be different files')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='voicescribe-sign-') as temp_name:
        temp = Path(temp_name)
        key = args.keystore
        if key is None:
            encoded = os.environ.get('VOICESCRIBE_KEYSTORE_B64', '')
            if not encoded:
                raise SystemExit('Missing VOICESCRIBE_KEYSTORE_B64; no fallback key is generated')
            key = temp / 'release.p12'
            key.write_bytes(base64.b64decode(encoded, validate=True))
            key.chmod(0o600)
        # Verify the public certificate before using this key to sign anything.
        cert = temp / 'certificate.der'
        exported = subprocess.run([
            'keytool', '-exportcert', '-keystore', str(key), '-storetype', 'PKCS12',
            '-alias', 'voicescribe', '-storepass:env', 'VOICESCRIBE_KEYSTORE_PASSWORD',
            '-file', str(cert),
        ], capture_output=True)
        if exported.returncode or not cert.is_file():
            raise SystemExit('Cannot open signing key; check the private credentials')
        if hashlib.sha256(cert.read_bytes()).hexdigest() != expected:
            raise SystemExit('Signing key does not match the pinned permanent certificate')
        signed = temp / 'signed.apk'
        subprocess.run([
            'java', '-jar', str(args.apksigner), 'sign', '--ks', str(key),
            '--ks-type', 'PKCS12', '--ks-key-alias', 'voicescribe',
            '--ks-pass', 'env:VOICESCRIBE_KEYSTORE_PASSWORD',
            '--key-pass', 'env:VOICESCRIBE_KEYSTORE_PASSWORD',
            '--out', str(signed), str(args.input),
        ], check=True, capture_output=True)
        verified = subprocess.run([
            'java', '-jar', str(args.apksigner), 'verify', '--verbose',
            '--print-certs', str(signed),
        ], check=True, capture_output=True, text=True)
        certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$',
                                  verified.stdout, re.MULTILINE)
        if [value.lower() for value in certificates] != [expected]:
            raise SystemExit('Signed APK certificate verification failed')
        # Replace only after successful signing and verification. No private
        # material is copied into the artifact directory.
        import shutil
        shutil.copyfile(signed, args.output)
    print(f'Verified permanent signing certificate: {expected}')
    print(f'Signed APK: {args.output.name} ({args.output.stat().st_size} bytes)')


if __name__ == '__main__':
    main()
