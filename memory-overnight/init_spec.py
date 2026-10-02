import pathlib,json,sys,time
ROOT=pathlib.Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT.parent/'native-supervisor-exact-final'))
from workport.native_supervisor import create
from workport.native_checkpoint import encode,assert_source
from workport.state import canonical,fingerprint
from workport.usage import UsageLedger
from workport.coordination import Coordinator
control=json.loads((ROOT/'control.json').read_text())
arch=json.loads((ROOT/'architecture-report.json').read_text())
repo=ROOT.parent/'jarvis-memory-overnight'
main='app/src/main/java/com/battlesbudz/jarvis/v2/'
test='app/src/test/java/com/battlesbudz/jarvis/v2/'
n=arch['next_unit']
first=n['production_allowed_paths']+n['new_test_allowed_paths']+n['documentation_append_only']+n['new_test_append_only_paths']
# Preserve existing assertion contracts; modernize only artificial timestamp fixtures
# to an injected, advancing clock where the new access-time expiry requires it.
first+=[test+'chat/ConversationHistoryTest.kt']
common=[str(p.relative_to(repo)) for p in repo.rglob('*.kt') if any(str(p.relative_to(repo)).startswith(main+s) for s in ['memory/','conversation/','ai/','ui/Conversation','ui/Memory','voice/VoiceModelSession','voice/VoiceCallService','voice/VoiceCallStore','voice/CallModelSlot','chat/ShortTermConversation','chat/ConversationHistory'])]
common += [main+'JarvisRuntime.kt',main+'diagnostics/DiagnosticRecorder.kt','docs/memory-os-native.md','docs/verification/features.md']+n['new_test_append_only_paths']
frozen=[p for p in arch['verification']['frozen_files_sha256'] if '.agents' not in p.split('/')]
argv=['python3','-B','-m','unittest','discover','-s','scripts','-p','test_*.py']
goals=[]
def goal(ident,desc,paths,dep):
    allowed=sorted(set(paths))
    assert len(allowed)<=128
    goals.append({'id':ident,'goal':desc+' Mandatory completion: independent exact-candidate review, frozen local Python harness suite, then full exact-remote signed release JVM/native/helpers, API30/API35 all named journeys and consolidated receipt. No fixture/old CI substitution; real-model/Fold6 performance remains separately unqualified.','priority':len(goals),'depends_on':[dep] if dep else [],'allowed_paths':allowed,'frozen_paths':[p for p in frozen if p not in allowed],'verification_argv':argv,'task_kind':'implementation','complexity':'complex'})
