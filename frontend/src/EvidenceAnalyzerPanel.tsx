import { useState } from 'react'

type Ref = { kind: string; indexId: string; chunkKey: string | null; chunkNumber: number | null }
type Evidence = { id: string; reference: Ref; origin: string; sourceRef: string; startLine: number; endLine: number; text: string }
type Saved = { id: string; prompt_version: string; state: string }
type Result = { analysisState: string; mock: boolean; notice: string; analysis?: { hypothesis: string; evidenceIds: string[]; suggestedExperiment: string; missingEvidence: string[] }; validationError?: string; call: { id: string; state: string; response_text?: string; error_message?: string; input_evidence?: unknown } }

export default function EvidenceAnalyzerPanel({ investigationId }: { investigationId: string }) {
  const [items, setItems] = useState<Evidence[]>([])
  const [selected, setSelected] = useState<string[]>([])
  const [requestId, setRequestId] = useState<string>(() => crypto.randomUUID())
  const [calls, setCalls] = useState<Saved[]>([])
  const [result, setResult] = useState<Result | null>(null)
  const [provider, setProvider] = useState('Load settings first')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function api<T>(path: string, body?: unknown): Promise<T> {
    const response = await fetch(`/api/investigations/${investigationId}${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body),
    })
    const text = await response.text()
    if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 1500)}`)
    return JSON.parse(text) as T
  }
  async function run(action: 'load' | 'analyze' | 'get', id?: string) {
    setBusy(true); setError('')
    try {
      if (action === 'load') {
        const [evidence, config, saved] = await Promise.all([
          api<Evidence[]>('/ai/evidence'), api<{ provider: string; model: string }>('/ai/configuration'), api<Saved[]>('/ai/calls'),
        ])
        setItems(evidence); setProvider(`${config.provider} / ${config.model}`)
        setCalls(saved.filter(call => call.prompt_version === 'evidence-hypothesis-v1'))
      } else if (action === 'get') {
        const saved = await api<Result>(`/hypotheses/${id ?? requestId}`)
        setResult(saved); setRequestId(saved.call.id)
      } else {
        setResult(await api<Result>('/hypotheses', { clientRequestId: requestId, evidence: items.filter(item => selected.includes(item.id)).map(item => item.reference) }))
      }
    } catch (ex) { setError(ex instanceof Error ? ex.message : 'Request failed') }
    finally { setBusy(false) }
  }
  return <section>
    <h2>Evidence analyzer</h2>
    <p>Proposes an advisory hypothesis. Suggested experiments are text only; this panel executes no tests or changes.</p>
    <p>Provider: {provider}. Live calls send selected evidence and the saved report to your configured provider and may incur charges.</p>
    <button disabled={busy} onClick={() => void run('load')}>Load analyzer settings and evidence</button>
    <p>The saved bug description, expected behavior and observed behavior are included automatically. Select relevant current code, logs and test results below.</p>
    <details><summary>Select evidence ({selected.length}/8)</summary>
      {items.map(item => <div key={item.id}><label><input type="checkbox" checked={selected.includes(item.id)} disabled={busy || (!selected.includes(item.id) && selected.length >= 8)} onChange={event => setSelected(event.target.checked ? [...selected, item.id] : selected.filter(id => id !== item.id))} /> {item.origin} · {item.sourceRef} · lines {item.startLine}–{item.endLine}</label><details><summary>View snippet</summary><pre>{item.text}</pre></details></div>)}
    </details>
    <p>Request ID: <code>{requestId}</code></p>
    <button disabled={busy || !selected.length} onClick={() => void run('analyze')}>{busy ? 'Working…' : 'Propose hypothesis'}</button>{' '}
    <button disabled={busy} onClick={() => { setRequestId(crypto.randomUUID()); setResult(null) }}>New request ID</button>{' '}
    <button disabled={busy} onClick={() => void run('get')}>Load this saved hypothesis</button>
    <p>Use a new request ID after changing evidence or the report. Loading a saved hypothesis does not call the model again.</p>
    {error && <p role="alert">{error}</p>}
    {result && <div><h3>{result.mock ? 'MOCK — synthetic integration result' : 'Advisory hypothesis'}</h3><p>{result.analysisState}: {result.notice}</p>{result.validationError && <p role="alert">{result.validationError}</p>}{result.call.error_message && <p>{result.call.error_message}</p>}<pre>{JSON.stringify(result.analysis ?? { rawResponse: result.call.response_text }, null, 2)}</pre><details><summary>Saved call and exact input references</summary><pre>{JSON.stringify(result.call, null, 2)}</pre></details></div>}
    <h3>Recent saved hypotheses</h3>
    <p>Reload settings to refresh this list (the latest 30 model calls are searched).</p>
    {calls.map(call => <p key={call.id}><button disabled={busy} onClick={() => void run('get', call.id)}>{call.id} · {call.state}</button></p>)}
  </section>
}

