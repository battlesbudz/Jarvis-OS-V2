#!/usr/bin/env python3
"""Verify phone-matching files and run the host-only, fixed-arrival experiment."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import wave

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('binary',type=Path)
p.add_argument('model_dir',type=Path)
p.add_argument('paul_wav',type=Path)
p.add_argument('output',type=Path)
a=p.parse_args()
expected=json.loads(Path(__file__).with_name('model-hashes.json').read_text())
for name, sha in expected.items():
 f=a.paul_wav if name=='paul.wav' else a.model_dir/name
 assert hashlib.sha256(f.read_bytes()).hexdigest()==sha, f'Model/reference mismatch: {name}'
assert not a.output.exists() or not any(a.output.iterdir()), "Use an empty output directory"
a.output.mkdir(parents=True,exist_ok=True)
with (a.output/'native.log').open('w') as log:
 result=subprocess.run([str(a.binary.resolve()),str(a.model_dir.resolve()),str(a.paul_wav.resolve())],cwd=a.output,stdout=log,stderr=subprocess.STDOUT)
def pcm(name):
 with wave.open(str(a.output/name)) as f:
  assert f.getframerate()==24000 and f.getsampwidth()==2 and f.getnchannels()==1
  return f.readframes(f.getnframes())
checks={}
if result.returncode==0:
 checks={
  'nine_cases_saved':len(list(a.output.glob('*.wav')))==9,
  'repeated_whole_pcm_identical':pcm('whole.wav')==pcm('whole-repeat.wav'),
  'late_unapplied_suffix_matches_prefix':pcm('prefix.wav')==pcm('append-15.wav')==pcm('append-30.wav'),
  'before_append_first_callback_matches_prefix':pcm('prefix.wav')[:5760*2]==pcm('append-5.wav')[:5760*2],
 }
manifest={'scope':'host_experiment_not_phone_performance','protocol':'pocket-append-v1','model_sha256':expected,
 'binary_sha256':hashlib.sha256(a.binary.read_bytes()).hexdigest(),'returncode':result.returncode,'mechanical_checks':checks,
 'wav_sha256':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(a.output.glob('*.wav'))},
 'accent_assessment':'not_assessed','word_completeness':'requires_transcription_and_listening'}
(a.output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print((a.output/'native.log').read_text())
result.check_returncode()
assert all(checks.values()), checks
