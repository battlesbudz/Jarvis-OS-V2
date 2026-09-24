# Tools design: interview decision record

Owner: Justin Battles. Interview completed September 24, 2026 (America/New_York).
Status: agreed product requirements, not evidence of implementation. This record and the [implementation plan](tools-implementation-plan.md) supersede conflicting proposals in the earlier research. In particular, automatic memory replaces routine manual memory approval, scripts are in scope, and external-agent access is deferred.

## Scope and integrations

| ID | Decision |
|---|---|
| D01 | Cover phone controls, app/web information retrieval, messages/notifications, calendar/reminders, and saved routines. |
| D02 | All AI reasoning stays on the phone. Online tools may run automatically when needed within granted access. |
| D03 | Prioritize Gmail, Google Calendar, Maps, Chrome; Samsung Calendar, Reminder, Notes, Messages; WhatsApp, Messenger, Facebook, Discord. These are targets, not assertions of available APIs. |
| D04 | Ask once which app to use for a task category, remember the default, and honor explicitly named apps. |
| D05 | Make AppFunctions a first-class consumer integration. If unavailable, deliver supported tasks through Android APIs, intents, MCP, and approved screen control while pursuing access. |
| D06 | Prepare Jarvis's AppFunctions provider architecture now; enable external-agent access in a later milestone. |
| D07 | Support guided MCP setup and custom server URLs in settings. Use free tools unless the user explicitly enables paid services. Purchases/subscriptions still need confirmation. |
| D08 | Benchmark FunctionGemma later; enable only if it improves speed without reducing reliability. Preserve E2B/E4B and the selected model. |

## Authority and confirmations

| ID | Decision |
|---|---|
| D09 | Request access the first time an app/data source is needed; remember the answer and allow revocation. |
| D10 | Automatically expose new tools within an app's previously approved access. Broader access needs approval. |
| D11 | ALWAYS separately confirm sending messages; purchases/payments/subscriptions; deleting files/messages/calendar events; public posts/file sharing; security/privacy/account changes. No routine may waive these confirmations. Show/read recipient and message before sending. |
| D12 | Confirm each action separately, never as one blanket approval for several pending actions. |
| D13 | Changing a pending action invalidates the previous request; present the revised action for fresh confirmation. |
| D14 | A spoken yes approves only the action just explicitly asked about; clarify an ambiguous reference. |
| D15 | Hold steps awaiting approval and their dependents; continue independent work. |
| D16 | While working, Jarvis may initiate a useful additional action if it matches an enabled routine's approved limits, even outside its original trigger, and is not harmful. Otherwise ask in chat with a ready-to-tap Approve button. D11 always overrides reusable routine permissions. A ready button never means approval is already granted. |
| D17 | Disabling a routine pauses steps relying on its permissions immediately and asks whether to continue. Completed actions cannot be undone by pausing. |

## Execution, voice, and screen control

| ID | Decision |
|---|---|
| D18 | Run independent tasks concurrently; queue conflicting steps needing the same app or screen. |
| D19 | Saying stop while Jarvis speaks silences speech; work continues unless cancellation explicitly targets tasks. |
| D20 | Ending a voice call does not cancel accepted tasks or release active screen work prematurely. |
| D21 | Automatically enter silently working mode when execution starts. Ignore ordinary speech until Hey Jarvis reactivates conversation; tasks keep running. |
| D22 | During an active call, ask aloud when input is required, listen for the reply, then return to silent work. Completion updates go to chat. After the call ends, use notifications and chat. |
| D23 | Ask before taking screen control while the user is using the phone. Approval spans the active task group/session, across apps, until revoked or the group finishes. |
| D24 | Display a floating Stop button throughout screen control; it cancels the current task. Spoken stop your task cancels the current task; stop all tasks cancels all. Clarify if a verbal target is ambiguous. |
| D25 | Pause screen actions during manual touch/navigation; independent background work continues. Resume after a configurable touch-idle interval with no countdown; re-observe the screen first. |
| D26 | Release screen control when the group finishes and nothing remains. A later task group must not silently inherit an expired session. |
| D27 | Resume safe unfinished work after process death/reboot; ask about uncertain outcomes before replay. |
| D28 | Retry safely and try other permitted methods that preserve intent; ask if still blocked. Choose task-specific effort limits and ask if more work is worthwhile when progress stalls. |
| D29 | Continue within Android battery/thermal limits unless the user stops tasks. Do not add an arbitrary low-battery pause preference. |
| D30 | While locked, continue tasks and accept spoken confirmations where Android permits. General questions are open to anyone; actions and reading private information aloud require recognizing Justin's voice. Uncertain identity requires unlocking. Speaker recognition remains a device-validation dependency, not a claim of strong authentication. |

