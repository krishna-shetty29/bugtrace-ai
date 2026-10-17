import { useState } from 'react'

type Ref = { kind: string; indexId: string; chunkKey: string | null; chunkNumber: number | null }
type Evidence = { id: string; reference: Ref; origin: string; sourceRef: string; text: string }
type Execution = { execution_id: string; classification: string; operation: string }
type Step = { role: string; state: string; call_id: string; error_message?: string; output?: { summary: string; evidenceIds: string[]; missingEvidence: string[]; testDraft: string; patchDraft: string; checks: string[] } }
type Run = { id: string; state: string; next_role: number; used_calls: number; max_calls: number; max_output_tokens: number; reserved_output_tokens: number; deadline: string; notice: string; error_message?: string; steps: Step[]; roles: string[]; execution_results: unknown }
type Saved = { id: string; state: string }
const roles = [
  ['Investigation coordinator', 'Java backend controls order, reservations, deadline and stored state.'],
  ['Evidence analyst', 'Interprets selected retrieved evidence.'],
  ['Reproduction agent', 'Proposes an experiment and may generate an uncompiled test draft.'],
  ['Diagnosis agent', 'Explains an observed failure using recorded execution evidence.'],
  ['Patch agent', 'Generates an unapplied change proposal.'],
  ['Verification agent', 'Proposes approved checks and summarizes actual recorded results.'],
]