goal('source-privacy','Close persisted secret/90-day retention bypasses in history, calls, diagnostics and summaries, original-time read/write/load/replay fences; preserve benign diagnostic prompts and accepted receipts. Add privacy JVM tests and additive Android test36/scenario. Existing ConversationHistoryTest assertions stay; only artificial epoch/future timestamp fixtures may use deterministic advancing clock.',first,None)
goal('local-extraction','Implement independent sensitive eligibility, explicit AUTOMATIC acceptance origin, source attribution/statement/inference labels, durable transactional extraction/index jobs and deletion suppression. Use selected local Gemma model via replaceable idle extractor yielding to active work; no assistant, hypothetical or quoted text as unsupported user facts. Sensitive facts remain locked behind live model/UI/TTS delivery fences.',common+[main+'memory/'+p+'.kt' for p in ['SourceTextPersistencePolicy','MemoryAcceptance','MemoryExtraction','MemoryExtractionJobs','MemoryExtractionWorker','MemoryEligibility','MemorySuppression','MemorySourceSupport','MemorySensitivityPolicy']]+[test+'memory/'+p+'.kt' for p in ['MemoryAcceptanceTest','MemoryExtractionTest','MemoryExtractionJobsTest','MemoryEligibilityTest','MemorySuppressionTest','MemorySensitivityPolicyTest']], 'source-privacy')
goal('hybrid-rag','Implement pinned local EmbeddingGemma Android artifact/runtime, revision/hash/model-versioned rebuildable vectors, exact keyword plus semantic cosine search and bounded rank fusion/relevance filtering on fixed privacy-safe golden corpus. Extend current every-turn memory packet with actual model context budget, dates/source IDs/uncertainty and tappable evidence badge. Preserve raw-current-request action authority, offline lexical degradation, erasure/expiry/lock fences; no hosted embeddings or unmeasured ANN.',common+['app/build.gradle.kts','app/proguard-rules.pro']+[main+'memory/'+p+'.kt' for p in ['MemoryEmbeddings','EmbeddingGemmaRuntime','EmbeddingModelStore','MemoryVectorIndex','MemoryHybridRetrieval','MemoryQueryPlanner','MemoryEvidencePacket','MemoryIndexWorker','MemoryRetrievalEvaluation']]+[main+'ui/MemoryEvidenceBadge.kt',main+'ui/MemoryEmbeddingSettings.kt']+[test+'memory/'+p+'.kt' for p in ['MemoryEmbeddingsTest','EmbeddingModelStoreTest','MemoryVectorIndexTest','MemoryHybridRetrievalTest','MemoryEvidencePacketTest','MemoryQueryPlannerTest','MemoryRetrievalEvaluationTest']], 'local-extraction')
goal('temporal-graph','Implement native canonical recorded/valid-time assertions, CHANGE versus CORRECTION, stable entity IDs with ambiguous-name protection, current/as-of retrieval and source-linked G-Brain projections/timelines/typed links. Never manufacture dates/relationships from similarity; suppress deleted derivatives and preserve historical legitimate states.',common+[main+'memory/'+p+'.kt' for p in ['MemoryTemporalAssertions','MemoryTemporalQuery','MemoryEntityResolver','MemoryGraph','MemoryGraphProjection','MemoryConsolidation']]+[test+'memory/'+p+'.kt' for p in ['MemoryTemporalAssertionsTest','MemoryTemporalQueryTest','MemoryEntityResolverTest','MemoryGraphTest','MemoryConsolidationTest']], 'hybrid-rag')
goal('source-adapters','Implement consent/scoped new-event adapter boundaries and cursor/revocation/deduplication behavior for SMS/MMS, email, Messenger, calendar/contacts, plus authorized task-screen events. No backfill or silent account/permission grant. Wire evidence-backed events to existing autonomy integration only when actual feature-tools capability exists; retain per-source coverage/missing-capability receipts and no fabricated full message coverage.',common+['app/src/main/AndroidManifest.xml']+[main+'memory/'+p+'.kt' for p in ['MemorySourceAdapter','MemoryAdapterCursor','MemorySourceConsent','SmsMemorySourceAdapter','EmailMemorySourceAdapter','MessengerMemorySourceAdapter','CalendarMemorySourceAdapter','ContactsMemorySourceAdapter','MemoryAutonomyEvents']]+[main+'ui/MemorySourceSettings.kt']+[test+'memory/'+p+'.kt' for p in ['MemorySourceAdapterTest','MemoryAdapterCursorTest','MemorySourceConsentTest','MemoryAutonomyEventsTest']], 'temporal-graph')
spec={'schema':1,'repository':{'owner':'battlesbudz','name':'Jarvis-OS-V2'},'source_branch':control['source_branch'],'base_head':control['base_head'],'deadline':control['deadline'],'lease_seconds':3600,'max_attempts':12,'max_dispatches':22,'max_reserved_tokens':289000,'goals':goals}
record=create(spec,'jarvis-memory-20261002')
(ROOT/'spec.json').write_text(canonical(spec))
(ROOT/'spec.sha256').write_text(fingerprint(spec)+'\n')
(ROOT/'checkpoint.json').write_bytes(encode(record))
assert_source(record,repo)
ledger=UsageLedger(ROOT/'coordination'/'usage')
ledger.complete(control['tickets']['architecture']['id'],'architecture-done','success',usage=None)
coord=Coordinator(ROOT/'coordination')
coord_spec={'schema':1,'repository':str(repo),'base_head':control['base_head'],'goal':goals[0]['goal'],'units':[{'id':'privacy','depends_on':[],'allowed_paths':goals[0]['allowed_paths'],'task_kind':'implementation','complexity':'complex'}],'reviewer':'/root/jarvis_privacy_review','frozen_paths':goals[0]['frozen_paths'],'verification_argv':argv,'max_seconds':min(3600,int(control['deadline']-time.time())),'max_attempts':3,'usage_scope_id':control['usage_scope'],'review_stage':'before_verification','preflight_argv':['git','diff','--check']}
run=coord.start('source-privacy-1',coord_spec)
ticket=ledger.reserve(control['usage_scope'],'privacy-1','prepare:'+run+':privacy',{'task_kind':'implementation','complexity':'complex','allowed_paths':goals[0]['allowed_paths']},18000)
control.update({'spec_hash':fingerprint(spec),'run':run,'coding_ticket':ticket['id'],'coordinator_scope_sha256':fingerprint({k:goals[0][k] for k in ['allowed_paths','frozen_paths','verification_argv']})})
(ROOT/'control.json').write_text(json.dumps(control,indent=2)+'\n')
print(json.dumps({'spec_hash':fingerprint(spec),'run':run,'coding_ticket':ticket,'goals':[{'id':g['id'],'allowed':len(g['allowed_paths']),'frozen':len(g['frozen_paths'])} for g in goals]}))
