import { useContext } from 'react'
import { TeamRoleContext } from './teamRole'
import { useState } from 'react'

type Counts = { tests: number; passed: number; failures: number; errors: number; skipped: number }
type Test = { class: string; name: string; status: string; message: string }
type Case = { name: string; patched: boolean; status: string; junit: Counts | null; scenario: { scenario: string; repetitions: number; completed: number; duplicates: number; passes: number; errors: number; clientTimeouts: number } | null; reports: { suite: string; testcases: Test[] }[]; execution: { container: string; evidence: string; overlaySha256: string | null; exitCode: number; timedOut: boolean; oomKilled: boolean } }
type Payload = { diff: string; reproduction: string; proposed: Record<string, string>; summary: { targetedChecksPassed: boolean; fullSuiteFailures: number; suiteExceptionRecognized: boolean }; diagnosis: { document: { diagnosis: string; expectedBehavior: string; observedBehavior: string; supportingExecutions: { executionId: string; explanation: string }[] } }; report: { verificationId: string; snapshot: { id: string; contentHash: string }; environment: { images: string[]; resourceLimits: string }; cases: Case[]; unverified: string[] } }
type Review = { id: string; patch_id: string; verification_id: string; diagnosis_id: string; state: string; bundle_hash: string; decision_reason: string | null; suite_exception_accepted: boolean; payload: Payload; automaticApplication: boolean; events: { action: string; created_at: string }[] }
type Entry = { id: string; patch_id: string; state: string }
async function api<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await response.text()
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 1600)}`)
  return JSON.parse(text) as T
}
export default function DeveloperReviewPanel({ investigationId }: { investigationId: string }) {
  const role = useContext(TeamRoleContext)
  const canDecide = role === 'OWNER' || role === 'REVIEWER'
  const canSubmit = canDecide || role === 'INVESTIGATOR'
  const base = `/api/investigations/${investigationId}/patch-reviews`
  const [entries, setEntries] = useState<Entry[]>([])
  const [patchId, setPatchId] = useState('a9ac9125-29d6-4832-a7a1-ce7659b8ad0d')
  const [verificationId, setVerificationId] = useState('bcb4a4e2-f71a-4877-bc5c-01a8f3b84c51')
  const [diagnosisId, setDiagnosisId] = useState('daf7a5f4-99e4-4287-8be8-85bcf6141106')
  const [review, setReview] = useState<Review | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [reason, setReason] = useState('')
  const [inspected, setInspected] = useState({ evidenceInspected: false, diffInspected: false, reproductionInspected: false, limitationsAcknowledged: false, acceptSuiteException: false })
  const [view, setView] = useState<'evidence' | 'diff' | 'reproduction'>('evidence')
  async function action(kind: string, id = review?.id) {
    setBusy(true); setError(''); setNotice('')
    try {
      if (kind === 'list') setEntries(await api<Entry[]>(base))
      else if (kind === 'register') {
        setReview(await api<Review>(base, { id: crypto.randomUUID(), patchId, verificationId, diagnosisId }))
        setEntries(await api<Entry[]>(base)); setInspected({ evidenceInspected: false, diffInspected: false, reproductionInspected: false, limitationsAcknowledged: false, acceptSuiteException: false })
      } else if (kind === 'load') {
        setReview(await api<Review>(`${base}/${id}`)); setReason(''); setInspected({ evidenceInspected: false, diffInspected: false, reproductionInspected: false, limitationsAcknowledged: false, acceptSuiteException: false })
      } else if (kind === 'verify') setReview(await api<Review>(`${base}/${id}/verify`, {}))
      else if (kind === 'APPROVE' || kind === 'REJECT') {
        setReview(await api<Review>(`${base}/${id}/decision`, { action: kind, expectedBundleHash: review?.bundle_hash, reason, ...inspected }))
        setEntries(await api<Entry[]>(base)); setNotice('Decision recorded. No repository files were applied or committed.')
      } else {
        const route = kind === 'export' ? 'export' : `download/${kind}`
        const response = await fetch(`${base}/${id}/${route}`)
        if (!response.ok) throw new Error((await response.text()).slice(0, 1600))
        const filename = kind === 'export' ? `patch-review-${id}.zip` : kind === 'reproduction' ? 'GeneratedCheckoutReproductionTest.java' : kind === 'diff' ? 'patch.diff' : 'verification-report.json'
        const url = URL.createObjectURL(await response.blob()); const link = document.createElement('a'); link.href = url; link.download = filename; link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000)
        setReview(await api<Review>(`${base}/${id}`)); setNotice('Download prepared. Export does not apply changes.')
      }
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Review action failed') }
    finally { setBusy(false) }
  }
  const payload = review?.payload
  const pending = review?.state === 'PENDING'
  const canApprove = pending && payload?.summary.targetedChecksPassed && payload.summary.suiteExceptionRecognized && inspected.evidenceInspected && inspected.diffInspected && inspected.reproductionInspected && inspected.limitationsAcknowledged && (payload.summary.fullSuiteFailures === 0 || inspected.acceptSuiteException) && reason.trim().length > 0
  return <section id="developer-review">
    <h2>Developer review and patch export</h2>
    <p>Review the reproduced failure, saved diagnosis, proposed diff and actual verification results together. Approval records your decision about this exact proposal. It does not apply, merge, commit or deploy changes.</p>
    <button disabled={busy} onClick={() => void action('list')}>Load saved reviews</button>
    <details><summary>Register a local verified proposal</summary>
      <p>The backend reads the configured workspace and verification folder. These fields accept UUIDs, not arbitrary file paths. Registration rechecks the saved source, diff, diagnosis and execution evidence.</p>
      <label>Patch UUID<input value={patchId} onChange={event => setPatchId(event.target.value)} /></label>
      <label>Verification UUID<input value={verificationId} onChange={event => setVerificationId(event.target.value)} /></label>
      <label>Diagnosis UUID<input value={diagnosisId} onChange={event => setDiagnosisId(event.target.value)} /></label>
      <button disabled={busy || !canSubmit} onClick={() => void action('register')}>Register proposal for review</button>
    </details>
    {entries.map(entry => <p key={entry.id}><button disabled={busy} onClick={() => void action('load', entry.id)}>{entry.id} · {entry.state}</button></p>)}
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    {review && payload && <>
      <h3>Review status: {review.state}</h3><p>Review: <code>{review.id}</code> · Patch: <code>{review.patch_id}</code></p>
      <p>Snapshot: <code>{payload.report.snapshot.id}</code> · base hash: <code>{payload.report.snapshot.contentHash}</code></p>
      <p>Bound proposal/evidence hash: <code>{review.bundle_hash}</code></p>
      <p><strong>Full-suite failures: {payload.summary.fullSuiteFailures}.</strong> {payload.summary.fullSuiteFailures > 0 ? 'The complete suite is not green. Inspect its actual failure and explicitly acknowledge the recognized legacy exception before approval.' : 'Inspect all actual results before deciding.'}</p>
      <button disabled={busy || !canSubmit} onClick={() => void action('verify')}>Revalidate saved evidence</button>{' '}
      <button disabled={busy} onClick={() => setView('evidence')}>Inspect evidence</button>{' '}
      <button disabled={busy} onClick={() => setView('diff')}>Inspect diff</button>{' '}
      <button disabled={busy} onClick={() => setView('reproduction')}>Inspect reproduction</button>{' '}
      <button disabled={busy} onClick={() => void action('reproduction')}>Download reproduction</button>{' '}
      <button disabled={busy} onClick={() => void action('diff')}>Download patch diff</button>{' '}
      <button disabled={busy} onClick={() => void action('verification')}>Download verification JSON</button>{' '}
      <button disabled={busy} onClick={() => void action('export')}>Export patch ZIP</button>
      {view === 'diff' && <><h3>Proposed diff</h3><pre>{payload.diff}</pre>{Object.entries(payload.proposed).map(([path, source]) => <details key={path}><summary>{path}</summary><pre>{source}</pre></details>)}</>}
      {view === 'reproduction' && <><h3>Exact saved response-loss reproduction</h3><pre>{payload.reproduction}</pre></>}
      {view === 'evidence' && <>
        <h3>Saved advisory diagnosis</h3><p>{payload.diagnosis.document.diagnosis}</p><p>Expected: {payload.diagnosis.document.expectedBehavior}</p><p>Observed: {payload.diagnosis.document.observedBehavior}</p>
        {payload.diagnosis.document.supportingExecutions.map(item => <p key={item.executionId}><a href={`#/executions/${item.executionId}`} target="_blank" rel="noreferrer">Supporting execution {item.executionId}</a> · {item.explanation}</p>)}
        <details><summary>Complete saved diagnosis and citations</summary><pre>{JSON.stringify(payload.diagnosis, null, 2)}</pre></details>
        <h3>Recorded verification checks</h3><div className="table"><table><thead><tr><th>Check</th><th>Outcome</th><th>Tests</th><th>Passed</th><th>Failed</th><th>Errors</th><th>Skipped</th></tr></thead><tbody>{payload.report.cases.map(item => <tr key={item.name}><td>{item.name}</td><td>{item.status}</td><td>{item.junit?.tests ?? 'N/A'}</td><td>{item.junit?.passed ?? 'N/A'}</td><td>{item.junit?.failures ?? 'N/A'}</td><td>{item.junit?.errors ?? 'N/A'}</td><td>{item.junit?.skipped ?? 'N/A'}</td></tr>)}</tbody></table></div>
        {payload.report.cases.map(item => <details key={item.name}><summary>{item.name} · actual evidence and assertions</summary><p>Container/attempt: {item.execution.container}</p><p>Test source SHA-256: {item.execution.overlaySha256 ?? 'No overlay'}</p><p>Exit: {item.execution.exitCode} · timed out: {String(item.execution.timedOut)} · OOM: {String(item.execution.oomKilled)}</p>{item.scenario && <pre>{JSON.stringify(item.scenario, null, 2)}</pre>}{item.reports.map(suite => <div key={suite.suite}><h4>{suite.suite}</h4>{suite.testcases.map((test, index) => <p key={index}>{test.name} · {test.status} · {test.message}</p>)}</div>)}</details>)}
        <h3>Recorded environment</h3>{payload.report.environment.images.map(image => <p key={image}><code>{image}</code></p>)}<p>{payload.report.environment.resourceLimits}</p>
        <h3>Unverified and limitations</h3><ul>{payload.report.unverified.map((item, index) => <li key={index}>{item}</li>)}</ul>
      </>}
      {pending && canDecide && <><h3>Record a developer decision</h3>
        {([['evidenceInspected', 'I inspected the diagnosis, supporting executions and verification results.'], ['diffInspected', 'I inspected the actual diff and allowed change scope.'], ['reproductionInspected', 'I inspected the preserved reproduction and before/after source hashes.'], ['limitationsAcknowledged', 'I acknowledge the unverified behaviors and limitations.'], ['acceptSuiteException', 'I explicitly accept the documented legacy test exception; the full suite remains failed.']] as const).map(([key, label]) => <p key={key}><label><input type="checkbox" disabled={busy} style={{ display: 'inline-block', width: 'auto', marginRight: 8 }} checked={inspected[key]} onChange={event => setInspected({ ...inspected, [key]: event.target.checked })} /> {label}</label></p>)}
        <label>Decision reason<textarea maxLength={2000} value={reason} onChange={event => setReason(event.target.value)} /></label>
        <button disabled={busy || !canApprove} onClick={() => void action('APPROVE')}>Approve proposal only</button>{' '}
        <button disabled={busy || !reason.trim()} onClick={() => void action('REJECT')}>Reject proposal</button>
      </>}
      {review.decision_reason && <p>Saved decision reason: {review.decision_reason}. Suite exception accepted: {String(review.suite_exception_accepted)}.</p>}
      <h3>Saved review audit</h3>{review.events.map((event, index) => <p key={index}>{event.created_at} · {event.action}</p>)}
      <p>Export is available for pending and rejected proposals too; the ZIP retains the review state. No export or approval grants automatic application permission.</p>
    </>}
  </section>
}
