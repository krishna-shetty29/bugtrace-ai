import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'

type Ref = { kind: string; indexId: string; chunkKey: string | null; chunkNumber: number | null }
type Evidence = { id: string; reference: Ref; origin: string; sourceRef: string; revision: string; lineKind: string; startLine: number; endLine: number; sourceHash: string; text: string }
type Configuration = { provider: string; model: string; mock: boolean; promptVersion: string; timeoutSeconds: number }
type Call = { id: string; provider: string; requested_model: string; actual_model: string | null; prompt_version: string; state: string; response_text?: string | null; input_tokens: number | null; output_tokens: number | null; total_tokens: number | null; usage_kind: string; duration_ms: number | null; error_code: string | null; error_message?: string | null; input_evidence?: unknown; created_at: string }

export default function AiModelPanel({ investigationId }: { investigationId: string }) {
  const [configuration, setConfiguration] = useState<Configuration | null>(null)
  const [evidence, setEvidence] = useState<Evidence[]>([])
  const [selected, setSelected] = useState<string[]>([])
  const [calls, setCalls] = useState<Call[]>([])
  const [call, setCall] = useState<Call | null>(null)
  const [question, setQuestion] = useState('Summarize the supplied checkout evidence and identify what remains unverified.')
  const [requestId, setRequestId] = useState(() => crypto.randomUUID())
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const active = useRef<AbortController | null>(null)
  useEffect(() => () => active.current?.abort(), [])
  async function request<T>(suffix: string, signal: AbortSignal, body?: unknown): Promise<T> {
    const response = await fetch(`/api/investigations/${investigationId}/ai${suffix}`, { signal, method: body === undefined ? 'GET' : 'POST', headers: body === undefined ? { Accept: 'application/json' } : { Accept: 'application/json', 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
    const text = await response.text()
    if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 1500)}`)
    return JSON.parse(text) as T
  }
  async function run(action: string, id?: string) {
    const controller = new AbortController(); active.current?.abort(); active.current = controller; setBusy(action); setError('')
    try {
      if (action === 'load') {
        setConfiguration(await request<Configuration>('/configuration', controller.signal))
        setEvidence(await request<Evidence[]>('/evidence', controller.signal))
        setCalls(await request<Call[]>('/calls', controller.signal))
      } else if (action === 'call') setCall(await request<Call>(`/calls/${id || requestId}`, controller.signal))
      else if (action === 'submit') {
        setCall(await request<Call>('/calls', controller.signal, { clientRequestId: requestId, question, evidence: evidence.filter(item => selected.includes(item.id)).map(item => item.reference) }))
        setCalls(await request<Call[]>('/calls', controller.signal))
      }
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : 'Model integration request failed') }
    finally { if (!controller.signal.aborted) setBusy('') }
  }
  function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); void run('submit') }
  function toggle(id: string) { setSelected(items => items.includes(id) ? items.filter(item => item !== id) : [...items, id]) }
  return <section style={{ overflowWrap: 'anywhere' }} aria-labelledby="ai-model-heading">
    <h2 id="ai-model-heading">Model integration</h2>
    <p>Default mode is a mock integration check. Responses are advisory and do not run tests or change investigation outcomes. Credentials stay in backend configuration.</p>
    <button type="button" disabled={!!busy} onClick={() => void run('load')}>Load model settings and evidence</button>
    {configuration && <><p><strong>{configuration.mock ? 'MOCK MODE — no external model call' : 'LIVE PROVIDER — selected evidence is sent to OpenAI and API charges may apply'}</strong><br />Provider: {configuration.provider} · Model: {configuration.model}<br />Prompt version: {configuration.promptVersion}</p>
      <details><summary>Select saved evidence ({selected.length}/8)</summary><p>Showing up to 80 current code chunks and 80 supplementary chunks. Build the existing source/context indexes first if needed.</p>
        {!evidence.length && <p>No saved evidence available. Build your Step 25 source embeddings or Step 26 context collection, then load again.</p>}
        {evidence.map(item => <details key={item.id}><summary><label style={{ display: 'inline' }}><input style={{ display: 'inline', width: 'auto', marginRight: 8 }} type="checkbox" checked={selected.includes(item.id)} disabled={!selected.includes(item.id) && selected.length >= 8} onChange={() => toggle(item.id)} />{item.origin} · {item.sourceRef} · {item.lineKind} {item.startLine}–{item.endLine}</label></summary><p>Evidence ID: <code>{item.id}</code><br />Revision: <code>{item.revision}</code></p><pre>{item.text}</pre></details>)}
      </details>
      <form onSubmit={submit}><label>Question<textarea required maxLength={2000} value={question} onChange={event => setQuestion(event.target.value)} /></label><p>Request ID: <code>{requestId}</code></p>
        <button disabled={!!busy || selected.length === 0}>{configuration.mock ? 'Run mock model call' : 'Send selected evidence to OpenAI'}</button>{' '}
        <button type="button" disabled={!!busy} onClick={() => { setRequestId(crypto.randomUUID()); setCall(null) }}>New request ID</button>{' '}
        <button type="button" disabled={!!busy} onClick={() => void run('call')}>Load this request</button>
        <p>The same request ID does not send a second call. Use a new ID for changed inputs or an intentional new attempt. After a connection error, load the saved request before retrying.</p>
      </form></>}
    {busy && <p role="status">{busy === 'submit' ? 'Waiting for the backend model call…' : 'Loading…'}</p>}
    {error && <p className="error" role="alert">{error}</p>}
    {call && <details open><summary>Saved call · {call.state} · {call.id}</summary><p>Provider: {call.provider} · Requested model: {call.requested_model} · Returned model: {call.actual_model || 'not available'}<br />Prompt: {call.prompt_version}<br />Duration: {call.duration_ms === null ? 'pending' : `${call.duration_ms} ms`}</p>
      <p>Usage: {call.usage_kind}{call.usage_kind === 'MOCK_NOT_MEASURED' ? ' — no real model tokens were measured' : ''}<br />Input tokens: {call.input_tokens ?? 'unavailable'} · Output tokens: {call.output_tokens ?? 'unavailable'} · Total: {call.total_tokens ?? 'unavailable'}</p>
      {call.error_code && <p className="error">{call.error_code}: {call.error_message}</p>}
      <pre>{call.response_text || 'No response text was recorded.'}</pre>
      <details><summary>Saved input evidence IDs and source references</summary><pre>{JSON.stringify(call.input_evidence, null, 2)}</pre></details>
    </details>}
    <h3>Recent model calls</h3>{calls.map(item => <p key={item.id}><button type="button" disabled={!!busy} onClick={() => void run('call', item.id)}>{item.state} · {item.provider} · {item.id}</button></p>)}
  </section>
}
