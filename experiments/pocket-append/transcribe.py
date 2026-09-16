#!/usr/bin/env python3
"""Optional content screen; ASR is not a listening or accent-quality verdict.
Requires faster-whisper==1.2.1. Use an already downloaded base.en model directory.
"""
import argparse
import hashlib
import json
from pathlib import Path
from faster_whisper import WhisperModel

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('whisper_model',type=Path)
p.add_argument('results',type=Path)
a=p.parse_args()
m=WhisperModel(str(a.whisper_model),device='cpu',compute_type='int8',cpu_threads=2)
rows=[]
for f in sorted(a.results.glob('*.wav')):
 segments,info=m.transcribe(str(f),language='en',beam_size=5,temperature=0,condition_on_previous_text=False,vad_filter=False)
 text=''.join(s.text for s in segments).strip()
 rows.append({'file':f.name,'sha256':hashlib.sha256(f.read_bytes()).hexdigest(),'transcript':text})
 print(f.name,repr(text),flush=True)
result={'assessment':'automatic_transcription_not_human_quality_rating','model':'Whisper base.en',
 'model_files_sha256':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in a.whisper_model.iterdir() if f.is_file()},
 'decode':{'beam_size':5,'temperature':0,'language':'en','condition_on_previous_text':False,'vad_filter':False},'runs':rows}
(a.results/'transcripts.json').write_text(json.dumps(result,indent=2)+'\n')
