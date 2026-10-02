import json,sys,pathlib
ROOT=pathlib.Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT.parent/'native-supervisor-exact-final'))
from workport.native_checkpoint import decode,encode,validate_transition,publication_plan,confirm_publication,git_blob_sha
from workport.native_supervisor import transition

def read(name):
    b=(ROOT/name).read_bytes()
    pin=(ROOT/'spec.sha256').read_text().strip()
    return decode(b,pin,git_blob_sha(b))

if sys.argv[1]=='plan':
    request=json.loads((ROOT/'transition-request.json').read_text())
    prev=read('checkpoint.json')
    payload=request['payload']
    if request['action'] not in {'claim','recover'}:
        payload={**payload,'owner_id':prev['owner']['id'],'generation':prev['generation']}
    nxt=transition(prev,request['action'],payload,request['key'])
    validate_transition(prev,nxt)
    (ROOT/'pending-checkpoint.json').write_bytes(encode(nxt))
    (ROOT/'publication-plan.json').write_text(json.dumps(publication_plan(nxt,request['state_head'],'memory-overnight/checkpoint.json')))
    print('planned')
elif sys.argv[1]=='initial-plan':
    (ROOT/'publication-plan.json').write_text(json.dumps(publication_plan(read('checkpoint.json'),sys.argv[2],'memory-overnight/checkpoint.json')))
    print('planned')
elif sys.argv[1]=='confirm':
    fetched=json.loads((ROOT/'publication-fetched.json').read_text())
    name='pending-checkpoint.json' if (ROOT/'pending-checkpoint.json').exists() else 'checkpoint.json'
    rec=read(name)
    receipt=confirm_publication(rec,fetched['commit_metadata'],fetched['actual_blob_metadata'],fetched['observed_ref'])
    if name.startswith('pending'):
        validate_transition(read('checkpoint.json'),rec)
        (ROOT/'checkpoint.json').write_bytes(encode(rec))
        (ROOT/name).unlink()
    (ROOT/'transitions').mkdir(exist_ok=True)
    (ROOT/'transitions'/f"{rec['sequence']}.json").write_text(json.dumps(fetched,sort_keys=True,indent=2)+'\n')
    print(json.dumps(receipt))
