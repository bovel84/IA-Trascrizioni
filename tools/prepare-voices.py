#!/usr/bin/env python3
"""Prepare pinned native runtime/models outside git. No user audio is uploaded."""
import argparse, hashlib, os, shutil, tarfile, tempfile, urllib.request
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    'sherpa.aar': ('https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-static-link-onnxruntime-2.0.1.aar', '07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575'),
    'segmentation.tar.bz2': ('https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2', '24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488'),
    'embedding.onnx': ('https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx', 'e9848563da86f263117134dfd7ad63c92355b37de492b55e325400c9d9c39012'),
    'two-speakers.wav': ('https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/1-two-speakers-en.wav', 'f1c877dc01595e28be7147bf2fe38e5268147a868bf3fdb5c37b97f5940e21f3'),
}
def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''): h.update(block)
    return h.hexdigest()
def prepare(destination):
    cache=destination/'voice-deps';cache.mkdir(parents=True,exist_ok=True)
    for name,(url,expected) in SOURCES.items():
        target=cache/name
        if target.exists() and digest(target)==expected: continue
        print('Preparing native voice dependency:',name,flush=True)
        temporary=target.with_suffix(target.suffix+'.download')
        try:
            with urllib.request.urlopen(url,timeout=120) as source,temporary.open('wb') as out: shutil.copyfileobj(source,out)
            if digest(temporary)!=expected: raise RuntimeError('Checksum mismatch: '+name)
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
    assets=destination/'voice-assets'/'voices';assets.mkdir(parents=True,exist_ok=True)
    member='sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx'
    with tarfile.open(cache/'segmentation.tar.bz2') as archive:
        with archive.extractfile(member) as source,(assets/'segmentation.onnx').open('wb') as out: shutil.copyfileobj(source,out)
        with archive.extractfile('sherpa-onnx-pyannote-segmentation-3-0/LICENSE') as source,(assets/'segmentation-LICENSE.txt').open('wb') as out: shutil.copyfileobj(source,out)
    if digest(assets/'segmentation.onnx')!='d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d': raise RuntimeError('Invalid segmentation model')
    shutil.copyfile(cache/'embedding.onnx',assets/'embedding.onnx')
    shutil.copyfile(ROOT/'android'/'VOICE-NOTICES.txt',assets/'NOTICES.txt')
    for license_file in (ROOT/'android'/'licenses').glob('*.txt'): shutil.copyfile(license_file,assets/license_file.name)
    tests=destination/'voice-test-assets';tests.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(cache/'two-speakers.wav',tests/'two-speakers.wav')
    print('Native voice assets verified.',flush=True)
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,default=ROOT/'android'/'build');args=parser.parse_args();prepare(args.output)