## Workflow creation, scheduling, and interface

| ID | Decision |
|---|---|
| D31 | Create workflows conversationally or save a successful task. Show a plain-language summary of steps, triggers, and permissions before enabling. A visual workflow editor is not required. |
| D32 | Trigger by voice/text, approved schedules, approved events such as notifications/location, and suggestions the user approves. |
| D33 | Run a missed routine automatically if still relevant; otherwise report missed. Assess current circumstances each time and ask if uncertain. All confirmation rules remain. |
| D34 | Reminders should alert at the requested time; other routines may run in a reasonable window. Follow phone Do Not Disturb, without a separate Jarvis quiet-hours schedule. |
| D35 | Chat/voice are the operational interface. Jarvis reports current work and step, updates task messages, and can initiate questions/follow-ups. Outside active conversation, send notifications plus chat messages, not unsolicited speech. |
| D36 | Settings contains Tools and Workflows, with saved workflows and connected apps/tools. No separate primary workspace is required. |
| D37 | Web/app answers are concise, with source links or app references in chat. |
| D38 | Completed task history retains summaries, completed steps, and errors. Discard retrieved content unless saved; do not retain complete raw tool transcripts by default. |

## Browser, skills, and scripts

| ID | Decision |
|---|---|
| D39 | First release scope includes an internal Jarvis browser with native-browser handoff. Navigate, fill, and submit requested forms; D11 governs sensitive actions. |
| D40 | Let the user take over internal-browser login/2FA/CAPTCHA while automation pauses. Reuse native-browser signed-in sessions where supported; hand off when needed. Support secure password-manager handoff for reduced future intervention. No guarantee of unattended 2FA/CAPTCHA. |
| D41 | Notice repeated tasks and suggest reusable routines for review, rather than silently enabling them. |
| D42 | Import community workflows for review and export personal workflows with private data removed. Support isolated custom scripts with explicit permissions, executing only on the phone. Explain unsupported scripts; no remote script fallback. |
| D43 | Save imports disabled when tools/scripts are unavailable and explain what is missing. |
| D44 | Automatically update community workflows only when permissions AND behavior remain unchanged; otherwise ask. Uncertainty requires review. |

## Automatic memory

| ID | Decision |
|---|---|
| D45 | Global automatic-memory setting, enabled by default across chat, voice, and tools. Capture relevant facts, preferences, topics, ideas, people, places, times, decisions, and similar information. |
| D46 | Candidate capture/self-review is followed by a separate background relevance evaluation. Save high-relevance candidates automatically to the memory wiki; routine user approval is not required. Consider evidence/confidence as well as relevance. |
| D47 | Run evaluation when the local model is idle; yield when the user needs Jarvis. |
| D48 | Update automatically on stronger new evidence, preserving change history. Manual correction/deletion is authoritative; older sources must not undo it. |
| D49 | Memories remain visible, editable, searchable, and deletable in the existing memory wiki. Exclude passwords, authentication codes, and payment credentials. Other relevant personal information is allowed. |

## Delivery

| ID | Decision |
|---|---|
| D50 | Deliver usable, tested releases after each milestone. First milestone: reliable phone commands while conversation continues. |
| D51 | First milestone includes opening apps, battery, volume; websites/settings/navigation destinations; play/pause/skip; approved tap/scroll/text input. Do not redefine this as only the existing three tools. |
| D52 | Full agreed product scope is delivered incrementally; provider exposure and FunctionGemma are later milestones. Differentiate automated verification from actual model/device evidence. |

## Interpretation and unresolved engineering choices

These are implementation decisions to resolve with prototypes, not additional user preferences to repeatedly interview:

- Android AppFunctions caller eligibility, actual installed-app capability coverage, and platform permission behavior.
- Background service/overlay/touch detection support, safe resumption after manual navigation, and lock-screen restrictions.
- Local speaker verification enrollment, replay/impostor testing and thresholds; no voice feature bypasses mandatory Android authentication.
- Local script language/runtime with a real isolation boundary and enforceable budgets; imported code does not gain the main app's permissions.
- Browser session separation, password-manager integration, and supported native handoff methods.
- Versioned relevance/confidence policy and interruption latency; settings defaults must be documented and tested.
- Task effort/time budgets and touch-idle delay remain configurable engineering defaults, not invented interview answers.

The navigation instruction accidentally included during the interview was background audio and is not a feature request.
