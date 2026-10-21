import { useEffect, useRef, useState } from 'react'
import WorkerAvailabilityPanel from './WorkerAvailabilityPanel'
import { queueExplanation } from './workerPresence'
import { request, runPath, checkRun, checkRecord, pendingId, download } from './benchmarkClient'
import type { Case, Detail, Evidence, Run, SavedRecord, Settings, Experiment } from './benchmarkClient'

const errorText = (cause: unknown) => cause instanceof Error ? cause.message : 'Request failed'
const json = (value: unknown) => JSON.stringify(value, null, 2)

export default function BenchmarkPage({ investigationId, runId }: { investigationId?: string; runId?: string }) {
  const [catalog, setCatalog] = useState<Case[]>([])
  const [caseId, setCaseId] = useState('case-001')
  const [collection, setCollection] = useState('1.0.0')
  const [recent, setRecent] = useState<{ investigation_id: string; title: string; case_id: string; collection_version: string }[]>([])
  const [detail, setDetail] = useState<Detail | null>(null)
  const [settings, setSettings] = useState<Settings | null>(null)
  const [selected, setSelected] = useState<string[]>([])
  const [run, setRun] = useState<Run | null>(null)
  const [records, setRecords] = useState<{ id: string; kind: string; document_sha256: string }[]>([])
  const [record, setRecord] = useState<SavedRecord | null>(null)
  const [search, setSearch] = useState<unknown>(null)
  const [callView, setCallView] = useState<unknown>(null)
  const [query, setQuery] = useState('checkout')
  const [maxCalls, setMaxCalls] = useState(4)
  const [wallSeconds, setWallSeconds] = useState(1800)
  const [experiments, setExperiments] = useState<Experiment[]>([])
  const [experiment, setExperiment] = useState<Experiment | null>(null)
  const [trusted, setTrusted] = useState(false)
  const [liveApproved, setLiveApproved] = useState(false)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const locked = useRef(false)

  useEffect(() => {
    const controller = new AbortController()
    async function load() {
      setTrusted(false); setLiveApproved(false); setExperiments([]); setExperiment(null); setRun(null); setDetail(null); setSettings(null); setSelected([]); setRecords([]); setRecord(null)
      try {
        if (!investigationId) {
          const [available, saved] = await Promise.all([request<{ cases: Case[] }>('/benchmark-browser/catalog', undefined, controller.signal), request<typeof recent>('/benchmark-browser/investigations', undefined, controller.signal)])
          if (!controller.signal.aborted) { setCatalog(available.cases); setRecent(saved) }
        } else {
          const captured = await request<Detail>(`/benchmark-browser/investigations/${investigationId}`, undefined, controller.signal)
          if (!controller.signal.aborted) setDetail(captured)
          if (captured.imported) {
            const value = await request<Settings>(runPath(investigationId), undefined, controller.signal)
            if (!controller.signal.aborted) setSettings(value)
            if (runId) {
              const [savedRun, savedRecords] = await Promise.all([request<Run>(runPath(investigationId, runId), undefined, controller.signal), request<typeof records>(`${runPath(investigationId, runId)}/records`, undefined, controller.signal)])
              checkRun(savedRun, captured, runId)
              if (!controller.signal.aborted) { setRun(savedRun); setRecords(savedRecords) }
            }
          }
        }
      } catch (cause) { if (!controller.signal.aborted) setError(errorText(cause)) }
    }
    void load()
    return () => controller.abort()
  }, [investigationId, runId])

  async function act(label: string, operation: () => Promise<void>) {
    if (locked.current) return
    locked.current = true; setBusy(label); setError(''); setNotice('')
    try { await operation() } catch (cause) { setError(errorText(cause)) }
    finally { locked.current = false; setBusy('') }
  }
  async function refresh() {
    if (!investigationId) return
    const captured = await request<Detail>(`/benchmark-browser/investigations/${investigationId}`); setDetail(captured)
    if (captured.imported) {
      setSettings(await request<Settings>(runPath(investigationId)))
      if (runId) {
        setRun(checkRun(await request<Run>(runPath(investigationId, runId)), captured, runId))
        setRecords(await request<typeof records>(`${runPath(investigationId, runId)}/records`))
      }
    }
  }
  async function create() {
    const key = `benchmark-create:${collection}:${caseId}`
    const created = await request<Detail>('/benchmark-browser/investigations', { requestId: pendingId(sessionStorage, key), caseId })
    sessionStorage.removeItem(key); window.location.hash = `/benchmarks/${created.investigationId}`
  }
  async function prepare() {
    if (!investigationId) return
    await request(`/investigations/${investigationId}/java-index`, {})
    await request(`/investigations/${investigationId}/semantic-index`, {})
    await refresh(); setNotice('Evidence indexes requested for the verified public case. Load saved evidence when preparation completes.')
  }
  async function createRun() {
    if (!investigationId || !settings) return
    const key = `benchmark-run:${investigationId}`
    const id = pendingId(sessionStorage, key)
    const saved = await request<Run>(runPath(investigationId), { id, query, evidence: settings.evidence.filter(item => selected.includes(item.id)).map(item => item.reference), maxCalls, maxOutputTokens: maxCalls * settings.model.maxOutputTokens, wallSeconds })
    sessionStorage.removeItem(key); window.location.hash = `/benchmarks/${investigationId}/${saved.id}`
  }
  async function advance() {
    if (!investigationId || !runId || !detail || !run || !settings) return
    if (run.provider !== 'mock' && !liveApproved) throw new Error('Acknowledge the live provider before sending a model request.')
    const key = `benchmark-step:${runId}`
    const saved = await request<Run>(`${runPath(investigationId, runId)}/advance`, { requestId: pendingId(sessionStorage, key) })
    checkRun(saved, detail, runId); sessionStorage.removeItem(key); setRun(saved); await refresh()
  }
  async function loadExperiments() {
    if (!investigationId || !runId) return
    setExperiments(await request<Experiment[]>(`${runPath(investigationId, runId)}/experiments`))
  }
  async function execute() {
    if (!investigationId || !runId || !run || !trusted) return
    const key = `benchmark-execute:${runId}`
    const value = await request<Experiment>(`${runPath(investigationId, runId)}/experiments`, { requestId: pendingId(sessionStorage, key), trustedFixtureApproved: trusted })
    sessionStorage.removeItem(key); setExperiment(value); await loadExperiments(); await refresh()
  }
  async function repair() {
    if (!investigationId || !runId || !run) return
    if (run.provider !== 'mock' && !liveApproved) throw new Error('Acknowledge the live provider before requesting repair.')
    const key = `benchmark-repair:${runId}`
    await request(`${runPath(investigationId, runId)}/compiler-repair`, { requestId: pendingId(sessionStorage, key) })
    sessionStorage.removeItem(key); setExperiment(null); await refresh(); await loadExperiments()
  }
  async function exportRecord() {
    if (!investigationId || !runId) return
    setRecord(checkRecord(await request<SavedRecord>(`${runPath(investigationId, runId)}/records/export`, {}), investigationId, runId)); await refresh()
  }
  async function loadRecord(id: string) {
    if (!investigationId || !runId) return
    setRecord(checkRecord(await request<SavedRecord>(`${runPath(investigationId, runId)}/records/${id}`), investigationId, runId))
  }
  async function retrieve() {
    if (!investigationId || !runId) return
    const saved = checkRecord(await request<SavedRecord>(`${runPath(investigationId, runId)}/records/search`, { query, kind: 'ALL', limit: 10 }), investigationId, runId)
    setSearch(saved.document.results); await refresh()
  }
  function toggle(item: Evidence) { setSelected(values => values.includes(item.id) ? values.filter(id => id !== item.id) : values.length < 8 ? [...values, item.id] : values) }
  const disabled = Boolean(busy)
  const verified = detail?.verification?.verified === true
  const canAdvance = run && ['READY_ANALYSIS', 'PLAN_READY'].includes(run.state)
  return <section style={{ overflowWrap: 'anywhere' }} aria-label="Benchmark investigation">
    <h1>Benchmarks</h1>
    <p>Investigate an approved public case with saved evidence and bounded model calls.</p>
    <p role="status">Experiments require explicit approval for this trusted public fixture. Snapshot and source bindings are validated again before dispatch. A saved proposal is not execution evidence.</p>
    {error && <p role="alert" className="error">{error}</p>}{busy && <p role="status">{busy}…</p>}{notice && <p role="status">{notice}</p>}
    {!investigationId ? <>
      <label>Registered collection<select disabled={disabled} value={collection} onChange={event => setCollection(event.target.value)}>{[...new Set(catalog.map(item => item.collectionVersion))].map(version => <option key={version}>{version}</option>)}</select></label>
      <label>Public case<select disabled={disabled} value={caseId} onChange={event => setCaseId(event.target.value)}>{catalog.filter(item => item.collectionVersion === collection).map(item => <option key={item.caseId}>{item.caseId}</option>)}</select></label>
      {catalog.filter(item => item.caseId === caseId && item.collectionVersion === collection).map(item => <div key={item.caseId}><h2>Report</h2><p>{item.bugReport}</p><h3>Expected behavior</h3><p>{item.expectedBehavior}</p></div>)}
      <button disabled={disabled || !catalog.some(item => item.caseId === caseId && item.collectionVersion === collection)} onClick={() => void act('Creating investigation', create)}>Register case and create fresh investigation</button>
      <h2>Saved benchmark investigations</h2>{recent.length ? <ul>{recent.map(item => <li key={item.investigation_id}><a href={`#/benchmarks/${item.investigation_id}`}>{item.case_id} · {item.title} · {item.investigation_id}</a></li>)}</ul> : <p>No saved benchmark investigations loaded.</p>}
    </> : <>
      <p><a href="#/benchmarks">All benchmark cases</a></p>
      <button disabled={disabled} onClick={() => void act('Loading saved state', refresh)}>Reload saved investigation</button>
      {detail && <>
        <WorkerAvailabilityPanel key={detail.snapshotId} snapshotId={detail.snapshotId} expectedHash={detail.verification?.contentHash} /><h2>{detail.caseId} · Collection {detail.collectionVersion}</h2><p>{detail.bugReport}</p><p><strong>Expected:</strong> {detail.expectedBehavior}</p>
        <details><summary>Case identity</summary><p>Investigation: {detail.investigationId}<br />Snapshot: {detail.snapshotId}<br />Release: {detail.releaseCommit}<br />Content hash: {detail.verification?.contentHash || 'Not imported'}</p></details>
        <button disabled={disabled} onClick={() => void act('Importing and verifying public case', async () => { setDetail(await request<Detail>(`/benchmark-browser/investigations/${investigationId}/import`, {})); await refresh() })}>{detail.imported ? 'Verify imported case' : 'Import and verify case'}</button>
        <p>Snapshot verification: {verified ? 'Verified' : 'Not verified'}</p>
        {verified && <button onClick={() => download(`${detail.investigationId}-import.json`, json({ registrationId: detail.registrationId, investigationId: detail.investigationId, import: detail.verification, browserUrl: window.location.href }), 'application/json')}>Download case import record</button>}
        <h2>Case evidence</h2><button disabled={disabled || !verified} onClick={() => void act('Preparing case evidence', prepare)}>Prepare case evidence</button>
        <button disabled={disabled || !verified} onClick={() => void act('Loading saved evidence', refresh)}>Load saved evidence and runs</button>
        {settings && <>
          <p>Provider: <strong>{settings.model.provider} / {settings.model.model}</strong>. {settings.model.mock && 'Mock mode checks the workflow; it does not measure model performance.'}</p>
          <p>Select up to eight source excerpts. Selected: {run ? run.evidence_ids.length : selected.length}.</p>
          {settings.evidence.map(item => <details key={item.id}><summary>{item.sourceRef} · lines {item.startLine}–{item.endLine}</summary><label><input style={{ width: 'auto', display: 'inline' }} type="checkbox" checked={runId ? Boolean(run?.evidence_ids?.includes(item.id)) : selected.includes(item.id)} disabled={disabled || Boolean(runId) || (!selected.includes(item.id) && selected.length >= 8)} onChange={() => toggle(item)} /> Use this evidence</label><p>{item.id}<br />Source hash: {item.sourceHash}</p><pre>{item.text}</pre></details>)}
          {!settings.evidence.length && <p>No saved source evidence. Prepare evidence first. This needs the configured local embedding service.</p>}
          <h2>Bounded workflow</h2>
          <label>Investigation query<input maxLength={200} minLength={2} value={query} disabled={disabled} onChange={event => setQuery(event.target.value)} /></label>
          {!runId && <>
            <label>Maximum model calls<input type="number" min={1} max={8} value={maxCalls} onChange={event => setMaxCalls(Number(event.target.value))} disabled={disabled} /></label>
            <label>Workflow deadline in seconds<input type="number" min={120} max={1800} value={wallSeconds} onChange={event => setWallSeconds(Number(event.target.value))} disabled={disabled} /></label>
            <p>The deadline includes waiting and clicks. Refreshing the page reads saved state and does not send a model request.</p>
            <p>Output-token reservations: {maxCalls * settings.model.maxOutputTokens}. At most three experiment jobs and two compiler repairs per run. Repairs also consume model-call and token reservations.</p>
            <button disabled={disabled || selected.length === 0 || query.trim().length < 2 || !Number.isInteger(maxCalls) || maxCalls < 1 || maxCalls > 8 || !Number.isInteger(wallSeconds) || wallSeconds < 120 || wallSeconds > 1800} onClick={() => void act('Saving bounded run', createRun)}>Create fresh bounded run</button>
          </>}
          {settings.runs.length > 0 && <><h3>Saved runs</h3><ul>{settings.runs.map(item => <li key={item.id}><a href={`#/benchmarks/${investigationId}/${item.id}`}>{item.id} · {item.state}</a></li>)}</ul></>}
        </>}
        {run && <>
          <p>Run: {run.id}<br /><strong>State: {run.state}</strong><br />Model requests reserved: {run.calls}/{run.max_calls}<br />Output tokens reserved: {run.reserved_output_tokens}/{run.max_output_tokens}</p><p>{run.message}</p>
          <button onClick={() => download(`${run.id}-run.json`, json({ investigationId, runId: run.id, run }), 'application/json')}>Download run record</button>
          {run.provider !== 'mock' && <label><input type="checkbox" style={{ width: 'auto', display: 'inline' }} checked={liveApproved} onChange={event => setLiveApproved(event.target.checked)} /> I intentionally authorize live calls using the displayed provider/model. Selected evidence will be sent and charges may apply.</label>}
          <button disabled={disabled || !canAdvance || (run.provider !== 'mock' && !liveApproved)} onClick={() => void act('Advancing one model step', advance)}>{run.state === 'PLAN_READY' ? 'Generate test proposal' : 'Advance one model step'}</button>
          <button disabled={disabled || !['READY_ANALYSIS', 'PLAN_READY', 'TEST_READY'].includes(run.state)} onClick={() => void act('Stopping run', async () => { setRun(checkRun(await request<Run>(`${runPath(investigationId, runId)}/stop`, {}), detail, run.id)) })}>Stop between steps</button>
          <button disabled={disabled} onClick={() => void act('Retrieving case source', retrieve)}>Retrieve case source</button>
          {search !== null && <details open><summary>Saved retrieval result</summary><pre>{json(search)}</pre></details>}
          {run.plan && <><h3>Advisory plan / conclusion</h3><p><strong>{run.plan.outcome}</strong>: {run.plan.hypothesis}</p><p>{run.plan.explanation}</p>{run.plan.missingEvidence.length > 0 && <p>Missing evidence: {run.plan.missingEvidence.join('; ')}</p>}<p>Evidence: {run.plan.evidenceIds.join(', ')}</p></>}
          {run.source_document && <><h3>Generated source proposal</h3><p>{run.source_document.intendedAssertion}</p><p>Fault scenario: {run.source_document.requiredFaultScenario}</p><p>Expected failure: {run.source_document.expectedFailureSignature}</p><pre>{run.source_document.testSource}</pre><button onClick={() => download('GeneratedBenchmarkReproductionTest.java.txt', run.source_document!.testSource)}>Download source proposal</button><p>Save the conclusion record below to persist its source version and hash.</p></>}
          <h3>Execution results</h3>
          <p>Jobs reserved: {run.experiment_jobs ?? 0}/3. Compiler repairs reserved: {run.compiler_repairs ?? 0}/2. Failed and expired reservations are not refunded.</p>
          <label><input type="checkbox" style={{ width: 'auto', display: 'inline' }} checked={trusted} onChange={event => setTrusted(event.target.checked)} /> Enable execution for this trusted benchmark fixture run</label>
          <button disabled={disabled || !trusted || run.state !== 'TEST_READY' || (run.experiment_jobs ?? 0) >= 3} onClick={() => void act('Queuing registered benchmark experiment', execute)}>Compile and run generated test</button>
          <button disabled={disabled} onClick={() => void act('Loading bound experiments', loadExperiments)}>Load saved experiments</button>
          <ul>{experiments.map(item => <li key={item.executionId}><button disabled={disabled} onClick={() => void act('Loading experiment evidence', async () => setExperiment(await request<Experiment>(`${runPath(investigationId, runId)}/experiments/${item.executionId}`)))}>{item.executionId} · v{item.sourceVersion} · {item.queueState} · {item.queueState === 'QUEUED' ? 'Waiting for worker — not executed' : item.result?.classification || item.technicalError || 'Pending'} · {item.observation}</button></li>)}</ul>
          {experiment && <><p>{queueExplanation(experiment.queueState)}</p><p>Source version: {experiment.sourceVersion}<br />Source SHA256: {experiment.sourceSha256}<br />Observation: {experiment.observation}</p><pre>{json(experiment)}</pre><button onClick={() => download(`${experiment.executionId}-benchmark.json`, json(experiment), 'application/json')}>Download execution evidence</button>
          {experiment.result?.classification === 'COMPILATION_FAILED' && experiment.sourceVersion === Math.max(0, ...experiments.map(item => item.sourceVersion)) && <><p>Only compiler diagnostics naming the generated Java file qualify. Review logs before requesting a repair.</p>{((run.compiler_repairs ?? 0) >= 2 || (run.experiment_jobs ?? 0) >= 3) ? <p>REPAIR_EXHAUSTED — generated test remains invalid.</p> : <button disabled={disabled || (run.provider !== 'mock' && !liveApproved)} onClick={() => void act('Requesting bounded compiler repair', repair)}>Repair generated-file compilation error</button>}</>}
          </>}
          <h3>Save and reload conclusion</h3><button disabled={disabled || run.state === 'BUSY'} onClick={() => void act('Saving case record', exportRecord)}>Save conclusion record</button>
          <button disabled={disabled} onClick={() => void act('Loading saved records', refresh)}>Load saved conclusion records</button>
          <ul>{records.map(item => <li key={item.id}><button disabled={disabled} onClick={() => void act('Revalidating saved record', () => loadRecord(item.id))}>{item.kind} · {item.id}</button></li>)}</ul>
          {record && <><p>Record: {record.recordId}<br />Hash: {record.documentSha256}</p>{record.document.generatedSource && <p>Source version {record.document.generatedSource.version} · {record.document.generatedSource.sourceSha256}</p>}<button onClick={() => download(`${record.recordId}.json`, json(record), 'application/json')}>Download saved record JSON</button><details open><summary>Saved conclusion and provenance</summary><pre>{json(record.document)}</pre></details></>}
          <details><summary>Saved model steps</summary><pre>{json(run.steps)}</pre>{run.steps.filter(step => step.call_id).map(step => <button key={step.id} disabled={disabled} onClick={() => void act('Loading owned model call', async () => { setCallView(await request(`${runPath(investigationId, runId)}/records/calls/${step.call_id}`)) })}>Inspect model call {step.call_id}</button>)}{callView !== null && <pre>{json(callView)}</pre>}</details>
        </>}
      </>}
    </>}
  </section>
}