export default function AgentRolesPanel({ investigationId }: { investigationId: string }) {
  const [evidence, setEvidence] = useState<Evidence[]>([])
  const [selected, setSelected] = useState<string[]>([])
  const [executions, setExecutions] = useState<Execution[]>([])
  const [chosenExecutions, setChosenExecutions] = useState<string[]>([])
  const [saved, setSaved] = useState<Saved[]>([])
  const [run, setRun] = useState<Run | null>(null)
  const [runId, setRunId] = useState<string>(() => crypto.randomUUID())
  const [provider, setProvider] = useState('Load settings first')
  const [perCall, setPerCall] = useState(1500)
  const [maxCalls, setMaxCalls] = useState(5)
  const [maxTokens, setMaxTokens] = useState(7500)
  const [deadline, setDeadline] = useState(1800)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function api<T>(suffix: string, body?: unknown): Promise<T> {
    const response = await fetch(`/api/investigations/${investigationId}${suffix}`, {
      method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const text = await response.text()
    if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 1500)}`)
    return JSON.parse(text) as T
  }
  async function action(kind: string, id?: string) {
    setBusy(true); setError('')
    try {
      if (kind === 'load') {
        const [items, options, runs, config] = await Promise.all([
          api<Evidence[]>('/ai/evidence'), api<Execution[]>('/agent-runs/execution-options'),
          api<Saved[]>('/agent-runs'), api<{ provider: string; model: string; maxOutputTokens: number }>('/ai/configuration'),
        ])
        setEvidence(items); setExecutions(options); setSaved(runs)
        setProvider(`${config.provider} / ${config.model}`); setPerCall(config.maxOutputTokens)
        setMaxTokens(config.maxOutputTokens * 5)
      } else if (kind === 'create') {
        setRun(await api<Run>('/agent-runs', { runId, evidence: evidence.filter(item => selected.includes(item.id)).map(item => item.reference), executionIds: chosenExecutions, maxCalls, maxOutputTokens: maxTokens, deadlineSeconds: deadline }))
      } else if (kind === 'get') {
        const loaded = await api<Run>(`/agent-runs/${id ?? runId}`)
        setRun(loaded); setRunId(loaded.id)
      } else if (kind === 'advance' && run) {
        setRun(await api<Run>(`/agent-runs/${run.id}/advance`, { expectedRole: run.next_role }))
      } else if (kind === 'stop' && run) {
        setRun(await api<Run>(`/agent-runs/${run.id}/stop`, {}))
      }
    } catch (ex) { setError(ex instanceof Error ? ex.message : 'Role workflow failed') }
    finally { setBusy(false) }
  }
  return <section>
    <h2>Bounded agent roles</h2>
    <p>One backend coordinates sequential roles. Tests and patches remain unvalidated text drafts. No worker job is created here.</p>
    <table><thead><tr><th>Role</th><th>Responsibility</th></tr></thead><tbody>{roles.map(([name, description]) => <tr key={name}><td>{name}</td><td>{description}</td></tr>)}</tbody></table>
    <button disabled={busy} onClick={() => void action('load')}>Load role settings and evidence</button>
    <p>Provider: {provider}. Live advances may incur API charges. Configured output limit: {perCall} tokens per call.</p>
    <details><summary>Selected evidence ({selected.length}/8)</summary>{evidence.map(item => <div key={item.id}><label><input type="checkbox" disabled={busy || (!selected.includes(item.id) && selected.length >= 8)} checked={selected.includes(item.id)} onChange={event => setSelected(event.target.checked ? [...selected, item.id] : selected.filter(id => id !== item.id))} /> {item.origin} · {item.sourceRef}</label><details><summary>View snippet</summary><pre>{item.text}</pre></details></div>)}</details>
    <details><summary>Actual saved executions ({chosenExecutions.length}/5)</summary>{executions.length === 0 && <p>No completed executions for this investigation and snapshot. Run the existing reproduction using your execution page first for a live diagnosis.</p>}{executions.map(item => <p key={item.execution_id}><label><input type="checkbox" disabled={busy || (!chosenExecutions.includes(item.execution_id) && chosenExecutions.length >= 5)} checked={chosenExecutions.includes(item.execution_id)} onChange={event => setChosenExecutions(event.target.checked ? [...chosenExecutions, item.execution_id] : chosenExecutions.filter(id => id !== item.execution_id))} /> {item.execution_id} · {item.operation} · {item.classification}</label></p>)}</details>
    <label>Maximum role calls<input type="number" min={1} max={5} value={maxCalls} onChange={event => setMaxCalls(Number(event.target.value))} /></label>
    <label>Output-token reservation budget<input type="number" min={perCall} max={20000} value={maxTokens} onChange={event => setMaxTokens(Number(event.target.value))} /></label>
    <label>Run deadline in seconds, including time between clicks<input type="number" min={60} max={3600} value={deadline} onChange={event => setDeadline(Number(event.target.value))} /></label>
    <p>Run UUID: <code>{runId}</code></p>
    <button disabled={busy || !selected.length} onClick={() => void action('create')}>Create bounded run</button>{' '}
    <button disabled={busy} onClick={() => { setRunId(crypto.randomUUID()); setRun(null) }}>New run ID</button>{' '}
    <button disabled={busy} onClick={() => void action('get')}>Load this saved run</button>
    {error && <p role="alert">{error}</p>}
    {run && <div><h3>{run.state}</h3><p>{run.notice}</p><p>Calls reserved: {run.used_calls}/{run.max_calls}. Output tokens reserved: {run.reserved_output_tokens}/{run.max_output_tokens}. Deadline: {run.deadline}</p>{run.error_message && <p>{run.error_message}</p>}
      <button disabled={busy || run.state !== 'READY'} onClick={() => void action('advance')}>{busy ? 'Working…' : `Advance: ${run.roles[run.next_role] ?? 'No next role'}`}</button>{' '}
      <button disabled={busy || run.state !== 'READY'} onClick={() => void action('stop')}>Stop between roles</button>
      <p>One advance requests one role. Refreshing only reads saved state. A running call cannot be cancelled by this panel.</p>
      <details><summary>Actual execution evidence, separate from model proposals</summary><pre>{JSON.stringify(run.execution_results, null, 2)}</pre></details>
      {run.steps.map(step => <article key={step.role}><h4>{step.role}: {step.state}</h4><p>Saved model call: <code>{step.call_id}</code></p>{step.error_message && <p>{step.error_message}</p>}{step.output && <><p>{step.output.summary}</p><p>Evidence IDs: {step.output.evidenceIds.join(', ') || 'None — insufficient evidence'}</p><p>Missing evidence: {step.output.missingEvidence.join('; ') || 'None declared by model'}</p>{step.output.testDraft && <details><summary>Uncompiled test draft — not executed</summary><pre>{step.output.testDraft}</pre></details>}{step.output.patchDraft && <details><summary>Unapplied patch draft — not verified</summary><pre>{step.output.patchDraft}</pre></details>}<p>Proposed checks: {step.output.checks.join(', ') || 'None'}</p></>}</article>)}
    </div>}
    <h3>Recent role runs</h3><p>Reload settings to refresh this list.</p>{saved.map(item => <p key={item.id}><button disabled={busy} onClick={() => void action('get', item.id)}>{item.id} · {item.state}</button></p>)}
  </section>
}
