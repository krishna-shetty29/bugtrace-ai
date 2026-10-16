import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'

type Context = { key: string; filePath: string | null; startLine: number; endLine: number; name: string; text: string; executionId: string | null; cosineSimilarity: number | null }
type Ranked = { context: Context; score: number; channels: Record<string, number> }
type Index = { id: string; state: string; chunk_count: number; model_digest: string; diagnostics: string; technical_error: string | null }
type Status = { snapshotId: string; contentHash: string; model: string; indexes: Index[] }
type Neighbor = { relation: string; direction: string; confidence: string; reason: string; anchor: string; context: Context }
type Results = { snapshotId: string; contentHash: string; query: string; semanticUsed: boolean; contexts: Ranked[]; neighbors: Neighbor[]; notices: string[] }

async function request<T>(path: string, signal: AbortSignal, post = false): Promise<T> {
  const response = await fetch(`/api${path}`, { signal, method: post ? 'POST' : 'GET', headers: { Accept: 'application/json' } })
  const text = await response.text()
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 1500)}`)
  return JSON.parse(text) as T
}
function Source({ value }: { value: Context }) {
  return <><p><strong>{value.name}</strong><br />{value.filePath || 'Saved execution log'} · lines {value.startLine}–{value.endLine}
    {value.executionId && <> · <a href={`#/executions/${value.executionId}`}>Execution</a></>}</p><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{value.text}</pre></>
}
export default function SemanticRetrievalPanel({ investigationId }: { investigationId: string }) {
  const [status, setStatus] = useState<Status | null>(null)
  const [results, setResults] = useState<Results | null>(null)
  const [query, setQuery] = useState('retry after payment confirmation is lost')
  const [semantic, setSemantic] = useState(true)
  const [neighbors, setNeighbors] = useState(true)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const active = useRef<AbortController | null>(null)
  useEffect(() => () => active.current?.abort(), [investigationId])
  async function run(action: string) {
    const controller = new AbortController(); active.current?.abort(); active.current = controller
    setBusy(action); setError('')
    try {
      const base = `/investigations/${investigationId}`
      if (action === 'retrieve') {
        const params = new URLSearchParams({ q: query, limit: '10', semantic: String(semantic), neighbors: String(neighbors) })
        setResults(await request<Results>(`${base}/retrieve?${params}`, controller.signal))
      } else setStatus(await request<Status>(`${base}/semantic-index`, controller.signal, action === 'build'))
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : 'Retrieval failed') }
    finally { if (!controller.signal.aborted) setBusy('') }
  }
  function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); void run('retrieve') }
  return <section aria-labelledby="semantic-heading">
    <h2 id="semantic-heading">Semantic retrieval</h2>
    <p>Build local embeddings once for this verified snapshot. Source stays in your local services. A build can take several minutes; keep this page open.</p>
    <button type="button" disabled={!!busy} onClick={() => void run('build')}>Build embeddings</button>{' '}
    <button type="button" disabled={!!busy} onClick={() => void run('status')}>Load embedding status</button>
    {busy && <p role="status">{busy === 'build' ? 'Building embeddings…' : busy === 'retrieve' ? 'Retrieving context…' : 'Loading status…'}</p>}
    {error && <p className="error" role="alert">{error}</p>}
    {status && <><p>Snapshot: <code>{status.snapshotId}</code><br />Model: {status.model}<br />Content hash: <code>{status.contentHash}</code></p>
      {!status.indexes.length && <p>No saved embedding index yet.</p>}
      {status.indexes.map(index => <details key={index.id} open={index.state === 'PARTIAL' || index.state === 'FAILED'}>
        <summary>{index.state} · {index.chunk_count} chunks</summary><p>Model digest: <code>{index.model_digest}</code></p>
        {index.technical_error && <p className="error">{index.technical_error}</p>}<pre>{index.diagnostics}</pre>
      </details>)}</>}
    <form onSubmit={submit}>
      <label>Describe the code or behavior you need<input value={query} onChange={event => setQuery(event.target.value)} required minLength={2} maxLength={200} /></label>
      <p><label><input style={{ display: 'inline', width: 'auto', marginRight: 8 }} type="checkbox" checked={semantic} onChange={event => setSemantic(event.target.checked)} /> Include semantic matches</label></p>
      <p><label><input style={{ display: 'inline', width: 'auto', marginRight: 8 }} type="checkbox" checked={neighbors} onChange={event => setNeighbors(event.target.checked)} /> Include nearby callers, callees and tests</label></p>
      <button disabled={!!busy}>Retrieve context</button>
    </form>
    {results && <>
      <p>Semantic matches used: <strong>{results.semanticUsed ? 'Yes' : 'No'}</strong> · Snapshot: <code>{results.snapshotId}</code></p>
      {results.notices.map((notice, n) => <p key={n}>{notice}</p>)}
      <p>Results combine exact, symbol and semantic ranks. Similarity and fused scores are ranking aids, not confidence probabilities.</p>
      {!results.contexts.length && <p>No matching context.</p>}
      {results.contexts.map((hit, n) => <details key={hit.context.key} open={n < 3}><summary>{n + 1}. {hit.context.name} · {Object.keys(hit.channels).join(' + ')}</summary>
        <p>Ranks: {Object.entries(hit.channels).map(([channel, rank]) => `${channel}: ${rank}`).join(' · ')} · Fused score: {hit.score.toFixed(5)}
          {hit.context.cosineSimilarity !== null && <> · Cosine similarity: {hit.context.cosineSimilarity.toFixed(3)}</>}</p><Source value={hit.context} />
      </details>)}
      <h3>Nearby source and tests</h3>
      {!results.neighbors.length && <p>No additional graph context found for these results.</p>}
      {results.neighbors.map((neighbor, n) => <details key={n}><summary>{neighbor.context.name} · {neighbor.relation} · {neighbor.confidence}</summary>
        <p>{neighbor.direction} of {neighbor.anchor}. {neighbor.reason}</p><Source value={neighbor.context} /></details>)}
    </>}
  </section>
}
