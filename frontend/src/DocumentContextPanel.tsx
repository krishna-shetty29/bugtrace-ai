import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'

type Options = { snapshotId: string; commit: string; modulePath: string; files: { path: string; bytes: number }[]; previousReports: { id: string; title: string }[] }
type Status = { indexes: { id: string; state: string; manifest: unknown; statistics: Record<string, number>; technical_error: string | null }[] }
type Reference = { indexId: string; chunkNumber: number; origin: string; sourceRef: string; sourceRevision: string; lineKind: string; startLine: number; endLine: number; documentHash: string; chunkHash: string; title: string; text: string }
type Source = { key: string; filePath: string; startLine: number; endLine: number; name: string; text: string; executionId: string | null }
type Result = { currentSnapshot: { snapshotId: string; semanticUsed: boolean; contexts: { context: Source; channels: Record<string, number> }[]; neighbors: { relation: string; confidence: string; context: Source }[]; notices: string[] }; supplementarySemanticUsed: boolean; supplementary: { reference: Reference; channels: Record<string, number>; score: number }[]; notices: string[] }
const checkboxStyle = { display: 'inline', width: 'auto', marginRight: 8 }
function originLabel(origin: string) {
  if (origin.startsWith('HISTORICAL')) return `${origin === 'HISTORICAL_DIFF' ? 'Historical diff' : 'Historical commit message'} — change record, not current source`
  if (origin === 'PREVIOUS_REPORT') return 'Previous report — recorded claims and outcome'
  if (origin.startsWith('EXECUTION')) return 'Saved execution evidence'
  return origin === 'SNAPSHOT_CONFIG' ? 'Captured configuration' : 'Captured documentation'
}
export default function DocumentContextPanel({ investigationId }: { investigationId: string }) {
  const [options, setOptions] = useState<Options | null>(null)
  const [paths, setPaths] = useState<string[]>([])
  const [reports, setReports] = useState<string[]>([])
  const [commits, setCommits] = useState('')
  const [execution, setExecution] = useState(true)
  const [history, setHistory] = useState(false)
  const [summaries, setSummaries] = useState(false)
  const [semantic, setSemantic] = useState(true)
  const [status, setStatus] = useState<Status | null>(null)
  const [result, setResult] = useState<Result | null>(null)
  const [query, setQuery] = useState('charge')
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [citation, setCitation] = useState<Reference | null>(null)
  const active = useRef<AbortController | null>(null)
  useEffect(() => () => active.current?.abort(), [])
  async function request<T>(suffix: string, signal: AbortSignal, body?: unknown): Promise<T> {
    const response = await fetch(`/api/investigations/${investigationId}${suffix}`, { signal, method: body === undefined ? 'GET' : 'POST', headers: body === undefined ? { Accept: 'application/json' } : { Accept: 'application/json', 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
    const text = await response.text()
    if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 1800)}`)
    return JSON.parse(text) as T
  }
  async function run(action: string, reference?: Reference) {
    const controller = new AbortController(); active.current?.abort(); active.current = controller; setBusy(action); setError('')
    try {
      if (action === 'sources') setOptions(await request<Options>('/context-sources', controller.signal))
      else if (action === 'status') setStatus(await request<Status>('/context-index', controller.signal))
      else if (action === 'build') setStatus(await request<Status>('/context-index', controller.signal, { documentationPaths: paths, commitIds: commits.split(/\s+/).filter(Boolean), summaryInvestigationIds: reports, includeExecutionEvidence: execution }))
      else if (action === 'citation' && reference) setCitation(await request<Reference>(`/context-citations/${reference.indexId}/${reference.chunkNumber}`, controller.signal))
      else if (action === 'retrieve') setResult(await request<Result>(`/context?${new URLSearchParams({ q: query, history: String(history), summaries: String(summaries), semantic: String(semantic) })}`, controller.signal))
    } catch (cause) { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : 'Context request failed') }
    finally { if (!controller.signal.aborted) setBusy('') }
  }
  function toggle(value: string, list: string[], update: (items: string[]) => void) { update(list.includes(value) ? list.filter(item => item !== value) : [...list, value]) }
  function retrieve(event: FormEvent<HTMLFormElement>) { event.preventDefault(); void run('retrieve') }
  function downloadContext() {
    const url = URL.createObjectURL(new Blob([JSON.stringify(result, null, 2)], { type: 'application/json' }))
    const link = document.createElement('a'); link.href = url; link.download = `context-${investigationId}.json`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  return <section style={{ overflowWrap: 'anywhere' }} aria-labelledby="document-context-heading">
    <h2 id="document-context-heading">Documentation, history and evidence</h2>
    <p>Select captured documents and optional history. Only commits at or before this snapshot are accepted. History and previous reports stay separate from current source.</p>
    <button type="button" disabled={!!busy} onClick={() => void run('sources')}>Load available sources</button>{' '}
    <button type="button" disabled={!!busy} onClick={() => void run('status')}>Load context status</button>
    {options && <details open><summary>Choose sources</summary><p>Snapshot: <code>{options.snapshotId}</code><br />Commit: <code>{options.commit}</code><br />Module: {options.modulePath}</p>
      <h3>Captured README and configuration files</h3>
      {!options.files.some(file => /readme/i.test(file.path)) && <p>No README was captured in this module. Existing files are listed below; do not add files to the immutable base.</p>}
      {options.files.map(file => <label key={file.path}><input style={checkboxStyle} type="checkbox" checked={paths.includes(file.path)} onChange={() => toggle(file.path, paths, setPaths)} />{file.path} ({file.bytes} bytes)</label>)}
      <label>Selected commit hashes, one per line<textarea value={commits} onChange={event => setCommits(event.target.value)} placeholder="Leave empty to omit history" /></label>
      <button type="button" onClick={() => setCommits(options.commit)}>Use captured snapshot commit</button>
      <p>Select at most five full commit hashes. Descendants, other branches outside the snapshot ancestry, and merge commits are rejected.</p>
      <h3>Previous reports for this exact snapshot</h3>
      {!options.previousReports.length && <p>No older reports are available for this snapshot.</p>}
      {options.previousReports.map(report => <label key={report.id}><input style={checkboxStyle} type="checkbox" checked={reports.includes(report.id)} onChange={() => toggle(report.id, reports, setReports)} />{report.title}</label>)}
      <label><input style={checkboxStyle} type="checkbox" checked={execution} onChange={event => setExecution(event.target.checked)} />Include the latest five completed executions for this investigation</label>
      <button type="button" disabled={!!busy} onClick={() => void run('build')}>Build selected context</button>
      <p>Build again with the same selections to check hash-based reuse. Removing a selection and rebuilding removes it from the active collection.</p>
    </details>}
    {busy && <p role="status">{busy === 'build' ? 'Indexing selected context… keep this page open.' : 'Loading…'}</p>}
    {error && <p role="alert" className="error">{error}</p>}
    {status && <><h3>Saved collections</h3>{!status.indexes.length && <p>No collection built yet.</p>}{status.indexes.map((index, n) => <details key={index.id} open={n === 0}><summary>{index.state}</summary>
      {index.technical_error && <p className="error">{index.technical_error}</p>}<pre>{JSON.stringify(index.statistics, null, 2)}</pre><details><summary>Source hashes and revisions</summary><pre>{JSON.stringify(index.manifest, null, 2)}</pre></details>
    </details>)}</>}
    <form onSubmit={retrieve}><label>Retrieve report context<input required minLength={2} maxLength={200} value={query} onChange={event => setQuery(event.target.value)} /></label>
      <label><input style={checkboxStyle} type="checkbox" checked={semantic} onChange={event => setSemantic(event.target.checked)} />Include semantic matches</label>
      <label><input style={checkboxStyle} type="checkbox" checked={history} onChange={event => setHistory(event.target.checked)} />Include selected historical changes</label>
      <label><input style={checkboxStyle} type="checkbox" checked={summaries} onChange={event => setSummaries(event.target.checked)} />Include selected previous reports</label>
      <button disabled={!!busy}>Retrieve report context</button>
    </form>
    {result && <><button type="button" onClick={downloadContext}>Download retrieved context JSON</button><h3>Current captured snapshot and its execution logs</h3><p>Snapshot: <code>{result.currentSnapshot.snapshotId}</code> · Current semantic matches used: {result.currentSnapshot.semanticUsed ? 'Yes' : 'No'}</p>
      {result.currentSnapshot.notices.map((notice, n) => <p key={n}>{notice}</p>)}
      {result.currentSnapshot.contexts.map((hit, n) => <details key={hit.context.key} open={n < 2}><summary>{hit.context.name} · {Object.keys(hit.channels).join(' + ')}</summary><p>{hit.context.filePath} · lines {hit.context.startLine}–{hit.context.endLine}{hit.context.executionId && <> · <a href={`#/executions/${hit.context.executionId}`}>Saved execution</a></>}</p><pre>{hit.context.text}</pre></details>)}
      <details><summary>Nearby callers, callees and tests</summary>{result.currentSnapshot.neighbors.map((neighbor, n) => <details key={n}><summary>{neighbor.context.name} · {neighbor.relation} · {neighbor.confidence}</summary><p>{neighbor.context.filePath}:{neighbor.context.startLine}–{neighbor.context.endLine}</p><pre>{neighbor.context.text}</pre></details>)}</details>
      <h3>Selected documentation and other evidence</h3><p>Supplementary semantic matches used: {result.supplementarySemanticUsed ? 'Yes' : 'No'}</p>
      {result.notices.map((notice, n) => <p key={n}>{notice}</p>)}
      {!result.supplementary.length && <p>No supplementary matches. Check your selected collection and retrieval filters.</p>}
      {result.supplementary.map(hit => <details key={`${hit.reference.indexId}:${hit.reference.chunkNumber}`}><summary>{originLabel(hit.reference.origin)} · {hit.reference.title}</summary>
        <p>Source: {hit.reference.sourceRef}<br />Revision: <code>{hit.reference.sourceRevision}</code><br />{hit.reference.lineKind}: {hit.reference.startLine}–{hit.reference.endLine}<br />Document hash: <code>{hit.reference.documentHash}</code></p>
        <p>Matched by: {Object.keys(hit.channels).join(' + ')}</p><pre>{hit.reference.text}</pre><button type="button" disabled={!!busy} onClick={() => void run('citation', hit.reference)}>Verify saved citation</button>
      </details>)}
    </>}
    {citation && <details open><summary>Saved citation retrieved</summary><p>{originLabel(citation.origin)} · {citation.sourceRef} · {citation.lineKind} {citation.startLine}–{citation.endLine}</p><p>Chunk SHA-256: <code>{citation.chunkHash}</code></p><pre>{citation.text}</pre></details>}
  </section>
}
