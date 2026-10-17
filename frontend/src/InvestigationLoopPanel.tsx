import { useEffect, useRef, useState } from 'react'

type Ref = { kind: string; indexId: string; chunkKey: string | null; chunkNumber: number | null }
type Evidence = { id: string; reference: Ref; sourceRef: string; origin: string; text: string }
type Limits = { maxExperiments: number; maxJobs: number; maxCalls: number; maxOutputTokens: number; totalExecutionSeconds: number; perExecutionSeconds: number; wallSeconds: number; maxRepairs: number }
type Metrics = { repetitions: number; completed: number; duplicates: number; passes: number; errors: number; clientTimeouts: number; duplicateRate: number | null; wilson95Lower: number | null; wilson95Upper: number | null; interpretation: string }
type Round = { number: number; plan: unknown; retrieval: unknown; source_document: { testSource: string } | null; source_sha: string | null; execution_id: string | null; outcome: string | null; actual_result: unknown; review: unknown; scenario_report: Metrics | null }
type Run = { id: string; version: number; state: string; message: string; terminal: boolean; execution_enabled: boolean; limits: Limits; calls: number; experiments: number; jobs: number; reserved_output_tokens: number; reserved_execution_seconds: number; deadline: string; rounds: Round[]; steps: unknown[]; approvedTemplate: unknown }
type Settings = { model: { provider: string; model: string; maxOutputTokens?: number }; evidence: Evidence[]; runs: { id: string; state: string }[] }
async function api<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, { method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await response.text()
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 1200)}`)
  return JSON.parse(text) as T
}
const next: Record<string, string> = { READY_HYPOTHESIS: 'Retrieve evidence and generate hypothesis', PLAN_READY: 'Validate experiment plan', PLAN_VALIDATED: 'Generate experiment test', DRAFT_READY: 'Compile and execute generated test', COMPILE_ERROR: 'Repair generated-file compilation error', RESULT_READY: 'Update hypothesis from actual result' }
export default function InvestigationLoopPanel({ investigationId }: { investigationId: string }) {
  const base = `/api/investigations/${investigationId}/loops`
  const [settings, setSettings] = useState<Settings | null>(null)
  const [selected, setSelected] = useState<string[]>([])
  const [query, setQuery] = useState('charge')
  const [scenario, setScenario] = useState('RETRY_AFTER_RESPONSE_LOSS')
  const [repetitions, setRepetitions] = useState(20)
  const [limits, setLimits] = useState<Limits>({ maxExperiments: 2, maxJobs: 3, maxCalls: 8, maxOutputTokens: 12000, totalExecutionSeconds: 900, perExecutionSeconds: 300, wallSeconds: 1800, maxRepairs: 2 })
  const [runId, setRunId] = useState<string>(() => crypto.randomUUID())
  const [run, setRun] = useState<Run | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const mounted = useRef(true)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  async function load(id: string) { const saved = await api<Run>(`${base}/${id}`); if (mounted.current) { setRunId(id); setRun(saved) } }
  async function action(kind: string, id = runId) {
    setBusy(true); setError('')
    try {
      if (kind === 'settings') setSettings(await api<Settings>(`${base}/settings`))
      else if (kind === 'load') await load(id)
      else if (kind === 'create') setRun(await api<Run>(base, { id: runId, query, limits, scenario, repetitions, evidence: settings?.evidence.filter(item => selected.includes(item.id)).map(item => item.reference) }))
      else if (run) setRun(await api<Run>(`${base}/${run.id}/step`, { requestId: crypto.randomUUID(), expectedVersion: run.version, command: kind }))
    } catch (ex) { setError(`${ex instanceof Error ? ex.message : 'Loop request failed'}. Load saved loop before trying another action.`) }
    finally { setBusy(false) }
  }
  useEffect(() => {
    if (!run || !['WAITING', 'BUSY'].includes(run.state)) return
    const timer = window.setInterval(() => { void load(run.id).catch(ex => setError(String(ex))) }, 3000)
    return () => window.clearInterval(timer)
    // Polling reads saved state only; no model call or next-step request occurs here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run?.id, run?.state, base])
  const fields: [keyof Limits, string, number, number][] = [
    ['maxExperiments', 'Maximum experiment plans', 1, 3], ['maxJobs', 'Maximum jobs including compiler retries', 1, 6],
    ['maxCalls', 'Maximum model requests including repairs and reviews', 1, 12], ['maxOutputTokens', 'Reserved output-token budget', 1, 20000],
    ['totalExecutionSeconds', 'Cumulative reserved execution seconds', 1, 1800], ['perExecutionSeconds', 'Per-job seconds', 1, 300],
    ['wallSeconds', 'Whole-loop deadline in seconds, including waiting and clicks', 60, 3600], ['maxRepairs', 'Compiler repairs per experiment', 0, 2],
  ]
  return <section>
    <h2>Bounded investigation loop</h2>
    <p>Each Advance performs one saved step. Polling never calls a model. Choose a controlled scenario and repetition count for the trusted checkout fixture. The reference fix stays outside inputs.</p>
    <button disabled={busy} onClick={() => void action('settings')}>Load loop settings and evidence</button>
    {settings && <>
      <p>Provider: {settings.model.provider} / {settings.model.model}. Live calls send selected evidence and report to the configured provider.</p>
      <details><summary>Loop evidence ({selected.length}/8)</summary>{settings.evidence.map(item => <div key={item.id}><label><input type="checkbox" checked={selected.includes(item.id)} disabled={busy || (!selected.includes(item.id) && selected.length >= 8)} onChange={event => setSelected(event.target.checked ? [...selected, item.id] : selected.filter(id => id !== item.id))} /> {item.origin} · {item.sourceRef}</label><details><summary>View snippet</summary><pre>{item.text}</pre></details></div>)}</details>
      <label>Literal retrieval query<input value={query} maxLength={200} onChange={event => setQuery(event.target.value)} /></label>
      <label>Controlled scenario<select value={scenario} onChange={event => setScenario(event.target.value)}>
        <option value="RETRY_AFTER_RESPONSE_LOSS">Successful charge, response lost, then retry</option>
        <option value="DELAYED_RESPONSE">Delayed response and client wait timeout</option>
        <option value="CONCURRENT_RETRIES">Two concurrent retries after response loss</option>
        <option value="RETRY_AFTER_COMPLETION">Retry after completed checkout</option>
      </select></label>
      <label>Repetitions in each experiment batch<input type="number" min={2} max={30} value={repetitions} onChange={event => setRepetitions(Number(event.target.value))} /></label>
      <p>One batch uses one job and runs 2–30 isolated repetitions. A client wait timeout does not cancel server processing. These controls are pinned when you create the loop.</p>
      {fields.map(([key, label, min, max]) => <label key={key}>{label}<input type="number" min={min} max={max} value={limits[key]} onChange={event => setLimits({ ...limits, [key]: Number(event.target.value) })} /></label>)}
      <p>Token limit reserves maximum output tokens before every call. Model input is separately capped at 24,000 characters per request. This is not a total billing or monetary cap. Time/job reservations are never refunded; crash retries are disabled for loop jobs.</p>
      <p>New/saved loop UUID: <code>{runId}</code></p>
      <button disabled={busy || !selected.length} onClick={() => void action('create')}>Create bounded loop</button>{' '}
      <button disabled={busy} onClick={() => { setRunId(crypto.randomUUID()); setRun(null) }}>New loop ID</button>{' '}
      <button disabled={busy} onClick={() => void action('load')}>Load saved loop</button>
      <h3>Recent saved loops</h3>{settings.runs.map(item => <p key={item.id}><button disabled={busy} onClick={() => void action('load', item.id)}>{item.id} · {item.state}</button></p>)}
    </>}
    {error && <p role="alert">{error}</p>}
    {run && <>
      <h3>{run.state}</h3><p>{run.message}</p><p>Saved loop UUID: <code>{run.id}</code> · version {run.version}</p>
      <p>Experiments {run.experiments}/{run.limits.maxExperiments}; jobs {run.jobs}/{run.limits.maxJobs}; model requests {run.calls}/{run.limits.maxCalls}; output tokens reserved {run.reserved_output_tokens}/{run.limits.maxOutputTokens}; execution seconds reserved {run.reserved_execution_seconds}/{run.limits.totalExecutionSeconds}. Deadline: {run.deadline}</p>
      {!run.terminal && <>
        <button disabled={busy || !next[run.state] || (run.state === 'DRAFT_READY' && !run.execution_enabled)} onClick={() => void action('ADVANCE')}>{busy ? 'Working…' : `Advance: ${next[run.state] ?? 'Waiting for saved result'}`}</button>{' '}
        <button disabled={busy || ['BUSY', 'WAITING'].includes(run.state)} onClick={() => void action('STOP')}>Stop loop between steps</button>{' '}
        {!run.execution_enabled && <button disabled={busy || ['BUSY', 'WAITING'].includes(run.state)} onClick={() => void action('ENABLE')}>Enable execution for this trusted fixture loop</button>}
      </>}
      <p>FINISHED_REPRODUCED requires the generated class’s actual expected assertion and a saved review. It is not a proven diagnosis or verified fix. Earlier role-run permissions do not grant execution permission to this loop.</p>
      <details><summary>Approved experiment template and immutable assertion</summary><pre>{JSON.stringify(run.approvedTemplate, null, 2)}</pre></details>
      {run.rounds.map(round => <article key={round.number}><h3>Experiment {round.number}</h3>
        <details><summary>Retrieved source references</summary><pre>{JSON.stringify(round.retrieval, null, 2)}</pre></details>
        <details open={run.state === 'PLAN_READY'}><summary>Hypothesis and proposed experiment</summary><pre>{JSON.stringify(round.plan, null, 2)}</pre></details>
        {round.source_document && <details open={run.state === 'DRAFT_READY'}><summary>Generated test · SHA-256 {round.source_sha}</summary><pre>{round.source_document.testSource}</pre></details>}
        {round.execution_id && <p><a href={`#/executions/${round.execution_id}`} target="_blank" rel="noreferrer">Open actual execution {round.execution_id}</a></p>}
        <p>Observed outcome: {round.outcome ?? 'Not observed'}</p>
        {round.scenario_report && <div><h4>Repeated scenario results</h4>
          <p>Requested {round.scenario_report.repetitions}; completed {round.scenario_report.completed}; duplicate repetitions {round.scenario_report.duplicates}; clean repetitions {round.scenario_report.passes}; scenario errors {round.scenario_report.errors}; observed client timeouts {round.scenario_report.clientTimeouts}.</p>
          <p>Duplicate rate: {round.scenario_report.duplicateRate === null ? 'Unavailable' : `${(100 * round.scenario_report.duplicateRate).toFixed(1)}%`} of valid repetitions.
          {round.scenario_report.wilson95Lower !== null && round.scenario_report.wilson95Upper !== null && ` Descriptive Wilson 95% interval: ${(100 * round.scenario_report.wilson95Lower).toFixed(1)}%–${(100 * round.scenario_report.wilson95Upper).toFixed(1)}%.`}</p>
          <p>{round.scenario_report.interpretation}</p>
          <details><summary>Per-repetition charge counts</summary><pre>{JSON.stringify(round.scenario_report, null, 2)}</pre></details>
        </div>}
        <details><summary>Actual result evidence</summary><pre>{JSON.stringify(round.actual_result, null, 2)}</pre></details>
        <details><summary>Updated hypothesis and finish/continue proposal</summary><pre>{JSON.stringify(round.review, null, 2)}</pre></details>
      </article>)}
      <details><summary>Saved step audit and immutable source versions</summary><pre>{JSON.stringify(run.steps, null, 2)}</pre></details>
    </>}
  </section>
}
