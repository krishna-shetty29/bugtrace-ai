import { useEffect, useState } from 'react'

type Ref = { kind: string; indexId: string; chunkKey: string | null; chunkNumber: number | null }
type Evidence = { id: string; reference: Ref; sourceRef: string; startLine: number; endLine: number; text: string }
type Execution = { executionId: string; eligible: boolean; classification?: string; observation?: string; notice?: string; metrics?: unknown }
type Code = { evidenceId: string; filePath: string; revision: string; startLine: number; endLine: number; quote: string; explanation: string }
type Document = { diagnosis: string; expectedBehavior: string; observedBehavior: string; triggeringConditions: string[]; relevantCodeLocations: Code[]; supportingExecutions: { executionId: string; explanation: string }[]; alternativeExplanations: string[]; unverifiedBehavior: string[] }
type Report = { id: string; state: string; verification_state: string; message: string; model_call_id: string; document: Document | null; document_hash: string | null; snapshot_id: string; content_hash: string; changesAuthorized: boolean; verified_at: string | null }
type Settings = { model: { provider: string; model: string; maxOutputTokens: number }; evidence: Evidence[]; executions: Execution[]; reports: { id: string; state: string }[]; attemptsUsed: number; maxAttempts: number }
async function api<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await response.text()
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 1400)}`)
  return JSON.parse(text) as T
}
export default function DiagnosisPanel({ investigationId }: { investigationId: string }) {
  const base = `/api/investigations/${investigationId}/diagnoses`
  const [settings, setSettings] = useState<Settings | null>(null)
  const [sources, setSources] = useState<string[]>([])
  const [jobs, setJobs] = useState<string[]>([])
  const [id, setId] = useState<string>(() => crypto.randomUUID())
  const [report, setReport] = useState<Report | null>(null)
  const [citation, setCitation] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function action(kind: string, selectedId = id, index = 0) {
    setBusy(true); setError('')
    try {
      if (kind === 'settings') setSettings(await api<Settings>(`${base}/settings`))
      else if (kind === 'create') {
        setReport(await api<Report>(base, { id, evidence: settings?.evidence.filter(item => sources.includes(item.id)).map(item => item.reference), executionIds: jobs }))
        setSettings(await api<Settings>(`${base}/settings`))
      } else if (kind === 'verify') setReport(await api<Report>(`${base}/${selectedId}/verify`, {}))
      else if (kind === 'citation') {
        setCitation(await api<unknown>(`${base}/${selectedId}/citations/${index}`))
        setReport(await api<Report>(`${base}/${selectedId}`))
      } else { setId(selectedId); setCitation(null); setReport(await api<Report>(`${base}/${selectedId}`)) }
    } catch (ex) { setError(ex instanceof Error ? ex.message : 'Diagnosis request failed') }
    finally { setBusy(false) }
  }
  useEffect(() => {
    if (report?.state !== 'GENERATING') return
    const timer = window.setInterval(() => { void api<Report>(`${base}/${report.id}`).then(setReport).catch(ex => setError(String(ex))) }, 3000)
    return () => window.clearInterval(timer)
  }, [base, report?.id, report?.state])
  function download() {
    const url = URL.createObjectURL(new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' }))
    const link = document.createElement('a'); link.href = url; link.download = `diagnosis-${report?.id}.json`; link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  const hasReproduction = settings?.executions.some(item => jobs.includes(item.executionId) && item.observation === 'EXPECTED_FAILURE_OBSERVED')
  const doc = report?.document
  const linksValid = report?.verification_state === 'VALID'
  return <section>
    <h2>Diagnosis report</h2>
    <p>Explain expected behavior, observed behavior, triggers, source locations, executions, alternatives and unverified behavior. This report cannot authorize or apply changes. Link validation checks provenance, not causal correctness.</p>
    <button disabled={busy} onClick={() => void action('settings')}>Load diagnosis settings and evidence</button>
    {settings && <>
      <p>Provider: {settings.model.provider} / {settings.model.model}. Each generation is one intentional model request with at most {settings.model.maxOutputTokens} output tokens. Attempts used {settings.attemptsUsed}/{settings.maxAttempts}; failures are not refunded. Live requests may incur charges.</p>
      <details><summary>Current Java evidence ({sources.length}/8)</summary>{settings.evidence.map(item => <div key={item.id}><label><input type="checkbox" checked={sources.includes(item.id)} disabled={busy || (!sources.includes(item.id) && sources.length >= 8)} onChange={event => setSources(event.target.checked ? [...sources, item.id] : sources.filter(id => id !== item.id))} /> {item.sourceRef} · lines {item.startLine}–{item.endLine}</label><details><summary>View source snippet</summary><pre>{item.text}</pre></details></div>)}</details>
      <details open><summary>Actual supporting executions ({jobs.length}/4)</summary>
        {settings.executions.length === 0 && <p>Run the generated reproduction for this investigation first.</p>}
        {settings.executions.map(item => <div key={item.executionId}><label><input type="checkbox" checked={jobs.includes(item.executionId)} disabled={busy || !item.eligible || (!jobs.includes(item.executionId) && jobs.length >= 4)} onChange={event => setJobs(event.target.checked ? [...jobs, item.executionId] : jobs.filter(id => id !== item.executionId))} /> {item.executionId} · {item.classification ?? 'Ineligible'} · {item.observation ?? item.notice}</label><details><summary>Repetition evidence when available</summary><pre>{JSON.stringify(item.metrics, null, 2)}</pre></details></div>)}
      </details>
      <p>Select at least one EXPECTED_FAILURE_OBSERVED execution. All selected executions must be cited, including any selected passing or inconclusive results.</p>
      <label>Diagnosis UUID<input value={id} onChange={event => setId(event.target.value)} /></label>
      <button disabled={busy || !sources.length || !jobs.length || !hasReproduction || settings.attemptsUsed >= settings.maxAttempts} onClick={() => void action('create')}>Generate advisory diagnosis</button>{' '}
      <button disabled={busy} onClick={() => { setId(crypto.randomUUID()); setReport(null); setCitation(null) }}>New diagnosis ID</button>{' '}
      <button disabled={busy} onClick={() => void action('load')}>Load saved diagnosis</button>
      <h3>Recent saved diagnoses</h3>{settings.reports.map(item => <p key={item.id}><button disabled={busy} onClick={() => void action('load', item.id)}>{item.id} · {item.state}</button></p>)}
    </>}
    {error && <p role="alert">{error}</p>}
    {report && <>
      <h3>{report.state}</h3><p>{report.message}</p><p>Evidence links: {report.verification_state}. Last checked: {report.verified_at ?? 'Not checked'}. Changes authorized: <strong>No</strong>.</p>
      <p>Diagnosis UUID: <code>{report.id}</code> · saved model call: <code>{report.model_call_id}</code></p>
      <p>Snapshot: <code>{report.snapshot_id}</code> · base hash: <code>{report.content_hash}</code></p>
      <button disabled={busy || report.state !== 'VALIDATED_ADVISORY'} onClick={() => void action('verify', report.id)}>Verify all diagnosis evidence links</button>{' '}
      <button disabled={!doc} onClick={download}>Download diagnosis JSON</button>
      {doc && <>
        <h3>Proposed diagnosis — advisory</h3><p>{doc.diagnosis}</p>
        <h3>Expected behavior</h3><p>{doc.expectedBehavior}</p>
        <h3>Observed behavior</h3><p>{doc.observedBehavior}</p>
        <h3>Triggering conditions</h3><ul>{doc.triggeringConditions.map((item, i) => <li key={i}>{item}</li>)}</ul>
        <h3>Relevant code locations</h3>{doc.relevantCodeLocations.map((item, i) => <article key={i}><p>{item.filePath} · revision {item.revision} · SOURCE_LINE {item.startLine}–{item.endLine}</p><pre>{item.quote}</pre><p>{item.explanation}</p><button disabled={busy || !linksValid} onClick={() => void action('citation', report.id, i)}>View verified source citation</button></article>)}
        <h3>Supporting executions</h3>{doc.supportingExecutions.map(item => <p key={item.executionId}>{linksValid ? <a href={`#/executions/${item.executionId}`} target="_blank" rel="noreferrer">{item.executionId}</a> : item.executionId} · {item.explanation}</p>)}
        <h3>Alternative explanations</h3><ul>{doc.alternativeExplanations.map((item, i) => <li key={i}>{item}</li>)}</ul>
        <h3>Unverified behavior</h3><ul>{doc.unverifiedBehavior.map((item, i) => <li key={i}>{item}</li>)}</ul>
        <p>Confidence text is never a permission grant. No model confidence field, report status or verification result authorizes a patch, deployment or extra execution.</p>
      </>}
      {citation !== null && <details open><summary>Verified saved citation</summary><pre>{JSON.stringify(citation, null, 2)}</pre></details>}
    </>}
  </section>
}
